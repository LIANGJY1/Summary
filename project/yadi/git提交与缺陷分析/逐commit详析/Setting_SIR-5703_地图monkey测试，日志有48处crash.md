# SIR-5703 · 地图 monkey 测试 48 处 crash（配对时 device 为空的 NPE）

- **提交**：`f1666de8` | 2026-08-10 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 系统需求（rc：空指针异常 / sol：修改空指针）

## 问题
地图应用 monkey 压测期间，Setting 进程日志累计出现 48 处 crash，崩溃点位于蓝牙配对流程。

## 根因分析
崩溃点在 `BluetoothUtil` 的配对协程里：先对当前已连设备执行 `it.bluetoothDevice.disconnect()` 并延时 160ms，然后**不经判空直接调用 `device.startPairing()`**。monkey 随机狂点场景下，`device` 包装对象内部的 `device.device`（真实 `BluetoothDevice`）可能尚未就绪或已被回收（蓝牙服务异步状态、设备列表刷新竞态），`startPairing()` 触发 NPE。正常手工操作时序稳定很难触发，monkey 高频随机操作放大了该窗口，累计 48 次。这是典型的"外部服务返回的包装对象未做成员判空"缺陷。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                                                         it.bluetoothDevice.disconnect()
                                                         delay(160.milliseconds)
-                                                        device.startPairing()
+                                                        if (device.device == null) {
+                                                            withContext(Dispatchers.Main) {
+                                                                ToastUtils.showMsgToast(
+                                                                    MyApplication.myApplication!!,
+                                                                    getString(R.string.click_bluetooth_hint)
+                                                                )
+                                                            }
+                                                        } else {
+                                                            device.startPairing()
+                                                        }
```

## 为什么能修复
在调用 `startPairing()` 前对 `device.device` 判空：为空时不再崩溃，改为切主线程 Toast 提示用户（`click_bluetooth_hint`），把"静默崩溃"转化为"可理解的失败提示"；非空才继续配对。monkey 压测目标"setting 不崩溃"达成。注意两点：`MyApplication.myApplication!!` 仍是非空断言，Application 生命周期内通常安全；崩溃消除但配对失败的用户引导较弱（仅 Toast），属于治标+体验兜底的组合。

## 复盘与经验
- monkey/自动化压测的价值就是把低概率竞态窗口放大成必现 crash，蓝牙等异步服务对象在每个使用点都应判空。
- NPE 修复要区分"吞掉"与"降级"：判空后给用户 Toast 提示比静默 return 更好，但要把失败原因打进日志以便追踪服务为何返回空。
- 高频 crash 聚集在同一函数（48 处同一栈）时，修复一处即可消除大部分 crash，优先按 crash 聚类排序投入。
- 协程里切 UI 操作（Toast）记得 `withContext(Dispatchers.Main)`，本修复的处理是标准写法。
