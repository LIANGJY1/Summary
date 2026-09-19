# SIR-8669/8683 · 反复点击后向碰撞预警开关，显示开启却未显示设置项
- **提交**：`b0bfa163` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 待测试验证 · 域 车控车设

## 问题
反复点击后向碰撞预警（RCW）开关后，开关显示开启状态，但下方的设置项功能区域没有显示，开关与内容区状态脱节。

## 根因分析
与 SIR-8669/HUD 开关同根因：`AssistedDrivingFragment` 的 ADAS 开关经 `setupAdasSwitch` → `CustomSwitchCompat.setClickFastWithRebound` 走"点击即翻、1 秒超时回弹"机制，但原 `setupAdasSwitch(switch, tag, action)` 签名根本没有回弹回调参数，回弹时只回滚开关视觉，不刷新内容区。RCW 还有第二条回弹路径：关闭流程 `startReboundForSwitch`（带 `REBOUND_DELAY_MS` 延迟把开关翻回 `STATE_ON` 并下发 `RCW_RCTA_SWITCH = SWITCH_VALUE_OFF` 再回弹），该路径同样没有联动 UI 刷新。两条路径都造成"开关开着、设置项没显示"。

## 关键代码修改
改动文件：AssistedDrivingFragment.kt、fragment_assisted_driving.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
-            setupAdasSwitch(ssvRearCollisionWarning.switchCompat, "RCW") {
+            setupAdasSwitch(ssvRearCollisionWarning.switchCompat, "RCW", {
                 setRearCollisionWarningState(STATE_ON)
-            }
+            }) {
+                updateRearCollisionWarningUI(STATE_OFF)
             }
...
-    private fun setupAdasSwitch(switch: CustomSwitchCompat, tag: String, action: () -> Unit) {
-        switch.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
+    private fun setupAdasSwitch(
+        switch: CustomSwitchCompat,
+        tag: String,
+        action: () -> Unit,
+        onRebound: (() -> Unit)? = null
+    ) {
+        switch.setClickFastWithRebound(
+            viewLifecycleOwner.lifecycleScope,
+            onChanged = { isChecked -> ... },
+            onReboundCallback = { _ ->
+                // 1秒超时回弹：开关视觉已回滚到关闭，下方设置项显隐需同步，避免开关与内容区脱节
+                onRebound?.invoke()
+            })
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt（关闭回弹路径）
-            startReboundForSwitch(mBinding.ssvRearCollisionWarning.switchCompat)
+            startReboundForSwitch(mBinding.ssvRearCollisionWarning.switchCompat) {
+                updateRearCollisionWarningUI(STATE_ON)
+            }
```

## 为什么能修复
`setupAdasSwitch` 增加 `onRebound` 可选参数并透传给 `setClickFastWithRebound` 的 `onReboundCallback`：正向点击回弹时调用 `updateRearCollisionWarningUI(STATE_OFF)` 同步隐藏设置项；关闭流程的 `startReboundForSwitch` 也补上回调，回弹回开启态时 `updateRearCollisionWarningUI(STATE_ON)` 同步显示设置项。两条回弹路径的 UI 联动都被接上，开关与内容区不再脱节。其余 ADAS 开关（FCW/LDW/交通提醒）未传 `onRebound`，行为保持原样，说明修复是按缺陷定向接线。布局 xml 的小幅调整配合内容区显隐刷新。

## 复盘与经验
- 同一开关存在多条回弹路径（正向点击回弹、二次确认关闭回弹）时，每条都要接 UI 联动回调，漏一条就是一次状态脱节。
- 公共 setup 函数扩展回调参数用默认值（`onRebound: (() -> Unit)? = null`）可平滑兼容既有调用点，实现"按需接线"式修复。
- ADAS 类开关与 HUD 开关（b398623b）是同一模式的连续修复，说明"回弹联动"应沉淀为公共接入规范，而不是逐个缺陷补课。
