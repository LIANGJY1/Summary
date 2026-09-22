# Atlas UI 深度优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 Atlas 现有信息架构和数据协议的前提下，建立统一视觉系统并重做题库阅读 / 编辑体验。

**Architecture:** 先抽取 UI tokens 和通用组件，再按应用壳、题库页、Markdown 渲染、弹窗反馈四个边界逐步替换散落样式。题库编辑继续直接写回 Markdown，编辑会话保留稳定快照，列表 key 使用源位置而不是题号。

**Tech Stack:** Kotlin、Compose Multiplatform Desktop、Material 3、现有 `AtlasTheme` / `Theme`、JUnit/Kotlin test。

**Spec:** `atlas/docs/superpowers/specs/2026-09-22-atlas-ui-redesign.md`

## Global Constraints

- 保留顶部导航、工作台 / 学习 / 题库 / 设置入口。
- 不改变 Markdown、题库解析、FSRS、搜索和设置数据协议。
- 不引入网络、模型或新的重量级 UI 依赖。
- “今日”不作为 UI 文案或固定日期概念出现。
- 所有可恢复保存错误必须保留用户输入，不能自动关闭编辑界面。

## Task 1: 建立 Atlas UI tokens

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`
- Test: `atlas/app/src/test/kotlin/atlas/ui/UiTokensTest.kt`

**Interfaces:**
- Produces `AtlasUiTokens` / `AtlasTypography` / `AtlasSpacing` 供页面组件使用。

- [x] Step 1: 写测试，验证 tokens 的语义字段完整且深浅主题均有可用值。
- [x] Step 2: 运行测试并修正缺失的布局 imports。
- [x] Step 3: 增加页面背景、面板、卡片、焦点、文本层级、间距和控件高度 tokens；让 `AtlasTheme` 提供当前 tokens。
- [x] Step 4: 运行现有 UI / core 测试，8 项任务通过。

## Task 2: 稳定主窗口壳和导航层级

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/Main.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`
- Test: `atlas/app/src/test/kotlin/atlas/AppRootModelTest.kt`（如已有同类模型则扩展）

**Interfaces:**
- Consumes tokens from Task 1.
- Keeps `AppRoot(store)` and existing tab labels unchanged.

- [ ] Step 1: 写导航状态与徽标可见性的回归测试。
- [ ] Step 2: 验证测试先失败或记录现有行为基线。
- [x] Step 3: 调整顶栏高度、选中指示、徽标尺寸和状态栏层级；不改变导航分支。
- [ ] Step 4: 验证窗口在默认尺寸与窄窗口下无布局溢出（代码测试通过，待人工启动验收）。

## Task 3: 重构题库阅读页组件边界

**Files:**
- Create: `atlas/app/src/main/kotlin/atlas/ui/question/QuestionPage.kt`
- Create: `atlas/app/src/main/kotlin/atlas/ui/question/QuestionCard.kt`
- Create: `atlas/app/src/main/kotlin/atlas/ui/question/QuestionEditor.kt`
- Create: `atlas/app/src/main/kotlin/atlas/ui/question/KnowledgeTreePanel.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/LearningView.kt`
- Test: `atlas/app/src/test/kotlin/atlas/ui/QuestionUiModelTest.kt`

**Interfaces:**
- `QuestionCard(entry, expanded, editing, onToggle, onEdit, onCopy)` renders only one question item.
- `QuestionEditor(entry, onChanged, onExit)` owns inline draft and auto-save state.
- `KnowledgeTreePanel(...)` owns tree selection, expansion and rename callbacks.

- [ ] Step 1: 为稳定列表 key、多题展开和编辑状态隔离写失败测试。
- [ ] Step 2: 验证重复题号仍产生唯一 key，连续导航不会重复提交。
- [ ] Step 3: 抽出题库页面组件，保持搜索范围、全库搜索、目录定位、重命名、新建题目和批量新建行为。
- [x] Step 4: 统一卡片层级：题号 / 标题 / 来源 / 答案 / 操作分层；标题和答案使用独立 `SelectionContainer`，拖动选择不触发卡片点击。
- [x] Step 5: 将编辑态放回同一卡片，沿用阅读态文本样式和答案容器，只增加光标与焦点边界；自动保存失败保留草稿。
- [x] Step 6: 运行题库相关测试，8 项任务通过。

## Task 4: 统一 Markdown 阅读组件

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`
- Test: `atlas/app/src/test/kotlin/atlas/ui/MarkdownRenderingTest.kt`

- [ ] Step 1: 增加表格列数、代码块、引用、标题和列表的渲染回归样例。
- [ ] Step 2: 验证现有异常样式先能被测试捕获。
- [x] Step 3: 统一正文行高、标题层级、表格表头 / 行背景 / 内边距、代码块字体和横向可读性。
- [x] Step 4: 验证答案区、预览浮层和题库页面继续使用同一 Markdown 渲染入口。

## Task 5: 统一弹窗、保存反馈和错误恢复

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/LearningView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/Main.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/PreviewDialog.kt`
- Test: `atlas/app/src/test/kotlin/atlas/ui/UiFeedbackTest.kt`

- [ ] Step 1: 为保存失败保留草稿、前后导航防重复和可恢复错误文案写测试。
- [ ] Step 2: 验证测试先失败。
- [ ] Step 3: 统一弹窗宽度 / 间距 / 按钮顺序；将可恢复错误收敛为 toast 或页面内提示，避免系统级 Error 弹窗遮挡内容。
- [ ] Step 4: 跑完整测试，检查取消、保存、连续点击和外部文件变化场景。

## Task 6: 视觉验收与回归

**Files:**
- Modify: `atlas/PRD.md`（在附录 C 登记本次 UI 优化）
- Review: `atlas/app/src/main/kotlin/atlas/ui/*.kt`

- [ ] Step 1: 使用 1280×820、窄窗口、深色、浅色和两档字号检查主窗口。
- [ ] Step 2: 检查题库：目录、搜索、全文搜索、展开多个、复制标题 / 答案、原地编辑、自动保存、连续前后导航。
- [ ] Step 3: 检查 Markdown：表格、代码块、引用、长文本和中文输入。
- [x] Step 4: 运行完整测试，Gradle 8.14.3 / JDK 17 下 8 项任务通过；窗口人工验收仍需后续执行。
