package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeDefinitionParams
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
import xyz.al.gradlelsp.documents.GradleWorkspaceDocumentSource
import xyz.al.gradlelsp.navigation.DocumentNavigationEngine
import xyz.al.gradlelsp.navigation.defaultGradleNavigationEngine
import xyz.al.gradlelsp.presentation.LspCompletionMapper
import xyz.al.gradlelsp.presentation.LspDefinitionMapper
import xyz.al.gradlelsp.presentation.LspDocumentSymbolMapper
import xyz.al.gradlelsp.presentation.LspDiagnosticMapper
import xyz.al.gradlelsp.presentation.LspHoverMapper
import xyz.al.gradlelsp.presentation.Utf16LineMap
import xyz.al.gradlelsp.symbols.DocumentSymbolEngine
import xyz.al.gradlelsp.symbols.defaultGradleDocumentSymbolEngine
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class GradleTextDocumentService(
    private val documents: DocumentStore = DocumentStore(),
    private val externalDocuments: ExternalDocumentStore = ExternalDocumentStore(),
    private val workspaceDocuments: GradleWorkspaceDocumentSource = GradleWorkspaceDocumentSource(documents),
    private val analyzer: DocumentAnalyzer = defaultGradleAnalysisEngine(),
    private val navigation: DocumentNavigationEngine = defaultGradleNavigationEngine(
        externalDocuments,
        workspaceDocuments,
    ),
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

    private val analysisLock = Any()
    private val pendingAnalysis = LinkedHashMap<String, DocumentSnapshot>()
    private var analysisWorkerScheduled = false

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

    fun configureWorkspaceRoots(rootUris: List<String>) {
        workspaceDocuments.configureRoots(rootUris)
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
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val snapshot = documents.current(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(emptyDefinitions())
        return supplyAsync(
            {
                try {
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
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val snapshot = documents.current(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(emptyCompletions())
        return supplyAsync(
            {
                try {
                    if (!documents.isCurrent(snapshot)) return@supplyAsync emptyCompletions()
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync emptyCompletions()
                    val completions = navigation.completeImports(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                    )
                    if (!documents.isCurrent(snapshot)) return@supplyAsync emptyCompletions()

                    Either.forRight(LspCompletionMapper.map(snapshot.text, completions))
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: import completion failed for ${snapshot.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyCompletions()
                }
            },
            navigationExecutor,
        )
    }

    private fun emptyCompletions(): Either<List<CompletionItem>, CompletionList> =
        Either.forRight(CompletionList(false, emptyList()))

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        val snapshot = documents.current(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(LspHoverMapper.empty())
        return supplyAsync(
            {
                try {
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync LspHoverMapper.empty()
                    val hover = navigation.hover(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                    ) ?: return@supplyAsync LspHoverMapper.empty()
                    if (!documents.isCurrent(snapshot)) return@supplyAsync LspHoverMapper.empty()

                    LspHoverMapper.map(snapshot.text, hover)
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: hover failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    LspHoverMapper.empty()
                }
            },
            navigationExecutor,
        )
    }

    override fun declaration(
        params: DeclarationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val snapshot = documents.current(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(emptyDefinitions())
        return supplyAsync(
            {
                try {
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
    }

    override fun implementation(
        params: ImplementationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val capture = documents.capture(params.textDocument.uri)
        val snapshot = capture.snapshot ?: return CompletableFuture.completedFuture(emptyDefinitions())
        return supplyAsync(
            {
                try {
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync emptyDefinitions()
                    val implementations = navigation.implementations(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                    )
                    if (!documents.isCurrent(snapshot) || documents.revision() != capture.revision) {
                        return@supplyAsync emptyDefinitions()
                    }

                    Either.forLeft(implementations.map(LspDefinitionMapper::map))
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: implementation failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyDefinitions()
                }
            },
            navigationExecutor,
        )
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        val capture = documents.capture(params.textDocument.uri)
        val snapshot = capture.snapshot ?: return CompletableFuture.completedFuture(emptyList())
        return supplyAsync(
            {
                try {
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync emptyList()
                    val references = navigation.references(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                        params.context.isIncludeDeclaration,
                    )
                    if (!documents.isCurrent(snapshot) || documents.revision() != capture.revision) {
                        return@supplyAsync emptyList()
                    }

                    references.map(LspDefinitionMapper::map)
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: references failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyList()
                }
            },
            navigationExecutor,
        )
    }

    override fun typeDefinition(
        params: TypeDefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val snapshot = documents.current(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(emptyDefinitions())
        return supplyAsync(
            {
                try {
                    val offset = Utf16LineMap(snapshot.text).offsetAt(params.position)
                        ?: return@supplyAsync emptyDefinitions()
                    val definitions = navigation.typeDefinitions(
                        AnalysisDocument(snapshot.uri, snapshot.fileName, snapshot.text),
                        offset,
                    )
                    if (!documents.isCurrent(snapshot)) return@supplyAsync emptyDefinitions()

                    Either.forLeft(definitions.map(LspDefinitionMapper::map))
                } catch (failure: Exception) {
                    logger.log(
                        "gradle-lsp: type definition failed for ${params.textDocument.uri}: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                    emptyDefinitions()
                }
            },
            navigationExecutor,
        )
    }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val snapshot = documents.current(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(emptyList())
        return supplyAsync(
            {
                try {
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
    }

    private fun schedule(snapshot: DocumentSnapshot) {
        val launchWorker = synchronized(analysisLock) {
            pendingAnalysis[snapshot.uri] = snapshot
            if (analysisWorkerScheduled) {
                false
            } else {
                analysisWorkerScheduled = true
                true
            }
        }
        if (!launchWorker) return

        try {
            analysisExecutor.execute(::drainAnalysis)
        } catch (_: RejectedExecutionException) {
            synchronized(analysisLock) {
                analysisWorkerScheduled = false
                pendingAnalysis.clear()
            }
            logger.log("gradle-lsp: analysis queue is closed; diagnostics were not scheduled")
        }
    }

    private fun drainAnalysis() {
        while (true) {
            val snapshot = synchronized(analysisLock) {
                val entries = pendingAnalysis.entries.iterator()
                if (!entries.hasNext()) {
                    analysisWorkerScheduled = false
                    null
                } else {
                    val pending = entries.next().value
                    entries.remove()
                    pending
                }
            } ?: return
            analyze(snapshot)
        }
    }

    private fun analyze(snapshot: DocumentSnapshot) {
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

    override fun close() {
        synchronized(analysisLock) { pendingAnalysis.clear() }
        if (shutdown(analysisExecutor)) analyzer.close()
        if (shutdown(navigationExecutor)) navigation.close()
        if (shutdown(symbolExecutor)) symbolEngine.close()
    }

    private fun shutdown(executor: ExecutorService): Boolean {
        executor.shutdown()
        if (executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) return true
        executor.shutdownNow()
        return executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    private fun <T> supplyAsync(
        action: () -> T,
        executor: ExecutorService,
    ): CompletableFuture<T> =
        try {
            CompletableFuture.supplyAsync({ action() }, executor)
        } catch (failure: RejectedExecutionException) {
            logger.log("gradle-lsp: request queue is full; request was rejected")
            CompletableFuture.failedFuture(failure)
        }

    private fun emptyDefinitions(): Either<List<Location>, List<LocationLink>> =
        Either.forLeft(emptyList())

    private companion object {
        fun newAnalysisExecutor(): ExecutorService = newExecutor("gradle-lsp-analysis")

        fun newNavigationExecutor(): ExecutorService = newExecutor("gradle-lsp-navigation")

        fun newSymbolExecutor(): ExecutorService = newExecutor("gradle-lsp-symbols")

        fun newExecutor(threadName: String): ExecutorService =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(MAXIMUM_PENDING_TASKS),
                { task -> Thread(task, threadName).apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )

        const val MAXIMUM_PENDING_TASKS = 16
        const val EXECUTOR_SHUTDOWN_SECONDS = 5L
    }
}
