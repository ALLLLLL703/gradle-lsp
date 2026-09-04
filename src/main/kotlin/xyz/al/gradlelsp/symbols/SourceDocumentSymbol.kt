package xyz.al.gradlelsp.symbols

import xyz.al.gradlelsp.analysis.AnalysisDocument

internal enum class SourceSymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    OBJECT,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    PROPERTY,
    VARIABLE,
    CONSTANT,
    TYPE_ALIAS,
    ENUM_MEMBER,
}

internal data class SourceDocumentSymbol(
    val name: String,
    val kind: SourceSymbolKind,
    val startOffset: Int,
    val endOffset: Int,
    val selectionStartOffset: Int,
    val selectionEndOffset: Int,
    val children: List<SourceDocumentSymbol> = emptyList(),
)

internal interface DocumentSymbolEngine : AutoCloseable {
    fun symbols(document: AnalysisDocument): List<SourceDocumentSymbol>

    override fun close() = Unit
}
