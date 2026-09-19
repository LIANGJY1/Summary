# SIR-2463 · 副蓝牙连接前排耳机听不到蓝牙音乐，只有喇叭外放
- **提交**：`d392bb3b` | 2026-07-16 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 A · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙连接前排耳机后听不到车机播放的蓝牙音乐，声音只从喇叭外放。

## 根因分析
注：缺陷库根因记录的是底层原因（7870 内部 dsp 阻塞导致声卡 pcmC0D8c 录制失败、tinymix 配置导致 dsp 数据串扰，由展锐/协议栈侧解决），与本次 app 层 diff 不完全一致，以下以 diff 实际内容为准。app 层问题：BTMusic 通过 `SettingsUtils.setGSetting("custom_avrcp_event", ...)` 写全局 Settings 来传递 AVRCP 播放/暂停控制意图（`MediaForegroundService` 播放状态变化时写 0/2），依赖 Setting 侧 `SettingVehicleService` 注册 `GlobalSettingsObserver` 监听后再转发给蓝牙协议栈。这条跨应用的间接链路脆弱：一旦 Setting 侧监听未建立或转发失败，`AnWBT_AVRCP_Control` 永远不会被调用，耳机通道收不到播放控制，音乐走外放。此外 `BtAnwManager.setAvrcpControl` 原实现遍历成员 `mPairedDevices`，该缓存可能为 null/未同步，且没有空指针保护。

## 关键代码修改
改动文件：`application/BTMusic/build.gradle`、`application/BTMusic/src/main/java/com/yadea/btmusic/App.kt`、`MainActivity.kt`、`service/MediaForegroundService.kt`、`component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java`、`whitelist/com.yadea.btmusic.xml`

```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
-            LogUtils.d(tag, "setAvrcpControl->$isPlaying")
-            SettingsUtils.setGSetting("custom_avrcp_event", if (isPlaying) 0 else 2)
+            LogUtils.d(tag, "mIsPlay setAvrcpControl->$isPlaying")
+            BtAnwManager.getInstance().setAvrcpControl(if (isPlaying) 0 else 2)
```

```diff
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
     public void setAvrcpControl(int controlId) {
-        Log.d(TAG, "setAvrcpControl -> controlId=" + controlId);
-        for (DeviceBean device : mPairedDevices) {
-            if (device.role == 1) {
-                String fontdevice = device.macAddress;
+        Set<BtRemoteDevice> pairDevices = mBtAdapter.AnWBT_GetPairedDevice();
+        Log.d(TAG, "setAvrcpControl 0-> controlId=" + controlId + ",mPairedDevices=" + (pairDevices == null));
+        if (pairDevices == null) return;
+        for (BtRemoteDevice device : pairDevices) {
+            if (device.getRole() == 1) {
+                String fontdevice = device.getAddress();
+                Log.d(TAG, "setAvrcpControl 1-> controlId=" + controlId + ",fontdevice=" + fontdevice);
                 mBtAdapter.AnWBT_AVRCP_Control(fontdevice, controlId);
+                return;
             }
         }
     }
```

配套改动：BTMusic `build.gradle` 新增 `implementation project(':component:Hardwarelibs')`；`App.onCreate` 提前 `BtAnwManager.getInstance()` 初始化；whitelist 增加 `BLUETOOTH_SCAN` 等特权权限以支撑直接访问蓝牙适配器。

## 为什么能修复
把 AVRCP 控制命令的下发通道从"写全局 Settings → Setting 应用监听转发"改为 BTMusic 进程内直连 `BtAnwManager` → `AnWBT_AVRCP_Control`，消除跨应用中转这一单点失效环节；`setAvrcpControl` 改为实时从 `mBtAdapter.AnWBT_GetPairedDevice()` 取配对设备并加 null 保护，避免缓存未同步导致命令无处下发。副作用：BTMusic 需要持有蓝牙特权权限（whitelist 已补），且与 Setting 侧残留监听并存期间可能出现双重处理，故有下一个提交 ccf1b551 清理。

## 复盘与经验
- 用全局 Settings 当跨进程 RPC 通道（写 key → 对方 observer 转发）链路长且无失败反馈；能进程内直调 SDK 时不要绕道第三方应用中转。
- 缓存的设备列表（`mPairedDevices`）与协议栈实时状态可能脱节，控制类接口应现场查询并处理 null。
- 缺陷库根因（dsp/tinymix 底层）与 app 层 diff 描述不一致时，一次缺陷往往是"底层+应用层"多条修复并行，复盘要分清各层各提交解决的是哪一段。
