# 无单号 · 修改互联设备切换逻辑（Setting 蓝牙列表融合 CarPlay/HiCar/CarLink 设备）

- **提交**：`37bd1320` | 2026-07-02 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
重写设置页蓝牙设备列表的组装与切换逻辑：把 CarPlay / HiCar / CarLink 互联设备合并进手机蓝牙列表，配对手机（含 CarPlay UUID）时弹窗让用户选择"CarPlay 还是蓝牙"连接方式，并支持强制断开场景。

## 实现结构
- 修改 `application/Setting/.../utils/BluetoothUtil.kt`（核心，+196 行级改动）：
  - `operationPhoneCar()` 新增 `isForceDisconnect` 参数，强断时不再走重连分支；
  - 新增 `showPairedDialog()`：检测配对设备 UUID 中是否含 CarPlay 服务 UUID（`00000000-deca-fade-deca-deafdecacafe`），首次弹"连接方式"选择框（CarPlay/蓝牙），选择结果按地址记入 `HAS_HINT_DIALOG_ADDRESS`，下次直连不再询问；
  - `handleDeviceList()` 从"单个设备打互联标记"重构为"接收全量列表，逐个互联通道 new `CachedBluetoothDevice` 虚拟条目再合并"；
  - `handlePairData()` 增加蓝牙开关判断，未配对设备先移除再统一构建列表；
  - 流量共享弹窗确认时先 `changeShareNetworkState(false)` 再 `true`（重置式开启）。
- 修改 `init/DeviceConnectManager.kt`、`ui/adapter/BluetoothAdapter.kt`、`ui/fragment/diologfragment/BluetoothFragment.kt`：适配上述签名与流程变化。
- 修改 `Constants.java`：新增 `HAS_HINT_DIALOG_ADDRESS`、`CURRENT_DEVICE_SHARE_NETWORK_STATE`。
- 修改 `values/strings.xml`、`values-en/strings.xml`：新增连接方式选择、蓝牙配对提示、不支持共享网络等 6 条文案。
- `build.gradle` 删 2 行。

数据流：系统配对列表 + 三路互联设备列表 → `handlePairData`/`handleDeviceList` 合并为 `MultiBluetoothDevice` 列表 → `BluetoothAdapter` 渲染 → 点击走 `operationPhoneCar`（或 `showPairedDialog` 先询问方式）→ 中间件连接/断开。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -191,65 +213,171 @@
+        @SuppressLint("MissingPermission", "NewApi")
+        fun showPairedDialog(
+            lifecycleScope: LifecycleCoroutineScope,
+            mWxBtManager: IWxBluetoothManager,
+            cachedDevice: CachedBluetoothDevice?,
+            activity: Activity?,
+            childFragmentManager: FragmentManager
+        ) {
+            cachedDevice?.let {
+                lifecycleScope.launch(Dispatchers.IO) {
+                    if (mWxBtManager.currentConnectDevice != null) {
+                        mWxBtManager.currentConnectDevice.disconnect()
+                        delay(160.milliseconds)
+                    }
+                    if (it.device.uuids.contentToString()
+                            .contains("00000000-deca-fade-deca-deafdecacafe")
+                    ) {
+                        ...
+                            var hasHintAddress = SettingsUtils.getGSetting(HAS_HINT_DIALOG_ADDRESS)
+                            if (hasHintAddress.contains(it.address)) {
+                                DeviceConnectManager.getInstance()
+                                    .connectCarPlay(activity, it.address)
+                            } else { ... TextDialog("连接方式", "请选择设备的连接方式", "Apple CarPlay", "蓝牙") ... }
+                    } else {
+                        it.connect(true)
+                    }
```

```diff
-        fun handleDeviceList(device: CachedBluetoothDevice) {
+        fun handleDeviceList(
+            phonePairedDevices: MutableList<MultiBluetoothDevice>
+        ) {
             try {
+                val list = mutableListOf<MultiBluetoothDevice>()
                 DeviceConnectManager.getInstance().mCarPlayDeviceManager?.apply {
                     carPlayDeviceList.forEach {
-                        if (TextUtils.equals(device.address, it.btAddr)) {
-                            device.phoneCarConnectionType = 1
-                            device.connectionType = it.projectionConnectType
-                            device.setIsPhoneCarConnect(it.deviceStatus == CarPlayConstants.DeviceStatus.CONNECTED)
-                            return@forEach
-                        }
+                        list.add(MultiBluetoothDevice(1, CachedBluetoothDevice(
+                            MyApplication.myApplication,
+                            it.deviceName, it.btAddr, 1,
+                            it.projectionConnectType,
+                            it.deviceStatus == CarPlayConstants.DeviceStatus.CONNECTED)))
                     }
                 }
```

实现讲解：旧逻辑是"先有蓝牙设备，再去互联列表里找匹配打标"，导致纯 CarPlay 设备（无蓝牙配对）根本进不了列表；新逻辑直接把三路互联设备用 `CachedBluetoothDevice` 轻量构造器（配套依赖 `6d7de086` 新增的构造方法）生成虚拟条目并进列表，从根上把"互联设备"提升为一等公民。`showPairedDialog` 的"询问一次并记住地址"用全局设置模拟了 per-device 记忆，代价是把逗号拼接的地址串塞进一个设置键。

## 复盘与要点
- 数据流反转（被动匹配 → 主动合并）是本次最有价值的重构手法：新增互联通道时只需在 `handleDeviceList` 再加一段，列表逻辑稳定。
- `has_hint_dialog_address` 用逗号拼接串存多设备选择记录，且 `hasHintAddress = ",${it.address}"` 直接覆盖而非追加，疑似丢历史的缺陷点，值得复查。
- 连接方式选择弹窗放在 `Dispatchers.IO` 协程里 `withContext(Main)` 弹出，IO 线程切回主线程的边界处理正确，可作协程 + 弹窗组合的参考写法。
