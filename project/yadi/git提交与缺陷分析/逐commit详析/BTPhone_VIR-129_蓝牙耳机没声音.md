# VIR-129 · 蓝牙耳机没声音（协议栈对接逻辑调整）

- **提交**：`5139e534` | 2026-07-29 | daizhecheng | BTPhone（实改 component/Hardwarelibs） | bugfix
- **缺陷库**：未关联缺陷库记录（单号 VIR-129，defs 无条目）

## 问题
连接蓝牙耳机后耳机没有声音。提交说明为"协议分析应用需要调整、逻辑问题、按协议侧要求修改"——即 ANW 蓝牙协议栈侧要求 App 适配层（`BtAnwManager`）调整音频路由相关设置逻辑。

## 根因分析
`BtAnwManager` 是 ANW 自研蓝牙栈的适配层，耳机的出声依赖三件事在正确的时机完成：给设备分配音频源（`AnWBT_AVRCP_AudioSourceIdSet`）、设置 AVRCP 控制（`setAvrcpControlNew`）、设置语音识别/通话通路的开关（`setVoiceRecognitionNew`）。原实现把这些动作埋在 `switchHfp` / `switchAvrcpAndA2dp` 两个 per-profile 分支里，且带前置条件（如 `Objects.equals(device.a2dpSourceFlag, BT_STATUS_CONNECT)`、`availableAudioSourceId != -1`）。profile 连接顺序不固定（耳机场景 HFP 与 A2DP Source 的到达时序不定），条件不满足时分配/设置被跳过后**没有重试入口**，音频源从未绑定到该设备，耳机即无声。缺陷库描述的"逻辑问题"应指此，但 diff 未直接给出协议栈侧的具体要求文档，此结论为依据代码结构的合理推断。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java

```diff
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ handleConnectState()
         int profileId = intent.getIntExtra(BtAdapterMessage.EXTRA_PROFILE_ID, -1);
         Log.d(TAG, "handleConnectState profile_id:...");
+        if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECTED
+                && (profileId == BtAdapterMessage.PROFILE_ID.A2DP_SOURCE ||
+                profileId == BtAdapterMessage.PROFILE_ID.AVRCP_TARGET ||
+                profileId == BtAdapterMessage.PROFILE_ID.HFP_AG)) {
+            int audioSourceId = mBtAdapter.AnWBT_AVRCP_AudioSourceIdGet(address);
+            int availableAudioSourceId = findAvailableAudioSourceId();
+            if (audioSourceId == -1 && availableAudioSourceId != -1) {
+                mBtAdapter.AnWBT_AVRCP_AudioSourceIdSet(address, availableAudioSourceId);
+            }
+        }
```

```diff
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ 新增统一处理方法（在连接状态与角色更新两处广播后调用）
+    private void updateSecondaryDeviceAvrcpAndVoice(Intent intent, String address) {
+        if (mPairedDevices.stream().anyMatch(device -> device.role == 1)) {
+            if ((profileId == BtAdapterMessage.PROFILE_ID.A2DP_SOURCE
+                    || profileId == BtAdapterMessage.PROFILE_ID.AVRCP_TARGET)) {
+                if (state == CONNECT_STATE.STATE_CONNECTED) {
+                    setAvrcpControlNew(address, 0);
+                } else {
+                    setAvrcpControlNew(address, 2);
+                }
+            }
+            if (profileId == BtAdapterMessage.PROFILE_ID.HFP) {
+                if (state == CONNECT_STATE.STATE_CONNECTED) {
+                    setVoiceRecognitionNew(1);
+                } else {
+                    setVoiceRecognitionNew(0);
+                }
+            }
+        }
+    }
```

同时：`handleConnectState` 中原 `switchHfp(state, device, address)` / `switchAvrcpAndA2dp(...)` 调用全部注释掉（仅保留 flag 记录）；`updateSecondaryDeviceAvrcpAndVoice` 挂接到主连接广播处理与 `HfpAgMainDeviceHandler`（角色更新）两个入口。

## 为什么能修复
音频源分配从"HFP/A2DP 各自分支内、条件满足才做"上移到 `handleConnectState` 入口处，三个关键 profile 任一连接都会立即尝试绑定 AudioSourceId，消除时序依赖；AVRCP 控制与语音通路改由 `updateSecondaryDeviceAvrcpAndVoice` 在**连接状态**和**角色更新**两个事件上统一驱动，不再依赖单次 profile 事件的偶然顺序，耳机通话/媒体通路得以建立。隐患：旧路径以大量注释代码形式保留（约 26 行注释），两个入口都可能触发 `setVoiceRecognitionNew`/`setAvrcpControlNew`，重复调用与状态互踩的风险存在；`role == 1` 语义（主/从设备）未在注释中说明，可读性差。

## 复盘与经验
- **协议握手类设置不能挂单点事件**：profile 连接顺序不确定时，把"绑定音频源"这类关键动作挂在入口级、做成幂等（已分配则跳过），比藏在某条分支里可靠。
- **蓝牙"没声音"排查路径**：先查音频源绑定（AudioSourceId），再查通路开关（voice/avrcp control），最后才查设备侧——本提交正是按此层次重排了代码。
- **用注释保留旧逻辑是技术债**：本文件改动后注释代码比有效代码还多，后续维护者难以分辨哪些是协议要求、哪些是废弃实现，应依赖版本管理而非注释保留历史。
- **跨团队联调问题（"按协议侧要求修改"）务必在提交/单据中留协议侧依据**，否则复盘时无法判断改动正确性的边界。
