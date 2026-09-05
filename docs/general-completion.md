# Kotlin DSL semantic completion

`textDocument/completion` now serves ordinary Kotlin expression and type positions,
not just imports. The trigger remains `.`; explicit completion requests also work.
This is compiler-backed completion, not IntelliJ completion parity.

## Verified matrix

`GradleSemanticCompletionIntegrationTest` opens real `build.gradle.kts` content
and exercises these scenarios:

| Category | Verified examples |
| --- | --- |
| Lexical scope | Script properties, function parameters, local variables; later locals excluded; nearest local shadows outer/script/member property |
| Functions | Local/script functions and distinct parameter overloads; compiler-rendered signatures |
| Types | Classes, nested classes in expression/type qualifiers, type aliases, type parameters; values excluded in type positions |
| Imports | Renamed class/function/extension imports, default `String` and `listOf` imports |
| Explicit receivers | String methods, Kotlin properties, generic `Box<String>.content`, safe calls; nullable receivers do not expose unsafe members |
| Implicit receivers | Extension-function receiver, member extension dispatch scope, DSL-marker receiver hiding |
| Extensions | Functions and properties, nullable receivers, applicable/inapplicable receivers, generic `List<T>` receiver substitution and upper-bound rejection, receiver overloads retained |
| Smart casts | Stable `Any` narrowed to `String`; stable nullable String narrowed by null check |
| Visibility | Private members and out-of-scope member extensions excluded |
| Real Gradle model | `dependencies { impl… }`, `tasks.reg…`, `repositories { mav… }`, default `Project` type, Java synthetic `project.name` / `project.tasks.names` properties |
| Recovery/presentation | Unclosed block and incomplete property initializer, mid-identifier UTF-16 replacement following an emoji, comments/strings excluded, distinct overload detail and stable sort order |
| Bounds/degradation | 128-item cap with `isIncomplete`; source-backed completion when model loading fails |

`GradleImportCompletionIntegrationTest` additionally covers packages, nested Java
and Kotlin types, visibility, model-generation isolation, import keyword placement,
callable overloads/properties/type aliases in imports, and request arrival/version
races (including queued stale requests and close/reopen). Existing navigation tests
run alongside these scenarios.

## Implementation boundary

- Compiler PSI identifies the completion reference, typed prefix, receiver, type
  position and whole-identifier replacement range. A dummy identifier is inserted
  only into compiler input; errors elsewhere are handled by compiler recovery.
- `BindingContext.LEXICAL_SCOPE`, alias-aware importing scopes and substituted
  receiver member scopes supply candidates. Compiler data-flow information supplies
  stable smart-cast receiver types. Compiler DSL-marker and visibility APIs filter
  candidates. Java property names/descriptors come from compiler synthetic-property
  APIs, not getter-name guessing.
- Applicable extensions are checked by Kotlin subtype constraints. Receiver-inferred
  type parameters are substituted into signatures; parameters not constrained by
  the receiver remain generic. No argument/expected-type ranking or full call
  overload resolution is claimed. Overloads with different receivers or parameter
  signatures remain separate; nearer same-signature declarations suppress outer
  duplicates, and applicable members suppress same-signature extensions.
- Results rank lexical/receiver members before extensions and imported declarations,
  then sort deterministically by compiler name/signature. The response is capped at
  128 items. Lookups enumerate relevant compiler scopes, not an eager whole-classpath
  index. Java synthetic properties are queried by compiler-derived matching names.
- Completion uses the shared DSL-replaceable `DocumentCompletionEngine.complete`
  seam, existing bounded navigation executor, and existing pinned, generation-keyed
  compiler environment cache (one idle model environment). Per-request analysis,
  synthetic scopes and candidates are not retained in a completion cache. Local
  analysis uses the bundled stdlib when Gradle model loading is unavailable.
- LSP mapping remains separate. Items have specific kinds, readable compiler-rendered
  detail, plain identifier insertion and UTF-16 `TextEdit`s. Protocol snapshot checks
  before/after analysis are unchanged.

## Deliberately remaining for Stage 2

Broad context-aware keywords, named arguments, call parentheses/snippets and snippet
capability negotiation are not implemented here. Header `import` keyword completion
retains its existing short-circuit behaviour (including a blank legal header); Stage
2 can merge keyword and semantic candidates through the shared completion seam.
There is no auto-import index, expected-type/smart completion ranking, Groovy engine,
or guarantee of completion in every compiler-error-recovery shape.

## Executable validation

- `./gradlew clean test installDist` — complete test suite and repository-local packaging.
- `./gradlew test installDist` — complete suite after narrowing synthetic-property enumeration.
- `node scripts/validate-rss.mjs` — packaged stdio diagnostics/navigation/hover/imports
  plus seven successive semantic edits: Gradle dependency/task/repository APIs,
  Java property, UTF-16 local replacement, smart cast and generic extension detail.
  Stage 1 checkpoint peak RSS: **966,404 KiB**, below 1,048,576 KiB; semantic requests 269–880 ms.
  Earlier runs were 978,536–1,035,564 KiB. Headroom is narrow; this is a measured
  representative scenario, not an OS-enforced memory guarantee. JVM limits were
  not increased. Packaging writes only `build/install`, not a user/system install.

Compiler API evidence: Kotlin 2.4.10 source JARs, particularly `ScopeUtils.kt`,
`CliTrace.kt`, `ConstraintSystemBuilderImpl.kt`, `ConstraintSystemImpl.kt`,
`DataFlowValueFactoryImpl.kt`, `DslMarkerUtils.kt`, `SyntheticScopes.kt`,
`JavaSyntheticPropertiesScope.kt` and `AllUnderImportScope.kt`.

## Scoped memory follow-up

Member/static/member-extension and import scopes now use compiler function,
variable and classifier **name sets before descriptor lookup**. This avoids the
`getDescriptorsFiltered` implementation's resolve-then-filter path in scopes that
ignore its predicate. Unknown classifier name sets retain a classifier-only
fallback; compiler name-set implementations themselves are not guaranteed lazy.
Lexical/importing scopes retain their alias-aware enumeration. A regression rejects
bulk enumeration and unrelated-name resolution; the full 36-test suite passes.

`GRADLE_LSP_MEMORY_DETAILS=1 node scripts/validate-rss.mjs` optionally records
`jcmd GC.heap_info`, `VM.metaspace`, `Compiler.codecache` and Linux `smaps_rollup`
after the ordinary peak sample, without forced GC or JVM-limit changes. The entire
existing workload is unchanged. Recovery baseline peak was 980,412 KiB; two runs
after name-first lookup peaked at 965,372 and 990,000 KiB. Import-class request
latencies fell from 7–273 ms to 4–10 ms in these samples, but RSS improvement is
not established beyond run-to-run variation.

Post-workload heap used/committed was 341,421/465,680 KiB and 372,414/487,344 KiB;
metaspace committed was 109,888/109,248 KiB and code cache used 40,049/43,137 KiB.
After observational attach, process RSS was 978,096/1,000,740 KiB (anonymous RSS
933,420/956,080 KiB). These non-GC snapshots show substantial committed heap and
native/anonymous residency; they do not establish a retained-object leak or fully
attribute the remaining native footprint. The 57–81 MiB pre-attach margin is still
variable: **robust final memory acceptance remains open**, not satisfied by these
two below-limit samples. Stage 2 must retain the extended workload and validate
again, rather than treating this focused optimization as a memory guarantee.
