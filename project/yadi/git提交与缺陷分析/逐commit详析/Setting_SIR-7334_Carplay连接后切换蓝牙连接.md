# SIR-7334 · Carplay 连接后无法切换蓝牙连接

- **提交**：`effc2762` | 2026-09-02 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
手机已通过 Carplay 连接车机后，在蓝牙列表里点该设备连接，蓝牙图标闪烁一下即回退，始终无法切换为蓝牙连接。

## 根因分析
同一台手机不能同时保持 Carplay 与蓝牙音频两种连接（[why]："同设备无法同时连接carplay和蓝牙"）。原逻辑在 `BluetoothUtil` 收到连接请求时直接 `device.connect(true)`，完全没有感知当前 `SCurrentThirdDevice` 正处于 Carplay 连接态（`phoneCarConnectionType == 1`），下层因连接冲突拒绝/静默失败，UI 层图标只闪不动。此外 `BluetoothFragment` 里还有一段旧逻辑：CARPLAY 设备连上时若当前在 `ConnectChildDialogActivity` 直接 `finish()` 弹窗，会把用户意图中的连接流程提前掐断（本次一并注释停用）。

## 关键代码修改
改动文件：BluetoothUtil.kt、BluetoothFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                                 } else {
-                                    device.connect(true)
+                                    if (SCurrentThirdDevice != null && SCurrentThirdDevice!!.phoneCarConnectionType == 1) {
+                                        DeviceConnectManager.getInstance().setOnDisconnectDeviceListener(object : DeviceConnectManager.OnDisconnectDeviceListener {
+                                            override fun onDisconnectDevice() {
+                                                device.connect(true)
+                                            }
+                                        })
+                                        operationPhoneCar(SCurrentThirdDevice!!, isForceDisconnect = true)
+                                    } else {
+                                        device.connect(true)
+                                    }
                                 }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
-        if (deviceType == CARPLAY && isConnected && activity is ConnectChildDialogActivity) {
-            activity?.finish()
-            return
-        }
+//        （整段注释停用：Carplay 连接成功不再直接关闭弹窗）
```

## 为什么能修复
连接蓝牙前先检查 `SCurrentThirdDevice.phoneCarConnectionType == 1`（Carplay 态）：是则先通过 `operationPhoneCar(isForceDisconnect = true)` 强制断开 Carplay，并挂 `DeviceConnectManager.OnDisconnectDeviceListener`，在 `onDisconnectDevice` 回调里再发起 `device.connect(true)`——把"直接连"改为"先断后连"的两步时序，消除了同设备连接冲突这一根因。隐患：回调是一次性语义但代码未置空 listener，重复切换可能重复触发 `connect`；若断开 Carplay 失败则蓝牙连接永远不会发起，缺少超时兜底。

## 复盘与经验
- 存在互斥约束的连接类资源（同设备 Carplay/蓝牙互斥）必须在业务层显式编排"断开→回调→再连接"时序，底层不会替你做冲突仲裁的 UX。
- 监听器回调衔接异步流程时，要考虑失败分支（断开失败、回调丢失），最好加超时或结果回调，否则用户点击无响应。
- 注释掉的死代码应及时删除而非留置，避免后人误读当前生效逻辑。
