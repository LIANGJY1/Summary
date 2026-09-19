# VIR-829 · 能量中心不显示当前电池 SOC 值（需求补齐）
- **提交**：`d5afa909` | 2026-09-14 | liqingqing | EnergyManagement | 需求补齐（提交自述 [why]"没有这个需求"，非代码缺陷）
- **缺陷库**：未关联缺陷库记录（单号 VIR-829，无 defs 详情）

## 问题
能量中心界面只有电池图形/进度条，不显示当前电池 SOC（电量百分比）数值，用户无法直接读数。

## 根因分析
并非逻辑错误，而是功能从未实现：`MainActivity` 的 SOC 刷新链路（`clampSoc` → `updateBatteryImageBySocTransition` 等）只驱动电池图形与光晕，界面上不存在承载 SOC 数值的控件。本次按需求在 `activity_main.xml` 新增 `TextView @+id/tv_battery_soc` 并接入既有数据链路；顺带把 `clampSoc` 的下限从 `soc < 1 → return 1` 放宽为 `soc < 0 → return 0`，因为有了数字显示后 0% 也需要如实呈现，原来的下限钳制会把真实 0 电量显示成 1%。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyTypeface.java；application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java；application/EnergyManagement/src/main/res/layout/activity_main.xml；application/EnergyManagement/src/main/res/values/strings.xml；application/EnergyManagement/src/main/res/values/style.xml
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
+++ b/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -1807,6 +1807,7 @@
         int normalizedSoc = clampSoc(soc);
         int previousSoc = mCurrentSoc;
         mCurrentSoc = normalizedSoc;
+        binding.tvBatterySoc.setText(getString(R.string.battery_soc_percent, normalizedSoc));
         updateBatteryImageBySocTransition(previousSoc, normalizedSoc);
@@ -1880,8 +1881,8 @@
     private int clampSoc(int soc) {
-        if (soc < 1) {
-            return 1;
+        if (soc < 0) {
+            return 0;
         }
```
```diff
--- a/application/EnergyManagement/src/main/res/layout/activity_main.xml
+++ b/application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ -67,6 +67,25 @@
+        <TextView
+            android:id="@+id/tv_battery_soc"
+            android:layout_width="108dp"
+            android:layout_height="0dp"
+            ...
+            android:fontFeatureSettings="tnum, lnum"
+            android:text="@string/battery_soc_placeholder"
+            android:textAppearance="@style/BatterySocText"
+            app:layout_constraintBottom_toBottomOf="@id/energy_bar_seek_bar"
+            app:layout_constraintStart_toStartOf="@id/energy_bar_seek_bar"
+            app:layout_constraintTop_toTopOf="@id/energy_bar_seek_bar"
+            tools:text="82%" />
```
配套：strings.xml 新增 `battery_soc_placeholder`("--%")与格式串 `battery_soc_percent`("%1$d%%")；style.xml 新增 `BatterySocText`（38sp、#CCFFFFFF）；`EnergyTypeface` 新增 `demibold()` 并在 `applyConfiguredTypefaces` 中应用到 `tvBatterySoc`。

## 为什么能修复（实现合理性）
SOC 更新入口（设置 `mCurrentSoc` 处）同步 `setText`，数值与电池图形严格同源同步刷新；占位符"--%"覆盖首帧未收到信号的状态。`clampSoc` 下限放宽与数字显示需求一致，无副作用；字体用 `fontFeatureSettings="tnum, lnum"` 保证等宽数字跳变不抖动。

## 复盘与经验
- "界面没显示 X"不一定有 bug，先确认是缺陷还是需求缺口，提交元数据里 [why] 会说实话。
- 给既有数据链路补显示控件时，数值刷新必须挂在同一次状态更新里，避免图形与文字出现双源不一致。
- 原有的边界钳制（clamp 1%）在新展示需求下会变成数据失真，加显示能力时要回头审一遍边界语义。
