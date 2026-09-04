@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
import java.util.concurrent.atomic.AtomicBoolean

/** A Kotlin compiler PSI file backed by its AST node tree. */
internal class ParsedKotlinFile internal constructor(
    val psi: KtFile,
) {
    fun syntaxDiagnostics(): List<SourceDiagnostic> =
        PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
            .map { error ->
                SourceDiagnostic(
                    startOffset = error.textRange.startOffset,
                    endOffset = error.textRange.endOffset,
                    message = error.errorDescription,
                    severity = SourceDiagnosticSeverity.ERROR,
                    kind = SourceDiagnosticKind.SYNTAX,
                    source = "kotlin-psi",
                )
            }

    fun diagnosticRangeAt(offset: Int): Pair<Int, Int> {
        if (psi.textLength == 0) return 0 to 0

        val boundedOffset = offset.coerceIn(0, psi.textLength - 1)
        val elementAtOffset = psi.findElementAt(boundedOffset)
        val diagnosticElement = if (elementAtOffset is PsiWhiteSpace) {
            PsiTreeUtil.nextLeaf(elementAtOffset, true) ?: elementAtOffset
        } else {
            elementAtOffset
        }
        val range = diagnosticElement?.textRange ?: return offset to offset
        return range.startOffset to range.endOffset
    }
}

internal class KotlinAstParser : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val disposable = Disposer.newDisposable("gradle-lsp-kotlin-psi")

    @Suppress("DEPRECATION_ERROR")
    private val configuration = CompilerConfiguration.create().apply {
        put(CommonConfigurationKeys.MODULE_NAME, "gradle-lsp-kotlin-psi")
        add(
            ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS,
            ScriptingCompilerConfigurationComponentRegistrar(),
        )
    }
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        configuration,
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val psiFactory = KtPsiFactory(environment.project)

    @Synchronized
    fun parse(fileName: String, text: String): ParsedKotlinFile {
        check(!closed.get()) { "Kotlin AST parser is closed" }
        return ParsedKotlinFile(psiFactory.createPhysicalFile(fileName, text))
    }

    @Synchronized
    @Suppress("DEPRECATION_ERROR")
    fun bindingContext(file: ParsedKotlinFile): BindingContext {
        check(!closed.get()) { "Kotlin AST parser is closed" }
        val trace = CliBindingTrace(environment.project)
        return TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            environment.project,
            listOf(file.psi),
            trace,
            configuration,
            environment::createPackagePartProvider,
        ).bindingContext
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Disposer.dispose(disposable)
        }
    }
}

internal class KotlinGradleDslAnalyzer(
    private val parser: KotlinAstParser = KotlinAstParser(),
    private val semanticAnalyzer: KotlinCompilerSemanticAnalyzer = KotlinCompilerSemanticAnalyzer(),
) : DocumentAnalyzer {
    override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> {
        val parsedFile = parser.parse(document.fileName, document.text)
        val syntaxDiagnostics = parsedFile.syntaxDiagnostics()
        val semanticDiagnostics = runCatching {
            semanticAnalyzer.analyze(document, parsedFile, syntaxDiagnostics)
        }.getOrElse { failure ->
            listOf(
                SourceDiagnostic(
                    startOffset = 0,
                    endOffset = 0,
                    message = "Gradle semantic analysis is unavailable: " +
                        (failure.message ?: failure::class.java.simpleName),
                    severity = SourceDiagnosticSeverity.WARNING,
                    kind = SourceDiagnosticKind.SEMANTIC,
                    source = "gradle-model",
                ),
            )
        }
        return syntaxDiagnostics + semanticDiagnostics
    }

    override fun close() = parser.close()
}
