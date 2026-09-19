# SIR-8556 · 已配对列表发起第三台设备连接无响应
- **提交**：`79c1f0da` | 2026-09-18 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 车控车设

## 问题
前、后排耳机蓝牙已各连接一台设备（共 2 台）后，在已配对设备列表里点击第三台设备发起连接，界面无任何响应。

## 根因分析
`BluetoothAnwFragment.handleDeviceItemClick(device)` 原逻辑只分"已配对/未配对、已连接/未连接"四种情况处理：未连接设备直接 `connectHfp + connectA2dp` 发起连接。车机蓝牙同时最多维持两路耳机连接（前排 role=1 / 后排 role=0 各一），当两路都已占用时点击第三台设备走入了"直接发起连接"分支，底层因连接槽位已满连接失败，而 UI 层没有"需要先断开一台"的提示与置换逻辑——缺陷库 rc"无对应逻辑"。点击既不成功也无反馈，表现为"无响应"。

## 关键代码修改
改动文件：BluetoothAnwFragment.kt、BluetoothConnectHintDialog.kt（新增）、dialog_bluetooth_connect_hint.xml（新增）、strings.xml、values-en/strings.xml 等
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
+    private var mConnectFrontDevice: DeviceBean? = null
+    private var mConnectBackDevice: DeviceBean? = null
...
     private fun handleDeviceItemClick(device: DeviceBean) {
-        BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_AUTO_CONNECT_INTERRUPT)
+        if ((mConnectFrontDevice != null && mConnectFrontDevice != device) && (mConnectBackDevice != null && mConnectBackDevice != device)) {
+            BluetoothConnectHintDialog(mConnectFrontDevice!!, mConnectBackDevice!!,
+                object : BluetoothConnectHintDialog.Callback {
+                    override fun callback(chooseDevice: DeviceBean) {
+                        lifecycleScope.launch(ioDispatcher){
+                            BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_AUTO_CONNECT_INTERRUPT)
+                            delay(150.milliseconds)
+                            disconnectDevice(chooseDevice)
+                            delay(500.milliseconds)
+                            BtAnwManager.getInstance().connectHfp(device, true)
+                            BtAnwManager.getInstance().connectA2dp(device, true)
+                        }
+                    }
+                }).show(childFragmentManager, "BluetoothInfoDialog")
+        } else { /* 原四分支逻辑不变 */ }
```
（另：刷新配对列表时按 `role` 与 `isConnect` 填充 `mConnectFrontDevice`/`mConnectBackDevice`；蓝牙关闭时清空两个引用并 `mAdapter.setIsConnectTwoHelmet(false)`。新弹窗展示前排/后排设备名，单选后点"确认"回调被选中设备。）

## 为什么能修复
点击入口前置"双路已满"判断（两路已连接设备均存在且都不等于被点设备）后：未满场景走原逻辑不变；已满场景弹出 `BluetoothConnectHintDialog`（文案"已连接2台设备,新设备连接需要断开一台原有设备"），用户选择要断开的前排或后排设备后，按"中断自动连接 → 150ms → 断开被选设备 → 500ms → 对新设备 connectHfp + connectA2dp"的时序串行执行置换连接。点击必有响应，槽位竞争消除。副作用：断开与重连之间的 `delay` 写死 150/500ms 属经验时序，极端蓝牙栈延迟下置换可能不彻底；弹窗回调里 `mChooseDevice!!` 依赖必须先选择才允许确认（`tvConfirm.isEnabled` 控制）来保证非空。

## 复盘与经验
- "点击无响应"类缺陷的一半是"路径未覆盖的分支静默失败"：枚举资源约束（蓝牙连接槽位、并发上限）并给每个不可能组合显式处理或提示。
- 资源满载时的置换交互（选择断谁）比静默断开最早/最旧的设备更可控，但必须把断开→连接做成带延时的串行事务。
- 状态引用（`mConnectFrontDevice/Back`）要在列表刷新、蓝牙关闭等多处同步维护/清空，否则判断依据会过期——本提交在 `onBluetoothStateChanged` 关闭分支与列表重建处都做了清理。
