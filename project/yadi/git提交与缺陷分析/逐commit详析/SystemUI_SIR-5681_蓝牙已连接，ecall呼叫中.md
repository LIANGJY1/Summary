# SIR-5681 · ECall 呼叫中误触发蓝牙电话浮窗甚至手机端拨打救援电话

- **提交**：`d22c0539` | 2026-08-26 | liujinfeng | SystemUI/BTPhone | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙已连接时若发生 ECall（紧急呼叫），蓝牙电话应用会弹出通话浮窗；现象升级场景中甚至会从手机端拨打救援电话，属于安全相关的误触发。

## 根因分析
BTPhone 通过 `InCallServiceImpl`（继承 `InCallService`）监听 telecom 框架的通话增删。问题在于 telecom 的 `onCallAdded`/`onCallRemoved` 对**所有** PhoneAccount 的 Call 都会回调——车机自带的系统电话（蜂窝/ECall）与蓝牙 HFP（`com.android.bluetooth.hfpclient.HfpClientConnectionService`）产生的 Call 都走这条通道。原代码在 `onCallAdded` 里不区分来源，直接执行 `moveAppToBackground()` 并接管通话 UI，ECall 的 Call 一进来就被当成蓝牙通话处理，拉起蓝牙电话浮窗；配合 MainActivity 中"SOS 触发 ECall 应用（`trigger_type=bluetooth_sos`）"的入口逻辑被 ECall 场景反向扰动，造成偶发从手机端拨出救援电话的严重交叉触发。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java`、`application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java`

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java
@@ -1220,6 +1221,12 @@ public class InCallServiceImpl extends InCallService {
             LogUtils.i(TAG, "telecomCall.getDetails().getHandle(): " + telecomCall.getDetails().getHandle());
         }
 
+        if (!isHfpCall(telecomCall)) {
+            LogUtils.i(TAG, "Ignore non-HFP call: "
+                    + (telecomCall.getDetails() != null
+                    ? telecomCall.getDetails().getAccountHandle() : null));
+            return;
+        }
         // 【新增】拨号/来电时，将BTPhone应用退出到后台，让系统电话应用接管
         moveAppToBackground();
```

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java
@@ -1302,6 +1309,15 @@ public class InCallServiceImpl extends InCallService {
         LogUtils.i(TAG, "==================== onCallAdded END ====================");
     }
 
+    private static boolean isHfpCall(Call telecomCall) {
+        Call.Details details = telecomCall != null ? telecomCall.getDetails() : null;
+        PhoneAccountHandle accountHandle = details != null ? details.getAccountHandle() : null;
+        ComponentName componentName = accountHandle != null ? accountHandle.getComponentName() : null;
+        return componentName != null
+                && "com.android.bluetooth.hfpclient.HfpClientConnectionService"
+                .equals(componentName.getClassName());
+    }
```

（`onCallRemoved` 同样加 `isHfpCall` 前置判断，非 HFP 通话直接 `super.onCallRemoved` 返回；`MainActivity` 的 ECall 跳转分支补充 `finish()` 结束自身。）

## 为什么能修复
新增 `isHfpCall()` 以 `Call.Details.getAccountHandle().getComponentName()` 判定通话来源：只有类名为 `HfpClientConnectionService`（蓝牙 HFP 客户端连接服务）的 Call 才进入蓝牙电话的处理分支，系统/ECall 通话在 `onCallAdded`/`onCallRemoved` 入口即被过滤，不再驱动蓝牙浮窗与 `moveAppToBackground`，两条通话链路彻底解耦，交叉误触发消失。`MainActivity` 补 `finish()` 避免跳转 ECall 应用后旧的 BTPhone 主界面残留在栈顶。隐患：硬编码 HfpClient 类名字符串，若 ROM 蓝牙栈实现变更需同步；被过滤的 Call 在本服务内完全无感，若未来需要在浮窗展示系统通话需另做通道。

## 复盘与经验
- `InCallService` 收到的是全局 telecom 通话，第一件事就应该按 `PhoneAccountHandle` 分流——"哪个连接服务来的 Call"是 BT/蜂窝/ECall 混用系统的第一道闸门，漏了这层判断，所有下游状态机都会被污染。
- 安全件（ECall/SOS）与其他应用交互时要做方向隔离设计：任何应用侧对 ECall 的"感知"都应只读不写，本例正是蓝牙链路反向影响了救援链路。
- 偶现通话类 bug 常由两条异步通话源交叠引起，日志里 `getAccountHandle` 是定位通话来源的第一线索。
