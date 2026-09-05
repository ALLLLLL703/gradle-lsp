# Kotlin DSL import package completion

`textDocument/completion` completes the next package segment in a top-level Kotlin
DSL import. For example, `import org.gr` offers `gradle.`, and
`import org.gradle.` offers packages such as `api.`. The advertised trigger is `.`.
This feature completes packages, not classes, members, aliases, or auto-imports.

## Behaviour

- The current unsaved document is parsed with a completion identifier at the caret.
  Only a simple-name expression inside a top-level `KtImportDirective`'s imported
  reference is accepted. Comments, strings, alias identifiers, ordinary expressions,
  and a new line after an unfinished import do not enter package lookup.
- Qualifiers and the typed prefix come from Kotlin PSI. Incomplete imports and
  unrelated syntax errors use the compiler's error recovery.
- Candidates are immediate subpackages from the script's Gradle classpath and JDK,
  obtained through the compiler environment's classpath PSI package finder. No
  dependency-source index or full `BindingContext` analysis is built for completion.
- Each item has kind `Module`, a `(package) qualified.name` detail, and a plain-text
  `TextEdit` replacing only the current segment, including any suffix after the
  caret. Kotlin identifier rendering escapes keyword package names. Insertion adds
  `.` unless the import already contains a following dot, including wildcard imports.
- Results are deduplicated and sorted, with at most 128 items. Truncation sets
  `CompletionList.isIncomplete`; a more specific prefix recomputes the list.
- Requests use the existing bounded navigation executor and compiler environment
  cache. Document snapshots are captured at request arrival and checked before and
  after work, so stale results are discarded. Model generation changes replace the
  compiler environment rather than retaining a separate completion index.

## References

The implementation follows the package-scope and package-part insertion behaviour
of IntelliJ's Kotlin plugin, and JDT LS's package item kind, detail and explicit edit
presentation. It does not insert Java's wildcard/semicolon snippet.

- [IntelliJ Kotlin K2PackageCompletionContributor](https://github.com/JetBrains/intellij-community/blob/826413b22cfe5b5c573662f5d2442f454dbc0b23/plugins/kotlin/completion/impl-k2/src/org/jetbrains/kotlin/idea/completion/impl/k2/contributors/K2PackageCompletionContributor.kt)
- [JDT LS import package completion scenario](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/1d02453818deabf9260b9d04f14f6a0a48001cb6/org.eclipse.jdt.ls.tests/src/org/eclipse/jdt/ls/core/internal/handlers/CompletionHandlerTest.java#L400-L434)

Validation: `./gradlew clean test installDist` and `node scripts/validate-rss.mjs`.
The latter exercises successive incomplete, unsaved imports over stdio alongside
navigation and hover, and checks the packaged server's peak RSS against 1 GiB.
