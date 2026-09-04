# Lesson Format

Use this file when writing the master outline and the `learn/xx-*.md` lesson notes.

## Master outline template

Use a structure close to this:

```md
# <Topic> Learning Outline

## Goal

Describe what the learner should be able to build or explain after finishing the curriculum.

## Target Language

State the programming language used for examples and exercises.

## Prerequisites

- prerequisite 1
- prerequisite 2

## Section Plan

### 01. <Section title>

- Why now:
- Concepts:
- Exercise:

### 02. <Section title>

- Why now:
- Concepts:
- Exercise:
```

## Per-section `learn/` note template

Use a structure close to this:

````md
# 01 <Section Title>

## Goal

One sentence describing what this section teaches.

## Core Concepts

- concept 1
- concept 2

## Minimal Patterns

```<language>
// short focused example
```

## Exercise

Describe the coding task the learner should complete after the lesson. Make it harder than a toy exercise: combine multiple ideas, include at least one tradeoff or edge case, and require the learner to apply the section in a realistic way.

## Review

- question 1
- question 2
````

## Naming guidance

- Use names such as `learn/01-basics.md`, `learn/02-collections.md`, `learn/03-http-client.md`.
- Keep the numeric prefix zero-padded to preserve lexical ordering.
- Match the file name to the section title closely enough that the learner can navigate by glance.

## Teaching progression examples

### If the topic is a language feature

Progression example:

1. motivation
2. syntax
3. simple examples
4. common mistakes
5. practice
6. integration with nearby features

### If the topic is a library or framework

Progression example:

1. problem the library solves
2. required language prerequisites
3. setup and dependencies
4. smallest working example
5. common configuration or data model patterns
6. realistic exercise

### If the topic is architecture

Progression example:

1. problem statement
2. smallest building blocks
3. boundaries and responsibilities
4. library choices and why
5. composed architecture
6. integration exercise

## Distillation rule

The `learn/` files are not full transcripts. They are compressed notes for reuse.

Compared with earlier versions, these notes should be denser: include more key ideas, more implementation detail, and a more specific exercise brief, while still staying concise.

Always preserve:

- what the learner should remember
- what code shape they should recognize
- what exercise they should do next

Remove:

- repetitive exposition
- long detours
- broad historical background unless directly useful
