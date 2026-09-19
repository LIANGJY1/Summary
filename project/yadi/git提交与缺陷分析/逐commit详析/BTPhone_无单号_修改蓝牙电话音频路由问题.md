# 无单号 · 蓝牙电话音频路由问题修复（路由状态机重构）
- **提交**：`ae7434e4` | 2026-09-01 | caohongliang | BTPhone | bugfix（无单号维护修复）
- **缺陷库**：未关联单号

## 问题
蓝牙通话中音频路由（车机蓝牙 / 手机外放）状态与实际不一致：切换后按钮状态错乱、通话接通时路由被强制覆盖、静音状态异常。

## 根因分析
多个机制叠加出错。其一，`CallingActivity` 初始化时用 `isCarAudioRouteMode()`（读 Telecom `UiCall.isCarAudio()`，平台上报不准）判断后，非车机音频场景 600ms 后强制 `initAudioMode(ROUTE_SPEAKER)`，覆盖真实路由。其二，`UiCallManager.getAudioRoute()` 直接返回 `mPhoneForward.getAudioRoute()` 的 HFP 链路原始值（"2-connect/0-disconnect"），与 UI 期望的 Telecom ROUTE_BLUETOOTH/ROUTE_SPEAKER 枚举混用，比较结果错乱。其三，`setMuted(muted)` 原实现是 `bluetoothAdapter.setMicState(micState == 0 ? 1 : 0)`——无条件取反，完全忽略入参，导致"解除静音"注释下调用 `setMuted(true)` 的自欺式用法。其四，`onCallStateChange` 在 DIALING/CONNECTING 阶段无条件强制 `setAudioRoute(ROUTE_BLUETOOTH)`，覆盖用户已选择的路由。

## 关键代码修改
改动文件：`CallingActivity.java`、`ThreeWayCallingActivity.java`、`floatview/FloatCallWindow.java`、`FloatCallWindowPresenter.java`、`telecom/telecom/UiCallManager.java`
```diff
--- .../telecom/telecom/UiCallManager.java
@@ getAudioRoute()
-        return mPhoneForward.getAudioRoute(address);
+        int hfpAudioState = TextUtils.isEmpty(deviceAddress)
+                ? mPhoneForward.getAudioRoute()
+                : mPhoneForward.getAudioRoute(deviceAddress);
+        return toTelecomAudioRoute(hfpAudioState);
+    }
+    private int toTelecomAudioRoute(int hfpAudioState) {
+        return hfpAudioState == STATE_AUDIO_CONNECTED ? ROUTE_BLUETOOTH : ROUTE_SPEAKER;
     }

@@ setMuted(boolean muted)
         int micState = bluetoothAdapter.getMicState();
-        bluetoothAdapter.setMicState(micState == 0 ? 1 : 0);
+        int targetMicState = muted ? 1 : 0;
+        if (mPhoneForward != null) { mPhoneForward.setMuted(muted); }
+        if (micState != targetMicState) { bluetoothAdapter.setMicState(targetMicState); }

@@ onCallStateChange()
-        if (uiCall.getState() == Call.STATE_DIALING || uiCall.getState() == Call.STATE_CONNECTING) {
-            ... setAudioRoute(ROUTE_BLUETOOTH);（拨号阶段强制蓝牙路由）
+        if (uiCall.getState() == Call.STATE_ACTIVE
+                && !mCallsWithDefaultAudioRoute.contains(uiCall)) {
+            // 每路电话首次接通后默认使用车机音频，后续不再自动覆盖用户的手动选择。
+            mCallsWithDefaultAudioRoute.add(uiCall);
+            setMuted(false);
```
```diff
--- .../btphone/CallingActivity.java
-        if (isCarAudioRouteMode()) { ... } else {
-            btnSpeaker.postDelayed(() -> initAudioMode(ROUTE_SPEAKER), 600);
-        }
+        btnSpeaker.post(() -> {
+            updateSpeakerButton(currentAudioRoute);
+            setBtnMuteEnabled(currentAudioRoute == ROUTE_BLUETOOTH);
+        });
@@ onAudioStateChangedByBroadcast(int route)
+        // Telecom的route在当前平台不准确，路由UI只由HFP广播更新。
         if (isAudioRouteSwitching) { ... }
+        currentAudioRoute = route;
+        updateSpeakerButton(route);
```
切换超时逻辑同步改为"读 `getAudioRoute()` 实际值恢复 UI"而非强制解锁。

## 为什么能修复
核心是统一路由真值来源：UI 只信 HFP 广播（`onAudioStateChangedByBroadcast`）和 HFP 链路状态映射（`toTelecomAudioRoute`），不再使用不准确的 Telecom route；`getAudioRoute()` 归一化枚举后，切换、校验、超时恢复全部基于同一口径。`setMuted` 修复入参被忽略的取反错误，接通时一次性 `setMuted(false)` 并按"每通话一次"集合 `mCallsWithDefaultAudioRoute` 设默认路由，不再周期性覆盖用户选择。删除 600ms 强制 SPEAKER 的定时器消除竞态。风险：删除 Telecom route 校验后，若 HFP 广播本身异常则 UI 无兜底来源；`FloatCallWindow` 大幅瘦身（-271 行）需回归浮窗通话全场景。

## 复盘与经验
- 音频路由这类"系统多真值源"（Telecom / HFP 链路 / AudioManager）场景，必须选定一个权威真值源并做枚举归一化，混用原始值与映射值是状态错乱的根源。
- `setMicState(state == 0 ? 1 : 0)` 式无条件取反是典型"toggle 语义冒充 set 语义"bug，调用方只能靠反向传参自保——修复后调用点的自欺代码（解除静音调 setMuted(true)）要一并清理。
- "每次状态变化都强制设置默认值"会覆盖用户选择；默认值应每会话/每通话只设一次。
