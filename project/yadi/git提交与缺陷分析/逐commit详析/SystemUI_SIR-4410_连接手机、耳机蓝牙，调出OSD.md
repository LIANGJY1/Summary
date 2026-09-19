# SIR-4410 · 连接手机/耳机蓝牙时音量 OSD 头盔图标显示错误
- **提交**：`d6259acf` | 2026-07-30 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
同时连接手机、耳机蓝牙后调出音量 OSD，OSD 上的音源图标显示错误（误显示/不显示头盔图标）。

## 根因分析
缺陷库根因明确：SystemUI 音量 OSD 的头盔图标依据 MCU 信号 `MPU_TO_MCU_QUERY_HELMET_CONNECT_STATUS` 判断，该信号不准确；而 Settings 的 `SoundFragment` 依据 `CarAudioManager` 输出设备 bitmask 中的 `AUDIO_OUTPUT_EXT_BT_MASTER_DEVICE` 位判断，表现正确。代码侧对应：`DigitalKeyVehicleService` 监听该 CarProperty 并把值喂给 `VolumeDialogActor.setHelmetConnect(valid)` 缓存到 `helmetConnectStatus` 字段，`getIconResId()` 用 `helmetConnectStatus == 1` 决定是否画头盔图标——单一瞬时信号、无同步查询、信号本身不可靠，手机/耳机蓝牙接入时 OSD 图标即出错。同提交还搭车修了 `CarPlayCallWindow` 双路通话上卡渲染逻辑（ACTIVE+RINGING 组合下上卡显示错路、swap 按钮不分状态一律 swapCalls），与缺陷单无关。

## 关键代码修改
改动文件：`application/SystemUI/.../digitalkey/DigitalKeyVehicleService.kt`、`application/SystemUI/.../vehiclecontrol/volume/CarAudioVolumeController.kt`、`application/SystemUI/.../vehiclecontrol/volume/VolumeDialogActor.kt`、另含 `application/BTPhone/.../carplay/CarPlayCallWindow.java` 等 CP 电话搭车修改
```diff
--- application/SystemUI/.../volume/CarAudioVolumeController.kt
+    private val carOutputDeviceCallback = object : CarAudioManager.CarOutputDeviceCallback() {
+        override fun onOutputDeviceChanged(device: Int) {
+            currentOutputDevice = device
+            val volumeDialog = ActorController.getInstance()
+                .get(ActorController.TYPE_VOLUME_ADJUST) as VolumeDialogActor
+            volumeDialog.onOutputDeviceChanged()
+        }
+    }
+    fun isBtMasterDeviceEnabled(): Boolean {
+        currentOutputDevice = cam.carOutputDevice  // 每次查询最新值
+        return (currentOutputDevice and CarAudioManager.AUDIO_OUTPUT_EXT_BT_MASTER_DEVICE) != 0
+    }
--- application/SystemUI/.../volume/VolumeDialogActor.kt
-        if (helmetConnectStatus == 1 && (groupId == ...GROUP_MEDIA || groupId == ...GROUP_CALL)) {
+        if (mAudioVolumeController.isBtMasterDeviceEnabled() && (groupId == ...GROUP_MEDIA || groupId == ...GROUP_CALL)) {
--- application/SystemUI/.../digitalkey/DigitalKeyVehicleService.kt
-        CarPropertyIds.MPU_TO_MCU_QUERY_HELMET_CONNECT_STATUS, //主头盔连接状态反馈
（并删除对应的 property 分发与 setHelmetConnect 调用）
```

## 为什么能修复
数据源从"MCU 一次性信号 + 本地缓存字段"切换为"`registerCarOutputDeviceCallback` 回调 + 每次取 `carOutputDevice` 最新 bitmask 判 `AUDIO_OUTPUT_EXT_BT_MASTER_DEVICE` 位"，与 Settings 同源，信号准确性问题被绕开；`onOutputDeviceChanged()` 转发到 `VolumeDialogActor`（切主线程后 `updateUI()`）保证弹窗显示中图标实时刷新，注册后还同步一次初值避免回调未触发前状态为空。副作用：`isBtMasterDeviceEnabled()` 依赖 `mAudioVolumeController` 非空且 `carOutputDevice` 可查询，代码里已 try/catch 兜底缓存值。

## 复盘经验
- 多应用展示同一状态（头盔连接）时必须对齐数据源：本例 Settings 正确、SystemUI 错误，差异全在"信谁"；跨模块对齐判断逻辑应沉淀为公共查询接口而非各自监听。
- 底层信号（MCU）不可信时，换数据源比在消费端打补丁有效；且"缓存瞬时信号"模式要配注册时初值同步，否则弹窗首显必错。
- 显示中的 UI 要有"状态变化主动刷新"通道（回调→post 到主线程→updateUI），只改判断函数不刷新 UI 则要等下次弹出才对。
- 一个 commit 混入 CP 电话双路通话修复会模糊缺陷归属，拆分提交更利于回溯。
