# SIR-6045 · 网易云播放中手机进音乐APP未操作dock栏却变为蓝牙音乐信息

- **提交**：`ce1dc85a` | 2026-08-23 | dufan | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
车机端网易云音乐正在播放，手机侧 merely 打开音乐 APP（未做任何点击），dock 栏媒体信息被蓝牙音乐信息覆盖。

## 根因分析
`BtMusicModel`（object 单例）通过 AVRCP 回调 `onPlaybackStateChanged` / `onMetadataChanged` 接收手机蓝牙音乐状态，之前收到任何回调都无条件把 `mPlaybackState`、`MediaMetadata`（打上 `MUSIC_SOURCE_BT` 标记）写入 LiveData，dock 栏订阅该 LiveData 直接刷新。手机打开音乐 APP 时，AVRCP 会推送一次播放状态/元数据（哪怕未播放），蓝牙音乐模型照单全收，dock 栏便"被变成蓝牙音乐信息"。缺陷库根因"蓝牙音乐未判断当前焦点，更新信息"点中要害：音源信息归属应由当前音频焦点决定，而不是由"谁推了数据"决定。修复新增 `isCanChangeInfo()`：通过 `App.getCarAudioManager().getCarFocusForZoneId(CarAudioManager.PRIMARY_AUDIO_ZONE)` 遍历主音频区焦点持有者，若焦点在车机端网易云音乐（`com.arcvideo.car.ncm.music`）则拒绝更新。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/App.kt（+47 行，新增 CarServiceManager/CarAudioManager 获取通道）、application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt（+14/-1）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ onPlaybackStateChanged
                 if (state != null && !mIsBCallInCall) {
+                    if (!isCanChangeInfo()) return@post
                     val stateState = state.state
                     stateChange(stateState)
                     mPlaybackState.value = state
@@ onMetadataChanged
-            LogUtils.i(TAG, "onMetadataChanged metadata: $mediaMetadata")
             LogUtils.d(
@@
-            val metadata = mediaMetadata?.let {
+            if (!isCanChangeInfo()) return
+            val metadata = mediaMetadata?.let {
@@ 新增焦点判断
+    private fun isCanChangeInfo(): Boolean{
+        App.app?.getCarAudioManager()?.getCarFocusForZoneId(CarAudioManager.PRIMARY_AUDIO_ZONE)?.forEach {
+            LogUtils.d(TAG, "forceAudioPackageName: " + it.packageName)
+            if (TextUtils.equals("com.arcvideo.car.ncm.music", it.packageName)) {
+                return false
+            }
+        }
+        return true
+    }
```
（App.kt 新增 `registerSignal()` 初始化 `CarServiceManager` 及 `getCarAudioManager()` 懒加载获取 `Car.AUDIO_SERVICE`，此处不展开。）

## 为什么能修复
在两条 AVRCP 数据入口统一加了焦点闸门：车机本地音乐持有主音频区焦点期间，蓝牙音乐的状态/元数据推送被丢弃，dock 栏不再被无关回调改写；焦点切回蓝牙（或本地音乐不在焦点列表）时恢复正常更新。隐患：包名硬编码 `com.arcvideo.car.ncm.music`，若音乐应用换包名或出现多个本地音源需同步维护；`isCanChangeInfo()` 里 `App.app?` 为空或 Car 服务未就绪时返回 true（放行），极端时序下仍可能闪现蓝牙信息，但整体可接受。

## 复盘与经验
- 多音源共存的车机系统里，媒体信息 UI 的更新必须以"当前音频焦点"为准绳，事件驱动（回调推送）不能替代焦点仲裁。
- 判定逻辑用焦点持有者包名列表，而不是"收到推送就更新"，这是 AOSP 车机媒体框架（MediaSession 与 AudioFocus）的正确打开方式。
- 硬编码第三方应用包名是权宜之计，长期应下沉到音源注册表/配置中心。
- 蓝牙音乐在通话中（`mIsBCallInCall`）已有闸门，本次补上了焦点闸门，闸门条件要在同一入口集中收敛。
