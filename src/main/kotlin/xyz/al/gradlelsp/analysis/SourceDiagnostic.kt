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
    val source: String,
)

internal data class AnalysisDocument(
    val uri: String,
    val fileName: String,
    val text: String,
)

internal interface DocumentAnalyzer : AutoCloseable {
    fun analyze(document: AnalysisDocument): List<SourceDiagnostic>

    override fun close() = Unit
}
