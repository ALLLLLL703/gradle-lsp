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
            val constructors = complete((0..139).joinToString("\n") {
                "class Candidate%03d(val value: Int)".format(it)
            } + "\nCandidate<caret>")
            assertEquals(128, constructors.items.size)
            assertTrue(constructors.isIncomplete)
            assertTrue(constructors.items.all { it.insertText.endsWith("()") && it.snippetText!!.contains("value") })
            val broken = "val localValue = 1\nfun use() { /* 😀 */ loc<caret>alValue\nval broken =\n"
            KotlinAstParser().use { parser -> assertTrue(parser.parse("build.gradle.kts", broken.replace("<caret>", "")).syntaxDiagnostics().isNotEmpty()) }
            val item = complete(broken).items.single { it.name == "localValue" }
            assertEquals("localValue", broken.replace("<caret>", "").substring(item.startOffset, item.endOffset))
            assertTrue(complete("val value = \"no sub<caret>\"").items.isEmpty())
            assertTrue(complete("// sub<caret>").items.isEmpty())
        }
    }

    @Test
    fun `keywords named arguments and callable edits use recovered contexts and negotiated snippets`() {
        val documents = DocumentStore()
        val uri = temporaryDirectory.resolve("build.gradle.kts").toUri().toString()
        val engine = KotlinFileNavigationEngine(modelProvider = { error("model unavailable") })
        val analyzer = object : DocumentAnalyzer { override fun analyze(document: AnalysisDocument) = emptyList<SourceDiagnostic>() }
        GradleTextDocumentService(documents = documents, analyzer = analyzer, navigation = engine).use { service ->
            val server = GradleLanguageServer(textDocuments = service)
            var version = 0
            fun complete(marked: String, snippets: Boolean = false): org.eclipse.lsp4j.CompletionList {
                server.initialize(org.eclipse.lsp4j.InitializeParams().apply {
                    capabilities = org.eclipse.lsp4j.ClientCapabilities().apply {
                        textDocument = org.eclipse.lsp4j.TextDocumentClientCapabilities().apply {
                            completion = org.eclipse.lsp4j.CompletionCapabilities().apply {
                                completionItem = org.eclipse.lsp4j.CompletionItemCapabilities().apply { snippetSupport = snippets }
                            }
                        }
                    }
                }).get()
                val text = marked.replace("<caret>", "")
                documents.open(uri, ++version, text)
                return service.completion(CompletionParams(TextDocumentIdentifier(uri),
                    Utf16LineMap(text).positionAt(marked.indexOf("<caret>")))).get(30, TimeUnit.SECONDS).right
            }
            fun keywords(marked: String) = complete(marked).items.filter { it.kind == org.eclipse.lsp4j.CompletionItemKind.Keyword }.map { it.label }
            for (keyword in listOf("val", "var", "fun", "class", "interface", "object", "private", "public", "internal", "data", "sealed", "open", "abstract", "typealias", "suspend", "inline", "package")) {
                assertContains(keywords(keyword.take(2) + "<caret>"), keyword)
            }
            val header = complete("<caret>")
            assertContains(header.items.map { it.label }, "import")
            assertTrue(header.items.any { it.kind != org.eclipse.lsp4j.CompletionItemKind.Keyword })
            for (keyword in listOf("if", "when", "try", "throw", "true", "false", "null", "object")) {
                assertContains(keywords("val value = " + keyword.take(2) + "<caret>"), keyword)
            }
            for (keyword in listOf("for", "while", "do", "return")) {
                assertContains(keywords("fun use() { " + keyword.take(2) + "<caret>\nval broken ="), keyword)
            }
            assertContains(keywords("fun use() { while (true) { br<caret> } }"), "break")
            assertContains(keywords("fun use() { for (x in 1..2) { con<caret> } }"), "continue")
            assertContains(keywords("fun String.use() { th<caret> }"), "this")
            assertContains(keywords("open class Parent\nclass Child : Parent() { fun use() { sup<caret> } }"), "super")
            assertContains(keywords("val value: sus<caret> () -> Unit = TODO()"), "suspend")
            for (marked in listOf("br<caret>", "contin<caret>", "ret<caret>", "sup<caret>", "val x = cla<caret>",
                "fun use() { pri<caret> }", "private pri<caret>", "fun use() { cons<caret> }", "val x: va<caret> = TODO()", "\"text va<caret>\"", "// va<caret>",
                "\"text \$va<caret>\"", "fun use() { while(true) { fun nested() { br<caret> } } }")) {
                assertTrue(keywords(marked).isEmpty(), "$marked: ${keywords(marked)}")
            }
            for (marked in listOf("val s = \"x\"\ns.tr<caret>", "val s: String? = null\ns?.tr<caret>",
                "String::tr<caret>", "val s = \"x\"\ns.tr<caret>()\nval broken =", "val s = \"x\"\ns.tr<caret>\nval broken =")) {
                assertTrue(keywords(marked).isEmpty(), "$marked: ${keywords(marked)}")
            }
            assertContains(keywords("val broken =\nval x = tr<caret>"), "true")
            assertContains(keywords("val x = \"text \${tr<caret>}\""), "true")
            assertTrue(complete("val localValue = 1\nval x = \"text \${loc<caret>}\"").items.any { it.label == "localValue" })
            val definitions = "fun choose(count: Int, text: String) {}\nfun choose(count: String, other: Boolean) {}\n"
            fun named(marked: String) = complete(definitions + marked).items.filter { it.textEdit.left.newText.endsWith(" = ") }
            for (name in listOf("count", "text", "other")) assertContains(named("choose(<caret>)").map { it.label }, name)
            assertContains(named("choose(1, te<caret>)").map { it.label }, "text")
            assertFalse(named("choose(1, ot<caret>)").any { it.label == "other" })
            assertFalse(named("choose(count = 1, co<caret>)").any { it.label == "count" })
            assertContains(named("choose(count = 1, te<caret>\nval broken =").map { it.label }, "text")
            assertContains(named("choose(te<caret>, count = 1)").map { it.label }, "text")
            assertTrue(complete("fun <T> generic(first: T, second: T) {}\ngeneric<Int>(\"wrong\", sec<caret>)").items.none { it.textEdit.left.newText == "second = " })
            assertEquals("text", complete(definitions + "choose(count = 1, te<caret> = \"value\")").items.single { it.label == "text" }.textEdit.left.newText)
            val trailing = complete("fun trailing(first: Int = 0, second: Int = 1, block: () -> Unit) {}\ntrailing(<caret>) {}")
                .items.filter { it.textEdit.left.newText.endsWith(" = ") }.map { it.label }
            assertContains(trailing, "first")
            assertContains(trailing, "second")
            assertFalse("block" in trailing)
            val varargs = complete("fun varied(vararg values: Int, text: String) {}\nvaried(1, 2, te<caret>)")
            assertTrue(varargs.items.any { it.textEdit.left.newText == "text = " }, varargs.toString())
            for (arguments in listOf("values = intArrayOf(1), te<caret>)", "values = *intArrayOf(1), te<caret>)",
                "*intArrayOf(1), te<caret>)", "1, 2, te<caret>)", "values = intArrayOf(1), te<caret>\nval broken =")) {
                val result = complete("fun varied(vararg values: Int, text: String) {}\nvaried($arguments")
                assertTrue(result.items.any { it.textEdit.left.newText == "text = " }, "$arguments: $result")
                assertTrue(result.items.none { it.textEdit.left.newText == "values = " }, "$arguments: $result")
            }
            for (arguments in listOf("values = arrayOf(\"wrong\")", "*arrayOf(\"wrong\")", "\"wrong\"")) {
                val result = complete("fun varied(vararg values: Int, text: String) {}\nvaried($arguments, te<caret>)")
                assertTrue(result.items.none { it.textEdit.left.newText == "text = " }, "$arguments: $result")
            }
            val function = "fun callable(count: Int, text: String = \"default\") {}\n/* 😀 */ cal<caret>lable"
            val plain = complete(function).items.single { it.label == "callable" }
            assertEquals("callable()", plain.textEdit.left.newText)
            assertEquals(org.eclipse.lsp4j.InsertTextFormat.PlainText, plain.insertTextFormat)
            val snippet = complete(function, true).items.single { it.label == "callable" }
            assertEquals("callable(\${1:count})\$0", snippet.textEdit.left.newText)
            assertEquals(org.eclipse.lsp4j.InsertTextFormat.Snippet, snippet.insertTextFormat)
            assertEquals(9, snippet.textEdit.left.range.start.character)
            assertEquals(17, snippet.textEdit.left.range.end.character)
            for (suffix in listOf("()", "<Int>()", " { }", ".toString()")) {
                val result = complete("fun <T> callable() {}\ncal<caret>" + suffix, true)
                assertTrue(result.items.any { it.label == "callable" }, "$suffix: $result")
                val item = result.items.single { it.label == "callable" }
                assertEquals("callable", item.textEdit.left.newText, suffix)
            }
            assertEquals("callable", complete("fun callable() {}\n::cal<caret>", true).items.single { it.label == "callable" }.textEdit.left.newText)
            val referenceItems = complete("String::sub<caret>", true)
            assertTrue(referenceItems.items.any { it.label == "substring" }, referenceItems.toString())
            assertEquals("substring", referenceItems.items.first { it.label == "substring" }.textEdit.left.newText)
            assertEquals("Local", complete("class Local(val count: Int)\nval x: Loc<caret> = TODO()", true).items.single { it.label == "Local" }.textEdit.left.newText)
            assertEquals("Alias", complete("class Hidden private constructor(val count: Int)\ntypealias Alias = Hidden\nAli<caret>", true)
                .items.single { it.label == "Alias" }.textEdit.left.newText)
            assertEquals(2, complete("class Overloaded { constructor(count: Int); constructor(text: String) }\nOverlo<caret>", true).items.count { it.label == "Overloaded" })
            assertEquals("Local(\${1:count})\$0", complete("class Local(val count: Int)\nLoc<caret>", true).items.single { it.label == "Local" }.textEdit.left.newText)
            KotlinAstParser().use { parser ->
                for (parameters in listOf("optional: Int = 0, required: String", "vararg values: Int, required: String",
                    "optional: Int = 0, required: String, trailing: Int = 1")) {
                    for (declaration in listOf("fun Callable($parameters) {}", "class Callable($parameters)",
                        "class Callable { constructor($parameters) }", "class Target($parameters)\ntypealias Callable = Target")) {
                        val result = complete("$declaration\nCall<caret>\nval broken =", true).items.single { it.label == "Callable" }
                        assertEquals("Callable(required = \${1:required})\$0", result.textEdit.left.newText, declaration)
                        val inserted = result.textEdit.left.newText.replace("\${1:required}", "\"value\"").removeSuffix("\$0")
                        val file = parser.parse("build.gradle.kts", "$declaration\n$inserted")
                        assertTrue(file.syntaxDiagnostics().isEmpty())
                        val binding = parser.bindingContext(file)
                        val call = org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(file.psi,
                            org.jetbrains.kotlin.psi.KtCallExpression::class.java).single()
                        val compilerCall = assertNotNull(binding[org.jetbrains.kotlin.resolve.BindingContext.CALL, call.calleeExpression])
                        val resolved = assertNotNull(binding[org.jetbrains.kotlin.resolve.BindingContext.RESOLVED_CALL, compilerCall])
                        assertTrue(resolved.status.isSuccess, "$declaration: ${binding.diagnostics.all()}")
                        assertEquals("required", resolved.valueArguments.entries.single { it.value.arguments.isNotEmpty() }.key.name.asString())
                    }
                }
            }
            assertEquals("Callable(\${1:first}, required = \${2:required}, last = \${3:last})\$0",
                complete("fun Callable(first: Int, optional: Int = 0, required: String, last: Boolean) {}\nCall<caret>", true)
                    .items.single { it.label == "Callable" }.textEdit.left.newText)
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
                "<caret>" to "import",
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
