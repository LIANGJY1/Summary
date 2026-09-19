# SIR-2880 · 关闭耳机蓝牙开关后车机喇叭发出预览音（音量监听未过滤非用户操作）

- **提交**：`aeb4e116` | 2026-07-21 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙连接前/后排耳机后，用户主动关闭耳机端蓝牙开关，车机喇叭会突然发出预览音（音量跳变声音）。

## 根因分析
`SoundFragment.kt` 中每个音量 SeekBar 的 `onProgressChanged` 回调不区分触发来源：只要 progress 变化就执行 `setVolumeForUsage(...)`（后排条则是 `BtAnwManager.setCtAbsoluteVolume`）。当耳机蓝牙开关被关闭时，音频输出设备发生切换（`onOutputDeviceChanged`），系统会把音量状态同步/回写到 UI——SeekBar 被程序化 `setProgress`，同样触发 `onProgressChanged(fromUser=false)`；旧代码把这个"设备切换引起的音量回调"当成用户拖动，再次主动设置音量，在输出设备切换的过渡瞬间对喇叭播放了对应音量的预览音。缺陷库记录与 diff 一致："音量监听非用户操作时设置了音量"→"非用户操作时不设置音量"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
@@ setupMediaSeekBar / setupNavigationSeekBar / setupVoiceSeekBar /
   setupCallSeekBar / setupRingtoneSeekBar / setupRearPassengerSeekBar（6 处同模式）
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
+                log("sbMedia.seekbarCentral=$progress ,fromUser=$fromUser")
+                if (!fromUser) return
                 ...
                 hasMediaTriggeredLimit = handleVolumeWithLimit(..., applyVolume = { ... })
             }
```

## 为什么能修复
在全部 6 个音量条（媒体/导航/语音/通话/铃声/后排乘客）的 `onProgressChanged` 入口统一加 `if (!fromUser) return`：程序化回写（设备切换、状态同步）只更新 UI 不再回设音量，切断了"音量变化→UI 回写→再设音量→喇叭出声"的环；只有真实用户拖动（`fromUser=true`）才会下发音量。副作用基本没有——状态同步本来就不应反向驱动设置动作，且各条仍通过 `onGroupVolumeChanged`/`onExtBTSlaveMediaVolumeChanged` 监听保持 UI 与实际音量一致。

## 复盘与经验
- `OnSeekBarChangeListener.onProgressChanged` 的 `fromUser` 参数是"用户意图"与"状态回显"的分界线，处理写接口的回调必须先过滤 `!fromUser`，否则任何程序化 setProgress 都会变成一次真实设置。
- 输入控件回调里做"写"操作时要想到闭环风险：写操作引发状态变化→状态变化回写控件→控件回调再写，形成自激；过滤来源是标准断环手段。
- 修复应在同一模式的所有实例上一次性铺齐（6 个 seekbar 全改），只修报障的那一个会留下同类隐患。
