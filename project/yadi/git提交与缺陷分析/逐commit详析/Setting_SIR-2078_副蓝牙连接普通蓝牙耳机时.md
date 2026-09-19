# SIR-2078 · 副蓝牙耳机音量调过 80% 无提示

- **提交**：`d6b8db8a` | 2026-07-08 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设 · 根因/方案均记为"增加弹窗提示"

## 问题
副蓝牙连接普通蓝牙耳机后，在声音设置里把媒体/通话音量拖过 80% 时没有任何 toast 提示（产品要求首次超过 80% 给出听力保护类提醒）。

## 根因分析
`SoundFragment` 的音量条（媒体 `sbMedia`、通话 `sbCall`）只是普通 SeekBar + `setVolumeForUsage` 直通：**"耳机输出"这一设备形态没有任何特殊分支**，`updateOutputDeviceChanged()` 只切换了图标与后排乘员条可见性，既没有超限拦截，也没有提示回调——超 80% 提示的功能从一开始就没实现（rc 直书"增加弹窗提示"）。实现需要三件事协同：识别"普通蓝牙耳机输出"场景、在拖动过程中实时拦截、首次越限时提示并夹到 80%。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/widget/LimitedSeekBar.kt`（新增 76 行）、`.../fragment/SoundFragment.kt`（+120/-40）、`layout/seekbar_setting_hud_brightness.xml`、`values{,-en}/strings.xml`、`adapter/DebounceOnItemClickListener.kt`（日志微调）
```kotlin
// --- application/Setting/src/main/java/com/yadea/setting/ui/widget/LimitedSeekBar.kt（新增 ToggleableLimitSeekBar）
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!limitedMode || hasTriggered) return super.onTouchEvent(event)
                val limit = (max * 0.8).toInt()
                val ratio = ((event.x - paddingLeft) / (width - paddingLeft - paddingRight)).coerceIn(0f, 1f)
                val newProgress = (ratio * (max - min)).toInt() + min
                if (newProgress > limit) {
                    hasTriggered = true
                    progress = limit                      // 首次越限：夹到 80%
                    onFirstExceedLimit?.invoke(newProgress, true)
                    return true
                }
                ...
            }
        }
```
```diff
// --- SoundFragment.kt  updateOutputDeviceChanged()：耳机输出时开启限位模式
             isBtMasterEnabled -> {
                 mBinding.sbMedia.ivLeft.setImageResource(R.drawable.ic_helmet)
+                mBinding.sbMedia.seekbarCentral.limitedMode = true
+                mBinding.sbCall.seekbarCentral.limitedMode = true
// --- onProgressChanged()：首次越限提示
+                if (progress.isGreaterThanEightyPercentOf(maxGroupValue)) {
+                    hasMediaTriggeredLimit = true
+                    seekBar?.progress = threshold
+                    voiceHint()   // ToastUtils.showMsgToast(R.string.voice_max_hint)
+                    return
+                }
```

## 为什么能修复
三层配合：`updateOutputDeviceChanged` 按输出设备位标志（`AUDIO_OUTPUT_EXT_BT_SLAVE/MASTER_DEVICE`）进入 limitedMode；自定义 `ToggleableLimitSeekBar` 在 ACTION_MOVE 用触点坐标预估 progress，越限即夹回 80% 并触发一次回调；回调里 toast（新增 `voice_max_hint` 文案）并置 `hasMediaTriggeredLimit`。首次越限被夹住+提示，之后 `hasTriggered/hasMediaTriggeredLimit` 放行，符合"提醒一次不强制"的交互。隐患：进度条双通道判断（控件 MOVE 拦截 + onProgressChanged 判断）逻辑重复，两处阈值算法（`max*0.8`）一旦改动需同步；`isGreaterThanEightyPercentOf` 用 infix 扩展写法花哨但可读性一般；通过外部拖到 80%+（非触摸）路径不经 MOVE 拦截，只被 onProgressChanged 兜住。

## 复盘与经验
- **设备形态驱动的 UI 策略要集中管理**：输出设备变化不只换图标，音量上限、提示等策略都应在该分支统一下发（limitedMode 就是把策略做成控件属性的雏形）。
- **"首次越限提醒"是典型 one-shot 交互**：需要 trigger 标志 + 夹位 + 提示三件套，且要明确提醒后是否放行——本例放行，法规类场景（如欧盟音量限制）则通常持续夹位，实现前先对齐规格。
- 自定义控件把拦截做在触摸层（MOVE 坐标预估）比在监听回调里事后纠正更顺滑，但两处阈值必须单一来源。
