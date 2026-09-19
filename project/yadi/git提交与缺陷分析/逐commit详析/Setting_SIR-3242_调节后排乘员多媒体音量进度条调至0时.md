# SIR-3242 · 后排乘员音量调至 0 未显示静音图标
- **提交**：`ca43ec81` | 2026-07-31 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
系统设置中调节后排乘员多媒体音量进度条到 0 时，左侧图标未切换为静音（off）图标，与 UI 不符。

## 根因分析
后排音量条 `sbRearPassenger` 的图标切换逻辑只挂在异步回调 `CarExtBTVolumeCallback.onExtBTSlaveMediaVolumeChanged()` 里：用户拖到 0 后要等蓝牙协议栈把音量回写、回调再次触发才换图标，且回调内用的是参数值 `extBTSlaveMediaVolume == 0` 判断；用户主动拖动的路径 `setupRearPassengerSeekBar().onProgressChanged` 里完全没有图标刷新，导致拖到 0 时图标不静音。另外静音图标本身（`ic_headphones_off`/`ic_helmet_off`）的矢量画法与新版 UI 不符（无斜杠、alpha 0.6），也是"UI 问题"的一部分。提交同车还带了 `BtAnwManager.java`（305 行）的大幅重构与 `SoundFragment` 的格式化整理。

## 关键代码修改
改动文件：`application/Setting/.../ui/fragment/SoundFragment.kt`、`application/Setting/.../ui/viewmodel/SoundViewModel.kt`、`application/Setting/src/main/res/drawable/ic_headphones_off.xml`、`ic_helmet_off.xml`、新增 `ic_sound_helmet.xml`、另含 anwExt 库 `BtAnwManager.java` 搭车重构
```diff
--- application/Setting/.../fragment/SoundFragment.kt (setupRearPassengerSeekBar)
                     applyVolume = {
                         BtAnwManager.getInstance().setCtAbsoluteVolume(progress)
+                        mBinding.sbRearPassenger.ivLeft.setImageResource(
+                            if (progress == 0) R.drawable.ic_headphones_off else R.drawable.ic_headphones)
                     })
--- application/Setting/.../fragment/SoundFragment.kt (onExtBTSlaveMediaVolumeChanged 回调)
-                mBinding.sbRearPassenger.ivLeft.setImageResource(if (extBTSlaveMediaVolume == 0) R.drawable.ic_headphones_off else R.drawable.ic_headphones)
+                val process = mBinding.sbRearPassenger.seekbarCentral.progress == 0
+                mBinding.sbRearPassenger.ivLeft.setImageResource(if (process) R.drawable.ic_headphones_off else R.drawable.ic_headphones)
--- application/Setting/src/main/res/drawable/ic_headphones_off.xml
-        android:fillAlpha="0.6"
-        android:fillColor="@color/text_default_press"
-        android:pathData="M27.57,22.947..."   (旧无斜杠图形)
+        android:fillColor="@color/icon_default_press"
+        android:pathData="M17.5869 0.75...L0 2.38965..."  (新版带斜线静音图形)
```
（`SoundViewModel` 中删除了批量置 off 图标逻辑里的 `sb_rear_passenger` 分支，避免与他处刷新互相覆盖；头盔启用分支图标统一换为新 `ic_sound_helmet`。）

## 为什么能修复
在用户拖动的 `onProgressChanged` 里同步按 `progress == 0` 切换静音图标，不依赖异步回写；回调路径改用 seekbar 当前 `progress` 判断，消除回调值与 UI 值不一致导致的图标回跳。图标资源按新 UI 重绘（斜杠静音样式、`icon_default_press` 颜色），观感与设计稿一致。隐患：同提交混入 `BtAnwManager` 大重构（305 行），与本单无关但一并上车，回归范围被无形扩大。

## 复盘经验
- "UI 状态随用户操作即时反馈"的逻辑要写在输入事件路径上（onProgressChanged），异步回写只能做校正，不能作为唯一刷新源。
- 图标状态判断应统一数据源：回调参数值 vs 控件当前值二选一，混用就会互相打架（本例两处都收敛到 seekbar.progress）。
- 缺陷标注"UI 问题"的提交也可能内嵌大规模重构，评审与测试范围要按 diff 实际内容圈定，而不是按单据描述。
