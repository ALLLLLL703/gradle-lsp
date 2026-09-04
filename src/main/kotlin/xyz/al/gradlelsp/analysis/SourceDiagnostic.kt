package xyz.al.gradlelsp.analysis

internal enum class SourceDiagnosticSeverity {
    ERROR,
    WARNING,
    INFORMATION,
}

internal enum class SourceDiagnosticKind {
    SYNTAX,
    SEMANTIC,
}

internal data class SourceDiagnostic(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val severity: SourceDiagnosticSeverity,
    val kind: SourceDiagnosticKind,
)

internal interface DocumentAnalyzer : AutoCloseable {
    fun analyze(fileName: String, text: String): List<SourceDiagnostic>

    override fun close() = Unit
}
