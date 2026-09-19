# SIR-6296 · 能量中心充电信息显示与UI不一致

- **提交**：`cbd347e0` | 2026-08-24 | liqingqing | EnergyManagement | bugfix（提交头误标 [feature]；mistag 已核对）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心充电信息区（剩余时间/充电功率/充电电流/充电电压）的数字与文字排布、字号与 UI 设计稿不一致。

## 根因分析
缺陷库根因"ui布局，文字大小和ui不一致"。实现层有三类偏差：① `themes.xml` 中 `MileageDebugRemainingTimeValue`/`MileageDebugMetricValue` 字号 36sp、标签 24sp、单位 20sp，均大于设计稿（30/20/16sp）；② `activity_main.xml` 中四个指标 cell 用固定宽度（160dp/118dp）+「标签在上、数值在下」结构，与设计「数值在上、标签在下」及自适应间距不符，且固定宽度导致列间距（18dp）与设计（36dp）不一致；③ 占位文案用单个 "-"，单位小写 "kw"（应为 kW）。另注：diff 与元数据不完全相符——本提交还夹带了大批逻辑改动（`TboxClientManager` 预约充电上报 `reportReservationRequest`、`MainActivity` 预约结果回执 `handleReservationResult`/1 秒超时回滚、信号超时时统一显示 "--" 的 `applyRemainingTimeTimeoutUi` 等），超出"改文字大小布局"的缺陷库描述，按 diff 实际为准并注明。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/module/TboxClientManager.java、view/ui/MainActivity.java、res/layout/activity_main.xml、res/values/strings.xml、res/values/themes.xml（5 文件，+227/-83）
```diff
--- application/EnergyManagement/src/main/res/values/themes.xml
@@ 充电信息字号对齐设计稿
     <style name="MileageDebugRemainingTimeValue">
-        <item name="android:textSize">36sp</item>
+        <item name="android:textSize">30sp</item>
     </style>
     <style name="MileageDebugRemainingTimeLabel">
-        <item name="android:textSize">24sp</item>
+        <item name="android:textSize">20sp</item>
     </style>
     <style name="MileageDebugMetricValue">
-        <item name="android:textSize">36sp</item>
+        <item name="android:textSize">30sp</item>
     </style>
     <style name="MileageDebugMetricUnit">
-        <item name="android:textSize">20sp</item>
+        <item name="android:textSize">16sp</item>
     </style>
     <style name="MileageDebugMetricLabel">
-        <item name="android:textSize">24sp</item>
+        <item name="android:textSize">20sp</item>
     </style>
--- application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ cell 结构：数值在上、标签在下，固定宽改自适应
-                    android:id="@+id/view_mileage_debug_cell_1"
-                    android:layout_width="160dp"
+                    android:id="@+id/view_mileage_debug_cell_1"
+                    android:layout_width="wrap_content"
                     ...
-                        android:layout_marginTop="6dp"
+                        android:layout_marginBottom="6dp"
@@ 标签从 cell 头部移到数值行之后
-                    <TextView android:id="@+id/tv_mileage_debug_remaining_time_label" .../>
                     <LinearLayout>...数值行...</LinearLayout>
+                    <TextView android:id="@+id/tv_mileage_debug_remaining_time_label" .../>
@@ 列间距
-                    android:layout_marginStart="18dp"
+                    android:layout_marginStart="36dp"
--- application/EnergyManagement/src/main/res/values/strings.xml
-    <string name="mileage_debug_charge_power_unit" translatable="false">kw</string>
+    <string name="mileage_debug_charge_power_unit" translatable="false">kW</string>
```
（MainActivity/TboxClientManager 的预约充电上报与超时回执逻辑为同提交附带改动，此处不逐行展开。）

## 为什么能修复
字号、标签位置、列间距、单位写法逐项对齐设计稿，充电信息区视觉与 UI 一致；数值行 `gravity="bottom"`+`fontFeatureSettings="tnum, lnum"` 保证数字等宽底对齐，尺寸自适应后不同数值长度不再挤压布局。副作用：cell 固定宽度改 `wrap_content` 后，极端长数值可能改变整行宽度，需确认与相邻元素的约束仍成立；同提交夹带的预约充电逻辑改动使该提交远超"UI 修复"范围，一旦预约链路出问题会污染本单归因。

## 复盘与经验
- UI 还原类 bug 的批量修法：先从设计稿提取字号/间距/结构差异清单，再集中改 theme+layout，避免零碎补丁。
- "标签在上还是在上"这类结构差异应在布局阶段用公共组件/样式固化，靠每个 cell 手写 LinearLayout 必然走样。
- 一个提交混入 UI 修复 + 预约充电逻辑新增，违背单一职责提交原则，回滚与追溯都会受牵连。
- 提交头 [feature] 标注错误再次出现（同批 `59ee74b9` 同问题），提交类型校验值得进 CI 门禁。
