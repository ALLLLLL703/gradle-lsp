package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.al.gradlelsp.navigation.SourceDefinition
import xyz.al.gradlelsp.navigation.SourceHover
import java.net.URI

internal object LspHoverMapper {
    fun map(documentText: String, hover: SourceHover): Hover {
        val contents = mutableListOf<Either<String, MarkedString>>()
        contents += Either.forRight(MarkedString("kotlin", hover.signature))
        hover.documentation?.takeIf(String::isNotBlank)?.let { documentation ->
            contents += Either.forLeft(documentation)
        }
        hover.source?.let { source ->
            contents += Either.forLeft(sourceDescription(source))
        }
        val lines = Utf16LineMap(documentText)
        return Hover(
            contents,
            Range(
                lines.positionAt(hover.startOffset),
                lines.positionAt(hover.endOffset),
            ),
        )
    }

    fun empty(): Hover = Hover(emptyList())

    private fun sourceDescription(source: SourceDefinition): String {
        val displayName = runCatching {
            URI.create(source.uri).path.substringAfterLast('/').ifBlank { "source" }
        }.getOrDefault("source")
        return "Source: *[${escapeLabel(displayName)}](${source.uri})*"
    }

    private fun escapeLabel(value: String): String =
        value.replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
}
