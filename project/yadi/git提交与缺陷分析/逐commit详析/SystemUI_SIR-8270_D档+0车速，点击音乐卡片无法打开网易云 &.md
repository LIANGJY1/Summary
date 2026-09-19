# SIR-8270 · D 档 0 车速点击音乐卡片无法打开网易云

- **提交**：`6e464f1e` | 2026-09-11 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
D 档且车速为 0 时，点击负一屏的音乐卡片无法打开网易云音乐（被拦截提示媒体不可用）。同提交还顺带按仪表联调结果微调了主交互切换动画延迟。

## 根因分析
`NavBarFragment.handleMusicCardClick()` 原逻辑先取 `settingsControllerService.getDisplayState()`，仅当 `displayState == 0 || displayState == 3` 时放行，否则弹 `media_enter_toast`（ICT 提示）直接 return——即按"仪表形态"决定音乐卡片是否可点。缺陷库根因说明这是需求缺口："需求中未定义媒体卡片的点击状态，仅说明了中间快捷按钮的点击逻辑"，实现时自行用 displayState 白名单做了拦截；而中间快捷按钮的正确判据是 `mDriveTouchLocked`（行驶触屏锁定）。D 档 0 车速下未触发触屏锁定但 displayState 不在白名单，卡片被误拦。正确语义应是：能否点击只由行驶触屏锁定决定，与仪表形态无关。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java`、`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`
```diff
// NavBarFragment.java
     private void handleMusicCardClick() {
-        int displayState = settingsControllerService.getDisplayState();
-        if (displayState != 0 && displayState != 3) {
-            ToastUtils.INSTANCE.showMsgICToast(requireContext(), getString(R.string.media_enter_toast));
+        // 与NaviBar其他快捷入口保持一致：是否可点击只由行驶触屏锁定状态决定，
+        // 不再按仪表形态(displayState)拦截，D档0车速未锁定时允许打开媒体应用（SIR-8270）
+        if (mDriveTouchLocked) {
+            LogUtils.d(TAG, "handleMusicCardClick blocked by drive touch lock");
             return;
         }
+        exitLinuxFormToDriveHome();
         // 收起负一屏
         ActorController.Companion.getInstance().hideShortcutWindow();
```
```diff
// PageStateMachine.kt（与仪表联调微调动画延迟）
-    private const val D_ZONE_CLICK_INTO_S1_DELAY: Long = 333 //500
-    private const val D_GEAR_INTO_S1_DELAY: Long = 500 //500
+    private const val D_ZONE_CLICK_INTO_S1_DELAY: Long = 650 //500
+    private const val D_GEAR_INTO_S1_DELAY: Long = 650 //500
```

## 为什么能修复
拦截判据从 `displayState` 白名单换成与其它快捷入口一致的 `mDriveTouchLocked`：D 档 0 车速未锁定时卡片可点，命中行驶锁定时仍安全拦截，驾驶安全约束不放松。新增 `exitLinuxFormToDriveHome()` 先退出 Linux 形态回主界面，保证从任意仪表形态点击卡片都有正确的落地路径，而不再依赖形态白名单间接决定。动画延迟 333/500→650ms 属于与仪表联调的体验对齐，与本 bug 无因果。隐患：原来被 displayState 拦截的场景现在只要未锁屏即可拉起媒体，需确认这是需求期望（缺陷库已确认"卡片确实应该可以点击跳转"）。

## 复盘与经验
- 同一屏内多个入口（快捷按钮 vs 卡片）的可点判据必须复用同一状态源（`mDriveTouchLocked`），各自实现白名单必然出现不一致。
- 需求未定义的交互（卡片点击态）实现时自行补逻辑，应在需求评审时显式登记假设，否则验收时成为"缺陷"。
- 拦截条件应表达"为什么不能点"（行驶安全锁定），而不是用间接信号（仪表形态）近似。
