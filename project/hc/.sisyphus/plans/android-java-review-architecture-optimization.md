# Honda27M AppStore 全量 Android Java 代码审查与架构优化计划

## TL;DR
> **Summary**: 本计划用于对 Honda27M AppStore Android workspace 进行一次全量、无代码实现的 Java 代码审查与架构优化规划，覆盖内存与稳定性、架构设计、性能优化、IPC 与系统服务四个维度，并输出模块级问题清单、证据包与优化建议。
> **Deliverables**:
> - 模块级四维度审查证据包
> - 跨模块问题台账与严重度分级
> - 模块/目录排期表（按用户展示顺序）
> - 优先级明确的架构优化建议与后续整改路线图
> **Effort**: XL
> **Parallel**: YES - 3 waves
> **Critical Path**: T1 基线与约束 → T2 AppStoreService → T3 hcotaservice → T4/T5 SDK 合同层 → T6/T7 AppStoreApp → T11 跨模块综合

## Context
### Original Request
- 制定一份全量 Android Java 代码审查和架构优化计划。
- 邀请 @oracle 作为架构顾问参与规划。
- 计划必须包含四个维度：
  1. 内存与稳定性（Context 泄漏、Handler 线程阻塞、未注销的监听器）
  2. 架构设计（解耦 God Classes、依赖注入改造、Clean Architecture）
  3. 性能优化（onDraw 对象分配、替换 SparseArray、字符串拼接）
  4. IPC 与系统服务（Binder 缓存、WMS/渲染优化）
- 按模块/目录给出排期表，展示顺序需先 `AppStoreApp`，再 `AppStoreService`。
- 输出验收标准。
- 不写任何代码。

### Interview Summary
- 该 workspace 为多模块 Android Java 工程，包含两条 IPC 栈：
  - App Store：`AppStoreApp` ↔ `AppStoreService` ↔ `AppStoreSDK`
  - HC OTA：`hcotaservice` ↔ `hcotasdk` ↔ `hcotabase`
- `AppStoreService` 为 exported IPC service，存在 `ServiceRegistry`、`HandlerThread`、Receiver 生命周期、GreenDAO、PackageManager/ActivityManager 等高风险热点。
- `hcotaservice` 为 exported OTA service，存在恢复流程、状态机、Room + SharedPreferences 双持久化、WindowManager overlay、power listener 等热点。
- `AppStoreApp` 为 fragmentation-based UI shell，存在全局观察者、对话框管理器、宽度分桶布局、SplitScreen 策略、全局下载状态扇出等热点。
- `AppStoreSDK` / `hcotasdk` 为 thin IPC facade，但 consumers 使用 prebuilt AAR，存在 source/runtime 不一致风险。
- `fragmentation_core` 为本地维护 fork，包含 `TransactionDelegate`、`SupportFragmentDelegate`、`FragmentationMagician` 等高风险框架适配点。

### Metis Review (gaps addressed)
- 已显式区分 **展示顺序** 与 **执行依赖顺序**：排期表按用户偏好先展示 `AppStoreApp`，执行波次仍按服务/契约优先。
- 已增加 **Assumptions / Constraints / Not Covered** 约束，防止“架构优化”膨胀为重写计划。
- 已要求每个模块都输出四维度证据、局部排除项、跨模块依赖说明、严重度台账。
- 已将 `ignoreFailures = true`、prebuilt AAR、恢复流程、hardcoded service target 作为全局 guardrail 纳入计划。

## Work Objectives
### Core Objective
生成一份可直接交给执行代理的全量 Android Java 代码审查与架构优化计划，使执行者无需再决定“先审哪里、审什么、输出什么、如何验收”，即可按模块完成四维度审查并汇总为可执行的优化路线图。

### Deliverables
- `.sisyphus/evidence/` 下的模块级审查证据文件
- 模块级四维度问题清单（Memory/Stability / Architecture / Performance / IPC-System）
- 跨模块风险登记表（P0/P1/P2）
- SDK/Service/Shared DTO 对齐矩阵
- 性能专项清单（`onDraw` 对象分配、`HashMap<Integer,...>` → `SparseArray/LongSparseArray` 替代机会、字符串热路径）
- IPC/系统服务专项清单（Binder 缓存/重连策略、listener cleanup、WMS/overlay 风险、system service ownership）
- 用户可读的排期表（先 `AppStoreApp`，再 `AppStoreService`）
- 优先级、依赖、整改前置条件明确的优化建议总表

### Definition of Done (verifiable conditions with commands)
- `test -f .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "AppStoreApp" .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "AppStoreService" .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "内存与稳定性" .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "架构设计" .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "性能优化" .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "IPC 与系统服务" .sisyphus/plans/android-java-review-architecture-optimization.md`
- `grep -q "展示顺序" .sisyphus/plans/android-java-review-architecture-optimization.md`

### Must Have
- 每个模块都覆盖四个维度，哪怕某一维度输出为“风险低/仅确认边界清晰”。
- 排期表展示顺序必须以 `AppStoreApp` 开头，第二行必须是 `AppStoreService`。
- 每个模块任务都必须给出：热点文件、预期输出、明确排除项、跨模块依赖说明、可执行验收标准。
- 必须显式区分：Review / Recommendation / Remediation Roadmap，禁止默认进入实现。
- 必须包含 baseline evidence commands，但明确说明这些命令**不是**唯一验收门槛。

### Must NOT Have (guardrails, AI slop patterns, scope boundaries)
- 不得承诺任何源码重构、性能修复、DI 实施、IPC 改造会在本计划内完成。
- 不得把测试任务绿灯当作审查完成依据；本仓库多个模块 `ignoreFailures = true`。
- 不得忽略 prebuilt `AppStoreSDK.aar` / `HCOTASDK.aar` 带来的 source/runtime 偏差。
- 不得将 `AppStoreApp`、`AppStoreService`、`hcotaservice`、SDK 层、shared contract 层混成一个大 review 包。
- 不得把 `fragmentation_core` 当普通业务模块处理；仅在上层 wrapper 无法解释风险时深入。

