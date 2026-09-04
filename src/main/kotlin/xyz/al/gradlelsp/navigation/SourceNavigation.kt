package xyz.al.gradlelsp.navigation

import xyz.al.gradlelsp.analysis.AnalysisDocument

internal data class SourceDefinition(
    val uri: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal interface DocumentNavigationEngine : AutoCloseable {
    fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition>

    override fun close() = Unit
}
