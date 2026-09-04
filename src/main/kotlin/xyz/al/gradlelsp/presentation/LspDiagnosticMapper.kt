package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import xyz.al.gradlelsp.analysis.SourceDiagnostic
import xyz.al.gradlelsp.analysis.SourceDiagnosticSeverity

internal object LspDiagnosticMapper {
    fun map(text: String, diagnostics: List<SourceDiagnostic>): List<Diagnostic> {
        val lineMap = Utf16LineMap(text)
        return diagnostics.map { diagnostic ->
            Diagnostic(
                Range(
                    lineMap.positionAt(diagnostic.startOffset),
                    lineMap.positionAt(diagnostic.endOffset),
                ),
                diagnostic.message,
                diagnostic.severity.toLspSeverity(),
                diagnostic.source,
            )
        }
    }

    private fun SourceDiagnosticSeverity.toLspSeverity(): DiagnosticSeverity =
        when (this) {
            SourceDiagnosticSeverity.ERROR -> DiagnosticSeverity.Error
            SourceDiagnosticSeverity.WARNING -> DiagnosticSeverity.Warning
            SourceDiagnosticSeverity.INFORMATION -> DiagnosticSeverity.Information
        }
}
