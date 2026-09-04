package xyz.al.gradlelsp

import xyz.al.gradlelsp.cli.CommandLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GradleLspApplicationTest {
    @Test
    fun `help writes usage to stdout without starting transport`() {
        var transportStarted = false
        val application = GradleLspApplication(StdioRunner { _, _, _ ->
            transportStarted = true
            0
        })
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = application.run(
            arrayOf("--help"),
            ByteArrayInputStream(ByteArray(0)),
            PrintStream(stdout),
            PrintStream(stderr),
        )

        assertEquals(0, exitCode)
        assertEquals(CommandLine.HELP_TEXT, stdout.toString(Charsets.UTF_8))
        assertEquals("", stderr.toString(Charsets.UTF_8))
        assertEquals(false, transportStarted)
    }

    @Test
    fun `stdio passes the process streams to the transport`() {
        val stdin = ByteArrayInputStream(ByteArray(0))
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val stdout = PrintStream(stdoutBytes)
        val stderr = PrintStream(stderrBytes)
        var called = false
        val application = GradleLspApplication(StdioRunner { input, output, error ->
            called = true
            assertSame(stdin, input)
            assertSame(stdout, output)
            assertSame(stderr, error)
            7
        })

        val exitCode = application.run(arrayOf("--stdio"), stdin, stdout, stderr)

        assertTrue(called)
        assertEquals(7, exitCode)
        assertEquals("", stdoutBytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `invalid arguments fail on stderr`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = GradleLspApplication().run(
            emptyArray(),
            ByteArrayInputStream(ByteArray(0)),
            PrintStream(stdout),
            PrintStream(stderr),
        )

        assertEquals(2, exitCode)
        assertEquals("", stdout.toString(Charsets.UTF_8))
        assertTrue(stderr.toString(Charsets.UTF_8).contains("Missing transport option"))
    }
}
