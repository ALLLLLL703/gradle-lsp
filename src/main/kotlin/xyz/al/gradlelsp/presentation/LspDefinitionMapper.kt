package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import xyz.al.gradlelsp.navigation.SourceDefinition

internal object LspDefinitionMapper {
    fun map(sourceText: String, definition: SourceDefinition): Location {
        val lines = Utf16LineMap(sourceText)
        return Location(
            definition.uri,
            Range(
                lines.positionAt(definition.startOffset),
                lines.positionAt(definition.endOffset),
            ),
        )
    }
}
