# SIR-7110 · 能量中心充电上限刻度条最右侧为黑色
- **提交**：`09643d9a` | 2026-09-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
充电上限进度条的刻度条最右侧刻度显示为黑色（实为被裁切后的异常观感），且首尾刻度高度与设计稿不符。

## 根因分析
自定义 `ScaleTickView` 在 `onDraw` 中用 `canvas.drawLine(x, cy-halfH, x, cy+halfH, majorPaint)` 画大刻度：`drawLine` 的线宽由 `Paint.setStrokeWidth(2dp)` 控制，但线是以坐标 x 为中心向两侧各扩 1dp。最右侧刻度 `x = w`（value == maxValue 时 ratio 为 1），线体一半超出 View 边界被裁掉；同时 `halfH` 取 4dp（即总高 8dp 只在首尾），代码里 `isFirstOrLast ? dpToPx(4) : dpToPx(2)` 是"半高"写法，中间刻度总高仅 4dp，与 UI 稿"首尾 8dp、中间 4dp 的 2dp 宽实心矩形"不一致——线被裁后露出的底色被看成黑色。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/ScaleTickView.java`
```diff
--- .../energymanagement/view/custom/ScaleTickView.java
@@ 构造函数
-        majorPaint.setStrokeWidth(dpToPx(2));
@@ onDraw() 大刻度分支
-                boolean isFirstOrLast = (value == minValue) || (value == maxValue);
-                float halfH = isFirstOrLast ? dpToPx(4) : dpToPx(2);
-                canvas.drawLine(x, cy - halfH, x, cy + halfH, majorPaint);
+                // 首尾刻度需要向内收半个宽度，否则会被 View 边界裁掉一半。
+                boolean isFirstOrLast = (value == minValue) || (value == maxValue);
+                float tickWidth = dpToPx(2);
+                float tickHeight = isFirstOrLast ? dpToPx(8) : dpToPx(4);
+                float halfW = tickWidth / 2f;
+                if (value == minValue) {
+                    x = halfW;
+                } else if (value == maxValue) {
+                    x = w - halfW;
+                }
+                float top = cy - tickHeight / 2f;
+                canvas.drawRect(x - halfW, top, x + halfW, top + tickHeight, majorPaint);
```

## 为什么能修复
改用 `drawRect` 显式按"2dp 宽 × 8dp/4dp 高"的矩形绘制，宽度不再依赖 `Paint.strokeWidth` 的隐性外扩；首尾刻度坐标向内收半个宽度（`halfW`），保证矩形完整落在 View 内，最右侧不再被裁成"黑色"。副作用：两端刻度各向内移 1dp，与进度条端点有极小偏差，视觉可接受。

## 复盘与经验
- `drawLine` + strokeWidth 画刻度时，线体以坐标为中心外扩，端点位于 View 边缘必然被裁；自绘刻度/分隔线应优先用 `drawRect` 显式控制几何。
- "最右侧显示为黑色"这类描述其实是裁切伪影，看到颜色异常先怀疑绘制越界而不是配色。
- 自定义 View 的尺寸语义（半高 vs 全高）容易在两版代码间走样，绘制逻辑应与设计稿的绝对值一一对应并写进注释。
