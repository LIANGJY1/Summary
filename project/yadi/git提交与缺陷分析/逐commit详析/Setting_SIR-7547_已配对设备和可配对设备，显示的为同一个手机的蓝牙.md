# SIR-7547 · 已配对与可配对设备列表显示同一手机名称
- **提交**：`fa22245a` | 2026-09-09 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 偶现-低于10% · 状态 待测试验证 · 域 车控车设（根因：未做去重处理）

## 问题
手机蓝牙设置页中，"已配对设备"与"可配对设备"两个列表同时出现同一台手机的名称，数据重复。

## 根因分析
`BluetoothFragment` 刷新列表时：`BluetoothUtil.handlePairData(mPhonePairedDevices, isClose)` 填充已配对列表；随后扫描结果 `availableDevices` 中类型为 `BluetoothDeviceType.CELL_PHONE` 的设备被无条件加入 `mPhoneAvailableDevices`。BLE/经典蓝牙扫描会对已配对设备继续上报（手机断连后仍在广播），两个列表的数据源没有交集过滤，同一 `address` 的设备同时出现在已配对与可配对两个分组中。缺陷库根因"未做去重处理"准确。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -413,13 +413,17 @@
             mPhonePairedDevices.clear()
             mPhoneAvailableDevices.clear()
             BluetoothUtil.handlePairData(mPhonePairedDevices, isClose)
+            val pairedAddresses =
+                mPhonePairedDevices.mapNotNull { it.bluetoothDevice?.address }.toSet()
             if (mWxBtManager.isEnable && mBindingHeader.sw.isChecked) {
                 availableDevices.forEach {
                     if (it.deviceType == BluetoothDeviceType.CELL_PHONE) {
                         log("availableDevices: $it--${availableDevices.size}")
-                        mPhoneAvailableDevices.add(
-                            MultiBluetoothDevice(1, it)
-                        )
+                        if (it.address !in pairedAddresses) {
+                            mPhoneAvailableDevices.add(
+                                MultiBluetoothDevice(1, it)
+                            )
+                        }
                     }
                 }
             }
```

## 为什么能修复
填充可配对列表前先把已配对设备的 MAC 地址（`bluetoothDevice?.address`）收集成 `Set`，扫描结果逐条用 `it.address !in pairedAddresses` 过滤，同一设备不会再进入两个列表；Set 哈希查找 O(1)，扫描量级下无性能问题。`mapNotNull` 顺带跳过 address 为空的异常条目。隐患：以 MAC 地址为唯一键，若同一手机以不同地址（如双蓝牙 profile 地址不同）出现仍可能重复，但常规场景地址唯一；过滤只针对 CELL_PHONE 类型，其他类型重复需另行确认。

## 复盘与经验
- 两个列表的数据源若来自不同通道（配对缓存 vs 实时扫描），合并展示前必须按业务唯一键（MAC 地址）做交集过滤，这是蓝牙/周边设备列表的标准去重模式。
- 去重放在"填充列表"的单一路径里（本例一次 Set 构建+过滤），比在每个 UI 使用点各自处理更可靠。
- 偶现类列表问题多由扫描时序（设备断连后仍在广播）触发，review 扫描→展示链路时应专门考虑已配对设备再次被扫到的场景。
