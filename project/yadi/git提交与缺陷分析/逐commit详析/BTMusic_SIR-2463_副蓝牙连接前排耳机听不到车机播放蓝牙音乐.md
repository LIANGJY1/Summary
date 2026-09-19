# SIR-2463 · 副蓝牙连接前排耳机听不到车机蓝牙音乐（仅喇叭外放）
- **提交**：`4f5cb42f` | 2026-07-15 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 A · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙连接前排耳机后，车机播放的蓝牙音乐耳机里听不到，只有喇叭外放发声。

## 根因分析
本仓库 diff 显示的修复是**补上 AVRCP 控制链路**：`MediaForegroundService` 观察播放状态变化时，此前只发本地 `isPlayFlow` 事件，没有把播放/暂停状态通过 AVRCP 下发给副蓝牙侧（前排耳机链路），副蓝牙 A2DP 源端流控状态与车机播放状态脱节，耳机端收不到正确触发的音频流。新增 `BtAnwManager.setAvrcpControl(controlId)` 遍历已配对设备、对 `role == 1`（前排）设备调 `mBtAdapter.AnWBT_AVRCP_Control(mac, controlId)` 转发控制字。
注意：缺陷库记录的完整根因更深——"7870 内部 dsp 阻塞导致副蓝牙声卡 pcmC0D8c 读取失败 + tinymix 配置导致 dsp 数据串扰"，其解决方案（展锐更新 dsp 逻辑、下发 tinymix 配置）在平台/BSP 层，不在本仓库；本提交是应用层配套的 AVRCP 接口接入，diff 与缺陷库 sol 描述不完全对应，以 diff 实际内容为准。

## 关键代码修改
改动文件：application/BTMusic/build.gradle（+1）、application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（-5，清理无用 import）、application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt（+2）、component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java（+10）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ 播放状态观察处
             lifecycleScope.launch { isPlayFlow.emit(isPlaying) }
+            BtAnwManager.getInstance().setAvrcpControl(if (isPlaying) 0 else 2)
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ 新增 AVRCP 控制转发
+    public void setAvrcpControl(int controlId) {
+        Log.d(TAG, "setAvrcpControl -> controlId=" + controlId);
+        for (DeviceBean device : mPairedDevices) {
+            if (device.role == 1) {
+                String fontdevice = device.macAddress;
+                mBtAdapter.AnWBT_AVRCP_Control(fontdevice, controlId);
+            }
+        }
+    }
```
（build.gradle 新增 `implementation project(':component:Hardwarelibs')` 引入 BtAnwManager）

## 为什么能修复
车机播放状态变化时向前排设备同步 AVRCP 控制字（播放/暂停），使副蓝牙 A2DP 源链路的流控与车机播放一致，耳机端得以按正确状态拉流出声。应用层这半边补齐后，配合平台侧 dsp/tinymix 修复（本仓库外）共同构成完整方案。隐患：`setAvrcpControl` 广播给所有 role==1 设备，若前排有多设备会有冗余控制；controlId 0/2 的语义依赖协议栈定义，代码处无注释。

## 复盘与经验
- **音频链路问题要分层定位**：同一"没声音"现象可能横跨应用状态机（AVRCP 流控）、协议栈、声卡/dsp 三层，缺陷库根因与仓库 diff 分属不同层，归因时要标明边界。
- **转发状态给从端是联动的常见缺口**：车机作为 A2DP source 时的播放状态同步，新增播放入口时容易漏发，建议收敛到单一状态发射点。
