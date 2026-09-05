package xyz.al.gradlelsp.completion

import xyz.al.gradlelsp.analysis.AnalysisDocument

internal enum class SourceCompletionKind { PACKAGE, CLASS, INTERFACE, ENUM, KEYWORD }

internal data class SourceCompletionItem(
    val name: String,
    val qualifiedName: String,
    val insertText: String,
    val startOffset: Int,
    val endOffset: Int,
    val kind: SourceCompletionKind = SourceCompletionKind.PACKAGE,
)

internal data class SourceCompletions(
    val items: List<SourceCompletionItem>,
    val isIncomplete: Boolean = false,
) {
    companion object {
        val EMPTY = SourceCompletions(emptyList())
    }
}

internal interface DocumentCompletionEngine {
    fun completeImports(document: AnalysisDocument, offset: Int): SourceCompletions = SourceCompletions.EMPTY
}
