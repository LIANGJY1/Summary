# SIR-7691 · 微信网络来电不应弹出蓝牙电话浮窗
- **提交**：`628fe235` | 2026-09-08 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
手机上微信（VoIP 网络电话）来电时，车机蓝牙电话弹出通话浮窗，把微信来电误当成普通蓝牙来电展示。

## 根因分析
蓝牙电话对微信通话的老屏蔽逻辑是 `TelecomUtils.isMyConnectedPhoneNumber(telecomCall, UiBluetoothMonitor.get().getConnectedPhoneNumber2())`——靠"微信通话号码 == 已连接手机的本机号码"这一特征识别（注释记载：苹果手机微信号码为本机连接号码，安卓手机微信电话号码为 10000000 或 00000000000）。但缺陷库/提交指出的场景是："微信来电没有来电号码，但是有本机号码"，旧判断在这些号码组合下不成立，`onCallAdded` 的拦截未命中，浮窗照常弹出。修复把散落在 4 个类里的"取已连接号码再比对"逻辑，统一收敛为新的 `TelecomUtils.isWechatPhoneNumber(Call)` 直接从 `Call.Details`（GatewayInfo/getHandle）提取号码判断是否微信网络电话；同时给老的取号方法补了 `gatewayInfo.getOriginalAddress() != null` 空指针防护。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java、telecom/dataexchange/TelecomForward.java、telecom/telecom/InCallUiStateMachine.java、telecom/telecom/TelecomUtils.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java
@@ (onCallAdded)
+        if (TelecomUtils.isWechatPhoneNumber(telecomCall)) {
+            LogUtils.w(TAG, "Ignore WeChat phone or voice call");
+            return;  // 直接返回，不处理微信电话
+        }
         moveAppToBackground();
         setPhoneNum(telecomCall, "onCallAdded: connectedNumber = ");
-        List<String> connectedNumber = UiBluetoothMonitor.get().getConnectedPhoneNumber2();
-        if (TelecomUtils.isMyConnectedPhoneNumber(telecomCall, connectedNumber)) {
-            LogUtils.w(TAG, "Wechat's phone or voice message. connectedNumber = ");
-            return;  // 直接返回，不处理微信电话
-        }
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/TelecomUtils.java
@@ -484,6 +484,26 @@
+    /**
+     * 根据 Telecom 通话信息判断是否为网络电话
+     */
+    public static boolean isWechatPhoneNumber(Call telecomCall) {
+        String phoneNumber = "";
+        Call.Details details = telecomCall != null ? telecomCall.getDetails() : null;
+        if (details != null) {
+            GatewayInfo gatewayInfo = details.getGatewayInfo();
+            if (gatewayInfo != null) {
+                phoneNumber = gatewayInfo.getOriginalAddress().getSchemeSpecificPart();
+            } else if (details.getHandle() != null) {
+                phoneNumber = details.getHandle().getSchemeSpecificPart();
+            }
+        }
+        return isWechatPhoneNumber(phoneNumber);
+    }
```
（`TelecomForward`、`InCallUiStateMachine` 的同款旧判断同步替换为 `isWechatPhoneNumber(...)`；`TelecomUtils` 原取号方法补 `getOriginalAddress()` 判空。）

## 为什么能修复
识别依据从"和本机号码比对"这种间接启发式，改为直接对通话句柄做微信网络电话特征判断，覆盖"无来电号码、仅有本机号码"的场景，四个入口（来电新增/移除、转发分发、状态机消息）统一拦截，浮窗不再弹出。隐患：新 `isWechatPhoneNumber(Call)` 内部 `gatewayInfo.getOriginalAddress()` 仍未判空（老方法已补），极端 gateway 信息下仍可能 NPE；微信号码特征（10000000/00000000000/本机号）属于约定式黑名单，微信改版或运营商特殊号码可能再次漏网。

## 复盘经验
- 判断"是不是某类通话"应该基于通话本身的属性（handle/scheme/GatewayInfo），而不是和外部状态（已连接号码列表）做脆弱比对。
- 同一业务判断散落多处（本单 4 个类）时，漏改一两处就是新 bug；先收敛成单一工具方法，再谈修 bug。
- 第三方 VoIP 走 Telecom 框架的号码特征是"事实约定"而非契约，屏蔽逻辑要集中、带注释、便于微信/系统版本升级时统一维护。
