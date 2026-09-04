# Repository rules

## Scope

- Implement Gradle Kotlin DSL (`*.gradle.kts`) support before Groovy DSL support.
- All Kotlin/Java package names must begin with `xyz.al.gradlelsp`.
- If the build is split into multiple Gradle projects, use the same `xyz.al.gradlelsp` namespace prefix for project/module identities where applicable.
- Keep protocol transport, document storage, compiler analysis, Gradle model loading, and presentation mapping in separate packages.

## AST-first analysis

- All syntax and semantic analysis must be based on Kotlin compiler structures: PSI/AST, FIR, Analysis API, or compiler diagnostics tied to those structures.
- Do not implement language features by regex, substring scanning, line parsing, token guessing, or hard-coded textual pattern matching.
- Raw text may only be used at the protocol boundary, to construct compiler input, and to convert offsets/UTF-16 positions. Semantic decisions must come from the AST/compiler model.
- Tests for every analysis feature must include malformed/incomplete Kotlin DSL input and prove that AST error recovery is used.

## Java reverse engineering

- Use the JD MCP server for Java bytecode decompilation and reverse analysis.
- Do not substitute ad-hoc `javap`, CFR, FernFlower, or manual bytecode inspection when JD MCP is available.
- Treat decompiled output as implementation evidence, not as a stable public API; verify public APIs against source or documentation when possible.

## LSP transport

- In `--stdio` mode, stdout is reserved exclusively for JSON-RPC/LSP frames.
- Send logs and diagnostics about the server process to stderr or the LSP client log channel.
- Keep request handlers non-blocking; compiler and Gradle work must run outside the LSP message-reader thread.
- Publish diagnostics only for the latest known document version; stale analysis must never overwrite newer results.

## Tests and debugging

- Validate every feature and bug fix with an executable scenario before considering it complete.
- Keep automated tests sparse and high-value: prefer a few end-to-end or integration scenarios over one test file per class.
- Do not add unit tests for obvious argument plumbing, trivial accessors, or behavior already exercised by a stronger scenario.
- Prioritize tests that open real `*.gradle.kts` content and cover malformed AST recovery, semantic diagnostics, UTF-16 ranges, document-version races, and LSP publication where relevant.
- Use DAP MCP for runtime-only defects when static inspection and focused tests do not identify the cause.
- Use Neovim MCP for editor integration tests when an actual LSP client interaction is required.

## Git commits

- Every functional addition or bug fix must be committed, even when the diff is approximately fifty lines or smaller.
- Make one focused commit per coherent feature or fix; do not combine unrelated behavior.
- Run the relevant tests before each feature/fix commit and record the command in the commit body when useful.
- Never commit `.gradle/`, `build/`, compiler output, temporary analysis files, editor sockets, credentials, or tokens.
- Do not rewrite, squash, amend, or discard existing user commits unless the user explicitly asks.
