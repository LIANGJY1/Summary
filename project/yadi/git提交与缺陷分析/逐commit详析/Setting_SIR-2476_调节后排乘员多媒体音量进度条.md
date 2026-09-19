# SIR-2476 · 调节后排乘员多媒体音量无预览音提示
- **提交**：`caa8d7aa` | 2026-07-13 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
调节后排乘员多媒体音量进度条时，喇叭未发出预览提示音（需求变更为后排耳机场景也应有提示音）。

## 根因分析（以 diff 实际内容为准，与提交消息/缺陷库描述有出入）
缺陷库称"未在 onExtBTSlaveMediaVolumeChanged 中设置提示音"，但本提交 diff（仅 2 个 hunk、+1/-1）实际改动是**耳机图标状态同步的挂载位置**：把 `sbRearPassenger.ivLeft` 的静音图标切换（`ic_headphones_off` / `ic_headphones`，`extBTSlaveMediaVolume == 0` 判断）从用户拖动进度条的回调中移除，改为在 `onExtBTSlaveMediaVolumeChanged` 音量变化回调中设置。该回调中 `flags == FLAG_PLAY_SOUND` 时 `playBeepWithUsage(type, AUDIO_VOLUME_GROUP_MEDIA)` 的预览音逻辑是此前 `9e857725` 已有的上下文行，本提交并未新增提示音播放调用。未能从 diff 判断"增加提示音"如何由本提交实现，可确认的实际效果是：音量回调统一入口处同步图标状态，避免仅由拖动路径更新的遗漏。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt（+1/-1）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
@@ onExtBTSlaveMediaVolumeChanged 回调（新增图标同步）
             settingVehicleService.getCarAudioManager()?.apply {
                 mBinding.sbRearPassenger.seekbarCentral.progress = extBTSlaveMediaVolume
+                mBinding.sbRearPassenger.ivLeft.setImageResource(if (extBTSlaveMediaVolume == 0) R.drawable.ic_headphones_off else R.drawable.ic_headphones)
             }
@@ 进度条拖动回调（移除原位置的图标设置）
-                mBinding.sbRearPassenger.ivLeft.setImageResource(if (progress == 0) R.drawable.ic_headphones_off else R.drawable.ic_headphones)
                 settingVehicleService.getCarAudioManager()?.apply {
                     setExtBTSlaveMediaVolume(progress, 0)
                 }
```

## 为什么能修复
图标状态改由音量变化回调统一刷新：凡走到 `onExtBTSlaveMediaVolumeChanged` 的音量变更（本地拖动设置后回灌、或外部变更）都会同步图标，消除"只有拖动才刷新"的单路径遗漏。若预览音依赖该回调的 flags 链路，统一入口也保证了每条音量路径都能触达播放判断。仅图标迁移无行为风险。

## 复盘与经验
- **UI 状态刷新挂在"状态变化回调"而非"用户操作"上**：同一音量有多条写入路径（拖动/旋钮/远端），只有挂在回调上才能全量覆盖——这与 SIR-1656（座椅记忆双源失步）是同一模式。
- **提交消息要与 diff 对齐**：本提交消息写"增加提示音"、实际改图标同步，事后复盘必须以 diff 为准，也提醒团队消息敷衍会永久污染追溯链。
