# SIR-6008 · 蓝牙电话 monkey 测试日志多处 ANR

- **提交**：`653252f3` | 2026-08-20 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 系统需求

## 问题
蓝牙电话模块 monkey 测试期间，日志中出现多处 ANR（应用无响应）。

## 根因分析
缺陷库根因："语音侧蓝牙广播在主线程处理且同时同步调用 HFP/PBAP Binder"。蓝牙连接状态广播在主线程 `BroadcastReceiver.onReceive` 中处理时，同步执行了跨进程的耗时清理操作：`BluetoothManager` 检测到换设备连接时在广播处理路径上直接调用 `DataClearUtil.clearDeviceData()`，该清理涉及联系人/通话记录数据库与跨进程调用，阻塞主线程超过阈值即触发 ANR；monkey 高频随机切换蓝牙状态使该路径被反复击中。提交说明 `[why]联系人清理涉及同步耗时操作`。修复：`BluetoothManager` 引入单线程 `dataClearExecutor`，清理任务入队后台执行（顺带先保存 `oldDeviceMac` 局部变量，避免异步执行时读到被更新的 MAC）；`DataClearUtil.isClearing` 改为 `volatile` 保证跨线程可见；`UiBluetoothMonitor` 的 HFP 连接广播改为 `handleHfpConnectionStateChanged()`，首次 CONNECTED 直接以广播中的设备触发 `downloadContacts(address)`，避免再走较慢的 Profile 查询路径。注：缺陷库 sol 描述的"所有 HFP/PBAP 调用迁移专用 HandlerThread、缓存优先后台刷新"是整体方案，本提交落地了其中的清理后台化与首连直连同步两部分。

## 关键代码修改
改动文件：BluetoothManager.java、UiBluetoothMonitor.java、DataClearUtil.java、AndroidManifest.xml（4 文件，+50/-17）
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/manager/BluetoothManager.java @@
+    private final ExecutorService dataClearExecutor = Executors.newSingleThreadExecutor();
...
                         if (previousConnectedMac != null && !previousConnectedMac.equals(currentMac)) {
-                            DataClearUtil.clearDeviceData(appContext, previousConnectedMac);
-                            Log.i(TAG, "New device detected, cleared old device [" + previousConnectedMac + "] data");
+                            final String oldDeviceMac = previousConnectedMac;
+                            dataClearExecutor.execute(() -> {
+                                try {
+                                    boolean success = DataClearUtil.clearDeviceData(appContext, oldDeviceMac);
+                                    Log.i(TAG, "Old device data cleanup completed, mac="
+                                            + oldDeviceMac + ", success=" + success);
+                                } catch (RuntimeException e) {
+                                    Log.e(TAG, "Old device data cleanup failed, mac=" + oldDeviceMac, e);
+                                }
+                            });
+                            Log.i(TAG, "Old device data cleanup queued, mac=" + oldDeviceMac);
                         }
```
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/utils/DataClearUtil.java @@
-    private static boolean isClearing = false;
+    private static volatile boolean isClearing = false;
```

## 为什么能修复
清理这类秒级耗时操作离开广播主线程，`onReceive` 快速返回，ANR 触发条件消失；volatile 修复后台化引入的状态可见性问题。隐患：清理变为异步后，"换设备后旧数据短暂可见"的窗口存在（清理排队未完成时新会话可能读到旧数据），依赖清理顺序保证（单线程 FIFO）缓解。Manifest 同时移除了 `CommandControllerReceiver` provider 声明，属附带瘦身。

## 复盘与经验
- `BroadcastReceiver.onReceive` 默认在主线程且有时限，任何数据库/跨进程操作都必须后台化——monkey 测试是暴露这类问题的最高效手段。
- 操作后台化时注意两件事：闭包捕获的变量要快照（oldDeviceMac），配套的状态标志要 volatile，否则修完 ANR 换来竞态 bug。
- 首次连接场景广播里已携带确定的状态与设备，直接使用比二次查询 Profile 更快也更不易漏。
