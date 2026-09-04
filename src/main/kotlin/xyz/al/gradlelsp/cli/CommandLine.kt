package xyz.al.gradlelsp.cli

internal sealed interface CommandLineResult {
    data object Help : CommandLineResult

    data object Stdio : CommandLineResult

    data class Error(val message: String) : CommandLineResult
}

internal object CommandLine {
    const val HELP_TEXT: String = """Usage: gradle-lsp --stdio

Options:
  --stdio    Run the language server over standard input and output.
  -h, --help Show this help message.
"""

    fun parse(arguments: Array<String>): CommandLineResult =
        when {
            arguments.contentEquals(arrayOf("--stdio")) -> CommandLineResult.Stdio
            arguments.contentEquals(arrayOf("--help")) || arguments.contentEquals(arrayOf("-h")) -> CommandLineResult.Help
            arguments.isEmpty() -> CommandLineResult.Error("Missing transport option. Use --stdio.")
            else -> CommandLineResult.Error("Unknown or incompatible arguments: ${arguments.joinToString(" ")}")
        }
}
