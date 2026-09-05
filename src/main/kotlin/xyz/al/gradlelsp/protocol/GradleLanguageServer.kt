package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class GradleLanguageServer(
    logger: ServerLogger = ServerLogger.standardError(),
    private val textDocuments: GradleTextDocumentService = GradleTextDocumentService(logger = logger),
    private val workspace: WorkspaceService = GradleWorkspaceService(),
) : LanguageServer, LanguageClientAware, AutoCloseable {
    private val shutdownRequested = AtomicBoolean(false)
    private val exitCode = CompletableFuture<Int>()

    override fun connect(client: LanguageClient) {
        textDocuments.connect(client)
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities()
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
        capabilities.setDeclarationProvider(true)
        capabilities.setDefinitionProvider(true)
        capabilities.setHoverProvider(true)
        capabilities.completionProvider = CompletionOptions(false, listOf("."))
        capabilities.setTypeDefinitionProvider(true)
        capabilities.setImplementationProvider(true)
        capabilities.setReferencesProvider(true)
        capabilities.setDocumentSymbolProvider(true)
        capabilities.experimental = mapOf(
            "gradleLsp" to mapOf(
                "externalDocument" to mapOf(
                    "uriScheme" to EXTERNAL_DOCUMENT_URI_SCHEME,
                    "request" to EXTERNAL_DOCUMENT_REQUEST,
                ),
            ),
        )
        val workspaceRoots = params.workspaceFolders
            ?.map { folder -> folder.uri }
            .orEmpty()
            .ifEmpty {
                listOfNotNull(
                    params.rootUri
                        ?: params.rootPath?.let { rootPath ->
                            Path.of(rootPath).toAbsolutePath().normalize().toUri().toASCIIString()
                        },
                )
            }
        textDocuments.configureWorkspaceRoots(workspaceRoots)

        textDocuments.configureCompletion(params.capabilities?.textDocument?.completion?.completionItem?.snippetSupport == true)
        val documentSymbols = params.capabilities?.textDocument?.documentSymbol
        textDocuments.configureDocumentSymbols(
            hierarchical = documentSymbols?.hierarchicalDocumentSymbolSupport == true,
            supportedKinds = documentSymbols?.symbolKind?.valueSet,
        )

        val result = InitializeResult(capabilities)
        result.serverInfo = ServerInfo("Gradle LSP", "0.1.0-SNAPSHOT")
        return CompletableFuture.completedFuture(result)
    }

    @JsonRequest(EXTERNAL_DOCUMENT_REQUEST)
    fun externalDocument(params: ExternalDocumentParams): CompletableFuture<ExternalDocumentContent?> =
        CompletableFuture.completedFuture(
            textDocuments.externalDocument(params.uri)?.let { document ->
                ExternalDocumentContent(document.uri, document.languageId, document.text)
            },
        )

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

    override fun close() = textDocuments.close()

    private companion object {
        const val EXTERNAL_DOCUMENT_URI_SCHEME = "gradle-lsp"
        const val EXTERNAL_DOCUMENT_REQUEST = "gradle-lsp/externalDocument"
    }
}

internal data class ExternalDocumentParams(
    val uri: String,
)

internal data class ExternalDocumentContent(
    val uri: String,
    val languageId: String,
    val text: String,
)
