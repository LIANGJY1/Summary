# 无单号 · 黑夜模式支持动态切换

- **提交**：`79635079` | 2026-07-10 | daizhecheng | Vlog | feature
- **关联单**：无

## 需求/目标
让 Vlog（行车记录仪）应用在不重建页面的前提下响应系统昼夜模式切换：`HomeActivity` 与 `CameraPairedActivity` 收到 uiMode 变化后手动刷新文字颜色与背景，夜间资源走 `drawable-night` 限定符目录。

## 实现结构
改动 2 个 Activity + 8 个资源文件（+86/-14）：
- `main/ui/HomeActivity.kt`、`main/ui/CameraPairedActivity.kt`：新增 `onConfigurationChanged` 钩子（掩码取 `UI_MODE_NIGHT_MASK`，YES/NO 均触发）与 `switchTheme()`，对绑定中关键控件逐一 `setTextColor/setBackground` 重新赋值。
- 资源：`bg_main.xml` 改用主题感知色 `@color/bg_application`；新增 `drawable/bg_view_top_line.xml`；夜间版 `drawable-night/icon_more.png` 新增、`main_bg2.png` 双目录删除（布局改引 `bg_main`）；`activity_home.xml`、`camera_pair_activity.xml` 背景与 id 调整。

数据流：系统切深色 → Activity 因 Manifest 已声明 `android:configChanges="uiMode|..."`（74/84 行，本提交未改 Manifest，沿用既有声明）不重建 → `onConfigurationChanged` → `switchTheme()` 用限定符资源重刷皮肤。

## 关键代码
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/HomeActivity.kt
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
+    fun switchTheme() {
+        mBinding.let {
+            it.btnOpenCapture.setTextColor(resources.getColor(R.color.text_default_default, null))
+            it.clContent.background = resources.getDrawable(R.drawable.bg_main, null)
+            it.leftContainer.background = resources.getDrawable(R.drawable.btn_bg_radio16_white, null)
+        }
+    }
```
实现讲解：`configChanges="uiMode"` + 手动 `switchTheme` 的组合换取"切换不闪屏、不丢状态"的体验，代价是把皮肤刷新责任从框架接管到业务代码——每个需要动态换肤的 Activity 都要枚举自己的控件清单。`getColor(color, null)`/`getDrawable(id, null)` 均传 theme 参数为 null 以自动用当前 Activity 主题解析，昼夜资源因此能自动切换。

## 复盘与要点
- 可复用骨架：`onConfigurationChanged(NIGHT_MASK) → switchTheme()`，适合车机上"全局深色随时切"的场景；比 `AppCompatDelegate.setDefaultNightMode` 自动重建更可控，但要维护控件清单。
- 手写清单易漏易错：本提交 `CameraPairedActivity` 的 `switchTheme` 里 `mainTxt1.setTextColor` 连写两遍（复制粘贴残留），第二行覆盖第一行—— reviewer 应对这种"清单式刷新"逐行核对。
- 遗留风险：动态添加的 View、Dialog 不在 `mBinding` 清单内不会换肤；`getDrawable` 每次 new Drawable 有轻微开销，频繁切换可缓存。
