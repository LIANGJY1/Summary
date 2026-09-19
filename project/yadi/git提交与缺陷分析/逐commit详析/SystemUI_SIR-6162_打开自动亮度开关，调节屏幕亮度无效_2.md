# SIR-6162 · 打开自动亮度开关时调节屏幕亮度无效（SystemUI 侧）

- **提交**：`d57cb555` | 2026-08-24 | liujinfeng | SystemUI | bugfix
- **缺陷库**：SIR-6162 等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互（与 Setting 侧 `16e6c292` 同单号协同修复）

## 问题
自动亮度开关处于打开状态时，用户拖动控制中心屏幕亮度条（以及 HUD 亮度条），亮度不随拖动变化。

## 根因分析
`BrightnessTile` 的 `onProgressChanged(fromUser=true)` 中原有顺序是：先 `systemSettingsControllerService.setScreen(SysUIConfig.ID_BACKLIGHT_BRIGHT, progress)` 设置亮度值，再检测 `brightnessModeBtn.isSelected()` 为真时 `performClick()` 关闭自动亮度。由于亮度设置指令先于"关自动亮度"指令到达 L2A/系统侧，关闭自动亮度时系统会用自动策略重算并覆盖刚写入的手动亮度值，最终表现为"拖了没效果"。缺陷库根因记录为"先调节的亮度，后关闭的自动亮度"，提交 how 明确"按照L2A接口要求，先关闭自动亮度，再调节亮度"——即接口约定要求先退出自动模式再写手动值，原实现顺序恰好相反。`HUDBrightnessTile.kt` 的 HUD 亮度路径存在完全相同的问题。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BrightnessTile.java、application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BrightnessTile.java
@@ onProgressChanged(fromUser=true)
+                // 先关闭自动亮度，再调节亮度
+                if (brightnessModeBtn.isSelected()) {
+                    brightnessModeBtn.performClick();
+                }
                 LogUtils.d(TAG, "Adjust brightness: " + progress);
                 systemSettingsControllerService.setScreen(SysUIConfig.ID_BACKLIGHT_BRIGHT, progress);
                 ReboundHelper.start(...);
-                if (brightnessModeBtn.isSelected()) {
-                    brightnessModeBtn.performClick();
-                }
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt
@@ onProgressChanged(fromUser=true)
+                    // 先关闭自动亮度，再调节亮度
+                    if (ivAutoSwitch.isSelected) {
+                        ivAutoSwitch.performClick()
+                    }
                     settingService.setHud(SysUIConfig.ID_HUD_BRIGHT_ADJUST,progress)
                     ...
-                    if (ivAutoSwitch.isSelected) {
-                        ivAutoSwitch.performClick()
-                    }
```

## 为什么能修复
把"关闭自动亮度"的 `performClick()` 提到设置亮度之前，指令序列满足 L2A 接口要求：自动策略先退出，随后写入的手动亮度不再被覆盖，拖动立即生效。屏幕亮度与 HUD 亮度两条路径同步修改，行为一致。隐患：`performClick()` 走按钮点击通道关闭自动亮度，触发一次额外的开关状态回写与 UI 刷新；若关闭指令为异步且无完成回调，极端情况下仍可能与亮度设置并发，但顺序正确后窗口已足够小。

## 复盘与经验
- 有依赖关系的两条控制指令，顺序就是语义：写值前先解除会覆盖该值的模式（自动/同步策略），这类"先退模式再设值"约定要在接口文档中显式化。
- 同一逻辑在多个 Tile（屏幕/HUD）中复制粘贴时，bug 也会成对出现，修复必须全量排查复制体。
- 拖动条 `onProgressChanged` 是高频回调，其中的模式切换动作要保证幂等，避免多次触发 `performClick()`。
