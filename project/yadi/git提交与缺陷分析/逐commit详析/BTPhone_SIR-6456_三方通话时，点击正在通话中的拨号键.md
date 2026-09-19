# SIR-6456 · 三方通话中点击拨号键，正在通话浮窗消失

- **提交**：`e300de46` | 2026-08-26 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
三方通话时（一路保持 + 一路新建呼出），点击正在通话中的拨号键后，本应显示的三方通话浮窗消失。

## 根因分析
`FloatCallWindowPresenter` 的浮窗状态机按"最新一次 UI 事件"驱动：点击拨号键会触发新的呼出流程，进入 `handleOutgoingMinState` / `handleOngoingMinState`（呼出/通话的最小化状态处理），这两个分支直接按单路通话展示 `showOutgoingUI()` 或单路浮窗，完全没有检查当前是否已存在多路通话——三方场景下新呼出事件把已保持那一路的浮窗覆盖/顶掉，浮窗"消失"。而原有的多路判断 `showThreeWayCallUI(hasHoldCall, hasActiveCall, hasDialingCall)` 只在通话列表刷新的一条路径里被调用，且判断条件用 `STATE_DIALING` 硬编码、不含 `STATE_CONNECTING`（telecom 在呼出建立初期的常见状态），呼出过渡期判断落空后仍走单路 UI。状态机各入口对"多路"的感知不一致，是低概率复现的根源（取决于状态迁移时序）。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java`

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
@@ -344,6 +344,11 @@ public class FloatCallWindowPresenter extends BasePresenter<IFloatWindowView>
     private void handleOutgoingMinState(int previousCallState, boolean isFromCar) {
         LogUtils.d(TAG, "STATE_OUT_GOING_MIN, previousState=" + previousCallState);
 
+        if (showThreeWayCallUIIfNeeded(UiCallManager.get().getCalls())) {
+            LogUtils.d(TAG,"should show three way call ui");
+            return;
+        }
+
         if (isFromCar) {
@@ -380,6 +385,12 @@ public class FloatCallWindowPresenter extends BasePresenter<IFloatWindowView>
     private void handleOngoingMinState(boolean isFromCar) {
         LogUtils.d(TAG, "STATE_ONGOING_MIN");
+
+        if (showThreeWayCallUIIfNeeded(UiCallManager.get().getCalls())) {
+            LogUtils.d(TAG,"should show three way call ui");
+            return;
+        }
```

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
@@ -712,12 +706,36 @@ public class FloatCallWindowPresenter extends BasePresenter<IFloatWindowView>
-    private void showThreeWayCallUI(boolean hasHoldCall, boolean hasActiveCall, boolean hasDialingCall) {
+    private boolean showThreeWayCallUIIfNeeded(List<UiCall> calls) {
+        if (getView() == null || calls.size() < 2) {
+            return false;
+        }
+        boolean hasHoldCall = false;
+        boolean hasActiveCall = false;
+        boolean hasOutgoingCall = false;
+        for (UiCall call : calls) {
+            int state = call.getState();
+            hasHoldCall |= state == Call.STATE_HOLDING;
+            hasActiveCall |= state == Call.STATE_ACTIVE;
+            hasOutgoingCall |= state == Call.STATE_CONNECTING || state == Call.STATE_DIALING;
+        }
+        if (hasHoldCall && hasActiveCall) {
+            getView().showThreewayHoldingUI();
+            return true;
+        } else if (hasHoldCall && hasOutgoingCall) {
+            getView().showThreewayOutgoingUI();
+            return true;
+        }
+        return false;
+    }
```

（另有 `updateOngoingCallView` 路径收敛为调用 `showThreeWayCallUIIfNeeded(calls)`，并在单路状态更新处补了"CONNECTING/DIALING 时重查多路"的判断。）

## 为什么能修复
修复把三方判断收敛成一个**带返回值的统一入口** `showThreeWayCallUIIfNeeded(calls)`：任何单路 UI 分支（呼出最小化、通话最小化、单路刷新）在展示前先查全量通话列表，一旦存在"HOLDING+ACTIVE"或"HOLDING+呼出中"组合就强制切回三方浮窗并短路后续单路逻辑，状态机的各入口对多路的判定不再有缺口。呼出判定从单一 `STATE_DIALING` 扩为 `STATE_CONNECTING || STATE_DIALING`，覆盖呼出建立初期的过渡状态，低概率窗口被关闭。副作用：状态迁移瞬间可能连续重设三方 UI（幂等），开销可忽略；若以后出现四路以上通话，`size >= 2` 的组合判定需再扩展。

## 复盘与经验
- 多路通话/多状态 UI 的状态机，"每个展示入口都必须先做全局状态裁决"——把判断收敛为返回 boolean 的单一函数并在所有入口前置调用，是消除时序性低概率 bug 的标准手法。
- telecom 的呼出有 `STATE_CONNECTING` 与 `STATE_DIALING` 两个前置态，只认其一必漏时序窗口；写通话状态判断时要把"连接中"家族枚举齐全。
- "点击某个按钮后浮窗消失"这类表象，要沿状态机找是谁切换了 UI 状态，而不是在点击处理里打转——点击只是触发了既有的状态机缺口。
