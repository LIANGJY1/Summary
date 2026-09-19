# 无单号 · 萌宠服务整体框架搭建
- **提交**：`887ffe61` | 2026-09-08 | liang-jy | Launcher | feature（大型首期开发提交）
- **关联单**：无

## 需求/目标
在 Launcher 内为"车载萌宠（虚拟助手形象）"服务搭建整体软件框架：定义事件/指令协议、状态机骨架、Kanzi 渲染桥接与测试基建，为后续"随机动作、单双击动作"等功能组入预留扩展点（协议先行、逻辑后补）。

## 实现结构
新增 16 个文件、784 行，三层结构：
- **入口/配置**：`PetCore.kt`（唯一入口 object，事件统一收口→主线程串行处理→指令分发）、`PetConfig.kt`（全部可调参数与 Kanzi 临时协议键集中管理）。
- **核心层 core/**：`PetProtocol.kt`（sealed class 定义 17 种 PetEvent 与 12 种 PetCommand，外加 PetLogger/PetScheduler/PetStoreApi 三个端口接口）、`PetStateMachine.kt`（事件→指令的纯逻辑状态机，首期只实现 Init/SwitchChanged/SendFailed）、`PetAction.kt`（动作枚举）、`PetArbiter.kt`（动作竞合裁决器占位）、`PetIdleLoop.kt`（常驻待机循环占位）。
- **平台层 platform/**：`PetKanziBridge.kt`（指令→KanziManager 属性写入，失败回调）、`PetSources.kt`（Settings.Global 开关 ContentObserver 信号源）、`PetStore.kt`（开关/一次性标记持久化）、`PetSoundPlayer.kt`（音效播放）。
- **测试**：`MiniFakes.kt`（日志收集器/手动时钟/内存存储三件套）、`PetScenarios.kt` 场景驱动、`PetScenariosTest.kt`。

数据流：信号源（开关/档位/电量/语音等，折算为语义沿）→ `PetCore.onEvent()`（主线程串行）→ `PetStateMachine.onEvent()` 产出 `List<PetCommand>` → `PetCore.dispatch()` 按类型路由到 Bridge/音效/定时器/存储；Bridge 发送失败回送 `SendFailed` 事件交状态机重试，形成闭环。

## 关键代码
```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/pet/PetCore.kt
/** 事件统一入口：切主线程串行处理，产出指令并分发。 */
fun onEvent(event: PetEvent) {
    mainHandler.post {
        stateMachine.onEvent(event).forEach { command -> dispatch(command) }
    }
}

/** 指令分发：按类型路由到执行层（Bridge/播放器/定时器/存储）。 */
private fun dispatch(command: PetCommand) {
    when (command) {
        is PetCommand.PlayAction, is PetCommand.Hide, is PetCommand.Show ->
            bridge.send(command) { ok ->
                if (!ok) onEvent(PetEvent.SendFailed(command)) // 发送失败交回状态机重试
            }
        is PetCommand.PlaySound -> soundPlayer.play(command.sound)
        is PetCommand.StopSound -> soundPlayer.stop()
        is PetCommand.ScheduleTimer -> scheduler.postDelayed(command.id, command.delayMs)
        is PetCommand.CancelTimer -> scheduler.cancel(command.id)
        ...
    }
}
```

```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/pet/platform/PetSources.kt
// 先注册、后读值、再对账：注册后若现值与调用方已用的初值不同，
// 补发一条对齐沿，消除"读初值"与"注册完成"之间的缝隙。
fun observeSwitch(initialSwitchOn: Boolean, onChanged: (switchOn: Boolean) -> Unit) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val rawSwitch = readSwitchRaw()
            if (rawSwitch != null) onChanged(rawSwitch == 1) // 只在有值时发沿
        }
    }
    switchObserver = observer
    context.contentResolver.registerContentObserver(
        Settings.Global.getUriFor(PetConfig.SWITCH_KEY), false, observer)
    val currentSwitch = readSwitchRaw()
    if (currentSwitch != null && (currentSwitch == 1) != initialSwitchOn) {
        onChanged(currentSwitch == 1) // 对账沿
    }
}
```

实现讲解：核心是"事件进、指令出"的单向数据流——状态机不直接执行副作用，只返回指令列表，执行层失败再以事件形式回流，天然可测试。core 层通过 PetLogger/PetScheduler/PetStoreApi 三个接口与 Android 解耦，测试里用 `MiniFakes.ManualScheduler.advance(ms)` 手动推时钟即可验证超时/看门狗逻辑。`PetSources.observeSwitch` 的"注册后对账沿"手法解决了 ContentObserver 注册窗口期丢信号的经典竞态。

## 复盘与要点
- **协议先行、占位扩展**：17 种事件/12 种指令一次性定义完整（含天气/生日/节日等远期触发源），首期只实现三五个，未实现的走 `onUnhandled` 仅记日志——框架稳定后功能组入只需在状态机 when 里补分支，这正是 6 天后 `0793b17d`（随机动作+单双击）能小步组入的原因。
- **可测试性设计前置**：首期提交就带上 MiniFakes + 场景测试，核心层零 Android 依赖；"手动时钟"替代 sleep 是车机定时器密集型逻辑的推荐测法。
- **遗留风险**：`PetKanziBridge.sendAction` 中 `setValue("", key, code)` 第一参数传空串、发送成功即回调 `onResult(true)`（并未等 Kanzi 真正执行完），协议键 `VPA.VPA_Action` 亦标注"临时键名，定稿后整表替换"——链路打通优先，真实回调契约留待协议定稿，存在动作回调语义与实际不符的风险。
