# SIR-7136/SIR-7140 · 蓝牙电话 HUD 投屏显示绿色挂断按钮
- **提交**：`fd7dc649` | 2026-09-08 | caohongliang | SystemUI（BTPhone 模块代码） | bugfix
- **缺陷库**：SIR-7136 C·必现·HUD（UI变更）；SIR-7140 B·必现·HUD（UI变更）

## 问题
蓝牙电话呼叫并 HUD 投屏时，HUD 上的按钮显示为绿色"挂断"样式，与 UI 稿要求（来电=绿色接听/去电=红色挂断等状态映射）不符。

## 根因分析
HUD 投屏视图由 `FloatCallWindow`（`FloatWindowManager` 管理 Display 4 的 HUD 窗口）渲染。原实现中 HUD 主通话取 `uiCallManager.getPrimaryCall()`，按钮图标/背景按主通话状态设置时缺乏统一的来电/去电判定与双路通话优先级选择：来电路径判断用 `getPrimaryCall()` 判空后默认 outgoing， ringing 呼叫可能不被识别为来电，导致按钮走了挂断（reject，绿色样式）分支；同时双路通话（如通话中再来电）场景没有按状态选主/副通话的逻辑。缺陷库根因登记为"UI 变更"——即 UI 稿对 HUD 电话按钮的状态样式做了新定义，原实现未按新定义区分。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java；application/BTPhone/src/main/java/com/yadea/btphone/floatview/HudCallStateResolver.java（新增）；application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatWindowManager.java；values/values-zh/values-en/strings.xml（新增 waiting_for_answer 等文案）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
+    private void updateHudButton(boolean incoming) {
+        hangupBtn2.setImageResource(incoming
+                ? R.drawable.icon_float_answer
+                : R.drawable.icon_float_reject);
+        hangupBtn2.setBackgroundResource(incoming
+                ? R.drawable.bg_answer_button_selector
+                : R.drawable.bg_reject_button_selector);
+    }
+
+    private UiCall selectHudCall(List<UiCall> calls, boolean secondary, UiCall excludedCall) {
+            int priority = secondary
+                    ? HudCallStateResolver.secondaryPriority(call.getState())
+                    : HudCallStateResolver.primaryPriority(call.getState());
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/HudCallStateResolver.java（新增）
+    static int primaryPriority(int state) {
+        switch (state) {
+            case Call.STATE_ACTIVE: return 5;
+            case Call.STATE_DIALING:
+            case Call.STATE_CONNECTING:
+            case Call.STATE_SELECT_PHONE_ACCOUNT:
+            case Call.STATE_NEW: return 4;
+            case Call.STATE_RINGING: return 3;
+            case Call.STATE_HOLDING: return 2;
+            default: return 0;
+        }
+    }
```
来电判定改为 `UiCallManager.get().getCallWithState(Call.STATE_RINGING) != null`；主/副通话分别经 `selectHudCall`+`HudCallStateResolver` 优先级选取；新增 `updateHudCallEnded()` 处理结束态；`FloatWindowManager.isDualDisplayAvailable()` 简化为 `isHudDisplayAvailable()`（只看 `mHudWindowManager != null`），删除 `getCurrentDisplayId()`。

## 为什么能修复
按钮图标+背景在 `updateHudButton(incoming)` 中成对切换，来电用 answer（绿色接听）、其余用 reject，样式与 UI 稿一一对应；主通话选取按状态优先级（ACTIVE>拨号中>振铃>保持），双路通话时 HUD 显示正确的主体通话、副通话进 tvTips 提示（含 waiting_for_answer 文案），消除"振铃被当出去电/挂断"的误判路径。副作用：状态优先级表与 Telecom 状态机耦合，新增 Call 状态需同步；`updateHudCallEnded` 依赖 `mHudOutgoingCall/mHudCallConnected` 跟踪，时序异常时退回默认按钮样式。

## 复盘与经验
- 涉及"按钮颜色=语义"的 UI（绿接听/红挂断），状态→样式的映射要抽成单一函数（如 updateHudButton）成对设置 icon+background，禁止散落赋值。
- 双路通话 UI 必须先定义"谁是主通话"的优先级规则，`getPrimaryCall()` 的系统语义与产品语义未必一致。
- 来电判定应以"存在 STATE_RINGING 呼叫"为准，而不是 primaryCall 兜底猜测（原代码 null 时 default to outgoing 正是误判来源）。
