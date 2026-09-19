# SIR-7171 · 苹果手机配对后 CarPlay 连接不上

- **提交**：`4bf04eac` | 2026-09-11 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 待测试验证 · 域 手车互联

## 问题
苹果手机发起蓝牙配对、车机端确认配对成功后，进入 CarPlay 连接流程时收不到 CarPlay 连接提示弹窗，CarPlay 连不上，设备只按普通蓝牙连接处理。

## 根因分析
`BluetoothUtil.showPairedDialog` 原逻辑在配对完成后立即读取 `it.device.uuids.contentToString()` 并判断是否包含 `APPLE_DEVICE_UUID` 来决定走 CarPlay 弹窗分支还是普通蓝牙连接分支。但配对刚完成时协议栈的 SDP 服务发现往往尚未结束，此时 `BluetoothDevice.getUuids()` 返回空数组——苹果设备被误判为"非苹果"，直接落入 `connect(true)` 普通蓝牙分支，CarPlay 弹窗流程被跳过。这是一个典型的时序竞态：uuids 是否为空取决于 SDP 完成时刻与本方法执行时刻的先后，因此表现为低概率复现。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt`
```diff
-                    if (it.device.uuids.contentToString().contains(APPLE_DEVICE_UUID)) {
-                        ...（原 CarPlay 弹窗分支）...
-                    } else {
-                        it.connect(true)
-                    }
+                    if (isAppleDevice(device.device)) {
+                        handleAppleCarPlay(device, activity, childFragmentManager)
+                        return@launch
+                    }
+                    // 配对刚完成时 SDP 可能尚未查询完成，device.uuids 为空，
+                    // 不能据此把苹果设备误判为非苹果而只走普通蓝牙连接。
+                    // 等待系统 ACTION_UUID 广播（SDP 完成后下发）再判定是否支持 CarPlay。
+                    if (device.device?.uuids.isNullOrEmpty()) {
+                        waitForUuidThenDecideCarPlay(device, activity, childFragmentManager)
+                        return@launch
+                    }
+                    device.connect(true)
```
```diff
+            val receiver = object : BroadcastReceiver() {
+                override fun onReceive(context: Context?, intent: Intent?) {
+                    if (intent?.action != BluetoothDevice.ACTION_UUID) return
+                    val bd = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
+                    if (bd == null || bd.address != targetAddress) return
+                    unregisterUuidReceiver(this)
+                    CoroutineScope(ioDispatcher).launch {
+                        if (isAppleDevice(bd)) handleAppleCarPlay(device, activity, childFragmentManager)
+                        else device.connect(true)
+                    }
+                }
+            }
+            uuidReceiver = receiver
+            appContext.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID))
```
新增伴生对象字段 `uuidReceiver`/`uuidTimeoutRunnable`/`uuidHandler` 与 `UUID_WAIT_TIMEOUT_MS = 3_000L`：3 秒超时兜底后按普通蓝牙连接处理，避免界面无限挂起。

## 为什么能修复
把"立即读 uuids 并一次判定"改为三段式：uuids 齐全→立即判定；uuids 为空→注册 `BluetoothDevice.ACTION_UUID` 广播等待 SDP 完成后系统下发真实 UUID 再判定；3 秒超时兜底防止广播不来导致流程卡死。原 CarPlay 弹窗分支整体抽成 `handleAppleCarPlay` 复用，逻辑不变。隐患：全局静态 receiver 若多次触发需先反注册旧监听（代码已做），超时分支与广播分支可能先后到达需幂等（超时 Runnable 里通过 `uuidReceiver == receiver` 判定规避）。

## 复盘与经验
- 蓝牙 `getUuids()` 依赖 SDP 缓存，配对完成后立刻读取大概率拿到空数组；凡是"配对后按 UUID 分流"的逻辑都必须等 `ACTION_UUID` 或超时。
- 对异步不确定事件要"事件驱动 + 超时兜底"双保险，且超时后应降级到保守路径（普通蓝牙连接）而不是挂死。
- 大段 if/else 分支先抽方法再改造，diff 更清晰，也降低重构引入回归的风险（本例 CarPlay 弹窗分支原样搬迁）。
- 该提交为 cherry-pick（源自 `3d4af35b`），说明问题先在其它分支修复后回迁。
