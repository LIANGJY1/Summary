# SIR-3307 · 主交互增加低速开关判断（需求变更，非缺陷修复）

- **提交**：`4f3db556` | 2026-08-25 | ljl | SystemUI | feature（**需求变更类提交**，mistag 已核对：缺陷库无记录，提交头为 [feature]，内容为新增"行驶触屏锁定"低速开关逻辑）
- **缺陷库**：未关联有效缺陷（ids 引用 SIR-3307，defs 为空）

## 问题（需求背景）
主交互（形态状态机）的触屏限制原来只看车速是否 >0，缺少车控车设新增的"解除行驶触屏锁定"开关及低速（<20km/h）宽容逻辑，需按 V1.1 §5.2 补充近期需求变更。

## 实现分析
新增核心组件 `DriveTouchLockController`（application/SystemUI/.../mainaction/common/DriveTouchLockController.kt，185 行），规则：
- 开关（`Settings.Global[SETTING_UNLOCK_SCREEN]`，ContentObserver 监听）=关：车速 >0 即锁定，=0 立即解除；
- 开关=开：车速 ≥20km/h 立即锁定；<20km/h 需持续 5s 才解除（`unlockRunnable` 迟滞计时，防车速抖动反复锁）。
线程模型上有明确注释：`evaluateLocked()` 锁内算状态、`notifyIfChanged()` 锁外回调，避免与 `PageStateMachine` 的双向调用死锁；首次评估必触发一次回调用于初始同步。

消费端 `PageStateMachine` 改造：
- `setSpeed()` 原有的"S3 车速>0 自动返回"逻辑移交 `onDriveTouchLockChanged(locked)` 统一处理（锁定时 S3 按 `navForeground` 触发 `MapFullscreenBackWithSpeed`/`AppPageBackWithSpeed`，解除时 S2 触发新增事件 `DriveTouchUnlocked` 回 S3）；
- `LyricSwipeDown` 事件（S2/S3 两处）的放行条件从 `speed <= 0` 改为 `!context.isScreenLocked`，语义与锁定规则对齐；
- 新增 `Event.NavExitedForeground`：S2 下导航异常退后台自动回 Android Home；
- `SysUIConfig` 新增 `ID_DRIVE_TOUCH_LOCK`（AL 通知仪表锁定标记）、`SETTING_UNLOCK_SCREEN` 常量；Toast 换用 `showMsgICToast`。

## 为什么这样做
把"是否锁屏"从散落的 `speed` 判断收敛为带开关+迟滞的独立控制器，状态机只消费翻转事件，规则变更只改一处。隐患：开关读取依赖 `Settings.Global`，写入方（设置页）需保证同 key；20km/h、5s 为硬编码常量。

## 复盘与经验
- 该提交在批次清单中混入（引用了单号但缺陷库为空），复盘统计"bugfix 数量"时应剔除此类需求变更提交。
- 行车限制类逻辑的"迟滞窗口"（20km/h+5s）是防状态抖动的标准手法，值得复用。
- 状态机重构思路：条件判断下沉到专用控制器、状态机只处理事件，比在各 handler 里重复 if(speed) 更可维护。
