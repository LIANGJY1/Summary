# Atlas 萌宠调试深入优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Atlas 萌宠调试的 98 个 case 易读、可追溯、可可靠中止，同时保持设备端注入协议不变。

**Architecture:** 以受控同步脚本从 PetScenarios.kt 与 08 命令清单生成自包含的场景资源，Atlas 维护少量中文展示文案。PetCommandRunner 持有子进程生命周期、超时、取消和有界输出；AppStore 负责编排状态，Compose 页面展示易读场景、步骤和可折叠技术细节。

**Tech Stack:** Kotlin 2.2.20、Compose Desktop / Material 3、kotlinx.coroutines 1.10.2、Python 3 标准库同步脚本、现有 Gradle 8.14.3 / JDK 17 工具链。

**Spec:** `atlas/docs/superpowers/specs/2026-09-30-atlas-pet-debug-deep-optimization.md`

## Global Constraints

- `PetScenarios.ALL` 是 case 编号、顺序与原英文名的唯一权威。
- `project/yadi/pet/08-调试注入命令清单.md` 是设备命令、预期现象与限制的唯一权威。
- Atlas 普通构建和运行读取已生成并打包的资源，不依赖外部 YaDi 源码目录。
- 不改变设备端 `pet_debug_event` Settings 协议、PetIpcTest Intent 协议、状态机语义和用户设备选择设置。
- 执行操作始终绑定开始时选定的设备；运行中不可切换目标。
- 调试事件不自动重试；停止或超时后不再发送后续步骤。
- 所有用户可见的场景名称、步骤和错误反馈使用中文；原英文 case 名和底层命令保留供追溯。
- 只改萌宠调试功能、其资源同步流程和对应 PRD；保留工作区内其他已有文件改动。

## File Map

| 文件 | 职责 |
|---|---|
| `atlas/scripts/sync_pet_scenarios.py` | 从 08 命令表和 PetScenarios 源提取、核对场景，生成/检查 TSV 资源。 |
| `atlas/app/src/main/resources/pet_scenarios.tsv` | 同步生成、随 Atlas 打包的 98 条场景数据。 |
| `atlas/app/src/main/resources/pet_scenario_zh.tsv` | Atlas 维护的 case 编号、中文标题和中文验证摘要。 |
| `atlas/app/src/main/kotlin/atlas/core/PetDebugTools.kt` | 载入并校验场景资源、把 DSL 转成有类型的步骤及可读说明。 |
| `atlas/app/src/main/kotlin/atlas/core/PetCommandRunner.kt` | 子进程执行、取消、超时、输出尾部限制与清理。 |
| `atlas/app/src/main/kotlin/atlas/AppStore.kt` | 调用 runner，维护运行状态、逐步进度和日志状态。 |
| `atlas/app/src/main/kotlin/atlas/ui/ToolsView.kt` | 中文场景目录、分步说明、技术详情、确认和结果反馈。 |
| `atlas/PRD.md` | 设计评审通过后更新实现状态与最终验收记录。 |

## Tasks

### Task 1: 建立受控场景同步与中文展示目录

**Files:**
- Create: `atlas/scripts/sync_pet_scenarios.py`
- Create: `atlas/app/src/main/resources/pet_scenario_zh.tsv`
- Generate: `atlas/app/src/main/resources/pet_scenarios.tsv`
- Modify: `atlas/app/src/main/kotlin/atlas/core/PetDebugTools.kt`

**Interfaces:**
- Script CLI: `python3 atlas/scripts/sync_pet_scenarios.py [--check]`; 默认写入生成资源，`--check` 只比对并返回非零状态。
- Kotlin model: `Scenario(number, stage, sourceName, titleZh, summaryZh, sequence, expected, limitation)`。
- Kotlin loader: `PetDebugTools.scenarios: List<Scenario>`；资源条数、连续编号和未知 DSL token 不合法时抛出包含 case 编号的明确错误。

- [ ] 解析 `08-调试注入命令清单.md` 的 98 行 case 表：编号、反引号 case 名、命令序列、预期表现和可选设备限制；跳过 Markdown 表头与分隔行。
- [ ] 解析 `PetScenarios.kt` 中 `PetScenarios.ALL` 的有序 `Case("...")` 名称，校验数量为 98、编号连续且名称逐行相同；错误输出列出首个不匹配编号和两侧名称。
- [ ] 建立 `pet_scenario_zh.tsv`，为 1..98 每条写中文标题与一句验证摘要；保留英文来源名作为同步键，不把英文名复制成中文翻译的替代品。
- [ ] 对场景命令 DSL 做白名单校验：`fresh_on`、`pet <已支持type/value>`、`ipc <整数>`、`media_play`、`media_pause`；未知 token、空命令、无效 festival/send_failed 参数均指出 case 编号并失败。
- [ ] 以 UTF-8 TSV 生成 `pet_scenarios.tsv`，字段顺序固定为编号、阶段、英文名、原序列、预期、限制；字段中的反斜线、制表符和换行统一转义。
- [ ] 在 `PetDebugTools` 中从 classpath 读取两份 TSV，校验中文元数据恰好覆盖 1..98，按编号合并；构建运行不读机器上的源 Markdown/Kotlin 文件。
- [ ] 执行同步命令生成资源，再执行 `--check`；核对差异为空、98 条数量与编号完整、无未识别 DSL token。

