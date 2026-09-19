# 无单号 · 蓝牙手车互联主动操作增加互斥逻辑

- **提交**：`3f19a717` | 2026-07-22 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
在蓝牙设备列表页对"手机互联/蓝牙"两类点击做统一的互斥仲裁：同设备直连/断开，跨设备先（按矩阵弹窗）确认再强制断开旧连接；顺带删除散落的 `SIsOperationCarPlay` 标志与 Setting 侧 `productName` 残留双写。

## 实现结构
5 个文件（+204/-108）：
- `utils/BluetoothUtil.kt`：新增核心入口 `connectOperationDevice(fragmentManager, device, isOperationBluetooth)`——以 `SCurrentThirdDevice` 判定同设备/跨设备，同设备蓝牙走"已连断开、未连先断当前蓝牙再连"，跨设备构造 callback（`operationPhoneCar(it, isForceDisconnect = true)` + 断旧连新）；新增 `needShowSwitchDialog(current, target)` 弹窗判定矩阵（1=无线CarPlay 2=无线HiCar 3=无线CarLink 4=蓝牙）与 `showConnectHintDialog`（10 秒倒计时取消按钮 + 单例防重）；删除 `SIsOperationCarPlay` 静态标志及 Apple UUID 探测写入；
- `BluetoothFragment.kt`：删除 `handleThirdClick/handleBluetoothClick` 两个私有处理，点击事件直接进 `connectOperationDevice`；
- `DeviceConnectManager.kt`：删除 `mProductName` 成员、`handleDeviceConnect` 方法及 SP 写入（设备名存储已移交 Launcher 侧 GSetting，`92a3fb02`），连接时直接 `mCurrentConnectType = type`；
- `strings.xml`（中/英）：新增 `connect_new_device_hint` 等文案。

数据流：列表点击 → `connectOperationDevice` 仲裁 →（可选）确认弹窗 → 断旧设备/`operationPhoneCar` 发起互联 → 状态回调刷新 UI。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
+        fun needShowSwitchDialog(current: Int, target: Int): Boolean {
+            return when (current) {
+                1 -> target == 2 || target == 3
+                2 -> true
+                3 -> target == 1 || target == 2 || target == 3
+                4 -> target == 1 || target == 2
+                else -> false
+            }
+        }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
+            } else {
+                if (mSwitchDialog != null) {
+                    return
+                }
+                val callback: () -> Unit = {
+                    SCurrentThirdDevice?.let {
+                        operationPhoneCar(it, isForceDisconnect = true)
+                        if (it.isConnected) {
+                            it.disconnect()
+                        }
+                        if (isOperationBluetooth) {
+                            device.connect(true)
+                        } else {
+                            operationPhoneCar(device)
+                        }
+                    }
+                }
+                if (needShowSwitchDialog(SCurrentThirdDevice!!.phoneCarConnectionType,
+                        if (isOperationBluetooth) 4 else device.phoneCarConnectionType)) {
+                    showConnectHintDialog(childFragmentManager, SettingsUtils.getGSetting("connect_device_name"), callback)
+                } else {
+                    callback.invoke()
+                }
```
实现讲解：把原来散在 Fragment 里的直连逻辑上收到工具类，并统一挂"当前设备 vs 目标设备"二维判定：同设备幂等切换，跨设备按 `needShowSwitchDialog` 矩阵决定是否打断用户。弹窗以 `mSwitchDialog != null` 做单例守卫 + 10 秒 CountDownTimer 自动取消，防连点重复弹。

## 复盘与要点
- `needShowSwitchDialog` 用 5x5 矩阵把产品规则（哪种互联切哪种要确认）集中表达，新增互联类型时只改一处，是可复用的规则表手法。
- 风险 1：跨设备分支里 `SCurrentThirdDevice!!` 强解包，若该静态变量在点击瞬间被清空（如设备移除回调）会 NPE。
- 风险 2：`connectOperationDevice` 用 `deviceId/address` 双条件判同设备，混合 CarLink 设备（只有 deviceId）时判断分支不对称，依赖 `== null` 兜底；静态 `SCurrentThirdDevice` 的生命周期管理仍是隐患。
