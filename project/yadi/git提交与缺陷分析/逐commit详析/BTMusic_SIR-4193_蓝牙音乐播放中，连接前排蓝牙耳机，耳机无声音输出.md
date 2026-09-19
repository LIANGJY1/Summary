# SIR-4193 · 蓝牙音乐播放中连接前排蓝牙耳机耳机无声音输出

- **提交**：`51dd271d` | 2026-07-27 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 问题取消 · 域 本地多媒体

## 问题
车机蓝牙音乐播放中连接前排蓝牙耳机时，耳机无声音输出。

## 根因分析
`BtAnwManager.handleConnectState()` 原来在收到 `A2DP_SOURCE`/`AVRCP_TARGET` 任一 profile 连接成功的广播时，就立刻 `AnWBT_AVRCP_AudioSourceIdGet/AnWBT_AVRCP_AudioSourceIdSet` 分配音频源并调用合并的 `switchAvrcpAndHfp()` 切换。此时 `device.setA2dpSourceFlag(stateValue)` 尚未落位，HFP 状态也未记录，音频源分配与 AVRCP/HFP 切换发生在 profile 状态不完整的时点上，导致耳机侧音频通路没被正确建立（缺陷库结论：代码调用逻辑问题，按协议侧要求调整）。状态为"问题取消"，改动是按底层/协议侧要求完成的规范化重构。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
// application 层：删除 connect 时点上的抢先分配（原 handleConnectState 头部整段移除）
-        if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECTED
-                && (profileId == BtAdapterMessage.PROFILE_ID.A2DP_SOURCE || profileId == BtAdapterMessage.PROFILE_ID.AVRCP_TARGET)) {
-            int audioSourceId = mBtAdapter.AnWBT_AVRCP_AudioSourceIdGet(address);
-            int availableAudioSourceId = findAvailableAudioSourceId();
-            if (audioSourceId == -1 && availableAudioSourceId != -1) {
-                mBtAdapter.AnWBT_AVRCP_AudioSourceIdSet(address, availableAudioSourceId);
-                switchAvrcpAndHfp();
-            }
-        }
```
```diff
// 按 profile 拆分为两个独立切换函数
             if (profileId == BtAdapterMessage.PROFILE_ID.HFP) {
+                switchHfp(state, device, address);
                 device.setHfpFlag(stateValue);
             } else if (profileId == BtAdapterMessage.PROFILE_ID.A2DP_SOURCE
                     || profileId == BtAdapterMessage.PROFILE_ID.AVRCP_TARGET) {
+                switchAvrcpAndA2dp(state, device, address);
                 device.setA2dpSourceFlag(stateValue);
```
新增 `switchHfp()`：HFP 连接成功且 `audioSourceId == -1` 时 `AnWBT_AVRCP_AudioSourceIdSet` 并 `setVoiceRecognitionNew(1)`，断开时 `setVoiceRecognitionNew(0)`；新增 `switchAvrcpAndA2dp()`：要求 `Objects.equals(device.a2dpSourceFlag, Constant.BT_STATUS_CONNECT)` 才分配音频源并 `setAvrcpControlNew(device.macAddress, 0)`，断开时 `setAvrcpControlNew(device.macAddress, 2)` 释放。

## 为什么能修复
音频源分配与通路切换从"A2DP_SOURCE/AVRCP_TARGET 首个广播即触发"改为按协议时序拆分：HFP 连接后置语音识别通路，A2DP/AVRCP 连接后（且 flag 已落位）才分配 `AudioSourceId` 并启动 AVRCP 控制，断开时显式释放（`setAvrcpControlNew(mac, 2)`），耳机的音频通路按正确顺序建立。各分支附带了 hfpFlag/audioSourceId 日志便于后续排查。风险：依赖 `a2dpSourceFlag` 先落位的顺序假设，若底层广播顺序变化需同步调整。

## 复盘与经验
- 蓝牙多 profile（HFP/A2DP/AVRCP）场景下，"收到任一 profile 连上就切通路"是典型时序错误，切换动作应绑定到协议要求的最后一个关键 profile 状态。
- 对资源型句柄（如 AudioSourceId）要成对实现"分配-释放"，断开分支同样需要处理，否则复用/重连时状态错乱。
- 底层协议侧要求的"应用侧调整"类缺陷，重点核对事件到达顺序与 flag 写入时机是否匹配，日志应打印完整 profile 状态。
