# SIR-7375 · 两路通话来回切换，偶现自动切回上一路
- **提交**：`b6741b82` | 2026-09-04 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙连接下两路通话（一方激活、一方保持）来回切换时，偶现切换后又自动回到上一路通话。

## 根因分析
`UiCallManager.switchCalls()` 旧实现把切换当成两个动作手工编排：先 `holdCall(activeCall)` 保持当前激活通话，再 `unholdCall(holdingCall)` 恢复保持通话。但 Telecom 框架在执行 unhold 时本身就会自动 hold 当前激活通话，于是 `activeCall` 收到两次 hold 语义（一次显式、一次 Telecom 内部），协议栈侧状态机在两次指令交错时把刚恢复的通话再次切回，表现为"自动回到上一路"。偶现源于两次指令到达/状态回调的时序竞争。此外旧代码没有切换进行中的状态标记，切换未完成时可再次点击切换按钮叠加指令，进一步放大竞态。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java`（核心）、`.../telecom/CallListener.java`、`.../floatview/FloatCallWindow.java`、`.../floatview/FloatCallWindowPresenter.java`、`.../floatview/IFloatWindowView.java`、`.../ThreeWayCallingActivity.java`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
             // 只有在一个通话激活、一个通话保持的情况下才能切换
             if (activeCall != null && holdingCall.getState() == Call.STATE_HOLDING) {
-                // 先保持当前激活的通话
-                holdCall(activeCall);
-                // 然后恢复之前保持的通话
-                unholdCall(holdingCall);
-
-                LogUtils.i(TAG, "Call switch completed: ...");
+                if (mPhoneForward == null || !mCallMapping.containsKey(activeCall)
+                        || !mCallMapping.containsKey(holdingCall)
+                        || isCallPendingTermination(activeCall) || isCallPendingTermination(holdingCall)) {
+                    LogUtils.w(TAG, "Ignore call switch: call unavailable or terminating");
+                    return;
+                }
+                mSwitchFromCall = activeCall;
+                mSwitchToCall = holdingCall;
+                mHandler.postDelayed(mCallSwitchTimeout, CALL_SWITCH_TIMEOUT_MS);
+                for (CallListener listener : mCallListeners) {
+                    listener.onCallSwitchingChanged(true);
+                }
+                try {
+                    // Telecom 会自动保持当前通话，只下发一次恢复操作。
+                    unholdCall(holdingCall);
+                } catch (RuntimeException e) {
+                    finishCallSwitch("request failed");
+                }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
+    private static final long CALL_SWITCH_TIMEOUT_MS = 5000;
+    private UiCall mSwitchFromCall;
+    private UiCall mSwitchToCall;
+    private final Runnable mCallSwitchTimeout = () -> finishCallSwitch("timeout");
+
+    public boolean isCallSwitching() {
+        return mSwitchFromCall != null;
+    }
+
+    private void updateCallSwitchState() {
+        if (!isCallSwitching()) return;
+        int fromState = mSwitchFromCall.getState();
+        int toState = mSwitchToCall.getState();
+        if (!mCallMapping.containsKey(mSwitchFromCall) || !mCallMapping.containsKey(mSwitchToCall)
+                || (fromState != Call.STATE_ACTIVE && fromState != Call.STATE_HOLDING)
+                || (toState != Call.STATE_ACTIVE && toState != Call.STATE_HOLDING)) {
+            finishCallSwitch("call removed or state changed");
+        } else if (fromState == Call.STATE_HOLDING && toState == Call.STATE_ACTIVE) {
+            finishCallSwitch("completed");
+        }
+    }
```

配套：`switchCalls()` 入口增加 `if (isCallSwitching()) return` 防重入；`onCallStateChange`/通话移除时调用 `updateCallSwitchState()` 跟踪切换完成（from 变 HOLDING 且 to 变 ACTIVE 才算 completed）；蓝牙断开/`onStop` 时 `finishCallSwitch` 兜底；`CallListener` 新增 `onCallSwitchingChanged` 默认方法，浮窗 `updateCallSwitchUI` 与三方页据此禁用切换/接听按钮（alpha 0.5）。

## 为什么能修复
切换动作从"hold + unhold 两条指令"改为"只发一条 `unholdCall(holdingCall)`"，把 hold 交给 Telecom 自动完成，消除了指令交错的竞态根源；`mSwitchFromCall/mSwitchToCall` 切换态 + 5 秒超时 + 状态回调校验（from=HOLDING 且 to=ACTIVE）确保切换期间 UI 防重入、异常路径（通话挂断/蓝牙断开/请求异常）都有确定出口，不会卡死按钮。副作用是切换期间按钮短暂禁用（视觉半透明），属合理交互约束。

## 复盘与经验
- 与系统框架（Telecom）交互前先弄清框架的隐式行为：unhold 会自动 hold 对方通话，应用再显式 hold 就是重复指令，这是"偶现自动切回"的根源——调用框架 API 前查阅其副作用，能少走很多弯路。
- 跨状态机的异步操作（通话切换）要有显式"进行中"状态：入口防重入、状态回调判完成、超时兜底、异常路径清理，四件套缺一不可。
- 切换/合并这类关键操作，UI 层同步禁用入口（本例 `onCallSwitchingChanged` 联动浮窗与全屏页）是防止用户连点放大的必要手段。
