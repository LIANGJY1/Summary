# SIR-2127 · 蓝牙通话中调节铃声进度条喇叭发出预览音
- **提交**：`9e857725` | 2026-07-10 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
车机蓝牙电话正在通话时，调节"系统音量-铃声"进度条，喇叭会发出预览提示音，干扰通话；需求变更为通话中不播预览音。

## 根因分析
`SoundFragment` 各音量 seekbar 拖动回调里固定调用 `mViewModel.playBeepWithUsage(type)` 播放预览音，`SoundViewModel.playBeepWithUsage` 只按 usage 类型起 `SoundPool` 播放，完全没有"当前是否有更高优先级音频焦点占用（如通话）"的判断；同时铃声组的预览音也从 `SoundPool` 缓存里初始化（`USAGE_NOTIFICATION_RINGTONE`、`USAGE_ASSISTANCE_SONIFICATION` 均在预加载列表中），通话中照样发声。根因是预览音播放与车机音频焦点状态脱钩。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt（+18/-13）、application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SoundViewModel.kt（+22/-59）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
@@ seekbar 预览音调用处
-                mViewModel.playBeepWithUsage(type)
+                mViewModel.playBeepWithUsage(type, groupId)      // 携带音量组 id
@@ 新增音频焦点回调
+    val mCarFocusCallback = object : CarAudioManager.CarFocusCallback() {
+        override fun onCarFocusChanged(audioZoneId: Int, focusHolders: List<AudioFocusInfo>?) {
+            mViewModel.setStopPlay(focusHolders)
+        }
+    }
@@ chime 关闭分支删除多余预览音
-                    mViewModel.playBeepWithUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
--- application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SoundViewModel.kt
@@ 新增：焦点持有者与当前预览音同组则停播
+    fun setStopPlay(focusHolders: List<AudioFocusInfo>?) {
+        focusHolders?.forEach {
+            val volumeGroupIdForUsage = settingVehicleService.getCarAudioManager()
+                ?.getVolumeGroupIdForUsage(it.attributes.usage) ?: -1
+            if (volumeGroupIdForUsage == mCurrentGroupId) {
+                stopCurrentPlay()
+                return@forEach
+            }
+        }
+    }
@@ 预加载列表移除 USAGE_ASSISTANCE_SONIFICATION
-                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
-                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
+                AudioAttributes.USAGE_NOTIFICATION_RINGTONE
@@ stopCurrentPlay 补充状态复位
+            mCurrentGroupId = -2
+            mCurrentUsageType = 0
```
注：本提交定义了 `mCarFocusCallback` 但尚未注册（注册在后续补丁 `0fddc72c` 中补齐）。

## 为什么能修复
预览音播放改为携带 `groupId`，`setStopPlay` 在音频焦点变化时把焦点持有者的 usage 映射为音量组，与当前预览音同组（如通话占用通话/铃声组）即 `stopCurrentPlay`，实现"通话中不发声"。同时 `stopCurrentPlay` 补复位 `mCurrentGroupId/mCurrentUsageType`，防止停播后残留状态导致下次判断错位。隐患：依赖焦点回调时序，焦点建立与拖动几乎同时时可能漏停一声音；未注册回调则整套机制不生效（见后续补丁）。

## 复盘与经验
- **提示音必须感知音频焦点**：任何"附带发声"的 UI 交互（音量条预览、开关音效）都要先判断焦点占用，通话/导航场景下静默是车机硬约束。
- **按"音量组"而非"usage"比较场景**：`getVolumeGroupIdForUsage` 把零散 usage 归一到组，比较逻辑更稳。
- **状态机复位要成对**：停播后 `mCurrentStreamId` 与 `mCurrentGroupId/mCurrentUsageType` 必须一起复位，否则"已停播"与"记录的播放态"不一致。
- **本提交埋了"定义未注册"的坑**：新回调只在类里声明不等于生效，注册/反注册必须同提交完成（这正是下一个补丁修的）。
