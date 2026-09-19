# SIR-8046 · 偶现设备列表显示 6 个已连接设备
- **提交**：`de8cfef4` | 2026-09-15 | dufan | HardwareLibs | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 测试错误 · 域 手车互联（rc：重新绑定时未判断；sol：重新绑定时判断）

## 问题
偶现：蓝牙设备列表中已配对/连接设备数达到上限（`MAX_PAIR_DEVICE_SIZE`）后仍多出一台，界面出现 6 个已连接设备。

## 根因分析
配对数上限由 `CachedBluetoothDeviceManager.setDeviceConnectTime` 里的 `if (mBluetoothConnectTime.size() > MAX_PAIR_DEVICE_SIZE)` 淘汰最旧设备来保证，但该方法的调用入口没有覆盖**重新绑定**路径：设备 removeBond 后再 bond 时，`BluetoothEventManager` 的 bond 状态广播处理只调 `onBondingStateChanged` / `onBluetoothDeviceBondChanged`，不会走 `setDeviceConnectTime`，重绑设备绕过了数量校验直接进入已配对集合，`mBluetoothConnectTime` 计数与实际配对数失配，偶现超限。另有两处放大因素：淘汰用的 `BluetoothUtils.removeLastDevice` 名不副实（实际按最旧时间挑选），且返回空串时淘汰循环仍空转；返回值无人校验，未取到最旧 MAC 时静默不淘汰。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/BluetoothEventManager.java；.../local/CachedBluetoothDeviceManager.java；.../utils/BluetoothUtils.java
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/BluetoothEventManager.java
+++ b/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/BluetoothEventManager.java
@@ -343,6 +343,10 @@
                 cachedDevice.onBondingStateChanged(bondState);
                 BluetoothEventManager.this.mDeviceManager.onBluetoothDeviceBondChanged(cachedDevice, bondState);
+                // 配对/重绑时执行设备数量上限校验，避免 removeBond 后重绑的设备绕过 setDeviceConnectTime 的限制导致配对数超过 MAX_PAIR_DEVICE_SIZE
+                if (bondState == 12) {
+                    BluetoothEventManager.this.mDeviceManager.setDeviceConnectTime(cachedDevice);
+                }
                 BluetoothEventManager.this.dispatchBondStateChanged(cachedDevice, bondState, preBondState);
```
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/local/CachedBluetoothDeviceManager.java
+++ b/... (实际路径 component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDeviceManager.java)
@@ -122,16 +122,19 @@
         mBluetoothConnectTime.put(device.getAddress(), currentTimeMillis);
         device.setLastConnectTime(currentTimeMillis);
+        Log.d(TAG, "setDeviceConnectTime : " + mBluetoothConnectTime.size());
         if (mBluetoothConnectTime.size() > MAX_PAIR_DEVICE_SIZE) {
-            String lastDevice = BluetoothUtils.removeLastDevice(mBluetoothConnectTime);
-            Iterator<CachedBluetoothDevice> iterator = mPairedDevice.iterator();
-            while (iterator.hasNext()) {
-                CachedBluetoothDevice bluetoothDevice = iterator.next();
-                if (bluetoothDevice.getAddress().equals(lastDevice)) {
-                    iterator.remove();
-                    bluetoothDevice.disconnect();
-                    bluetoothDevice.unpair();
-                    break;
+            String lastDevice = BluetoothUtils.removeOldestDevice(mBluetoothConnectTime);
+            if (!TextUtils.isEmpty(lastDevice)) {
+                Iterator<CachedBluetoothDevice> iterator = mPairedDevice.iterator();
+                ...（遍历匹配 lastDevice，remove + disconnect + unpair）
             }
         }
```
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/utils/BluetoothUtils.java
+++ b/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/utils/BluetoothUtils.java
@@ -150,9 +150,9 @@
-    public static String removeLastDevice(Map<String, Long> connectTimeMap) {
+    public static String removeOldestDevice(Map<String, Long> connectTimeMap) {
         if (connectTimeMap.isEmpty()) return "";
-        String oldestMac = "";
+        String oldestMac = null;
@@ -        return oldestMac;
+        return oldestMac != null ? oldestMac : "";
     }
```

## 为什么能修复
在 bond 状态广播（`bondState == 12`，BOND_BONDED）处补上 `setDeviceConnectTime` 调用，重绑设备与首次配对走同一上限校验，超限即淘汰最旧并 `disconnect()+unpair()`，列表数量封顶；`removeOldestDevice` 改名澄清语义、`null` 初值 + 空串返回 + `TextUtils.isEmpty` 判断杜绝"取不到最旧 MAC 仍继续流程"的静默失败，并加日志输出当前计数便于跟踪。副作用：重绑瞬间若已满上限会立刻挤掉最旧设备，属需求内行为；`bondState == 12` 魔数用 `BluetoothDevice.BOND_BONDED` 常量更佳。

## 复盘与经验
- 状态机入口要全覆盖：配对上限这类不变式校验必须挂在所有进入状态的路径上（首配、重绑、开机恢复），漏一条路径就是偶现 bug。
- "先 removeBond 再 bond"是测试常用绕过路径，容量/配额类逻辑要专门针对它补用例。
- 工具方法名与行为不一致（removeLastDevice 实为取最旧）会误导调用方与 reviewer，重构命名本身就是修 bug 的一部分；返回值必须校验，淘汰失败的静默分支最危险。
