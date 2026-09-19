# VIR-129 · 连接蓝牙耳机没声音（蓝牙电话）

- **提交**：`4330fd5e` | 2026-08-05 | daizhecheng | BTPhone | bugfix
- **缺陷库**：未关联单号（标题含 VIR-129）

## 问题
连接前排蓝牙耳机后耳机无声音输出（蓝牙电话语音通路未建立）。

## 根因分析
`BtAnwManager.updateSecondaryDeviceAvrcpAndVoice()` 中，HFP 连接成功后执行 `setVoiceRecognitionNew(1)`（打开耳机语音通路）的前置条件是 `checkRole() && address != null`。`checkRole()` 是对整个 `mPairedDevices` 的角色校验，一旦校验不通过（角色状态与预期不符），即使耳机 HFP 已连上，语音通路也不会打开，耳机自然无声；同样 `STATE_CONNECT_FAILED` 的重试 `scheduleHfpRetry` 也被 `checkRole()` 拦截，失败后无法自愈。缺陷库结论：代码调用逻辑问题，按协议侧要求调整（与 51dd271d 同源的音频通路时序问题）。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
// application 层：HFP 连接成功分支去掉 checkRole() 门槛
             if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECTED) {
-                if (checkRole() && address != null) {
+                if (address != null) {
                     mPairedDevices.stream().filter(it -> it.macAddress.equals(address)).findFirst().ifPresent(it -> {
                         if (it.role == 1) {
                             setVoiceRecognitionNew(1);
                         }
                     });
```
```diff
// STATE_CONNECT_FAILED 分支同样放开重试
             } else if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECT_FAILED) {
-                if (checkRole()) {
-                    mPairedDevices.stream().filter(...).ifPresent(it -> {
-                        if (it.role == 1) {
-                            scheduleHfpRetry(it);
-                        }
-                    });
-                }
+                mPairedDevices.stream().filter(it -> it.macAddress.equals(address)).findFirst().ifPresent(it -> {
+                    Log.w(TAG, "HFP connect failed for headset " + address + ", schedule retry");
+                    scheduleHfpRetry(address);
+                });
```
配套：`scheduleHfpRetry(DeviceBean)` 签名改为 `scheduleHfpRetry(String address)` 并增加空串防护；`updateSecondaryDeviceAvrcpAndVoice` 由 private 改为 public（供协议侧/外部触发）；A2DP/AVRCP 连接时保留按 role 分配 `AnWBT_AVRCP_AudioSourceIdSet(address, 0/1)` + `setAvrcpControlNew(address, 0)`，断开 `setAvrcpControlNew(address, 2)`；各分支日志补充 profileId/state/role 明细。

## 为什么能修复
语音通路的打开不再受全局角色校验 `checkRole()` 连坐，只要目标耳机 `role == 1` 且 HFP 连接成功就 `setVoiceRecognitionNew(1)`，HFP 失败重试也不再被同一条件拦截，耳机声音通路按协议时序（A2DP/AVRCP 管媒体、HFP 管语音）独立建立。风险：去掉 `checkRole()` 后依赖单设备自身 role 字段判断，若上游 role 值异常仍会漏开，日志已增强便于定位。

## 复盘与经验
- "前置全局校验 + 单设备动作"的组合容易把个别设备的通路建立连坐掉，条件应收敛到与动作直接相关的对象属性（该设备的 role），而非全局状态。
- 连接失败的重试路径与成功路径同等重要，任何门槛都要允许重试机制运转，否则失败即终态。
- 与 51dd271d 对照可见：音频通路类问题（无声）排查主线是 AudioSourceId 分配、setAvrcpControlNew、setVoiceRecognitionNew 三把开关的触发时序与条件。
