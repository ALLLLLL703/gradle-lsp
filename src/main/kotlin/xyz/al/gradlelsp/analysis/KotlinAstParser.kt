@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.assignment.plugin.AssignmentComponentRegistrar
import org.jetbrains.kotlin.assignment.plugin.AssignmentConfigurationKeys
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.samWithReceiver.SamWithReceiverComponentRegistrar
import org.jetbrains.kotlin.samWithReceiver.SamWithReceiverConfigurationKeys
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
import org.jetbrains.kotlin.scripting.configuration.ScriptingConfigurationKeys
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.displayName
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm

internal data class KotlinScriptAnalysisContext(
    val classPath: List<Path>,
    val implicitImports: List<String>,
    val baseClassName: String,
    val implicitReceiverClassName: String,
)

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

internal class KotlinAstParser(
    private val scriptContext: KotlinScriptAnalysisContext? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val disposable = Disposer.newDisposable("gradle-lsp-kotlin-psi")
    private val scriptClassLoader = scriptContext?.let { context ->
        URLClassLoader(
            context.classPath.map { path -> path.toUri().toURL() }.toTypedArray(),
            KotlinAstParser::class.java.classLoader,
        )
    }

    @Suppress("DEPRECATION_ERROR")
    private val configuration = CompilerConfiguration.create().apply {
        put(CommonConfigurationKeys.MODULE_NAME, "gradle-lsp-kotlin-psi")
        put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")))
        put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)
        add(
            ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS,
            ScriptingCompilerConfigurationComponentRegistrar(),
        )
        scriptContext?.let { context ->
            addJvmClasspathRoots(context.classPath.map(Path::toFile))
            add(
                ScriptingConfigurationKeys.SCRIPT_DEFINITIONS,
                gradleScriptDefinition(context),
            )
            add(
                CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS,
                SamWithReceiverComponentRegistrar(),
            )
            add(
                SamWithReceiverConfigurationKeys.SAM_WITH_RECEIVER_ANNOTATION,
                "org.gradle.api.HasImplicitReceiver",
            )
            add(
                CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS,
                AssignmentComponentRegistrar(),
            )
            add(
                AssignmentConfigurationKeys.ASSIGNMENT_ANNOTATION,
                "org.gradle.api.SupportsKotlinAssignmentOverloading",
            )
        }
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
    fun parseJava(fileName: String, text: String): PsiJavaFile? {
        check(!closed.get()) { "Kotlin AST parser is closed" }
        return PsiFileFactory.getInstance(environment.project)
            .createFileFromText(fileName, JavaLanguage.INSTANCE, text) as? PsiJavaFile
    }

    @Synchronized
    fun subPackages(parent: FqName): List<FqName> {
        check(!closed.get()) { "Kotlin AST parser is closed" }
        val scope = GlobalSearchScope.allScope(environment.project)
        // Only classpath PSI packages: the general facade also calls Kotlin light-class finders,
        // which require a fully analysed module and are unnecessary for import path completion.
        val finder = PsiElementFinderImpl(environment.project)
        val psiPackage = finder.findPackage(parent.asString()) ?: return emptyList()
        return finder.getSubPackages(psiPackage, scope).map { child -> FqName(child.qualifiedName) }
    }

    @Synchronized
    @Suppress("DEPRECATION_ERROR")
    fun bindingContext(file: ParsedKotlinFile): BindingContext = bindingContext(listOf(file))

    @Synchronized
    @Suppress("DEPRECATION_ERROR")
    fun bindingContext(files: List<ParsedKotlinFile>): BindingContext {
        check(!closed.get()) { "Kotlin AST parser is closed" }
        val trace = CliBindingTrace(environment.project)
        return TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            environment.project,
            files.map(ParsedKotlinFile::psi),
            trace,
            configuration,
            environment::createPackagePartProvider,
        ).bindingContext
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Disposer.dispose(disposable)
            scriptClassLoader?.close()
        }
    }

    private fun gradleScriptDefinition(context: KotlinScriptAnalysisContext): ScriptDefinition {
        val hostConfiguration = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm {
                baseClassLoader(requireNotNull(scriptClassLoader))
            }
        }
        val compilationConfiguration = ScriptCompilationConfiguration {
            hostConfiguration(hostConfiguration)
            displayName("Gradle Kotlin DSL")
            fileExtension("kts")
            baseClass(KotlinType(context.baseClassName))
            implicitReceivers.append(KotlinType(context.implicitReceiverClassName))
            defaultImports.append(context.implicitImports)
        }
        return ScriptDefinition.FromConfigurations(
            hostConfiguration,
            compilationConfiguration,
            evaluationConfiguration = null,
        )
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
