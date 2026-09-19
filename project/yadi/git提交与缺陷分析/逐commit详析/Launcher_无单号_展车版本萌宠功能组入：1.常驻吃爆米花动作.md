# 无单号 · 展车版本萌宠功能组入

- **提交**：`aa27b25a` | 2026-08-20 | liang-jy | Launcher | feature（大型首期开发提交）
- **关联单**：无

## 需求/目标
在 Launcher 中组入展车版"萌宠"（3D 虚拟宠物）交互功能：常驻吃爆米花随机动作、语音唤醒聆听动作、音乐播放听歌动作、点击放屁动作，动作通过 Kanzi 协议下发渲染。

## 实现结构
19 个文件、+1021 行。新增 `control/pet/` 包：`PetActionCode`（与 Kanzi 协议对齐的 VPA 动作码全表：0~101）、`PetAction` 枚举（code + priority + isLoop + isEnd）、`PetActionArbiter` 接口与 `PassThroughArbiter`/`PriorityArbiter` 两个策略、`PetConfig`（空闲触发 10s、空闲窗口 10~15s、开关通道、音乐防抖等常量）、`PetActionController`（单例核心：聚合各信号源、dispatch 动作、管理空闲定时器）、`PetPropertyMonitor`（监听 Settings.Global 萌宠开关）、`PetVoiceSource`/`AiLitPetVoiceSource`（语音唤醒事件抽象 + AI-Lit 实现）、`PetAudioSource`。新增 `control/audiosource/` 包：`AudioSourceMonitor`（366 行，媒体会话/音频焦点监控）、`AudioSourceState`/`AudioSourceType`/`AudioSourceListener`。另新增两个语音 SDK jar（aiContract/aiLit 8.0），微调 `KanziConstants`、`KanziType`、`KanziDataSourceManager`、`Myapplication`。
数据流：AudioSourceMonitor（音乐播放状态）+ AiLitPetVoiceSource（语音唤醒）+ Kanzi onKanziData（点击/播放完成上报）+ PetPropertyMonitor（开关）→ PetActionController 仲裁（Arbiter 策略 + 空闲定时器）→ KanziDataSourceManager.sendVpaAction(code) → Kanzi 渲染动作。

## 关键代码
```diff
--- /dev/null  (application/Launcher/src/main/java/com/yadea/launcher/control/pet/PetAction.kt)
+enum class PetAction(
+    val code: Int,
+    val priority: Int,
+    val isLoop: Boolean,
+    val isEnd: Boolean
+) {
+    POPCORN(PetActionCode.RANDOM_ACTION_3, 3, false, false),
+    FART(PetActionCode.CLICK_1, 2, false, false),
+    MUSIC(PetActionCode.MUSIC_START, 1, true, false),
+    MUSIC_END(PetActionCode.MUSIC_END, 4, false, true),
+    VOICE(PetActionCode.VOICE_WAKE_START, 0, true, false),
+    VOICE_END(PetActionCode.VOICE_WAKE_END, 4, false, true)
+}
```
```diff
--- /dev/null  (application/Launcher/src/main/java/com/yadea/launcher/control/pet/PetActionArbiter.kt)
+interface PetActionArbiter {
+    fun canDispatch(current: PetAction?, candidate: PetAction): Boolean
+}
+class PriorityArbiter : PetActionArbiter {
+    override fun canDispatch(current: PetAction?, candidate: PetAction) =
+        current == null || candidate.priority <= current.priority
+}
```
```diff
--- /dev/null  (application/Launcher/src/main/java/com/yadea/launcher/control/pet/PetActionController.kt)
+            KanziType.VPA.VPA_OUT_ACTION_PLAY_STATUS -> {
+                if (value == "0") {
+                    ...
+                    // 临时对应，动作播放完后，发一个Action = 0，不然动作不生效
+                    KanziDataSourceManager.getInstance(appContext).sendVpaAction(0)
+                    if (currentAction?.isLoop == false) {
+                        currentAction = null
+                    }
+                    restartIdleTimer()
```
实现讲解：三层结构清晰——信号采集（audiosource/voice/property 三路 monitor）、决策（PetActionController 持有 currentAction 状态机 + 可插拔 Arbiter 仲裁）、执行（sendVpaAction 下发动作码）。动作枚举用 priority/isLoop/isEnd 四元组描述语义，循环动作（VOICE/MUSIC）由配对的 *_END 收尾，一次性动作播完由 Kanzi 上报 play status=0 触发复位并重启空闲定时器（空闲 10~15s 窗口内触发随机爆米花动作）。点击放屁当前用"有动作执行中即忽略"的临时规则，注释明确 TODO 后续换优先级仲裁。音乐状态带 debounce 常量过滤切歌抖动。

## 复盘与要点
- 策略模式仲裁器（PassThrough/Priority 可切换，PetConfig.USE_PRIORITY_DEFAULT 控制）为后续动作冲突调优预留了演进空间，是好取舍。
- Kanzi 协议有隐含约定："动作播完必须补发 Action=0 否则不生效"——这类渲染端协议怪癖应以注释/常量固化（已做注释），避免后人误删。
- 硬编码音乐包名白名单（网易云音乐/蓝牙音乐）+ 大量 TODO 与默认透传仲裁，说明一期以"能跑"优先，动作打断语义尚不完整，存在连点/抢断的边界风险。
