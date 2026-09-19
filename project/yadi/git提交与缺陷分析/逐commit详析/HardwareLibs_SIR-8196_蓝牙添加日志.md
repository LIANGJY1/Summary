# SIR-8196 · 蓝牙 A2DP/HFP 连接路径添加日志（mistag 标记）
- **提交**：`48dbeea5` | 2026-09-14 | dufan | HardwareLibs | 日志增强（非 bugfix，提交类型为 feature；缺陷库标记 mistag=true）
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 待测试验证 · 域 手车互联（关联为排查支撑，非行为修复）

## 问题
无行为缺陷。为排查 SIR-8196"数据加载异常"类蓝牙问题补充观测点：profile 服务连接时看不到已连接设备列表，且 HFP 日志 tag 硬编码为 "HeadsetProfile" 与类内 TAG 不一致，过滤日志困难。

## 根因分析（改动说明）
纯日志变更，不涉及逻辑：`A2dpSinkProfile` / `HeadsetClientProfile` 的 `onServiceConnected` 中，在 `getConnectedDevices()` 取到列表后各补一条 `Log.d(TAG, "Bluetooth service connected:" + deviceList)`，用于确认服务绑定瞬间系统侧已连接设备集合；`HeadsetClientProfile` 匿名回调里 4 处硬编码 `"HeadsetProfile"` 字符串统一改为类常量 `TAG`，使 `onServiceConnected`/`onServiceDisconnected`/发现新设备告警可按统一 tag 过滤。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/profile/A2dpSinkProfile.java；component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/profile/HeadsetClientProfile.java
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/profile/A2dpSinkProfile.java
+++ b/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/profile/A2dpSinkProfile.java
@@ -191,7 +191,7 @@
             A2dpSinkProfile.this.mService = new BluetoothA2dpSink(proxy);
             List<BluetoothDevice> deviceList = A2dpSinkProfile.this.mService.getConnectedDevices();
-
+            Log.d(TAG, "Bluetooth service connected:" + deviceList);
             while (!deviceList.isEmpty()) {
```
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/profile/HeadsetClientProfile.java
+++ b/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/profile/HeadsetClientProfile.java
@@ -215,17 +215,17 @@
         public void onServiceConnected(int profile, BluetoothProfile proxy) {
             if (HeadsetClientProfile.V) {
-                Log.d("HeadsetProfile", "Bluetooth service connected");
+                Log.d(TAG, "Bluetooth service connected");
             }
             HeadsetClientProfile.this.mService = (BluetoothHeadsetClient) proxy;
             List<BluetoothDevice> deviceList = HeadsetClientProfile.this.mService.getConnectedDevices();
-
+            Log.d(TAG, "Bluetooth service connected:" + deviceList);
```

## 为什么能修复（价值）
本身不修行为，价值在可观测性：连接设备列表日志能直接暴露"服务绑定时系统已连接 N 台设备"的事实，是定位偶现多设备/状态不同步问题的第一手证据；统一 TAG 降低多 profile 日志混流时的排查成本。无副作用，仅日志开销。

## 复盘与经验
- 蓝牙 profile 服务绑定回调（onServiceConnected）是状态同步的起点，在此处打印已连接设备列表是排查"连接状态偶现异常"的标准埋点位。
- 日志 tag 应统一引用常量，硬编码字符串会导致按 tag 过滤时漏日志。
- 排查类单据（mistag）也应留档：观测点的加入位置本身就是对可疑路径的判断。
