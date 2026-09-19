# SIR-7451 · 设置页来电铃声进度条无法调到 0
- **提交**：`84cea744` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
设置页声音界面，来电铃声拖动条无法调节到 0（静音），最小只能停在 1。

## 根因分析
`VolumeFragment.setupRingtoneSeekBar()` 中为铃声拖动条注册的 `onProgressChanged` 回调里，写死了一行业务限制 `if (progress <= 1) seekBar?.progress = 1`。也就是说只要用户把进度拖到 1 或更低，代码会立刻把进度强制回写为 1，随后再调用 `mViewModel.setVolumeForUsage(CarAudioManager.AUDIO_VOLUME_GROUP_RINGSTONE 对应 group, progress)` 下发声量。这个"最低音量限制为 1"是历史遗留需求（注释里也写着"铃声音量拖动条（最低音量限制为 1）"），与当前 UI/交互规格（允许 0 即静音）冲突，属于典型的产品规格变更后代码里的旧约束没有被同步清除。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/VolumeFragment.kt（1 处删除 + 注释更新）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/VolumeFragment.kt
@@ -369,7 +369,7 @@ class VolumeFragment : BaseFragment<FragmentVolumeBinding, VolumeViewModel>() {
     /**
-     * 铃声音量拖动条（最低音量限制为 1）
+     * 铃声音量拖动条
      */
     private fun setupRingtoneSeekBar() {
@@ -377,7 +377,6 @@
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 log("sbRingtone.seekbarCentral=$progress ,fromUser=$fromUser")
                 if (!fromUser) return
-                if (progress <= 1) seekBar?.progress = 1
                 mViewModel.setVolumeForUsage(
                     CarAudioManager.AUDIO_VOLUME_GROUP_RINGTONE, seekBar?.progress!!
                 )
```

## 为什么能修复
删掉强制回写 `progress = 1` 的钳位语句后，用户拖到 0 时进度条保持 0，`setVolumeForUsage` 直接把 0 传给 CarAudioManager，铃声即静音，与系统行为一致。改动是单点钳位移除，无副作用；唯一隐患是若底层音频策略不允许 0，可能出现 UI 显示 0 但实际音量非 0，这属于底层约束而非本层问题（缺陷库根因"底层回调未做处理"与本 diff 实际机制略有出入，以 diff 为准：本层是自己加了钳位）。

## 复盘与经验
- 旧需求以"魔法钳位"硬编码在 UI 回调里，需求翻转后极易被遗忘；钳位类逻辑应收敛到可配置项或 VM 层并留注释关联需求单号。
- 注释里写明"最低音量限制为 1"却没人质疑，说明评审时对照的是代码而不是需求规格；UI 类 bug 评审应拿最新 UX 稿逐项核对。
- 进度条类控件的 `onProgressChanged` 中对 `seekBar.progress` 反向赋值会造成"拖不动"的用户感知，这类写法要格外警惕。
