# SIR-1494 · 停发慢充电流信号后SeekBar重置为默认 2.0kW
- **提交**：`e93dbfcc` | 2026-07-03 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
发送慢充电流信号 0.9kW 后停发，能量中心慢充电流不保持 0.9kW，而是变成默认值 2.0kW。

## 根因分析
`MainActivity.updateSlowChargeSeekBarEnabledState`（`application/EnergyManagement/.../view/ui/MainActivity.java`，约 505 行）负责慢充功率条的可用态刷新：`boolean enabled = isSlowChargeSeekBarEnabledBySignals()`，信号超时导致不可用时，旧代码顺带执行 `binding.seekbarRangeMode.setProgress(SLOW_CHARGE_POWER_DEFAULT_TENTHS - SLOW_CHARGE_POWER_MIN_TENTHS)`，把进度条拉回默认 2.0kW 档。"禁用控件"的语义被扩大成了"禁用并重置数值"，用户设置的 0.9kW 在信号丢失瞬间被替换为默认值。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -505,9 +505,7 @@
     private void updateSlowChargeSeekBarEnabledState() {
         boolean enabled = isSlowChargeSeekBarEnabledBySignals();
-        if (!enabled) {
-            binding.seekbarRangeMode.setProgress(SLOW_CHARGE_POWER_DEFAULT_TENTHS - SLOW_CHARGE_POWER_MIN_TENTHS);
-        }
+        // 信号超时时保持当前值不变，只改变视觉状态（置灰）
         binding.seekbarRangeMode.setEnabled(true);
         binding.seekbarRangeMode.setFocusable(enabled);
```

## 为什么能修复
删除禁用分支里的 `setProgress(默认值)` 后，超时只影响视觉/交互态（`setFocusable(false)`、置灰），进度条数值保持最后收到的 0.9kW；信号恢复后 `isSlowChargeSeekBarEnabledBySignals()` 重新可用，无需恢复动作。隐患：若产品语义是"信号丢失后旧设置不可信、应回默认"，冻结旧值只是延后问题；且 `setEnabled(true)` 写死为 true 仅靠 focusable 表达禁用，触摸拦截依赖控件内部实现，需确认置灰时不可拖动。

## 复盘与经验
- "可用性"与"取值"是 SeekBar 的两个正交状态：禁用控件不应修改其值，重置数值应只发生在明确的业务事件（如恢复出厂）。
- 本模块 SIR-1486/1494 两个缺陷同构——超时分支里顺手 `set 0/默认值`。规范应为：超时冻结显示，恢复后刷新。
- 修复时用注释替换删除的代码并说明意图，便于后续维护者理解为什么"什么都不做"是对的。
