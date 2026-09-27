# Atlas Settings Center Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 Atlas 设置页为双栏分区设置中心，同时保留现有设置行为和持久化协议。

**Architecture:** `SettingsView` 负责设置中心壳、左侧导航、状态摘要和当前分区路由；每个分区仍在同一文件内通过独立 composable 承载，避免改变现有保存闭包。`ColorSettingsPage` 只统一入口视觉，不改主题编辑逻辑。

**Tech Stack:** Kotlin、Compose Desktop、Material 3、现有 `Theme`/`AtlasPanel`/`AtlasUiTokens`。

**Spec:** `docs/superpowers/specs/2026-09-27-atlas-settings-redesign.md`

## Global Constraints

- 保留默认知识库路径 `/home/liang/Project/MyProject/Summary/knowledge-base`。
- 不清空、不覆盖用户已有设置、主题、自定义主题和知识库文件。
- 不改变 `SettingsStore`、`AppStore`、主题 V2 编解码和索引协议。
- 即时保存控件继续即时保存；文本列表继续通过显式保存按钮提交。
- 不新增外部依赖。

### Task 1: 设置中心壳与分区导航

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt`

**Interfaces:**
- Produces `SettingsDestination`, `SettingsNavItem`, `SettingsSectionHeader` 和 `SettingsSummary` composables，仅由本文件内部使用。

- [ ] **Step 1: 将 `SettingsView` 的单列根布局改为双栏布局**：左侧宽度 190dp，右侧使用 `weight(1f)`，右侧最大内容宽度 960dp；保持现有 `store` 和 `onOpenColors` 参数。
- [ ] **Step 2: 添加设置分区状态**：默认进入 `overview`，导航项为 `overview`、`appearance`、`library`、`privacy`、`review`、`recording`、`about`；切换只改变显示分区，不写入设置。
- [ ] **Step 3: 添加顶部状态摘要**：显示当前主题名称、知识库路径末级目录、`store.scanMessage.value`；摘要只读，不复制编辑控件。
- [ ] **Step 4: 将现有设置区块接入路由**：外观与阅读、知识库、隐私边界、复习、屏幕录制、关于分别迁移到对应分区 composable，保留原有回调和保存代码。

### Task 2: 统一设置分区组件与控件层级

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt`

**Interfaces:**
- `SettingsSection(title: String, description: String?, content: @Composable ColumnScope.() -> Unit)`。
- `SettingsActionRow(content: @Composable RowScope.() -> Unit)`。

- [ ] **Step 1: 用 `SettingsSection` 替换原 `SettingsCard`**：标题、说明和内容使用统一间距；避免每个字段再重复套卡片。
- [ ] **Step 2: 把知识库路径、扫描按钮、题目源文档归并为两个明确组**：`库位置与扫描`、`题目来源`。
- [ ] **Step 3: 把隐私边界归并为 `仅本地` 与 `忽略` 两组**，保留保存后全量重扫行为和图例。
- [ ] **Step 4: 把录屏目录、帧率、码率和脚本入口归并为 `输出`、`质量`、`工具` 三组**，保留原有保存语义。
- [ ] **Step 5: 将“删除/重建/更换知识库”等低频操作使用警示说明和独立操作栏，不改变实际动作。**

### Task 3: 主题入口与设置子页视觉统一

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/ColorSettingsView.kt`

- [ ] **Step 1: 使用同一套页面标题、返回行、最大宽度和分区容器**，使配色页与设置中心一致。
- [ ] **Step 2: 将 Atlas 浅色/深色预览改为双列主题预览区，保留点击即生效。**
- [ ] **Step 3: 将自定义主题列表改为统一行项目：色板预览、主题名、当前模式、编辑/删除操作；不改主题编码和删除逻辑。**

### Task 4: 文档记录与静态检查

**Files:**
- Modify: `atlas/PRD.md`

- [ ] **Step 1: 在 PRD 变更日志记录设置中心双栏信息架构。**
- [ ] **Step 2: 使用 `git diff --check` 检查格式；按用户要求本轮不主动运行编译或测试。**
