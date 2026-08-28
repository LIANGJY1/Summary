---
name: android-source-annotator
description: >
  Annotates Android framework / AndroidX / Jetpack source code (Kotlin + Java) with
  high-quality comments directly in the local source files. Use when the user asks to
  add comments, annotate code, write KDoc/Javadoc, explain framework internals inline,
  add WHY-comments, or generate code documentation for Android/AOSP/AndroidX source they
  have checked out locally. Works fully offline on local files — no remote services.
metadata:
  author: Adapted from lhao17202-hue/annotate-code-skill + danilop/walk-the-code + modelcontextprotocol/kotlin-sdk
  source: https://github.com/lhao17202-hue/annotate-code-skill
  version: "1.0"
---

# Android Source Code Annotator

Annotate Android framework-level source code (AndroidX / Jetpack / AOSP) with comments
written **directly into the local source files**. Two capabilities:

1. **Annotate code**: Add KDoc/Javadoc comments to classes/functions/methods, following the project's existing style.
2. **Tribal-knowledge comments**: Add WHY-comments that capture design decisions, invariants, gotchas, and thread-safety facts a reader of framework code needs.

## 0. Core Discipline (read first)

- **Comment into the source file you were given** — never create parallel doc files unless asked.
- **Only add comments. NEVER change code logic, variable names, signatures, or formatting.**
- **Every claim you write in a comment must be traceable to the code immediately above it.** If you are inferring intent, mark it: `// [inferred]`.
- **Respect version context**: AndroidX code differs across releases. If the file you annotate is versioned (e.g., lifecycle-runtime 2.8.7), note the version in the file header comment you add (or keep the existing header).
- **Don't comment the obvious.** If a line/function is self-evident, skip it. Blanket line-by-line commenting is forbidden.

## 1. Determine Scope

Extract three pieces of information:

| Dimension | Meaning | Default |
|-----------|---------|---------|
| **Targets** | Which classes/functions/files | Must be specified; ask if unclear |
| **Depth** | Header-only, method-level, or inline WHY-comments | Method-level + key inline |
| **Style** | KDoc (Kotlin) / Javadoc (Java), comment language | Match existing file style |

### Target Identification
- **User specified names** → Grep for definitions, confirm after finding
- **User specified files** → Read file, extract top-level classes/functions
- **User said "entire project" / "a module"** → Full mode: Glob to count files, batch 10-15 per batch, prioritize core modules
- **Nothing is clear** → Ask, don't guess

### Skip List (framework-specific)
Skip these unless explicitly requested:
- Generated files (`*.pb.*`, databinding/`BR.*`, build artifacts)
- Test files (`test/`, `androidTest/`, `*Test.kt`)
- Files already fully commented (count them in the final report)
- `@hide`/`@Internal` implementation stubs where only the public API matters

## 2. Execution Pipeline

```
Detect language → Scan code → Understand flow → Generate comments → Write to source → Report
```

### 2.1 Detect Language & Comment Style
- **Kotlin** → KDoc (`/** ... */`). See `references/comment-templates.md` §Kotlin.
- **Java** → Javadoc (`/** ... */`). See `references/comment-templates.md` §Java.
- Match the existing comment language (Chinese/English) used in the file. If mixed, use the dominant one.

### 2.2 Scan Code
- Grep + Read the target function/class definition AND body before writing anything.
- **For framework code, trace one hop**: check what the method calls (callees) and who calls it (callers within the file/module) so the comment explains the role in the flow, not just the local behavior.

### 2.3 Understand the mechanism (framework-specific)
Before commenting an Android framework class/method, identify:
- **Entry point**: what triggers this (lifecycle callback, LayoutManager hook, Looper message, snapshot write, Binder call)
- **Thread context**: which thread runs this code (main looper, background executor, binder thread). This is often the single most important fact for framework code.
- **State machine / invariants**: what must hold (e.g., "called before `onCreate` returns", "only valid after `attach()`")
- **Version-specific behavior**: if behavior differs across API levels, note it.