### Assumptions / Constraints / Not Covered
- Assumption: 本计划默认目标是 **review + recommendation + remediation roadmap**，而不是进入实现阶段。
- Assumption: `AppStoreSDK` / `hcotasdk` 需按严格兼容性视角审查，因为 runtime consumers 使用 prebuilt AAR。
- Constraint: Java LSP 在当前环境不可用；执行代理需以 grep/read/命令基线与仓库结构证据为主。
- Constraint: `../Config`、JDK 17、flatDir AAR、外部三方库路径属于跨模块 guardrail，应纳入背景但不展开为独立整改任务。
- Not Covered: 不包含实际代码修复、DI 框架落地、导航框架替换、数据库迁移实施、性能基准压测实现。
- Not Covered: 不将 UI 改版、美术/交互重设计纳入本计划；仅审查其渲染与生命周期风险。

## Verification Strategy
> ZERO HUMAN INTERVENTION - all verification is agent-executed.
- Test decision: **none — review-only plan**；允许执行 baseline evidence commands（Gradle/grep/read）收集背景证据，但不以代码修改测试为主。
- QA policy: Every task produces an evidence artifact under `.sisyphus/evidence/` and validates section completeness + hotspot coverage + dependency notes.
- Evidence: `.sisyphus/evidence/task-{N}-{slug}.md`
- Baseline evidence commands:
  - `./gradlew projects`
  - `./gradlew :AppStoreApp:testDebugUnitTest`
  - `./gradlew :AppStoreService:testDebugUnitTest`
  - `./gradlew :hcotaservice:testDebugUnitTest`
- Baseline caveat: `AppStoreApp` / `AppStoreService` / `hcotaservice` test tasks may still report success while tests fail because `ignoreFailures = true`.

## Execution Strategy
### 展示顺序 vs 执行顺序
- **展示顺序**：遵循用户要求，排期表先展示 `AppStoreApp`，再展示 `AppStoreService`。
- **执行顺序**：遵循依赖与运行时爆炸半径，先服务与契约层，再 App 层，再 fork 层。

### 排期表（用户展示顺序）
| 展示顺序 | 模块/目录 | 实际执行任务 | 执行波次 | 重点目标 | 模块验收摘要 |
|---|---|---|---|---|---|
| 1 | `AppStoreApp/` | T6 + T7 | Wave 2 | 全局观察者、Fragmentation UI、SplitScreen、渲染性能 | 产出四维度审查包 + 宽屏/全局状态/对话框泄漏风险表 |
| 2 | `AppStoreService/` | T2 | Wave 1 | ServiceRegistry、线程/Receiver、God Classes、系统服务 | 产出服务生命周期图 + IPC/Listener/Thread 风险矩阵 |
| 3 | `AppStoreSDK/` | T4 | Wave 1 | IPC facade、hardcoded binding、AAR 偏差 | 产出契约对齐矩阵 + source/runtime 偏差表 |
| 4 | `AppStoreBase/` | T8 | Wave 2 | shared DTO / tools 边界、Android 泄漏 | 产出 shared-base 边界越界清单 |
| 5 | `hcotaservice/` | T3 | Wave 1 | OTA 状态机、恢复流程、Room/SP、WindowManager | 产出恢复路径图 + IPC/系统服务风险清单 |
| 6 | `hcotasdk/` | T5 | Wave 1 | OTA client facade、回调扇出、兼容性 | 产出 OTA 契约兼容性清单 |
| 7 | `hcotabase/` | T9 | Wave 2 | OTA shared contracts / state constants | 产出 shared contract 稳定性清单 |
| 8 | `fragmentation_core/` | T10 | Wave 2 | fork 生命周期/事务/状态保存风险 | 产出 fork 风险豁免与上层规避建议 |

### Parallel Execution Waves
> Target: 5-8 tasks per wave. <3 per wave (except final) = under-splitting.
> 先拆出跨模块基线与契约层，再并行业务模块，最后统一综合输出。

Wave 1: T1 基线与证据协议、T2 AppStoreService、T3 hcotaservice、T4 AppStoreSDK、T5 hcotasdk

Wave 2: T6 AppStoreApp 生命周期/全局状态、T7 AppStoreApp UI/渲染性能、T8 AppStoreBase、T9 hcotabase、T10 fragmentation_core

Wave 3: T11 跨模块综合、优先级归并、整改路线图与最终汇报

### Dependency Matrix (full, all tasks)
| Task | Depends On | Why |
|---|---|---|
| T1 | none | 定义审查基线、证据格式、契约前提 |
| T2 | T1 | AppStoreService 证据需使用统一模板与 baseline caveat |
| T3 | T1 | hcotaservice 同上 |
| T4 | T1 | SDK 合同审查需要 baseline 与导出服务清单 |
| T5 | T1 | OTA SDK 同上 |
| T6 | T1, T4 | AppStoreApp 全局观察者依赖 AppStoreSDK 合同审查 |
| T7 | T1, T6 | UI/渲染问题需建立在 AppStoreApp 生命周期/全局状态清晰之后 |
| T8 | T1, T2, T4 | Shared base 的越界判断依赖服务层与 SDK 层边界确认 |
| T9 | T1, T3, T5 | OTA shared contract 审查依赖 OTA service/SDK 已出边界结论 |
| T10 | T1, T6, T7 | fork 风险需在 app wrapper 层问题澄清后再深入 |
| T11 | T2-T10 | 汇总跨模块冲突、优先级、整改路线图 |

### Agent Dispatch Summary (wave → task count → categories)
- Wave 1 → 5 tasks → `deep`, `unspecified-high`
- Wave 2 → 5 tasks → `deep`, `unspecified-high`, `visual-engineering`（仅用于 UI/渲染审查表达）
- Wave 3 → 1 task → `writing`

## TODOs
> Review + Recommendation = ONE task. Never separate.
> EVERY task MUST have: Agent Profile + Parallelization + QA Scenarios.

