# 无单号 · [SRS_BT_LinkSetting_012] 修改语音音色设置

- **提交**：`ef522ecd` | 2026-08-13 | dufan | Setting | feature
- **关联单**：无（SRS_BT_LinkSetting_012）

## 需求/目标
语音设置页的 TTS 音色资源名失效/升级，需把三个音色选项映射到新版 TTS 资源（24000 采样率版本）。

## 实现结构
仅改动 `application/Setting/src/main/java/com/yadea/setting/ui/fragment/VoiceFragment.kt`（1 文件 3 增 3 删）。VoiceFragment 中的音色单选列表在 `onItemChecked` 回调里先把选中位置持久化到 `SettingsUtils.setGSetting(VOICE_TIMBRE, position)`，再通过 `AiLitContext.getSpeechManager().ttsResource` 直接把新音色资源名下发给语音引擎。本次仅替换 when 分支里的三个资源名字符串。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VoiceFragment.kt
@@ override fun onItemChecked(position: Int, text: String) {
                 SettingsUtils.setGSetting(VOICE_TIMBRE, position)
                 AiLitContext.getSpeechManager().ttsResource = when(position){
-                    0 -> "xijunm_ttsv5"
-                    1 -> "madouf_wenrou_ttsv5_cn"
-                    else -> "xbekef_ttsv5_cn"
+                    0 -> "xijunmv5n_24000"
+                    1 -> "madoufv5_wenrou_24000"
+                    else -> "xbekefv5_24000"
                 }
```
实现讲解：音色切换是"本地持久化 + 引擎热切换"两步，本次只是资源名对齐——旧资源名（ttsv5 后缀）换成带 24000 采样率标识的新命名，说明引擎侧音色包升级后命名规则变为 `名称_采样率`。字符串硬编码在 UI 层，改动成本最低但也意味着资源名变更必须跟着改代码。

## 复盘与要点
- TTS 音色资源名以 magic string 散落在 Fragment 里，建议收敛到常量或配置下发，引擎升级时不用改 UI 代码。
- `onItemChecked` 中先写设置再切引擎，若 `ttsResource` 赋值失败会造成 UI 状态与引擎实际音色不一致，可考虑以引擎回执为准回写。
- 影响等级标 D、测试范围"无"，但音色属于用户可感知项，实际上线前仍需人工试听验证。
