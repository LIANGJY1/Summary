# SIR-4601 · 前后排蓝牙耳机都连接时来电双方无声音（HFP 重连）
- **提交**：`89d9dbf0` | 2026-07-31 | daizhecheng | BTPhone/anwExt 库 | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 蓝牙电话

## 问题
前后排蓝牙耳机同时连接时蓝牙来电，通话双方都听不到声音（HFP 音频链路未建立）。

## 根因分析
缺陷库根因："已按协议侧要求添加 HFP 重试逻辑"。场景为两台 HFP 设备（前排+后排耳机）并存，协议栈在多设备竞争下 HFP 连接偶发失败且失败后无人再发起连接，音频网关一直不就绪，来电即无声。原 `BtAnwManager.updateSecondaryDeviceAvrcpAndVoice()` 只处理了 HFP `CONNECTED`/`DISCONNECTED` 两个状态分支（联动 `setVoiceRecognitionNew`），对 `STATE_CONNECT_FAILED` 没有任何处置——失败即终结，缺少补偿机制。这是与协议栈（BSP）联调确认后的结论：协议侧不稳定，应用侧需按协议侧要求加重试兜底。

## 关键代码修改
改动文件：`component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java`（仅此 1 文件，+65 行）
```diff
--- component/Hardwarelibs/.../anwBt/BtAnwManager.java
+    private static final int HFP_RETRY_MAX_COUNT = 2;
+    private static final long HFP_RETRY_DELAY_MS = 2000L;
+    private static final long HFP_RETRY_RESET_WINDOW_MS = 60_000L;
+    private final Map<String, Integer> mHfpRetryCount = new ConcurrentHashMap<>();
+    private final Map<String, Long> mHfpFirstFailTime = new ConcurrentHashMap<>();
+    private final android.os.Handler mHfpRetryHandler = new android.os.Handler(Looper.getMainLooper());
+
+    private void scheduleHfpRetry(DeviceBean device) {
+        // 距首次失败超过 60s 重置窗口则清零计数；超过 HFP_RETRY_MAX_COUNT 放弃
+        // removeCallbacksAndMessages(address) 防同设备重试任务重叠
+        ... mHfpRetryHandler.postDelayed(retry, address, HFP_RETRY_DELAY_MS); ...
+        // retry 内容: mPairedDevices 中找到该 mac → connectHfp(it, true)
+    }
+    private void resetHfpRetry(String address) { /* 连接成功/断开时清计数与任务 */ }
@@ updateSecondaryDeviceAvrcpAndVoice
+            } else if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECT_FAILED) {
+                if (checkRole()) {
+                    mPairedDevices.stream().filter(it -> it.macAddress.equals(address)).findFirst().ifPresent(it -> {
+                        if (it.role == 1) {
+                            Log.w(TAG, "HFP connect failed for headset " + address + ", schedule retry");
+                            scheduleHfpRetry(it);
+                        }
+                    });
+                }
             } else if (state == ...STATE_DISCONNECTED) {
...
+                    resetHfpRetry(address);
```

## 为什么能修复
HFP 连接失败状态新增分支：仅对"主角色"（role==1）设备调度重试，2 秒后 `connectHfp(it, true)` 重连，最多 2 次、60 秒窗口外计数清零，重试成功或断开后 `resetHfpRetry` 清理状态。补偿机制把偶发失败从"终态"变成"可自愈"，通话音频链路恢复建立，双方无声消失。设计上比较克制：限次数防风暴、按设备 mac 隔离任务（`postDelayed(retry, address, ...)`）、ConcurrentHashMap 防并发。隐患：重试与用户主动断开/切换设备存在竞态窗口（2 秒延迟期间状态可能已变化），依赖 `resetHfpRetry` 在 DISCONNECTED 时清理兜底。

## 复盘经验
- 多蓝牙音频设备并存是 HFP 不稳定的典型诱因，协议栈层问题应用层只能加"带上限的重试"兜底，且要与协议侧约定口径（日志里已注明 may need BSP investigation）。
- 状态机补分支要连着清理逻辑一起写：新增 FAILED 处理的同时，DISCONNECTED 补 reset，否则计数泄漏会让后续重试失效。
- 重试参数三件套（次数/间隔/重置窗口）+ 按设备隔离 + 线程安全容器，是连接类重试逻辑的标准形态，可直接复用。