- [ ] 1. 建立全局审查基线与证据协议

  **What to do**: 产出全仓拓扑摘要、导出组件清单、prebuilt AAR 消费说明、四维度证据模板、baseline commands 与 `ignoreFailures` caveat。将后续所有模块审查统一到同一严重度分级（P0/P1/P2）和证据结构，并明确性能专项（`onDraw`、`SparseArray` 替代、字符串热路径）及 IPC/系统服务专项（Binder 缓存/重连、WMS/overlay、listener cleanup）检查单。
  **Must NOT do**: 不要修改生产代码；不要把 baseline commands 误写成唯一验收门槛；不要忽略 `AppStoreSDK.aar` / `HCOTASDK.aar` 的 source/runtime 偏差。

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: 需要统一跨模块约束、运行时拓扑和证据协议。
  - Skills: `[]` - 本任务只需要仓库理解与计划执行。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: T2-T10 | Blocked By: none

  **References**:
  - Pattern: `AGENTS.md` - 根级拓扑、构建约束、AAR 消费规则
  - Pattern: `settings.gradle` - 模块边界与 included modules
  - Pattern: `build.gradle` - 根级构建导向与 flatDir 背景
  - Pattern: `AppStoreService/src/main/AndroidManifest.xml` - 导出服务与权限边界
  - Pattern: `hcotaservice/src/main/AndroidManifest.xml` - OTA 导出服务与权限边界

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-1-baseline-protocol.md`
  - [ ] `grep -q "## 模块拓扑" .sisyphus/evidence/task-1-baseline-protocol.md`
  - [ ] `grep -q "AppStoreSDK.aar" .sisyphus/evidence/task-1-baseline-protocol.md`
  - [ ] `grep -q "ignoreFailures" .sisyphus/evidence/task-1-baseline-protocol.md`
  - [ ] `grep -q "P0" .sisyphus/evidence/task-1-baseline-protocol.md`
  - [ ] `grep -q "SparseArray\|onDraw\|字符串" .sisyphus/evidence/task-1-baseline-protocol.md`
  - [ ] `grep -q "Binder\|WMS\|overlay" .sisyphus/evidence/task-1-baseline-protocol.md`

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Baseline evidence packet complete
    Tool: Bash
    Steps: Validate evidence file exists and grep required headings/topology/AAR/test-caveat markers.
    Expected: All grep checks succeed; file contains topology, constraints, evidence template, severity model.
    Evidence: .sisyphus/evidence/task-1-baseline-protocol.md

  Scenario: Missing caveat is detected
    Tool: Bash
    Steps: Run grep for `ignoreFailures` and `AAR` markers after evidence generation.
    Expected: If either marker is missing, task fails and evidence is marked incomplete.
    Evidence: .sisyphus/evidence/task-1-baseline-protocol-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-1-baseline-protocol.md`

- [ ] 2. 审查 AppStoreService：内存/稳定性、架构、性能、IPC/系统服务

  **What to do**: 对 `AppStoreService` 形成完整审查包，至少覆盖 `AppStoreService.java`、`ServiceRegistry.java`、`App.java`、`DownloadManager.java`、`InstalledAppManager.java`、`PreAppManager.java`、`AppStorePackageManager.java`、`ProcessManager.java`、`UxrManager.java`、`AppStorePowerManager.java`、`ProcessUtil.java`。输出：线程与 Receiver 生命周期图、Listener 注册/注销矩阵、God Class 拆分建议、系统服务/API 风险清单、IPC 接口与 manager ownership 表。
  **Must NOT do**: 不要提出直接改造实现代码；不要忽略 GreenDAO 生成物来自 build output；不要用“测试能过”代替稳定性审查。

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: 服务启动、IPC 暴露、线程/监听器/系统服务均集中在该模块。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: T8, T11 | Blocked By: T1

  **References**:
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/AppStoreService.java` - HandlerThread、Receiver 生命周期
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/ServiceRegistry.java` - composition root / IPC interface exposure
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/App.java` - 异步初始化顺序
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/download/manager/DownloadManager.java` - God class / callback fan-out
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/install/InstalledAppManager.java` - inventory/cache authority
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/install/PreAppManager.java` - AppStore ↔ HC OTA bridge
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/install/AppStorePackageManager.java` - PackageInstaller callback
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/restriction/ProcessManager.java` - process control / ActivityManager risk
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/restriction/uxr/UxrManager.java` - restriction listener
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/power/AppStorePowerManager.java` - power listener cleanup
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/util/tools/ProcessUtil.java` - ActivityManager/UsageStats/forceStop reflection

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "ServiceRegistry" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "DownloadManager" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "未注销" .sisyphus/evidence/task-2-appstoreservice-review.md`
  - [ ] `grep -q "P0\|P1\|P2" .sisyphus/evidence/task-2-appstoreservice-review.md`

  **QA Scenarios**:
  ```
  Scenario: AppStoreService review packet complete
    Tool: Bash
    Steps: Validate file exists and grep the four dimensions, hotspot classes, severity markers, and dependency notes.
    Expected: Evidence packet contains all four dimensions and names core hotspots with risk/impact/recommendation.
    Evidence: .sisyphus/evidence/task-2-appstoreservice-review.md

  Scenario: Missing lifecycle or cleanup analysis is caught
    Tool: Bash
    Steps: Grep for HandlerThread, receiver cleanup, listener cleanup, and ProcessUtil/PackageManager sections.
    Expected: Any missing lifecycle/cleanup section causes task failure.
    Evidence: .sisyphus/evidence/task-2-appstoreservice-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-2-appstoreservice-review.md`

