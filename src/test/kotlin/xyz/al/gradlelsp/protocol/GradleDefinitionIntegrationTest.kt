package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.SourceDiagnostic
import xyz.al.gradlelsp.documents.DocumentStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleDefinitionIntegrationTest {
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
        val noAnalysis = object : DocumentAnalyzer {
            override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> = emptyList()
        }
        val textDocuments = GradleTextDocumentService(documents = documents, analyzer = noAnalysis)

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
}
