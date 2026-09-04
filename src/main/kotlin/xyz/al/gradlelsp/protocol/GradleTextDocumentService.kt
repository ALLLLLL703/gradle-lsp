package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService

internal class GradleTextDocumentService : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) = Unit

    override fun didChange(params: DidChangeTextDocumentParams) = Unit

    override fun didClose(params: DidCloseTextDocumentParams) = Unit

    override fun didSave(params: DidSaveTextDocumentParams) = Unit
}
