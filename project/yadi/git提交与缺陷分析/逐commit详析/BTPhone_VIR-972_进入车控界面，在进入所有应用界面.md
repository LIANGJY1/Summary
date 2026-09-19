# VIR-972 · applist 中点 Home 只退一层未回主界面

- **提交**：`11b6db48` | 2026-09-11 | caohongliang | BTPhone（实际改动 Launcher/SystemUI） | bugfix
- **缺陷库**：未关联单号（VIR-972 元数据在 w/wh/ho 中；defs 为空）
- 模块标注说明：元数据模块为 BTPhone，但 diff 实际全部落在 Launcher 与 SystemUI，以 diff 为准。

## 问题
从其它应用（如车控界面）进入"所有应用"（applist）后点击 Home 键，只执行了 applist 的退场动画回到上一层，没有回到桌面主界面。

## 根因分析
Home 键响应在 `SystemUI` 的 `NavBarFragment` 中：当 `currentPosition == 4`（applist 场景）时调用 `sendBroadcastExitAppListPage()` 发广播 `ACTION_EXIT_WITH_ANIMATOR`。`ExitWithAnimatorReceiver` 收到后固定调用 `AppListExitRequestRouter.shared.requestExitIfAvailable(false)`——第二个参数 `isNeedGoHome` 写死 false，表示"只退场、不回桌面"；`AppListActivity` 退场动画结束时 `onAnimationEnd` 里无论 `isNeedGoHome` 与否都 `finish()`，`navigateToLauncher()` 虽会执行但也只是显式跳 `MainActivity`。整条链路没有任何一处把 Home 语义（回桌面）传给 applist，因此 Home 与普通退场行为完全一样。另外 `navigateToLauncher` 用硬编码 `setClassName("com.yadea.launcher", "...MainActivity")` 指定桌面，不如标准 HOME intent 通用。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt`、`application/Launcher/src/main/java/com/yadea/launcher/services/ExitWithAnimatorReceiver.kt`、`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java`
```diff
// NavBarFragment.java（Home 按键分支）
         if (currentPosition == 4) {
-            sendBroadcastExitAppListPage();
+            sendBroadcastExitAppListPage(true);
         } else {
             SettingsUtils.INSTANCE.setGSetting("is_click_home", 1);
             ActivityStarter.INSTANCE.goLauncher(requireContext(), view);
```
```diff
// ExitWithAnimatorReceiver.kt
-        AppListExitRequestRouter.shared.requestExitIfAvailable(false)
+        val isNeedGoHome = intent.getBooleanExtra("isNeedGoHome", false)
+        AppListExitRequestRouter.shared.requestExitIfAvailable(isNeedGoHome)
```
```diff
// AppListActivity.kt
                 override fun onAnimationEnd(animation: Animator) {
                     if (isNeedGoHome) {
                         navigateToLauncher()
+                    } else {
+                        finish()
                     }
-                    finish()
                 }
```
`navigateToLauncher()` 同时改为标准 `Intent(Intent.ACTION_MAIN)` + `addCategory(Intent.CATEGORY_HOME)` 启动桌面。

## 为什么能修复
把"是否回桌面"的语义从 SystemUI 一路通过广播 extra `isNeedGoHome` 传到 `AppListExitRequestRouter`，动画结束时按标志分流：Home → `navigateToLauncher()`（走 CATEGORY_HOME 回主界面）；普通退场 → 仅 `finish()`，两者行为不再混用。`CATEGORY_HOME` 方式也不再硬编码桌面类名，若后续更换 Launcher 实现依然有效。副作用：`navigateToLauncher()` 内部已有 `finish()`，原代码动画结束后又 `finish()` 一次属重复调用，新代码将其收敛进 else 分支，逻辑更干净。

## 复盘与经验
- 跨应用（SystemUI → Launcher）的页面退出请求，广播 extra 里必须带"意图语义"（如 isNeedGoHome），否则接收方只能按单一行为处理。
- 带动画退场时，"动画结束回调里统一 finish"容易把互斥分支（回桌面 vs 仅退场）揉在一起，应在回调中按状态分流。
- 启动桌面用 `ACTION_MAIN`/`CATEGORY_HOME` 标准 intent，避免硬编码包名类名带来的耦合。
