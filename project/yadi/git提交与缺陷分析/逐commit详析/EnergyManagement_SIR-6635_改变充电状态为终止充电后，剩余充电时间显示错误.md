# SIR-6635 · 终止充电后剩余充电时间显示错误
- **提交**：`5b10b5f9` | 2026-08-31 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
把充电状态切为"终止充电"后，能量中心的"剩余充电时间"区域仍显示残留的旧值/占位内容，应显示"—"占位。

## 根因分析
`EnergyManagement` 的 `MainActivity`（`com.android.yadea.energymanagement.view.ui.MainActivity`）中剩余充电时间的展示区由四个控件组成：数值 `tvMileageDebugRemainingTimeValue`、单位 `tvMileageDebugRemainingTimeUnit`、破折号 `tvMileageDebugRemainingTimeDash`、分钟 `tvMileageDebugRemainingTimeMin`。UI 改版后"无剩余时间"态的规范是：隐藏数值+单位（旧版用于显示 `--h` 占位的两个 TextView），显示 Dash+"min"。旧代码在此分支里仍把 Value/Unit `setVisibility(View.VISIBLE)`——只更新了文本没隐藏控件，`--h` 占位与 Dash 同时上屏，显示错误。这是单据所说"旧 UI 变更不彻底"：改版时新增了 Dash 控件，却漏改旧控件的可见性分支。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
```diff
--- a/.../energymanagement/view/ui/MainActivity.java
         if (mRemainingTimeTimeoutLost) {
             applyRemainingTimeTimeoutUi();
         } else {
-            binding.tvMileageDebugRemainingTimeValue.setVisibility(View.VISIBLE);
+            binding.tvMileageDebugRemainingTimeValue.setVisibility(View.GONE);
             binding.tvMileageDebugRemainingTimeValue.setText(R.string.mileage_debug_remaining_time_value);
             binding.tvMileageDebugRemainingTimeUnit.setText(R.string.mileage_debug_remaining_time_unit);
-            binding.tvMileageDebugRemainingTimeUnit.setVisibility(View.VISIBLE);
+            binding.tvMileageDebugRemainingTimeUnit.setVisibility(View.GONE);
             binding.tvMileageDebugRemainingTimeDash.setVisibility(View.VISIBLE);
             binding.tvMileageDebugRemainingTimeMin.setVisibility(View.VISIBLE);
         }
```

## 为什么能修复
"无剩余时间"分支里 Value/Unit 置 GONE 后，区域只剩 Dash（"—"）与"min"，与改版后的 UI 规范一致，终止充电后不再出现 `--h` 与新占位并存的错误画面。改动是把旧控件可见性与新控件对齐的两行翻转，无逻辑副作用；风险仅在于另一分支 `applyRemainingTimeTimeoutUi()`（信号超时态）的可见性组合需保持互斥正确，若超时态也依赖这两个控件需同步检查。

## 复盘与经验
- UI 改版替换占位方案时（`--h` 文案 → Dash 控件），必须全局搜索旧占位控件的每一处可见性分支，"新增了新控件但没改旧控件"是改版不彻底的标配缺陷。
- 同一语义的多个控件（数值/单位/占位符）应封装成一个自定义 View 或统一的状态设置方法，四处 setVisibility 散落必然漏改——本文件的姊妹提交 52091348（网格定宽）处理的正是同一块区域。
- "终止充电"这类状态切换的测试要盯占位/空态展示，而不仅是数值正确性。
