package xyz.al.gradlelsp.completion

import xyz.al.gradlelsp.analysis.AnalysisDocument

internal enum class SourceCompletionKind {
    PACKAGE, CLASS, INTERFACE, ENUM, ENUM_MEMBER, TYPE_ALIAS, TYPE_PARAMETER,
    VARIABLE, PROPERTY, FUNCTION, METHOD, KEYWORD,
}

internal data class SourceCompletionItem(
    val name: String,
    val qualifiedName: String,
    val insertText: String,
    val startOffset: Int,
    val endOffset: Int,
    val kind: SourceCompletionKind = SourceCompletionKind.PACKAGE,
    val detail: String? = null,
    val sortText: String = qualifiedName,
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
    fun complete(document: AnalysisDocument, offset: Int): SourceCompletions = SourceCompletions.EMPTY
}
