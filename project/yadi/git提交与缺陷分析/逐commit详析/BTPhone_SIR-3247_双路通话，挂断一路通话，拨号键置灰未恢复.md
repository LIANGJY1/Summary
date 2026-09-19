# SIR-3247 · 双路通话挂断一路后拨号键置灰未恢复

- **提交**：`a59baf7d` | 2026-07-27 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
双路通话挂断其中一路后，拨号键保持置灰，无法再次用拨号盘发起新电话启动双路通话。

## 根因分析
车机播出号码时 Telecom 会做"通话对象替换"：旧通话回调 DISCONNECTED 移除、新通话以异常状态序列（STATE_DIALING=7 等）出现。旧代码三处被这一时序打穿：① `InCallServiceImpl.updateDialPadButtonState()` 用 `!isShouldDisable(1)` 按"通话总数≥1"置灰拨号键，通话对象替换期间残留的旧通话让计数恒≥1，置灰后永不恢复；② `InCallUiStateMachine` 各 OutGoing 态在 `handleCallRemoved` 里立即 `transitionTo(idleState)`，替换瞬间误入 idle 导致浮窗异常消失；③ `FloatCallWindowPresenter.onCallStateChanged()` 依赖 `getPrimaryCall()`，通话带 parent 时返回 null，浮窗随之清空。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java、application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java、application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/InCallUiStateMachine.java、application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java
-            LogUtils.i(TAG, "updateDialPadButtonState: Total calls=" + callCount +", Disable dial button=" + isShouldDisable(1));
+            LogUtils.i(TAG, "updateDialPadButtonState: Total calls=" + callCount +", Disable dial button=" + isShouldDisableCallBtn(2));
             // 通过 RxBus 发送事件通知 MainActivity 更新拨号盘状态
             RxBus.getInstance().post(
-                    new RxEventMsg<Boolean>(Constants.EventCode.DIAL_PAD_ENABLE_STATE_CHANGED, !isShouldDisable(1))
+                    new RxEventMsg<Boolean>(Constants.EventCode.DIAL_PAD_ENABLE_STATE_CHANGED, !isShouldDisableCallBtn(2))
             );
```
```diff
// InCallServiceImpl.java 新增统计方法（只统计真实活跃通话）
+    private boolean isShouldDisableCallBtn(int count) {
+        int activeCallCount = 0;
+        List<UiCall> calls = UiCallManager.get().getCalls();
+        for (UiCall call : calls) {
+            if (call != null) {
+                int state = call.getState();
+                if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING
+                || state == Call.STATE_DIALING || state == Call.STATE_RINGING) {
+                    activeCallCount++;
+                }
+            }
+        }
+        boolean ShouldDisableCallBtn = activeCallCount >= count;
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/InCallUiStateMachine.java（OutGoingMinState 等 4 个去电状态同构修改）
         protected boolean handleCallRemoved(UiCall call) {
-            transitionTo(idleState);
+            LogUtils.d(TAG, "OutGoingMinState handleCallRemoved: delay idle transition");
+            sendMessageDelayed(obtainMessage(MSG_CALL_REMOVED_DELAYED, call), UiCallManager.OUTGOING_CALL_REPLACEMENT_GRACE_MS);
+            return HANDLED;
+        }
+        @Override
+        protected boolean handleDelayedCallRemoved(UiCall call) {
+            if (UiCallManager.get().getCalls().isEmpty()) {
+                transitionTo(idleState);
+            } else {
+                LogUtils.d(TAG, "OutGoingMinState handleDelayedCallRemoved: calls still exist, skip idle");
+            }
+            return HANDLED;
+        }
```
配套改动：`UiCallManager` 新增 `OUTGOING_CALL_REPLACEMENT_GRACE_MS = 1000` 与 `mResetCallInitiatedByCarRunnable`，最后一通未接通的车机去电被移除时延迟 1s 再复位 `SCallInitiatedByCar`；`FloatCallWindowPresenter` 在 `getPrimaryCall()` 为 null 时回退取 `calls.get(0)`；删除立即清屏的 `InCallServiceImpl.SDisconnectCallback` 机制。

## 为什么能修复
拨号键判断从"通话总数≥1"改为"活跃态（ACTIVE/HOLDING/DIALING/RINGING）计数≥2"，残留的 DISCONNECTED 通话对象不再把按钮锁死，挂断一路后能正常恢复并可再次拨号；去电状态机的 idle 迁移延迟 1 秒等替换完成，超时后仍校验 `getCalls().isEmpty()` 才进 idle，消除了替换瞬间的浮窗闪退。延迟窗口是折中方案，若替换耗时超过 1s 仍可能闪一下，属于已知残留风险。

## 复盘与经验
- Telecom 通话对象会被"移除+新建"替换，任何按通话对象计数/即时迁移状态机的设计都要考虑替换竞态，必要时用宽限期+二次确认。
- 判断 UI 可用性（如拨号键）应基于语义状态（活跃通话数），而不是容器 size——列表里会残留断开态对象。
- `getPrimaryCall()` 这类带隐含条件（hasParent）的查询方法，调用方要做 null 回退，不能假设非空。
