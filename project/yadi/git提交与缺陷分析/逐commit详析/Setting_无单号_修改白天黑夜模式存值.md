# SIR-XXX · 修改白天黑夜模式存值

- **提交**：`4ec6d4f1` | 2026-09-04 | dufan | Setting | feature（内容实为缺陷修复：存取不对称）
- **关联单**：SIR-XXX（占位单号）

## 问题现象
显示设置页"白天/黑夜模式"选择项重启后回显错乱：写的是业务索引（`setGSetting(THEME_SHOW_MODE)`? 否——写侧实际存的是 Secure 设置 `ui_night_mode`），读侧也从 `Settings.Secure` 读 `ui_night_mode` 再翻译 UiModeManager 常量，但写侧存入的是 UI position，读侧按 `MODE_NIGHT_CUSTOM/NO/YES` 解析，两侧值域不一致导致回显错位。

## 根因分析
存取两端值域不对称：`setDisplayMode` 把 UI 的 position（0/1/2）直接 `setSecureSetting("ui_night_mode", position)`，而 `initDisplayMode` 读出后按 `UiModeManager.MODE_NIGHT_*`（0/1/2 恰好重叠但语义是 night mode 而非业务档位，且默认值与 else 分支的解释不同）。典型的"一个键被两个值域解释"。修复方向：回显改读自有键 `THEME_SHOW_MODE`（存业务 position，默认 `MODE_AUTO`），写侧改为先驱动 `UiModeManager` 再把业务 position 存入 `THEME_SHOW_MODE`，把"系统生效值"与"业务存档值"彻底分离。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
@@ -175,17 +175,11 @@
     private fun initDisplayMode() {
-        val mode = SettingsUtils.getSecureSetting(
-            "ui_night_mode",
-            UiModeManager.MODE_NIGHT_CUSTOM
-        )
         mBinding.rgDisplayMode.setSelectedIndex(
-            when (mode) {
-                UiModeManager.MODE_NIGHT_CUSTOM -> 0
-                UiModeManager.MODE_NIGHT_NO -> 1
-                UiModeManager.MODE_NIGHT_YES -> 2
-                else -> 0
-            }
+            SettingsUtils.getGSetting(
+                THEME_SHOW_MODE,
+                MODE_AUTO
+            )
         )
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/DisplayViewModel.kt
@@ -97,7 +97,6 @@
         val uiManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
-        SettingsUtils.setSecureSetting("ui_night_mode", position)
         when (position) {
             MODE_AUTO -> { uiManager.nightMode = UiModeManager.MODE_NIGHT_CUSTOM }
             MODE_DAY -> uiManager.nightMode = UiModeManager.MODE_NIGHT_NO
             MODE_NIGHT -> uiManager.nightMode = UiModeManager.MODE_NIGHT_YES
         }
+        SettingsUtils.setGSetting(THEME_SHOW_MODE, position)
```

## 为什么能修复
读写两端统一使用同一个键（THEME_SHOW_MODE）与同一个值域（业务 position 0/1/2），回显自然一致；系统 UiModeManager 只承担"生效"职责不再承担"存档"职责，职责单一后不存在翻译损耗。

## 复盘与经验
- 持久化键的值域必须在写入点和读取点唯一，中间不做任何常量翻译；需要翻译时（如系统 API 枚举），翻译只发生在"生效层"，存档层永远存业务枚举。
- `Settings.Secure` 属系统命名空间，应用私有业务状态写进去既易冲突又难迁移，本提交改回 `SettingsUtils.getGSetting` 是正确的边界回收。
- 多语言对照：写侧一个 `setSecureSetting("ui_night_mode", position)` 顺手删除，说明之前是"两处各存一半"的中间态，修复时把旧键一次性清干净，避免双写残留。
