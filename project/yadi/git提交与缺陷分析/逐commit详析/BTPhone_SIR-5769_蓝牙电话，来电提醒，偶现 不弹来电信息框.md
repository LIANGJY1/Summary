# SIR-5769 · 蓝牙来电偶现不弹来电框且应用崩溃
- **提交**：`ac0b03fb` | 2026-08-11 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 蓝牙电话

## 问题
来电提醒时偶现不弹来电信息框，且蓝牙电话应用崩溃。压测下高概率复现。

## 根因分析
缺陷库根因：telecom 产生 `STATE_SELECT_PHONE_ACCOUNT` 占位通话，导致蓝牙电话状态机误判。代码层面有三处连锁问题：
1. `UiCallManager.doTelecomCallAdded()` 中占位通话（`STATE_SELECT_PHONE_ACCOUNT`）被放入 `mCallMapping` 后无人清理，与后续真实外呼（`STATE_CONNECTING/STATE_DIALING`）并存；
2. `InCallUiStateMachine` 处理 `Call.STATE_RINGING` 时用 `getSecondaryCall()` 判断"三方来电"，占位通话会被误判为第二条通话，走错三方来电分支；
3. 进入三方分支后 `FloatCallWindowPresenter` 的 `STATE_INCOMING_MIN_3PART` 与 `FloatCallWindow.inflateThreeWayIncomingView()` 直接假定"活跃通话+振铃通话"都存在，`getCallWithState(Call.STATE_ACTIVE)` 返回 null 时后续直接解引用崩溃（同时已 `setVisibility(GONE)+removeAllViewsInLayout()` 导致来电框不显示）。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`、`floatview/FloatCallWindowPresenter.java`、`telecom/telecom/InCallUiStateMachine.java`、`telecom/telecom/UiCallManager.java`
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
             UiCall uiCall = getOrCreateCallContainer(callHolder);
+            removeSupersededOutgoingPlaceholders(uiCall);
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
+    private void removeSupersededOutgoingPlaceholders(UiCall replacementCall) {
+        if (replacementCall == null ||
+                (replacementCall.getState() != Call.STATE_CONNECTING &&
+                        replacementCall.getState() != Call.STATE_DIALING)) {
+            return;
+        }
+        List<UiCall> placeholders = new ArrayList<>();
+        for (UiCall call : mCallMapping.keySet()) {
+            if (call != replacementCall && call.getState() == Call.STATE_SELECT_PHONE_ACCOUNT) {
+                placeholders.add(call);
+            }
+        }
+        for (UiCall placeholder : placeholders) {
+            mCallMapping.remove(placeholder);
+            mCallsPendingTermination.remove(placeholder);
+        }
+    }
+
     private void onCallStateChange(UiCall uiCall) {
+        if (uiCall == null || !mCallMapping.containsKey(uiCall)) {
+            LogUtils.w(TAG, "Ignore state callback from a removed or unknown call");
+            return;
+        }
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
     private void inflateThreeWayIncomingView() {
+        UiCallManager uiCallManager = UiCallManager.get();
+        UiCall call = uiCallManager.getCallWithState(Call.STATE_ACTIVE);
+        UiCall secondCall = uiCallManager.getCallWithState(Call.STATE_RINGING);
+        if (call == null || secondCall == null) {
+            LogUtils.e(TAG, "inflateThreeWayIncomingView aborted: activeCall=" + call +
+                    ", ringingCall=" + secondCall);
+            return;
+        }
         setVisibility(View.GONE);
         removeAllViewsInLayout();
         View view = LayoutInflater.from(getContext()).inflate(R.layout.float_three_way_incoming_window, this);
-        UiCallManager uiCallManager = UiCallManager.get();
-        UiCall call = uiCallManager.getCallWithState(Call.STATE_ACTIVE);
-        UiCall secondCall = UiCallManager.get().getCallWithState(Call.STATE_RINGING);
```
（`FloatCallWindowPresenter` 的 `STATE_INCOMING_MIN_3PART` 同样补了 null 兜底：mSecondaryCall 非空走 `showIncomingUI()`，mPrimaryCall 非空走 `showMiniFlolatUI()`，否则 `showEmptyUI()`；`InCallUiStateMachine` 将三方判定从 `getSecondaryCall()` 改为 `getCallWithState(Call.STATE_ACTIVE, Call.STATE_HOLDING)`。）

## 为什么能修复
四层防御：源头清理占位通话（新外呼建立时移除 `STATE_SELECT_PHONE_ACCOUNT` 遗留对象），状态机改用真实活跃/保持通话做三方判定，两个 UI 入口增加 null 守卫把"异常状态"降级为可用的普通来电/迷你/空界面而非崩溃。`onCallStateChange` 的 mapping 校验防止已移除占位通话的迟到期回调再次污染状态。副作用：占位通话若被系统复用为真实通话可能被提前移除，但紧接着的新 `doTelecomCallAdded` 会重新建卡，风险低。

## 复盘与经验
- 系统 telecom 的占位状态（`SELECT_PHONE_ACCOUNT`）是车载蓝牙电话常见"幽灵通话"来源，通话集合必须按状态做生命周期清理，否则状态机会被污染。
- "三方来电"判定不要依赖 `getSecondaryCall()` 这类位置语义，应按通话状态组合（ACTIVE/HOLDING + RINGING）判断。
- UI 渲染前先取数据判空、再动 View（本例原代码先 `removeAllViewsInLayout` 再取数据），顺序颠倒会把崩溃放大成黑屏/无弹窗。
- 回调入口按 `mCallMapping.containsKey` 校验身份，可屏蔽已清理对象的迟到期事件。
