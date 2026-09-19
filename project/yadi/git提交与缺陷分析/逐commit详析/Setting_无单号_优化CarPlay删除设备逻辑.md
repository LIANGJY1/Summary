# 无单号 · 优化 CarPlay 删除设备逻辑（先断连后删除的时序保证）

- **提交**：`b4b75611` | 2026-07-07 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
修正"删除 CarPlay 设备"的操作时序：不再同步调用 `deleteCarPlayDevice`，而是注册一次性 `OnDeleteDeviceListener`，等 CarPlay 会话真正断开（`setDeviceConnectStatus(CARPLAY, false)` 触发）后再执行删除，避免删了设备但中间件仍持有会话导致的复连/状态残留。

## 实现结构
- 修改 `init/DeviceConnectManager.kt`：新增 `mDeleteDeviceListener` 与 `OnDeleteDeviceListener` 接口、`setOnDeleteDeviceListener()`；CARPLAY 分支断开时先回零再回调监听器并置空（一次性语义）。
- 修改 `utils/BluetoothUtil.kt`：`operationPhoneCar` 删除分支改为"先 `disconnectCarPlay` + 注册监听器"，`deleteCarPlayDevice` 移入回调；另含格式化与 `!isOperationBluetooth` 条件合并。
- 修改 `ui/fragment/diologfragment/BluetoothFragment.kt`：`initView`/`onDestroyView` 复位 `SIsOperationBluetooth = false`，防跨页面残留。

数据流：用户删除设备 → `disconnectCarPlay` → 中间件会话 DEACTIVATED → `setDeviceConnectStatus(CARPLAY,false)` → `onDeleteDevice()` → `deleteCarPlayDevice(address)` → 中间件删设备 → 列表刷新。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ -783,7 +783,13 @@
             CARPLAY -> {
                 if (mCurrentConnectType != 1 && !isConnected) return
                 mProductName = ""
                 SPUtils.setParam("productName", mProductName)
-                mCurrentConnectType = if (isConnected) 1 else 0
+                if (isConnected) {
+                    mCurrentConnectType = 1
+                } else {
+                    mCurrentConnectType = 0
+                    mDeleteDeviceListener?.onDeleteDevice()
+                    mDeleteDeviceListener = null
+                }
             }
```

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -63,7 +63,13 @@
                             log("disconnectCarPlay:${device.address}, isNeedDelete:$isNeedDelete")
                             disconnectCarPlay(device.address)
                             if (isNeedDelete) {
-                                deleteCarPlayDevice(device.address)
+                                DeviceConnectManager.getInstance()
+                                    .setOnDeleteDeviceListener(object :
+                                        DeviceConnectManager.OnDeleteDeviceListener {
+                                        override fun onDeleteDevice() {
+                                            deleteCarPlayDevice(device.address)
+                                        }
+                                    })
                             }
```

实现讲解：核心是"把删除动作挂到断开事件之后"——同步删除时中间件可能因会话未释放而拒绝或产生复连竞态。用一次性监听器把删除延迟到状态机确认断开后，时序由状态机保证。回调后置空 `mDeleteDeviceListener` 避免重复触发，这是单次事件的单槽监听器写法（相比广播/Flow 更轻，但仅支持一个待删除设备）。

## 复盘与要点
- 对"先断开、后删除"这类有严格先后依赖的远端操作，把第二步挂到第一步的**状态回调**上比 `delay` 猜时长可靠（本文件别处也有 `delay(160.milliseconds)` 的猜时长写法，对比鲜明）。
- 单槽一次性监听器只够"同一时刻删一台设备"的场景；若列表批量删除或用户快速连续操作，前一个监听器会被覆盖，扩展时应改队列。
- `SIsOperationBluetooth` 在 Fragment 生命周期两端复位，说明该静态标记曾出现"离开页面后残留导致弹窗误判"，用静态变量承载跨页面状态要配套复位点。
