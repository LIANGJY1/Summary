# 无单号 · 修改互联设备切换逻辑（Setting 同步状态机重构 + CarPlay 连接时换机确认弹窗）

- **提交**：`d4b48f1d` | 2026-07-06 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
把 Launcher 侧 `dd00ca96` 的互联状态机重构同步到 Setting 模块的 `DeviceConnectManager`，并重写配对切换策略：废弃"记住已提示地址"（`has_hint_dialog_address`）方案，改为实时查 CarPlay 设备列表；新增"CarPlay 已连接时换连新设备需确认断开"的弹窗。

## 实现结构
- 修改 `init/DeviceConnectManager.kt`（+203/-169 级）：
  - 新增与 Launcher 同构的 `private fun setDeviceConnectStatus(deviceType, isConnected)`：三协议 when 分支维护 `mCurrentConnectType`/`productName`/SP，断开去重卫语句，主线程统一回调；
  - `Handler(Looper.getMainLooper())` 删除，全部 post/postDelayed 换 `ThreadUtils`；
  - `connectCarPlay(activity, address)` → `connectCarPlay(address)`；
  - CarPlay 连接成功时置 `mShareNetworkSupported = false`（共享网络能力随 CarPlay 连接关闭）。
- 修改 `utils/BluetoothUtil.kt`：
  - 删除 `HAS_HINT_DIALOG_ADDRESS` 读取逻辑，改为 `mCarPlayDeviceManager.carPlayDeviceList.firstOrNull { it.btAddr == address }` 实时判断；
  - 新增 `SIsOperationBluetooth` 标记与 `SRefreshData = MutableLiveData<Boolean>()` 刷新通知；
  - CarPlay 已连接（`getCurrentConnectType() == 1`）时点蓝牙配对，先弹"连接新设备将断开当前 CarPlay"确认框，确认后强制断开当前 CarPlay 设备再 `startPairing()`。
- 修改 `Constants.java`：删除 `HAS_HINT_DIALOG_ADDRESS`。
- 修改 `dialog_bluetooth.xml`、中英文 `strings.xml`：新增 `connect_new_device_hint_for_carplay` 文案等。

数据流：配对点击 → 判断当前连接类型 → CarPlay 在连则确认弹窗 → 强断旧 CarPlay → 160ms 延迟后配对新机；状态流转统一走 `setDeviceConnectStatus` → 主线程遍历监听器。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ -837,6 +754,66 @@
+    private fun setDeviceConnectStatus(deviceType: String, isConnected: Boolean) {
+        when (deviceType) {
+            CARPLAY -> {
+                if (mCurrentConnectType != 1 && !isConnected) return
+                mProductName = ""
+                SPUtils.setParam("productName", mProductName)
+                mCurrentConnectType = if (isConnected) 1 else 0
+            }
+            HICAR -> {
+                if (mCurrentConnectType != 2 && !isConnected) return
+                if (isConnected) {
+                    mProductName = "HUAWEI HiCar"
+                    SPUtils.setParam("productName", mProductName)
+                    mCurrentConnectType = 2
+                } else { mProductName = ""; mCurrentConnectType = 0 }
+            }
+            CARLINK -> { ... }
+        }
+        ThreadUtils.runOnUiThread {
+            synchronized(mListener) {
+                for (callback in mListener) {
+                    callback.onDeviceStatusChanged(deviceType, isConnected, mProductName)
+                }
+            }
+        }
+    }
```

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@（配对流程内）
-                            if (SIsFromCarConnect) { it.setAutoConnect(false) }
-                            if (it.device == null) { ...Toast... } else { it.startPairing() }
+                            if (DeviceConnectManager.getInstance().getCurrentConnectType() == 1) {
+                                    TextDialog("", getString(R.string.connect_new_device_hint_for_carplay))
+                                    .setCallback(object : Callback {
+                                        override fun confirm(content: Any?) {
+                                            lifecycleScope.launch(Dispatchers.IO) {
+                                                pairedDevices.firstOrNull {
+                                                    it.bluetoothDevice?.phoneCarConnectionType == 1 && it.bluetoothDevice.isPhoneCarConnect }
+                                                    ?.let {
+                                                        operationPhoneCar(it.bluetoothDevice!!, false, true)
+                                                        it.bluetoothDevice.disconnect()
+                                                        delay(160.milliseconds)
+                                                        device.startPairing()
+                                                    }
+                                            }
+                                        }
+                                        override fun cancel() {}
+                                    }).show(childFragmentManager, "ConnectHintDialog")
```

实现讲解：两个应用各自维护一份 DeviceConnectManager 是当前架构现实，本次把 Launcher 的状态机收敛经验原样移植，保证两侧行为一致（type/productName 语义、断开去重、ThreadUtils）。策略层面则推翻了自己 4 天前的"弹一次并记地址"方案——因为 Setting 侧能实时访问 CarPlay 设备列表，"是否已配对 CarPlay"不需要持久化记忆就能判断，去掉了状态不一致的隐患。换机确认弹窗补上了"CarPlay 独占连接"的产品约束。

## 复盘与要点
- "实时查数据源"优于"持久化记录用户选择"：前者天然一致，后者要处理增删设备、跨应用同步等边角，本次返工印证了这一取舍。
- 两个模块各持一份 DeviceConnectManager 且需人工保持同步，说明该类应下沉 Common/Hardwarelibs；本批次内 Launcher(`dd00ca96`) 与 Setting(`d4b48f1d`) 的重复重构就是重复代码的维护成本实证。
- `MutableLiveData` 作为模块级刷新总线（`SRefreshData`）比回调散传更清晰，但静态 LiveData 有泄漏面，需注意观察者生命周期。
