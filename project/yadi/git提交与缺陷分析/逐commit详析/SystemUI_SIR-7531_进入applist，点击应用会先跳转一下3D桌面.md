# SIR-7531 · 进入applist点击应用先闪一下3D桌面再进应用

- **提交**：`6c2eafbe` | 2026-09-15 | caohongliang | SystemUI/Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 问题取消 · 域 主交互

## 问题
进入 applist（应用列表）后点击某个应用，界面会先跳/闪一下 3D 桌面（Launcher 主界面），随后才进入目标应用，跳转路径不符合 UX 预期。注意缺陷库状态为"问题取消"，但代码侧仍做了逻辑修正。

## 根因分析
两个因素叠加：1) `AppListActivity` 是可复用实例（`onNewIntent` 复活），`onNewIntent` 里每次都会调 `prepareForEnterAnimation()` 重放入场动画，同时旧的复位逻辑挂在 `onPause()`；从 applist 启动应用时 applist 只是 pause 不 stop，`shouldResetForReusableInstance` 等复位路径与启动竞态，导致表现上先回到桌面再进应用。2) `AppInfoUtils` 的启动 Intent 只带 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`，没有 `FLAG_ACTIVITY_TASK_ON_HOME`，应用任务被压在 Launcher 任务之下，启动瞬间露出 3D 桌面。同提交还顺带把通知中心卡片圆角从 `dp_18` 硬编码改为引用 `notification_card_radius`（值由 24dp 改回 18dp），与该单无关，属顺带 UI 调整。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt、application/Launcher/src/main/java/com/yadea/launcher/utils/AppInfoUtils.java、application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt、application/SystemUI/src/main/res/drawable/vector_notify_center_bg.xml、application/SystemUI/src/main/res/values/dimens.xml
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
@@ onNewIntent
         openCarConnectTab(intent)
-        prepareForEnterAnimation()
+//        prepareForEnterAnimation()
         scheduleEnterAnimationIfNeeded()
@@ onPause -> onStop
     override fun onPause() {
         LogUtils.d(TAG, "onPause")
+        super.onPause()
+    }
+
+    override fun onStop() {
+        LogUtils.d(TAG, "onStop")
+        super.onStop()
         if (!SIsCarConnectClick && enterAnimationController.shouldResetForReusableInstance(
                 isFinishing = isFinishing,
                 isChangingConfigurations = isChangingConfigurations,
@@ 启动标志补齐（AppInfoUtils.java / ActivityStarter.kt 同理）
             intent.addFlags(
                     Intent.FLAG_ACTIVITY_NEW_TASK
                             | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
+                            | Intent.FLAG_ACTIVITY_TASK_ON_HOME
             );
```

## 为什么能修复
`FLAG_ACTIVITY_TASK_ON_HOME` 让从 applist/SystemUI（`ActivityStarter` 同步补了该标志）启动的应用任务"钉"在桌面任务之上，点击应用直接在前台过渡，不再露出 3D 桌面。把复位逻辑从 `onPause()` 挪到 `onStop()`，使"启动应用仅 pause"的场景不再触发 reusable 实例复位，消除与启动动画的竞态；注释掉 `onNewIntent` 里的 `prepareForEnterAnimation()` 则避免复用实例再次重放入场动画。隐患：若某条路径依赖"pause 即复位"，行为会推迟到 stop；且入场动画在复用场景不再重放，需 UX 确认可接受。

## 复盘与经验
- 车机"全屏 Activity 可复用（singleTask/onNewIntent）"的页面，生命周期复位逻辑应放在 `onStop`（真正不可见），放 `onPause` 会与前台跳转产生大量竞态——这是本类跳转类 bug 的高频根因。
- 想让"从桌面列表进应用不闪桌面"，`FLAG_ACTIVITY_TASK_ON_HOME` 是标准解法，多个启动入口要同步加，漏一个入口就复现。
- 一个 fix 提交里混入无关 UI 资源改动（圆角 dimen 抽取）会污染归因，建议拆分提交。
