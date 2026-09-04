package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.defaultGradleAnalysisEngine
import xyz.al.gradlelsp.documents.DocumentSnapshot
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocument
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.navigation.DocumentNavigationEngine
import xyz.al.gradlelsp.navigation.defaultGradleNavigationEngine
import xyz.al.gradlelsp.presentation.LspDefinitionMapper
import xyz.al.gradlelsp.presentation.LspDocumentSymbolMapper
import xyz.al.gradlelsp.presentation.LspDiagnosticMapper
import xyz.al.gradlelsp.presentation.Utf16LineMap
import xyz.al.gradlelsp.symbols.DocumentSymbolEngine
import xyz.al.gradlelsp.symbols.defaultGradleDocumentSymbolEngine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class GradleTextDocumentService(
    private val documents: DocumentStore = DocumentStore(),
    private val externalDocuments: ExternalDocumentStore = ExternalDocumentStore(),
    private val analyzer: DocumentAnalyzer = defaultGradleAnalysisEngine(),
    private val navigation: DocumentNavigationEngine = defaultGradleNavigationEngine(externalDocuments),
    private val symbolEngine: DocumentSymbolEngine = defaultGradleDocumentSymbolEngine(),
    private val logger: ServerLogger = ServerLogger.standardError(),
    private val analysisExecutor: ExecutorService = newAnalysisExecutor(),
    private val navigationExecutor: ExecutorService = newNavigationExecutor(),
    private val symbolExecutor: ExecutorService = newSymbolExecutor(),
) : TextDocumentService, AutoCloseable {
    @Volatile
    private var client: LanguageClient? = null

    @Volatile
    private var hierarchicalDocumentSymbols = false

    @Volatile
    private var supportedDocumentSymbolKinds = LspDocumentSymbolMapper.legacyKinds

    fun connect(client: LanguageClient) {
        this.client = client
    }

    fun configureDocumentSymbols(
        hierarchical: Boolean,
        supportedKinds: List<SymbolKind>?,
    ) {
        hierarchicalDocumentSymbols = hierarchical
        supportedDocumentSymbolKinds = supportedKinds?.toSet()
            ?: LspDocumentSymbolMapper.legacyKinds
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

    fun externalDocument(uri: String): ExternalDocument? = externalDocuments.find(uri)

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        CompletableFuture.supplyAsync(
            {
                try {
                    val snapshot = documents.current(params.textDocument.uri)
                        ?: return@supplyAsync emptyDefinitions()
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync emptyDefinitions()
                    val definitions = navigation.definitions(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                    )
                    if (!documents.isCurrent(snapshot)) return@supplyAsync emptyDefinitions()

                    Either.forLeft(definitions.map(LspDefinitionMapper::map))
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: definition failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyDefinitions()
                }
            },
            navigationExecutor,
        )

    override fun declaration(
        params: DeclarationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        CompletableFuture.supplyAsync(
            {
                try {
                    val snapshot = documents.current(params.textDocument.uri)
                        ?: return@supplyAsync emptyDefinitions()
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync emptyDefinitions()
                    val declarations = navigation.declarations(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                    )
                    if (!documents.isCurrent(snapshot)) return@supplyAsync emptyDefinitions()

                    Either.forLeft(declarations.map(LspDefinitionMapper::map))
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: declaration failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyDefinitions()
                }
            },
            navigationExecutor,
        )

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
        CompletableFuture.supplyAsync(
            {
                try {
                    val snapshot = documents.current(params.textDocument.uri)
                        ?: return@supplyAsync emptyList()
                    val symbols = symbolEngine.symbols(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                    )
                    if (!documents.isCurrent(snapshot)) return@supplyAsync emptyList()

                    if (hierarchicalDocumentSymbols) {
                        LspDocumentSymbolMapper.hierarchical(
                            snapshot.text,
                            symbols,
                            supportedDocumentSymbolKinds,
                        )
                    } else {
                        LspDocumentSymbolMapper.flat(
                            snapshot.uri,
                            snapshot.text,
                            symbols,
                            supportedDocumentSymbolKinds,
                        )
                    }
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: document symbols failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyList()
                }
            },
            symbolExecutor,
        )

    private fun schedule(snapshot: DocumentSnapshot) {
        analysisExecutor.execute {
            try {
                val diagnostics = analyzer.analyze(
                    AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                )
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
        shutdown(analysisExecutor)
        shutdown(navigationExecutor)
        shutdown(symbolExecutor)
        analyzer.close()
        navigation.close()
        symbolEngine.close()
    }

    private fun shutdown(executor: ExecutorService) {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private fun emptyDefinitions(): Either<List<Location>, List<LocationLink>> =
        Either.forLeft(emptyList())

    private companion object {
        fun newAnalysisExecutor(): ExecutorService = newExecutor("gradle-lsp-analysis")

        fun newNavigationExecutor(): ExecutorService = newExecutor("gradle-lsp-navigation")

        fun newSymbolExecutor(): ExecutorService = newExecutor("gradle-lsp-symbols")

        fun newExecutor(threadName: String): ExecutorService =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, threadName).apply { isDaemon = true }
            }
    }
}
