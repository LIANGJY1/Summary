# SIR-8546 · 频繁切换白天/黑夜模式，屏幕出现灰色显示
- **提交**：`fd3f4697` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 主交互

## 问题
频繁更换白天/黑夜模式时，屏幕出现灰色显示（重绘异常），界面颜色不随主题正确刷新。

## 根因分析
`DisplayFragment` 的主题切换入口 `rgDisplayMode.onItemChecked` 直接调用 `mViewModel.setDisplayMode(requireContext(), position)`（内部走 `UiModeManager` 切换 uiMode，触发系统级 configuration change 与全局重建/重绘）。连续快速点击时，多次主题切换请求叠加：上一次重建尚未完成，新的切换又触发 `uiMode` 变更，系统在中间态重绘出未完成配色的界面——表现即"灰色显示"。缺陷库 rc"系统频繁重绘导致"、提交 `[why] 系统重绘界面原因`、`[how] 按钮做防抖处理防止频繁切换` 三者一致：从触发源限流，而不是处理重绘结果。

## 关键代码修改
改动文件：DisplayFragment.kt（+15/-33）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
         mBinding.rgDisplayMode.onItemChecked { position, _ ->
+            // 防抖短时间内重复点击会连续触发重建导致重绘异常
+            val now = System.currentTimeMillis()
+            if (now - displayModeSwitchStartedAt < DISPLAY_MODE_SWITCH_DEBOUNCE) {
+                mBinding.rgDisplayMode.setSelectedIndex(
+                    SettingsUtils.getGSetting(THEME_SHOW_MODE, MODE_AUTO)
+                )
+                return@onItemChecked
+            }
+            displayModeSwitchStartedAt = now
             mViewModel.setDisplayMode(requireContext(), position)
         }
     }
+
+    companion object {
+        private const val DISPLAY_MODE_SWITCH_DEBOUNCE = 1000L
+        private var displayModeSwitchStartedAt = 0L
+    }
 }
```
（同提交还移除了上一个提交 f42050e1 加入的 `TIME_12_24` ContentObserver 及 `onDestroyView`，属回退性改动——与主题缺陷无关但需留意两次提交相互覆盖。）

## 为什么能修复
点击入口加 1000ms 防抖：防抖窗口内的重复点击被拒绝，并把单选组回设为已生效的主题（`SettingsUtils.getGSetting(THEME_SHOW_MODE)`），保证视觉与实际一致；窗口外的新点击正常放行并刷新时间戳。主题切换请求被限制为至多 1 秒一次，系统重建/重绘不再叠加，灰色中间态不再出现。隐患：`displayModeSwitchStartedAt` 是 `companion object` 静态变量，Fragment 重建后仍保留上次切换时间，属有意为之（跨实例防抖）；副作用是极快离开再进入页面后的首次切换可能被误拦 1 秒内，影响轻微。

## 复盘与经验
- 触发系统级重建的设置项（主题/语言/密度）必须在入口做防抖/节流，"重建风暴"型显示异常在源头限流远比事后恢复可靠。
- 拒绝请求时要同时把控件视觉回滚到已生效值（`setSelectedIndex(getGSetting(...))`），否则会出现"看着选中了、实际没切换"的新脱节。
- 注意同文件提交间的相互覆盖：本提交回退了 f42050e1 的 ContentObserver，两个 bugfix 在同一 Fragment 上先后落地又部分撤销，阅读历史时需比对确认（当前 HEAD 是否仍保留监听值得复查）。
