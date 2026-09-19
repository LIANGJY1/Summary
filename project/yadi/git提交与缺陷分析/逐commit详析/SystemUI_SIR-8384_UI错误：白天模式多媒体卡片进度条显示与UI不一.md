# SIR-8384/SIR-8386 · 媒体卡片进度条返工：展平 SVG 出图 + 回归 scale
- **提交**：`35990c50` | 2026-09-14 20:02 | caohongliang | SystemUI | bugfix（对 `40ab12d6` 的返工/收敛，同一单号）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互
- **元数据差异说明**：提交信息与上一条完全相同（"改为vector和clip的方式设置"），但 diff 实际是**删除 vector 资源、恢复 PNG、clip 改回 scale**——以 diff 实际为准，提交信息为复制未更新。

## 问题
接 `40ab12d6`：vector+clip 方案渲染出的进度面与设计稿仍有差异（横向渐变蒙版丢失），本提交完成最终修正。

## 根因分析
设计稿的进度面是"SVG 含横向渐变蒙版"的复合效果（新注释原话："SVG 含横向渐变蒙版，使用展平后的图片保留设计效果"）。上一轮手工转写的 vector 只有纵向线性渐变（`startY=0→endY=80`），表达不了横向蒙版维度；且 clip 方案在短进度时透明段不可见、可见段对比度不足（日间注释："使较短进度下仍保持足够的可见度"）。正确解法变成：让 UI 侧把带蒙版的 SVG **展平渲染成位图**（渐变/蒙版效果已烘焙进像素），回到 `scale` 按进度缩放这张"完整效果图"——因为渐变已经展平进图里，scale 压缩不会再破坏渐变语义（等比缩放整条渐变，视觉上与设计一致）。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/drawable/seekbar_progress.xml；.../drawable-night/seekbar_progress.xml；删除 .../drawable/icon_seekbar_bg.xml、icon_seekbar_bg_night.xml；新增 drawable-mdpi/icon_seekbar_bg.png、icon_seekbar_bg_night.png（二进制：由 SVG 展平导出的位图）
```diff
--- a/application/SystemUI/src/main/res/drawable/seekbar_progress.xml
+++ b/application/SystemUI/src/main/res/drawable/seekbar_progress.xml
@@ -13,12 +13,12 @@
     <item android:id="@android:id/progress">
-        <!-- 保持设计图完整尺寸，仅按播放进度裁出左侧区域，避免渐变被压缩。 -->
-        <clip
-            android:clipOrientation="horizontal"
-            android:gravity="left">
+        <!-- 白天模式按播放进度缩放渐变，使较短进度下仍保持足够的可见度。 -->
+        <scale
+            android:scaleWidth="100%"
+            android:scaleGravity="left|center_vertical">
             <layer-list>
-                <!-- 进度面 - 30%透明度的渐变背景 -->
+                <!-- SVG 含横向渐变蒙版，使用展平后的图片保留设计效果。 -->
                 <item android:drawable="@drawable/icon_seekbar_bg">
```
（night 版同步改回 `<scale>`，注释"夜间进度面补齐横向蒙版后，按播放进度缩放完整渐变"。）

## 为什么能修复
把"无法用 vector 表达的横向蒙版渐变"预烘焙进展平位图，资源本身即设计终稿，`scale` 缩放整张完整效果图即等价于设计语义下的进度展示，彻底消除两轮中"渐变被压缩/蒙版丢失"两类偏差；删除手工 vector 避免错误资源残留。代价：回到位图方案，mdpi 密度下大屏缩放可能仍偏软，但与设计稿一致性优先。

## 复盘与经验
- 修复不是一次到位的：40 分钟内 clip(机制修复)→展平位图+scale(还原设计)的往返说明，UI 还原类问题最终裁判是设计稿比对，不是机制推断。
- 无法参数化表达的复杂设计效果，"设计侧展平导出终稿位图 + 运行时按进度缩放"是务实解；前提是导出前把蒙版/渐变全部烘焙进图。
- 复制上一条提交信息做返工提交会严重误导追溯（元数据与 diff 相反），返工提交必须写清与上一版的差异及原因。
