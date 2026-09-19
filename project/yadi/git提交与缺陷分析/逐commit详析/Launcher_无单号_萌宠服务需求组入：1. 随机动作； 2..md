# 无单号 · 萌宠服务需求组入：1. 随机动作；2. 单双击动作
- **提交**：`0793b17d` | 2026-09-14 | liang-jy | Launcher | feature
- **关联单**：无

## 需求/目标
在 `887ffe61` 搭好的萌宠框架上组入首批真实功能：①常驻待机循环中按洗牌池播放随机动作；②用户点击萌宠时随机播放点击动作（CLICK_1"放屁"/CLICK_2）并配音效。同时打通车辆信号（插枪/电量）、车机就绪、前后台切换等全部事件源，为后续需求铺路。

## 实现结构
15 个文件、+996/-114，是框架提交的"填肉"版：
- **PetStateMachine.kt**（+335）：核心扩张，新增插枪/电量/点击/IviReady/Render/ActionPlayStatus/TimerFired 七类事件处理，引入 `openingConsumed`（出场机会一次性）、`loopActive`、`soundWaiting/soundPlaying` 等状态位，形成"出场→常驻循环→随机动作→点击打断→回常驻"的完整生命周期。
- **PetIdleLoop.kt**：实现洗牌袋（shuffle bag）随机动作选取（相邻不重复、固定种子可复现）、常驻间隔区间随机、点击动作二选一。
- **PetSources.kt**（+107）：接入车辆信号（AC/DC 充电枪，复用 VehicleService 订阅链）与车机就绪回调，Kanzi 上报（点击/播放状态）折算为事件。
- **VehicleService.java**（+26）：新增 `OnIviReadyListener` 接口与粘性 `mIviReady` 标记，迟到的订阅者可补发就绪事件。
- **PetSoundPlayer.kt**：从占位实现升级为真播放（AudioFocusRequest + MediaPlayer + res/raw 占位音频）。
- **PetCore.kt / KanziDataSourceManager.java / Myapplication.kt**：新增 `USE_NEW_PET_SERVICE` 入口开关，新旧萌宠实现按开关分流；Kanzi 渲染启停作为前后台唯一判定源接入。
- **PetConfig.kt**（+81）：协议键、动作码、定时器 ID、临时开关（SWITCH_FORCE_ON / SOUND_PLAY_IMMEDIATELY）集中成表。
- **PetScenarios.kt**（+344）：场景表同步扩张覆盖新逻辑；新增两个 res/raw 音效文件。

数据流：车辆信号/IviReady（VehicleService）与 Kanzi 上报（点击/播放状态）→ PetSources 折算为 PetEvent → PetCore.onEvent（主线程串行）→ PetStateMachine 产出指令（PlayAction/PlaySound/ScheduleTimer...）→ dispatch 到 Bridge/播放器/定时器；播放状态回调与看门狗超时回灌状态机形成闭环。

## 关键代码
```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/pet/core/PetIdleLoop.kt
/** 洗牌袋：取空后按配置码池重洗，保证相邻两次不重复 */
private var bag: MutableList<PetAction> = mutableListOf()

fun nextAction(): PetAction {
    if (bag.isEmpty()) {
        bag = PetConfig.SHUFFLE_POOL_CODES
            .mapNotNull { code -> PetAction.entries.firstOrNull { it.code == code } }
            .toMutableList()
            .apply { shuffle(random) }
    }
    val next = bag.removeAt(bag.lastIndex)
    log.d(TAG, "nextAction: $next (bag left=${bag.size})")
    return next
}
```

```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/pet/core/PetStateMachine.kt
private fun onClicked(): List<PetCommand> {
    if (!isSwitchOn || !renderStarted) { return emptyList() }
    if (batteryLow) { return emptyList() }   // 低电量点击不响应
    // 循环中（常驻姿态或码池内随机动作）允许点击打断，完成后回常驻；
    // 放行集合从码池派生，池调整无需改此处
    val action = currentAction
    if (action == PetAction.RESIDENT || action?.code in PetConfig.SHUFFLE_POOL_CODES) {
        val click = idleLoop.pickClickAction()
        return issueActionWithSound(click)
    }
    return emptyList() // 出场进行中拒绝
}
```

```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/pet/core/PetStateMachine.kt
private fun onIviReady(): List<PetCommand> {
    if (!isSwitchOn) return emptyList()
    if (openingConsumed) {
        // 进程内第二次就绪（如休眠唤醒重握手）：出场机会已消耗，不重播
        return emptyList()
    }
    openingConsumed = true
    return if (renderStarted && !plugged) {
        playOpening()   // 就绪+前台+未插枪 -> 播出场动作
    } else {
        // 条件不满足：错过不补播，直接进入常驻循环
        loopActive = true
        if (renderStarted) enterResidentLoop() else emptyList()
    }
}
```

实现讲解：随机动作用"洗牌袋"替代纯随机，天然保证相邻两次不重复且每轮覆盖全池；`Random(RANDOM_SEED)` 固定种子让场景测试可断言。点击打断的放行集合直接从 `SHUFFLE_POOL_CODES` 派生，新增随机动作码不用改状态机。前后台以 Kanzi 渲染启停为唯一判定源，后台时统一取消三类定时器挂起循环、出场动作切后台即终止不补播，语义干净（"错过即错过"）。大量联调期妥协被显式钉在 PetConfig：`SWITCH_FORCE_ON`（Setting 未就绪强制开）、`SOUND_PLAY_IMMEDIATELY`（Kanzi 缺"动作开始"回调时下发即播音效）、看门狗 5s→20s，每个都带 TODO 注明恢复条件。

## 复盘与要点
- **框架提交的价值在组入时兑现**：本次 +996 行中状态机只是"when 补分支 + 新状态位"，协议/分发/测试基建零改动——印证了 `887ffe61` 协议先行的设计；洗牌袋、固定种子随机、事件驱动状态机三个手法都值得复用。
- **联调期妥协要"显式化"**：外部依赖（Setting 开关、Kanzi 回调）未就绪时不散落 magic 逻辑，而是集中为带 TODO 的配置开关+注释恢复条件，是处理"先跑通、后转正"的模范做法；风险是这些 TODO 依赖人工回收，建议登记到任务系统。
- **粘性事件与一次性机会**：`VehicleService.mIviReady` 粘性标记解决迟到订阅者补发；`openingConsumed` 把"出场"定义为进程级一次性机会，休眠唤醒重握手不重播——车机上电场景的状态语义想得很细。遗留风险：`PROP_BATTERY_SOC = -1`（信号号未提供），低电量点击抑制实际未生效。
