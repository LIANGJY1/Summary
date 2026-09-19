# SIR-7534 · 开启极致续航开关缺少 Dialog 弹窗提示
- **提交**：`22b787f2` | 2026-09-17 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
在设置中打开"极致续航"开关时，没有按 UI 式样弹出二次确认 Dialog，开关直接生效。

## 根因分析
原 `DrivingFragment` 中极致续航开关的监听是"一杆到底"：`setupSwitchListener` 里无论开还是关，都直接把 `SWITCH_CMD_ON/OFF` 通过 `settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_ECOFUNCSTS, state)` 下发车控，完全没有开启前的确认弹窗逻辑——即提交信息所说"之前缺失 UI"。由于开启极致续航会大量关闭耗电功能，属于高影响操作，UI 规范要求先弹窗确认。缺陷库写"ui未释放/ui释放代码修改"，与 diff 实际（补齐缺失的确认弹窗 UI 及其状态联动）表述不一致，以 diff 为准。

## 关键代码修改
改动文件：DrivingFragment.kt、values/strings.xml、values-en/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
-            // 极致续航
             setupSwitchListener(ssvExtremeRangeSetting.switchCompat, { isExtremeRangeEnabled }) { isChecked ->
-                val state = if (isChecked) CanSignalMessages.SWITCH_CMD_ON else CanSignalConstants.SWITCH_CMD_OFF
-                logClick("[Command Send] Set extreme range: $state")
-                settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_ECOFUNCSTS, state)
-                extremeRangePendingState = isChecked
+                if (!isChecked) {
+                    val state = CanSignalConstants.SWITCH_CMD_OFF
+                    settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_ECOFUNCSTS, state)
+                    extremeRangePendingState = false
+                }
+            }
+
+            ssvExtremeRangeSetting.setOnOverlayClickListener {
+                if (!isExtremeRangeEnabled) {
+                    return@setOnOverlayClickListener
+                }
+                showTipDialog(
+                    title = getString(R.string.extreme_range),
+                    content = getString(R.string.extreme_range_confirm_content),
+                    confirmText = getString(R.string.extreme_range_confirm_enable),
+                    cancelText = getString(R.string.cancel),
+                    onConfirm = {
+                        extremeRangePendingState = true
+                        mBinding.ssvExtremeRangeSetting.isChecked = true
+                        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_ECOFUNCSTS, CanSignalConstants.SWITCH_CMD_ON)
+                    }
+                )
             }
```
```diff
// application/Setting/src/main/res/values/strings.xml
+    <string name="extreme_range_confirm_content">开启极致续航后，将会大量关闭除驾驶和安全相关的耗电功能，是否开启极致续航？</string>
+    <string name="extreme_range_confirm_enable">确认开启</string>
```
（另有 `updateExtremeRangeStatusUI()`/overlay 的 enable/disable 联动与 `ReboundHelper.cancel` 回弹处理，保证弹窗流程下开关置灰与回弹状态一致）

## 为什么能修复
点击路径被重构为"关直接生效、开先拦截"：开关组件的 overlay 拦截层捕获开启意图后弹 `showTipDialog`，只有用户点"确认开启"才置位 `isChecked` 并下发 `CCU_ECOFUNCSTS = SWITCH_CMD_ON`；关闭路径保持直发，不打扰用户。`updateExtremeRangeStatusUI` 按连接/开关状态切换 overlay 与置灰，避免弹窗确认期间状态错乱。副作用：确认期间信号未下发，若车控状态在别处被改变，靠 `extremeRangePendingState` 回弹机制兜底。

## 复盘与经验
- 高影响功能开关应区分开/关两个方向的交互成本：危险方向（开启）走确认弹窗，安全方向（关闭）直发，比对称处理更符合体验规范。
- 用 `setOnOverlayClickListener` 在控件层拦截原始点击，比在回调里回滚 `isChecked` 更干净，避免"开关闪一下又弹回去"的观感问题。
- 新增交互必须同步补齐多语言文案（values 与 values-en 成对提交），否则运行时资源缺失崩溃。
- 缺陷库根因字段（"ui未释放"）与实际改动（缺确认弹窗）不符时，写复盘要以 diff 为准并注明差异。
