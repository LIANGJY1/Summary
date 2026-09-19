# SIR-2881 · 副蓝牙后排耳机音量条调节无效果（缺少AVRCP绝对音量接口）

- **提交**：`2fe510d5` | 2026-07-21 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙连接前/后排耳机后，拖动设置页"后排多媒体音量"进度条，耳机实际音量几乎不变（只有很小的预览音），必须去调前排多媒体音量条才有明显效果。

## 根因分析
`SoundFragment.kt` 的 `setupRearPassengerSeekBar()` 中 `applyVolume` 调用的是 `settingVehicleService.getCarAudioManager()?.setExtBTSlaveMediaVolume(progress, 0)`——这是车机端 CarAudioManager 的本地音量接口，只改变车机侧混音/预览音量，并不会把音量命令下发到真正出声的蓝牙耳机。副蓝牙耳机出声走的是蓝牙协议栈的 AVRCP 绝对音量通道，而应用侧根本没有对接该接口（元数据："没有音量调节接口，和协议侧对接错误"；缺陷库："调节音量成功后，协议栈没有上传成功状态给上层"）。也就是说 UI 层调的是一个"不作用于耳机的接口"，音量自然调不动。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt；component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java；component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java
+    public int AnWBT_AVRCP_SetCtAbsoluteVolume(String btaddr, int absoluteVolume) {
+        return executeAidl("AnWBT_AVRCP_SetCtAbsoluteVolume", () -> mService.AnWBT_AVRCP_SetCtAbsoluteVolume(btaddr, absoluteVolume));
+    }
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
+    public void setCtAbsoluteVolume(int absoluteVolume) {
+        Log.d(TAG, "setCtAbsoluteVolume 0-> absoluteVolume=" + absoluteVolume);
+        for (DeviceBean deviceBean : mPairedDevices) {
+            if (deviceBean.role == 0) {
+                String fontdevice = deviceBean.macAddress;
+                mBtAdapter.AnWBT_AVRCP_SetCtAbsoluteVolume(fontdevice, absoluteVolume);
+            }
+        }
+    }
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
                     onTriggered = {},
                     applyVolume = {
-                        settingVehicleService.getCarAudioManager()?.setExtBTSlaveMediaVolume(progress, 0)
+                        log("setupRearPassengerSeekBar sbRearPassenger SetCtAbsoluteVolume =$progress")
+                        BtAnwManager.getInstance().setCtAbsoluteVolume(progress)
                     }
```

## 为什么能修复
新增的 `AnWBT_AVRCP_SetCtAbsoluteVolume` 通过 AIDL 直连蓝牙协议栈服务，`BtAnwManager.setCtAbsoluteVolume` 遍历已配对设备中 `role == 0` 的设备逐个下发 AVRCP 绝对音量，把"后排耳机音量条"从改车机本地音量改为直接控制耳机端音量，命令真正作用于出声设备。同时在 `BtAdapter.OnAvrcpTgGetOrSetInfoInd` 回调加日志，便于观察协议栈上报的音量状态。隐患：遍历发的是"所有 role==0 设备"，若前后排耳机角色分配变化，仍依赖 role 字段的准确性；协议栈不回调成功状态的问题（缺陷库 rc）部分要靠协议侧配合，本提交先以日志打通链路。

## 复盘与经验
- 车机音频分"本地输出"与"蓝牙转发"两条链路：给蓝牙耳机调音量必须走 AVRCP 绝对音量，调 CarAudioManager 本地接口是典型的"接口对接错层"。
- "拖动条动了但听感没变"这类问题，先用日志确认命令是否到达协议栈/终端设备，再判断是接口错、链路断还是状态不回传。
- 跨进程硬件能力（AIDL 服务）新增接口时，在 Manager 层做一次按设备遍历封装，能让上层 UI 一行调用完成多设备同步。
