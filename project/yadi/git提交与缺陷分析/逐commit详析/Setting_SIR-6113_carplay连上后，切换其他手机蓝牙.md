# SIR-6113 · CarPlay连接后切换其他手机蓝牙，车机与手机反复连接又断开

- **提交**：`d32fa9cd` | 2026-08-21 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
CarPlay 已连接后，切换连接另一台手机的蓝牙时，手机和车机陷入"连接成功又立即断开"的循环，无法稳定连接。

## 根因分析
`BluetoothUtil` 的已配对设备点击连接路径上，旧逻辑有一个分叉：`mWxBtManager.currentConnectDevice != null`（当前有互联设备在连）时走 `connectOperationDevice()` 完整切换流程，否则直接 `it.connect(true)` 裸连接。问题在于 `currentConnectDevice` 的赋值依赖互联会话状态回调，CarPlay 刚连上或状态切换间隙该值可能尚未同步/已被清空，此时代码走了裸连接分支——新设备在没有先断开旧 CarPlay 链路、没有注册 `SConnectCallback` 衔接回调的情况下发起连接，两条蓝牙链路（互联协议与经典蓝牙）互相抢占，双方反复连接又断开。缺陷库"连接逻辑有误"即指这条绕过统一切换逻辑的分支。修复删除该分叉：已配对设备连接一律走 `connectOperationDevice(childFragmentManager, it, true)`。该函数内部已含完整处理：同设备时若 `mWxBtManager.currentConnectDevice` 存在且非目标设备，先 `disconnect()` 旧设备并设置 `BluetoothFragment.SConnectCallback` 在断开后回调 `device.connect(true)`，保证"先断后连"的时序。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt（+1/-5）
```diff
@@ application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt @@
                     lifecycleScope.launch(Dispatchers.IO) {
                         if (it.isBonded) {
-                            if (mWxBtManager.currentConnectDevice != null) {
-                                connectOperationDevice(childFragmentManager, it, true)
-                            } else {
-                                it.connect(true)
-                            }
+                            connectOperationDevice(childFragmentManager, it, true)
                         } else {
```

## 为什么能修复
所有连接请求统一收敛到 `connectOperationDevice` 的"先断旧设备（经回调确认断开后）再连新设备"时序里，消除了裸连接与互联链路争抢导致的连接振荡；不再依赖 `currentConnectDevice` 是否及时同步这一脆弱前置条件。隐患：所有连接都过切换逻辑后，纯蓝牙场景也可能多一步判断，行为依赖 `SCurrentThirdDevice` 状态准确性。

## 复盘与经验
- "根据某个缓存状态分叉到不同处理路径"是连接类 bug 的常见来源：缓存状态有同步窗口，分叉不如统一走同一条带完整时序处理的路径。
- 蓝牙/互联切换必须"先断后连"且有完成回调衔接（SConnectCallback 模式），并行发起连接必然振荡。
- 修复有时是做减法：删掉 4 行分支让所有流量走同一条成熟路径，比新增逻辑更安全。