- [ ] 3. 审查 hcotaservice：恢复流程、状态机、持久化与系统服务

  **What to do**: 对 `hcotaservice` 输出完整四维度审查包，至少覆盖 `HCOtaService.java`、`HCOtaManager.java`、`HCOtaHandlingProcess.java`、`PackageInstallProcess.java`、`HCOtaPersistenceManager.java`、`HCOtaStateManager.java`、`HCOtaPercentageManager.java`、`HCOtaPowerManager.java`、`HCOtaPackageManager.java`、`HCOtaDatabase.java`、`OtaProgressWindow.java`。重点分析恢复路径、重复执行风险、Room/SP 双持久化、WindowManager overlay 生命周期、listener/cache fan-out。
  **Must NOT do**: 不要忽略 `allowMainThreadQueries()`；不要把 OTA 状态机当普通业务流；不要用 UI overlay 的存在推断其生命周期一定正确。

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: 恢复语义、系统服务、持久化和 IPC 生命周期交织复杂。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: T9, T11 | Blocked By: T1

  **References**:
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/HCOtaService.java` - exported OTA service lifecycle
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/manager/HCOtaManager.java` - composition root / resume logic
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/process/HCOtaHandlingProcess.java` - orchestration core
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/process/PackageInstallProcess.java` - install order / restore side effects
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/manager/HCOtaPersistenceManager.java` - Room + SharedPreferences split
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/manager/HCOtaStateManager.java` - state/listener hub
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/manager/HCOtaPercentageManager.java` - progress cache/listener fan-out
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/manager/HCOtaPowerManager.java` - power listener lifecycle
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/manager/HCOtaPackageManager.java` - file/package operations
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/db/HCOtaDatabase.java` - main-thread DB policy
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/OtaProgressWindow.java` - WindowManager overlay

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "HCOtaManager" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "HCOtaHandlingProcess" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "allowMainThreadQueries" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "WindowManager" .sisyphus/evidence/task-3-hcotaservice-review.md`
  - [ ] `grep -q "恢复" .sisyphus/evidence/task-3-hcotaservice-review.md`

  **QA Scenarios**:
  ```
  Scenario: hcotaservice review packet complete
    Tool: Bash
    Steps: Validate evidence file and grep OTA resume/state/persistence/overlay/system-service sections.
    Expected: Evidence packet explicitly covers restart recovery, Room/SP split, listener fan-out, and overlay lifecycle risk.
    Evidence: .sisyphus/evidence/task-3-hcotaservice-review.md

  Scenario: Missing recovery-path review is detected
    Tool: Bash
    Steps: Grep for restart/recovery/process state and fail if absent.
    Expected: Task fails if evidence does not cover duplicate resume or process restart edge cases.
    Evidence: .sisyphus/evidence/task-3-hcotaservice-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-3-hcotaservice-review.md`

- [ ] 4. 审查 AppStoreSDK：IPC facade、observer fan-out 与 AAR 偏差

  **What to do**: 对 `AppStoreSDK` 输出四维度审查包，覆盖 `AppStoreSDK.java`、`client/AppStoreClient.java`、`impl/UiClientObserverImpl.java`、`impl/DownloadRestrictionObserverImpl.java`。重点分析单例生命周期、Binder/RIPC 绑定缓存与重连策略、CopyOnWrite observer fan-out、hardcoded service target、source/runtime 偏差、DTO/接口与 service 端一致性。
  **Must NOT do**: 不要把 service 内部规则复制到 SDK 审查包；不要忽略 prebuilt AAR 导致的发布依赖；不要默认 facade 改动可立即影响 AppStoreApp。

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: 契约层虽小，但跨模块影响大。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: T6, T8, T11 | Blocked By: T1

  **References**:
  - Pattern: `AppStoreSDK/src/main/java/com/hynex/appstoresdk/AppStoreSDK.java` - facade / interface aggregation
  - Pattern: `AppStoreSDK/src/main/java/com/hynex/appstoresdk/client/AppStoreClient.java` - hardcoded RIPC target
  - Pattern: `AppStoreSDK/src/main/java/com/hynex/appstoresdk/impl/UiClientObserverImpl.java` - UI observer fan-out
  - Pattern: `AppStoreSDK/src/main/java/com/hynex/appstoresdk/impl/DownloadRestrictionObserverImpl.java` - restriction observer fan-out
  - Pattern: `AppStoreService/src/main/java/com/hynex/appstoreservice/ServiceRegistry.java` - service-side exposure counterpart

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "AppStoreClient" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "hardcoded" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "CopyOnWrite" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "AAR" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "Binder\|缓存\|重连" .sisyphus/evidence/task-4-appstoresdk-review.md`
  - [ ] `grep -q "兼容性" .sisyphus/evidence/task-4-appstoresdk-review.md`

  **QA Scenarios**:
  ```
  Scenario: AppStoreSDK contract review complete
    Tool: Bash
    Steps: Validate evidence file and grep hardcoded binding, observer fan-out, AAR skew, and service alignment sections.
    Expected: Evidence packet names facade/API risks and source/runtime mismatch explicitly.
    Evidence: .sisyphus/evidence/task-4-appstoresdk-review.md

  Scenario: Missing contract-alignment analysis is caught
    Tool: Bash
    Steps: Grep for service counterpart and compatibility markers.
    Expected: Task fails if AppStoreService counterpart alignment is missing.
    Evidence: .sisyphus/evidence/task-4-appstoresdk-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-4-appstoresdk-review.md`

- [ ] 5. 审查 hcotasdk：OTA facade、回调扇出与契约稳定性

  **What to do**: 对 `hcotasdk` 输出四维度审查包，覆盖 `HCOtaSDK.java`，明确其 hardcoded target、Binder/RIPC 重连与缓存策略、callback list 生命周期、与 `hcotaservice` / `hcotabase` 的契约对齐要求、兼容性与 source/runtime 偏差。该模块小，但必须形成“低体量高风险”的契约审查结论。
  **Must NOT do**: 不要因为文件少而跳过四维度；不要忽略 callback fan-out 的泄漏和 stale state 风险；不要把 OTA service 内部实现问题直接归因给 SDK。

  **Recommended Agent Profile**:
  - Category: `unspecified-high` - Reason: 范围小但契约敏感。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: T9, T11 | Blocked By: T1

  **References**:
  - Pattern: `hcotasdk/src/main/java/com/hynex/hcotasdk/HCOtaSDK.java` - singleton facade / callback fan-out / target binding
  - Pattern: `hcotaservice/src/main/java/com/hynex/hcotaservice/HCOtaService.java` - service counterpart
  - Pattern: `hcotabase/src/main/java/com/hynex/hcotabase/HCOTAInterface.java` - shared contract
  - Pattern: `hcotabase/src/main/java/com/hynex/hcotabase/HCOTACallBack.java` - callback contract

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "HCOtaSDK" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "HCOTAInterface" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "回调" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "Binder\|缓存\|重连" .sisyphus/evidence/task-5-hcotasdk-review.md`
  - [ ] `grep -q "hardcoded" .sisyphus/evidence/task-5-hcotasdk-review.md`

  **QA Scenarios**:
  ```
  Scenario: hcotasdk contract review complete
    Tool: Bash
    Steps: Validate file and grep callback lifecycle, binding target, shared-contract, and compatibility sections.
    Expected: Evidence packet proves the module has been assessed across all four dimensions despite small size.
    Evidence: .sisyphus/evidence/task-5-hcotasdk-review.md

  Scenario: Thin-module underreview is detected
    Tool: Bash
    Steps: Grep for the four required dimension headings.
    Expected: Task fails if any dimension is missing because the module was treated as “too small to review”.
    Evidence: .sisyphus/evidence/task-5-hcotasdk-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-5-hcotasdk-review.md`

