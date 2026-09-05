package xyz.al.gradlelsp.protocol

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScopeImpl
import org.jetbrains.kotlin.utils.Printer
import org.junit.jupiter.api.io.TempDir
import xyz.al.gradlelsp.analysis.*
import xyz.al.gradlelsp.completion.SourceCompletions
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.navigation.KotlinFileNavigationEngine
import xyz.al.gradlelsp.presentation.Utf16LineMap
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GradleSemanticCompletionIntegrationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `member completion resolves matching compiler names without bulk descriptor enumeration`() {
        val lookups = mutableListOf<String>()
        val match = Name.identifier("matching")
        val other = Name.identifier("unrelated")
        val scope = object : MemberScopeImpl() {
            override fun getFunctionNames() = setOf(match, other)
            override fun getVariableNames() = setOf(match, other)
            override fun getClassifierNames() = setOf(match, other)
            override fun getContributedFunctions(name: Name,
                location: LookupLocation): Collection<SimpleFunctionDescriptor> {
                lookups += "function:$name"
                return emptyList()
            }
            override fun getContributedVariables(name: Name,
                location: LookupLocation): Collection<PropertyDescriptor> {
                lookups += "property:$name"
                return emptyList()
            }
            override fun getContributedClassifier(name: Name,
                location: LookupLocation): ClassifierDescriptor? {
                lookups += "classifier:$name"
                return null
            }
            override fun getContributedDescriptors(kindFilter: DescriptorKindFilter,
                nameFilter: (Name) -> Boolean): Collection<DeclarationDescriptor> =
                error("Bulk enumeration would resolve unrelated declarations")
            override fun printScopeStructure(p: Printer) = Unit
        }
        scope.completionDescriptors(DescriptorKindFilter.ALL) { it == match }
        assertEquals(listOf("function:matching", "property:matching", "classifier:matching"), lookups)
        lookups.clear()
        scope.completionDescriptors(DescriptorKindFilter.CLASSIFIERS) { it == match }
        assertEquals(listOf("classifier:matching"), lookups)
    }

    @Test
    fun `local semantic scopes types overloads receivers and recovery work without a Gradle model`() {
        KotlinFileNavigationEngine(modelProvider = { error("model unavailable") }).use { engine ->
            fun complete(marked: String): SourceCompletions {
                val document = AnalysisDocument(temporaryDirectory.resolve("build.gradle.kts").toUri().toString(),
                    "build.gradle.kts", marked.replace("<caret>", ""))
                return engine.complete(document, marked.indexOf("<caret>"))
            }
            fun names(marked: String) = complete(marked).items.map { it.name }
            assertContains(names("val localValue = 1\nloc<caret>"), "localValue")
            val escaped = complete("val `when` = 1\n`wh<caret>en`").items.single { it.name == "`when`" }
            assertEquals("`when`", escaped.insertText)
            assertEquals(6, escaped.endOffset - escaped.startOffset)
            assertContains(names("val localValue = 1\nprintln(loc<caret>)"), "localValue")
            assertContains(names("fun use(parameter: String) { par<caret> }"), "parameter")
            assertContains(names("fun use() { val lexical = 1; lex<caret> }"), "lexical")
            assertFalse("later" in names("fun use() { lat<caret>; val later = 1 }"))
            val overloads = complete("fun choose(x: Int) = x\nfun choose(x: String) = x\ncho<caret>").items.filter { it.name == "choose" }
            assertEquals(2, overloads.size)
            assertTrue(overloads.map { it.detail }.distinct().size == 2, overloads.toString())
            assertContains(names("class LocalType\nval value: Loc<caret> = TODO()"), "LocalType")
            assertContains(names("typealias LocalAlias = String\nval value: LocalA<caret> = TODO()"), "LocalAlias")
            assertContains(names("fun <TypeParameter> use(value: TypeP<caret>) {}"), "TypeParameter")
            assertContains(names("import java.util.ArrayList as Renamed\nval value: Ren<caret> = TODO()"), "Renamed")
            assertContains(names("val value: Str<caret> = TODO()"), "String")
            assertContains(names("val value = lis<caret>"), "listOf")
            assertContains(names("val value = \"hello\"\nvalue.sub<caret>"), "substring")
            assertContains(names("val value: String? = null\nvalue?.len<caret>"), "length")
            assertFalse("length" in names("val value: String? = null\nvalue.len<caret>"))
            assertContains(names("class Outer { class Nested }\nval value: Outer.Nes<caret> = TODO()"), "Nested")
            assertContains(names("fun String.customExtension() = length\n\"hi\".custom<caret>"), "customExtension")
            assertFalse("customExtension" in names("fun String.customExtension() = length\n42.custom<caret>"))
            assertContains(names("val String.customProperty get() = length\n\"hi\".customP<caret>"), "customProperty")
            val extensionOverloads = complete("fun String.convert() = 1\nfun CharSequence.convert() = 2\n\"hi\".conv<caret>")
                .items.filter { it.name == "convert" }
            assertEquals(2, extensionOverloads.size)
            assertEquals(2, extensionOverloads.map { it.detail }.distinct().size)
            assertContains(names("import kotlin.text.trim as aliasedTrim\n\"hi\".aliasedT<caret>"), "aliasedTrim")
            val generic = complete("fun <T> List<T>.customFirst(): T = first()\nlistOf(\"hi\").customF<caret>").items.single { it.name == "customFirst" }
            assertTrue(generic.detail!!.contains("String"), generic.toString())
            val unconstrained = complete("fun <T, R> List<T>.customMap(block: (T) -> R): List<R> = map(block)\nlistOf(\"hi\").customM<caret>")
                .items.single { it.name == "customMap" }
            assertTrue(unconstrained.detail!!.contains("String") && unconstrained.detail.contains("R"), unconstrained.toString())
            assertFalse(unconstrained.detail.contains("ERROR"), unconstrained.toString())
            assertFalse("onlyNumbers" in names("fun <T : Number> List<T>.onlyNumbers() = size\nlistOf(\"hi\").onlyN<caret>"))
            assertContains(names("fun use(value: Any) { if (value is String) value.sub<caret> }"), "substring")
            assertContains(names("fun String.use() { sub<caret> }"), "substring")
            assertFalse("secret" in names("class Hidden { private fun secret() {} }\nHidden().sec<caret>"))
            val shadow = complete("val shared = \"outer\"\nfun use() { val shared = 42; sha<caret> }").items.single { it.name == "shared" }
            assertTrue(shadow.detail!!.contains("Int"), shadow.toString())
            assertContains(names("fun use(value: String?) { if (value != null) value.len<caret> }"), "length")
            assertContains(names("fun String?.nullableExtension() = this\nval value: String? = null\nvalue.nullableE<caret>"), "nullableExtension")
            val genericMember = complete("class Box<T>(val content: T)\nBox(\"hi\").con<caret>").items.single { it.name == "content" }
            assertTrue(genericMember.detail!!.contains("String"), genericMember.toString())
            assertFalse("localValue" in names("val localValue = 1\nval value: localV<caret> = TODO()"))
            assertContains(names("import kotlin.collections.listOf as aliasedList\nval value = aliasedL<caret>"), "aliasedList")
            assertContains(names("class C { fun String.memberExtension() = length; fun use() { \"hi\".memberE<caret> } }"), "memberExtension")
            assertFalse("memberExtension" in names("class C { fun String.memberExtension() = length }\n\"hi\".memberE<caret>"))
            assertContains(names("class Outer { class Nested }\nOuter.Nes<caret>"), "Nested")
            val receiverShadow = complete("class C { val shared = \"member\"; fun use() { val shared = 42; sha<caret> } }").items.single { it.name == "shared" }
            assertTrue(receiverShadow.detail!!.contains("Int"), receiverShadow.toString())
            val dsl = "@DslMarker annotation class Marker\n@Marker class Outer { fun outerOnly() {} }\n" +
                "@Marker class Inner { fun innerOnly() {} }\nfun Outer.nest(block: Inner.() -> Unit) {}\n" +
                "fun Outer.use() { nest { out<caret> } }"
            assertFalse("outerOnly" in names(dsl))
            val broad = complete((0..139).joinToString("\n") { "val candidate%03d = 1".format(it) } + "\ncandidate<caret>")
            assertEquals(128, broad.items.size)
            assertTrue(broad.isIncomplete)
            assertEquals(broad.items.map { it.sortText }.sorted(), broad.items.map { it.sortText })
            val broken = "val localValue = 1\nfun use() { /* 😀 */ loc<caret>alValue\nval broken =\n"
            KotlinAstParser().use { parser -> assertTrue(parser.parse("build.gradle.kts", broken.replace("<caret>", "")).syntaxDiagnostics().isNotEmpty()) }
            val item = complete(broken).items.single { it.name == "localValue" }
            assertEquals("localValue", broken.replace("<caret>", "").substring(item.startOffset, item.endOffset))
            assertTrue(complete("val value = \"no sub<caret>\"").items.isEmpty())
            assertTrue(complete("// sub<caret>").items.isEmpty())
        }
    }

    @Test
    fun `real Gradle receivers and default extension imports complete through LSP with UTF16 edits`() {
        val script = Path.of("build.gradle.kts").toAbsolutePath().normalize()
        val model = GradleKotlinDslModelLoader().modelFor(script)
        val documents = DocumentStore()
        val uri = script.toUri().toString()
        val engine = KotlinFileNavigationEngine(modelProvider = { model })
        val analyzer = object : DocumentAnalyzer { override fun analyze(document: AnalysisDocument) = emptyList<SourceDiagnostic>() }
        GradleTextDocumentService(documents = documents, analyzer = analyzer, navigation = engine).use { service ->
            var version = 0
            for ((marked, expected) in listOf(
                "dependencies { impl<caret> }" to "implementation",
                "tasks.reg<caret>" to "register",
                "project.na<caret>" to "name",
                "project.tasks.na<caret>" to "names",
                "repositories { mav<caret> }" to "mavenCentral",
                "val value: Pro<caret> = project" to "Project",
                "dependencies { /* 😀 */ impl<caret>ementation\nval broken =\n" to "implementation",
            )) {
                val text = marked.replace("<caret>", "")
                documents.open(uri, ++version, text)
                val result = service.completion(CompletionParams(TextDocumentIdentifier(uri),
                    Utf16LineMap(text).positionAt(marked.indexOf("<caret>")))).get(60, TimeUnit.SECONDS).right
                val items = result.items.filter { it.label == expected }
                assertTrue(items.isNotEmpty(), "$marked: $result")
                assertTrue(items.all { !it.detail.isNullOrBlank() })
                assertEquals(items.size, items.map { it.detail }.distinct().size)
                assertEquals(result.items.map { it.sortText }.sorted(), result.items.map { it.sortText })
                val edit = items.first().textEdit.left
                val lines = Utf16LineMap(text)
                val replacement = text.replaceRange(lines.offsetAt(edit.range.start)!!, lines.offsetAt(edit.range.end)!!, edit.newText)
                assertContains(replacement, expected)
                if (marked.contains("😀")) {
                    assertEquals(24, edit.range.start.character)
                    assertEquals(38, edit.range.end.character)
                }
            }
        }
    }
}
