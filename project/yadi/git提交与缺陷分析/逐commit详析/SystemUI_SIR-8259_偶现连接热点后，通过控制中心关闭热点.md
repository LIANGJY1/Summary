# SIR-8259 · 控制中心关热点后仍显示"打开未连接"
- **提交**：`2990c7d7` | 2026-09-14 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互

## 问题
偶现：连接热点后通过控制中心关闭热点，实际热点已关闭，但控制中心显示"热点打开、未连接"的错误状态。

## 根因分析
`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt` 中，原 `hotspotState` 是一个计算属性 getter，每次读取都实时调 `hotspotManager?.isHotspotEnabled` 向系统热点接口查询。热点关闭是异步流程：`WifiManager` 发出 `WIFI_AP_STATE_DISABLED` 广播后，底层接口释放存在延迟，此时若有连接数量回调（连接数清零）触发 UI 刷新，读取到的 `isHotspotEnabled` 仍为 true，于是 `updateHotspot(true, 0)` 把"打开未连接"画上界面；随后状态广播即使到达，也因界面状态机不区分来源而无法可靠纠正。根因是**两路事件源（状态广播、连接数回调）+ 一个非确定性的实时查询**共同决定同一 UI 状态，时序竞态下必然偶现错乱。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
@@ -91,6 +91,10 @@
     private var isBluetoothOpen = false
+    /**
+     * 保存热点是否打开，以状态广播为准，避免热点接口释放延迟导致状态回退
+     */
+    private var hotspotState = false
     private var hotspotConnectedDevices = 0
@@ -168,47 +172,55 @@
                     WifiManager.WIFI_AP_STATE_ENABLED -> {
                         LogUtils.e(TAG, "---Hotspot enabled---")
+                        hotspotState = true
                         Settings.Global.putInt(...)
@@ (DISABLED 分支)
                     WifiManager.WIFI_AP_STATE_DISABLED -> {
                         LogUtils.e(TAG, "---Hotspot disabled---")
+                        hotspotState = false
+                        Settings.Global.putInt(
+                            context.contentResolver,
+                            SysUIConfig.HOTSPOT_STATE,
+                            0
+                        )
                         hotspotConnectedDevices = 0
                         if (mStatusBarUICallback != null) {
-                            mStatusBarUICallback!!.updateHotspot(false, 0)
+                            mStatusBarUICallback?.updateHotspot(false, 0)
                         }
@@ (FAILED 分支同样 hotspotState = false)
@@ -318,6 +330,7 @@
         hotspotManager = HotspotManager.getInstance(mAppContext)
+        hotspotState = hotspotManager?.isHotspotEnabled ?: false
@@ -574,14 +587,7 @@
-    private val hotspotState: Boolean
-        /**************************************HOTSPOT START */
-        get() {  //NOSONAR
-            // 检查热点是否开启
-            val isWifiApEnabled: Boolean = hotspotManager?.isHotspotEnabled ?: false//NOSONAR
-            return isWifiApEnabled
-        }
-
+    /**************************************HOTSPOT START */
```
（同提交还顺带把大量 `!!` 改为 `?.`、`getAction()` 改为 `action` 等 Kotlin 规范化清理，以及配对监听里 `device!!` 改 `device?.` 防 NPE。）

## 为什么能修复
把 `hotspotState` 从"读时实时查询"改为"写时由 `WIFI_AP_STATE_CHANGED` 广播维护的单一事实来源"，启动时用 `isHotspotEnabled` 做一次初值同步；关闭流程中连接数回调再怎么先到，读到的也是广播尚未翻转前的旧值，而广播 DISABLED 一到就强制 `updateHotspot(false, 0)` 并同步写 `Settings.Global`，UI 终态一定正确。隐患：若进程存活期间广播丢失（极端场景），缓存会与真实状态漂移，但有启动时初值同步兜底。

## 复盘与经验
- 同一 UI 状态有多个事件源时，必须指定唯一权威来源（这里是状态广播），其余事件只贡献附属数据（连接数），否则时序竞态必然产生偶现显示 bug。
- "实时 getter"看似永远准确，实则在异步状态迁移窗口期恰恰最不可靠；缓存 + 明确的写入口是更稳的模式。
- 偶现"开关状态回退/错乱"类问题，先画出事件到达顺序图，找出读非确定性状态的路径。
