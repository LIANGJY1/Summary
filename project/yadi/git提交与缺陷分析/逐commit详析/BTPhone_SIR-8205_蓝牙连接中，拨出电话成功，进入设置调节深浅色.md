# SIR-8205 · 通话中切换深浅色后通话浮窗消失

- **提交**：`4fac1caa` | 2026-09-11 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 待测试验证 · 域 蓝牙电话
- 说明：缺陷库根因为"旧通话挂断后延迟关闭任务把新通话浮窗移除"；提交信息则写"日夜切换不刷新"。diff 实际同时包含两处修复，二者都是本问题链路的组成部分。

## 问题
蓝牙通话（如拨出电话成功）过程中，进入设置切换深/浅色模式，正在显示的通话浮窗消失（或显示错乱），通话 UI 丢失。

## 根因分析
两个叠加缺陷：
1. **浮窗是缓存视图、无日夜刷新**：`FloatCallWindow` 的布局按通话状态一次性 inflate 缓存，`BtPhoneApp` 的 uiMode 变更回调里只重建了 CarPlay 通话窗口，普通蓝牙电话浮窗没有任何刷新入口；昼夜切换后缓存视图里的资源仍是旧皮肤，窗口无法按新主题重载。
2. **过期延迟任务误关浮窗**：挂断/主叫为空等路径里用 `mHandler.postDelayed(this::showEmptyUI, 100/1000ms)` 延迟关闭浮窗。切深浅色触发状态重建后，这些旧任务到期仍无条件执行 `showEmptyUI()` 把浮窗清空——缺陷库指出的"旧通话的挂断后延迟关闭任务把新通话的浮窗给移除了"。`inflateOutGoingView` 中 `primaryCall == null` 时的 100ms 延迟同样可能误伤后续新状态。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/BtPhoneApp.java`、`.../floatview/FloatCallWindow.java`、`.../floatview/FloatCallWindowPresenter.java`、`.../floatview/FloatWindowManager.java`
```diff
// BtPhoneApp.java（uiMode 变更回调中补充蓝牙浮窗刷新）
+        // 普通蓝牙电话浮窗同样是缓存视图，日夜切换后需要按当前状态重新加载资源。
+        try {
+            FloatWindowManager.getInstance().onDayNightChanged();
+        } catch (Throwable t) {
+            LogUtils.e(TAG, "Refresh Bluetooth call window on uiMode change failed: " + t);
+        }
```
```diff
// FloatCallWindow.java（延迟关闭前校验当前通话状态）
+    private void hideAfterHangupIfStillIdle() {
+        if (mPresenter != null && mPresenter.getCurrentState() != InCallUiStateMachine.STATE_IDLE) {
+            LogUtils.i(TAG, "Skip stale hangup cleanup because call state has changed");
+            return;
+        }
+        showEmptyUI();
+    }
```
另：`onDayNightChanged()` 置 `mForceLayoutRefresh = true` 后调用 `mPresenter.updateLayoutInternal()`（内部改为 `initLayoutInternal(mCurrentCallStateCode, mPreviousCallState)`），`showMiniFlolatUI` 的刷新条件增加 `mForceLayoutRefresh ||` 强制 `inflateCallingFloatView()` 重载视图；三处 `postDelayed(showEmptyUI)` 统一替换为 `hideAfterHangupIfStillIdle`。

## 为什么能修复
日夜切换时新增的 `onDayNightChanged` 链路按当前通话状态码 `mCurrentCallStateCode` 重新走布局初始化并强制重 inflate，浮窗资源随主题刷新；延迟关闭统一收口到 `hideAfterHangupIfStillIdle`，任务执行前校验状态机非 `STATE_IDLE` 即跳过，旧通话的过期清理任务不再误关新通话浮窗。两个改动分别消除"不刷新"与"误关闭"两个根因。隐患：`mForceLayoutRefresh` 是成员标志位、靠 try/finally 复位，若 `updateLayoutInternal` 内部再入需保证幂等；延迟任务校验依赖状态机状态及时更新。

## 复盘与经验
- 缓存型浮窗/悬浮视图必须提供"配置变更后按当前状态重载"的入口，onConfigurationChanged 的处理清单要覆盖所有浮层，不只最显眼的那个。
- `postDelayed` 延迟执行关闭/清理时，任务体必须先校验"触发条件是否仍然成立"（状态机状态、会话 id），否则就是定时误伤炸弹。
- 缺陷库根因与提交说明不一致时，diff 里往往各修了一半，复盘要合并两条线索看完整场景。
