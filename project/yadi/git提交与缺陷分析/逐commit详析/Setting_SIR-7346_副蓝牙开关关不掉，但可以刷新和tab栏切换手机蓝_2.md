# SIR-7346（补充）· 副蓝牙开关关不掉（蓝牙回调慢导致状态被刷新逻辑拉回）

- **提交**：`933f7fb7` | 2026-09-14 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- 说明：同一单号的前置修复见 `447b28a8`（2026-09-11）；本提交为三天后的补充修复，问题闭环以两者合并理解。

## 问题
副蓝牙（手机蓝牙）开关关闭后开关状态被"拉回"开启，表现为关不掉；关闭过程中还能触发刷新和 tab 栏切换手机蓝牙列表。

## 根因分析
前置修复（`447b28a8`）只改了 `BluetoothFragment`，而 `BluetoothAnwFragment`（副蓝牙 ANW 通道页面）仍存在多处与"关闭"竞争的逻辑：关闭确认后仅发起 `TYPE_DISABLE`，开关 UI 依旧等待底层回调；提交信息根因"收到回调慢"指即回调迟迟不来时页面残留开启态。同时 `lazyLoadData()` 与 `onHiddenChanged` 都带"延迟 300ms 自动 `startScan()`"的逻辑，关闭蓝牙过程中页面可见性变化会重新拉起扫描/刷新，界面看起来像"关了又被打开"；`tvNoConnect` 被置 VISIBLE 还会闪现"未连接"空态。此外 `BluetoothFragment` 的可用设备列表把名称带 `HEADSET_NAME_SUFFIX` 的设备（副蓝牙耳机类）当手机设备加入，切 tab 时列表内容串扰。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`
```diff
// BluetoothAnwFragment.kt：关闭确认回调——乐观关闭+置灰+先停扫描
                 onConfirm = {
+                    mBindingHeader.sw.isChecked = false
+                    mBindingHeader.sw.enableOverlay(true)
                     mIsUserClosingBluetooth = true
                     BtAnwManager.saveDeviceList(BtAnwManager.getInstance().mPairedDevices)
+                    BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_STOP_INQUIRY)
                     BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_DISABLE)
                     showRefresh(false)
-                    mBinding.tvNoConnect.visibility = View.VISIBLE
+                    mBinding.tvNoConnect.visibility = View.GONE
                 }
```
```diff
// BluetoothAnwFragment.kt：移除自动重扫（lazyLoadData/onHiddenChanged 同型，均注释）
-            lifecycleScope.launch {
-                delay(300.milliseconds)
-                if (BtAnwManager.getInstance().isBtOn && !mIsUserClosingBluetooth) {
-                    startScan()
-                }
-            }
```
```diff
// BluetoothAnwFragment.kt：BT_STATE_ON 回调不再触发整页 lazyLoad
-            lazyLoadData()
+            setDeviceData(true)
```
```diff
// BluetoothAnwFragment.kt：onDestroy 时停扫描
+        if (BtAnwManager.getInstance().isBtOn) {
+            BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_STOP_INQUIRY)
+        }
```
```diff
// BluetoothFragment.kt：可用设备过滤副蓝牙（耳机后缀）设备
-                    if (it.deviceType == BluetoothDeviceType.CELL_PHONE) {
+                    if (it.deviceType == BluetoothDeviceType.CELL_PHONE  && !it.name.contains(HEADSET_NAME_SUFFIX)) {
```

## 为什么能修复
关闭确认时立即 `isChecked = false` + `enableOverlay(true)` 置灰，UI 不再等慢回调；先 `TYPE_STOP_INQUIRY` 停掉扫描再 `TYPE_DISABLE`，避免扫描活动拖慢/干扰关闭流程，并删除了页面显隐时"300ms 后自动重扫"的所有入口，关闭后不会再被扫描刷新"复活"；`tvNoConnect` 保持 GONE 消除关闭过程闪现空态；`BT_STATE_ON` 回调只刷数据不重扫，避免开关节奏被扫描打乱；`BluetoothFragment` 过滤耳机后缀设备后，切 tab 不再把副蓝牙设备混入手机蓝牙列表。隐患：大量逻辑用注释而非删除保留，可读性差；乐观置灰依赖后续 `STATE_OFF` 回调 `disableOverlay` 解除，若关闭彻底失败需要超时兜底恢复可点。

## 复盘经验
- 同一缺陷在多个同构 Fragment（BluetoothFragment/BluetoothAnwFragment）出现时，修复必须逐个落地并交叉检查，只改一个等于没修完。
- "页面可见即自动重扫"的隐式行为要与开关操作互斥，否则任何状态切换都会被扫描流程干扰。
- 关闭类异步操作前先停掉相关活动（扫描/心跳/轮询），能显著缩短回调链路，从源头缓解"回调慢"。
- 注释停用逻辑应尽快清理成删除+说明，避免后续维护者误恢复。
