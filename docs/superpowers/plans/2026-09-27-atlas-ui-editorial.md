# Atlas Editorial UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Atlas Macchiato 主题基础上，统一应用色彩层级、页面布局状态和 Markdown 阅读体验。

**Architecture:** 保留既有主题持久化、Markdown 文件协议和导航信息架构；新增由 `ThemeSpec` 派生的交互色 token，页面通过 `AtlasUiTokens`/语义色消费；Markdown 继续使用单一渲染入口，但补强阅读宽度、代码块、引用、表格和任务列表的视觉层级。

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, Material 3, existing Atlas theme/token system.

**Spec:** `atlas/PRD.md` §6.4 and the approved Atlas Editorial design in the current task.

## Global Constraints

- 不改变知识库 Markdown 协议、题目解析、主题 V2 持久化格式或业务导航。
- 所有颜色通过语义 token 使用，不在页面新增散落的硬编码品牌色。
- Markdown 正文保持单一阅读样式；颜色辅助结构，不替代字号、字重和留白。
- 不运行自动化测试或构建；用户会自行验证。

### Task 1: 强化主题语义层

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Theme.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`

- [ ] **Step 1: Add derived interaction colors**
  Add hover, selected, pressed, focus and border helpers derived from the existing `ThemeSpec` surface/accent fields; keep them runtime-derived so V2 records remain 20 fields.

- [ ] **Step 2: Extend shared UI tokens**
  Add content max width, reading width, and consistent corner/spacing values to `AtlasUiTokens`; expose semantic surface helpers for panels and controls.

- [ ] **Step 3: Replace generic translucent fills in shared components**
  Update `StatusChip`, `NavBadge`, dividers and Markdown surfaces to consume the new semantic colors with restrained alpha values.

### Task 2: Atlas shell and page hierarchy

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/Main.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/TodayView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/PreviewDialog.kt`

- [ ] **Step 1: Refine top bar states**
  Keep the existing tabs but use a low-noise surface, selected indicator, hover state, and stronger keyboard focus treatment.

- [ ] **Step 2: Redesign workbench hierarchy**
  Add a compact summary row and turn actionable items into semantic task rows with status rail, count, and primary action; preserve navigation callbacks.

- [ ] **Step 3: Center preview reading column**
  Give the preview dialog a distinct header/content split and constrain Markdown to the shared reading width while retaining editor/AI actions.

### Task 3: High-density pages and controls

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/LearningView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/ToolsView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/ColorSettingsView.kt`

- [ ] **Step 1: Apply surface hierarchy to review and question pages**
  Use consistent panel/card surfaces, selected rows, separators and reading width without changing review/question state transitions.

- [ ] **Step 2: Improve tool and settings cards**
  Use semantic success/warning/error surfaces, clear section headers, and consistent control spacing; retain all existing actions.

- [ ] **Step 3: Improve theme editor previews**
  Make the light/dark preview cards show the same semantic UI and Markdown roles used by the app instead of generic swatches.

### Task 4: Markdown reader polish

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`

- [ ] **Step 1: Enforce editorial reading measure and rhythm**
  Constrain the reader to the shared max width, use stable heading/body line heights, and increase whitespace around structural blocks.

- [ ] **Step 2: Improve code, quote, list and table blocks**
  Add selection-friendly code blocks with language labels, quote backgrounds, aligned list markers, and horizontally scrollable wide tables.

- [ ] **Step 3: Add task-list presentation**
  Render `[ ]` and `[x]` list markers with non-color status cues while retaining ordinary list behavior.

- [ ] **Step 4: Keep dirty-line overlays subordinate**
  Ensure changed-line highlighting does not erase Markdown semantic colors or reduce text contrast.

### Task 5: Static review

**Files:**
- Review all modified Kotlin files and run `git diff --check` only.

- [ ] **Step 1: Search for stale theme/style APIs**
  Confirm removed Markdown-style and old theme references are not reintroduced.

- [ ] **Step 2: Check whitespace and accidental file changes**
  Run `git diff --check` and inspect the diff summary; do not run tests or builds per user instruction.

