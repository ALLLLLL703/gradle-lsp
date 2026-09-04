package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GradleLanguageServerTest {
    @Test
    fun `initialize advertises full document synchronization`() {
        val server = GradleLanguageServer()

        val result = server.initialize(InitializeParams()).join()

        assertEquals(TextDocumentSyncKind.Full, result.capabilities.textDocumentSync.left)
        assertEquals("Gradle LSP", result.serverInfo.name)
    }

    @Test
    fun `orderly shutdown exits successfully`() {
        val server = GradleLanguageServer()

        server.shutdown().join()
        server.exit()

        assertEquals(0, server.exitCode().join())
    }

    @Test
    fun `exit without shutdown is unsuccessful`() {
        val server = GradleLanguageServer()

        server.exit()

        assertEquals(1, server.exitCode().join())
    }

    @Test
    fun `shutdown does not terminate the server before exit`() {
        val server = GradleLanguageServer()

        server.shutdown().join()

        assertFalse(server.exitCode().isDone)
    }
}
