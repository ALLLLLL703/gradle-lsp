package xyz.al.gradlelsp

import xyz.al.gradlelsp.cli.CommandLine
import xyz.al.gradlelsp.cli.CommandLineResult
import xyz.al.gradlelsp.protocol.StdioLanguageServer
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

internal fun interface StdioRunner {
    fun run(input: InputStream, output: OutputStream, error: PrintStream): Int
}

internal class GradleLspApplication(
    private val stdioRunner: StdioRunner = StdioRunner { input, output, error ->
        StdioLanguageServer().run(input, output, error)
    },
) {
    fun run(
        arguments: Array<String>,
        input: InputStream,
        output: PrintStream,
        error: PrintStream,
    ): Int =
        when (val command = CommandLine.parse(arguments)) {
            CommandLineResult.Help -> {
                output.print(CommandLine.HELP_TEXT)
                0
            }

            CommandLineResult.Stdio -> stdioRunner.run(input, output, error)
            is CommandLineResult.Error -> {
                error.println(command.message)
                error.println("Run gradle-lsp --help for usage.")
                2
            }
        }
}
