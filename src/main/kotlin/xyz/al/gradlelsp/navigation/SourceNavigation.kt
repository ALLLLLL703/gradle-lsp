package xyz.al.gradlelsp.navigation

import xyz.al.gradlelsp.analysis.AnalysisDocument

internal data class SourceDefinition(
    val uri: String,
    val sourceText: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal interface DocumentNavigationEngine : AutoCloseable {
    fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition>

    fun declarations(document: AnalysisDocument, offset: Int): List<SourceDefinition> =
        definitions(document, offset)

    fun typeDefinitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> = emptyList()

    fun references(
        document: AnalysisDocument,
        offset: Int,
        includeDeclaration: Boolean,
    ): List<SourceDefinition> = emptyList()

    override fun close() = Unit
}
