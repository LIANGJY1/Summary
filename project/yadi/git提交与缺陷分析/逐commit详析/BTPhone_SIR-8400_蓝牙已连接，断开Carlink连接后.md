# SIR-8400 · 断开 Carlink 后蓝牙电话误显示"未连接设备"
- **提交**：`781a6000` | 2026-09-17 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙手机已连接且通话正常，断开 Carlink（手机互联）连接后，蓝牙电话界面错误显示"未连接设备"。

## 根因分析
缺陷库定位为"多设备协议状态错乱"。原架构中 `BluetoothManager` 通过 `FrameworkBluetoothProvider`（本次提交整体删除）监听 `BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED` 广播，并直接用 intent 里的 `EXTRA_STATE` 更新全局状态：`handleHfpConnectionStateChange` 收到 `STATE_DISCONNECTED` 就无条件 `connectionState.postValue(DISCONNECTED)`。在多设备场景下，该广播是"单台设备"的事件——Carlink 断开时其设备的 HFP 断连广播触发，Provider 却把它当成全局状态，将仍然连接的手机也一并判为断开，UI 随之显示未连接。广播事件≠全局事实，这是典型的"以单设备事件冒充聚合状态"错误。

## 关键代码修改
改动文件：BluetoothManager.java、UiBluetoothMonitor.java、MainActivity.java（删除 FrameworkBluetoothProvider.java、BluetoothStateProvider.java，共 -499 行）
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/manager/BluetoothManager.java
-public class BluetoothManager {
+public class BluetoothManager implements UiBluetoothMonitor.Listener {
...
-    private final MediatorLiveData<BluetoothConnectionState> connectionState =
-        new MediatorLiveData<>();
+    private final MutableLiveData<BluetoothConnectionState> connectionState =
+        new MutableLiveData<>();
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/telecom/UiBluetoothMonitor.java
+    /**
+     * 获取电话HFP Client Profile的全局连接状态。
+     * 状态使用方应查询该值，不直接使用单台设备广播中的状态。
+     */
+    public int getHfpClientConnectionState() {
+        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
+            return BluetoothProfile.STATE_DISCONNECTED;
+        }
+        return mBluetoothAdapter.getProfileConnectionState(16);
+    }
+                        // Profile服务就绪后通知状态使用方刷新初始状态
+                        notifyListeners();
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/manager/BluetoothManager.java
+    @Override
+    public void onStateChanged() {
+        updateBluetoothConnectionState(UiBluetoothMonitor.get().getHfpClientConnectionState());
+    }
```

## 为什么能修复
状态源从"转发单设备广播的 EXTRA_STATE"改为"收到通知后回查 `BluetoothAdapter.getProfileConnectionState(16)` 这一聚合事实"。Carlink 断开的广播到来时，回查结果仍显示真实手机的 HFP 处于 CONNECTED，因此不会再误报断开；`UiBluetoothMonitor` 在 Profile 服务就绪后 `notifyListeners()` 也保证了初始状态正确发布。同时砍掉了 Provider 双层转发（MediatorLiveData→直接 MutableLiveData），状态链路由"Provider→Manager→UI"缩短为"Monitor→Manager→UI"，消除了两套监听不一致的隐患。副作用：每次通知都要同步查询 Profile 状态（binder 调用），频率不高时可接受。

## 复盘与经验
- 蓝牙多设备场景下，`ACTION_CONNECTION_STATE_CHANGED` 是按设备投递的事件，判断"是否有设备连接"必须用 `getProfileConnectionState()` 聚合查询或先核对广播中的设备是否为当前关注设备——缺陷库 sol"防止广播的非当前设备"正是此意。
- `isHfpConnected()` 原代码用 `BluetoothAdapter.STATE_CONNECTED`（开关态常量）与 `getProfileConnectionState` 结果比较也是隐患（恰与 `BluetoothProfile.STATE_CONNECTED` 数值相同才侥幸工作），本次改为常量语义正确的写法。
- 状态分发架构中"事件"与"状态"要分层：事件只做触发，真实状态应回查权威数据源（single source of truth）。
- 大幅删除双层抽象（StateProvider/FrameworkProvider）反而降低了出错面，修复不一定靠加代码。
