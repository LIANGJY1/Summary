# SIR-4409 · 手机+耳机同时连接时控制中心只显示手机蓝牙图标
- **提交**：`133efce3` | 2026-08-12 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
同时连接手机蓝牙和耳机（头盔等二级音频设备）蓝牙时，控制中心只显示手机蓝牙图标，耳机图标不显示。

## 根因分析
缺陷库根因为"协议栈未提供耳机、头盔的判断接口"，即原 UI 判定链路只覆盖手机类设备。代码上：`SystemSettingsControllerService.isBluetoothConnected` / `bluetoothType` 只依赖 `mWxBluetoothManager.getCurrentConnectDevice()`（单设备模型，手机连接通道），`BasicServicesTile.updateBluetoothIcon()` 也只根据这个单一 `BluetoothDeviceType` 在 `bluetooth1Image`/`bluetooth2Image` 两个图标槽里二选一，完全没有耳机（ANW）设备的连接状态来源；耳机经 `BtAnwManager` 通道连接时既不触发刷新，也没有任何接口可查其连接数，图标自然只剩手机。原代码还有一处笔误：`bluetooth1Image.setImageResource(View.GONE)` 把 int 常量当资源 id 设置。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/.../SystemSettingsControllerService.kt`、`quicksetting/ui/BasicServicesTile.java`
```diff
--- SystemSettingsControllerService.kt
     val isBluetoothConnected: Boolean
-        get() = mWxBluetoothManager!!.getCurrentConnectDevice() != null
+        get() = getBluetoothConnectedDevice() != null ||
+            getBluetoothAnwConnectedDevices().isNotEmpty()
+
+    fun getBluetoothAnwConnectedDevices(): List<DeviceBean> {
+        if (!mBtAnwManager.isBtOn) {
+            return emptyList()
+        }
+        return mBtAnwManager.mPairedDevices.filter { it.isAnyProfileConnected }
+    }
+
+    private fun updateQuickSettingBluetooth(inOperation: Boolean) {
+        ...
+        val connectedDevices = getBluetoothConnectedDevice()
+        val anwConnectedDevices = getBluetoothAnwConnectedDevices()
+        mQuickSettingUICallback!!.updateBluetooth(
+            isBluetoothEnabled,
+            inOperation,
+            connectedDevices != null || anwConnectedDevices.isNotEmpty(),
+            connectedDevices?.getName() ?: anwConnectedDevices.firstOrNull()?.getName() ?: ""
+        )
+    }
```
```diff
--- BasicServicesTile.java  updateBluetoothIcon()
+        boolean phoneConnected = settingService.getBluetoothConnectedDevice() != null;
+        int secondaryDeviceCount = settingService.getBluetoothAnwConnectedDevices().size();
+        if (phoneConnected) {
+            bluetooth1Image.setImageResource(R.drawable.vector_bt_phone);
+            bluetooth1Image.setVisibility(View.VISIBLE);
+        } else {
+            bluetooth1Image.setVisibility(View.GONE);
+        }
+        if (secondaryDeviceCount > 0) {
+            bluetooth2Image.setImageResource(secondaryDeviceCount > 1
+                    ? R.drawable.vector_bt_airs : R.drawable.vector_bt_air);
+            bluetooth2Image.setVisibility(View.VISIBLE);
+        } else {
+            bluetooth2Image.setVisibility(View.GONE);
+        }
```
（同时注册 `BtAnwManager` 回调 `mAnwBluetoothListener`：配对/连接/开关状态变化时 `handleConnectState(originalIntent)` 同步快照并 `updateQuickSettingBluetooth(false)` 刷新；`onConnectionStateChanged`/`onBluetoothStateChanged` 原先四份手写 updateBluetooth 分支统一收敛到 `updateQuickSettingBluetooth()`。）

## 为什么能修复
给 SystemUI 增加了耳机设备的连接状态来源（`BtAnwManager.mPairedDevices` 过滤 `isAnyProfileConnected`），图标逻辑从"单设备二选一"改为"手机槽 + 耳机槽独立判定"，两类设备可同时显示（多耳机还切换 `vector_bt_airs`）；BtAnwManager 回调保证耳机连断实时刷新并修正 Setting 侧 `DeviceBean` 快照时序（`handleConnectState` 在回调先行下发后补数据）。顺手消除了 `setImageResource(View.GONE)` 的资源 id 笔误与多处 `!!` NPE 风险（改 `?.`）。隐患：`mPairedDevices` 快照若与协议栈不同步可能短暂多显示图标，已有 `handleConnectState` 补偿。

## 复盘与经验
- "单当前连接设备"模型（getCurrentConnectDevice）天然不支持多设备并行连接，UI 层一旦要展示多设备就必须引入集合型状态源，而不是给单设备打补丁。
- 两条蓝牙通道（手机 HFP/A2DP 与 ANW 耳机）事件源不同，UI 刷新要同时挂两路回调，且注意回调与内部数据更新顺序（本例回调先于 DeviceBean 更新的时序问题用 handleConnectState 显式同步）。
- 相同的 updateBluetooth 手写四份极易漂移，先收敛为单一函数再改逻辑，是修这类 UI 状态 bug 的正确顺序。
