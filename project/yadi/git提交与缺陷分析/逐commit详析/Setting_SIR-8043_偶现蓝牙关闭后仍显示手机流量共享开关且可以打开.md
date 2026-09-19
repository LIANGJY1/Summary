# SIR-8043 · 偶现蓝牙关闭后仍显示"手机流量共享"开关且可打开

- **提交**：`3040be92` | 2026-09-10 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 待测试验证 · 域 手车互联

## 问题
蓝牙关闭（未连接手车互联）后，设置页仍偶现显示"手机流量共享"开关，并且可以把它打开。

## 根因分析
`BluetoothFragment.setTrafficSharingView()` 决定流量共享开关是否展示：原判断只有 `mPhonePairedDevices.isNotEmpty()`，再遍历找 `it.bluetoothDevice?.isPhoneCarConnect == true` 的设备。问题有二：① 配对缓存列表 `mPhonePairedDevices` 在蓝牙关闭后并不必然清空，设备对象上上一次互联留下的 `isPhoneCarConnect=true` 是历史脏数据（缺陷库"历史数据未清除"）；② 判断链里完全没有"当前连接类型"这一必要条件，`DeviceConnectManager.getCurrentConnectType()` 为 0（无任何互联）时也不影响展示。脏数据 + 缺失连接态条件，导致无连接时开关仍可显示并可操作。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt（2 文件 +2/-1）
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ isSameDevice(device: CachedBluetoothDevice, btAddr: String)
         if (TextUtils.equals(device.address, btAddr) || TextUtils.equals(device.deviceId, btAddr)) {
             device.phoneCarConnectionType = 0
+            device.setIsPhoneCarConnect(false)
             return true
         }
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ setTrafficSharingView()
-        if (mPhonePairedDevices.isNotEmpty()) {
+        if (mPhonePairedDevices.isNotEmpty() && DeviceConnectManager.getInstance().getCurrentConnectType() != 0) {
```

## 为什么能修复
双保险消除根因：`isSameDevice()` 在判定为同一设备并重置 `phoneCarConnectionType = 0` 的同时同步清掉 `isPhoneCarConnect`，堵住脏数据产生的源头；`setTrafficSharingView()` 增加 `getCurrentConnectType() != 0` 前置条件，即使列表残留脏数据，无连接时开关也必然隐藏。风险很低；隐患是 `isSameDevice` 的调用点如果在"断开又立刻重连"的场景先被触发，会短暂清标志，依赖后续 CONNECTED 事件再置回（与 147099cb 的事件链路衔接）。

## 复盘与经验
- 展示条件要包含"业务前提"（连接类型非 0），不能只依赖数据自身状态；数据驱动 UI 时，脏数据的兜底判断必须存在。
- 修复脏数据类缺陷的标准组合拳：源头清理（状态重置时把关联标志一并复位）+ 出口校验（显示/操作前再验业务前提）。
- 偶现(低于10%)问题多发生在"关闭/断开"路径的状态残留，测试时应覆盖互联断开、蓝牙关闭、页面停留刷新等组合时序。
