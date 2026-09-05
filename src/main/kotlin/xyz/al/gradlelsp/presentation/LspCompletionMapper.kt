package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.al.gradlelsp.completion.SourceCompletions
import xyz.al.gradlelsp.completion.SourceCompletionKind

internal object LspCompletionMapper {
    fun map(documentText: String, completions: SourceCompletions, snippetSupport: Boolean = false): CompletionList {
        val lines = Utf16LineMap(documentText)
        return CompletionList(
            completions.isIncomplete,
            completions.items.map { candidate ->
                val label = candidate.name + if (candidate.kind == SourceCompletionKind.PACKAGE) "." else ""
                CompletionItem(label).apply {
                    kind = when (candidate.kind) {
                        SourceCompletionKind.PACKAGE -> CompletionItemKind.Module
                        SourceCompletionKind.CLASS -> CompletionItemKind.Class
                        SourceCompletionKind.INTERFACE -> CompletionItemKind.Interface
                        SourceCompletionKind.ENUM -> CompletionItemKind.Enum
                        SourceCompletionKind.ENUM_MEMBER -> CompletionItemKind.EnumMember
                        SourceCompletionKind.TYPE_ALIAS -> CompletionItemKind.Class
                        SourceCompletionKind.TYPE_PARAMETER -> CompletionItemKind.TypeParameter
                        SourceCompletionKind.VARIABLE -> CompletionItemKind.Variable
                        SourceCompletionKind.PROPERTY -> CompletionItemKind.Property
                        SourceCompletionKind.FUNCTION -> CompletionItemKind.Function
                        SourceCompletionKind.METHOD -> CompletionItemKind.Method
                        SourceCompletionKind.PARAMETER -> CompletionItemKind.Variable
                        SourceCompletionKind.KEYWORD -> CompletionItemKind.Keyword
                    }
                    detail = candidate.detail ?: "(${candidate.kind.name.lowercase()}) ${candidate.qualifiedName}"
                    filterText = candidate.name
                    sortText = candidate.sortText
                    insertTextFormat = if (snippetSupport && candidate.snippetText != null) InsertTextFormat.Snippet else InsertTextFormat.PlainText
                    textEdit = Either.forLeft(
                        TextEdit(
                            Range(lines.positionAt(candidate.startOffset), lines.positionAt(candidate.endOffset)),
                            if (snippetSupport) candidate.snippetText ?: candidate.insertText else candidate.insertText,
                        ),
                    )
                }
            },
        )
    }
}
