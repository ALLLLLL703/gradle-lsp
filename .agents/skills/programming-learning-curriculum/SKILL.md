---
name: programming-learning-curriculum
description: Teach programming language features, libraries, architecture, and supporting math or physics through a staged curriculum. Use when Codex should create a learning outline, teach from small concepts to larger systems, assign coding exercises, and preserve each lesson in project-root learn/xx-*.md files without implementing the learner's source code.
---

# Programming Learning Curriculum

Teach programming through dense, progressive stages and durable Markdown notes. Spend attention on semantics, implementation reasoning, APIs, tradeoffs, debugging, and pitfalls rather than filler.

## Non-negotiable outputs

- Write generated curriculum documents in Chinese unless the user requests another language. Keep code, APIs, identifiers, and exact errors unchanged where needed.
- Before teaching, write a topic-specific `*-outline.md` in the project root with prerequisites, ordered stages, and exercise progression.
- After each mainline lesson, write `learn/xx-<topic>.md` with the goal, concepts, compact code patterns, examples, exercise, and optional hints.
- Route tangents, tool comparisons, and off-mainline prerequisite repair to `extra/<topic>.md`.
- Do not edit source files or complete the learner's implementation. Small illustrative snippets and review feedback are allowed.

Read [references/lesson-format.md](./references/lesson-format.md) only when creating or revising curriculum files.

## Workflow

1. **Scope the target.** Identify the language, requested feature/library/architecture, expected outcome, current level, and missing prerequisites.
2. **Check feasibility when needed.** For unusual or stack-sensitive goals, search current primary documentation and real implementations. If convincing evidence is missing and the route is consequential, warn the user and ask whether to continue. Do not perform this ceremony for ordinary established topics.
3. **Create the outline.** Order stages from language foundations through isolated patterns, library use, architecture integration, and a larger exercise. State why each stage comes next.
4. **Teach one substantial stage.** Explain purpose and semantics, show a compact example, discuss runtime/type-system behavior, API use, design choices, edge cases, and failure modes, then assign code practice. Group related ideas without skipping prerequisites.
5. **Persist immediately.** Write the matching `learn/` note; put a side answer in `extra/` instead.
6. **Review, then advance.** Inspect the learner's code and reasoning, explain the immediate issue and underlying model, and update the path when gaps appear.
7. **Integrate.** Combine completed concepts into modules, data flow, concurrency, boundaries, or a larger application exercise.

## Teaching rules

- Every stage ends with hands-on work that combines concepts, includes a design choice or edge case, and asks for a brief rationale when useful.
- For language features, teach purpose and semantics rather than syntax alone.
- For libraries and frameworks, use current primary documentation, show the smallest setup, and connect APIs to host-language behavior.
- For architecture, introduce the supporting language/library concepts first, then responsibilities, boundaries, data flow, concurrency, and extension points.
- Include only the math or physics needed to understand the implementation.
- Keep chat concise but sufficient for interaction; keep the durable explanation and examples in Markdown.

## Completion check

Confirm that the outline exists, the lesson has an exercise, the numbered note contains usable code examples, off-mainline material is in `extra/`, samples match the target language, and Codex did not take over implementation.
