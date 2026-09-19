# 无单号 · 手车互联开发：修改HiCar连接（Setting 侧状态机收紧 + 连接操作下沉）

- **提交**：`407c86d1` | 2026-06-27 | dufan | Setting | 类型：手车互联开发提交（[bugfix] 标签、影响等级 D、测试范围"无"，实为开发迭代）
- **缺陷库**：未关联单号

## 问题
无对应缺陷单。此前 `DeviceConnectManager` 对 HiCar/CarLink 会话状态的处理是"非连接即断开"：任何非 `DEVICE_CONNECTED` 的状态（含中间态）都会清空 `mProductName`、`mCurrentConnectType=0` 并广播断开；同时手机车互联（HiCar/CarLink/CarPlay）的连接/断开/删除操作散落在 `BluetoothFragment` 的 item 点击分支里且大量空实现。

## 根因分析
以 diff 实际内容为准，本提交做了两类结构修正。一是事件语义收紧：`onSessionStateChanged` 由 `if (state == DEVICE_CONNECTED) {...} else {广播断开}` 改为 `when` 只对 `HiCarConstants.SessionState.DEVICE_CONNECTED` 与 `DEVICE_DISCONNECT`（CarLink 对应 `DEVICE_DISCONNECTED`）两个明确状态做处理，避免会话建立/握手过程中的中间态被误判为"已断开"而刷新 UI、写坏 `SPUtils` 里的 productName。二是职责下沉：新增 `com.yadea.setting.utils.BluetoothUtil`，把按 `phoneCarConnectionType`（1 占位 / 2 HiCar / 3 CarLink）分发 connect/disconnect/delete 的 `operationPhoneCar()` 和点击设备弹窗 `showClickDeviceDialog()` 收敛为工具方法，`BluetoothFragment` 瘦身约 178 行。另附带删除了误入库的 `.gradle/7.5/checksums` 构建产物。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt（新增，202 行）、.gradle/7.5 构建产物删除

```diff
--- application/Setting/.../init/DeviceConnectManager.kt
@@ HiCar 会话状态：else 兜底 → 明确双态
-            if (state == HiCarConstants.SessionState.DEVICE_CONNECTED) {
-                ...通知 connected...
-            } else {
-                mProductName = ""
-                mCurrentConnectType = 0
-                ...通知 disconnected...
-            }
+            when (state) {
+                HiCarConstants.SessionState.DEVICE_CONNECTED -> { ...通知 connected... }
+                HiCarConstants.SessionState.DEVICE_DISCONNECT -> {
+                    mProductName = ""
+                    mCurrentConnectType = 0
+                    ...通知 disconnected...
+                }
+            }
```

```diff
--- application/Setting/.../utils/BluetoothUtil.kt（新增）
@@ 按互联类型分发操作
+        fun operationPhoneCar(device: CachedBluetoothDevice, isNeedDelete: Boolean = false) {
+            when (device.phoneCarConnectionType) {
+                2 -> if (device.isPhoneCarConnect) {
+                    DeviceConnectManager.getInstance().getHiCarDeviceListManager()?.apply {
+                        disconnectHiCar(device.address)
+                        if (isNeedDelete) deleteHiCarDevice(device.address)
+                    }
+                } else { ...connectHiCar... }
+                3 -> ...disconnectCarLink / connectCarLink...
+            }
+        }
```

## 为什么能修复
状态机收紧消除了"中间态触发假断开"的根源——UI 与 `mProductName` 持久化只响应确定的连接/断开事件；`operationPhoneCar` 统一入口补齐了此前 HiCar 连接分支为空实现导致的"点击无效果"，并支持断开即删除（`isNeedDelete`）。隐患：`when` 不带 else 分支意味着新增未处理状态时静默忽略；`BluetoothUtil` 中类型 1（CarPlay）分支仍是空 if/else，功能未完成。

## 复盘与经验
- **状态回调不要用 else 兜底解释**：SDK 会话状态多为多值枚举，`非A即B` 的二值化会把中间态当断开处理；应显式列出目标状态、其余忽略。
- **连接类操作要收敛单入口**：connect/disconnect/delete 三操作 × N 种互联协议，散在 UI 分支必然出现空实现与不一致，工具类单入口（`operationPhoneCar`）才能保证行为对称。
- **协议类型用整型魔法数（1/2/3）分发**易错，后续应换成枚举或 sealed class。
