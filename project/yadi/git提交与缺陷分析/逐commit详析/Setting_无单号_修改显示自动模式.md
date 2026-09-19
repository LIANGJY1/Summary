# 无单号 · 显示"自动模式"无效（黑夜切换不生效）
- **提交**：`bd6c4236` | 2026-08-28 | sgh | Setting | bugfix
- **缺陷库**：未关联单号（无缺陷记录）

## 问题
显示设置选"自动模式"后，昼夜主题不会按预期自动切换，自动模式形同虚设。

## 根因分析
`DisplayViewModel.setDisplayMode()` 原实现对 MODE_AUTO 调用的是 `uiManager.nightMode = UiModeManager.MODE_NIGHT_AUTO`。车机上该模式依赖环境光传感器及系统侧自动判定链路，在此平台（按提交消息"按fw老师要求"）并不生效。同时选择状态持久化用的是应用私有 GSetting `THEME_SHOW_MODE`，与系统 `UiModeManager` 的真实状态是两份孤立数据，`initDisplayMode()` 回显时读的是自己的 GSetting，即使系统实际处于别的模式，UI 也无从发现，"设置值"和"生效值"长期脱节。

## 关键代码修改
改动文件：DisplayViewModel.kt、DisplayFragment.kt、SettingsUtils.kt
```diff
--- a/.../ui/viewmodel/DisplayViewModel.kt
@@ setDisplayMode
-        SettingsUtils.setGSetting(THEME_SHOW_MODE, position)
+        SettingsUtils.setSecureSetting("ui_night_mode", position)
         when (position) {
-            MODE_AUTO -> uiManager.nightMode = UiModeManager.MODE_NIGHT_AUTO
+            MODE_AUTO -> {
+                uiManager.nightMode = UiModeManager.MODE_NIGHT_CUSTOM
+                // 定义夜间模式从晚上 7:00 开始
+                val startTime = LocalTime.of(19, 0)
+                // 定义夜间模式到早上 6:00 结束
+                val endTime = LocalTime.of(6, 0)
+                uiManager.customNightModeStart = startTime
+                uiManager.customNightModeEnd = endTime
+            }
             MODE_DAY -> uiManager.nightMode = UiModeManager.MODE_NIGHT_NO
             MODE_NIGHT -> uiManager.nightMode = UiModeManager.MODE_NIGHT_YES
         }
```
```diff
--- a/.../ui/fragment/DisplayFragment.kt
@@ initDisplayMode
+        val mode = SettingsUtils.getSecureSetting("ui_night_mode", UiModeManager.MODE_NIGHT_CUSTOM)
         mBinding.rgDisplayMode.setSelectedIndex(
-            SettingsUtils.getGSetting(THEME_SHOW_MODE, MODE_AUTO)
+            when (mode) {
+                UiModeManager.MODE_NIGHT_CUSTOM -> 0
+                UiModeManager.MODE_NIGHT_NO -> 1
+                UiModeManager.MODE_NIGHT_YES -> 2
+                else -> 0
+            }
         )
```
`SettingsUtils` 新增 `getSecureSetting`/`setSecureSetting`（基于 `Settings.Secure.getInt/putInt`）。

## 为什么能修复
把"自动"从依赖硬件判定的 `MODE_NIGHT_AUTO` 换成 `MODE_NIGHT_CUSTOM` 并显式写入自定义时段（19:00–次日 6:00），系统按时间自动切换，不再依赖光感链路，这正是提交消息"按 fw 老师要求设置时间"的含义；状态源从应用 GSetting 迁移到系统 `Settings.Secure.ui_night_mode`，设置页与系统共用同一真值，回显不再失真。隐患：`setDisplayMode` 标了 `@RequiresApi(R)`，调用侧无版本分支，若系统低于 R 会 NoSuchMethod；写 `Settings.Secure` 需要系统签名权限，普通应用会静默失败——车机系统应用场景下均成立，但移植需注意。

## 复盘与经验
- `MODE_NIGHT_AUTO` 在无有效光感数据链的车机上不可用，"自动"需求应尽早与系统/底软对齐实现方式（时间制 CUSTOM 是常见替代）。
- 设置项的"UI 状态"与"系统真实状态"必须同源，双写两份存储迟早漂移；迁移存储源时要同步改读、写、回显三点。
- 平台 API 行为差异（AUTO 不可用）这类坑，靠真机全模式遍历测试才能提前暴露。
