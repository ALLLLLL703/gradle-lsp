package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.SourceDiagnostic
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.navigation.DocumentNavigationEngine
import xyz.al.gradlelsp.navigation.SourceDefinition
import xyz.al.gradlelsp.navigation.SourceHover
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `external documents are evicted by retained text budget`() {
        val externalDocuments = ExternalDocumentStore(
            maximumEntries = 10,
            maximumEstimatedTextBytes = 8,
        )
        val first = externalDocuments.register("one", "One.kt", "kotlin", "1234")
        val second = externalDocuments.register("two", "Two.kt", "kotlin", "5678")

        assertEquals(null, externalDocuments.find(first.uri))
        assertEquals(second, externalDocuments.find(second.uri))
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
    fun `implementation finds transitive class and member implementations in recovered workspace scripts`() {
        val project = Files.createTempDirectory("gradle-lsp-implementations")
        try {
            val settings = project.resolve("settings.gradle.kts")
            val script = project.resolve("build.gradle.kts")
            val otherScript = project.resolve("jobs.gradle.kts")
            Files.writeString(settings, "rootProject.name = \"fixture\"\n")
            val text = """
                val broken =
                val typeTarget = Runnable::class
                val memberTarget = Runnable::run
                abstract class BaseJob : Runnable
                class LocalJob : BaseJob() { override fun run() = Unit }
            """.trimIndent()
            Files.writeString(script, text)
            Files.writeString(
                otherScript,
                """
                    interface MarkerJob : Runnable
                    object RemoteJob : Runnable { override fun run() = Unit }
                """.trimIndent(),
            )

            val documents = DocumentStore().apply { open(script.toUri().toString(), 1, text) }
            val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())
            val initialize = InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(project.toUri().toString(), "fixture"))
            }

            GradleLanguageServer(textDocuments = textDocuments).use { server ->
                val capabilities = server.initialize(initialize).join().capabilities
                assertTrue(capabilities.implementationProvider.left)

                val classImplementations = textDocuments.implementation(
                    ImplementationParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(1, 18),
                    ),
                ).join().left
                assertEquals(
                    listOf(
                        script.toUri().toString() to Position(3, 15),
                        script.toUri().toString() to Position(4, 6),
                        otherScript.toUri().toString() to Position(0, 10),
                        otherScript.toUri().toString() to Position(1, 7),
                    ),
                    classImplementations.map { location -> location.uri to location.range.start },
                )

                val memberImplementations = textDocuments.implementation(
                    ImplementationParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(2, 30),
                    ),
                ).join().left
                assertEquals(
                    listOf(
                        script.toUri().toString() to Position(4, 42),
                        otherScript.toUri().toString() to Position(1, 43),
                    ),
                    memberImplementations.map { location -> location.uri to location.range.start },
                )
            }
        } finally {
            project.toFile().deleteRecursively()
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
                interface Worker { fun work() }
                class WorkerImpl : Worker { override fun work() = Unit }
                val worker: Worker = WorkerImpl()
                worker.work()
                WorkerImpl().work()
                class PropertyBox(val payload: Int) { fun copy() = payload }
                val propertyBox = PropertyBox(1)
                propertyBox.payload
                class Meter(val amount: Int) { operator fun plus(other: Meter) = Meter(amount + other.amount) }
                val left = Meter(1)
                val total = left + Meter(2)
                class Shelf {
                    operator fun get(index: Int) = index
                    operator fun set(index: Int, value: Int) = Unit
                }
                val shelf = Shelf()
                val read = shelf[0]
                shelf[1] = 2
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

                val baseMemberReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(9, 24),
                        ReferenceContext(false),
                    ),
                ).join()
                assertEquals(listOf(Position(12, 7)), baseMemberReferences.map { location -> location.range.start })

                val overrideMemberReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(10, 42),
                        ReferenceContext(false),
                    ),
                ).join()
                assertEquals(
                    listOf(Position(13, 13)),
                    overrideMemberReferences.map { location -> location.range.start },
                )

                val constructorPropertyReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(14, 23),
                        ReferenceContext(false),
                    ),
                ).join()
                assertEquals(
                    listOf(Position(14, 51), Position(16, 12)),
                    constructorPropertyReferences.map { location -> location.range.start },
                )

                val plusDeclaration = Position(17, text.lines()[17].indexOf("plus") + 1)
                val plusOperator = Position(19, text.lines()[19].indexOf("+"))
                val operatorDefinition = textDocuments.definition(
                    DefinitionParams(TextDocumentIdentifier(script.toUri().toString()), plusOperator),
                ).join().left.single()
                assertEquals(Position(17, text.lines()[17].indexOf("plus")), operatorDefinition.range.start)

                val operatorReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        plusDeclaration,
                        ReferenceContext(false),
                    ),
                ).join()
                assertEquals(listOf(plusOperator), operatorReferences.map { location -> location.range.start })

                val getDeclaration = Position(21, text.lines()[21].indexOf("get") + 1)
                val setDeclaration = Position(22, text.lines()[22].indexOf("set") + 1)
                val readBracket = Position(25, text.lines()[25].indexOf("["))
                val writeBracket = Position(26, text.lines()[26].indexOf("["))
                val getDefinition = textDocuments.definition(
                    DefinitionParams(TextDocumentIdentifier(script.toUri().toString()), readBracket),
                ).join().left.single()
                val setDefinition = textDocuments.definition(
                    DefinitionParams(TextDocumentIdentifier(script.toUri().toString()), writeBracket),
                ).join().left.single()
                assertEquals(Position(21, text.lines()[21].indexOf("get")), getDefinition.range.start)
                assertEquals(Position(22, text.lines()[22].indexOf("set")), setDefinition.range.start)

                val getReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        getDeclaration,
                        ReferenceContext(false),
                    ),
                ).join()
                val setReferences = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        setDeclaration,
                        ReferenceContext(false),
                    ),
                ).join()
                assertEquals(listOf(readBracket), getReferences.map { location -> location.range.start })
                assertEquals(listOf(writeBracket), setReferences.map { location -> location.range.start })
            }
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace navigation falls back for an open script outside configured roots`() {
        val configuredRoot = Files.createTempDirectory("gradle-lsp-configured-root")
        val outsideRoot = Files.createTempDirectory("gradle-lsp-outside-root")
        try {
            Files.writeString(configuredRoot.resolve("settings.gradle.kts"), "rootProject.name = \"configured\"\n")
            Files.writeString(outsideRoot.resolve("settings.gradle.kts"), "rootProject.name = \"outside\"\n")
            val script = outsideRoot.resolve("build.gradle.kts")
            val text = "val target = 1\nval broken =\ntarget\n"
            Files.writeString(script, text)
            val documents = DocumentStore().apply { open(script.toUri().toString(), 1, text) }
            val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())
            val initialize = InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(configuredRoot.toUri().toString(), "configured"))
            }

            GradleLanguageServer(textDocuments = textDocuments).use { server ->
                server.initialize(initialize).join()
                val locations = textDocuments.references(
                    ReferenceParams(
                        TextDocumentIdentifier(script.toUri().toString()),
                        Position(0, 5),
                        ReferenceContext(true),
                    ),
                ).join()

                assertEquals(listOf(0, 2), locations.map { location -> location.range.start.line })
            }
        } finally {
            configuredRoot.toFile().deleteRecursively()
            outsideRoot.toFile().deleteRecursively()
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
    fun `diagnostic scheduling coalesces superseded full document snapshots`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val enteredFirstAnalysis = CountDownLatch(1)
        val continueFirstAnalysis = CountDownLatch(1)
        val analyzed = CopyOnWriteArrayList<String>()
        val analyzedLatest = CountDownLatch(1)
        val analyzer = object : DocumentAnalyzer {
            override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> {
                analyzed += document.text
                if (analyzed.size == 1) {
                    enteredFirstAnalysis.countDown()
                    check(continueFirstAnalysis.await(5, TimeUnit.SECONDS))
                } else {
                    analyzedLatest.countDown()
                }
                return emptyList()
            }
        }
        val textDocuments = GradleTextDocumentService(analyzer = analyzer)

        textDocuments.use {
            textDocuments.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, "version 1")),
            )
            assertTrue(enteredFirstAnalysis.await(5, TimeUnit.SECONDS))
            (2..20).forEach { version ->
                textDocuments.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri, version),
                        listOf(TextDocumentContentChangeEvent("version $version")),
                    ),
                )
            }
            continueFirstAnalysis.countDown()
            assertTrue(analyzedLatest.await(5, TimeUnit.SECONDS))
        }

        assertEquals(listOf("version 1", "version 20"), analyzed)
    }

    @Test
    fun `queued navigation keeps the document generation from request arrival`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val original = "val original = 1\noriginal\n"
        val replacement = "val replacement = 2\nreplacement\n"
        val documents = DocumentStore().apply { open(uri, 1, original) }
        val enteredFirstRequest = CountDownLatch(1)
        val continueFirstRequest = CountDownLatch(1)
        val calls = AtomicInteger()
        val navigation = object : DocumentNavigationEngine {
            override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
                if (calls.incrementAndGet() == 1) {
                    enteredFirstRequest.countDown()
                    check(continueFirstRequest.await(5, TimeUnit.SECONDS))
                }
                return listOf(SourceDefinition(uri, document.text, 4, document.text.indexOf(' ', 4)))
            }
        }
        val textDocuments = GradleTextDocumentService(
            documents = documents,
            analyzer = noAnalysis(),
            navigation = navigation,
        )

        textDocuments.use {
            val first = textDocuments.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(1, 1)),
            )
            assertTrue(enteredFirstRequest.await(5, TimeUnit.SECONDS))
            val queued = textDocuments.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(1, 1)),
            )
            documents.replace(uri, 2, replacement)
            continueFirstRequest.countDown()

            assertTrue(first.join().left.isEmpty())
            assertTrue(queued.join().left.isEmpty())
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
            typealias AnswerAlias = Answer
            val aliased: AnswerAlias = Answer()
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

            val expandedAliasType = textDocuments.typeDefinition(
                TypeDefinitionParams(TextDocumentIdentifier(uri), Position(5, 14)),
            ).join().left.single()
            assertEquals(Position(0, 6), expandedAliasType.range.start)
            assertEquals(Position(0, 12), expandedAliasType.range.end)
        }
    }

    @Test
    fun `hover exposes a Kotlin signature KDoc source and UTF-16 range after PSI recovery`() {
        val text = """
            /**
             * Doubles a value.
             *
             * @param value the input value
             * @return the doubled value
             */
            fun twice(value: Int): Int = value * 2
            val broken =
            val marker = "😀"; twice(21)
        """.trimIndent()
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())

        GradleLanguageServer(textDocuments = textDocuments).use { server ->
            val capabilities = server.initialize(InitializeParams()).join().capabilities
            assertTrue(capabilities.hoverProvider.left)

            val hover = textDocuments.hover(
                HoverParams(TextDocumentIdentifier(uri), Position(8, 20)),
            ).join()
            val contents = hover.contents.left
            val signature = contents.first().right

            assertEquals("kotlin", signature.language)
            assertTrue(signature.value.contains("fun twice(value: kotlin.Int): kotlin.Int"), signature.value)
            assertEquals(
                """
                    Doubles a value.

                    * **Parameters:**
                      * **value** the input value

                    * **Returns:**
                      * the doubled value
                """.trimIndent(),
                contents[1].left,
            )
            assertTrue(contents[2].left.startsWith("Source: *[build.gradle.kts](file:"))
            assertEquals(Position(8, 19), hover.range.start)
            assertEquals(Position(8, 24), hover.range.end)
        }
    }

    @Test
    fun `stale hover is discarded after the document changes`() {
        val text = "val answer = 42\nanswer\n"
        val replacement = "val answer = 43\nanswer\n"
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val enteredNavigation = CountDownLatch(1)
        val continueNavigation = CountDownLatch(1)
        val navigation = object : DocumentNavigationEngine {
            override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> = emptyList()

            override fun hover(document: AnalysisDocument, offset: Int): SourceHover {
                enteredNavigation.countDown()
                check(continueNavigation.await(5, TimeUnit.SECONDS))
                return SourceHover("val answer: kotlin.Int", null, null, 16, 22)
            }
        }
        val textDocuments = GradleTextDocumentService(
            documents = documents,
            analyzer = noAnalysis(),
            navigation = navigation,
        )

        textDocuments.use {
            val response = textDocuments.hover(
                HoverParams(TextDocumentIdentifier(uri), Position(1, 1)),
            )
            assertTrue(enteredNavigation.await(5, TimeUnit.SECONDS))
            documents.replace(uri, 2, replacement)
            continueNavigation.countDown()

            assertTrue(response.join().contents.left.isEmpty())
        }
    }

    private fun Position.isAtOrBefore(other: Position): Boolean =
        line < other.line || line == other.line && character <= other.character

    private fun noAnalysis(): DocumentAnalyzer = object : DocumentAnalyzer {
        override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> = emptyList()
    }
}