### Task 2: 把命令序列转换为可解释的步骤

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/core/PetDebugTools.kt`
- Modify: `atlas/app/src/main/resources/pet_scenario_zh.tsv`

**Interfaces:**
- `Command(label, args, workingDirectory, timeoutMs, userTitle, userExplanation)` 继续保存 argv，并补齐面向用户的步骤标题与解释。
- `sealed interface Step`: `Run(command: Command)`, `Wait(millis: Long, userTitle: String, userExplanation: String)`, `Note(text: String)`。
- `fun scenarioSteps(scenario, adbPath, serial): List<Step>` 生成用户步骤说明和可折叠 `technicalCommand`。
- `fun describeToken(token: String): StepDescription` 为事件和特殊动作提供一致中文说明。

- [ ] 定义 Settings 注入、PetIpcTest IPC、媒体控制、清理进程和等待步骤的用户文案映射；例如 `pet play_done` 显示“模拟动作完成回调”，`ipc 14` 显示“通过 PetIpcTest 发送生日事件”。
- [ ] 为发送失败动作码显示动作中文名与代码；为 festival 0..5 显示节日名称；未知码在同步校验阶段被拒绝。
- [ ] 将 `fresh_on` 展开为清空残留调试键、停止 Launcher、启动 HOME Launcher、等待启动稳定四个有名步骤。
- [ ] 从场景序列产生 typed steps 和安全执行 argv；不再用“未知步骤”Note 让非法 token 继续通过。
- [ ] 对 case 2、33、34、76 等 limitation 建立结构化展示字段，使页面可以单独呈现“设备无法精确复现”的原因。
- [ ] 运行场景同步 `--check`，确认步骤词典覆盖全部 98 条序列使用的 token。

### Task 3: 实现可取消、有界输出的 PetCommandRunner

**Files:**
- Create: `atlas/app/src/main/kotlin/atlas/core/PetCommandRunner.kt`

**Interfaces:**
- `suspend fun run(steps: List<PetDebugTools.Step>, onUpdate: (PetRunUpdate) -> Unit): PetRunResult`
- `fun cancelCurrent()` 立即强制终止当前进程并关闭流。
- `StepResult(outcome: Outcome, exitCode: Int?, outputTail: List<String>, truncated: Boolean, elapsedMs: Long)` 表示单条外部命令结果。
- `PetRunUpdate(stepIndex: Int, totalSteps: Int, stepTitle: String, stepResult: StepResult?)` 用于更新当前步骤和命令完成结果。
- `PetRunResult(outcome: Outcome, failedStep: Int?, outputTail: List<String>, truncated: Boolean, elapsedMs: Long, message: String?)`；`Outcome` 包含 `SUCCESS`、`FAILED`、`TIMED_OUT`、`CANCELLED`、`START_FAILED`。
- 命令与步骤类型由 `PetDebugTools` 定义；runner 只消费 `PetDebugTools.Command` 和 `PetDebugTools.Step`。

- [ ] 将 ProcessBuilder 启动、工作目录、超时和 stdout/stderr 合流封装到 runner；构造器接收输出最大行数、最大字符数，默认 80 行 / 16 KiB。
- [ ] 并发读取子进程输出，持续排空完整管道；只把最近的有界尾部保存在内存，淘汰旧行时累计 `truncated=true`。
- [ ] 用可取消等待等待进程退出；在 `finally` 中关闭输入流、reader 并清理当前进程引用。
- [ ] `cancelCurrent()` 对活动进程调用 `destroyForcibly()`，关闭其输出流；取消流程不会继续执行后续命令。
- [ ] 超时、启动失败、非零退出和取消映射为不同 `StepResult` / `PetRunResult`，超时时同样杀进程、关流并收集当前尾部。
- [ ] 由 `run(steps, onUpdate)` 顺序执行步骤；每步前检查协程取消状态，Wait 使用 `delay`，结束时只返回一个最终 `PetRunResult`。
- [ ] 对 logcat 提供同一生命周期管理接口，停止/流结束/异常都释放进程和 reader。

### Task 4: 接入运行状态与设备操作

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/AppStore.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/core/PetCommandRunner.kt`

