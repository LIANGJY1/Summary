# SIR-8071 · 第一路通话接通时拨第二路，来电浮窗显示异常

- **提交**：`eef8df1e` | 2026-09-10 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 蓝牙电话（提交头标影响等级 D，以缺陷库 B 为准）

## 问题
第一路通话已接通（active）时拨打第二路电话，对不支持通话保持（call hold）的手机，telecom 会上报"1 路 active + 第二路 dialing"的异常状态组合，车机来电/呼叫浮窗显示异常。

## 根因分析
`FloatCallWindowPresenter.handleOutgoingMinState(previousCallState, isFromCar)` 处理第二路呼出的最小化状态：先尝试 `showThreeWayCallUIIfNeeded(UiCallManager.get().getCalls())`，不满足三方通话条件（只有 2 路）就继续往下走，最终 `getView().showOutgoingUI()` 直接弹呼叫中界面。"1 路 active + 1 路 dialing"既不是合法三方通话、也不是干净的单一呼出（缺陷库记：telecom 返回该组合属异常），旧逻辑没有对这种中间态做拦截，把第一路已接通的通话界面顶掉、误显示为"呼叫中"，造成显示异常。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java（1 文件 +11/-1）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
@@ handleOutgoingMinState()
-        if (showThreeWayCallUIIfNeeded(UiCallManager.get().getCalls())) {
+        UiCallManager uiCallManager = UiCallManager.get();
+        List<UiCall> calls = uiCallManager.getCalls();
+        if (showThreeWayCallUIIfNeeded(calls)) {
             LogUtils.d(TAG,"should show three way call ui");
             return;
         }
+
+        // 第二路刚发起但第一路尚未保持时，继续显示第一路通话界面，避免状态误显示为"呼叫中"。
+        if (calls.size() >= 2
+                && uiCallManager.getCallWithState(Call.STATE_ACTIVE) != null
+                && uiCallManager.getCallWithState(Call.STATE_CONNECTING, Call.STATE_DIALING) != null) {
+            LogUtils.i(TAG, "Keep ongoing UI while waiting for the active call to be held");
+            return;
+        }
```

## 为什么能修复
新增守卫分支精确命中异常组合（多路并存 + 存在 ACTIVE + 存在 CONNECTING/DIALING），此时保持当前通话 UI 不动，等第一路被保持（held）后状态机进入正常三方/切换流程再由后续回调刷新界面，"误显示呼叫中"的路径被屏蔽。改动只影响 OUT_GOING_MIN 状态的一个分支，正常单路呼出（calls.size()==1）不受影响；隐患：若某些手机永远不发 held 状态，第二路会一直停留在"看不到呼叫中界面"的状态，需要靠三方通话 UI 或后续状态流转兜住，测试要覆盖支持/不支持 hold 两类手机。

## 复盘与经验
- 多路通话是状态机密集区：telecom 可能上报 active+active、active+dialing、active+held、dialing+alerting 等组合，UI 层必须对"非法/中间组合"显式表态（保持现状或收敛），不能默认落在通用分支。
- "屏蔽异常场景"是车机对外围设备（手机能力差异）不一致性的常见工程策略，但屏蔽点要留日志（本例 LogUtils.i）并明确等待条件，否则变成不可诊断的黑洞。
- 修复前先固定"合法状态枚举表"（哪些组合、各自显示什么 UI），异常组合按表归类，能显著减少此类来回修。
