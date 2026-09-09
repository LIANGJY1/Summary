# 萌宠（VPA Pet）功能 — 执行计划

> 状态：执行中

## 目标
Launcher 新增萌宠动作仲裁中枢，收敛三路信号（音频/Kanzi 回调/语音）统一下发 `VPA_Action`。

## 新增文件（包 `com.yadea.launcher.control.pet`）
1. `PetAction.kt` — 动作枚举（code/priority/isLoop）
2. `PetConfig.kt` — 配置常量（空闲阈值）
3. `PetActionArbiter.kt` — 仲裁策略（PassThrough + Priority）
4. `PetAudioSource.kt` — 音频适配器
5. `PetVoiceSource.kt` — 语音抽象接口（R4 预留）
6. `PetActionController.kt` — 仲裁中枢（单例）

## 修改文件
1. `manager/KanziType.java` — VPA 类补 `VPA_CLICKED`、`VPA_OUT_ACTION_PLAY_STATUS`
2. `control/KanziDataSourceManager.java` — 补 `sendVpaAction()` + `notifyDataChanged` VPA 转发
3. `Myapplication.kt` — init `PetActionController`

## 动作码
RESIDENT=0 / POPCORN=3 / FART=5 / MUSIC_START=90 / MUSIC_END=91 / VOICE_START=100 / VOICE_END=101

## 待办（后续）
- R4 aiLit 集成（等双进程验证）
- R1 `VPA_Out_Action_PlayStatus` 回调名确认
- R5 `VPA_Switch` 回传名确认
- 常驻态 code 0 vs 2 确认
