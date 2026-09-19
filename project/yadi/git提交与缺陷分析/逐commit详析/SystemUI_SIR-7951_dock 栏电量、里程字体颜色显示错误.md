# SIR-7951 · Dock 栏电量、里程字体颜色显示错误
- **提交**：`9df88ce2` | 2026-09-15 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 待测试验证 · 域 车控车设（rc/sol：需求变更导致，补充开机后下发主题状态逻辑）

## 问题
仪表 Dock 栏的电量、里程字体颜色显示错误——仪表侧主题（日/夜）状态与 IVI 实际主题不一致，尤其开机后。

## 根因分析
`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt` 负责向仪表（Meter）同步 UI 状态，但此前的"连接就绪"同步流程只补发了相机状态、行驶触屏锁定状态（`setDriveTouchLock`）等，**没有下发当前主题（日夜模式）**。正常场景主题切换事件会实时推给仪表，但开机时序下仪表连接建立晚于主题状态确立，或需求变更后要求开机即同步——仪表拿不到当前主题就按默认色板渲染 Dock 栏电量/里程字体，颜色与当前模式不符。根因即缺陷库所记："需求变更导致，补充开机后下发主题状态逻辑"——状态同步清单缺少"主题"这一项。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
@@ -964,6 +965,17 @@
                     getCameraStatus()
                     // 重连后补发行驶触屏锁定状态，保证仪表 Dock 锁屏标记与 IVI 侧一致
                     setDriveTouchLock(if (PageStateMachine.getContext().isScreenLocked) 1 else 0)
+                    // 开机/重连时同步当前主题到仪表
+                    val currentNightMode = mAppContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
+                    val themeValue = when (currentNightMode) {
+                        Configuration.UI_MODE_NIGHT_YES -> 1
+                        Configuration.UI_MODE_NIGHT_NO -> 0
+                        else -> -1
+                    }
+                    if (themeValue != -1) {
+                        LogUtils.d(TAG, "sync theme on AL connect: themeValue=$themeValue")
+                        setMeterThemeStyle(themeValue)
+                    }
                     // 重连后如果还有未确认的 MeterForm，立即补发一次
```

## 为什么能修复
在仪表连接就绪的同步点，读取 `mAppContext.resources.configuration.uiMode` 的 `UI_MODE_NIGHT_MASK`，映射为 `setMeterThemeStyle(1=夜/0=日)` 下发，与 `setDriveTouchLock` 等"重连补发"项目并列，保证开机/重连后仪表主题与 IVI 强制对齐，Dock 字体颜色随正确色板渲染。`UI_MODE_NIGHT_UNDEFINED` 场景以 -1 跳过、不误发。隐患：仅覆盖连接建立时机，若连接后 IVI 主题变化依赖原有实时推送路径，两路并存需保证同一入口（`setMeterThemeStyle`）避免乱序。

## 复盘与经验
- 跨屏（IVI→仪表）状态同步要区分"事件流"与"状态快照"：事件流会丢（连接建立前的切换事件），连接就绪时必须补发一份当前状态快照。
- "重连后补发"清单模式（先锁屏状态、后主题）值得沉淀为显式列表，新增可同步状态时逐项登记，避免再漏。
- 需求变更（新增开机同步要求）落地时，检查现有同步清单是否覆盖新状态，是缺陷库 rc"需求变更导致"的典型成因。
