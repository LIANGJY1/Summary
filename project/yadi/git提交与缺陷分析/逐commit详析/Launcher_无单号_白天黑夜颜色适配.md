# [SIR-XXXX] · Launcher 白天黑夜颜色适配（uiMode 交还系统 + Kanzi 同步日夜）

- **提交**：`31f1f23c` | 2026-06-30 | liujinfeng | Launcher(+CommonTools) | feature
- **关联单**：SIR-XXXX 占位号

## 需求/目标
Launcher 主界面与列表页的黑白模式颜色适配：把 `uiMode` 从 configChanges 白名单移除、交还系统重建 Activity 完成资源自动切换；Kanzi 3D 侧则通过新增的 `WEATHER_STATUS` 信号感知日夜；重命名弹窗支持换肤后刷新。

## 实现结构
- `AndroidManifest.xml`（关键一笔）：application 与 AppListActivity 的 `configChanges` 删掉 `uiMode`——此前日夜切换不重建 Activity、只能靠手动刷肤，此后走 `onConfigurationChanged`→资源重载。
- `MainActivity.kt`：持持有 `kanziDataSourceManager`/`renameDialog` 字段；`onConfigurationChanged` 中 `refreshWindowBg()`（窗口背景改用 `drawable-night/main_kanzi_view.png`，删除按名字符串取 drawable 的旧法）+ `setUiModeToKanzi(newConfig)` + `renameDialog?.refreshUI()`；`setUiModeToKanzi` 将 `UI_MODE_NIGHT_MASK` 折算 0/1 写给 Kanzi `CarModel.WEATHER_STATUS`；生命周期里 `!!` 全部改 `?.`。
- `RenameDialogManager.kt`：`showRenameDialog` 增加 `onDismiss` 回调并返回 `EditDialog?` 实例，去掉 `dialogBg = R.color.transparent` 特殊背景。
- `CommonTools/dialog/EditDialog.kt`（+19 行）：新增 `refreshUI()` 与 `DismissListener` 支持（配合上述刷新）。
- `AppListActivity.kt`：列表收起遮罩从 `Color.argb(写死 SCRIM 红/绿/蓝)` 改 `setBackgroundResource(R.color.bg_gaussianblur_2)` 语义色。
- 资源：`applist_tab_text_color_selector.xml`、`activity_app_list.xml` tab 颜色语义化；新增 `drawable-night/main_kanzi_view.png`（267KB 夜间主背景）；SystemUI colors.xml 2 处同步。
- 附带：`kanzi-release.aar` 229MB→294MB 升级。

数据流：日夜切换 →（无 uiMode 声明）系统重建 Activity/触发 onConfigurationChanged → Android 资源自动换肤；同时 MainActivity 主动把 0/1 写入 Kanzi WEATHER_STATUS → 3D 车模场景切换日夜光照。

## 关键代码
```diff
# application/Launcher/src/main/AndroidManifest.xml
-        android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|uiMode|locale|layoutDirection|touchscreen"
+        android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|locale|layoutDirection|touchscreen"
```
```diff
# application/Launcher/src/main/java/com/yadea/launcher/function/main/view/MainActivity.kt
+    fun setUiModeToKanzi(config: Configuration) {
+        val uiMode = getCurrentUiMode(config)
+        mKzManager?.setValue("", KanziType.CarModel.WEATHER_STATUS, uiMode)
+    }
+
+    fun getCurrentUiMode(config: Configuration): Int {
+        return when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
+            Configuration.UI_MODE_NIGHT_YES -> 1
+            else -> 0
+        }
+    }
```

实现讲解：本提交是整个黑白模式适配的"策略转折点"——此前 dufan 系列靠 `onConfigurationChanged` 手动逐控件刷肤（前提是 Activity 不重建），这里反过来把 `uiMode` 移出 configChanges 让系统重建、资源自动切换，只需为"无法重建的部分"（Kanzi Surface、已显示的 Dialog）做手动同步。`WEATHER_STATUS` 信号是 Android UI 与 Kanzi 3D 两套渲染体系的日夜桥接点。

## 复盘与要点
- 核心取舍：系统重建（省手动刷肤代码、状态恢复风险）vs 手动 onConfigurationChanged（保留现场但要维护刷肤清单）。Launcher 主页选择重建、弹窗走 refreshUI，是混合策略的实例。
- 可复用手法：凡有自绘/跨渲染引擎（Kanzi/Unity）的界面，日夜适配都要一个"uiMode→引擎信号"的桥接 value；用 `uiMode and UI_MODE_NIGHT_MASK` 而非魔数比较。
- 遗留风险：把 65MB 增量的 kanzi aar 与颜色适配混在一个提交；Activity 重建路径需验证 Kanzi init 不会重复泄漏 Surface。
