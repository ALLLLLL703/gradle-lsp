package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.KotlinAstAnalyzer
import xyz.al.gradlelsp.documents.DocumentSnapshot
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.presentation.LspDiagnosticMapper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class GradleTextDocumentService(
    private val documents: DocumentStore = DocumentStore(),
    private val analyzer: DocumentAnalyzer = KotlinAstAnalyzer(),
    private val logger: ServerLogger = ServerLogger.standardError(),
    private val analysisExecutor: ExecutorService = newAnalysisExecutor(),
) : TextDocumentService, AutoCloseable {
    @Volatile
    private var client: LanguageClient? = null

    fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val document = params.textDocument
        schedule(documents.open(document.uri, document.version, document.text))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val change = params.contentChanges.singleOrNull()
        if (change == null || change.range != null) {
            logger.log("gradle-lsp: ignored non-full document change for ${params.textDocument.uri}")
            return
        }

        documents.replace(params.textDocument.uri, params.textDocument.version, change.text)?.let(::schedule)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val closed = documents.close(params.textDocument.uri) ?: return
        client?.publishDiagnostics(PublishDiagnosticsParams(closed.uri, emptyList(), closed.version))
    }

    override fun didSave(params: DidSaveTextDocumentParams) = Unit

    private fun schedule(snapshot: DocumentSnapshot) {
        analysisExecutor.execute {
            try {
                val diagnostics = analyzer.analyze(snapshot.fileName, snapshot.text)
                if (documents.isCurrent(snapshot)) {
                    client?.publishDiagnostics(
                        PublishDiagnosticsParams(
                            snapshot.uri,
                            LspDiagnosticMapper.map(snapshot.text, diagnostics),
                            snapshot.version,
                        ),
                    )
                }
            } catch (failure: Exception) {
                logger.log(
                    "gradle-lsp: analysis failed for ${snapshot.uri}: " +
                        (failure.message ?: failure::class.java.simpleName),
                )
            }
        }
    }

    override fun close() {
        analysisExecutor.shutdown()
        if (!analysisExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            analysisExecutor.shutdownNow()
        }
        analyzer.close()
    }

    private companion object {
        fun newAnalysisExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "gradle-lsp-analysis").apply { isDaemon = true }
            }
    }
}
