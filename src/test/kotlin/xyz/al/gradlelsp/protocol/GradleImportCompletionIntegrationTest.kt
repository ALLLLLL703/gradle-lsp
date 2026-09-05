package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtProperty
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.DocumentAnalyzer
import xyz.al.gradlelsp.analysis.GradleDsl
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.analysis.SourceDiagnostic
import xyz.al.gradlelsp.completion.SourceCompletions
import xyz.al.gradlelsp.completion.SourceCompletionItem
import xyz.al.gradlelsp.completion.SourceCompletionKind
import xyz.al.gradlelsp.fixture.ImportOuter
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.gradle.GradleKotlinDslModel
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.navigation.DocumentNavigationEngine
import xyz.al.gradlelsp.navigation.GradleNavigationEngine
import xyz.al.gradlelsp.navigation.KotlinFileNavigationEngine
import xyz.al.gradlelsp.navigation.SourceDefinition
import xyz.al.gradlelsp.presentation.Utf16LineMap
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradleImportCompletionIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `import package completion uses the script classpath and recovered PSI with exact segment edits`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val model = GradleKotlinDslModelLoader().modelFor(script)
        val modelCalls = AtomicInteger()
        val navigation = GradleNavigationEngine().use(
            GradleDsl.KOTLIN,
            KotlinFileNavigationEngine(modelProvider = { modelCalls.incrementAndGet(); model }),
        )
        val service = GradleTextDocumentService(analyzer = noAnalysis(), navigation = navigation)
        val uri = script.toUri().toString()
        var version = 0
        fun complete(markedText: String): Pair<String, CompletionList> {
            val offset = markedText.indexOf(CARET)
            check(offset >= 0)
            val text = markedText.replace(CARET, "")
            version += 1
            if (version == 1) {
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", version, text)))
            } else {
                service.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri, version),
                        listOf(TextDocumentContentChangeEvent(text)),
                    ),
                )
            }
            val response = service.completion(
                CompletionParams(TextDocumentIdentifier(uri), Utf16LineMap(text).positionAt(offset)),
            ).get(30, TimeUnit.SECONDS)
            assertTrue(response.isRight)
            return text to response.right
        }

        GradleLanguageServer(textDocuments = service).use { server ->
            val capability = server.initialize(InitializeParams()).join().capabilities.completionProvider
            assertNotNull(capability)
            assertEquals(listOf("."), capability.triggerCharacters)
            assertEquals(false, capability.resolveProvider)

            val malformed = "import org.gradle.\nval broken =\nval recovered = 42"
            KotlinAstParser().use { parser ->
                val parsed = parser.parse("build.gradle.kts", malformed)
                assertTrue(parsed.syntaxDiagnostics().isNotEmpty())
                assertEquals(1, parsed.psi.importDirectives.size)
                assertTrue(PsiTreeUtil.collectElementsOfType(parsed.psi, KtProperty::class.java).any { it.name == "recovered" })
            }

            val root = complete("import $CARET\nval broken =\nval recovered = 42").second
            assertTrue(root.items.map { it.label }.containsAll(listOf("java.", "kotlin.", "org.")), root.toString())
            assertEquals(root.items.map { it.sortText }.sorted(), root.items.map { it.sortText })
            assertEquals(root.items.size, root.items.map { it.label }.distinct().size)
            assertFalse(root.isIncomplete)
            assertFalse(root.items.any { it.filterText == "META-INF" }, root.toString())

            val scenarios = listOf(
                Triple("import org.gr$CARET", "org.gradle", "import org.gradle."),
                Triple("import org.gradle.$CARET", "org.gradle.api", "import org.gradle.api."),
                Triple("import kotlin.col$CARET", "kotlin.collections", "import kotlin.collections."),
                Triple("import com.google.gs$CARET", "com.google.gson", "import com.google.gson."),
                Triple("import java.ut${CARET}il.concurrent.*", "java.util", "import java.util.concurrent.*"),
                Triple("import java.$CARET", "java.util", "import java.util."),
                Triple("/* 😀 */ import org.gr$CARET", "org.gradle", "/* 😀 */ import org.gradle."),
                Triple("import org . gr$CARET", "org.gradle", "import org . gradle."),
            )
            for ((line, expectedPackage, expectedLine) in scenarios) {
                val suffix = "\nval broken =\nval recovered = 42"
                val (text, result) = complete(line + suffix)
                val item = result.items.single { it.detail == "(package) $expectedPackage" }
                assertEquals(CompletionItemKind.Module, item.kind)
                assertEquals(InsertTextFormat.PlainText, item.insertTextFormat)
                assertEquals(null, item.additionalTextEdits)
                val edit = item.textEdit.left
                val lines = Utf16LineMap(text)
                val start = assertNotNull(lines.offsetAt(edit.range.start))
                val end = assertNotNull(lines.offsetAt(edit.range.end))
                assertEquals(expectedLine + suffix, text.replaceRange(start, end, edit.newText))
                assertEquals(0, edit.range.start.line)
                assertEquals(0, edit.range.end.line)
                if (line.startsWith("/*")) assertEquals(Position(0, 20), edit.range.start)
            }

            for ((line, expectedType, expectedKind) in listOf(
                Triple("import org.gradle.api.Pro$CARET", "org.gradle.api.Project", CompletionItemKind.Interface),
                Triple("import java.util.Ar${CARET}rayList", "java.util.ArrayList", CompletionItemKind.Class),
                Triple("/* 😀 */ import java.util.Map.En$CARET", "java.util.Map.Entry", CompletionItemKind.Interface),
                Triple("import kotlin.collections.Map.En$CARET", "kotlin.collections.Map.Entry", CompletionItemKind.Interface),
                Triple("import java.lang.Thread.St$CARET", "java.lang.Thread.State", CompletionItemKind.Enum),
                Triple("import java.util.Ma${CARET}p.Entry", "java.util.Map", CompletionItemKind.Interface),
            )) {
                val (text, result) = complete(line + "\nval broken =\nval recovered = 42")
                val item = result.items.single { it.detail.endsWith(" $expectedType") }
                assertEquals(expectedKind, item.kind)
                val shortName = expectedType.substringAfterLast('.')
                assertEquals(shortName, item.label)
                assertEquals(shortName, item.textEdit.left.newText)
                val edit = item.textEdit.left
                val lines = Utf16LineMap(text)
                val changed = text.replaceRange(lines.offsetAt(edit.range.start)!!, lines.offsetAt(edit.range.end)!!, edit.newText)
                assertTrue(changed.contains("import $expectedType"), changed)
                if (line.startsWith("/*")) assertEquals(Position(0, 30), edit.range.start)
            }
            assertTrue(complete("import java.util.ArrayList.si$CARET").second.items.isEmpty())
            assertTrue(complete("import org.gradle.api.Project.$CARET").second.items.isEmpty())
            assertTrue(complete("import xyz.al.doesnotexist.$CARET").second.items.isEmpty())

            val beforeNonImport = modelCalls.get()
            for (text in listOf(
                "// import org.gr$CARET",
                "val s = \"import org.gr$CARET\"",
                "import java.util.List as Li$CARET",
                "import java.util.*$CARET",
                "import /* $CARET */ org.gradle.api.Project",
                "fun scope() { import org.gr$CARET }",
                "val ordinary = org.gr$CARET",
                "package org.gr$CARET",
            )) {
                assertTrue(complete(text).second.items.isEmpty(), text)
            }
            assertEquals(beforeNonImport, modelCalls.get(), "Non-import positions must not load a Gradle model")
        }
    }

    @Test
    fun `package candidates are bounded escaped and isolated by the compiler model generation`() {
        val first = compilePackages("first", (0..139).map { "p%03d".format(it) } + listOf("when", "café", "types"))
        val second = compilePackages("second", listOf("replacement"))
        var model = GradleKotlinDslModel(listOf(first), emptyList(), emptyList(), "first")
        KotlinFileNavigationEngine(modelProvider = { model }).use { engine ->
            fun complete(markedText: String): SourceCompletions {
                val text = markedText.replace(CARET, "")
                return engine.completeImports(
                    AnalysisDocument(temporaryDirectory.resolve("build.gradle.kts").toUri().toString(), "build.gradle.kts", text),
                    markedText.indexOf(CARET),
                )
            }

            val prefix = "import xyz.al.gradlelsp.fixture.imports."
            val broad = complete("$prefix$CARET")
            assertTrue(broad.isIncomplete)
            assertEquals(128, broad.items.size)
            val narrowed = complete("${prefix}p13$CARET")
            assertFalse(narrowed.isIncomplete)
            assertEquals((130..139).map { "p$it" }, narrowed.items.map { it.name })
            val quoted = complete("$prefix`wh${CARET}en`").items.single()
            assertEquals("`when`.", quoted.insertText)
            assertEquals(prefix.length, quoted.startOffset)
            assertEquals(prefix.length + "`when`".length, quoted.endOffset)
            assertEquals("café.", complete("${prefix}caf$CARET").items.single().insertText)
            assertEquals(listOf("Marker"), complete("${prefix}types.$CARET").items.map { it.name })
            assertEquals("Inner", complete("${prefix}types.Marker.In$CARET").items.single().name)
            assertEquals("Deep", complete("${prefix}types.Marker.Nested.D$CARET").items.single().name)
            assertTrue(complete("${prefix}types.Marker.Hidden.D$CARET").items.isEmpty())
            assertTrue(complete("${prefix}types.Marker.Protected.$CARET").items.isEmpty())
            val manyClasses = complete("${prefix}types.Marker.Part$CARET")
            assertEquals(128, manyClasses.items.size)
            assertTrue(manyClasses.isIncomplete)
            val narrowClasses = complete("${prefix}types.Marker.Part13$CARET")
            assertEquals((130..139).map { "Part$it" }, narrowClasses.items.map { it.name })
            assertFalse(narrowClasses.isIncomplete)
            val samePackage = complete("package xyz.al.gradlelsp.fixture.imports.types\n${prefix}types.Package$CARET")
            assertEquals("PackageOnly", samePackage.items.single().name)

            model = GradleKotlinDslModel(listOf(second), emptyList(), emptyList(), "second")
            assertEquals(listOf("replacement"), complete("$prefix$CARET").items.map { it.name })
            model = GradleKotlinDslModel(listOf(first), emptyList(), emptyList(), "first")
            assertEquals("p139", complete("${prefix}p139$CARET").items.single().name)
        }
    }

    @Test
    fun `Kotlin import classifiers preserve nesting and exclude inaccessible and synthetic declarations`() {
        val classes = Path.of(ImportOuter::class.java.protectionDomain.codeSource.location.toURI())
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val model = GradleKotlinDslModel(listOf(classes, stdlib), emptyList(), emptyList())
        KotlinFileNavigationEngine(modelProvider = { model }).use { engine ->
            fun complete(path: String): SourceCompletions {
                val text = "import $path"
                return engine.completeImports(
                    AnalysisDocument(temporaryDirectory.resolve("build.gradle.kts").toUri().toString(), "build.gradle.kts", text),
                    text.length,
                )
            }
            val prefix = "xyz.al.gradlelsp.fixture."
            assertEquals(listOf("ImportOuter"), complete("${prefix}Import").items.map { it.name })
            val members = complete("${prefix}ImportOuter.").items
            assertEquals(listOf("Contract", "Inner", "Mode", "Nested", "Singleton"), members.map { it.name })
            assertEquals(SourceCompletionKind.INTERFACE, members.single { it.name == "Contract" }.kind)
            assertEquals(SourceCompletionKind.ENUM, members.single { it.name == "Mode" }.kind)
            assertEquals(listOf("Nested"), complete("${prefix}ImportOuter.N").items.map { it.name })
            assertTrue(complete("${prefix}ImportOuter.Missing").items.isEmpty())
            assertEquals("Deep", complete("${prefix}ImportOuter.Nested.D").items.single().insertText)
            assertTrue(complete("${prefix}ImportOuter.Hidden.").items.isEmpty())
            assertTrue(complete("${prefix}ImportOuter.Mode.F").items.isEmpty())
        }
    }

    @Test
    fun `import keyword completion uses recovered header PSI without loading a Gradle model`() {
        val uri = temporaryDirectory.resolve("build.gradle.kts").toUri().toString()
        val documents = DocumentStore()
        val modelCalls = AtomicInteger()
        val engine = KotlinFileNavigationEngine(modelProvider = { modelCalls.incrementAndGet(); error("No model for keyword completion") })
        GradleTextDocumentService(documents = documents, analyzer = noAnalysis(), navigation = engine).use { service ->
            fun complete(markedText: String): Pair<String, CompletionList> {
                val text = markedText.replace(CARET, "")
                documents.open(uri, 1, text)
                val result = service.completion(CompletionParams(TextDocumentIdentifier(uri),
                    Utf16LineMap(text).positionAt(markedText.indexOf(CARET)))).get(10, TimeUnit.SECONDS).right
                return text to result
            }
            for (input in listOf(
                CARET,
                "im$CARET",
                "im${CARET}port",
                "/* 😀 */ imp$CARET\nval broken =\nval recovered = 1",
                "import java.util.List\nim$CARET",
                "import java.\n$CARET\nval recovered = 1",
                "package xyz.al.gradlelsp.fixture\nim$CARET\nimport java.util.List",
            )) {
                val (text, result) = complete(input)
                val item = result.items.single()
                assertEquals("import", item.label)
                assertEquals(CompletionItemKind.Keyword, item.kind)
                assertEquals("import ", item.textEdit.left.newText)
                val edit = item.textEdit.left
                val lines = Utf16LineMap(text)
                val replaced = text.substring(lines.offsetAt(edit.range.start)!!, lines.offsetAt(edit.range.end)!!)
                assertTrue("import".startsWith(replaced), replaced)
                if (input.startsWith("/*")) assertEquals(Position(0, 9), edit.range.start)
            }
            for (input in listOf(
                "val before = 1\nim$CARET",
                "fun f() { im$CARET }",
                "class C { im$CARET }",
                "val x = im$CARET",
                "im$CARET + 1",
                "// im$CARET",
                "val x = \"im$CARET\"",
                "import java.util.List as im$CARET",
                "`im${CARET}port`",
            )) assertTrue(complete(input).second.items.isEmpty(), input)
            assertEquals(0, modelCalls.get())
        }
    }

    @Test
    fun `completion is non-blocking and drops stale snapshots before and after analysis`() {
        val uri = temporaryDirectory.resolve("build.gradle.kts").toUri().toString()
        val text = "import org.gr"
        val documents = DocumentStore().apply { open(uri, 1, text) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val navigation = object : DocumentNavigationEngine {
            override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> = emptyList()

            override fun completeImports(document: AnalysisDocument, offset: Int): SourceCompletions {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return SourceCompletions(listOf(SourceCompletionItem("gradle", "org.gradle", "gradle.", 11, 13)))
            }
        }
        GradleTextDocumentService(documents = documents, analyzer = noAnalysis(), navigation = navigation).use { service ->
            val params = CompletionParams(TextDocumentIdentifier(uri), Position(0, text.length))
            try {
                val running = service.completion(params)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFalse(running.isDone)
                val queued = service.completion(params)
                assertFalse(queued.isDone)
                documents.replace(uri, 2, "import java.ut")
                release.countDown()
                assertTrue(running.get(10, TimeUnit.SECONDS).right.items.isEmpty())
                assertTrue(queued.get(10, TimeUnit.SECONDS).right.items.isEmpty())
                assertEquals(1, calls.get(), "The queued stale snapshot must never enter the compiler")

                documents.close(uri)
                documents.open(uri, 1, text)
                assertEquals("gradle.", service.completion(params).get(10, TimeUnit.SECONDS).right.items.single().label)
                assertTrue(service.completion(CompletionParams(TextDocumentIdentifier(uri), Position(99, 0)))
                    .get(10, TimeUnit.SECONDS).right.items.isEmpty())
                documents.close(uri)
                assertTrue(service.completion(params).get(10, TimeUnit.SECONDS).right.items.isEmpty())
            } finally {
                release.countDown()
            }
        }
    }

    private fun compilePackages(name: String, suffixes: List<String>): Path {
        val output = Files.createDirectories(temporaryDirectory.resolve(name).resolve("classes"))
        val sources = suffixes.map { suffix ->
            val source = temporaryDirectory.resolve(name).resolve(suffix).resolve("Marker.java")
            Files.createDirectories(source.parent)
            val members = if (suffix == "types") {
                "public class Inner {} public static class Nested { public static class Deep {} } " +
                    "private static class Hidden { public static class Deep {} } protected static class Protected {} " +
                    (0..139).joinToString(" ") { "public static class Part%03d {}".format(it) }
            } else ""
            Files.writeString(source,
                "package xyz.al.gradlelsp.fixture.imports.$suffix; public final class Marker { $members } class PackageOnly {}")
            source.toString()
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", output.toString(), *sources.toTypedArray()))
        return output
    }

    private fun noAnalysis(): DocumentAnalyzer = object : DocumentAnalyzer {
        override fun analyze(document: AnalysisDocument): List<SourceDiagnostic> = emptyList()
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