## 3. Comment Content Rules

### 3.1 Header comments (KDoc/Javadoc) — "what" + "why"
For each annotated class/function:
- **Summary** (required): one sentence on purpose — the WHY, not a restatement of the signature.
  - Bad: `// Creates a ViewModelStore.`
  - Good: `// Retains ViewModels across configuration changes — survives Activity recreation but NOT process death.`
- **Parameters** (if any): type + meaning + constraints
- **Returns** (if any): type + meaning
- **Side effects** (when applicable): I/O, state changes, thread handoffs, registered listeners
- **Notes**: edge cases, known pitfalls, thread-safety

### 3.2 KDoc tag placement (Kotlin correctness — from kotlin-sdk skill)
- Primary-constructor `val/var` properties → `@property` in the class KDoc
- Primary-constructor bare parameters (not properties) → `@param`
- Properties declared in the body → inline `/** */` directly above the declaration (not `@property`)
- Function params → `@param`; non-Unit returns → `@return`
- Don't write `@throws` or a "suspend" note; one concise third-person sentence per tag

### 3.3 Inline WHY-comments (tribal knowledge layer)
Add sparse inline comments ONLY where the code hides something the reader can't see:
- **Trade-offs**: `// hand-rolled pool instead of library built-in — needed for custom retry logic`
- **Invariants**: `// must run on main thread — dispatches to background for the actual IO`
- **What would break**: `// reordering this breaks the recycling pool invariant in ViewHolder cache`
- **Non-obvious ordering**: `// must call super.onCreate BEFORE registering observers`
- **Why not the simpler approach**: `// single flat list — index shifts are acceptable at this size`

Mark inferred rationale as `[inferred]`; never present a guess as the original author's intent.

### 3.4 Coverage and restraint (from walk-the-code quality rules)
- Annotate **selectively**: aim for meaningful coverage of non-obvious code — do NOT comment every line.
- Reserve "important" comments (explicitly flagged, e.g. `// [IMPORTANT]`) for **architectural boundaries and key design decisions** — roughly 10-15% of what you comment.
- Skip imports, obvious getters/setters, boilerplate, and pure delegation.

## 4. Write to Source Files

- Use Edit tool, place comments directly above the declaration (header) or on the line above the statement (inline).
- Preserve existing indentation and blank-line style.
- **Already well-commented → skip; incomplete → supplement missing parts only, preserving original content.**
- **Only add comments — never change code logic, variable names, signatures, or formatting.**
- If Edit can't match the exact insertion point, skip and inform the user — never fudge the match.

## 5. Special Cases (Android framework)

- **`@hide` / `@SystemApi` / `@Internal` annotated members**: comment them normally but note the visibility: `// @hide — not part of public API, may change`
- **Lifecycle callbacks** (`onCreate`/`onStart`/`onResume`): the comment should state the contract — what the platform guarantees at that point (e.g., "view hierarchy not yet visible", "state saved")
- **Large/complex methods** (e.g., `performTraversals`, `onLayout`): add step-by-step section comments by logical stage, not per-line
- **Compose internals** (SlotTable, Recomposer): comment the data structure role and the recomposition trigger, not the arithmetic
- **Binder/AIDL stubs**: comment the IPC boundary — what crosses the process, thread pool implications
- **Interfaces/abstract classes**: comment the contract each implementor must honor

## 6. Report

After completion, report concisely:
> Annotated N targets: `LifecycleRegistry.moveToState()`, `ViewModelStore.get()`, ...
> Skipped M (reasons: already well-commented / definition not found / ...)

## 7. Must NOT Do

- NEVER modify code logic, signatures, names, or formatting
- NEVER fabricate line numbers, API behavior, or thread semantics
- NEVER write comments that just restate the code (`// x = x + 1` → banned)
- NEVER claim a version behavior you haven't verified against the actual code
- NEVER add a comment on every line — selective annotation only
- NEVER write `TODO`/`FIXME` unless the user asks
