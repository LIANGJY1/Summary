# SIR-7862 · 上报能量回收切换信号时 Tab 选项没有高亮
- **提交**：`42c790da` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：置灰逻辑问题）

## 问题
车辆上报能量回收切换信号时，车机"驾驶操控"界面的能量回收 Tab 选项没有高亮显示（被极致续航联动置灰压制）。

## 根因分析
`DrivingFragment.updateExtremeRangeUI(state)` 里，节能联动（关闭座椅加热/把手加热/氛围灯 + `applyEnergyRecoveryGrayByExtremeRange`）被包在 `if (state == extremeRangePendingState)`（即"回显与临时态一致"）分支内，且分支依据用的是 `extremeRangePendingState`（本地临时值）而非 `state`（CAN 回显值）。当外部主动上报能量回收/极致续航信号（本地无 pending 记录或时序错位）时，`state != extremeRangePendingState` 走到失败提示分支，`applyEnergyRecoveryGrayByExtremeRange` 不执行，能量回收 Tab 的置灰/恢复状态停留在过期值，无法恢复高亮。根因是"回显处理分支里用临时值决定联动副作用"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -428,24 +428,23 @@
     fun updateExtremeRangeUI(state: Boolean) {
         mBinding.apply {
-            if (state == extremeRangePendingState) {
-                SwitchHelper.cancelRebound(ssvExtremeRangeSetting.switchCompat)
-                when (extremeRangePendingState) {
-                    true -> {
-                        sendExtremeRangeEnergySavingCommands()
-                        applyEnergyRecoveryGrayByExtremeRange(true)
-                    }
-                    false -> {
-                        applyEnergyRecoveryGrayByExtremeRange(false)
-                    }
-                    null -> {}
-                }
+            when (state) {
+                true -> {
+                    sendExtremeRangeEnergySavingCommands()
+                    applyEnergyRecoveryGrayByExtremeRange(true)
+                }
+                false -> {
+                    applyEnergyRecoveryGrayByExtremeRange(false)
+                }
+            }
+
+            if (state == extremeRangePendingState) {
+                SwitchHelper.cancelRebound(ssvExtremeRangeSetting.switchCompat)
             } else {
                 when (extremeRangePendingState) {
                     true -> showToast(getString(R.string.extreme_range_switch_open_fail_tip))
```

## 为什么能修复
节能联动与能量回收置灰改由 CAN 回显 `state` 驱动的 when 分支执行，无论信号来自本地操作回显还是外部上报，`applyEnergyRecoveryGrayByExtremeRange(true/false)` 都会按最新状态执行，Tab 高亮不再被过期的 pending 值卡住；"回显与 pending 一致才取消回弹、不一致才 toast 失败"的防呆语义保留且与联动解耦。注意副作用：`state==true` 时每次回显都会重发 `sendExtremeRangeEnergySavingCommands()`（关加热/氛围灯指令），信号重放时会重复下发，依赖下游幂等。

## 复盘与经验
- CAN 回显处理函数中，联动副作用（联动置灰、级联指令）必须依据回显值 state，而不是本地临时值 pending；pending 只用于比对"这次回显是不是我发起的"。
- 把"状态联动副作用"与"操作确认防呆（回弹/toast）"拆成两个独立分支，可同时满足外部信号与本地操作两条路径。
- 主动上报类测试用例（不发指令、直接注信号）能提前暴露这类"只认自己发起流程"的分支缺陷，与 SIR-7848 同类，建议归并为一个测试族。
