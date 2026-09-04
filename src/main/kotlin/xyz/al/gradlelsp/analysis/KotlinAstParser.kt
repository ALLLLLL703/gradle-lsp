package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.util.concurrent.atomic.AtomicBoolean

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
}

internal class KotlinAstParser : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val disposable = Disposer.newDisposable("gradle-lsp-kotlin-psi")
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration(),
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val psiFactory = KtPsiFactory(environment.project)

    @Synchronized
    fun parse(fileName: String, text: String): ParsedKotlinFile {
        check(!closed.get()) { "Kotlin AST parser is closed" }
        return ParsedKotlinFile(psiFactory.createFile(fileName, text))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Disposer.dispose(disposable)
        }
    }
}

internal class KotlinAstAnalyzer(
    private val parser: KotlinAstParser = KotlinAstParser(),
) : DocumentAnalyzer {
    override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> =
        parser.parse(document.fileName, document.text).syntaxDiagnostics()

    override fun close() = parser.close()
}