**Interfaces:**
- `PetRunState(targetSerial, title, status, currentStep, totalSteps, outputTail, outputTruncated, elapsedMs, resultMessage)`。
- `runPetScenario(scenario)` 和 `runPetQuickAction(action)` 启动唯一萌宠流程；`stopPetDebug()` 终止当前进程并停止后续步骤。

- [ ] 用 `PetCommandRunner.run` 替换 `AppStore.execPetCommand` 中的 `CompletableFuture.readText + Process.waitFor`；AppStore 把 `PetRunUpdate` 映射到 `PetRunState`。
- [ ] 开始运行时快照 `toolboxSerial`；运行期间继续禁用设备切换、其他启动类操作和重复 case 启动。
- [ ] 每个步骤开始、输出更新、等待、结束和取消都回写中文运行状态；运行完成后保留可复制的技术详情和有界输出。
- [ ] 取消时先杀活动子进程，再取消 job；确保流程不能在当前 adb 命令完成后继续发下一事件。
- [ ] 让错误文案分别解释 adb 缺失、设备掉线、启动失败、非零退出、超时和用户停止；非零输出尾部可展开查看。
- [ ] 将萌宠 logcat 通过 runner 的 `startLogcat` / `stopLogcat` 管理，保持现有设备 serial 和 tag 过滤，不改变其他工具的 logcat 生命周期。
- [ ] 执行 Atlas Kotlin 编译，解决本任务引入的编译错误。

### Task 5: 重做 case 阅读与执行反馈

**Files:**
- Modify: `atlas/app/src/main/kotlin/atlas/ui/ToolsView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/AppStore.kt`（只在页面需要独立状态字段时）

**Interfaces:**
- `PetDebugCard(store)` 消费 `PetDebugTools.scenarios` 的双语元数据、typed steps 与 `PetRunState`。
- 新增私有组件 `PetScenarioRow`、`PetScenarioDetails` 和 `PetRunOutput`，仅服务萌宠页面。

- [ ] case 收起行以中文标题为主，显示编号、阶段、中文验证摘要；英文原名放在次要文字或详情里。
- [ ] 搜索同时匹配编号、中文标题、中文摘要、英文 case 名、命令 token 和设备限制；阶段 chips 的现有行为保留。
- [ ] 展开区按序显示前置条件、中文步骤卡片、预期现象、设备限制；原序列与实际 adb argv 收入折叠的“技术详情”。
- [ ] 快捷命令分组增加用途说明，清楚区分 Settings 调试注入、PetIpcTest 正式 IPC、发送失败模拟、媒体会话和构建安装。
- [ ] 执行确认展示设备 serial、会中断/覆盖的行为、case 预计步骤与限制；只在有副作用的快捷操作或运行 case 前确认。
- [ ] 进度区展示中文状态、步骤计数和耗时；用户停止显示“当前命令已结束，后续步骤未执行”；输出达到上限时显示“更早输出已截断”。
- [ ] 保留专用日志、目标选择和当前页面层级；在 1280×820 和窄窗口下检查文本换行、按钮可见和长列表滚动。
- [ ] 运行 Atlas Kotlin 编译，修复 UI 类型、Compose 状态或资源读取问题。

### Task 6: 更新 PRD 并完成变更审阅

**Files:**
- Modify: `atlas/PRD.md`
- Review: `atlas/docs/superpowers/specs/2026-09-30-atlas-pet-debug-deep-optimization.md`
- Review: `atlas/docs/superpowers/plans/2026-09-30-atlas-pet-debug-deep-optimization.md`

- [ ] 将 §6.4.23 从“设计中/待实现”改成与实际行为一致的实现说明，记下同步资源路径与取消/输出上限。
- [ ] 在附录 C 记录最终提交、实际执行的同步检查与编译命令；没有执行的检查不标为通过。
- [ ] `git diff --check` 检查本任务文件；审阅最终 diff，确认只涉及萌宠调试、资源同步和 PRD。
- [ ] 在本轮交付前明确说明：完成了什么、实际跑过哪些非测试验证、哪些设备侧验收尚未执行。

## Commit Boundaries

- Task 1–2: `feat(atlas): localize and synchronize pet scenarios`
- Task 3–4: `feat(atlas): make pet command runs cancellable`
- Task 5: `feat(atlas): explain pet debug cases in Chinese`
- Task 6: `docs(atlas): record pet debug optimization`

每次提交只暂存本计划列出的文件；不提交 Summary 中其它已修改或未跟踪文件。
