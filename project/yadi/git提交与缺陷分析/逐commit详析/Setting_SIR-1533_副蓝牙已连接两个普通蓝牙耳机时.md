# SIR-1533 · 调节头盔多媒体音量时图标瞬间变回普通多媒体图标
- **提交**：`8a5a064b` | 2026-07-02 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙已连接两个普通蓝牙耳机时，在系统音量进度条列表中调节头盔多媒体音量，左侧图标立刻从头盔图标变成普通多媒体图标。

## 根因分析
`SoundFragment`（`application/Setting/.../ui/fragment/SoundFragment.kt`）的 `setLeftImg(type, seekBar)` 负责按音量组刷新左侧图标：旧的 `when(type)` 只显式处理 NAVI/VOICE/RINGTONE 三组，MEDIA 组落入 `else` 分支，无条件使用 `ic_sound_media/ic_sound_media_off`（普通多媒体图标）。当输出设备是头盔（BT master）时，调节音量触发 `onGroupVolumeChanged` → `setLeftImg`，图标被强制刷回普通多媒体图标。更深一层：`updateOutputDeviceChanged(device)` 只在函数内局部使用 `device` 刷可见性，**没有把 device 存为成员**，`setLeftImg` 根本无从得知当前输出设备；且初始化时 `updateOutputDeviceChanged` 放在 `initObserve()` 里执行（此时视图尚未就绪，取到的输出设备状态不可靠）。另外 `BluetoothAnwAdapter` 中 `majorCode/major`（耳机/头盔判定）只在已连接分支计算，未连接分支的图标不区分设备类型。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt`、新增 `res/drawable/ic_helmet_off.xml`、`component/Hardwarelibs/.../anwBt/BtAnwManager.java`（仅加日志）
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
@@ -70,7 +71,30 @@
         val isZero = seekBar.progress == 0
+        val isBtMasterEnabled =
+            (mCurrentOutputDevice and CarAudioManager.AUDIO_OUTPUT_EXT_BT_MASTER_DEVICE) != 0
         val res = when (type) {
+            CarAudioManager.AUDIO_VOLUME_GROUP_MEDIA -> {
+                view = mBinding.sbMedia.root
+                if (isBtMasterEnabled) {
+                    if (isZero) R.drawable.ic_helmet_off else R.drawable.ic_helmet
+                } else {
+                    if (isZero) R.drawable.ic_sound_media_off else R.drawable.ic_sound_media
+                }
+            }
+            CarAudioManager.AUDIO_VOLUME_GROUP_CALL -> {
+                view = mBinding.sbCall.root
+                if (isBtMasterEnabled) {
+                    if (isZero) R.drawable.ic_helmet_off else R.drawable.ic_helmet
+                } else {
+                    R.drawable.ic_sound_call
+                }
+            }
@@ -144,6 +144,7 @@
     private fun updateOutputDeviceChanged(device: Int) {
+        mCurrentOutputDevice = device
         val isSpeakerEnabled = (device and CarAudioManager.AUDIO_OUTPUT_DEVICE_SPEAKER) != 0
@@ -194,13 +194,13 @@
-        val device = mViewModel.getCarAudioOutputDevice()
-        updateOutputDeviceChanged(device)
     }
     override fun lazyLoadData() {
         mViewModel.setSeekbarView(mBinding)
+        val device = mViewModel.getCarAudioOutputDevice()
+        updateOutputDeviceChanged(device)
```

## 为什么能修复
三点联动消除根因：1) `mCurrentOutputDevice` 成员缓存输出设备位掩码，`setLeftImg` 每次刷新都能判断当前是否头盔输出；2) MEDIA/CALL 组显式分支：BT master 生效时用 `ic_helmet`/新增的 `ic_helmet_off`（音量为 0），否则才用普通多媒体图标，调节音量不再"变回"普通图标；3) 初始设备状态改到 `lazyLoadData()`（视图就绪后）获取，保证首次渲染即正确。`BluetoothAnwAdapter` 把 `major` 判定前移，未连接设备也按类型显示耳机/头盔图标。隐患：CALL 组在非 BT master 场景不再区分音量 0 的置灰图标（固定 `ic_sound_call`），若 UI 要求通话图标也有 off 态需补充。

## 复盘与经验
- 图标随"输出设备 + 音量值"双状态变化时，刷新函数必须能同时拿到两个状态源；把 device 缓存为成员是最小修复。
- `when/switch` 里用 `else` 兜底 MEDIA 这类高频分支很危险——新场景一旦落入 else 就被渲染成默认样式。
- 初始化依赖视图的操作要放在视图就绪的生命周期（lazyLoadData），否则读到的状态与后续回调不一致，表现"闪一下就错"。
