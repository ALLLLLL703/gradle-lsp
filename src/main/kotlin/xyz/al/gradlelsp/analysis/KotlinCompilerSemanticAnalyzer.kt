@file:Suppress("DEPRECATION")

package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.assignment.plugin.AssignmentComponentRegistrar
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.samWithReceiver.SamWithReceiverComponentRegistrar
import xyz.al.gradlelsp.gradle.GradleKotlinDslModel
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelProvider
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal class KotlinCompilerSemanticAnalyzer(
    private val modelProvider: GradleKotlinDslModelProvider = GradleKotlinDslModelLoader(),
) {
    fun analyze(
        document: AnalysisDocument,
        parsedFile: ParsedKotlinFile,
        syntaxDiagnostics: List<SourceDiagnostic>,
    ): List<SourceDiagnostic> {
        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        check(model.classPath.isNotEmpty()) { "Gradle returned an empty Kotlin DSL classpath for $script" }

        return withTemporaryDirectory { temporaryDirectory ->
            compile(document, parsedFile, syntaxDiagnostics, model, temporaryDirectory)
        }
    }

    private fun compile(
        document: AnalysisDocument,
        parsedFile: ParsedKotlinFile,
        syntaxDiagnostics: List<SourceDiagnostic>,
        model: GradleKotlinDslModel,
        temporaryDirectory: Path,
    ): List<SourceDiagnostic> {
        val template = KotlinGradleScriptTemplate.forFile(document.fileName)
        val imports = model.implicitImports.filter(String::isNotBlank).distinct()
        val prefix = buildString {
            imports.forEach { append("import ").append(it).append('\n') }
            append('\n')
        }
        val source = temporaryDirectory.resolve(template.fileName)
        Files.writeString(source, prefix + document.text)
        val output = Files.createDirectory(temporaryDirectory.resolve("classes"))
        val collector = KotlinCompilerMessageCollector(
            document.text,
            parsedFile,
            syntaxDiagnostics,
            prefixLineCount = imports.size + 1,
        )
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.toString())
            destination = output.toString()
            moduleName = "gradle-lsp-analysis"
            classpath = model.classPath.joinToString(File.pathSeparator)
            noStdlib = true
            noReflect = true
            jvmTarget = "21"
            script = true
            allowAnyScriptsInSourceRoots = true
            scriptTemplates = arrayOf(template.className)
            pluginClasspaths = arrayOf(
                compilerPluginPath(SamWithReceiverComponentRegistrar::class.java),
                compilerPluginPath(AssignmentComponentRegistrar::class.java),
            )
            pluginOptions = arrayOf(
                "plugin:kotlin.scripting:script-templates=${template.className}",
                "plugin:org.jetbrains.kotlin.samWithReceiver:annotation=org.gradle.api.HasImplicitReceiver",
                "plugin:org.jetbrains.kotlin.assignment:annotation=org.gradle.api.SupportsKotlinAssignmentOverloading",
            )
        }

        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        if (exitCode == ExitCode.INTERNAL_ERROR) {
            error(collector.failureMessage ?: "Kotlin compiler failed while analyzing ${document.fileName}")
        }
        return collector.sourceDiagnostics()
    }

    private fun compilerPluginPath(pluginClass: Class<*>): String =
        Path.of(pluginClass.protectionDomain.codeSource.location.toURI()).toString()

    private inline fun <T> withTemporaryDirectory(action: (Path) -> T): T {
        val directory = Files.createTempDirectory("gradle-lsp-kotlin-analysis-")
        return try {
            action(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}

internal enum class KotlinGradleScriptTemplate(
    val fileName: String,
    val className: String,
    val implicitReceiverClassName: String,
) {
    PROJECT(
        "build.gradle.kts",
        "org.gradle.kotlin.dsl.KotlinProjectScriptTemplate",
        "org.gradle.api.Project",
    ),
    SETTINGS(
        "settings.gradle.kts",
        "org.gradle.kotlin.dsl.KotlinSettingsScriptTemplate",
        "org.gradle.api.initialization.Settings",
    ),
    INIT(
        "init.gradle.kts",
        "org.gradle.kotlin.dsl.KotlinGradleScriptTemplate",
        "org.gradle.api.invocation.Gradle",
    ),
    ;

    companion object {
        fun forFile(fileName: String): KotlinGradleScriptTemplate =
            when {
                fileName == SETTINGS.fileName || fileName.endsWith(".settings.gradle.kts") -> SETTINGS
                fileName == INIT.fileName || fileName.endsWith(".init.gradle.kts") -> INIT
                else -> PROJECT
            }
    }
}

private data class KotlinCompilerMessage(
    val severity: CompilerMessageSeverity,
    val message: String,
    val location: CompilerMessageSourceLocation?,
)

private class KotlinCompilerMessageCollector(
    private val sourceText: String,
    private val parsedFile: ParsedKotlinFile,
    private val syntaxDiagnostics: List<SourceDiagnostic>,
    private val prefixLineCount: Int,
) : MessageCollector {
    private val messages = mutableListOf<KotlinCompilerMessage>()
    private val lines = SourceLines(sourceText)

    var failureMessage: String? = null
        private set

    override fun clear() {
        messages.clear()
        failureMessage = null
    }

    override fun hasErrors(): Boolean = messages.any { it.severity.isError }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity == CompilerMessageSeverity.EXCEPTION) {
            failureMessage = message
        }
        if (severity.isError || severity.isWarning) {
            messages += KotlinCompilerMessage(severity, message, location)
        }
    }

    fun sourceDiagnostics(): List<SourceDiagnostic> =
        messages.mapNotNull(::toSourceDiagnostic)
            .distinctBy { listOf(it.startOffset, it.endOffset, it.message, it.severity) }

    private fun toSourceDiagnostic(message: KotlinCompilerMessage): SourceDiagnostic? {
        val location = message.location ?: return null
        val sourceLine = location.line - prefixLineCount
        val startOffset = lines.offsetAt(sourceLine, location.column) ?: return null
        val compilerEndOffset = if (location.lineEnd > 0 && location.columnEnd > 0) {
            lines.offsetAt(location.lineEnd - prefixLineCount, location.columnEnd)
        } else {
            null
        }
        val (astStart, astEnd) = parsedFile.diagnosticRangeAt(startOffset)
        val endOffset = compilerEndOffset
            ?.takeIf { it >= startOffset }
            ?: astEnd
        val diagnostic = SourceDiagnostic(
            startOffset = astStart.coerceAtMost(startOffset),
            endOffset = endOffset.coerceAtLeast(startOffset),
            message = message.message,
            severity = if (message.severity.isError) {
                SourceDiagnosticSeverity.ERROR
            } else {
                SourceDiagnosticSeverity.WARNING
            },
            kind = SourceDiagnosticKind.SEMANTIC,
            source = "kotlin-compiler",
        )
        return diagnostic.takeUnless { candidate -> syntaxDiagnostics.any { overlaps(it, candidate) } }
    }

    private fun overlaps(left: SourceDiagnostic, right: SourceDiagnostic): Boolean =
        left.startOffset <= right.endOffset && right.startOffset <= left.endOffset
}

private class SourceLines(text: String) {
    private val text = text
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }

    fun offsetAt(oneBasedLine: Int, oneBasedColumn: Int): Int? {
        if (oneBasedLine !in 1..lineStarts.size || oneBasedColumn < 1) return null
        val lineStart = lineStarts[oneBasedLine - 1]
        val lineEnd = if (oneBasedLine < lineStarts.size) {
            lineStarts[oneBasedLine] - 1
        } else {
            text.length
        }
        return (lineStart + oneBasedColumn - 1).coerceAtMost(lineEnd)
    }
}
