# SIR-1491 · 充电枪信号丢失后充电信息仍显示旧数值
- **提交**：`993be862` | 2026-07-03 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
充电中停发 AC 充电枪连接信号后，剩余时间、充电功率、充电电流、充电电压等充电信息仍显示旧数值。

## 根因分析
`MainActivity.updateChargingGunConnectedUi`（`application/EnergyManagement/.../view/ui/MainActivity.java`，约 1070 行）在 `!connected`（充电枪断开/信号丢失）分支里只做了两件事：隐藏 `btnStopCharging`（停止充电按钮）和 `tvChargeEnergyInfo`（充电电量信息），并调整调试面板位置。充电信息区的四个数值控件 `tvMileageDebugRemainingTimeValue/tvMileageDebugChargePowerValue/tvMileageDebugChargeCurrentValue/tvMileageDebugChargeVoltageValue` 完全没有处理——文本停留在最后收到的 CAN 数值上，界面呈现"枪已断开但数据还在"的矛盾状态。缺的是断开时的显示清理路径。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -1070,6 +1070,14 @@
         if (!connected) {
             binding.btnStopCharging.setVisibility(View.GONE);
             binding.tvChargeEnergyInfo.setVisibility(View.GONE);
+            // 清除充电信息显示（剩余时间、充电功率、充电电流、充电电压）
+            binding.tvMileageDebugRemainingTimeValue.setText(R.string.mileage_debug_remaining_time_value);
+            binding.tvMileageDebugRemainingTimeUnit.setVisibility(View.VISIBLE);
+            binding.tvMileageDebugRemainingTimeDash.setVisibility(View.VISIBLE);
+            binding.tvMileageDebugRemainingTimeMin.setVisibility(View.VISIBLE);
+            binding.tvMileageDebugChargeCurrentValue.setText(R.string.mileage_debug_charge_current_value);
+            binding.tvMileageDebugChargeVoltageValue.setText(R.string.mileage_debug_charge_voltage_value);
+            binding.tvMileageDebugChargePowerValue.setText(R.string.mileage_debug_charge_power_value);
             updateMileageDebugPanelPosition(false);
         }
```

## 为什么能修复
断开分支补齐显示清理：四个数值 TextView 重置为占位资源（`mileage_debug_*_value` 资源值为 "-"，见 strings 中 `translatable="false">-</string>` 系列），剩余时间的单位/横杠/分钟控件恢复可见性，界面统一回"无数据"态。与该模块其它信号超时修复方向一致（SIR-1486 冻结、本单清为 "--"，因枪连接信号语义上是"充电会话结束"，清空比冻结更合理）。隐患：清理的是"显示"而非数据缓存，若后续信号恢复前的中间状态仍会刷新这些 TextView，需保证刷新入口同样受 connected 状态约束。

## 复盘与经验
- "隐藏按钮"和"清理数据展示"是断开场景的两个独立职责，只做前者就留下僵尸数值。
- 无数据显示统一用 "--" 占位资源，而不是空串或 0，与 SIR-1513 的处理可以形成模块内一致规范。
- 断开/超时分支的 UI 清理清单应对照布局控件树逐一核对，漏一个控件就是一条缺陷。