- [ ] 6. 审查 AppStoreApp（核心生命周期与全局状态）

  **What to do**: 输出 `AppStoreApp` 核心层四维度审查包，覆盖 `App.java`、`base/presenter/CommonPresenter.java`、`global/DownloadStateDispatcher.java`、`global/GlobalDialogManager.java`、`mine/settings/AppSettingsFragment.java`、`home/MainActivity.java`。重点分析 Application bootstrap、全局观察者、Dialog dedupe、service startup coupling、Fragmentation shell、Context/Activity 持有与 listener 清理。
  **Must NOT do**: 不要把宽屏渲染与布局细节混进本任务；不要假设全局单例一定安全；不要忽略 `AppStoreSDK` 合同层已识别出的 observer/callback 约束。

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: UI app 的生命周期、全局状态与 IPC side effect 集中在此。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: T7, T10, T11 | Blocked By: T1, T4

  **References**:
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/App.java` - bootstrap / global singleton wiring
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/base/presenter/CommonPresenter.java` - presenter god class hotspot
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/global/DownloadStateDispatcher.java` - app-wide observer aggregator
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/global/GlobalDialogManager.java` - dialog cache + service startup coupling
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/mine/settings/AppSettingsFragment.java` - login gating / update orchestration
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/home/MainActivity.java` - Fragmentation shell / task-root behavior

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "CommonPresenter" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "DownloadStateDispatcher" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "GlobalDialogManager" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "Activity\|Context" .sisyphus/evidence/task-6-appstoreapp-core-review.md`
  - [ ] `grep -q "service startup" .sisyphus/evidence/task-6-appstoreapp-core-review.md`

  **QA Scenarios**:
  ```
  Scenario: AppStoreApp core review complete
    Tool: Bash
    Steps: Validate evidence file and grep lifecycle/global observer/dialog/service-coupling markers.
    Expected: Evidence packet covers leaks, global state, architecture boundaries, and app↔service lifecycle coupling.
    Evidence: .sisyphus/evidence/task-6-appstoreapp-core-review.md

  Scenario: Missing global-state analysis is detected
    Tool: Bash
    Steps: Grep for observer, dialog, and service startup sections.
    Expected: Task fails if any of these core global-state topics are missing.
    Evidence: .sisyphus/evidence/task-6-appstoreapp-core-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-6-appstoreapp-core-review.md`

- [ ] 7. 审查 AppStoreApp（UI 渲染、宽屏与性能热点）

  **What to do**: 输出 `AppStoreApp` UI/渲染四维度审查包，覆盖 `RecommendationFragment.java`、`SplitScreenHelper.java`、`SearchAppActivity.java`、`AppDetailActivity.java`、`DownloadButtonHelper.java`、`src/main/res/layout-w*dp/`。重点分析 onDraw / 对象分配、String.format/字符串拼接、WindowManager/Display 相关计算、宽度分桶布局一致性、fragment transaction state-loss 风险。
  **Must NOT do**: 不要只看 Java 不看 `layout-w*dp`；不要把核心生命周期问题重复记录到本任务；不要直接提出 UI 改版方案。

  **Recommended Agent Profile**:
  - Category: `visual-engineering` - Reason: 涉及渲染行为、布局分桶、UI 性能与 WMS 风险表达。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 不需要实际前端编码技能注入。

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: T10, T11 | Blocked By: T1, T6

  **References**:
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/recommendation/RecommendationFragment.java` - layout listener / banner/recycler / split-screen branches
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/util/SplitScreenHelper.java` - rendering/layout policy
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/recommendation/activity/SearchAppActivity.java` - text watcher / search UI lifecycle
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/recommendation/activity/AppDetailActivity.java` - detail UI / split-screen behavior
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/DownloadButtonHelper.java` - render state logic / string hot paths
  - Pattern: `AppStoreApp/src/main/res/layout-w*dp/` - width-qualified XML variants

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "RecommendationFragment" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "SplitScreenHelper" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "layout-w" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "onDraw\|对象分配\|字符串拼接" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`
  - [ ] `grep -q "WMS\|渲染" .sisyphus/evidence/task-7-appstoreapp-ui-review.md`

  **QA Scenarios**:
  ```
  Scenario: AppStoreApp UI/rendering review complete
    Tool: Bash
    Steps: Validate evidence file and grep split-screen, width-qualified layouts, rendering, and performance markers.
    Expected: Evidence packet covers Java + XML coupling, rendering hotspots, and UI state-loss edge cases.
    Evidence: .sisyphus/evidence/task-7-appstoreapp-ui-review.md

  Scenario: XML/JAVA coupling gap is detected
    Tool: Bash
    Steps: Grep for both `layout-w` and `SplitScreenHelper` in the evidence packet.
    Expected: Task fails if the review only covered Java or only covered XML variants.
    Evidence: .sisyphus/evidence/task-7-appstoreapp-ui-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-7-appstoreapp-ui-review.md`

- [ ] 8. 审查 AppStoreBase：shared DTO、工具层与 Android 边界泄漏

  **What to do**: 输出 `AppStoreBase` 四维度审查包，覆盖 `ApkUtil.java`、`AsyncTaskUtil.java`、`RxJavaUtil.java`、`IpcWrapper.java`、`DownloadStatus.java` 及关键 Parcelable/DTO。重点分析 shared base 是否泄漏 Android 系统能力、package/install/process 语义是否越界、反射/序列化边界是否清晰、工具类是否承担业务责任，并识别 `HashMap<Integer,...>` → `SparseArray/LongSparseArray` 的替代机会与字符串热路径。
  **Must NOT do**: 不要把所有工具类一概视为问题；不要忽略它作为 shared contract 层的复用价值；不要在本任务内重做 service 侧实现审查。

  **Recommended Agent Profile**:
  - Category: `unspecified-high` - Reason: 共享层边界复杂但实现体量中等。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: T11 | Blocked By: T1, T2, T4

  **References**:
  - Pattern: `AppStoreBase/src/main/java/com/hynex/appstorebase/tools/ApkUtil.java` - Android/package/install system-service leakage
  - Pattern: `AppStoreBase/src/main/java/com/hynex/appstorebase/tools/AsyncTaskUtil.java` - thread/handler utility risk
  - Pattern: `AppStoreBase/src/main/java/com/hynex/appstorebase/tools/RxJavaUtil.java` - reactive helper boundary
  - Pattern: `AppStoreBase/src/main/java/com/hynex/appstorebase/bean/vsp/IpcWrapper.java` - reflection/transport boundary
  - Pattern: `AppStoreBase/src/main/java/com/hynex/appstorebase/constant/DownloadStatus.java` - shared state authority

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "ApkUtil" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "IpcWrapper" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "SparseArray\|LongSparseArray\|字符串" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "shared" .sisyphus/evidence/task-8-appstorebase-review.md`
  - [ ] `grep -q "越界\|boundary" .sisyphus/evidence/task-8-appstorebase-review.md`

  **QA Scenarios**:
  ```
  Scenario: AppStoreBase boundary review complete
    Tool: Bash
    Steps: Validate evidence file and grep shared DTO/tool/boundary/system-service markers.
    Expected: Evidence packet distinguishes acceptable shared contracts from boundary leakage.
    Evidence: .sisyphus/evidence/task-8-appstorebase-review.md

  Scenario: Shared-contract overgeneralization is detected
    Tool: Bash
    Steps: Grep for both positive reuse notes and negative leakage findings.
    Expected: Task fails if the evidence file only lists problems without boundary classification.
    Evidence: .sisyphus/evidence/task-8-appstorebase-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-8-appstorebase-review.md`

