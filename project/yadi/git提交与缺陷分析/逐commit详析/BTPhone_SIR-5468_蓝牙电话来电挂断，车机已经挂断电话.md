# SIR-5468 · 车机来电挂断后手机实际未挂断

- **提交**：`fdbcaf06` | 2026-08-03 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
来电时在车机上挂断/拒接，车机界面已结束，但手机实际仍在响铃、通话未被拒接。

## 根因分析
`UiCallManager.rejectCall()/disconnectCall()` 在向 Telecom 发出请求后立即调用 `doRemoveUiCall(uiCall)`，且该方法在 `mCallMapping.size() == 1` 时就把通话对象从映射中删除。这属于"乐观移除"：Telecom 侧后续确认流程（onCallRemoved、状态回调）再也找不到对应 CallHolder，应用侧状态机与 Telecom 真实状态脱节——三方通话后残留的三方状态（`OngoingMin3PartState` 等）无法退出，再次来电时 `FloatCallWindowPresenter.declineCall()` 拿到的 `mPrimaryCall` 为空/错位，拒接指令没能正确下发到 Telecom，手机端继续响铃（状态机异常导致接口调用错误）。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java、application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/InCallUiStateMachine.java、application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
+    private final Set<UiCall> mCallsPendingTermination = new HashSet<>();
...
         CallHolder telecomCall = mCallMapping.get(uiCall);
         if (telecomCall != null && mPhoneForward != null) {
+            if (!mCallsPendingTermination.add(uiCall)) {
+                LogUtils.d(TAG, "rejectCall ignored: call termination already pending");
+                return;
+            }
             mPhoneForward.rejectCall(telecomCall, rejectWithMessage, textMessage);
-            doRemoveUiCall(uiCall);
         }
...
-    public void doRemoveUiCall(UiCall uiCall) {
-        if (mCallMapping.size() == 1) {
-            mCallMapping.remove(uiCall);
-        }
-    }
+    public boolean isCallPendingTermination(UiCall uiCall) {
+        return uiCall != null && mCallsPendingTermination.contains(uiCall);
+    }
```
```diff
// UiCallManager.java 真实移除只发生在 Telecom 确认回调 doTelecomCallRemoved 中
             mCallMapping.remove(uiCall);
+            mCallsPendingTermination.remove(uiCall);
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/InCallUiStateMachine.java 三方残留状态自愈 + 空指针防护
             LogUtils.e(TAG, "OngoingMin3PartState handleCallAdded()");
+            if (call.getState() == Call.STATE_RINGING && UiCallManager.get().getCalls().size() == 1) {
+                LogUtils.w(TAG, "Recover stale three-party state for a single incoming call");
+                transitionTo(incomingState);
+                return HANDLED;
+            }
...
-            if (primaryCall.getState() == Call.STATE_RINGING && is4Part) {
+            if (primaryCall == null) {
+                transitionTo(idleState);
+            } else if (primaryCall.getState() == Call.STATE_RINGING && is4Part) {
```
`FloatCallWindowPresenter`：拒接/挂断后仅当 `getCalls().size() == 1 && isCallPendingTermination(...)` 才 `showEmptyUI()` 乐观隐藏；`onCallStateChanged` 对 pendingTermination 的通话直接 return 忽略刷新；删除"强制触发一次 UI 更新"的补丁调用。

## 为什么能修复
通话对象的生命周期归还给 Telecom：应用侧只在收到 onCallRemoved 确认后才真正移除，`mCallMapping` 全程可查，拒接指令经 `mPhoneForward.rejectCall(telecomCall...)` 必然落到正确 CallHolder，手机端真正被拒接；UI 层用 `mCallsPendingTermination` 做"乐观隐藏 + 忽略中间态刷新"，体验上与原行为一致。状态机侧补的三方残留自愈和 `primaryCall == null → idle` 防护，兜底历史脏状态。副作用：若 Telecom 确认事件丢失，通话会滞留在 pending 集合（浮窗保持隐藏），蓝牙断开路径已补 `mCallsPendingTermination.clear()` 兜底。

## 复盘与经验
- "乐观更新"必须区分 UI 层与数据层：UI 可乐观隐藏，数据/状态机必须等真实回调确认，否则与系统服务（Telecom）脱节后连指令都发不出去。
- 挂断后手机还在响、而车机 UI 已结束，典型的"应用侧提前清理 + 系统侧未确认"组合，排查方向是通话对象映射与 onCallRemoved 时序。
- 状态机要对历史脏状态具备自愈能力（如三方态收到单路 RINGING 新来电时回 incoming），并对空对象做迁移到 idle 的兜底。
