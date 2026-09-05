package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.al.gradlelsp.completion.SourceCompletions

internal object LspCompletionMapper {
    fun map(documentText: String, completions: SourceCompletions): CompletionList {
        val lines = Utf16LineMap(documentText)
        return CompletionList(
            completions.isIncomplete,
            completions.items.map { candidate ->
                CompletionItem("${candidate.name}.").apply {
                    kind = CompletionItemKind.Module
                    detail = "(package) ${candidate.qualifiedName}"
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
