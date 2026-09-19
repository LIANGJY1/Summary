# SIR-8398 · 偶发蓝牙已连接设备显示在未配对设备列表

- **提交**：`fe46fccb` | 2026-09-15 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 偶现-低于10% · 状态 待测试验证 · 域 车控车设

## 问题
偶发场景下蓝牙手机已连接，但设备信息卡片出现在"未配对设备（可用设备）"列表里，而不是已配对列表。

## 根因分析
`BluetoothFragment` 组装列表时，已配对设备来自 `BluetoothUtil.handlePairData(mPhonePairedDevices, isClose)` 的缓存数据。当缓存与系统绑定状态失步时，某个已配对设备会漏出 `mPhonePairedDevices`，随后在"可用设备=全部扫描结果 - 已配对地址"的过滤中因 `pairedAddresses` 不含它而漏剔除，于是出现在未配对区。修复思路是增加一道"自愈"：直接读设备自身的 `isBonded`（来自系统蓝牙栈，权威数据），凡 `isBonded` 为真、类型为 `BluetoothDeviceType.CELL_PHONE` 且不在已配对缓存地址集中的可用设备，重新归位到 `mPhonePairedDevices`，`pairedAddresses` 随之修正。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@             mPhonePairedDevices.clear()
             mPhoneAvailableDevices.clear()
             BluetoothUtil.handlePairData(mPhonePairedDevices, isClose)
+            // pairedDevices 缓存失步时会漏掉已配对设备，而该设备必然出现在 availableDevices 中（漏剔除）。
+            // 用设备自身 isBonded（直读系统，权威）判据将其归位到已配对区；下方 pairedAddresses 随之修正，
+            if (mWxBtManager.isEnable && !isClose) {
+                val healedAddresses =
+                    mPhonePairedDevices.mapNotNull { it.bluetoothDevice?.address }.toSet()
+                availableDevices
+                    .filter {
+                        it.isBonded && it.deviceType == BluetoothDeviceType.CELL_PHONE &&
+                                it.address !in healedAddresses
+                    }.forEach { mPhonePairedDevices.add(MultiBluetoothDevice(1, it)) }
+            }
             val pairedAddresses =
                 mPhonePairedDevices.mapNotNull { it.bluetoothDevice?.address }.toSet()
```

## 为什么能修复
不再信任单一缓存来源：用系统侧 `isBonded` 作为最终判据做列表自愈，缓存漏掉的已配对设备必然从可用列表"搬回"已配对区，后续以 `pairedAddresses` 进行的剔除也就不再漏。副作用：`MultiBluetoothDevice(1, it)` 硬编码了已配对类型标记，且自愈只覆盖手机类型（CELL_PHONE），其他设备类型（如后续新增的头盔/耳机）若出现同类失步不会被归位。

## 复盘与经验
- "缓存列表 + 差集过滤"的经典坑：源缓存漏一条，差集就会把错误结果渲染出来；用权威源（系统 `isBonded`）做二次校验比修缓存更稳。
- 偶现蓝牙显示问题的通用排查路径：先怀疑两个数据源（系统栈回调 vs 本地缓存）失步的窗口期。
- 自愈逻辑按 `deviceType` 过滤是双刃剑：防止误归位，但新增设备类型时要记得扩展条件。