- [ ] 9. 审查 hcotabase：OTA shared contracts 与状态常量稳定性

  **What to do**: 输出 `hcotabase` 四维度审查包，覆盖 `HCOTAInterface.java`、`HCOTACallBack.java`、`State.java`。明确 OTA shared contract 是否足够稳定、状态常量是否过于靠近实现细节、跨进程/跨模块兼容性是否存在隐患、是否需要 contract freeze 建议。
  **Must NOT do**: 不要因为文件少而略过性能/稳定性维度；不要将 service 端状态机细节直接搬到 shared contract 层。

  **Recommended Agent Profile**:
  - Category: `unspecified-high` - Reason: 共享契约层小而关键。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: T11 | Blocked By: T1, T3, T5

  **References**:
  - Pattern: `hcotabase/src/main/java/com/hynex/hcotabase/HCOTAInterface.java` - OTA IPC contract
  - Pattern: `hcotabase/src/main/java/com/hynex/hcotabase/HCOTACallBack.java` - callback contract
  - Pattern: `hcotabase/src/main/java/com/hynex/hcotabase/State.java` - shared state constants/annotations

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "HCOTAInterface" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "HCOTACallBack" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "State" .sisyphus/evidence/task-9-hcotabase-review.md`
  - [ ] `grep -q "兼容性" .sisyphus/evidence/task-9-hcotabase-review.md`

  **QA Scenarios**:
  ```
  Scenario: hcotabase shared-contract review complete
    Tool: Bash
    Steps: Validate file and grep the three contract files plus compatibility/stability sections.
    Expected: Evidence packet states whether shared contracts are stable, leaky, or over-coupled to implementation.
    Evidence: .sisyphus/evidence/task-9-hcotabase-review.md

  Scenario: Shared-state granularity gap is detected
    Tool: Bash
    Steps: Grep for state-constant ownership and contract-freeze markers.
    Expected: Task fails if state constants are reviewed without ownership/compatibility conclusions.
    Evidence: .sisyphus/evidence/task-9-hcotabase-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-9-hcotabase-review.md`

- [ ] 10. 审查 fragmentation_core：fork 生命周期/事务/状态保存风险

  **What to do**: 输出 `fragmentation_core` 四维度审查包，覆盖 `TransactionDelegate.java`、`SupportFragmentDelegate.java`、`FragmentationMagician.java`，并结合 `AppStoreApp` wrapper 层判断哪些风险应在 app wrapper 规避、哪些才值得进入 fork 级整改建议。输出 fork 风险豁免清单与“优先在 wrapper 修复”的判定标准，并显式覆盖 fragment transaction state-loss、Handler/主线程调度、WMS/渲染耦合风险。
  **Must NOT do**: 不要把所有 Fragment 问题都归咎于 fork；不要提出大规模替换导航框架；不要在未结合 AppStoreApp wrapper 结论前直接建议改 fork。

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: 本地 fork 风险高、爆炸半径大。
  - Skills: `[]` - 纯审查任务。
  - Omitted: `[]` - 无额外技能要求。

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: T11 | Blocked By: T1, T6, T7

  **References**:
  - Pattern: `fragmentation_core/src/main/java/me/yokeyword/fragmentation/TransactionDelegate.java` - transaction controller hotspot
  - Pattern: `fragmentation_core/src/main/java/me/yokeyword/fragmentation/SupportFragmentDelegate.java` - lifecycle/visibility delegate hotspot
  - Pattern: `fragmentation_core/src/main/java/androidx/fragment/app/FragmentationMagician.java` - reflection hack / framework-compat risk
  - Pattern: `AppStoreApp/src/main/java/com/hynex/appstoreapp/base/view/` - 上层 wrapper 对照面

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "## 内存与稳定性" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "## 架构设计" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "## 性能优化" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "## IPC 与系统服务" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "TransactionDelegate" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "SupportFragmentDelegate" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "FragmentationMagician" .sisyphus/evidence/task-10-fragmentation-core-review.md`
  - [ ] `grep -q "wrapper" .sisyphus/evidence/task-10-fragmentation-core-review.md`

  **QA Scenarios**:
  ```
  Scenario: fragmentation_core review complete
    Tool: Bash
    Steps: Validate evidence file and grep fork hotspot classes plus wrapper-vs-fork decision markers.
    Expected: Evidence packet distinguishes fork-level fixes from app-wrapper mitigations.
    Evidence: .sisyphus/evidence/task-10-fragmentation-core-review.md

  Scenario: Fork blame overreach is detected
    Tool: Bash
    Steps: Grep for both `wrapper` and `fork` decision criteria.
    Expected: Task fails if the review attributes all lifecycle issues to the fork without upper-layer screening.
    Evidence: .sisyphus/evidence/task-10-fragmentation-core-review-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-10-fragmentation-core-review.md`

