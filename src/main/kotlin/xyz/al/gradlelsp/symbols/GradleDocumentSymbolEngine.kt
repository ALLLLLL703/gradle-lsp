package xyz.al.gradlelsp.symbols

import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.GradleDsl
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

/** Routes outline requests without coupling protocol code to a concrete Gradle DSL. */
internal class GradleDocumentSymbolEngine(
    engines: Map<GradleDsl, DocumentSymbolEngine> = emptyMap(),
) : DocumentSymbolEngine {
    private val closed = AtomicBoolean(false)
    private val engines = EnumMap<GradleDsl, DocumentSymbolEngine>(GradleDsl::class.java).apply {
        putAll(engines)
    }

    @Synchronized
    fun use(dsl: GradleDsl, engine: DocumentSymbolEngine): GradleDocumentSymbolEngine {
        check(!closed.get()) { "Gradle document symbol engine is closed" }
        val previous = engines.put(dsl, engine)
        if (previous !== engine && previous != null) previous.close()
        return this
    }

    override fun symbols(document: AnalysisDocument): List<SourceDocumentSymbol> {
        check(!closed.get()) { "Gradle document symbol engine is closed" }
        val engine = synchronized(this) {
            GradleDsl.detect(document.fileName)?.let(engines::get)
        }
        return engine?.symbols(document).orEmpty()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val installed = synchronized(this) {
                engines.values.toSet().also { engines.clear() }
            }
            installed.forEach(DocumentSymbolEngine::close)
        }
    }
}

internal fun defaultGradleDocumentSymbolEngine(): GradleDocumentSymbolEngine =
    GradleDocumentSymbolEngine().use(GradleDsl.KOTLIN, KotlinDocumentSymbolEngine())
