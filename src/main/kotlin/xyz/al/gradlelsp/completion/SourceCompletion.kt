package xyz.al.gradlelsp.completion

import xyz.al.gradlelsp.analysis.AnalysisDocument

internal data class SourcePackageCompletion(
    val name: String,
    val qualifiedName: String,
    val insertText: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class SourceCompletions(
    val items: List<SourcePackageCompletion>,
    val isIncomplete: Boolean = false,
) {
    companion object {
        val EMPTY = SourceCompletions(emptyList())
    }
}

internal interface DocumentCompletionEngine {
    fun completeImports(document: AnalysisDocument, offset: Int): SourceCompletions = SourceCompletions.EMPTY
}
