# SIR-6293 · 进入歌词模式再退出时闪现网易云音乐界面

- **提交**：`b5aee4a5` | 2026-08-26 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
从音乐应用进入歌词模式再退出时，屏幕上会闪现网易云音乐的界面（漏底/闪屏）。

## 根因分析
`PlatformNotifierImpl.showLinuxLyric()` 负责把仪表/桌面切到歌词形态（`settingService.setMeterForm(5)` 即置 S5 状态）。原实现是收到通知立即 `setMeterForm(5)`，而此刻 kanzi（3D 车模/桌面渲染侧）的进出歌词动效还在进行中——kanzi 与歌词面板两段动画同时执行（缺陷库根因），歌词面板先于 kanzi 底层就绪被拉起，中间态透出了底层尚未被遮盖的网易云音乐 surface，表现为闪现。本质是两个渲染端（SystemUI 侧面板、kanzi 侧底层）之间只有"发通知"而没有时序协同，谁先谁后全靠调度运气。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/notifier/PlatformNotifierImpl.kt`

```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/notifier/PlatformNotifierImpl.kt
@@ -48,8 +51,35 @@ class PlatformNotifierImpl (private val context: Context): PlatformNotifier {
     // → S5
     override fun showLinuxLyric() {
-        LogUtils.d(TAG,"[NOTIFY Linux] Show lyric page -> state=S5")
-        settingService.setMeterForm(5)
+        // 1. 先 startService 给 MapMiddlewareService 发 MeterForm=5
+        val intent = Intent().apply {
+            setClassName(
+                "com.yadea.mapmiddlewareservice",
+                "com.yadea.mapmiddlewareservice.service.MapMiddlewareService"
+            )
+            putExtra("Meter_Form", "5")
+        }
+        try {
+            context.startService(intent)
+        } catch (e: Exception) {
+            LogUtils.e(TAG, "Failed to start MapMiddlewareService", e)
+        }
+
+        // 取消上一次的 pending，避免快速连续触发导致多次 setMeterForm(5)
+        pendingShowLyricRunnable?.let {
+            mainHandler.removeCallbacks(it)
+            pendingShowLyricRunnable = null
+        }
+
+        // 2. 延迟 100ms 后再通知仪表侧 setMeterForm(5)
+        val runnable = Runnable {
+            pendingShowLyricRunnable = null
+            settingService.setMeterForm(5)
+        }
+        pendingShowLyricRunnable = runnable
+        mainHandler.postDelayed(runnable, 200)
     }
```

## 为什么能修复
进入歌词时先通过 `MapMiddlewareService`（`Meter_Form=5`）让底层侧先行动起来，再用 `mainHandler.postDelayed` 把 `settingService.setMeterForm(5)` 延后 200ms 发出，给 kanzi 底层动画留出完成时间——kanzi 动画走完后再执行歌词面板动画（缺陷库 sol），漏底窗口被错开消除。`pendingShowLyricRunnable` + `removeCallbacks` 保证快速连续进出歌词模式时旧的延迟任务被取消，不会叠加触发。隐患：200ms 是经验值硬编码（代码注释写 100ms 与实际 200ms 不一致），若 kanzi 动画时长调整需同步；依赖 handler 延迟做跨端时序协同本质是脆弱的"以时间换顺序"，更彻底的方案是 kanzi 动画完成回执后再触发。

## 复盘与经验
- 跨渲染端（Android SystemUI 与 kanzi/Linux 侧）的形态切换，两端动画必须有显式时序协议；靠"同时发、调度碰运气"必然偶现闪屏，经验延迟只是低成本止血。
- 延迟任务要配对管理（持有引用 + removeCallbacks），否则快速重复触发会堆积多个 pending 造成状态抖动——本 commit 的 pending 模式可直接复用。
- 代码注释与实现不一致（写 100ms 实为 200ms）会误导后续维护，魔法延迟能力值应提成常量并注明取值依据（如"kanzi 动画时长 + 余量"）。
