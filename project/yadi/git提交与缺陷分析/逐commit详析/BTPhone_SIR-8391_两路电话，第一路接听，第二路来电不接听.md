# SIR-8391 · 两路电话时第一路的静音/切音频按钮无反馈
- **提交**：`24d358f2` | 2026-09-15 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
两路电话场景：第一路已接听，第二路来电不接听时，点击第一路通话的"切换手机端接听（音频通道）"和"静音"按钮，页面无任何反馈、操作不生效。

## 根因分析
双通话悬浮窗 `application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java` 的静音按钮处理里写的是 `UiCall uiCall = uiCallManager.getPrimaryCall()`。Telecom 语义下**第二路来电（RINGING）会升级成为"主通话"**，`getPrimaryCall()` 返回的是第二路而不是正在 ACTIVE 通话的第一路；随后 `if (uiCall != null && uiCall.getState() == Call.STATE_ACTIVE)` 守卫不成立，静音逻辑整体被跳过且无提示——"使用了错误的通话对象"。同时该双通话布局路径里只绑定了 `btnMute`，`btn_first_speaker`（音频通道切换按钮）根本没初始化、没有点击监听，点它自然也无反馈。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
+++ b/application/BTPhone/floatview/FloatCallWindow.java（实际路径 application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java）
@@ -182,7 +182,8 @@
                 LogUtils.i(TAG, " Mute button clicked");
 
                 UiCallManager uiCallManager = UiCallManager.get();
-                UiCall uiCall = uiCallManager.getPrimaryCall();
+                // 第二路来电会成为主通话，静音操作仍应作用于正在通话的一路。
+                UiCall uiCall = uiCallManager.getCallWithState(Call.STATE_ACTIVE);
 
                 // 只有在通话激活状态才能切换静音
                 if (uiCall != null && uiCall.getState() == Call.STATE_ACTIVE) {
@@ -786,12 +787,17 @@
         SmartEllipsizeTextView tvFirstNum = view.findViewById(R.id.tv_first_num);
         ImageView ivFirstHangUp = view.findViewById(R.id.iv_first_hangup);
+        btnSpeaker = view.findViewById(R.id.btn_first_speaker);
         btnMute = view.findViewById(R.id.btnMute);
@@ +        btnSpeaker.setOnClickListener(v -> {
+            LogUtils.i(TAG, "Audio route switch button clicked during second incoming call");
+            switchAudioRouteMode();
+        });
         btnMute.setOnClickListener(this);
```

## 为什么能修复
静音操作改为按**状态**取对象：`getCallWithState(Call.STATE_ACTIVE)` 精确命中处于通话中的第一路，不受"来电成为主通话"的语义影响，守卫条件成立、静音生效；`btnSpeaker` 在双通话布局里补上 `findViewById` 绑定与 `switchAudioRouteMode()` 监听，音频通道切换恢复反馈。两处都是把"按位置（primary）取对象"改为"按语义（active/具体按钮）取对象"。隐患：若极端场景出现两条 ACTIVE 通话，`getCallWithState` 返回哪一条取决于管理器实现，需确认其遍历顺序；未接听的第二路本就不应响应静音，行为符合预期。

## 复盘与经验
- "主通话"是 Telecom 的动态概念，多路通话下 UI 按钮"看起来属于哪一路"与 `getPrimaryCall()` 返回值会错位，操作类逻辑应按目标通话的**状态**定位对象，而非按 primary 位置。
- 双通话布局是单通话布局的变体分支，控件绑定清单要逐一核对——本例 `btnSpeaker` 在该分支漏绑，属于分支覆盖不全。
- "点击无反馈"类问题先分两类：没走到逻辑（对象取错/守卫拦截）vs 走了逻辑但无 UI 响应（未绑定监听），日志埋点可快速二分。
