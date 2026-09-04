package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
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