- [ ] 11. 形成跨模块综合结论、优先级与整改路线图

  **What to do**: 汇总 T1-T10 输出，形成一个跨模块 issue register 与 remediation roadmap，按四维度与模块双轴排序，输出：P0/P1/P2 台账、依赖顺序、建议整改波次、必须先做的契约对齐项、可后做的性能微优化项、明确不建议立即推进的重构项。最终产出用户汇报稿，说明“展示顺序”与“执行依赖顺序”的差异。
  **Must NOT do**: 不要新增任何未在 T1-T10 证据中出现的核心结论；不要把路线图写成实施任务单；不要遗漏任何模块的排除项与证据来源。

  **Recommended Agent Profile**:
  - Category: `writing` - Reason: 需要整合证据、排序、形成高可读汇报材料。
  - Skills: `[]` - 纯整理与计划任务。
  - Omitted: `[]` - 不需要实现技能。

  **Parallelization**: Can Parallel: NO | Wave 3 | Blocks: Final Verification Wave | Blocked By: T2, T3, T4, T5, T6, T7, T8, T9, T10

  **References**:
  - Pattern: `.sisyphus/evidence/task-1-baseline-protocol.md` - 全局约束与证据协议
  - Pattern: `.sisyphus/evidence/task-2-appstoreservice-review.md` - AppStoreService 审查包
  - Pattern: `.sisyphus/evidence/task-3-hcotaservice-review.md` - hcotaservice 审查包
  - Pattern: `.sisyphus/evidence/task-4-appstoresdk-review.md` - AppStoreSDK 审查包
  - Pattern: `.sisyphus/evidence/task-5-hcotasdk-review.md` - hcotasdk 审查包
  - Pattern: `.sisyphus/evidence/task-6-appstoreapp-core-review.md` - AppStoreApp core 审查包
  - Pattern: `.sisyphus/evidence/task-7-appstoreapp-ui-review.md` - AppStoreApp UI 审查包
  - Pattern: `.sisyphus/evidence/task-8-appstorebase-review.md` - AppStoreBase 审查包
  - Pattern: `.sisyphus/evidence/task-9-hcotabase-review.md` - hcotabase 审查包
  - Pattern: `.sisyphus/evidence/task-10-fragmentation-core-review.md` - fork 审查包

  **Acceptance Criteria** (agent-executable only):
  - [ ] `test -f .sisyphus/evidence/task-11-cross-module-roadmap.md`
  - [ ] `grep -q "P0" .sisyphus/evidence/task-11-cross-module-roadmap.md`
  - [ ] `grep -q "展示顺序" .sisyphus/evidence/task-11-cross-module-roadmap.md`
  - [ ] `grep -q "执行依赖顺序" .sisyphus/evidence/task-11-cross-module-roadmap.md`
  - [ ] `grep -q "AppStoreApp" .sisyphus/evidence/task-11-cross-module-roadmap.md`
  - [ ] `grep -q "AppStoreService" .sisyphus/evidence/task-11-cross-module-roadmap.md`
  - [ ] `grep -q "hcotaservice" .sisyphus/evidence/task-11-cross-module-roadmap.md`

  **QA Scenarios**:
  ```
  Scenario: Cross-module roadmap complete
    Tool: Bash
    Steps: Validate final roadmap file and grep severity register, schedule ordering explanation, and all module names.
    Expected: Final evidence file contains prioritized backlog, dependency order, user-facing schedule, and explicit deferrals.
    Evidence: .sisyphus/evidence/task-11-cross-module-roadmap.md

  Scenario: Cross-module synthesis gap is detected
    Tool: Bash
    Steps: Grep for modules and severity markers; compare count of reviewed modules against expected eight modules.
    Expected: Task fails if any module or severity tier is missing from the final roadmap.
    Evidence: .sisyphus/evidence/task-11-cross-module-roadmap-error.md
  ```

  **Commit**: NO | Message: `n/a` | Files: `.sisyphus/evidence/task-11-cross-module-roadmap.md`

## Final Verification Wave (MANDATORY — after ALL implementation tasks)
> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
> **Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.**
> **Never mark F1-F4 as checked before getting user's okay.** Rejection or user feedback -> fix -> re-run -> present again -> wait for okay.
- [ ] F1. Plan Compliance Audit — oracle

  **What to do**: 审核 T1-T11 的实际产出是否严格符合本计划：八个模块全部覆盖；排期表展示顺序仍为 `AppStoreApp` → `AppStoreService`；每个模块证据文件都包含四维度、热点文件、排除项、依赖说明、验收结论；最终路线图没有越界为实现任务。
  **Acceptance Criteria**:
  - [ ] `test -f .sisyphus/evidence/f1-plan-compliance.md`
  - [ ] `grep -q "AppStoreApp" .sisyphus/evidence/f1-plan-compliance.md`
  - [ ] `grep -q "AppStoreService" .sisyphus/evidence/f1-plan-compliance.md`
  - [ ] `grep -q "四维度" .sisyphus/evidence/f1-plan-compliance.md`
  - [ ] `grep -q "无越界实现任务" .sisyphus/evidence/f1-plan-compliance.md`

  **QA Scenarios**:
  ```
  Scenario: Plan compliance passes
    Tool: Bash
    Steps: Validate T1-T11 evidence files exist; grep final compliance report for module coverage, four-dimension coverage, schedule-order confirmation, and no-implementation confirmation.
    Expected: Compliance report confirms all planned deliverables exist and match plan scope.
    Evidence: .sisyphus/evidence/f1-plan-compliance.md

  Scenario: Missing module or dimension is detected
    Tool: Bash
    Steps: Compare expected module list (8 modules) and dimension headings against evidence set; fail if any are absent.
    Expected: Verification fails fast when any module packet or required dimension is missing.
    Evidence: .sisyphus/evidence/f1-plan-compliance-error.md
  ```

