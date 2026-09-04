package xyz.al.gradlelsp.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandLineTest {
    @Test
    fun `stdio selects stdio transport`() {
        assertEquals(CommandLineResult.Stdio, CommandLine.parse(arrayOf("--stdio")))
    }

    @Test
    fun `long and short help select help`() {
        assertEquals(CommandLineResult.Help, CommandLine.parse(arrayOf("--help")))
        assertEquals(CommandLineResult.Help, CommandLine.parse(arrayOf("-h")))
    }

    @Test
    fun `missing transport is rejected`() {
        val result = assertIs<CommandLineResult.Error>(CommandLine.parse(emptyArray()))
        assertEquals("Missing transport option. Use --stdio.", result.message)
    }

    @Test
    fun `unknown and combined arguments are rejected`() {
        assertIs<CommandLineResult.Error>(CommandLine.parse(arrayOf("--socket")))
        assertIs<CommandLineResult.Error>(CommandLine.parse(arrayOf("--stdio", "--help")))
    }
}
