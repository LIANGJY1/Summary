# SIR-1531 · 后排乘员音量条调到 0 不显示静音图标

- **提交**：`c70d378b` | 2026-07-08 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B（提交消息标 C） · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设 · 根因/方案均记为"UI问题，增加静音图标"

## 问题
副蓝牙已连接两个普通蓝牙耳机时，系统音量进度条列表中把"后排乘员多媒体音量"调到 0，条目左侧图标不切换为静音图标。

## 根因分析
`SoundFragment` 中各音量条的"0 值静音图标"逻辑集中在 `SoundViewModel` 的一个 when 分支里（按 `R.id.sb_media/sb_navigation/...` 逐条映射 `ic_xxx_off`），**唯独漏了后排乘员条 `R.id.sb_rear_passenger`**——调 0 时该条图标维持 `ic_headphones` 不变。根因是这类"每新增一个音量条就要同步补一处 when 分支 + 一张 off 图"的映射没有集中约束，新增后排乘员条时遗漏。本提交同时修正了后台音量上限的取值来源（`getCarAudioManager()?.extBTSlaveMediaVolume` → `mViewModel.getMaxVolumeForUsage(AUDIO_OUTPUT_EXT_BT_SLAVE_DEVICE)`），并删除了一处与 progress 初始化冲突的 `max` 赋值。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt`、`.../viewmodel/SoundViewModel.kt`、`application/Setting/src/main/res/drawable/ic_headphones_off.xml`（新增 13 行矢量图标）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SoundViewModel.kt
                     R.id.sb_ringtone -> ivLeft.setImageResource(R.drawable.ic_sound_ringtone_off)
+                    R.id.sb_rear_passenger -> ivLeft.setImageResource(R.drawable.ic_headphones_off)
                 }
```
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt  后排乘员条 onProgressChanged
+                mBinding.sbRearPassenger.ivLeft.setImageResource(
+                    if (progress == 0) R.drawable.ic_headphones_off else R.drawable.ic_headphones)
                 settingVehicleService.getCarAudioManager()?.apply {
                     setExtBTSlaveMediaVolume(progress, 0)
                 }
// --- 上限取值修正
-                    settingVehicleService.getCarAudioManager()?.extBTSlaveMediaVolume ?: 0
+                    mViewModel.getMaxVolumeForUsage(CarAudioManager.AUDIO_OUTPUT_EXT_BT_SLAVE_DEVICE)
```

## 为什么能修复
两处补齐：ViewModel 的集中映射补上 `sb_rear_passenger → ic_headphones_off`（覆盖非用户拖动路径的刷新），拖动回调里同步按 `progress == 0` 切换图标（覆盖实时拖动路径），新增的 `ic_headphones_off` 矢量图提供静音视觉。上限取值改走 ViewModel 统一通道，消除了直接依赖 CarAudioManager 可空返回导致的 `?: 0` 退化（此前 max 为 0 时 80% 阈值恒为 0，限位逻辑失效）。副作用很小；但"一个状态三处映射"（ViewModel when + 拖动回调 + 初始化）仍是易漏结构。

## 复盘与经验
- **枚举式 when 映射新增条目必然漏**：音量条这类"同类控件集合"应把 off/on 图标作为条目配置数据（列表驱动），而不是散落在 when 分支里逐个补。
- **UI 状态切换要覆盖所有更新路径**：拖动回调、外部信号刷新、初始化三条路径都要走同一个图标决策函数，本例修复实际是在两条路径上分别补，说明决策尚未收敛。
- `?: 0` 兜底会把异常静默成"合法的 0"，让阈值计算全盘失效，可空依赖应尽早判空报错。
