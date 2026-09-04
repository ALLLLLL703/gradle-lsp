package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.psi.KtProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinAstParserTest {
    @Test
    fun `malformed gradle script reports syntax error and recovers later declaration`() {
        KotlinAstParser().use { parser ->
            val parsed = parser.parse(
                "build.gradle.kts",
                """
                plugins {
                    id("java")
                }
                val broken =
                val recovered = 42
                """.trimIndent(),
            )

            assertTrue(parsed.psi.isScript())
            assertTrue(parsed.syntaxDiagnostics().isNotEmpty())
            val recoveredNames = parsed.psi.script
                ?.blockExpression
                ?.statements
                .orEmpty()
                .filterIsInstance<KtProperty>()
                .mapNotNull(KtProperty::getName)
            assertEquals(listOf("broken", "recovered"), recoveredNames)
        }
    }
}
