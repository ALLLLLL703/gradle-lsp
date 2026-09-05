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
    fun map(documentText: String, completions: SourceCompletions): CompletionList {
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
                        SourceCompletionKind.KEYWORD -> CompletionItemKind.Keyword
                    }
                    detail = "(${candidate.kind.name.lowercase()}) ${candidate.qualifiedName}"
                    filterText = candidate.name
                    sortText = candidate.qualifiedName
                    insertTextFormat = InsertTextFormat.PlainText
                    textEdit = Either.forLeft(
                        TextEdit(
                            Range(lines.positionAt(candidate.startOffset), lines.positionAt(candidate.endOffset)),
                            candidate.insertText,
                        ),
                    )
                }
            },
        )
    }
}
