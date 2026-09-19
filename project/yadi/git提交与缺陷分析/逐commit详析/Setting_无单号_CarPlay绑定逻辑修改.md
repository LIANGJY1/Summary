# 无单号 · CarPlay 绑定逻辑修改

- **提交**：`9961672f` | 2026-07-22 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
调整已配对苹果设备点击后的 CarPlay 建立路径：设备已在 CarPlay 设备列表中时，不再直接调 `connectCarPlay(address)`，而是走普通蓝牙 `connect(true)`，由既有的蓝牙连接监听链路自然触发 CarPlay 会话；其余大量为格式化改动。

## 实现结构
单文件 18+/5-：`utils/BluetoothUtil.kt` 的 `showPairedDialog` 分支——`carPlayDeviceList.firstOrNull { it.btAddr == address }` 命中时的动作从 `DeviceConnectManager.getInstance().connectCarPlay(it.address)` 改为 `it.connect(true)`；未命中时保留 `checkWirelessCarPlayAvailability` 探测 + "连接方式选择"弹窗（确认→connectCarPlay，取消→cancelWirelessCarPlay+蓝牙连接）。另有 `connectOperationDevice`/`showConnectHintDialog` 的纯格式化（多行参数排版）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                             DeviceConnectManager.getInstance().mCarPlayDeviceManager?.carPlayDeviceList?.firstOrNull { device ->
                                 device.btAddr == it.address
                             }?.apply {
-                                DeviceConnectManager.getInstance().connectCarPlay(it.address)
+                                it.connect(true)
                             } ?: run {
```
实现讲解：此前"蓝牙已连 + 列表已登记"的设备点击会重复发起 CarPlay 连接指令，与蓝牙重连流程并发，容易出现会话状态机错乱。改为只做蓝牙连接、让 `DeviceConnectManager` 的连接回调去拉起 CarPlay，单一路径建立会话。此改动与同日 `3f19a717` 的互斥仲裁、次日 `09f97aa0` 的"删除设备后刷新列表"共同构成 CarPlay 连接体验的系列修补。

## 复盘与要点
- 同一条连接链路上"直达接口"与"经状态机回调"并存时，必然出现重复发起；收敛到单一触发点是与 `3f19a717` 一致的设计方向。
- `checkWirelessCarPlayAvailability` 弹窗分支保留直达 `connectCarPlay`（用户显式选择 CarPlay 时），直达接口并未消失，只是使用场景收窄到"用户确认后"，语义更合理。
- 大段纯格式化与功能改动混在一个提交，diff 噪音偏高，review 时需区分（本次功能实质只有 1 行）。
