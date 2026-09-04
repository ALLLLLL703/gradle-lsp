package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.SourceDiagnostic
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.navigation.DocumentNavigationEngine
import xyz.al.gradlelsp.navigation.SourceDefinition
import java.nio.file.Path
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
    fun `definition resolves a recovered local symbol from an LSP UTF-16 position`() {
        val text = """
            val answer = 42
            val broken =
            val marker = "😀"; answer
        """.trimIndent()
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val uri = script.toUri().toString()
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis())

        GradleLanguageServer(textDocuments = textDocuments).use { server ->
            val capabilities = server.initialize(InitializeParams()).join().capabilities
            assertTrue(capabilities.definitionProvider.left)

            val response = textDocuments.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(2, 19)),
            ).join()
            val location = response.left.single()

            assertEquals(uri, location.uri)
            assertEquals(Position(0, 4), location.range.start)
            assertEquals(Position(0, 10), location.range.end)
        }
    }

    private fun noAnalysis(): DocumentAnalyzer = object : DocumentAnalyzer {
        override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> = emptyList()
    }
}
