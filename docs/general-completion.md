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
  detail, capability-negotiated callable snippets or plaintext `name()` insertion and UTF-16 `TextEdit`s. Existing call syntax, callable references, type positions and simple string-template entries retain identifier-only edits. Protocol snapshot checks
  before/after analysis are unchanged.

## General completion contexts and insertion

- Keyword candidates come from Kotlin `KtTokens.KEYWORDS`/`SOFT_KEYWORDS`. Each
  matching candidate is reparsed at the PSI caret with a small compiler-input
  continuation; the parser must recognize the keyword outside an error node.
  PSI declaration/type/expression context, compiler modifier target predicates/compatibility and lexical
  receiver/function/loop boundaries further restrict candidates. Verified headers,
  declarations and modifiers include `package`, `import`, `val`, `var`, `fun`,
  `class`, `interface`, `object`, `typealias`, visibility, modality, `data`,
  `inline` and `suspend`; function types support `suspend`.
- Expressions include `if`, `when`, `try`, `throw`, literals and object expressions;
  statement contexts support `for`, `while`, `do`. Unlabelled `return` is offered
  inside block-bodied named functions, `break`/`continue` inside loops without
  crossing function/class boundaries, `this` with an implicit receiver and `super`
  inside a class receiver context. Comments, character/string text and simple
  `$name` template entries exclude keywords; `${expression}` supports ordinary
  semantic and keyword completion. Malformed initializers/unclosed blocks are tested.
- Legal header `import` is merged with keywords and useful semantic candidates,
  including an empty header with the real Gradle model. Keywords rank ahead of
  large receiver scopes so truncation does not hide header keywords. Import directive bodies still use import-only lookup.
- Named arguments use compiler resolved calls and ambiguous reference targets,
  stable parameter names, supplied positional/named argument PSI and compiler
  subtype constraints (including explicit type arguments). Supplied parameters
  (including trailing lambdas and repeated positional varargs) are excluded, mismatching overloads rejected, and distinct viable signatures
  retained. Existing `=` is preserved. No source-text callee guessing is used.
- Function/method and concrete constructor items show compiler signatures. With
  negotiated `completionItem.snippetSupport`, required parameters become escaped
  snippet placeholders and a final tab stop. Defaults/varargs are omitted.
  Plaintext clients receive `name()` without snippet syntax. Existing parentheses,
  type arguments, trailing lambdas, receiver dots, type positions and callable
  references are not overwritten or doubled. `String::substring` uses the compiler
  double-colon receiver type; constructor overloads remain separate.

This is not exhaustive IntelliJ parity: no auto-import index, expected-type/smart
ranking, lambda-body generation, labelled/nonlocal return completion, complete
modifier applicability checking, or guarantee for every malformed PSI recovery
shape. Named arguments depend on targets retained by compiler recovery (no separate
IDE resolve-all-candidates session); Java unstable/synthetic parameter names are
excluded. A 128-item response may omit lower-ranked extensions at a blank prefix.
Groovy and other LSP features remain outside this change.

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

## Stage 2 validation checkpoint

The integrated suite additionally verifies keyword negatives, string-template
expressions, malformed named calls, overload constraints and already-supplied
arguments, snippet negotiation/plaintext fallback, constructor overloads, existing
call syntax, callable references and UTF-16 edits. Existing scope/shadowing,
real-Gradle receiver and running/queued stale-snapshot scenarios remain intact.
The packaged workload retains every previous operation and now adds nine successive
contexts: loop/literal/type keywords, named arguments, existing calls, constructors,
callable references, string templates and blank-prefix Gradle receiver completion.
The first completed extended run peaked at **1,009,332 KiB** (semantic requests
252–1,121 ms); this narrow margin is not robust final memory acceptance. The
validation script now clears its diagnostics timeout after completion rather than
keeping Node alive for three minutes. No workload or JVM limits were reduced.

### Bounded completion presentation

Semantic candidates now stream through shadow filtering and presentation with a
129-item lookahead, rather than resolving every candidate's constructors and
rendering every signature before truncating. A 140-class constructor completion
scenario verifies bounded results, `isIncomplete` and insertion metadata. This
preserves compiler scope enumeration and ranking; it avoids unnecessary resolution
and presentation of candidates that cannot fit the response.

On the unchanged 16-context packaged workload, full presentation peaked at
1,009,332–1,028,832 KiB, with blank Gradle receiver completion 1,121–1,208 ms.
After bounded presentation: 981,748–1,008,248 KiB, blank completion 765–821 ms.
This is a concrete avoided allocation path, not sufficient RSS margin by itself.
Optional memory details now include `VM.native_memory summary`; tracking is only
available when explicitly enabled for observation via
`JAVA_OPTS=-XX:NativeMemoryTracking=summary`, never required for normal launch.

### Packaged JIT memory bound and final measurement

The host JVM selected **12 JIT compiler threads** by default. An observational NMT
run measured compiler arena peak **119,746 KiB** and retained Arena Chunks
**61,661 KiB**, with 28 process threads. Limiting `CICompilerCount=2` reduced those
observations to **41,794 / 22,026 KiB** and 18 threads. This is a bounded JVM
compilation-concurrency change, not a heap increase, forced GC or smaller workload.
Two environment-override experiments peaked at **816,312 / 828,084 KiB**.

The package now ships `-XX:CICompilerCount=2` alongside the unchanged heap,
metaspace, direct memory, code-cache and stack limits. After
`./gradlew clean test installDist`, two ordinary `node scripts/validate-rss.mjs`
runs (no override or NMT) passed at **831,640 / 831,672 KiB** peak RSS, leaving
about **212 MiB** below 1 GiB. All previous diagnostics/navigation/hover/import
operations and all 16 successive general completion contexts were retained.

| Production measurement | Run 1 | Run 2 |
| --- | --- | --- |
| Peak RSS (KiB) | 831,640 | 831,672 |
| Threads | 18 | 18 |
| External definitions (ms) | 1,556 / 1,242 / 914 | 1,547 / 1,283 / 972 |
| External hover (ms) | 1,112 | 1,130 |
| General completions (ms) | 244–1,000 | 229–857 |
| Blank Gradle receiver completion (ms) | 1,000 | 857 |

Repeated reductions and compiler-arena attribution provide substantially better
representative headroom than the Stage 1 checkpoints; they are not an OS-enforced
RSS guarantee on all JDKs, hosts or workspaces. Neither user installation nor push
is part of validation. The build-file commit contains only the approved JVM flag;
pre-existing user formatting remains unstaged and otherwise unchanged.

Keyword follow-up tests also cover real-model blank headers, `package`, repeated
visibility modifiers and invalid local `const`. Modifier target predicates are
compiler-defined, with PSI-derived class targets and compiler type-modifier token
sets. The packaged client negotiates snippets and checks callable snippet format
and exact constructor placeholders, while integration tests retain plaintext
capability fallback and identifier-only existing-call/callable-reference edits.

Final repeated checks after keyword target/ranking refinements and packaged snippet
negotiation: `./gradlew clean test installDist` passed (37 tests), followed by two
ordinary RSS runs at **815,836 / 837,280 KiB** with 18 threads. General completions
were **240–849 / 308–824 ms**; external definitions **964–1,476 / 964–1,398 ms**;
external hover **1,073 / 1,066 ms**. No `JAVA_OPTS`, NMT, forced GC or reduced
workload was used in these final acceptance runs.