- [ ] F2. Evidence Quality Review — unspecified-high

  **What to do**: 抽检并评分 T1-T11 证据质量，重点检查每个证据文件是否包含：具体热点文件、风险描述、影响说明、整改建议、严重度、跨模块依赖；避免空泛“review complete”。
  **Acceptance Criteria**:
  - [ ] `test -f .sisyphus/evidence/f2-evidence-quality.md`
  - [ ] `grep -q "风险" .sisyphus/evidence/f2-evidence-quality.md`
  - [ ] `grep -q "影响" .sisyphus/evidence/f2-evidence-quality.md`
  - [ ] `grep -q "建议" .sisyphus/evidence/f2-evidence-quality.md`
  - [ ] `grep -q "P0\|P1\|P2" .sisyphus/evidence/f2-evidence-quality.md`

  **QA Scenarios**:
  ```
  Scenario: Evidence quality passes
    Tool: Bash
    Steps: Validate review report exists and grep for risk/impact/recommendation/severity checks across sampled evidence files.
    Expected: Quality report confirms every evidence packet is actionable rather than narrative-only.
    Evidence: .sisyphus/evidence/f2-evidence-quality.md

  Scenario: Narrative-only evidence is rejected
    Tool: Bash
    Steps: Fail validation if any sampled evidence file lacks hotspot names, severity, or recommendation markers.
    Expected: Verification fails if evidence contains vague completion language without concrete findings.
    Evidence: .sisyphus/evidence/f2-evidence-quality-error.md
  ```

- [ ] F3. Baseline Command & Artifact QA — unspecified-high

  **What to do**: 执行并归档 baseline commands，核对模块列表、测试任务输出、`ignoreFailures` caveat 是否被正确记录；验证 `.sisyphus/evidence/` 产物与计划中的文件命名保持一致。
  **Acceptance Criteria**:
  - [ ] `test -f .sisyphus/evidence/f3-baseline-command-qa.md`
  - [ ] `grep -q "./gradlew projects" .sisyphus/evidence/f3-baseline-command-qa.md`
  - [ ] `grep -q ":AppStoreApp:testDebugUnitTest" .sisyphus/evidence/f3-baseline-command-qa.md`
  - [ ] `grep -q ":AppStoreService:testDebugUnitTest" .sisyphus/evidence/f3-baseline-command-qa.md`
  - [ ] `grep -q ":hcotaservice:testDebugUnitTest" .sisyphus/evidence/f3-baseline-command-qa.md`
  - [ ] `grep -q "ignoreFailures" .sisyphus/evidence/f3-baseline-command-qa.md`

  **QA Scenarios**:
  ```
  Scenario: Baseline command QA passes
    Tool: Bash
    Steps: Run the baseline Gradle commands, store outputs, and validate the QA report records command status plus caveat handling.
    Expected: QA report confirms commands ran, outputs were captured, and weak-green caveat is preserved.
    Evidence: .sisyphus/evidence/f3-baseline-command-qa.md

  Scenario: Artifact naming or caveat mismatch is detected
    Tool: Bash
    Steps: Compare expected evidence filenames and grep for the `ignoreFailures` caveat; fail if naming or caveat documentation is missing.
    Expected: Verification fails if artifacts are missing/misnamed or if Gradle output is reported without caveat context.
    Evidence: .sisyphus/evidence/f3-baseline-command-qa-error.md
  ```

- [ ] F4. Scope Fidelity Check — deep

  **What to do**: 复核最终路线图是否仍然属于“review + recommendation + remediation roadmap”范围，没有偷偷演变成实施清单；检查是否错误扩大到 UI 改版、导航框架替换、数据库迁移实施、三方库升级执行等超范围内容。
  **Acceptance Criteria**:
  - [ ] `test -f .sisyphus/evidence/f4-scope-fidelity.md`
  - [ ] `grep -q "review-only" .sisyphus/evidence/f4-scope-fidelity.md`
  - [ ] `grep -q "未进入实现" .sisyphus/evidence/f4-scope-fidelity.md`
  - [ ] `grep -q "不包含 UI 改版" .sisyphus/evidence/f4-scope-fidelity.md`
  - [ ] `grep -q "不包含框架替换" .sisyphus/evidence/f4-scope-fidelity.md`

  **QA Scenarios**:
  ```
  Scenario: Scope fidelity passes
    Tool: Bash
    Steps: Validate scope-fidelity report exists and grep for review-only boundary plus explicit out-of-scope items.
    Expected: Report confirms no implementation or redesign work slipped into the roadmap.
    Evidence: .sisyphus/evidence/f4-scope-fidelity.md

  Scenario: Scope creep is detected
    Tool: Bash
    Steps: Fail if roadmap or evidence files contain unapproved implementation commitments, rewrite language, or redesign tasks.
    Expected: Verification fails when review work drifts into execution scope.
    Evidence: .sisyphus/evidence/f4-scope-fidelity-error.md
  ```

## Commit Strategy
- Review-only plan: **NO CODE COMMITS**.
- Evidence and plan artifacts may remain as working outputs unless the user explicitly requests a commit.
- If a future execution session needs commits, split by module and concern; do not combine App/UI/Service/SDK/fork changes into one commit.

## Success Criteria
- 用户获得一份**可执行但不实施**的全量审查与优化计划。
- 排期表按用户要求先展示 `AppStoreApp`、再展示 `AppStoreService`，同时明确真实执行依赖顺序。
- 八个模块全部覆盖，且每个模块均有四维度输出要求、证据形式、排除项与验收门槛。
- 明确纳入以下高风险 guardrails：
  - prebuilt AAR source/runtime 偏差
  - `ignoreFailures = true` 导致的弱测试信号
  - exported service / power listener / receiver / callback fan-out 生命周期
  - `HCOtaDatabase.allowMainThreadQueries()` 与恢复流程
  - `fragmentation_core` 的 fork 风险与 wrapper 优先策略
- 最终路线图能将问题归并为：
  - 启动/生命周期治理
  - 持久化/恢复治理
  - IPC/契约治理
  - UI/全局状态与渲染性能治理
