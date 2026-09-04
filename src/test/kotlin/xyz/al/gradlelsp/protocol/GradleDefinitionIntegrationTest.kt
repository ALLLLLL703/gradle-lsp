package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.SourceDiagnostic
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.navigation.DocumentNavigationEngine
import xyz.al.gradlelsp.navigation.SourceDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleDefinitionIntegrationTest {
    @Test
    fun `external definition handles expose their in-memory content`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, "val answer = externalAnswer") }
        val externalDocuments = ExternalDocumentStore()
        val external = externalDocuments.register(
            origin = "fixture.jar!/fixture/Api.kt",
            displayName = "Api.kt",
            languageId = "kotlin",
            text = "package fixture\n\nval externalAnswer = 42\n",
        )
        val targetOffset = external.text.indexOf("externalAnswer")
        val navigation = object : DocumentNavigationEngine {
            override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> =
                listOf(
                    SourceDefinition(
                        external.uri,
                        external.text,
                        targetOffset,
                        targetOffset + "externalAnswer".length,
                    ),
                )
        }
        val textDocuments = GradleTextDocumentService(
            documents = documents,
            externalDocuments = externalDocuments,
            analyzer = noAnalysis(),
            navigation = navigation,
        )

        GradleLanguageServer(textDocuments = textDocuments).use { server ->
            val experimental = server.initialize(InitializeParams()).join()
                .capabilities.experimental as Map<*, *>
            val gradleLsp = experimental["gradleLsp"] as Map<*, *>
            val externalCapability = gradleLsp["externalDocument"] as Map<*, *>
            assertEquals("gradle-lsp", externalCapability["uriScheme"])
            assertEquals("gradle-lsp/externalDocument", externalCapability["request"])
            assertTrue(
                ServiceEndpoints.getSupportedMethods(GradleLanguageServer::class.java)
                    .containsKey("gradle-lsp/externalDocument"),
            )
            val location = textDocuments.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(0, 15)),
            ).join().left.single()
            val content = server.externalDocument(ExternalDocumentParams(location.uri)).join()

            assertEquals(external.uri, location.uri)
            assertEquals("kotlin", content?.languageId)
            assertEquals(external.text, content?.text)
            assertEquals(null, server.externalDocument(ExternalDocumentParams("gradle-lsp://source/missing")).join())
        }
    }

    @Test
    fun `document symbols preserve recovered PSI hierarchy and negotiate kinds`() {
        val text = """
            val emoji = "😀"; val after = 1
            object Registry
            enum class Mode { FAST }
            class Box(val value: Int) {
                fun doubled(): Int {
                    val local = value * 2
                    return local
                }
                constructor() : this(0)
            }
            val broken =
            fun recovered() = 42
            plugins { id("java") }
        """.trimIndent()
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())
        val initialize = InitializeParams().apply {
            capabilities = ClientCapabilities().apply {
                textDocument = TextDocumentClientCapabilities().apply {
                    documentSymbol = DocumentSymbolCapabilities().apply {
                        hierarchicalDocumentSymbolSupport = true
                    }
                }
            }
        }

        GradleLanguageServer(textDocuments = textDocuments).use { server ->
            val capabilities = server.initialize(initialize).join().capabilities
            assertTrue(capabilities.documentSymbolProvider.left)

            val response = textDocuments.documentSymbol(
                DocumentSymbolParams(TextDocumentIdentifier(uri)),
            ).join()
            assertTrue(response.all { it.isRight })
            val symbols = response.map { it.right }
            assertEquals(
                listOf("emoji", "after", "Registry", "Mode", "Box", "broken", "recovered"),
                symbols.map { it.name },
            )
            assertEquals(Position(0, text.indexOf("after")), symbols[1].selectionRange.start)
            assertEquals(SymbolKind.Class, symbols.single { it.name == "Registry" }.kind)
            assertEquals(SymbolKind.Constant, symbols.single { it.name == "Mode" }.children.single().kind)

            val box = symbols.single { it.name == "Box" }
            assertEquals(listOf("value", "doubled", "constructor"), box.children.map { it.name })
            assertTrue(box.range.start.isAtOrBefore(box.selectionRange.start))
            assertTrue(box.selectionRange.end.isAtOrBefore(box.range.end))
            assertTrue(symbols.none { it.name == "plugins" || it.name == "local" })

            textDocuments.configureDocumentSymbols(
                hierarchical = false,
                supportedKinds = SymbolKind.entries,
            )
            val flat = textDocuments.documentSymbol(
                DocumentSymbolParams(TextDocumentIdentifier(uri)),
            ).join()
            assertTrue(flat.all { it.isLeft })
            assertEquals("Box", flat.map { it.left }.single { it.name == "doubled" }.containerName)
            assertEquals(SymbolKind.Object, flat.map { it.left }.single { it.name == "Registry" }.kind)
            assertEquals(SymbolKind.EnumMember, flat.map { it.left }.single { it.name == "FAST" }.kind)
            assertTrue(flat.map { it.left }.none { it.name == "local" })
        }
    }

    @Test
    fun `references stream workspace scripts use open overlays and preserve constructor overloads`() {
        val project = Files.createTempDirectory("gradle-lsp-references")
        try {
            val settings = project.resolve("settings.gradle.kts")
            val script = project.resolve("build.gradle.kts")
            val overlayScript = project.resolve("overlay.gradle.kts")
            val diskScript = project.resolve("disk.gradle.kts")
            Files.writeString(settings, "rootProject.name = \"fixture\"\n")
            val text = """
                class Box {
                    constructor(value: Int) { println(value) }
                    constructor(value: CharSequence) { println(value) }
                }
                val broken =
                val marker = "😀"; val current: String = "current"
                val first = Box(1)
                val recovered = Box(2)
                val other = Box("x")
            """.trimIndent()
            Files.writeString(script, text)
            Files.writeString(overlayScript, "val stale: Int = 1\n")
            Files.writeString(diskScript, "val fromDisk: String = \"disk\"\n")

            val documents = DocumentStore().apply {
                open(script.toUri().toString(), 1, text)
                open(overlayScript.toUri().toString(), 1, "val fromOverlay: String = \"open\"\n")
            }
            val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())
            val initialize = InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(project.toUri().toString(), "fixture"))
            }

            GradleLanguageServer(textDocuments = textDocuments).use { server ->
                val capabilities = server.initialize(initialize).join().capabilities
                assertTrue(capabilities.referencesProvider.left)

                val stringReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(5, 33),
                        ReferenceContext(false),
                    ),
                ).join()
                assertEquals(
                    setOf(script.toUri().toString(), overlayScript.toUri().toString(), diskScript.toUri().toString()),
                    stringReferences.map { location -> location.uri }.toSet(),
                )
                assertEquals(3, stringReferences.size)
                assertEquals(
                    Position(5, 32),
                    stringReferences.single { location -> location.uri == script.toUri().toString() }.range.start,
                )
                assertEquals(
                    Position(0, 17),
                    stringReferences.single { location -> location.uri == overlayScript.toUri().toString() }.range.start,
                )

                val constructorParams = ReferenceParams(
                    TextDocumentIdentifier(script.toUri().toString()),
                    Position(1, 8),
                    ReferenceContext(false),
                )
                val calls = textDocuments.references(constructorParams).join()
                assertEquals(
                    listOf(Position(6, 12), Position(7, 16)),
                    calls.map { location -> location.range.start },
                )

                constructorParams.context = ReferenceContext(true)
                val callsAndDeclaration = textDocuments.references(constructorParams).join()
                assertEquals(listOf(1, 6, 7), callsAndDeclaration.map { location -> location.range.start.line })
                assertEquals(Position(1, 4), callsAndDeclaration.first().range.start)
                assertEquals(Position(1, 15), callsAndDeclaration.first().range.end)
            }
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace references are discarded when an open candidate changes`() {
        val target = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val candidate = Path.of("settings.gradle.kts").toAbsolutePath().normalize()
        val targetText = "val answer = 42\nanswer\n"
        val candidateText = "val copy = answer\n"
        val documents = DocumentStore().apply {
            open(target.toUri().toString(), 1, targetText)
            open(candidate.toUri().toString(), 1, candidateText)
        }
        val enteredNavigation = CountDownLatch(1)
        val continueNavigation = CountDownLatch(1)
        val navigation = object : DocumentNavigationEngine {
            override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> = emptyList()

            override fun references(
                document: AnalysisDocument,
                offset: Int,
                includeDeclaration: Boolean,
            ): List<SourceDefinition> {
                enteredNavigation.countDown()
                check(continueNavigation.await(5, TimeUnit.SECONDS))
                return listOf(SourceDefinition(candidate.toUri().toString(), candidateText, 11, 17))
            }
        }
        val textDocuments = GradleTextDocumentService(
            documents = documents,
            analyzer = noAnalysis(),
            navigation = navigation,
        )

        textDocuments.use {
            val response = textDocuments.references(
                ReferenceParams(
                    TextDocumentIdentifier(target.toUri().toString()),
                    Position(1, 1),
                    ReferenceContext(false),
                ),
            )
            assertTrue(enteredNavigation.await(5, TimeUnit.SECONDS))
            documents.replace(candidate.toUri().toString(), 2, "val copy = 0\n")
            continueNavigation.countDown()

            assertTrue(response.join().isEmpty())
        }
    }

    @Test
    fun `stale definition is discarded after an identical close and reopen`() {
        val text = "val answer = 42\nanswer\n"
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val enteredNavigation = CountDownLatch(1)
        val continueNavigation = CountDownLatch(1)
        val navigation = object : DocumentNavigationEngine {
            override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
                enteredNavigation.countDown()
                check(continueNavigation.await(5, TimeUnit.SECONDS))
                return listOf(SourceDefinition(uri, text, 4, 10))
            }
        }
        val textDocuments = GradleTextDocumentService(
            documents = documents,
            analyzer = noAnalysis(),
            navigation = navigation,
        )

        GradleLanguageServer(textDocuments = textDocuments).use {
            val response = textDocuments.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(1, 1)),
            )
            assertTrue(enteredNavigation.await(5, TimeUnit.SECONDS))
            documents.close(uri)
            documents.open(uri, 1, text)
            continueNavigation.countDown()

            assertTrue(response.join().left.isEmpty())
        }
    }

    @Test
    fun `definition declaration and type definition resolve recovered symbols from UTF-16 positions`() {
        val text = """
            class Answer
            val answer = Answer()
            val broken =
            val marker = "😀"; answer
        """.trimIndent()
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())

        GradleLanguageServer(textDocuments = textDocuments).use { server ->
            val capabilities = server.initialize(InitializeParams()).join().capabilities
            assertTrue(capabilities.declarationProvider.left)
            assertTrue(capabilities.definitionProvider.left)
            assertTrue(capabilities.typeDefinitionProvider.left)

            val position = Position(3, 19)
            val definition = textDocuments.definition(
                DefinitionParams(TextDocumentIdentifier(uri), position),
            ).join().left.single()
            val declaration = textDocuments.declaration(
                DeclarationParams(TextDocumentIdentifier(uri), position),
            ).join().left.single()
            val typeDefinition = textDocuments.typeDefinition(
                TypeDefinitionParams(TextDocumentIdentifier(uri), position),
            ).join().left.single()

            assertEquals(uri, definition.uri)
            assertEquals(Position(1, 4), definition.range.start)
            assertEquals(Position(1, 10), definition.range.end)
            assertEquals(definition, declaration)
            assertEquals(Position(0, 6), typeDefinition.range.start)
            assertEquals(Position(0, 12), typeDefinition.range.end)
        }
    }

    private fun Position.isAtOrBefore(other: Position): Boolean =
        line < other.line || line == other.line && character <= other.character

    private fun noAnalysis(): DocumentAnalyzer = object : DocumentAnalyzer {
        override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> = emptyList()
    }
}
