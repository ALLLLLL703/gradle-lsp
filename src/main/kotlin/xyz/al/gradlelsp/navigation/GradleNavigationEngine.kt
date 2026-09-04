package xyz.al.gradlelsp.navigation

import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.GradleDsl
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.documents.GradleWorkspaceDocumentSource
import xyz.al.gradlelsp.documents.WorkspaceDocumentSource
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

/** Routes shared navigation requests to the engine installed for a Gradle DSL. */
internal class GradleNavigationEngine(
    engines: Map<GradleDsl, DocumentNavigationEngine> = emptyMap(),
) : DocumentNavigationEngine {
    private val closed = AtomicBoolean(false)
    private val engines = EnumMap<GradleDsl, DocumentNavigationEngine>(GradleDsl::class.java).apply {
        putAll(engines)
    }

    @Synchronized
    fun use(dsl: GradleDsl, engine: DocumentNavigationEngine): GradleNavigationEngine {
        check(!closed.get()) { "Gradle navigation engine is closed" }
        val previous = engines.put(dsl, engine)
        if (previous !== engine && previous != null) {
            previous.close()
        }
        return this
    }

    override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> =
        engineFor(document)?.definitions(document, offset).orEmpty()

    override fun declarations(document: AnalysisDocument, offset: Int): List<SourceDefinition> =
        engineFor(document)?.declarations(document, offset).orEmpty()

    override fun typeDefinitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> =
        engineFor(document)?.typeDefinitions(document, offset).orEmpty()

    override fun references(
        document: AnalysisDocument,
        offset: Int,
        includeDeclaration: Boolean,
    ): List<SourceDefinition> =
        engineFor(document)?.references(document, offset, includeDeclaration).orEmpty()

    override fun implementations(document: AnalysisDocument, offset: Int): List<SourceDefinition> =
        engineFor(document)?.implementations(document, offset).orEmpty()

    private fun engineFor(document: AnalysisDocument): DocumentNavigationEngine? {
        check(!closed.get()) { "Gradle navigation engine is closed" }
        return synchronized(this) {
            GradleDsl.detect(document.fileName)?.let(engines::get)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val installed = synchronized(this) {
                engines.values.toSet().also { engines.clear() }
            }
            installed.forEach(DocumentNavigationEngine::close)
        }
    }
}

internal fun defaultGradleNavigationEngine(
    externalDocuments: ExternalDocumentStore = ExternalDocumentStore(),
    workspaceDocuments: WorkspaceDocumentSource = GradleWorkspaceDocumentSource(DocumentStore()),
): GradleNavigationEngine =
    GradleNavigationEngine().use(
        GradleDsl.KOTLIN,
        KotlinFileNavigationEngine(
            externalDocuments = externalDocuments,
            workspaceDocuments = workspaceDocuments,
        ),
    )
