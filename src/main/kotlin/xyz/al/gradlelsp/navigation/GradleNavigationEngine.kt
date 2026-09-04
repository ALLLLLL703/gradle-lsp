package xyz.al.gradlelsp.navigation

import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.GradleDsl
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

    override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
        check(!closed.get()) { "Gradle navigation engine is closed" }
        val engine = synchronized(this) {
            GradleDsl.detect(document.fileName)?.let(engines::get)
        }
        return engine?.definitions(document, offset).orEmpty()
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

internal fun defaultGradleNavigationEngine(): GradleNavigationEngine =
    GradleNavigationEngine().use(GradleDsl.KOTLIN, KotlinFileNavigationEngine())
