# SIR-7848 · 上报驾驶模式切换信号时 Tab 项不高亮/式样不符
- **提交**：`ef41464e` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：置灰逻辑问题）

## 问题
车辆上报驾驶模式切换信号时，车机"驾驶操控"界面的驾驶模式 Tab 项没有高亮显示，选中式样与 UI 稿不一致（被错误置灰）。

## 根因分析
`DrivingFragment` 的 CAN 状态回调中，置灰判定逻辑把"湿滑模式开启则驾驶模式锁定"的判断嵌进了 CAN 使能赋值里：先无条件 `isDrivingModeEnabled = (it == CAN_STATUS_ENABLED)`，随即又用 `settingVehicleService.slipModeSwitchSetting.value` 的当前值二次覆盖为 false。问题在于该覆盖只发生在 CAN 状态回调这条路径；而湿滑模式信号单独变化时走的 `updateDrivingModeStateBySlipMode(isActive)` 是另一套 UI 更新（滑主动时清副标题、不滑时恢复 `isDrivingModeEnabled`），两条路径各自维护状态、时序交错后 `rgDriveMode.setGrayState` 收到过期值——CAN 报文顺序不同时 Tab 被错误置灰、不高亮。本质是"同一 UI 状态有两个互不知情的写入方"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -272,13 +272,10 @@
                 isDrivingModeEnabled = (it == CAN_STATUS_ENABLED)
                 isSlipModeEnabled = (it == CAN_STATUS_ENABLED)
                 isExtremeRangeEnabled = (it == CAN_STATUS_ENABLED)
-                if (isDrivingModeEnabled && settingVehicleService.slipModeSwitchSetting.value == CanSignalConstants.SWITCH_ON) {
-                    isDrivingModeEnabled = false
-                }
                 log("isDrivingModeEnabled: $isDrivingModeEnabled")
                 mBinding.vcNestedScrollView.runWithScrollRestore {
-                    mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
+                    updateDrivingModeGrayState()
                     updateSlipModeGrayBySpeed()
                     mBinding.ssvExtremeRangeSetting.setGrayState(isExtremeRangeEnabled)
                 }
```
```diff
@@ -303,22 +300,18 @@
-    private fun updateDrivingModeStateBySlipMode(slipModeActive: Boolean) {
+    private fun updateDrivingModeGrayState() {
+        val slipModeActive = settingVehicleService.slipModeSwitchSetting.value == CanSignalConstants.SWITCH_ON
         mBinding.vcNestedScrollView.runWithScrollRestore {
             if (slipModeActive) {
                 mBinding.rgDriveMode.setGrayState(false)
                 mBinding.rgDriveMode.setSubTitle(getString(R.string.slip_mode_enabled_driving_mode_locked))
             } else {
                 mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
                 mBinding.rgDriveMode.setSubTitle("")
             }
         }
     }
```
湿滑模式回调同样改为调用统一的 `updateDrivingModeGrayState()`。（注意：置灰参数取 `isDrivingModeEnabled`——即 CAN 使能状态，湿滑激活分支传 false 解除置灰并加"湿滑模式已锁定"副标题，与修改前内部布尔覆盖逻辑不同，以湿滑开关实时值为准。）

## 为什么能修复
湿滑激活时的特殊展示（不置灰 + 副标题锁定提示）从 CAN 回调内的内联 if 和独立的 `updateDrivingModeStateBySlipMode` 收敛为唯一入口 `updateDrivingModeGrayState()`，每次刷新都现场读取 `slipModeSwitchSetting.value`，两条信号路径不再互相覆盖过期状态，Tab 高亮/式样由最新 CAN 状态+湿滑开关唯一决定。隐患：湿滑激活分支 `setGrayState(false)`（不置灰但锁操作）的语义要靠控件 `setGrayState(false)`+点击拦截配合，需确认不可误触。

## 复盘与经验
- 同一 UI 状态（Tab 置灰）只能有一个刷新入口；"在赋值路径里顺手改状态"（CAN 回调内联覆盖 isDrivingModeEnabled）是状态时序错乱的常见源头。
- 多信号联合决定 UI 时，刷新函数应在执行时读取各信号最新值（pull），而不是依赖回调参数拼接（push），可天然消除到达顺序问题。
- 置灰、副标题提示这类"组合展示"要作为一个原子更新单元封装，避免两处更新只改一半。
