# SIR-3543 · 单路通话挂起浮窗按钮与UI不一致未显示"保留中"

- **提交**：`f6361dc2` | 2026-07-27 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
单路通话被挂起（保持）后，通话浮窗上没有"保留中"状态提示，也没有恢复通话的按钮，按钮排布与新版 UI 设计不一致。

## 根因分析
浮窗旧实现 `FloatCallWindow` 只有 `ivCallOut` 等控件，状态文案 `tvStatus` 仅处理 `STATE_DIALING`（显示"正在呼叫"），完全没有 `STATE_HOLDING` 分支；布局 `float_calling_window.xml` 中也没有"恢复通话"按钮。单路通话进入保持态后，浮窗维持通话中样式，与 UI 稿要求的"保留中 + 恢复按钮"不一致（缺陷库：UI 变更，需适配最新 UI）。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java、application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java、application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java、application/BTPhone/src/main/res/layout/float_calling_window.xml
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
-        if (tvStatus != null && primaryCall.getState() == Call.STATE_DIALING) {
-            tvStatus.setText(R.string.now_calling);
+        if (tvStatus != null) {
+            if (primaryCall.getState() == Call.STATE_DIALING) {
+                tvStatus.setText(R.string.now_calling);
+            } else if (primaryCall.getState() == Call.STATE_HOLDING) {
+                tvStatus.setText(R.string.hold);
+            }
+        }
+        if (ivUnhold != null) {
+            if (primaryCall.getState() == Call.STATE_HOLDING) {
+                ivUnhold.setVisibility(VISIBLE);
+            } else {
+                ivUnhold.setVisibility(GONE);
+            }
         }
```
```diff
// FloatCallWindow.java 保持态下隐藏互斥按钮（mute/dialpad/speaker 同构处理）
         if (btnMute != null) {
             if (primaryCall.getState() == Call.STATE_HOLDING) {
                 btnMute.setVisibility(GONE);
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java 新增恢复通话
+    public void unholdCall() {
+        LogUtils.d(TAG, "unholdCall");
+        UiCallManager uiCallManager = UiCallManager.get();
+        UiCallManager.get().unholdCall(uiCallManager.getPrimaryCall());
+    }
```
配套：`FloatCallWindow` 中 `ivCallOut` 更名为 `ivUnhold` 并在 `onClick` 增加 `R.id.iv_unhold -> mPresenter.unholdCall()`；布局把原 `iv_hangup` 位置插入 `iv_unhold`、按钮链 `iv_hangup → iv_dialpad → btnSpeaker → btnMute` 重排；`UiCallManager` 大段为方法缩进重构（搬入内部类）与 `unRegisterCallHolderCallback` 时机调整，非本次 UI 缺陷核心。

## 为什么能修复
状态文案增加 `STATE_HOLDING → R.string.hold` 分支后，保持态会正确显示"保留中"；`ivUnhold` 恢复按钮在保持态显示、其余态隐藏，同时隐藏静音/拨号盘/免提等与保持态互斥的按钮，浮窗样式与最新 UI 稿一致且功能闭环（可一键恢复）。风险点：`onClick` 的 `R.id.iv_unhold` 分支无 break（落到 default），当前无副作用但属于可清理的代码瑕疵。

## 复盘与经验
- UI 适配类缺陷的通用修复套路：状态 → 文案映射表补全分支 + 新按钮的"显示/隐藏 + 点击"三件套，缺一不可。
- 浮窗/弹窗上的按钮往往存在互斥关系（保持 vs 静音/拨号盘），改 UI 时要同步梳理各通话状态下每个控件的可见性矩阵。
- 大 diff 提交要先剥离纯缩进/搬移 hunk 再分析，避免把重构当功能改动。
