# SIR-7509 · D档有车速时点击置灰的座椅位置按钮无toast提示
- **提交**：`333636a2` | 2026-09-07 | sgh | Setting | bugfix（需求变更适配）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
D 档有车速时，座椅位置按钮按设计应置灰，但点击它没有任何提示；仅停车（P 档/无车速）场景的提示正常。产品要求"置灰但仍要点得出解释"。

## 根因分析
`VehicleControlFragment` 中两个环节脱节。点击侧 `selectSeatPosition()`：旧判断读取 `settingVehicleService.driveStateManager?.currentDriveState`，仅当 `driveState != DriveState.DRIVELIMITED1.ordinal` 时弹 `R.string.drive_mode_not_adjust_seat_tip` 并拦截——依赖一个全局驾驶状态枚举与按钮置灰条件并非同一套口径。展示侧 `refreshSeatPositionState()`：`disabled` 由 `isSeatControlEnabled || drivingDisabled || seatSelfLearnActive || ... || overheatFaulted` 多条件汇总，按钮 `alpha = if (disabled) 0.3f` 且 `isEnabled = !disabled`——只要任一条件置灰，按钮被 `setEnabled(false)`，点击事件被 Android 直接吞掉，根本到不了 `selectSeatPosition()`，自然无 toast。修复：点击侧拦截条件改为与置灰同源的 `if (drivingDisabled) { log; showToast(tip); return }`；展示侧新增 `val clickable = if (disabled) drivingDisabled else true`，按钮保持 `alpha` 置灰外观但 `isEnabled = clickable`——即"仅因行车禁用而置灰"时按钮仍可点击（用于弹解释 toast），因故障/自学习等其他原因置灰时依旧真禁用，正常态不变。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
```diff
--- application/Setting/.../fragment/VehicleControlFragment.kt
-        val driveState = settingVehicleService.driveStateManager?.currentDriveState
-        log("car driveState：$driveState")
-        if (driveState != com.yadea.apf.vehiclebase.Constants.DriveState.DRIVELIMITED1.ordinal) {
+        if (drivingDisabled) {
+            log("selectSeatPosition blocked: drivingDisabled=true")
             showToast(getString(R.string.drive_mode_not_adjust_seat_tip))
             return
         }
...
+        //置灰有速度运行点击弹toast
+        val clickable = if (disabled) drivingDisabled else true
...
             seatAdjustmentBinding?.apply {
                 val alpha = if (disabled) 0.3f else 1.0f
                 btnPosition1.alpha = alpha
-                btnPosition1.isEnabled = !disabled
+                btnPosition1.isEnabled = clickable
                 btnPosition2.alpha = alpha
-                btnPosition2.isEnabled = !disabled
+                btnPosition2.isEnabled = clickable
                 btnPosition3.alpha = alpha
-                btnPosition3.isEnabled = !disabled
+                btnPosition3.isEnabled = clickable
             }
```

## 为什么能修复
"外观置灰（alpha）"与"可否点击（isEnabled）"解耦后，行车置灰场景的点击能到达 `selectSeatPosition()`，由与置灰同源的 `drivingDisabled` 条件弹出行提示，提示口径和置灰口径一致，不会再出现"置灰原因与提示原因对不上"。风险：故障类置灰（如座椅电机故障）点击仍无反馈，如需解释也要同模式扩展；"置灰可点"的交互要让视觉态与可点态的语义差异经过 UX 确认，避免用户误以为按钮可用。

## 复盘与经验
- `isEnabled=false` 会静默吞掉点击，"置灰但点击要弹提示"的需求必须把视觉置灰（alpha/tint）与事件禁用（isEnabled）分开管理——这是 Android 车机设置页的高频模式。
- 拦截提示的条件要与置灰条件同源（同一布尔字段），两套口径（driveState 枚举 vs drivingDisabled）迟早出现不一致，本例正是先统一了口径才让提示可靠。
- 同单号 SIR-7509 的另一提交 0a779ab0（显示模式背景色）同期处理不同子项，需求变更按子问题拆提交是清晰的实践。
