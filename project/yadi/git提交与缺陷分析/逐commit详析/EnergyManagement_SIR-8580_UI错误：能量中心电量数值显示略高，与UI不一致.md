# SIR-8580 · 能量中心电量数值显示位置略高，与 UI 稿不一致

- **提交**：`0dceb8d6` | 2026-09-18 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 解决方案 · 域 能量中心

## 问题
能量中心主页的电量（SOC）数值整体显示偏高，与 UI 稿的电池框位置对不齐。

## 根因分析
电量条是自定义 View `EnergyBarSeekBar` 绘制的：电池帧位图实际尺寸为 144x155，而控件本身是 143x158dp。修复前 `computeBarRect` 直接把位图画满整个控件（`barRect.set(batteryOffsetX, 0f, w + batteryOffsetX, h)`），位图相对背景中的透明电池框整体偏大偏低，视觉上"数值/电池内容高于 UI 稿"。同时 `activity_main.xml` 中 SOC 数值 TextView 的上下边距（34dp/28dp）没有按最终电池框位置校准，文字基线偏高。缺陷库根因写"间距不对"，与 diff 基本吻合——本质是"位图绘制矩形与控件尺寸不匹配 + 文案边距未校准"的复合问题，commit message 概括为"修改高度"。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyBarSeekBar.java、application/EnergyManagement/src/main/res/layout/activity_main.xml（共 +12/-5）
```diff
--- .../view/custom/EnergyBarSeekBar.java
@@ 绘制矩形计算
+    // 电池帧为 144x155；在现有 143x158dp 控件内上移并收窄绘制高度，
+    // 对齐主页背景中的透明电池框，控件位置不变以保留 SOC 文字和底部光效的布局。
+    private static final float BATTERY_DRAW_OFFSET_Y_DP = -3f;
+    private static final float BATTERY_DRAW_HEIGHT_TRIM_DP = 3f;
@@ computeBarRect
         if (barBitmap == null) return;
-        float batteryOffsetX = BATTERY_DRAW_OFFSET_X_DP
-                * getResources().getDisplayMetrics().density;
-        barRect.set(batteryOffsetX, 0f, w + batteryOffsetX, h);
+        float density = getResources().getDisplayMetrics().density;
+        float batteryOffsetY = BATTERY_DRAW_OFFSET_Y_DP * density;
+        float batteryHeight = Math.max(0f, h - BATTERY_DRAW_HEIGHT_TRIM_DP * density);
+        barRect.set(batteryOffsetX, batteryOffsetY,
+                w + batteryOffsetX, batteryOffsetY + batteryHeight);
--- .../res/layout/activity_main.xml
@@ SOC 数值 TextView
-            android:layout_marginTop="34dp"
-            android:layout_marginBottom="28dp"
+            android:layout_marginTop="41dp"
+            android:layout_marginBottom="21dp"
```

## 为什么能修复
把电池位图绘制区上移 3dp 并收窄 3dp 后，144x155 的帧图在 158dp 高的控件里对齐了背景电池框；数值 TextView 上下边距各调 7dp（34→41、28→21），文字整体下移且保持垂直居中，两者重新对齐 UI 稿。控件外框尺寸未动，SOC 文字、底部光效等其他布局不受影响；`Math.max(0f, ...)` 也防了极端情况下高度为负。隐患仅在于偏移是按当前切图尺寸写死的常量，切图一换就需要重调。

## 复盘与经验
- 自定义 View 内"画满控件"的绘制假设很脆弱：切图尺寸（144x155）与控件尺寸（143x158dp）不一致时必须显式声明映射关系，最好把位图期望尺寸写成常量并注释来源（本提交的注释就是好的示范）。
- 像素级 UI 还原 bug，定位手段是"量出谁偏了"：先分清是内容画偏（draw rect）还是文字放偏（layout margin），再分别修正，避免反复改一处的拉锯。
- 视觉校准常用"整体平移 + 保持控件外框"的手法，不改变 View 边界就不会波及兄弟视图的约束布局，修复面最小。
