package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.psi.KtProperty
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.navigation.KotlinFileNavigationEngine
import java.net.JarURLConnection
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
    fun `external Kotlin declaration prefers attached source`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath()
        val baseModel = GradleKotlinDslModelLoader().modelFor(script)
        val tuplesSource = requireNotNull(
            javaClass.classLoader.getResource("commonMain/kotlin/util/Tuples.kt"),
        )
        val sourceJar = Path.of((tuplesSource.openConnection() as JarURLConnection).jarFileURL.toURI())
        val model = baseModel.copy(sourcePath = listOf(sourceJar))
        val text = """
            val broken =
            val recovered = 1
            val pair = Pair(1, 2)
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val reference = text.indexOf("Pair")
            val definition = navigation.definitions(document, reference).single()

            assertTrue(definition.uri.startsWith("gradle-lsp://source/"))
            assertTrue(definition.uri.endsWith("/Tuples.kt"))
            assertEquals(
                "Pair",
                definition.sourceText.substring(definition.startOffset, definition.endOffset),
            )
            assertTrue(definition.sourceText.contains("public data class Pair"))
        }
    }

    @Test
    fun `external Kotlin declaration falls back to a metadata stub`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath()
        val model = GradleKotlinDslModelLoader().modelFor(script).copy(sourcePath = emptyList())
        val text = """
            import org.gradle.kotlin.dsl.KotlinProjectScriptTemplate

            val templateType = KotlinProjectScriptTemplate::class
            fun buildscriptOf(template: KotlinProjectScriptTemplate) = template.getBuildscript()
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val reference = text.indexOf("KotlinProjectScriptTemplate", text.indexOf("val templateType"))
            val definition = navigation.definitions(document, reference).single()

            assertTrue(definition.uri.startsWith("gradle-lsp://source/"))
            assertTrue(definition.uri.endsWith("/KotlinProjectScriptTemplate.decompiled.kt"))
            assertEquals(
                "KotlinProjectScriptTemplate",
                definition.sourceText.substring(definition.startOffset, definition.endOffset),
            )
            assertTrue(definition.sourceText.contains("class KotlinProjectScriptTemplate"))

            val methodReference = text.indexOf("getBuildscript")
            val method = navigation.definitions(document, methodReference).single()
            assertEquals(
                "getBuildscript",
                method.sourceText.substring(method.startOffset, method.endOffset),
            )
            assertTrue(method.sourceText.contains("class `KotlinProjectScriptTemplate`"))
            assertTrue(method.sourceText.contains("fun getBuildscript"))
        }
    }

    @Test
    fun `external Java declaration prefers attached source`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath()
        val model = GradleKotlinDslModelLoader().modelFor(script)
        val text = """
            import com.google.gson.Gson

            val json = Gson().toJson("Gradle")
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val reference = text.indexOf("toJson")
            val definition = navigation.definitions(document, reference).single()

            assertTrue(definition.uri.startsWith("gradle-lsp://source/"))
            assertTrue(definition.uri.endsWith("/Gson.java"))
            assertEquals(
                "toJson",
                definition.sourceText.substring(definition.startOffset, definition.endOffset),
            )
            assertTrue(definition.sourceText.contains("public String toJson(Object src)"))
        }
    }

    @Test
    fun `external Java declaration falls back to JD-Core`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val text = """
            val broken =
            val recovered = 1
            repositories {
                mavenCentral()
            }
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)
        val model = GradleKotlinDslModelLoader().modelFor(script).copy(sourcePath = emptyList())
        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val reference = text.indexOf("mavenCentral")
            val definition = navigation.definitions(document, reference + 1).single()

            assertTrue(definition.uri.startsWith("gradle-lsp://source/"))
            assertTrue(definition.uri.endsWith("/RepositoryHandler.java"))
            assertEquals(
                "mavenCentral",
                definition.sourceText.substring(definition.startOffset, definition.endOffset),
            )
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
