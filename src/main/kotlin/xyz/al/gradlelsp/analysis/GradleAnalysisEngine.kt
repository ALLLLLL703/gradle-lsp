package xyz.al.gradlelsp.analysis

import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

internal enum class GradleDsl {
    KOTLIN,
    GROOVY,
    ;

    fun accepts(fileName: String): Boolean =
        when (this) {
            KOTLIN -> fileName.endsWith(".gradle.kts")
            GROOVY -> fileName.endsWith(".gradle")
        }

    companion object {
        fun detect(fileName: String): GradleDsl? = entries.firstOrNull { it.accepts(fileName) }
    }
}

/**
 * Shared analysis entry point. Protocol scheduling and diagnostic presentation stay outside this
 * class; adding another DSL only requires installing a different engine for that DSL.
 */
internal class GradleAnalysisEngine(
    engines: Map<GradleDsl, DocumentAnalyzer> = emptyMap(),
) : DocumentAnalyzer {
    private val closed = AtomicBoolean(false)
    private val engines = EnumMap<GradleDsl, DocumentAnalyzer>(GradleDsl::class.java).apply {
        putAll(engines)
    }

    @Synchronized
    fun use(dsl: GradleDsl, engine: DocumentAnalyzer): GradleAnalysisEngine {
        check(!closed.get()) { "Gradle analysis engine is closed" }
        val previous = engines.put(dsl, engine)
        if (previous !== engine && previous != null) {
            previous.close()
        }
        return this
    }

    override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> {
        check(!closed.get()) { "Gradle analysis engine is closed" }
        val engine = synchronized(this) {
            GradleDsl.detect(document.fileName)?.let(engines::get)
        }
        return engine?.analyze(document).orEmpty()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val installed = synchronized(this) {
                engines.values.toSet().also { engines.clear() }
            }
            installed.forEach(DocumentAnalyzer::close)
        }
    }
}

internal fun defaultGradleAnalysisEngine(): GradleAnalysisEngine =
    GradleAnalysisEngine().use(GradleDsl.KOTLIN, KotlinGradleDslAnalyzer())
