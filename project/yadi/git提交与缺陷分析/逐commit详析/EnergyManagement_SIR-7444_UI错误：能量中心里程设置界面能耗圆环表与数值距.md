# SIR-7444 · 里程设置界面能耗圆环表与数值距离过近
- **提交**：`172c72f2` | 2026-09-04 | liqingqing | EnergyManagement | bugfix（UI 间距）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心里程设置界面，能耗圆环表（自绘 View）与其旁边的图例/数值文字间距过近，视觉上贴在一起，与设计稿不符。

## 根因分析
圆环图 `EnergyDistributionView` 是 `Canvas` 自绘控件，图例行 `drawLegendRow()` 中标记圆点横坐标硬编码 `dpToPx(108f)`、文字横坐标 `dpToPx(124f)`，相对圆环边缘的留白不足；同时图例数值使用的复合控件 `LabelValueLayout` 把自身与内部 TextView 的 gravity 设为 `CENTER_HORIZONTAL`/`center`，文字实际渲染位置向中间聚拢，进一步压缩了圆环与数值之间的视觉距离。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyDistributionView.java`、`.../view/custom/LabelValueLayout.kt`、`application/EnergyManagement/src/main/res/layout/layout_label_value.xml`

```diff
--- application/EnergyManagement/src/main/java/com/yadea/energymanagement/view/custom/EnergyDistributionView.java
     private void drawLegendRow(Canvas canvas, float centerY, int color, String text) {
-        float markerCenterX = dpToPx(108f);
+        // Keep a 30dp visual gap from the ring edge to the legend marker.
+        float markerCenterX = dpToPx(117f);
         legendPaint.setColor(color);
         canvas.drawCircle(markerCenterX, centerY, dpToPx(7f), legendPaint);
 
         Paint.FontMetrics fontMetrics = labelPaint.getFontMetrics();
         float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
-        canvas.drawText(text, dpToPx(124f), baseline, labelPaint);
+        canvas.drawText(text, dpToPx(133f), baseline, labelPaint);
     }
```

```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/LabelValueLayout.kt
-        gravity = android.view.Gravity.CENTER_HORIZONTAL
+        gravity = android.view.Gravity.START
```

（`layout_label_value.xml` 的 `<merge>` 根与 `tvRight`/数值 TextView 的 gravity 由 center/center_horizontal 统一改为 `start`。）

## 为什么能修复
自绘坐标右移（108→117、124→133dp）直接拉开圆环边缘与图例标记/文字的间距（注释明确"30dp visual gap"）；`LabelValueLayout` 改为左对齐后文字不再向中线聚拢，位置可预期。均为视觉层改动，无逻辑副作用；隐患是自绘坐标仍为硬编码 float，圆环半径或其他元素尺寸变化时需重新校准这几个魔法数。

## 复盘与经验
- 自绘 View 的间距问题要在 `drawXxx` 坐标里解，XML margin 管不到 Canvas 内容；修改时同步挪动一组相关坐标（圆点+文字），保持相对关系。
- 复合控件（自定义 Layout + inflate 的 XML）的 gravity 要容器与子 View、代码与 XML 两处一致，只改一边会出现"看起来没生效"的困惑。
- 自绘控件内的关键坐标建议提取为带注释的常量（如 `LEGEND_MARKER_X_DP = 117`），本例代码注释里写了设计意图"30dp gap"，是好的实践，但仍可再进一步参数化。
