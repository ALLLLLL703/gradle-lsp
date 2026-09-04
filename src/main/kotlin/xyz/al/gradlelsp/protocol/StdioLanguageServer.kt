package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

internal class StdioLanguageServer {
    fun run(input: InputStream, output: OutputStream, error: PrintStream): Int {
        val server = GradleLanguageServer(logger = ServerLogger(error::println))
        val threadNumber = AtomicInteger()
        val executor = ThreadPoolExecutor(
            CORE_JSON_RPC_THREADS,
            MAXIMUM_JSON_RPC_THREADS,
            JSON_RPC_THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAXIMUM_PENDING_JSON_RPC_TASKS),
            { task ->
                Thread(task, "gradle-lsp-jsonrpc-${threadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
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

    private companion object {
        const val CORE_JSON_RPC_THREADS = 2
        const val MAXIMUM_JSON_RPC_THREADS = 4
        const val MAXIMUM_PENDING_JSON_RPC_TASKS = 128
        const val JSON_RPC_THREAD_KEEP_ALIVE_SECONDS = 30L
    }
}
