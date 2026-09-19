# 无单号 · BTMusic 黑白（深浅色）模式适配

- **提交**：`bbf3fef7` | 2026-07-01 | daizhecheng | BTMusic | feature
- **关联单**：无

## 需求/目标
让蓝牙音乐应用在系统深色/浅色（黑白）模式切换时实时刷新主题：Activity 监听 `uiMode` 配置变化后整体重刷背景、文字颜色与控件图标。

## 实现结构
- 修改 `MainActivity.kt`：新增 `onConfigurationChanged` 覆写 + `switchTheme()` 方法，按深浅模式重新给 `clContent`/`clForgetContent` 背景、标题/歌手/连接按钮文字色、空态图标赋值；另含部分格式化改动。
- 修改 `view/weiget/WidgetMusicPlayControl.kt`：新增 `switchView()`，刷新播放控制条（播放/上一曲/下一曲图标、时间文字色）。

数据流：系统切换深浅模式 → `onConfigurationChanged` 收到 `UI_MODE_NIGHT_YES/NO` → `switchTheme()` 手动重设主题资源 → 内嵌自定义控件通过 `playControl.switchView()` 二级刷新。项目里 Activity 大概率配置了 `uiMode` 不重建（avoid relaunch），所以采用手动刷主题而不是走资源重建。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
@@ -326,6 +321,35 @@
+    override fun onConfigurationChanged(newConfig: Configuration) {
+        super.onConfigurationChanged(newConfig)
+        val currentNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
+        when (currentNightMode) {
+            Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO -> {
+                LogUtils.i(TAG, "onConfigurationChanged currentNightMode=$currentNightMode")
+                switchTheme()
+            }
+            else -> {}
+        }
+    }
+
+    @SuppressLint("UseCompatLoadingForDrawables")
+    private fun switchTheme() {
+        mBinding.let {
+            it.clContent.background = resources.getDrawable(R.drawable.bg_main, null)
+            it.clForgetContent.background = resources.getDrawable(R.drawable.bg_main_forget, null)
+            it.ivPic.setImageResource(R.drawable.def_img)
+            it.tvTitle.setTextColor(resources.getColor(R.color.text_default_default, null))
+            ...
+            it.playControl.switchView()
+        }
+    }
```

实现讲解：`newConfig.uiMode and UI_MODE_NIGHT_MASK` 提取模式位，`UI_MODE_NIGHT_YES`/`NO` 两种情况都调 `switchTheme()`，由 drawable/color 资源系统自动按 `-night` 限定符给出对应深浅值——代码只负责"重新取一遍资源"，具体颜色差异全部交给资源目录，这是车机上避免 Activity 重建的经典适配手法。子控件 `WidgetMusicPlayControl` 暴露 `switchView()` 而不是让 Activity 直接摸内部 View，保持了封装边界。

## 复盘与要点
- 手动 `switchTheme()` 列举式刷资源：新增控件时容易漏刷（方法里逐个 View 赋值），更稳妥的做法是 `recreate()` 或用 `AppCompatDelegate.setDefaultNightMode()` 触发整棵视图树重建，取舍点是车机性能与闪屏。
- `switchView()` 里对 `ivPlayAndPause` 连续 `setImageResource` 两次（一次按播放态、一次固定 `music_play_selector`），后一次会覆盖前者，疑似笔误，属于可复查的小瑕疵。
- 可复用手法：自定义控件暴露 `switchView()` 这类主题刷新入口，配合宿主 `onConfigurationChanged` 统一分发，是模块内多控件换肤的轻量模式。
