package xyz.al.gradlelsp.analysis

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import xyz.al.gradlelsp.documents.WorkspaceDocumentSource
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.navigation.KotlinFileNavigationEngine
import xyz.al.gradlelsp.navigation.SourceDefinition
import java.net.JarURLConnection
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `definition preserves overloaded constructor declarations after PSI recovery`() {
        val text = """
            class Box {
                constructor(value: Int) { println(value) }
                constructor(value: CharSequence) { println(value) }
            }
            val broken =
            val number = Box(1)
            val text = Box("value")
        """.trimIndent()
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine().use { navigation ->
            val numberCall = text.indexOf("Box", text.indexOf("val number"))
            val textCall = text.indexOf("Box", text.indexOf("val text"))

            assertEquals(text.indexOf("constructor"), navigation.definitions(document, numberCall).single().startOffset)
            assertEquals(
                text.indexOf("constructor", text.indexOf("constructor") + 1),
                navigation.definitions(document, textCall).single().startOffset,
            )
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

            val hover = assertNotNull(navigation.hover(document, reference))
            val documentation = assertNotNull(hover.documentation)
            assertTrue(hover.signature.contains("Pair"), hover.signature)
            assertTrue(documentation.contains("Represents a generic pair of two values"), documentation)
            assertTrue(assertNotNull(hover.source).uri.endsWith("/Tuples.kt"))
        }
    }

    @Test
    fun `hover renders exact external Gradle KDoc after PSI recovery`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val model = GradleKotlinDslModelLoader().modelFor(script)
        val text = """
            val broken =
            val recovered = 1
            dependencies {
                implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
            }
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val reference = text.indexOf("implementation")
            val hover = assertNotNull(navigation.hover(document, reference))
            val documentation = assertNotNull(hover.documentation)

            assertTrue(hover.signature.contains("DependencyHandler.implementation"), hover.signature)
            assertTrue(
                documentation.contains("Adds a dependency to the 'implementation' configuration."),
                documentation,
            )
            assertTrue(documentation.contains("**dependencyNotation**"))
            assertTrue(documentation.contains("**Returns:**"))
            assertTrue(assertNotNull(hover.source).uri.endsWith("/ImplementationConfigurationAccessors.kt"))
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
    fun `metadata stub preserves a generic Kotlin constructor`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath()
        val model = GradleKotlinDslModelLoader().modelFor(script).copy(sourcePath = emptyList())
        val text = """
            val broken =
            val pair = Pair(1, "value")
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val definition = navigation.definitions(document, text.indexOf("Pair")).single()

            assertTrue(
                definition.uri.endsWith("/Pair.decompiled.kt"),
                "${definition.uri}\n${definition.sourceText}",
            )
            assertEquals(
                "constructor",
                definition.sourceText.substring(definition.startOffset, definition.endOffset),
            )
            assertTrue(definition.sourceText.contains("class `Pair`<`A`, `B`>"))
        }
    }

    @Test
    fun `metadata stub selects a callable instead of its same-named parameter`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath()
        val model = GradleKotlinDslModelLoader().modelFor(script).copy(sourcePath = emptyList())
        val text = """
            val broken =
            val recovered = listOf(1, 2)
            val selected = recovered.random(kotlin.random.Random.Default)
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val reference = text.indexOf("random")
            val definition = navigation.definitions(document, reference).single()
            KotlinAstParser().use { parser ->
                val parsedStub = parser.parse("random.decompiled.kt", definition.sourceText)
                val function = PsiTreeUtil.collectElementsOfType(parsedStub.psi, KtNamedFunction::class.java)
                    .single { declaration -> declaration.name == "random" }
                val parameter = function.valueParameters.single()

                assertEquals(function.nameIdentifier?.textRange?.startOffset, definition.startOffset)
                assertEquals("random", definition.sourceText.substring(definition.startOffset, definition.endOffset))
                assertTrue(definition.startOffset < requireNotNull(parameter.nameIdentifier).textRange.startOffset)
            }
        }
    }

    @Test
    fun `external Java definition preserves overloaded constructors`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val model = GradleKotlinDslModelLoader().modelFor(script).copy(sourcePath = emptyList())
        val text = """
            val broken =
            val empty = GradleException()
            val message = GradleException("failure")
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)

        KotlinFileNavigationEngine(modelProvider = { model }).use { navigation ->
            val emptyCall = text.indexOf("GradleException")
            val messageCall = text.indexOf("GradleException", emptyCall + 1)
            val emptyConstructor = navigation.definitions(document, emptyCall).single()
            val messageConstructor = navigation.definitions(document, messageCall).single()

            assertTrue(emptyConstructor.lineAtSelection().contains("GradleException()"))
            assertTrue(messageConstructor.lineAtSelection().contains("GradleException(String"))
            assertTrue(emptyConstructor.startOffset != messageConstructor.startOffset)
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

            val hover = assertNotNull(navigation.hover(document, reference))
            val documentation = assertNotNull(hover.documentation)
            assertTrue(hover.signature.contains("toJson"), hover.signature)
            assertTrue(documentation.contains("serializes the specified object"), documentation)
            assertTrue(documentation.contains("**src**"))
            assertTrue(documentation.contains("**Returns:**"))
            assertTrue(assertNotNull(hover.source).uri.endsWith("/Gson.java"))
        }
    }

    @Test
    fun `references include a resolved external Gradle declaration`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val model = GradleKotlinDslModelLoader().modelFor(script).copy(sourcePath = emptyList())
        val text = """
            val broken =
            repositories {
                mavenCentral()
            }
        """.trimIndent()
        val document = AnalysisDocument(script.toUri().toString(), script.fileName.toString(), text)
        val workspace = WorkspaceDocumentSource { origin, consume -> consume(origin) }

        KotlinFileNavigationEngine(
            modelProvider = { model },
            workspaceDocuments = workspace,
        ).use { navigation ->
            val reference = text.indexOf("mavenCentral")
            val locations = navigation.references(document, reference, includeDeclaration = true)
            val workspaceReference = locations.single { location -> location.uri == document.uri }
            val externalDeclaration = locations.single { location -> location.uri.startsWith("gradle-lsp://") }

            assertEquals(reference, workspaceReference.startOffset)
            assertEquals(
                "mavenCentral",
                externalDeclaration.sourceText.substring(
                    externalDeclaration.startOffset,
                    externalDeclaration.endOffset,
                ),
            )
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

    private fun SourceDefinition.lineAtSelection(): String {
        val lineStart = sourceText.lastIndexOf('\n', startOffset - 1).let { index -> index + 1 }
        val lineEnd = sourceText.indexOf('\n', endOffset).takeIf { index -> index >= 0 } ?: sourceText.length
        return sourceText.substring(lineStart, lineEnd)
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
