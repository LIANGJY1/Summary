# SIR-3388 · 展厅模式插枪不拉起能量中心（feature，误标 bugfix）
- **提交**：`5625f076` | 2026-08-13 | ljl | SystemUI | **feature（非 bugfix，JSON 标记 mistag=true）**
- **缺陷库**：未关联缺陷（单号为 SIR-3388 丢失模式/展厅模式需求族，无 defs 记录）

## 类型说明
提交标题为 `[feature][yadea][SystemUI][SIR-3388]展厅模式插枪不拉能量中心`，属展厅演示模式的功能逻辑补充，非缺陷修复，不强行剖析。

## 改动概要
4 个文件，+26/-5 行：
- `util/SysUIConfig.java`：新增展厅演示 App 常量 `SHOWCASE_PACKAGE_NAME = "com.yadea.apps.showcaseapp"` 与 `SHOWCASE_HOME_CLASS_NAME`；
- `digitalkey/mainaction/common/DataContext.kt`：上下文新增 `showcaseForeground` 字段；
- `digitalkey/DigitalKeyVehicleService.kt`：`onTopActivityChange` 中识别展厅 App 前台并调用 `PageStateMachine.setShowcaseForeground()`；
- `digitalkey/mainaction/PageStateMachine.kt`：插枪 `transitionTo(State.S0_Android_Park)` 的拉起逻辑由 if/else 改为 when——`showcaseForeground` 为真时打日志 `[IGNORE] showcase app is in Foreground, skip goEnergy`，不再调用 `notifier.goEnergy()`。
