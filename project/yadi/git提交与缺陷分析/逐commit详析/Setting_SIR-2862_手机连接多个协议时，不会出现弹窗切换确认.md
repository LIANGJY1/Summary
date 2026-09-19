# SIR-2862 · 手机连接多个协议时不弹切换确认弹窗

- **提交**：`7a84e55b` | 2026-08-06 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联（rc：判断逻辑有误 / sol：修改判断逻辑）

## 问题
手机与车机已通过某个手车互联协议连接后，再以另一协议/新设备发起连接时，本应弹出的"是否断开当前连接并切换至新设备"确认弹窗不出现，直接静默处理。

## 根因分析
弹窗逻辑依赖两个判断，各有一处错误：
1. `BluetoothFragment` 中选取"当前已连接设备" `SCurrentThirdDevice` 的条件是 `isPhoneCarConnect == true && phoneCarConnectionType in 2..3`，两个条件**耦合在一起**。当已连接设备的协议类型不在 2..3 区间（如其它互联协议）时，`SCurrentThirdDevice` 不会被赋值，保持 null。
2. `BluetoothUtil.showConnectHintDialog()` 里 `isSameDevice` 的兜底分支是 `SCurrentThirdDevice == null || deviceId相同 || address相同`——当前设备为 null 时直接判定"同一设备"，于是跳过弹窗。此外 deviceId/address 的相等比较没有空值防护，两端都是空/null 时 `null == null` 也成立，同样误判为同设备。
两条路径殊途同归：本应视为"新设备"的连接被误判成"同一设备"，确认弹窗被跳过。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
             mPhonePairedDevices.forEach {
-                if (it.bluetoothDevice?.isPhoneCarConnect == true && it.bluetoothDevice.phoneCarConnectionType in 2..3) {
+                if (it.bluetoothDevice?.isPhoneCarConnect == true) {
                     SCurrentThirdDevice = it.bluetoothDevice
-                    isShow = true
+                    if (it.bluetoothDevice.phoneCarConnectionType in 2..3) {
+                        isShow = true
+                    }
                     return@forEach
                 }
             }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
             val isSameDevice = SCurrentThirdDevice == null ||
-                    SCurrentThirdDevice?.deviceId == device.deviceId || SCurrentThirdDevice?.address == device.address
+                    (SCurrentThirdDevice?.deviceId == device.deviceId && !TextUtils.isEmpty(device.deviceId))
+                    || (SCurrentThirdDevice?.address == device.address && !TextUtils.isEmpty(device.address))
```
另外 `BluetoothFragment` else 分支中 `SCurrentThirdDevice = null` 的重置被注释保留（避免列表瞬时为空时把当前设备清掉，重回 null 误判）。

## 为什么能修复
修复把"是否已连接设备"与"是否特定协议"解耦：只要 `isPhoneCarConnect` 为真就登记 `SCurrentThirdDevice`，弹窗比较时拿到的是真实的已连接设备而非 null，`isSameDevice` 的 null 兜底不再被误触发；deviceId/address 比较加上非空校验后，空 ID 的两个不同设备不会再被判同。三层修正共同保证"新协议/新设备"必然走确认弹窗。副作用：`SCurrentThirdDevice = null` 被注释后该变量生命周期变长，若设备真正断开需依赖其它路径刷新，存在引用过期设备的可能。

## 复盘与经验
- 复合条件里"身份判断"与"业务过滤"耦合（`连接 && 类型 in 区间`）会让身份信息丢失，引发下游兜底分支误判，条件要分层。
- `X == null` 视作"同一设备"的兜底语义极其危险，null 应该意味着"未知"而非"相同"；等值比较前先做 isEmpty 防护是 Kotlin/Java 互操作的基本功。
- 弹窗类 bug 的排查顺序：先确认弹窗函数是否被调用，再回溯它的前置布尔条件，通常错在条件而非 UI。
