# SIR-8091 · 控制中心蓝牙连接状态与车设页不一致（缓存对象分裂）

- **提交**：`4bcb6d08` | 2026-09-10 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互（提交头标影响等级 D，以缺陷库 C 为准）

## 问题
控制中心显示的蓝牙连接状态与设置（车设）页内的状态不一致——已连接/已断开两边说法对不上。

## 根因分析
`CachedBluetoothDeviceManager`（Hardwarelibs 蓝牙缓存层）内部维护两份集合：`mCachedDevices` 与 `mPairedDevice`。Profile 层的连接状态广播更新的是 **`mCachedDevices` 里持有的 `CachedBluetoothDevice` 对象**，而 `getCurrentConnectDevice()` 却遍历 **`mPairedDevice`** 调 `isConnected()`——两份集合持有的可能是不同实例/不同步的旧对象，在 mPairedDevice 上查询连接态自然拿到过期结果（代码里新增注释自证："Profile 广播更新的是 mCachedDevices，连接状态查询必须使用同一份对象缓存"）。此外 `onDeviceDisappeared()` 旧实现不区分配对状态，设备消失即从 `mCachedDevices` 移除，且判断用的魔法数字 `10`（BOND_NONE），这会误删仍在列表里的设备对象，加剧两份缓存分裂。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDeviceManager.java（1 文件 +13/-6）
```diff
--- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDeviceManager.java
@@ onDeviceDisappeared
-    public boolean onDeviceDisappeared(CachedBluetoothDevice cachedDevice) {
-        this.mCachedDevices.remove(cachedDevice);
-        return cachedDevice.getBondState() == 10;
+    public synchronized boolean onDeviceDisappeared(CachedBluetoothDevice cachedDevice) {
+        cachedDevice.setVisible(false);
+        boolean unpaired = cachedDevice.getBondState() == BluetoothDevice.BOND_NONE;
+        if (unpaired) {
+            this.mCachedDevices.remove(cachedDevice);
+            this.mPairedDevice.remove(cachedDevice);
+        }
+        return unpaired;
@@ getCurrentConnectDevice
-    public CachedBluetoothDevice getCurrentConnectDevice() {
+    public synchronized CachedBluetoothDevice getCurrentConnectDevice() {
         CachedBluetoothDevice currentConnectDevice = null;
-        for (CachedBluetoothDevice cachedBluetoothDevice : this.mPairedDevice) {
-            if (cachedBluetoothDevice.isConnected()) {
+        // Profile 广播更新的是 mCachedDevices，连接状态查询必须使用同一份对象缓存。
+        for (CachedBluetoothDevice cachedBluetoothDevice : this.mCachedDevices) {
+            if (cachedBluetoothDevice.getBondState() == BluetoothDevice.BOND_BONDED
+                    && cachedBluetoothDevice.isConnected()) {
                 currentConnectDevice = cachedBluetoothDevice;
                 break;
             }
```

## 为什么能修复
连接态查询改到与广播更新相同的 `mCachedDevices` 缓存上（并加 BOND_BONDED 前置过滤），读到的一定是最新对象状态，控制中心与车设两边数据源统一后表现一致；`onDeviceDisappeared` 改为"仅未配对才移除、配对设备只置不可见，且两份集合同步清理"，消除了缓存对象被误删/单边删除造成的分裂；两个方法加 `synchronized` 降低并发读改风险。隐患：若 `mPairedDevice` 还有其他读取路径依赖旧行为，需要逐一核对（本提交只改了两处）。

## 复盘与经验
- 同一实体维护多份集合时，"写入走 A、读取走 B"是最危险的不一致源；要么单缓存 + 视图派生，要么所有读写都过同一入口并同步。
- 删除缓存条目前先判断业务状态（配对与否），"消失≠未配对"（出范围也触发 disappeared）。
- 用 `BluetoothDevice.BOND_NONE` 等常量替换魔法数字 `10`，可读性即正确性的第一道防线。
