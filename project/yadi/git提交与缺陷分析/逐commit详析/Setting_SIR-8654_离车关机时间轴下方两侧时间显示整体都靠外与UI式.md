# SIR-8654 · 离车关机时间轴两侧时间标签位置偏外，与 UI 式样不一致

- **提交**：`34092f7b` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 解决方案 · 域 车控车设

## 问题
离车关机（踢脚落下时间）时间轴场景中，时间轴下方两侧的时间标签整体偏靠外，且滑块上的时间气泡在滑到两端时被卡片边缘截断/压线，整体与最新 UI 式样不一致。

## 根因分析
两类叠加问题：其一，`seekbar_simple.xml` 里 SeekBar 自带的水平内边距未清零，加上外层 `paddingHorizontal=30dp`、右端 "300s" 标签没有右边距，导致两端时间文字的基准位置整体外扩；其二，`SceneModeFragment` 更新气泡标签时用 `labelX.coerceIn(0f, seekBar.width - labelWidth)` 把标签硬夹在 seekBar 范围内，而 Android ViewGroup 默认 `clipChildren=true`，滑到两端时气泡既被夹住又被父容器裁剪，位置与 UI 稿（气泡中心跟随滑块、可滑出卡片）相悖。缺陷库根因"ui显示遗漏/按最新 ui 修改"与 diff 一致。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt、application/Setting/src/main/res/drawable/tv_time_label_bg.xml、application/Setting/src/main/res/layout/fragment_scene_mode.xml、application/Setting/src/main/res/layout/layout_car_control_seat.xml、application/Setting/src/main/res/layout/seekbar_simple.xml（共 +28/-8）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
@@ 气泡标签定位
-            var labelX = thumbX - labelWidth / 2f
-            val minX = 0f
-            val maxX = seekBar.width.toFloat() - labelWidth
-            labelX = labelX.coerceIn(minX, maxX)
+            // 允许标签中心跟随滑块，两端可滑出卡片外（父容器已设置 clipChildren=false）
+            val labelX = thumbX - labelWidth / 2f
             timeLabel.x = labelX
@@ 新增
+    private fun allowTimeLabelOverflow() {
+        var parent = mBinding.includeKickstandDropTime.tvTimeLabel.parent
+        while (parent is ViewGroup) {
+            parent.clipChildren = false
+            parent.clipToPadding = false
+            parent = parent.parent
+        }
+    }
--- application/Setting/src/main/res/layout/seekbar_simple.xml
@@ SeekBar
+        android:paddingStart="0dp"
+        android:paddingEnd="0dp"
@@ 底部时间行
-        android:paddingHorizontal="@dimen/dp_30">
+        android:paddingHorizontal="@dimen/dp_26">
@@ 端点标签
             android:text="300s"
+            android:layout_marginRight="@dimen/dp_14"
```
配套改动：气泡背景 `tv_time_label_bg.xml` 由 80dp 加宽到 92dp（viewport 与 path 同步扩展）；`fragment_scene_mode.xml`、`layout_car_control_seat.xml`、`seekbar_simple.xml` 逐层补 `clipChildren="false"`。

## 为什么能修复
时间标签的"外扩"由 SeekBar 内建 padding 清零、外层 padding 30→26dp、端点标签补 14dp 右边距共同拉回 UI 稿位置；气泡的"夹住/截断"则通过去掉 `coerceIn` 限制 + 全父链 `clipChildren/clipToPadding=false` 解决，标签中心可以真正跟随滑块滑出卡片，气泡背景加宽 92dp 也匹配了新式样的文字宽度。风险是全局关闭裁剪后，若标签定位时机不当（宽度为 0 时代码里已有 `post` 与 `labelWidth<=0` 保护）可能出现闪烁，但位置计算本身仍是确定性的。

## 复盘与经验
- SeekBar 有默认的水平 thumb padding，`thumb="@null"` 时它不会消失，做"轨道与刻度精确对齐"必须显式 `paddingStart/End=0dp`。
- 需要"子 View 溢出父容器"的效果时，要同时改两处：去掉代码里的位置钳制（`coerceIn`），并在父链上关 `clipChildren/clipToPadding`——只改其一要么被裁剪、要么画出界看不见。
- 代码动态定位的标签（`timeLabel.x = ...`）与 XML 静态标签的位置基准必须同源（都相对轨道），否则修好一端另一端又会"靠外"。
