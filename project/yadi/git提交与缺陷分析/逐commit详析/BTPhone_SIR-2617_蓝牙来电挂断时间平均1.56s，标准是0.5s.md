# SIR-2617 · 蓝牙来电挂断后浮窗1.56s才消失（断开事件绕过冗长UI链路）

- **提交**：`21e857c1` | 2026-07-22 | dufan | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙来电挂断后，通话浮窗平均 1.56 秒才消失，产品标准是 0.5 秒内。

## 根因分析
挂断时浮窗消失走的是"通话状态机"通用链路：`FloatCallWindowPresenter` 收到状态变化后 `mHandler.postDelayed(..., 0)` 再 `initLayoutInternal(...)`，中间还要经过 `updateOngoingCallView()`——而该方法没有过滤 `Call.STATE_DISCONNECTED`，挂断后仍在对浮窗做"通话中"视图更新，随后才轮到 `showEmptyUI()` 清理；`showEmptyUI()` 内部又是先清理 HUD 配套视图、子视图、引用，最后才 `setVisibility(GONE)`。多段延迟叠加（回调排队 + 无效更新 + 清理顺序靠后）造成 1.56s 的视觉延迟。提交 [why]"回调后处理消失逻辑过迟"与 diff 吻合。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java；application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java；application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
```diff
--- a/.../telecom/InCallServiceImpl.java
+    public static DisconnectCallback SDisconnectCallback;
+    public interface DisconnectCallback {
+        void onDisconnect();
+    }
@@ 断开处理处
                 if (UiCallManager.get() != null) { ... }
+                SDisconnectCallback.onDisconnect();
--- a/.../floatview/FloatCallWindowPresenter.java
+        InCallServiceImpl.SDisconnectCallback = () -> {
+            LogUtils.d(TAG, "SDisconnectCallback onDisconnect()");
+            if (getView() == null) return;
+            getView().showEmptyUI();
+        };
@@ updateOngoingCallView
-        if (getView() != null && primaryCall != null) {
+        if (getView() != null && primaryCall != null && primaryCall.getState() != Call.STATE_DISCONNECTED) {
@@ 状态变化处理
-        mHandler.postDelayed(() -> {
-            initLayoutInternal(currentStateCode, lastStateCode);
-        }, 0);
+        initLayoutInternal(currentStateCode, lastStateCode);
--- a/.../floatview/FloatCallWindow.java
     public void showEmptyUI() {
+        setVisibility(View.GONE);          // 提前到第一步
+        removeAllViewsInLayout();
         ...
-        setVisibility(View.GONE);          //（原来在清理链最后）
-        removeAllViewsInLayout();
+        mFloatWindowManager.removeHudCompanionView();  // 移到末尾
```

## 为什么能修复
新增 `SDisconnectCallback` 静态回调，挂断事件在 `InCallServiceImpl` 处理断开的现场**直接**驱动 `showEmptyUI()`，绕过状态机排队与无效的"通话中"刷新；`updateOngoingCallView` 过滤 DISCONNECTED 状态后不再做无谓更新；`showEmptyUI` 把"不可见"提到第一步、HUD 移除放最后，用户立刻看不到浮窗，后续清理在后台完成。`postDelayed(...,0)` 一并移除。风险点：静态回调持有 Presenter lambda，若不置空可能引用泄漏，且依赖 `InCallServiceImpl` 与 Presenter 生命周期顺序。

## 复盘与经验
- 性能/时延类指标（如"0.5s 内消失"）要测端到端链路：通用状态机链路上的每一跳（postDelayed、无关刷新、清理顺序）都在吃预算。
- 终态事件（挂断/断开）值得一条直达快路径，不要与中间态共用同一条重处理链路。
- 让 View "先不可见、再清理"是零成本的感知优化；把耗时收尾工作排到用户不可见之后。
