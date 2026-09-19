# SIR-6185 · 发起CarPlay连接成功后蓝牙OSD浮窗卡死

- **提交**：`c27ba77a` | 2026-08-21 | dufan | Setting | bugfix
- **缺陷库**：等级 A · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
发起 CarPlay 连接，连接成功后承载蓝牙连接流程的 OSD 浮窗（`ConnectChildDialogActivity`）不消失，界面卡住无法继续操作。

## 根因分析
缺陷库根因为"处理逻辑异常"。三条链路配合出问题：其一，连接结果回调 `BluetoothFragment` 的状态更新方法在 CarPlay 连上后仍按普通蓝牙设备处理（更新列表/等待删除设备 id），不会关闭宿主 `ConnectChildDialogActivity`，OSD 浮窗因此滞留屏幕表现为"卡死"；其二，`DeviceConnectManager.setDeviceConnectStatus()` 里 CARPLAY 非连接状态直接 `return`，挂着 `mDeleteDeviceListener` 不触发也不清理（SIR-6130 修复时把删除动作移进了 `handleDeviceDisconnect`，遗漏了这个提前 return 的分支），状态回调链悬空；其三，`BluetoothUtil` 的断开/取消配对流程在 CarPlay 建链过程中立即执行 `device.disconnect(); device.unpair()`，与建链竞争。修复分别处理：CARPLAY 连接成功且宿主是对话框 Activity 时直接 `finish()` 并 return；CARPLAY+`SESSION_STATUS_DEACTIVATED` 时在 return 前触发并清空 `mDeleteDeviceListener`；断开/取消配对前 `delay(500.milliseconds)` 给建链流程让出窗口。

## 关键代码修改
改动文件：DeviceConnectManager.kt、BluetoothFragment.kt、BluetoothUtil.kt（3 文件，+12/-3）
```diff
@@ application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt @@
+        if (deviceType == CARPLAY && isConnected && activity is ConnectChildDialogActivity) {
+            activity?.finish()
+            return
+        }
         deleteDeviceId?.let {
```
```diff
@@ application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt @@
             } else {
+                if (deviceType == CARPLAY && status == CarPlayConstants.SessionStatus.SESSION_STATUS_DEACTIVATED) {
+                    mDeleteDeviceListener?.onDeleteDevice()
+                    mDeleteDeviceListener = null
+                }
                 return
             }
```
```diff
@@ application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt @@
                         operationPhoneCar(device, true)
                         if (device.device != null) {
+                            delay(500.milliseconds)
                             device.disconnect()
                             device.unpair()
                         }
```

## 为什么能修复
浮窗的关闭被显式绑定到"CarPlay 连接成功"这一事件上，不再依赖普通蓝牙流程的列表刷新路径，OSD 不再滞留；DEACTIVATED 分支补触发删除监听，修复了状态回调链上的悬挂引用；500ms 延迟降低断开/取消配对与建链的竞态。隐患：固定 500ms 是经验值，慢设备上可能不够；`activity?.finish()` 判断依赖浮窗确实是 `ConnectChildDialogActivity`，从其它入口进入 CarPlay 连接时不会自动关闭。

## 复盘与经验
- "连接成功"类 A 级卡死，多为承载流程的临时 UI（浮窗/对话框）没有绑定到成功事件的关闭路径——每个流程都要有明确的 exit。
- 状态机函数里的提前 `return` 分支是清理逻辑的盲区：SIR-6130 把删除动作收进 `handleDeviceDisconnect` 时漏掉了 return 分支，本次补齐——同一状态链的修改要点查所有出口。
- 蓝牙断开/取消配对与协议建链并存时要有时间窗隔离，`delay` 虽朴素但有效；根治需要事件化的连接状态互斥。
