package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

internal class StdioLanguageServer {
    fun run(input: InputStream, output: OutputStream, error: PrintStream): Int {
        val server = GradleLanguageServer(logger = ServerLogger(error::println))
        val threadNumber = AtomicInteger()
        val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "gradle-lsp-jsonrpc-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        val launcher = LSPLauncher.createServerLauncher(
            server,
            input,
            output,
            executor,
            Function { messages -> messages },
        )
        server.connect(launcher.remoteProxy)

        error.println("gradle-lsp: starting stdio language server")
        val listening = launcher.startListening()
        server.exitCode().whenComplete { _, _ -> listening.cancel(true) }

        return try {
            listening.get()
            server.exitCode().getNow(0)
        } catch (_: CancellationException) {
            server.exitCode().getNow(0)
        } catch (failure: ExecutionException) {
            error.println("gradle-lsp: stdio transport failed: ${failure.cause?.message ?: failure.message}")
            1
        } finally {
            executor.shutdownNow()
            server.close()
            error.println("gradle-lsp: stdio language server stopped")
        }
    }
}
