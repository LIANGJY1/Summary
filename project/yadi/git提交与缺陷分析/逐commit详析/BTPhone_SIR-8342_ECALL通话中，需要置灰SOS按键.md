# SIR-8342 · ECALL 通话中置灰 SOS 按键（新需求实现）
- **提交**：`098926ca` | 2026-09-14 | caohongliang | BTPhone | 新需求实现（缺陷库 rc/sol 均为"新需求/实现新需求"）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
ECALL（紧急呼叫）通话进行中，拨号盘的 SOS 按键仍可点击、外观正常，需求要求通话期间置灰并禁用，挂断后恢复。

## 根因分析（实现说明）
原 `application/BTPhone/src/main/java/com/yadea/btphone/view/DialSosButton.java` 是个纯交互自定义 View（缩放动画 + 文案），完全没有感知 ECALL 状态的通路。实现采用"系统属性初值 + 广播驱动增量"模式：ECALL 应用通过 `com.adayo.app.ecall.action.STATE_CHANGED` 广播（extra `active`）通知状态，`persist.ecall.active` 系统属性作为兜底/初值来源；View 在 `onAttachedToWindow` 先按属性同步一次状态再注册广播，`onDetachedFromWindow` 反注册，`updateECallState` 里 `setEnabled(!active)` + `setAlpha(active ? 0.3f : 1.0f)` 完成禁用置灰。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/view/DialSosButton.java（新增 53 行）
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/view/DialSosButton.java
+++ b/application/BTPhone/src/main/java/com/yadea/btphone/view/DialSosButton.java
@@ (新增常量)
+    private static final String ACTION_ECALL_STATE_CHANGED = "com.adayo.app.ecall.action.STATE_CHANGED";
+    private static final String EXTRA_ECALL_ACTIVE = "active";
+    private static final String PROPERTY_ECALL_ACTIVE = "persist.ecall.active";
+    private static final float DISABLED_ALPHA = 0.3f;
@@ (新增广播接收)
+    private final BroadcastReceiver eCallStateReceiver = new BroadcastReceiver() {
+        @Override
+        public void onReceive(Context context, Intent intent) {
+            boolean active = intent.getBooleanExtra(EXTRA_ECALL_ACTIVE,
+                    SystemProperties.getBoolean(PROPERTY_ECALL_ACTIVE, false));
+            updateECallState(active);
+        }
+    };
@@ (生命周期绑定)
+    protected void onAttachedToWindow() {
+        super.onAttachedToWindow();
+        updateECallState(SystemProperties.getBoolean(PROPERTY_ECALL_ACTIVE, false));
+        if (!receiverRegistered) {
+            ContextCompat.registerReceiver(getContext(), eCallStateReceiver,
+                    new IntentFilter(ACTION_ECALL_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
+            receiverRegistered = true;
@@ (状态应用)
+    private void updateECallState(boolean active) {
+        clearAnimation();
+        setEnabled(!active);
+        setAlpha(active ? DISABLED_ALPHA : 1.0f);
+    }
```

## 为什么能修复（实现评价）
`setEnabled(false)` 从事件层拦住点击，`setAlpha(0.3)` 提供视觉置灰，双通道保证"不可点且看起来不可点"；`clearAnimation()` 避免置灰时残留按压缩放动画。附着时先用持久化属性回显，解决"ECALL 中页面重建后按钮短暂可点"的窗口。隐患：`RECEIVER_EXPORTED` 声明接受外部广播，依赖发送方为可信系统应用；若 ECALL 广播未发出（仅属性变化）需等下次附着才能刷新。

## 复盘与经验
- 按钮"置灰"要同时做 `setEnabled(false)`（拦截事件）与视觉降透明度（告知用户），只改其一都会被测试提单。
- 状态回显用持久化系统属性（persist.*）做初值、广播做增量，是车载多进程状态同步的常用轻量方案。
- View 级广播注册/反注册挂在 `onAttachedToWindow`/`onDetachedFromWindow` 并用 `receiverRegistered` 防重复，避免泄漏与 IllegalArgumentException。
