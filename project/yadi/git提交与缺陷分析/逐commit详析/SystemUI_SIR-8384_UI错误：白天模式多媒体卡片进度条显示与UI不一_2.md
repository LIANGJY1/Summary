# SIR-8384/SIR-8386 · 媒体卡片进度条与 UI 不符（第一轮：png+scale 改 vector+clip）
- **提交**：`40ab12d6` | 2026-09-14 19:25 | caohongliang | SystemUI | bugfix（第一轮尝试，37 分钟后被 `35990c50` 部分回退）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rc：figma 下载的图片资源有问题；sol：根据 svg 生成正确的图片）

## 问题
白天模式下多媒体卡片播放进度条的显示效果与 UI 设计图有差异：设计稿进度面是带渐变/蒙版的填充，实际显示（png+scale 方案）在短进度时渐变被横向压缩，观感不对。

## 根因分析
`drawable/seekbar_progress.xml` / `drawable-night/seekbar_progress.xml` 的 progress 层用 `<scale android:scaleWidth="100%">` 包裹整条渐变背景：scale 是**等比拉伸**，进度只有 20% 时，364dp 宽的渐变图被压缩到 ~73dp 内绘制，起点透明→终点实色的渐变全程被挤进这一小段，与设计稿"渐变按整条轨道铺底、按进度裁切显示"的语义不符。配套资源是 mdpi 的位图（`drawable-mdpi/icon_seekbar_bg.png`），高分辨率车机上缩放也会引入模糊与色差。第一轮修复选择 vector+clip 方案：clip 保持图层完整尺寸、只裁出左侧可见区，从机制上消除压缩。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/drawable/seekbar_progress.xml；.../drawable-night/seekbar_progress.xml；.../drawable/icon_seekbar_bg.xml（新增）；.../drawable/icon_seekbar_bg_night.xml（新增）；删除 drawable-mdpi/icon_seekbar_bg.png、icon_seekbar_bg_night.png
```diff
--- a/application/SystemUI/src/main/res/drawable/seekbar_progress.xml
+++ b/application/SystemUI/src/main/res/drawable/seekbar_progress.xml
@@ -13,9 +13,10 @@
     <item android:id="@android:id/progress">
-        <scale
-            android:scaleWidth="100%"
-            android:scaleGravity="left|center_vertical">
+        <!-- 保持设计图完整尺寸，仅按播放进度裁出左侧区域，避免渐变被压缩。 -->
+        <clip
+            android:clipOrientation="horizontal"
+            android:gravity="left">
             <layer-list>
                 <item android:drawable="@drawable/icon_seekbar_bg">
```
```diff
--- /dev/null
+++ b/application/SystemUI/src/main/res/drawable/icon_seekbar_bg.xml
+<vector xmlns:android="http://schemas.android.com/apk/res/android"
+    xmlns:aapt="http://schemas.android.com/aapt"
+    android:width="364dp" android:height="80dp"
+    android:viewportWidth="364" android:viewportHeight="80">
+  <group>
+    <clip-path android:pathData="M0,0H364V80H0V0Z"/>
+    <path android:pathData="M0,0H364V80H0V0Z" android:fillAlpha="0.2">
+      <aapt:attr name="android:fillColor">
+        <gradient android:startX="124.85" android:startY="0"
+            android:endX="124.85" android:endY="80" android:type="linear">
+          <item android:offset="0" android:color="#003C4558"/>
+          <item android:offset="1" android:color="#FF3C4558"/>
+        </gradient>
+      </aapt:attr>
+    </path>
+  </group>
+</vector>
```

## 为什么能修复（本轮效果）
clip 替代 scale 消除了"短进度压缩渐变"的机制性偏差，vector 替代 mdpi png 消除了位图缩放模糊。**但本轮未达终态**：设计 SVG 的渐变实际含横向渐变蒙版，此处 vector 只还原了纵向线性渐变（startY→endY），无法表达横向蒙版效果——这正是 37 分钟后 `35990c50` 返工的原因。

## 复盘与经验
- SeekBar 进度层 `scale` vs `clip` 的语义差异：scale 等比压缩整层（渐变随之压缩），clip 按进度裁切完整图层（渐变保持），带渐变的进度条应优先 clip 或"预合成完整渐变图 + scale"。
- figma 导出的 PNG 在 mdpi 目录会因车机高密度屏缩放产生观感偏差，UI 单要核对资源密度目录。
- 复杂设计效果（多层蒙版渐变）手工转 vector 极易丢维度，本例就是纵向渐变丢了横向蒙版——转写后要与设计稿逐像素比对。
