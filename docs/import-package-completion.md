# Kotlin DSL import completion

`textDocument/completion` completes packages and classes in top-level Kotlin DSL
imports. For example, `import org.gr` offers `gradle.`, `import org.gradle.api.Pro`
offers `Project`, and `import java.util.Map.En` offers `Entry`. Nested classes,
Kotlin `inner` classes, interfaces, objects and enum types are supported. The
advertised trigger is `.`. Callable members, type aliases and auto-imports are not
part of this feature.

At a legal file-header position, `im` / `imp` completes to `import ` with kind
`Keyword`. This also works before existing imports and in incomplete input,
without loading a Gradle model. Keyword completion is not offered inside comments,
strings, expressions, declarations, aliases, or after script statements.

## Behaviour

- The current unsaved document is parsed with a completion identifier at the caret.
  Only a simple-name expression inside a top-level `KtImportDirective`'s imported
  reference is accepted. Comments, strings, alias identifiers, ordinary expressions,
  and a new line after an unfinished import do not enter package/class lookup.
  Keyword placement is independently checked by reparsing a candidate import and
  requiring a top-level import directive at the replacement offset.
- Qualifiers and the typed prefix come from Kotlin PSI. Incomplete imports and
  unrelated syntax errors use the compiler's error recovery.
- Candidates are immediate subpackages from the script's Gradle classpath and JDK,
  obtained through the compiler environment's classpath PSI package finder.
  Class candidates come from compiler package and inner-class scopes. Class IDs
  resolve the package/class boundary without capitalization heuristics. Compiler
  visibility checks use a source-backed origin in the script's declared package,
  excluding inaccessible types, synthetic file facades and enum entries.
- Package items have kind `Module`; types use `Class`, `Interface` or `Enum`.
  Each item has a qualified-name detail and a plain-text `TextEdit` replacing only
  the current segment, including any suffix after the caret. Kotlin identifier
  rendering escapes keyword names. Packages append `.` unless one already follows;
  classes insert their name without a dot, constructor call or additional import.
- Results are deduplicated and sorted, with at most 128 items. Truncation sets
  `CompletionList.isIncomplete`; a more specific prefix recomputes the list.
- Requests use the existing bounded navigation executor and compiler environment
  cache. Document snapshots are captured at request arrival and checked before and
  after work, so stale results are discarded. Model generation changes replace the
  compiler environment rather than retaining a separate completion index. Each
  environment retains at most one lazy import module, constructed from an empty
  source file with the script's package header; it never analyses the incomplete
  script for class completion. Changing the source package replaces this module,
  and closing the environment releases it.

## References

The implementation follows the package-scope and package-part insertion behaviour
of IntelliJ's Kotlin plugin, and JDT LS's package item kind, detail and explicit edit
presentation. It does not insert Java's wildcard/semicolon snippet.

- [IntelliJ Kotlin K2PackageCompletionContributor](https://github.com/JetBrains/intellij-community/blob/826413b22cfe5b5c573662f5d2442f454dbc0b23/plugins/kotlin/completion/impl-k2/src/org/jetbrains/kotlin/idea/completion/impl/k2/contributors/K2PackageCompletionContributor.kt)
- [IntelliJ Kotlin import members contributor](https://github.com/JetBrains/intellij-community/blob/826413b22cfe5b5c573662f5d2442f454dbc0b23/plugins/kotlin/completion/impl-k2/src/org/jetbrains/kotlin/idea/completion/impl/k2/contributors/K2ImportDirectivePackageMembersCompletionContributor.kt)
- [JDT LS import package completion scenario](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/1d02453818deabf9260b9d04f14f6a0a48001cb6/org.eclipse.jdt.ls.tests/src/org/eclipse/jdt/ls/core/internal/handlers/CompletionHandlerTest.java#L400-L434)

Validation: `./gradlew clean test installDist` and `node scripts/validate-rss.mjs`.
The latter exercises successive incomplete, unsaved imports over stdio alongside
navigation and hover, and checks the packaged server's peak RSS against 1 GiB.
