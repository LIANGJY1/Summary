# SIR-7882 · 手机蓝牙连接界面一直弹出配对失败弹窗

- **提交**：`e3e21685` | 2026-09-11 | dufan | HardwareLibs | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
手机蓝牙连接界面，车机反复弹出"配对失败"弹窗，关掉后又弹，循环不止。

## 根因分析
`component/Hardwarelibs` 的 `BtAnwManager.updateSecondaryDeviceAvrcpAndVoice()` 中，HFP 连接失败（`STATE_CONNECT_FAILED`）时会过滤已配对设备并调用 `scheduleHfpRetry(address)` 安排重试。该重试逻辑形成闭环：重试连接 → 再次失败 → 再次回调 `STATE_CONNECT_FAILED` → 再次 schedule 重试；而每次失败都会触发上层"配对失败"弹窗，于是弹窗循环出现。缺陷库根因"上层发起请求"、方案"停止发起"准确——问题不在配对本身，而是应用层对失败连接的自动重试策略与失败弹窗叠加成了死循环。

## 关键代码修改
改动文件：`component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java`
```diff
             } else if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECT_FAILED) {
-                mPairedDevices.stream().filter(it -> it.macAddress.equals(address)).findFirst().ifPresent(it -> {
-                    Log.w(TAG, "HFP connect failed for headset " + address + ", schedule retry");
-                    scheduleHfpRetry(address);
-                });
+//                mPairedDevices.stream().filter(it -> it.macAddress.equals(address)).findFirst().ifPresent(it -> {
+//                    Log.w(TAG, "HFP connect failed for headset " + address + ", schedule retry");
+//                    scheduleHfpRetry(address);
+//                });
             } else if (state == BtAdapterMessage.CONNECT_STATE.STATE_DISCONNECTED) {
```

## 为什么能修复
直接停用"失败即自动重试"路径后，一次连接失败只报一次失败，不再循环发起请求，弹窗随之只出现一次。副作用：HFP 连接失败后不再有应用层自动重连，依赖重试才能恢复的场景（如手机端短暂未就绪）需要用户手动重连或由其它重连机制兜底；且修复方式是注释而非删除，保留了两义性——后续若引入"有限次数重试 + 退避"应替换此处。

## 复盘与经验
- "失败自动重试"必须有限次并配退避，否则失败弹窗/失败上报与重试叠加就是无限循环——重试策略要和失败反馈机制放在一起设计。
- 弹窗类死循环的排查思路：找到"谁在反复触发"，往往不是弹窗逻辑错，而是上游事件源在循环（本例是 STATE_CONNECT_FAILED 重试环）。
- 用注释停用逻辑只能作为紧急止血，遗留注释代码应尽快删除并在缺陷库记录后续方案。
