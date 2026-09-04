package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.psi.KtProperty
import xyz.al.gradlelsp.navigation.KotlinFileNavigationEngine
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `file navigation resolves recovered declarations and lexical shadowing`() {
        val text = """
            val answer = 42
            fun twice(value: Int): Int = value * 2
            val broken =
            val topLevelUse = answer
            val functionUse = twice(answer)
            fun scope() {
                val answer = 7
                println(answer)
            }
        """.trimIndent()
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine().use { navigation ->
            fun definitionOffset(referenceOffset: Int): Int =
                navigation.definitions(document, referenceOffset + 1).single().startOffset

            val topLevelDeclaration = text.indexOf("answer")
            val topLevelReference = text.indexOf("answer", text.indexOf("topLevelUse"))
            assertEquals(topLevelDeclaration, definitionOffset(topLevelReference))

            val functionDeclaration = text.indexOf("twice")
            val functionReference = text.indexOf("twice", text.indexOf("functionUse"))
            assertEquals(functionDeclaration, definitionOffset(functionReference))

            val parameterDeclaration = text.indexOf("value")
            val parameterReference = text.indexOf("value", parameterDeclaration + "value".length)
            assertEquals(parameterDeclaration, definitionOffset(parameterReference))

            val localDeclaration = text.indexOf("answer", text.indexOf("fun scope"))
            val localReference = text.indexOf("answer", localDeclaration + "answer".length)
            assertEquals(localDeclaration, definitionOffset(localReference))
        }
    }

    @Test
    fun `real Gradle script model supports Kotlin DSL semantic diagnostics`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val document = AnalysisDocument(
            uri = script.toUri().toString(),
            fileName = script.fileName.toString(),
            text = """
                plugins {
                    application
                }
                repositories {
                    mavenCentral()
                }
                val mismatch: String = 42
                val marker = "😀"; unknownGradleDslSymbol()
            """.trimIndent(),
        )

        defaultGradleAnalysisEngine().use { analyzer ->
            val diagnostics = analyzer.analyze(document)

            val mismatch = diagnostics.firstOrNull {
                it.kind == SourceDiagnosticKind.SEMANTIC &&
                    it.message.contains("Initializer type mismatch")
            }
            assertTrue(mismatch != null, diagnostics.joinToString("\n", transform = SourceDiagnostic::message))

            val unresolved = diagnostics.firstOrNull {
                it.kind == SourceDiagnosticKind.SEMANTIC &&
                    it.message.contains("Unresolved reference 'unknownGradleDslSymbol'")
            }
            assertTrue(unresolved != null, diagnostics.joinToString("\n", transform = SourceDiagnostic::message))
            assertEquals(
                "unknownGradleDslSymbol",
                document.text.substring(unresolved.startOffset, unresolved.endOffset),
            )
            assertFalse(
                diagnostics.any {
                    it.message.contains("Unresolved reference 'plugins'") ||
                        it.message.contains("Unresolved reference 'mavenCentral'")
                },
                diagnostics.joinToString("\n", transform = SourceDiagnostic::message),
            )
        }
    }
}
