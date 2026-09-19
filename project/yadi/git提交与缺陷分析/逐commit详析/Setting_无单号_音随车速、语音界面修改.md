# 无单号 [SRS_SYSSetting_008] 音随车速、语音界面修改
- **提交**：`da46d41e` | 2026-08-18 | sgh | Setting | feature
- **关联单**：SRS_SYSSetting_008

## 需求/目标
两块 UI 改版：音效页新增"音随车速"单选（本期为占位实现，只记录本地态并弹说明，不下发信号）；语音页介绍卡片从四项改为三项（新增"屏幕声控"，去掉"免唤醒"和全局唤醒入口），语音唤醒开关只控制音色区可见性。

## 实现结构
- `SoundEffectFragment.kt`：新增 `rgSoundFollowSpeed` 的初始化 + listener，点击 tip 弹 SentinelDialogSmall 说明；`onItemChecked` 仅更新 `mSoundFollowSpeedMode`，日志标注 `[Placeholder]`——信号链路留待后续。
- `VoiceFragment.kt`：删除"全局唤醒"整块（布局入口、GlobalWakeUpDialogFragment 调用、setGlobalWakeUpState）；三个介绍卡改为代码动态设置图标/标题/描述；`swVoiceWakeup` 联动对象从整块 `llVoice` 收窄为 `llVoiceTimbre`。
- `SceneModeFragment.kt`：驻车断电提示从大弹窗 SentinelDialog 换成 SentinelDialogSmall（弹窗规格统一）。
- 资源：ic_voice_1/2/3 更新、fragment_voice.xml 精简 93 行、新增音随车速文案。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundEffectFragment.kt
+    @SuppressLint("MissingPermission")
+    private fun setupSoundFollowSpeedListener() {
+        mBinding.rgSoundFollowSpeed.setOnTipClickListener(object : ImageTextRadioGroup.OnTipClickListener {
+            override fun onTipClick() {
+                SentinelDialogSmall(
+                    getString(R.string.sound_follow_speed),
+                    getString(R.string.sound_follow_speed_hint)
+                ).show(childFragmentManager, "SoundFollowSpeedTipDialog")
+            }
+        })
+        mBinding.rgSoundFollowSpeed.setOnItemCheckedListener(listener = object :
+            ImageTextRadioGroup.OnItemCheckedListener {
+            override fun onItemChecked(position: Int, text: String) {
+                mSoundFollowSpeedMode = position
+                logClick("[Placeholder] Sound follow speed mode: $position")
+            }
+        })
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/VoiceFragment.kt
         mBinding.swVoiceWakeup.setOnCheckedChangeListener {
-            mBinding.llVoice.visibility = if (it) VISIBLE else GONE
+            mBinding.llVoiceTimbre.visibility = if (it) View.VISIBLE else View.GONE
             AiLitContext.getSpeechManager().setVoiceWakeUpEnable(it)
         }
```
音随车速走"先上 UI 壳、后接信号"的灰度路径：用 `[Placeholder]` 日志显式标记未完成的下发链路，方便后续全文搜索定位。语音页删掉全局唤醒后从"混合入口"简化为纯介绍卡片 + 唤醒开关 + 音色选择。

## 复盘与要点
- 占位实现 + 显式 Placeholder 标记是"UI 先行、协议后到"迭代节奏下的实用手法，但需要跟踪清单配合，否则容易漏接信号。
- 弹窗组件出现大/小两档后，本提交开始统一提示类弹窗都用 Small 版，说明组件收敛在持续进行。
- 遗留风险：音随车速选项无回显订阅，车端或音频服务实际生效状态与 UI 可能脱节；`initSoundFollowSpeedMode` 用成员默认值 0 初始化而非读取存储，重启后 UI 不记忆上次选择。
