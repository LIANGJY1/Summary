# SIR-7857 · 里程管理页"其他电耗 100%"文本被截断
- **提交**：`4f99848e` | 2026-09-08 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
里程管理页能耗分布图中，"其他"电耗达到最大值 100% 时，右侧百分比文本被裁掉一截。

## 根因分析
`EnergyDistributionView` 是自绘 View：`activity_mileage_management.xml` 把它的宽度固定为 220dp，而 `onDraw` 里标签文本固定从 `dpToPx(133f)` 处开始 `canvas.drawText`。"其他 100%" 是最长文本组合，133dp 起点加上文本宽度超出了 220dp 的 View 右边界，Canvas 按 View 边界裁剪导致截断。这类"固定起点 + 固定宽度"的画法没有任何末端保护，字体、字号、密度一变就必然复发。修复双管齐下：布局宽度放宽到 240dp 增加可用空间；自绘里在绘制前 `labelPaint.measureText(text)` 实测文本宽，若超过 `getWidth() - textStartX - dpToPx(4f)`（可用宽减 4dp 末端留白），就按比例缩小 `labelPaint.setTextSize(...)`（下限 `spToPx(16f)`），画完后恢复原字号，保证任何最大值文本都不会再被裁。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyDistributionView.java、application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyDistributionView.java
@@ -155,6 +155,14 @@
         Paint.FontMetrics fontMetrics = labelPaint.getFontMetrics();
         float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
-        canvas.drawText(text, dpToPx(133f), baseline, labelPaint);
+        float textStartX = dpToPx(133f);
+        float maxTextWidth = getWidth() - textStartX - dpToPx(4f);
+        float originalTextSize = labelPaint.getTextSize();
+        float measuredTextWidth = labelPaint.measureText(text);
+        if (maxTextWidth > 0f && measuredTextWidth > maxTextWidth) {
+            labelPaint.setTextSize(Math.max(spToPx(16f), originalTextSize * maxTextWidth / measuredTextWidth));
+        }
+        canvas.drawText(text, textStartX, baseline, labelPaint);
+        labelPaint.setTextSize(originalTextSize);
     }
 }
--- application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml
@@ -145,7 +145,7 @@
             <com.android.yadea.energymanagement.view.custom.EnergyDistributionView
                 android:id="@+id/energy_distribution_view_this_mile"
-                android:layout_width="220dp"
+                android:layout_width="240dp"
```
（subtotal_mile 处的同款 View 同步 220dp → 240dp。）

## 为什么能修复
宽度放宽给最大文本腾出空间，实测宽度 + 按比例缩字号（带 16sp 下限与 4dp 留白）构成兜底保护，"其他 100%" 不再越界被裁。副作用：极端情况下文本会以缩小字号呈现（视觉略小但完整），且 paint 的 textSize 修改/恢复都在同一线程的 onDraw 内完成，无并发问题；若未来要求文本永不缩放，则需改为自动换行或动态 View 宽度方案。

## 复盘经验
- 自绘文本"固定 x 起点 + 固定 View 宽"没有末端保护，最大值场景必截断；drawText 前用 measureText 做宽度预算是自绘 View 的基本功。
- 末端保护三件套：实测宽度、按比例缩放（设下限）、画后恢复 paint 状态，可整体推广为工具方法。
- 截断类缺陷要测最大值/最长文案（100%、最长单位、最大字号、最大密度），只测典型值发现不了。
