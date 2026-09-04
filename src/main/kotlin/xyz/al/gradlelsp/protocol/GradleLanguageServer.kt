package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class GradleLanguageServer(
    private val textDocuments: TextDocumentService = GradleTextDocumentService(),
    private val workspace: WorkspaceService = GradleWorkspaceService(),
) : LanguageServer, LanguageClientAware {
    private val shutdownRequested = AtomicBoolean(false)
    private val exitCode = CompletableFuture<Int>()

    @Volatile
    private var client: LanguageClient? = null

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities()
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)

        val result = InitializeResult(capabilities)
        result.serverInfo = ServerInfo("Gradle LSP", "0.1.0-SNAPSHOT")
        return CompletableFuture.completedFuture(result)
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested.set(true)
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        exitCode.complete(if (shutdownRequested.get()) 0 else 1)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments

    override fun getWorkspaceService(): WorkspaceService = workspace

    fun exitCode(): CompletableFuture<Int> = exitCode
}
