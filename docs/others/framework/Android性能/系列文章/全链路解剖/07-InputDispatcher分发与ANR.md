# 07 · InputDispatcher 分发与 ANR

确定目标后，Dispatcher 将事件写入连接，经 InputChannel 发布给应用；应用的 finish 回执推动 waitQueue 出队。

> 术语沿用：#6 EventEntry、#8 notifyMotion、#9 prepareDispatchCycleLocked、#10 deliverInputEvent、#11 InputChannel、#12 InputMessage、#16 S/R/R+/D 线程状态、#17 waker、#29 mInboundQueue、#30 waitQueue、#32 QueuedInputListener（00 篇术语表）。本篇新定义四个概念，见 §1。

## 0. 本篇范围与上下游边界

本篇走读 `dispatcher/InputDispatcher.cpp`（6401 行）的分发与回执段——`dispatchMotionLocked`（:1634-1697）全函数、`dispatchEventLocked`（:1753-1780）、`prepareDispatchCycleLocked`（:2894-2954）、`enqueueDispatchEntriesLocked`（:2956-2987）、`enqueueDispatchEntryLocked`（:2989-3133）、`startDispatchCycleLocked`（:3221-3402）、`finishDispatchCycleLocked`/`handleReceiveCallback`/`doDispatchCycleFinishedCommand`（:3444-3462/:3512-3586/:5724-5785）、ANR 链（`processAnrsLocked` :670-703、`getDispatchingTimeoutLocked` :705-716、`onAnrLocked` :5812-5845、`processConnectionUnresponsiveLocked` :5936-5954）、`AnrTracker.cpp`（73 行）、Latency 三件套（`LatencyTracker.cpp` 174 行、`InputEventTimeline.cpp` 86 行、`LatencyAggregator.cpp` 283 行），以及 #11 InputChannel 的发送侧（`include/input/InputTransport.h:237` class InputChannel、`libs/input/InputTransport.cpp` 的 `openInputChannelPair` :334-363、`sendMessage` :365-401、`InputPublisher::publishMotionEvent` :541-622、`receiveConsumerResponse` :691-717）。收端 JNI（`frameworks/base/core/jni/android_view_InputEventReceiver.cpp`，实际路径在 core/jni，契约批07 作业单所写 services/core/jni 为旧位）只取衔接点，展开"见批08"。

- **上游边界**：批06 交接点——`InputTarget` 列表组装完成、`dispatchEventLocked` 入口（批06 §6.6/:884 已证三通道顺序=vector 遍历序）。本篇从该入口接手，把事件送到 socket 并等待回执。
- **下游边界**：socket 对端。`InputChannel::sendMessage` 写出的 #12 InputMessage 到达应用进程后，消费与 finish 回执的**应用侧**动作（consumeEvents、finishInputEvent、sendFinishedSignal 的调用链）"见批08"；本篇只引收端两处锚点划定交接（§5.1）。
- **硬结论预告一**：契约批06 留下的未证项"`oq:`/`wq:` 计数轨在本样本可见性未查证"已翻转——**本 trace 三通道的 oq/wq 计数轨全部存在且有 1790 个采样**（§4.5，Q1），它们把"发布→waitQueue→回执"三步变成逐 ns 可读的计数序列，本篇用它把 prepare→deliver 的 429,654 ns 拆到动作级（§11）。
- **硬结论预告二**：`publishMotionEvent` 的 ATRACE slice 在本 trace 不存在——`InputTransport.cpp` 未定义 `ATRACE_TAG`，按 cutils/trace.h:86-87 缺省规则取 `ATRACE_TAG_NEVER`，其内 12 处 ATRACE 全部失效（§3.1，源码+trace 双向已证）。
- 主线先闭环：§3 走读完"targets→逐连接入队"、§4 走读完"发布写 socket"、§5 走读完"回执出队"，分发-回执周期即闭合；其后 §6 是 ANR 链，§7 是 Latency 三件套，§8 是堆积排空，§9-§14 是辅助路径、索引、关联、边界、汇总。

## 1. 本篇新定义的四个概念

1. **发布-回执周期**：单条连接上一次完整投递的动作序列——`startDispatchCycleLocked` 从 `outboundQueue` 取 `DispatchEntry`（:3232-3236）→ `publishMotionEvent` 序列化并写 socket（:3293-3311 → `InputTransport.cpp:621`）→ 条目迁入 #30 waitQueue 并登记 ANR 计时（:3390-3400）→ 应用回执后 `doDispatchCycleFinishedCommand` 出队（:5761-5781）→ 无条件重启下一周期（:5784）。每连接独立循环，互不等待。
2. **连接分发超时**：本篇对"ANR 阈值取值"的命名。三源按优先级取值（`getDispatchingTimeoutLocked`，:705-716）：monitor 连接取 `mMonitorDispatchingTimeout`；窗口连接取 WMS 随窗口信息下发的 `dispatchingTimeout`；两者皆无取 `DEFAULT_INPUT_DISPATCHING_TIMEOUT`（:80-82，`IInputConstants.aidl:26` 的 5000 ms × `HwTimeoutMultiplier`）。
3. **ANR 计时总账**：`InputDispatcher.h:528` 的 `AnrTracker mAnrTracker`——`multiset<pair<timeoutTime, token>>`（`AnrTracker.h:55`）。发布成功登记（:3396-3399）、回执出队注销（:5766）、连接移除按 token 全删（:5720）、全量复位清空（:5195）四个同步点维持它与各连接 #30 waitQueue 的对应关系；`processAnrsLocked` 每圈取 `firstTimeout()` 决定下次醒来的时刻（:687）。
4. **Timeline 信号**：应用进程经 #11 InputChannel 反向发给 dispatcher 的 `InputMessage::Type::TIMELINE` 消息（`InputTransport.h` Type 枚举内；`InputConsumer::sendTimeline`，`InputTransport.cpp:1274-1289`），载荷是 `GraphicsTimeline` 两字段——`GPU_COMPLETED_TIME`（应用把 buffer 交给 SurfaceFlinger 的时刻）与 `PRESENT_TIME`（帧上屏时刻），定义在 `include/input/Input.h:305-314`。dispatcher 侧由 `InputPublisher::receiveConsumerResponse` 解出（:710-714），交给 `mLatencyTracker.trackGraphicsLatency`（:3546-3554）。

## 2. 数据底座：DispatchEntry 发布期字段与 Connection 状态机

分发段的每一步都读写在批06 §2 引入过的 `DispatchEntry`（`Entry.h:221-253`）与 `Connection`（`Connection.h:31-74`）上。三问三答——规则：发布期字段由发布动作写入、由回执动作消费；谁设置：分发线程独占（全程持 mLock）；谁执行：`startDispatchCycleLocked` 写、`doDispatchCycleFinishedCommand` 读；后果：`deliveryTime`/`timeoutTime` 成为 ANR 判定与时延统计的两个锚点。

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3232-3236
    while (connection->status == Connection::Status::NORMAL && !connection->outboundQueue.empty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.front();
        dispatchEntry->deliveryTime = currentTime;
        const std::chrono::nanoseconds timeout = getDispatchingTimeoutLocked(connection);
        dispatchEntry->timeoutTime = currentTime + timeout.count();
```

走读：出队时刻即 `deliveryTime`（"the event was actually delivered"的取值点，`Entry.h` 注释口径）；`timeoutTime` = 当前时刻 + 概念 2 的超时值——这两个字段随条目走完整个发布-回执周期。Connection 的三态 `status`（NORMAL/BROKEN/ZOMBIE，`Connection.h:36-46`）与 `responsive` 位（:54-57）决定每一步的准入：prepare 跳过非 NORMAL（:2914-2921），目标裁决跳过非 responsive（批06 §6.3 的 `hasResponsiveConnectionLocked` :4576-4597），ANR 到期把 responsive 置假（:698）。

```cpp
// frameworks/native/services/inputflinger/dispatcher/Connection.h:59-64
    // Queue of events that need to be published to the connection.
    std::deque<DispatchEntry*> outboundQueue;

    // Queue of events that have been published to the connection but that have not
    // yet received a "finished" response from the application.
    std::deque<DispatchEntry*> waitQueue;
```

走读：双队列分工以注释原文为证——outboundQueue"待发布"，waitQueue"已发布未回执"。迁移点只有一处（:3390-3395），出队点只有两处（:5764 正常回执、:3475 拆链清空）。源码学习副本的批注位置：Connection.h:30-35（类头块，批0 时代既有，本批核对一致）。

## 3. dispatchMotionLocked 全函数：从 entry 到逐连接入队（机制走读①）

### 3.1 全函数骨架与 ATRACE 的作用域边界

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:1634-1650
bool InputDispatcher::dispatchMotionLocked(nsecs_t currentTime, std::shared_ptr<MotionEntry> entry,
                                           DropReason* dropReason, nsecs_t* nextWakeupTime) {
    ATRACE_CALL();
    // Preprocessing.
    if (!entry->dispatchInProgress) {
        entry->dispatchInProgress = true;

        logOutboundMotionDetails("dispatchMotion - ", *entry);
    }

    // Clean up if dropping the event.
    if (*dropReason != DropReason::NOT_DROPPED) {
        setInjectionResult(*entry,
                           *dropReason == DropReason::POLICY ? InputEventInjectionResult::SUCCEEDED
                                                             : InputEventInjectionResult::FAILED);
        return true;
    }
```

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:1686-1697
    // Add monitor channels from event's or focused display.
    addGlobalMonitoringTargetsLocked(inputTargets, getTargetDisplayId(*entry));

    // Dispatch the motion.
    if (conflictingPointerActions) {
        CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                                   "conflicting pointer actions");
        synthesizeCancelationEventsForAllConnectionsLocked(options);
    }
    dispatchEventLocked(currentTime, entry, inputTargets);
    return true;
}
```

走读：批06 §6.1 已走读前半（分流、PENDING 出口、失败出口），本篇补两个尾段事实。其一，monitor 追加（:1687）发生在目标裁决**之后**、扇出之前——全局 monitor 永远排在 targets 尾段（批06 §6.6 已证顺序，此处补调用位置行号）。其二，`conflictingPointerActions`（同设备两个未决手势动作冲突，裁决期间由 `findTouchedWindowTargetsLocked` 经出参置位，:1663 传入 :1690 消费）触发时向**所有连接**广播 CANCEL 后照常派发本事件——广播与派发不互斥。`ATRACE_CALL()`（:1636）在函数作用域，trace 的 `dispatchMotionLocked` slice（725,270 ns，`6,830,475,418` 起）覆盖其全部子 slice（Q5：`dispatchEventLocked` 649,230 ns 为其 depth-1 子 slice）。

**ATRACE 作用域边界（已证，源码+trace 双向）**：本文件有两种打点形态。`ATRACE_CALL()`（:1636/:1756）在函数作用域，slice 覆盖全函数；而 `prepareDispatchCycleLocked`/`enqueueDispatchEntriesLocked`/`enqueueDispatchEntryLocked`/`startDispatchCycleLocked` 四个函数用的是 **if 块作用域**的 `ATRACE_NAME`——以 `enqueueDispatchEntryLocked` 为例：

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2989-3002（节选）
void InputDispatcher::enqueueDispatchEntryLocked(const sp<Connection>& connection,
                                                 std::shared_ptr<EventEntry> eventEntry,
                                                 const InputTarget& inputTarget,
                                                 int32_t dispatchMode) {
    if (ATRACE_ENABLED()) {
        std::string message = StringPrintf("enqueueDispatchEntry(inputChannel=%s, dispatchMode=%s)",
                                           connection->getInputChannelName().c_str(),
                                           dispatchModeToString(dispatchMode).c_str());
        ATRACE_NAME(message.c_str());
    }
    int32_t inputTargetFlags = inputTarget.flags;
    if (!(inputTargetFlags & dispatchMode)) {
        return;
    }
```

走读：`ATRACE_NAME` 对象在 :2998 的 `}` 处析构——slice 只覆盖"拼名字＋打点"这几行（5 μs 量级），函数真正的裁决与入队动作（:3000-3133）发生在 slice **之外**。trace 判据：Q5 中六条 `enqueueDispatchEntry(dispatchMode=…)` 小 slice（5,231-5,539 ns）依次紧随，而 `oq:AppList` 的 0→1 采样（入队动作，:3132）出现在 AS_IS 小 slice 结束（6,830,652,842）之后 20,153 ns（6,830,672,995，Q2）。**读本 trace 时这四个名字的 slice 时长不代表函数执行时长**；`dispatchMotionLocked`/`dispatchEventLocked`/`findTouchedWindowTargetsLocked`（批06 §6.7 引的 39,538 ns）三个 `ATRACE_CALL` slice 才覆盖全函数。同一形态核对其余三处：`prepareDispatchCycleLocked` :2898-2903、`enqueueDispatchEntriesLocked` :2960-2965、`startDispatchCycleLocked` :3223-3227 全部 if 块作用域。

**publishMotionEvent slice 缺席（已证）**：`InputPublisher::publishMotionEvent` 内也有 ATRACE（`InputTransport.cpp:550-555`），但 `InputTransport.cpp` 全文无 `#define ATRACE_TAG`（grep 仅 `InputDispatcher.cpp:18` 有 `#define ATRACE_TAG ATRACE_TAG_INPUT`）；cutils 缺省规则如下：

```cpp
// system/core/libcutils/include/cutils/trace.h:86-87
#ifndef ATRACE_TAG
#define ATRACE_TAG ATRACE_TAG_NEVER
```

走读：`ATRACE_TAG_NEVER` = 0（:50），`ATRACE_ENABLED()` 对 0 标签恒假——`InputTransport.cpp` 内 12 处 ATRACE（含 `publishKeyEvent` :505-508、`publishMotionEvent` :550-555）全部失效。trace 判据：Q5 在 0x3b3b7f02 的完整发布窗口（6,830,540,000-6,831,330,000）列出 tid 1488 全部 slice，无任何 `publishMotionEvent` 条目（`批07-查询原始输出.txt` Q5 注）。发布段在 trace 中的可见痕迹只有 InputDispatcher.cpp 侧的 slice 与 oq/wq 计数采样。

### 3.2 dispatchEventLocked：扇出点与 pokeUserActivity

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:1753-1771
void InputDispatcher::dispatchEventLocked(nsecs_t currentTime,
                                          std::shared_ptr<EventEntry> eventEntry,
                                          const std::vector<InputTarget>& inputTargets) {
    ATRACE_CALL();
    if (DEBUG_DISPATCH_CYCLE) {
        ALOGD("dispatchEventToCurrentInputTargets");
    }

    updateInteractionTokensLocked(*eventEntry, inputTargets);

    ALOG_ASSERT(eventEntry->dispatchInProgress); // should already have been set to true

    pokeUserActivityLocked(*eventEntry);

    for (const InputTarget& inputTarget : inputTargets) {
        sp<Connection> connection =
                getConnectionLocked(inputTarget.inputChannel->getConnectionToken());
        if (connection != nullptr) {
            prepareDispatchCycleLocked(currentTime, connection, eventEntry, inputTarget);
```

走读：扇出三步——`updateInteractionTokensLocked`（:3150-3198，仅向事件日志写"Interaction with"串，DEBUG 域外无行为）、`pokeUserActivityLocked`（:1765，向 PowerManagerService 报告用户活动）、逐 target 查 Connection 调 prepare（:1767-1771）。连接不存在则跳过（:1772-1778 仅 DEBUG 日志）。trace 判据：`pokeUserActivity` 两条 slice（`6,831,220,072`/`6,831,234,226`，8,308/5,654 ns）出现在三通道 prepare 全部之后且 depth=0（Q5 尾部；批06 §6.8 已引），与 :1765 在扇出循环之前、slice 内经由 policy binder 调用产生的时序一致。

### 3.3 prepareDispatchCycleLocked：连接准入与拆分（机制四问① 上半）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2894-2953（节选）
void InputDispatcher::prepareDispatchCycleLocked(nsecs_t currentTime,
                                                 const sp<Connection>& connection,
                                                 std::shared_ptr<EventEntry> eventEntry,
                                                 const InputTarget& inputTarget) {
    if (ATRACE_ENABLED()) {
        std::string message =
                StringPrintf("prepareDispatchCycleLocked(inputChannel=%s, id=0x%" PRIx32 ")",
                             connection->getInputChannelName().c_str(), eventEntry->id);
        ATRACE_NAME(message.c_str());
    }
    ...
    if (connection->status != Connection::Status::NORMAL) {
        ...
        return;
    }

    // Split a motion event if needed.
    if (inputTarget.flags & InputTarget::FLAG_SPLIT) {
        ...
            std::unique_ptr<MotionEntry> splitMotionEntry =
                    splitMotionEvent(originalMotionEntry, inputTarget.pointerIds);
            ...
            enqueueDispatchEntriesLocked(currentTime, connection, std::move(splitMotionEntry),
                                         inputTarget);
            return;
    }
    ...
    // Not splitting.  Enqueue dispatch entries for the event as is.
    enqueueDispatchEntriesLocked(currentTime, connection, eventEntry, inputTarget);
}
```

走读（机制四问① 上半：prepare 的职责）：**prepare 是"连接级准入"**——它不做事件加工，只做三件连接属性相关的判定：①slice 打点（slice 名携带通道名与事件 id，本样本 AppList 通道 slice 名内 `id=0x3b3b7f02`）；②status 非 NORMAL 直接 return（:2914-2921）；③FLAG_SPLIT 时调 `splitMotionEvent` 把多指事件按目标 `pointerIds` 拆成子事件（:2924-2950，拆出的子事件若为 CANCEL 另记 `LOGTAG_INPUT_CANCEL` 事件日志）后**提前 return**，不拆则把原 entry 交给下一层。本样本单窗单指无 split（批06 §6.7 已证），走 :2953 直通。`splitMotionEvent` 的拆分实现（深拷贝子集坐标、pointerProperties 校验）在 :3747 起的独立函数，机制上属裁决侧补充，本样本不触发，不展开（未触发即无 trace 对应物，无断言）。

### 3.4 enqueueDispatchEntriesLocked：六模式循环与 wasEmpty（机制四问① 中半）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2960-2986
    ...
    bool wasEmpty = connection->outboundQueue.empty();

    // Enqueue dispatch entries for the requested modes.
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_OUTSIDE);
    ...
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER);

    // If the outbound queue was previously empty, start the dispatch cycle going.
    if (wasEmpty && !connection->outboundQueue.empty()) {
        startDispatchCycleLocked(currentTime, connection);
    }
```

走读（机制四问① 中半：enqueueDispatchEntriesLocked 的职责）：**它是"模式展开器＋周期启动器"**——固定顺序尝试六种 dispatch 模式（批06 §6.8 已引全表），每模式调一次 :2989；随后 wasEmpty 判定：只有出队前 outboundQueue 为空且本次至少建了一条，才就地启动发布周期（:2984-2986）。wasEmpty 为假（上一周期未回执）时**只排队不启动**——启动由 :5784 的回执路径接管。trace 判据：Q5 中三通道各一条 `enqueueDispatchEntriesLocked` slice（5,423-5,769 ns），其内六条小 slice（Q5 全列）；`startDispatchCycleLocked` slice 出现在六条小 slice 之后（AppList：6,830,707,457）——if 块作用域（§3.1）使三层 slice 呈"平铺"而非嵌套，顺序仍是代码序。

### 3.5 enqueueDispatchEntryLocked：建条目、改写 action、入队（机制四问① 下半）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3033-3088（节选）
        case EventEntry::Type::MOTION: {
            const MotionEntry& motionEntry = static_cast<const MotionEntry&>(newEntry);
            ...
            if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_OUTSIDE) {
                dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_OUTSIDE;
            } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT) {
                dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_EXIT;
            } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER) {
                dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_ENTER;
            } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT) {
                dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_CANCEL;
            } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER) {
                dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_DOWN;
            } else {
                dispatchEntry->resolvedAction = motionEntry.action;
                dispatchEntry->resolvedEventId = motionEntry.id;
            }
            ...
            dispatchEntry->resolvedEventId =
                    dispatchEntry->resolvedEventId == DEFAULT_RESOLVED_EVENT_ID
                    ? mIdGenerator.nextId()
                    : motionEntry.id;
```

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3125-3133
    // Remember that we are waiting for this dispatch to complete.
    if (dispatchEntry->hasForegroundTarget()) {
        incrementPendingForegroundDispatches(newEntry);
    }

    // Enqueue the dispatch entry.
    connection->outboundQueue.push_back(dispatchEntry.release());
    traceOutboundQueueLength(*connection);
}
```

走读（机制四问① 下半：enqueueDispatchEntryLocked 的职责）：**它是"事件加工器"**——五道动作：①模式过滤（:3000-3002，不含即 return，六模式五空转的出处）；②action 改写——本摘录五种模式把 resolvedAction 换成对应合成动作，仅 AS_IS 保留原 action 且把 resolvedEventId 定为原事件 id（:3051-3052）；非 AS_IS 模式的 resolvedEventId 走 :3085-3088 生成新 id，改写发生时 :3089-3094 会打一条 "Transmute MotionEvent(id=0x…)" 记录（本样本三通道全 AS_IS，无 transmute，Q5 无该名 slice，已证）；③`trackKey`/`trackMotion` 连接级一致性校验（:3075-3083，失序/重复事件跳过不入队——`InputState` 维护"已发给该应用的动作序列"基准）；④OBSCURED 位叠加（:3068-3073）与 pointer-down-outside 焦点通知（:3102-3103，经命令队列回调 policy）；⑤push 进 outboundQueue（:3131）并采样 oq 计数轨（:3132）。前台目标计数 `incrementPendingForegroundDispatches`（:3126-3128，实现 ：4495-4500）只对挂 `InjectionState` 的条目生效——给 `injectionState->pendingForegroundDispatches` 加一，供同步注入等待"前台分发全部完成"的条件变量用（:4375-4378 等待、:4502-4510 减到零 notify）；本样本事件经 filter 回注挂有 InjectionState（批06 §2），故照常 +1，普通 Reader 事件为空操作。

**prepare 与 enqueueDispatchEntry 的职责分工（机制四问① 收口）**：prepare 管"这条连接能不能收、收哪份事件"（准入＋拆分），enqueueDispatchEntryLocked 管"收到的目标要变成什么消息"（模式过滤＋action/id 改写＋一致性校验＋入队）。前者一次调用，后者按模式一至六次调用；两者由 enqueueDispatchEntriesLocked 串联。四问归档——规则：连接状态门（prepare）先于事件变换门（enqueue）；谁设置：status 由拆链/注销维护，inputTarget.flags 由批06 §6 的裁决产出；谁执行：分发线程；后果：任何一道门不过，该通道就没有 oq 采样、没有发布、没有回执——trace 判据为"该通道无 oq/wq 活动"。

## 4. 发布：startDispatchCycleLocked → publishMotionEvent → sendMessage（机制走读②）

InputChannel 是连接系统分发器和应用窗口的输入通道，负责传递事件及完成回执。本节从分发器一侧解释事件如何写入通道，以及通道暂时不可写时如何处理。

### 4.1 发布循环与坐标加工

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3262-3290（节选）
                PointerCoords scaledCoords[MAX_POINTERS];
                const PointerCoords* usingCoords = motionEntry.pointerCoords;

                // Set the X and Y offset and X and Y scale depending on the input source.
                if ((motionEntry.source & AINPUT_SOURCE_CLASS_POINTER) &&
                    !(dispatchEntry->targetFlags & InputTarget::FLAG_ZERO_COORDS)) {
                    float globalScaleFactor = dispatchEntry->globalScaleFactor;
                    if (globalScaleFactor != 1.0f) {
                        for (uint32_t i = 0; i < motionEntry.pointerCount; i++) {
                            scaledCoords[i] = motionEntry.pointerCoords[i];
                            ...
                            scaledCoords[i].scale(globalScaleFactor, 1 /* windowXScale */,
                                                  1 /* windowYScale */);
                        }
                        usingCoords = scaledCoords;
                    }
                } else {
                    ...
                }

                std::array<uint8_t, 32> hmac = getSignature(motionEntry, *dispatchEntry);
```

走读：发布前坐标加工两处（源码学习副本的批注位置 InputDispatcher.cpp:3372-3375，本批新增）：全局缩放只乘 X/Y、窗口变换不乘（缩放值随消息发回，应用取相对坐标时再用——注释原文 :3272-3274）；`FLAG_ZERO_COORDS`（批06 §6.3 的 OUTSIDE 副本防泄露）把全部坐标清零。`getSignature`（:3420-3434）只对 DOWN/UP 签 HMAC，MOVE 返回 `INVALID_HMAC`（:3423-3427）。随后按事件类型六路 publish（KEY :3242-3256、MOTION :3259-3312、FOCUS :3315-3321、TOUCH_MODE :3323-3331、CAPTURE :3333-3340、DRAG :3342-3349）；MOTION 路传 `dispatchEntry->resolvedEventId`（批06 §4.7 的回注 id 保留在此兑现为消息字段）与 `resolvedAction`。

### 4.2 InputChannel 发送侧：SEQPACKET 与 32KB（#11/#12 的物理规则）

```cpp
// frameworks/native/libs/input/InputTransport.cpp:334-351（节选）
status_t InputChannel::openInputChannelPair(const std::string& name,
                                            std::unique_ptr<InputChannel>& outServerChannel,
                                            std::unique_ptr<InputChannel>& outClientChannel) {
    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets)) {
        ...
    }

    int bufferSize = SOCKET_BUFFER_SIZE;
    setsockopt(sockets[0], SOL_SOCKET, SO_SNDBUF, &bufferSize, sizeof(bufferSize));
    setsockopt(sockets[0], SOL_SOCKET, SO_RCVBUF, &bufferSize, sizeof(bufferSize));
    setsockopt(sockets[1], SOL_SOCKET, SO_SNDBUF, &bufferSize, sizeof(bufferSize));
    setsockopt(sockets[1], SOL_SOCKET, SO_RCVBUF, &bufferSize, sizeof(bufferSize));
```

```cpp
// frameworks/native/libs/input/InputTransport.cpp:47-47
static const size_t SOCKET_BUFFER_SIZE = 32 * 1024;
```

走读：`AF_UNIX + SOCK_SEQPACKET` 数据报语义保证一条 #12 InputMessage 整包原子送达（不粘包、不半读）；两端收发缓冲各 32KB（:347-351，`SOCKET_BUFFER_SIZE` :47）——这是"应用不读时事件能积压多少"的容量依据（源码学习副本的批注位置 InputTransport.cpp:43-51，本批新增：按原注释"a few dozen large multi-finger motion events"口径，满指针 MOTION 消息约 1KB 量级，32KB 可容纳几十条）。通道两端名与 token：server 端名加 " (server)"、client 端加 " (client)"（:355-361），共享同一 BBinder token（:353）——trace 通道名 `1b4998d com.yadea.launcher/com.yadea.launcher.function.applist.AppListActivity (server)` 的后缀即此。通道创建入口 `createInputChannel`（`InputDispatcher.cpp:5461` 区间，源码学习副本的批注位置 :5592-5596）与 monitor 注册（:5500，源码学习副本的批注位置 :5632-5642）批06 已审计。

```cpp
// frameworks/native/libs/input/InputTransport.cpp:365-401（节选）
status_t InputChannel::sendMessage(const InputMessage* msg) {
    const size_t msgLength = msg->size();
    InputMessage cleanMsg;
    msg->getSanitizedCopy(&cleanMsg);
    ssize_t nWrite;
    do {
        nWrite = ::send(getFd(), &cleanMsg, msgLength, MSG_DONTWAIT | MSG_NOSIGNAL);
    } while (nWrite == -1 && errno == EINTR);

    if (nWrite < 0) {
        int error = errno;
        ...
        if (error == EAGAIN || error == EWOULDBLOCK) {
            return WOULD_BLOCK;
        }
        if (error == EPIPE || error == ENOTCONN || error == ECONNREFUSED || error == ECONNRESET) {
            return DEAD_OBJECT;
        }
        return -error;
    }

    if (size_t(nWrite) != msgLength) {
        ...
        return DEAD_OBJECT;
    }
    ...
    return OK;
}
```

走读（源码学习副本的批注位置 InputTransport.cpp:389-427，本批修正三处后核对一致）：发送四规则——①`getSanitizedCopy`（:368；其本体 :165 起 memset 0 后逐字段拷贝，清零 padding）使写出的是净化副本，调用方对象不变；②`MSG_DONTWAIT` 非阻塞＋`MSG_NOSIGNAL` 防对端死亡触发 SIGPIPE（:371）；③错误码映射 EAGAIN/EWOULDBLOCK→WOULD_BLOCK、EPIPE/ECONNRESET 等→DEAD_OBJECT（:380-385）；④仅 EINTR 重试（:370-372）。`nWrite != msgLength` 分支（:389-395）在 SEQPACKET 语义下正常路径不可达，属防御检查。**非阻塞语义的后果**：send 永不睡眠——缓冲满立即返回 WOULD_BLOCK，把"等不等"的决定权交回调用方（§4.3）。

### 4.3 写满的后果：两种处置（机制四问②）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3360-3388（节选）
        // Check the result.
        if (status) {
            if (status == WOULD_BLOCK) {
                if (connection->waitQueue.empty()) {
                    ALOGE("channel '%s' ~ Could not publish event because the pipe is full. "
                          "This is unexpected because the wait queue is empty, so the pipe "
                          "should be empty and we shouldn't have any problems writing an "
                          "event to it, status=%s(%d)",
                          ...);
                    abortBrokenDispatchCycleLocked(currentTime, connection, true /*notify*/);
                } else {
                    // Pipe is full and we are waiting for the app to finish process some events
                    // before sending more events to it.
                    ...
                }
            } else {
                ...
                abortBrokenDispatchCycleLocked(currentTime, connection, true /*notify*/);
            }
            return;
        }
```

走读（机制四问②：socket 写满的后果）。规则：写满（WOULD_BLOCK）分两种——waitQueue 空（计数失衡，说明通道异常）走 `abortBrokenDispatchCycleLocked` 拆链（:3361-3370；该函数 ：3464-3495 清空双队列、置 BROKEN、经命令回调 `notifyInputChannelBroken`，源码学习副本的批注位置 :3585-3588 本批新增）；waitQueue 非空（应用未消费完）**既不重试也不丢弃**——`return` 把条目留在 outboundQueue 队首。后果三连：①该连接的发布暂停，直到下一轮回执触发 ：5784 重启周期重试 publish；②**不影响其他连接**（每连接独立队列与周期，批06 C4 论证的前提）；③**不影响 mInboundQueue 出队**——后续事件照常弹出、照常 prepare、照常在本连接 outboundQueue 排队（oq 计数升到 2、3……），应用追上后逐条补发。下一事件如何排队的完整 trace 证据见 §8。非 WOULD_BLOCK 的错误（含 DEAD_OBJECT）一律拆链（:3380-3386）。

### 4.4 oq→waitQueue 迁移与 ANR 计时登记（机制走读③ 起点）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3390-3401
        // Re-enqueue the event on the wait queue.
        connection->outboundQueue.erase(std::remove(connection->outboundQueue.begin(),
                                                    connection->outboundQueue.end(),
                                                    dispatchEntry));
        traceOutboundQueueLength(*connection);
        connection->waitQueue.push_back(dispatchEntry);
        if (connection->responsive) {
            mAnrTracker.insert(dispatchEntry->timeoutTime,
                               connection->inputChannel->getConnectionToken());
        }
        traceWaitQueueLength(*connection);
    }
}
```

走读：发布成功才迁移——erase 出 outboundQueue（采样 oq，:3394）、push 进 waitQueue（:3395）、`responsive` 为真才向概念 3 的 ANR 计时总账登记 `(timeoutTime, token)`（:3396-3399，源码学习副本的批注位置 :3503-3509 本批新增），最后采样 wq（:3400）。**这是连接级 ANR 的计时起点**：起算时刻不是事件发生（eventTime）、不是裁决完成，而是"写 socket 成功"这一步；timeoutTime 在 :3236 已按概念 2 定值。失响应连接不登记（:3397 条件）——注释原文见 `InputDispatcher.h:523-527`（"Once a connection becomes unresponsive, its entries are removed from AnrTracker to prevent unneeded wakeups"）。

### 4.5 trace 配对：0x3b3b7f02 的发布逐动作表（oq/wq 计数轨，新证据）

批06 §10 第 7 项未证状态"oq/wq 计数轨可见性未查证"的查证结果（Q1/Q2，新数值留痕于 `批07-查询原始输出.txt`）：**六条计数轨全部存在**——`oq:`/`wq:` ×三通道，各 1,790 个采样（Gesture Monitor 的 wq 1,789 个），轨名 `oq:1b4998d com.yadea.launcher/com.yadea`（40 字符缓冲截断，`InputDispatcher.cpp:6166` 的 `char counterName[40]`）等。0x3b3b7f02 发布窗口的逐动作采样（rel ns）：

| rel_ts | 轨 | 值变化 | 代码动作（行号） | 对应 slice |
|---|---|---|---|---|
| 6,830,672,995 | oq:AppList | 0→1 | enqueueDispatchEntryLocked(AS_IS) push（:3131-3132） | AS_IS 小 slice 已于 6,830,652,842 结束（§3.1 if 作用域） |
| 6,830,794,765 | oq:AppList | 1→0 | startDispatchCycleLocked erase（:3391-3394） | startDispatchCycle slice 6,830,707,457+5,269 已结束 |
| 6,830,811,995 | wq:AppList | 0→1 | waitQueue push＋mAnrTracker.insert（:3395-3399） | 同上 |
| 6,830,902,649 | oq:GestureMonitor | 0→1 | 同构 | startDispatchCycle(GM) 6,830,937,342 |
| 6,830,992,726 | oq:GestureMonitor | 1→0 | 同构 | — |
| 6,831,001,572 | wq:GestureMonitor | 0→1 | 同构 | — |
| 6,831,091,649 | oq:PointerEventDispatcher2 | 0→1 | 同构 | startDispatchCycle(PED2) 6,831,128,611 |
| 6,831,178,726 | oq:PointerEventDispatcher2 | 1→0 | 同构 | — |
| 6,831,186,995 | wq:PointerEventDispatcher2 | 0→1 | 同构 | — |

走读：三通道各得"入 oq→发布→入 wq"三点，采样顺序与批06 §6.7 的 prepare 顺序一致（AppList→GestureMonitor→PointerEventDispatcher2）。**AppList 的 publish 写 socket 时刻被夹逼**：startDispatchCycle slice 结束（6,830,712,726）之后、Launcher 主线程 sched_wakeup（`6,830,764,842`，批0 复验二 §一：waker=InputDispatcher tid=1488）之前——send() 完成使内核唤醒对端，耗时上界 52,116 ns；其后分发线程继续做 erase/push 簿记（6,830,794,765/6,830,811,995）。wq 轨首次把"该事件已发出、等回执"变成可测状态：**waitQueue 驻留时长**由此可算（§5.5）。oq 空转五模式不采样（未建条目即 :3000-3002 return，未到 :3132）——Q2 中每通道恰好一对 0→1/1→0 采样，与批06 §6.8"六条小 slice 仅 AS_IS 建条目"互证。

## 5. 回执链：从应用 finish 到 waitQueue 出队（机制走读④）

### 5.1 应用的回执消息与本篇边界

应用侧 `finishInputEvent` → `NativeInputEventReceiver::finishInputEvent`（`android_view_InputEventReceiver.cpp:155-166`）→ `mOutboundQueue` → `InputConsumer::sendFinishedSignal`（`InputTransport.cpp:1225-1271`，实写 `sendUnchainedFinishedSignal` :1305-1319，携带 consumeTime）→ 对端。消息结构：

```cpp
// frameworks/native/libs/input/InputTransport.cpp:691-717（节选）
android::base::Result<InputPublisher::ConsumerResponse> InputPublisher::receiveConsumerResponse() {
    ...
    InputMessage msg;
    status_t result = mChannel->receiveMessage(&msg);
    if (result) {
        return android::base::Error(result);
    }
    if (msg.header.type == InputMessage::Type::FINISHED) {
        return Finished{
                .seq = msg.header.seq,
                .handled = msg.body.finished.handled,
                .consumeTime = msg.body.finished.consumeTime,
        };
    }

    if (msg.header.type == InputMessage::Type::TIMELINE) {
        return Timeline{
                .inputEventId = msg.body.timeline.eventId,
                .graphicsTimeline = msg.body.timeline.graphicsTimeline,
        };
    }
```

走读（源码学习副本的批注位置 InputTransport.cpp:751-755，本批新增）：回执消息两种——FINISHED（seq/handled/consumeTime）与 TIMELINE（概念 4）；读到 WOULD_BLOCK 即 socket 暂空。应用侧何时调用 finish、consumeTime 取哪个时刻，全部"见批08"（其 §4.4 已证 `finishInputEvent` Java 链与 mSeqMap 反查）；本篇只取一个事实：**consumeTime 是应用随回执带回来的时刻**，dispatcher 侧原样收下（§7.1 用它算段时延）。

### 5.2 handleReceiveCallback：fd 可读的 Looper 回调

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3512-3563（节选）
int InputDispatcher::handleReceiveCallback(int events, sp<IBinder> connectionToken) {
    std::scoped_lock _l(mLock);
    sp<Connection> connection = getConnectionLocked(connectionToken);
    ...
    nsecs_t currentTime = now();
    bool gotOne = false;
    status_t status = OK;
    for (;;) {
        Result<InputPublisher::ConsumerResponse> result =
                connection->inputPublisher.receiveConsumerResponse();
        if (!result.ok()) {
            status = result.error().code();
            break;
        }

        if (std::holds_alternative<InputPublisher::Finished>(*result)) {
            const InputPublisher::Finished& finish =
                    std::get<InputPublisher::Finished>(*result);
            finishDispatchCycleLocked(currentTime, connection, finish.seq, finish.handled,
                                      finish.consumeTime);
        } else if (std::holds_alternative<InputPublisher::Timeline>(*result)) {
            if (shouldReportMetricsForConnection(*connection)) {
                ...
                mLatencyTracker
                        .trackGraphicsLatency(timeline.inputEventId,
                                              connection->inputChannel->getConnectionToken(),
                                              std::move(timeline.graphicsTimeline));
            }
        }
        gotOne = true;
    }
    if (gotOne) {
        runCommandsLockedInterruptable();
        if (status == WOULD_BLOCK) {
            return 1;
        }
    }
```

走读：回调注册在分发线程自己的 mLooper 上（批06 §6.5 引 :5529 的 addFd），fd 可读即触发；持 mLock 循环读到 WOULD_BLOCK 为止。两条分流：FINISHED → `finishDispatchCycleLocked`（**只投命令，不出队**，§5.3）；TIMELINE → `trackGraphicsLatency`（monitor 连接与非 responsive 连接被 `shouldReportMetricsForConnection`（:381-392）排除——gesture monitor"每帧都收"，计入会偏置统计，注释原文 :382-383）。读到 DEAD_OBJECT（对端关闭）→ :3584 `removeInputChannelLocked` 拆链（monitor 不告警，:3572-3576）。源码学习副本的批注位置 :3618-3626（批0 时代既有，本批核对一致）。

### 5.3 finishDispatchCycleLocked 与 doDispatchCycleFinishedCommand（机制四问④）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3444-3462
void InputDispatcher::finishDispatchCycleLocked(nsecs_t currentTime,
                                                const sp<Connection>& connection, uint32_t seq,
                                                bool handled, nsecs_t consumeTime) {
    ...
    if (connection->status == Connection::Status::BROKEN ||
        connection->status == Connection::Status::ZOMBIE) {
        return;
    }

    // Notify other system components and prepare to start the next dispatch cycle.
    auto command = [this, currentTime, connection, seq, handled, consumeTime]() REQUIRES(mLock) {
        doDispatchCycleFinishedCommand(currentTime, connection, seq, handled, consumeTime);
    };
    postCommandLocked(std::move(command));
}
```

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:5738-5785（节选）
    if (shouldReportFinishedEvent(*dispatchEntry, *connection)) {
        mLatencyTracker.trackFinishedEvent(dispatchEntry->eventEntry->id,
                                           connection->inputChannel->getConnectionToken(),
                                           dispatchEntry->deliveryTime, consumeTime, finishTime);
    }
    ...
    // Dequeue the event and start the next cycle.
    ...
    dispatchEntryIt = connection->findWaitQueueEntry(seq);
    if (dispatchEntryIt != connection->waitQueue.end()) {
        dispatchEntry = *dispatchEntryIt;
        connection->waitQueue.erase(dispatchEntryIt);
        const sp<IBinder>& connectionToken = connection->inputChannel->getConnectionToken();
        mAnrTracker.erase(dispatchEntry->timeoutTime, connectionToken);
        if (!connection->responsive) {
            connection->responsive = isConnectionResponsive(*connection);
            ...
        }
        traceWaitQueueLength(*connection);
        if (restartEvent && connection->status == Connection::Status::NORMAL) {
            connection->outboundQueue.push_front(dispatchEntry);
            traceOutboundQueueLength(*connection);
        } else {
            releaseDispatchEntry(dispatchEntry);
        }
    }

    // Start the next dispatch cycle for this connection.
    startDispatchCycleLocked(now(), connection);
}
```

走读（机制四问④：waitQueue 何时清空、下一帧何时可发）。清空条件：**按 seq 配对成功**（:5728-5731 找不到即早退——seq 已被清掉的迟到回执直接丢弃）且 post-event 判定走完。清空动作：erase（:5764）＋ANR 计时注销（:5766，概念 3 的第二个同步点）＋wq 采样（:5774）。`restartEvent` 为真时条目推回 outboundQueue **队首**重走（:5775-5777）；为假时释放（:5779，`releaseDispatchEntry` :3505-3510 含前台计数回收）。最后**无条件** `startDispatchCycleLocked(now(), connection)`（:5784）——outboundQueue 非空则补发（写满时积压的条目在此重试），为空则空转（while 条件 :3232 不成立，只多一条 slice）。**"下一帧/下一事件何时可发"的答案**：最早在本回执命令执行完的同一圈——同一 Running 窗口内，命令执行由 `dispatchOnce` 的命令段（批06 §3.2 :614-618）或回调内的 `runCommandsLockedInterruptable`（:3559）承载。restartEvent 的来源：`afterKeyEventLockedInterruptable`（:5974-6148）按键未处理时查 fallback 键映射，命中就把**同一条** KeyEntry 就地改写（keyCode 换目标键、flags 加 `AKEY_EVENT_FLAG_FALLBACK`，:6123-6138）并返回 true；`afterMotionEventLockedInterruptable` 恒返回 false（:6151-6155）——触摸无重投路径。源码学习副本的批注位置 :5864-5873（本批修正：原文"policy 在事件处理后仍可能改变主意（如 getUserActivity 联动）"与 A13 代码不符，A13 的重投来源是 fallback 键机制，已按 ：6123-6138 重写）。

### 5.4 回执的 dispatcher 侧处理时刻可被调度延迟拉开

trace 判据（Q4/Q6/Q6b，新数值留痕）：分发线程在第一轮分发结束后 S 615,769（6,831,375,265–6,831,991,034），随后 R 6,164,154（6,831,991,034 起，waker=system_server tid 756——批06 §4.9 的注入线程，主线程在该 R 窗口内完成后续注入并 wake）。批08 §11.3 的拆解（42,192 ns 收尾与 `6,832,783,072` 起 S 为其推算口径）给出：应用在 deliver 结束（6,832,740,880，批0 复验 §一）后约 42 μs 内完成 finishInputEvent 写链——即回执信号约在 6,832,78x 写入 socket（区间估计），而分发线程从 6,831,991,034 起处于 R（可运行未运行）直到 6,838,155,188。**回执被读出并处理的最早可能时刻因此被推到 Running 194,230 窗口（6,838,155,188–6,838,349,418）**：`wq:AppList` 1→0 采样实测落在其中的 `6,838,299,572`（Q2）。两点已证：①回执的 dispatcher 侧处理时刻 = 应用写信号时刻 + 分发线程调度延迟（此窗口内 R 6.164 ms＋R+ 4.906 ms 两段，归因批14）；②采样点代码归属唯一——健康连接上产生 wq 减一采样的位置只有 :5774（:3476 需 BROKEN 状态，与 6,843+ 的成功发布矛盾）。未证项：该采样点按 :5784 应紧随一条 `startDispatchCycleLocked(AppList)` slice，Q6b 窄窗（6,838,000,000-6,838,600,000）实测零 slice（Q6c 证明整个 system_server 该窗口零 slice）——trace 事件缺失候选（本 trace tokenizer errors 422、chunks overwritten 658 背景，批00 §3.1），该 slice 是否发出过不可判（未证，不用于任何归因）。

对照样本（已证，同一机制的正常形态）：Gesture Monitor 回执出队采样 `6,831,311,957`（wq 1→0，Q2）落在分发线程 Running 1,009,154 窗口内，其后 `6,831,322,226` 实测一条 `startDispatchCycleLocked([Gesture Monitor])` slice（Q5 末行，depth 0）——erase 采样与重启 slice 间隔 10,269 ns，与 :5774→:5784 的代码序逐点对应。PointerEventDispatcher2 的回执出队 `6,843,379,688`（Q2）与重启 slice `6,843,394,111`（Q6 首行）同构对应（间隔 14,423 ns）。

### 5.5 trace 配对：三通道 waitQueue 驻留时长

由 Q2 的 wq 0→1（发布）与 1→0（回执出队）两端点直接相减（新数值留痕）：

| 通道 | 发布入 wq | 回执出 wq | 驻留 | 说明 |
|---|---|---|---|---|
| Gesture Monitor | 6,831,001,572 | 6,831,311,957 | 310,385 ns | 消费者在 system_server UiThread（批06 §6.5），回执最快 |
| AppList 主通道 | 6,830,811,995 | 6,838,299,572 | 7,487,577 ns | 含应用处理 1,734,538 ns deliver（批0 复验 §一）＋两侧调度延迟 |
| PointerEventDispatcher2 | 6,831,186,995 | 6,843,379,688 | 12,192,693 ns | 消费者同样在 system_server，UiThread 繁忙时段被拉长 |

走读：三条驻留时长差一个量级以上且互不影响——**每连接独立回执**的直接证据（无全局屏障）；三条都远小于概念 2 的 5,000 ms 超时，本 trace 无 ANR 判定发生（§6.6）。驻留的内部构成（应用侧消费段"见批08"§11.3；dispatcher 侧等待段=调度延迟，批14 收口）。

## 6. ANR 链：计时、检查、落地（机制走读③）

输入 ANR 是系统检测到输入响应超过期限后的无响应判定。已发送事件等待应用确认，与找不到焦点窗口是不同的超时路径；要先确定具体类型和计时起点。

### 6.1 计时起点与终点（机制四问③）

**起点**：连接级——发布成功登记 `mAnrTracker.insert(timeoutTime, token)`（:3396-3399，§4.4）；无焦点窗口级——`findFocusedWindowTargetsLocked` 挂起时定 `mNoFocusedWindowTimeoutTime = currentTime + 应用分发超时`（:1898-1910，批06 §4.7 已引；触摸路径不经过，批06 E17）。**终点（注销）**：三个——回执出队 `mAnrTracker.erase(timeoutTime, token)`（:5766）；连接移除按 token 全删 `eraseToken`（:5720，`removeConnectionLocked`）；全量复位 `mAnrTracker.clear()`（:5195，`resetAndDropEverythingLocked`）。四问归档——规则：计时挂在"发布成功"事件上，注销挂在"回执/移除/复位"事件上；谁设置：分发线程（全部四点均持 mLock）；谁执行：`processAnrsLocked` 每圈消费；后果：应用处理慢→计时持续累积→到期触发 ANR 判定（§6.3）。源码学习副本的批注位置 InputDispatcher.h:530-541（本批新增同步点清单）。

```cpp
// frameworks/native/services/inputflinger/dispatcher/AnrTracker.cpp:26-40
void AnrTracker::insert(nsecs_t timeoutTime, sp<IBinder> token) {
    mAnrTimeouts.insert(std::make_pair(timeoutTime, std::move(token)));
}

/**
 * Erase a single entry only. If there are multiple duplicate entries
 * (same time, same connection), then only remove one of them.
 */
void AnrTracker::erase(nsecs_t timeoutTime, const sp<IBinder>& token) {
    auto pair = std::make_pair(timeoutTime, token);
    auto it = mAnrTimeouts.find(pair);
    if (it != mAnrTimeouts.end()) {
        mAnrTimeouts.erase(it);
    }
}
```

走读：`erase` 只删一条（注释原文与实现一致）——同一连接同一超时时刻的多条登记（不同 seq 的事件算出同 timeoutTime）只抵消一笔；按 token 全删是另一入口（`eraseToken` :42-50，连接消失时用）。`firstTimeout()`（:58-63）空表返回 `numeric_limits<nsecs_t>::max()`，与 :687 的 `std::min` 配合得到"无到期则睡到别的事件"的语义（源码学习副本的批注位置 AnrTracker.cpp:33-38/:46-50/:67-72，批0 时代既有，本批核对一致）。

### 6.2 超时值：三源取值

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:705-716
std::chrono::nanoseconds InputDispatcher::getDispatchingTimeoutLocked(
        const sp<Connection>& connection) {
    if (connection->monitor) {
        return mMonitorDispatchingTimeout;
    }
    const sp<WindowInfoHandle> window =
            getWindowHandleLocked(connection->inputChannel->getConnectionToken());
    if (window != nullptr) {
        return window->getDispatchingTimeout(DEFAULT_INPUT_DISPATCHING_TIMEOUT);
    }
    return DEFAULT_INPUT_DISPATCHING_TIMEOUT;
}
```

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:80-82
const std::chrono::duration DEFAULT_INPUT_DISPATCHING_TIMEOUT = std::chrono::milliseconds(
        android::os::IInputConstants::UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS *
        HwTimeoutMultiplier());
```

走读（概念 2 落地）：monitor 固定值；窗口值由 WMS 随 WindowInfo 下发（`getDispatchingTimeout` 带缺省参数）；缺省常量 = `IInputConstants.aidl:26` 的 `UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS = 5000` × `HwTimeoutMultiplier()`。`mMonitorDispatchingTimeout` 初值同缺省（:544），仅测试口 :6396 可改。AppList 主通道走窗口值分支；Gesture Monitor/PointerEventDispatcher2 走 monitor 分支。

### 6.3 每圈检查：processAnrsLocked

批06 §3.3 已引全函数（:670-703），此处只补连接级到期的三步（批06 节选省略的 :692-702）：

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:692-702
    // If we reached here, we have an unresponsive connection.
    sp<Connection> connection = getConnectionLocked(mAnrTracker.firstToken());
    if (connection == nullptr) {
        ALOGE("Could not find connection for entry %" PRId64, mAnrTracker.firstTimeout());
        return nextAnrCheck;
    }
    connection->responsive = false;
    // Stop waking up for this unresponsive connection
    mAnrTracker.eraseToken(connection->inputChannel->getConnectionToken());
    onAnrLocked(connection);
    return LONG_LONG_MIN;
```

走读：到期连接先 `responsive = false`（:698）——从此新目标裁决跳过它（批06 §6.3 的 :4576-4597 与 monitor 选取 ：1968-1988 都查 responsive）、§4.4 的登记条件（:3397）也不再为它计时；随后按 token 全删其登记（:700，"Stop waking up"注释原文）；最后 `onAnrLocked` 落地；返回 LONG_LONG_MIN 让本圈立即继续（批06 §3.2 唤醒期限语义）。

### 6.4 onAnrLocked 落地：上报与 CANCEL

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:5812-5845（节选）
void InputDispatcher::onAnrLocked(const sp<Connection>& connection) {
    ...
    // Since we are allowing the policy to extend the timeout, maybe the waitQueue
    // is already healthy again. Don't raise ANR in this situation
    if (connection->waitQueue.empty()) {
        ALOGI("Not raising ANR because the connection %s has recovered",
              connection->inputChannel->getName().c_str());
        return;
    }
    ...
    DispatchEntry* oldestEntry = *connection->waitQueue.begin();
    const nsecs_t currentWait = now() - oldestEntry->deliveryTime;
    std::string reason =
            android::base::StringPrintf("%s is not responding. Waited %" PRId64 "ms for %s",
                                        connection->inputChannel->getName().c_str(),
                                        ns2ms(currentWait),
                                        oldestEntry->eventEntry->getDescription().c_str());
    ...
    updateLastAnrStateLocked(getWindowHandleLocked(connectionToken), reason);

    processConnectionUnresponsiveLocked(*connection, std::move(reason));

    // Stop waking up for events on this connection, it is already unresponsive
    cancelEventsForAnrLocked(connection);
}
```

走读（源码学习副本的批注位置 :5987-5995，本批修正后核对一致）：三道工序——①waitQueue 已空则放弃 ANR（policy 可延长超时的窗口期里应用可能已追上，:5816-5822）；②以 waitQueue **最老条目**算等待时长并生成 reason（:5831-5837，注释论证了为什么取最老而不取"肇事"条目）；③`processConnectionUnresponsiveLocked`（:5936-5954，源码学习副本的批注位置 :6120-6124 本批新增：monitor 查 `findMonitorPidByTokenLocked`、窗口取 `ownerPid`）→ `sendWindowUnresponsiveCommandLocked`（:5912-5920）→ 命令队列放锁回调 `mPolicy->notifyWindowUnresponsive`——JNI 实现在 `com_android_server_input_InputManagerService.cpp:831-848`（CallVoidMethod :845-846），Java 链 `InputManagerService.java:2957-2960` → `InputManagerCallback.java:103-105` → `mService.mAnrController.notifyWindowUnresponsive`（应用错误弹窗的入口在 WMS 侧 AnrController）。收尾 `cancelEventsForAnrLocked`（:1782-1795）合成 CANCEL——**是追加入队**：经 `synthesizeCancelationEventsForConnectionLocked`（:3614-3685）逐条 `enqueueDispatchEntryLocked`（:3680）后照常 `startDispatchCycleLocked`（:3684）发布；outbound/wait 队列不清空（该函数头注释 :1783-1787 原文明言不在此断连）。源码学习副本的批注原文"清空其 outbound/wait 队列"与代码不符，已修正（过程记录 §3 F2）。CANCEL 与普通事件共用发布路（源码学习副本的批注位置 :3780-3782，本批新增）。

### 6.5 本 trace 无 ANR 判定（已证）

判据两条：①三通道 wq 驻留（§5.5 表）最长 12,192,693 ns，距概念 2 的 5,000 ms 阈值差三个数量级；②`processAnrsLocked` 若有到期必然置 `responsive=false` 并 `onAnrLocked`，而本窗口内三通道持续接收新事件（Q2 的 oq/wq 序列持续到 6,857）且 prepare 全部成功（Q3）——responsive 从未变假。ANR 弹窗路径在本样本零触发（已证）；该路径的 Java 侧展开（AnrController/弹窗）不属本篇源码域，机制上止于 policy 回调（§6.4 已引 JNI 行号）。

## 7. LatencyTracker / InputEventTimeline / LatencyAggregator（机制走读⑤）

这三个组件用于记录和汇总输入延迟：LatencyTracker 跟踪事件，InputEventTimeline 保存相关时间点，LatencyAggregator 统计符合条件的数据。它们服务于延迟观测，不负责选择事件目标或完成事件分发。

### 7.1 时延账本的三个登记点与"时刻五元组"

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:4066-4071
        if (args->id != android::os::IInputConstants::INVALID_INPUT_EVENT_ID &&
            IdGenerator::getSource(args->id) == IdGenerator::Source::INPUT_READER &&
            !mInputFilterEnabled) {
            const bool isDown = args->action == AMOTION_EVENT_ACTION_DOWN;
            mLatencyTracker.trackListener(args->id, isDown, args->eventTime, args->readTime);
        }
```

走读：登记点一 `trackListener`（grep 全文件仅 :4070 一处调用，且被三重条件包住——id 有效、来源 INPUT_READER、`!mInputFilterEnabled`，属 notifyMotion 的**未装 filter 直入队路径**，批06 §4.6 已证；notifyKey 与 `injectInputEvent` 均无 trackListener 调用——本样本走 filter 回注路径，故本事件的 Timeline 账本从 trackListener 起就不存在，见 §7.5）。登记点二 `trackFinishedEvent`（:5738-5742，回执出队时，参数 `deliveryTime/consumeTime/finishTime`）；登记点三 `trackGraphicsLatency`（:3546-3554，收到概念 4 的 TIMELINE 信号时）。五元组的取值点：`eventTime`/`readTime` 来自 Reader（批05 §7）；`deliveryTime` = :3234 出队时刻；`consumeTime` = 应用随 FINISHED 回执带来（§5.1）；`finishTime` = handleReceiveCallback 的 `now()`（:3530）。

### 7.2 InputEventTimeline 的合账规则

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputEventTimeline.h:40-46（节选）
struct ConnectionTimeline {
    // DispatchTimeline
    nsecs_t deliveryTime; // time at which the event was sent to the receiver
    nsecs_t consumeTime;  // time at which the receiver read the event
    nsecs_t finishTime;   // time at which the finish event was received
    // GraphicsTimeline
    std::array<nsecs_t, GraphicsTimeline::SIZE> graphicsTimeline;
```

走读：一条 `InputEventTimeline`（isDown/eventTime/readTime，`InputEventTimeline.h:76-88`）挂每连接一份 `ConnectionTimeline`；`setDispatchTimeline`/`setGraphicsTimeline` 均只写一次，二次写入返回 false 并使调用方整条丢弃（`InputEventTimeline.cpp:35-54`；源码学习副本的批注位置 InputEventTimeline.cpp:38-41，批0 时代既有，本批核对一致）；`isComplete()` 要求两半齐备（:31-33）。**A13 字段口径**：本篇术语表无"Expected/Actual Timeline"实体——契约批07 作业单所写的 Expected/Actual 是 A12 之前 FrameTimeline 的表述；A13 输入链的实际字段就是上列五元组加 graphicsTimeline 两字段（`Input.h:305-314`），与 SF 侧 FrameTimeline（#28，批12 域）不共用结构（未证项：两者在 trace 工具层的展示关联，本 trace 不可判，§12）。

### 7.3 成熟剪枝与重复 id 防污染

```cpp
// frameworks/native/services/inputflinger/dispatcher/LatencyTracker.cpp:39-46
const std::chrono::duration ANR_TIMEOUT = std::chrono::milliseconds(
        android::os::IInputConstants::UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS *
        HwTimeoutMultiplier());

static bool isMatureEvent(nsecs_t eventTime, nsecs_t now) {
    std::chrono::duration age = std::chrono::nanoseconds(now) - std::chrono::nanoseconds(eventTime);
    return age > ANR_TIMEOUT;
}
```

走读：账本按 eventTime 有序副表（`mEventTimes`）剪枝——`reportAndPruneMatureRecords`（:148-166）从头逐条判"成熟"（年龄超过与 ANR 同源的 5,000 ms×倍率，:39-41），成熟的整条送 `mTimelineProcessor->processTimeline` 后删除，碰第一条不成熟的即停（副表有序性保证）。"now"用新事件的 eventTime 近似（:143-147 注释原文承认该近似：空闲期不推进回收）。撞 id（随机 id 重复）两份同丢（:72-79；源码学习副本的批注位置 LatencyTracker.cpp:71-72/:78-81/:94-97/:162-167，批0 时代既有，本批核对一致）。

### 7.4 LatencyAggregator 的上报通道（机制四问⑤）

```cpp
// frameworks/native/services/inputflinger/dispatcher/LatencyAggregator.cpp:98-107（节选）
LatencyAggregator::LatencyAggregator() {
    AStatsManager_setPullAtomCallback(android::util::INPUT_EVENT_LATENCY_SKETCH, nullptr,
                                      LatencyAggregator::pullAtomCallback, this);
    dist_proc::aggregation::KllQuantileOptions options;
    options.set_inv_eps(100); // Request precision of 1.0%, instead of default 0.1%
    for (size_t i = 0; i < SketchIndex::SIZE; i++) {
        mDownSketches[i] = KllQuantile::Create(options);
        mMoveSketches[i] = KllQuantile::Create(options);
    }
}
```

走读（机制四问⑤：LatencyAggregator 的上报通道）。两条出口（`processTimeline` :123-126 先统计后慢事件，源码学习副本的批注位置 :135-139）：①**统计草图**——七段时延（`SketchIndex`：EVENT_TO_READ/READ_TO_DELIVER/DELIVER_TO_CONSUME/CONSUME_TO_FINISH/CONSUME_TO_GPU_COMPLETE/GPU_COMPLETE_TO_PRESENT/END_TO_END，`LatencyAggregator.h:28-37`）×DOWN/MOVE 两套 = 十四张 KLL 分位数草图（`ns2hus` 百微秒量化 :33-35；inv_eps=100 即 1% 精度 ：102）；统计入口 `processStatistics`（:128-167，源码学习副本的批注位置 :143-148）只收 `isComplete()` 的连接时序（:144-146）；上报为 **pull atom** `INPUT_EVENT_LATENCY_SKETCH`（:99，statsd 拉取时序列化并清零重开，:169-206）。②**慢事件**——`processSlowEvent`（:208-257）：端到端（PRESENT_TIME−eventTime）超阈值（默认 200 ms，:57，服务端可配 ：61-67）且距上次上报超 60 s（:59/:225-230）时 `stats_write(SLOW_INPUT_EVENT_REPORTED, …)` 推送（:243-252）；阈值只在首次调用读一次（static 初始化，:209-211，重启才生效）。**上限口径**：`MAX_EVENTS_FOR_STATISTICS = 20000`（:43）数的是 Timeline 条数，每条按连接数展开后草图样本可超过它（源码学习副本的批注位置 LatencyAggregator.cpp:33-36/:87-91/:111-113/:135-139/:143-148/:189-192/:231-239，批0 时代既有，本批核对一致）。

### 7.5 本 trace 的可见性：盲区声明（已证源码侧＋盲区 trace 侧）

源码侧已证：`LatencyAggregator.cpp`/`LatencyTracker.cpp`/`InputEventTimeline.cpp` 三文件 **ATRACE 计数为 0**（grep 实测 0/0/0）——全部输出走 statsd atom（pull/push），不经 atrace。trace 侧判据：本样本采集不含 statsd atom 事件（批00 §3.7 同源的采集配置边界），Q1 的计数轨清单中亦无任何 latency 相关轨。**结论（盲区）**：本事件 0x3b3b7f02 的 Timeline 账本与任何草图增量在 trace 中不可见。可加判的源码侧事实：本事件走 filter 回注路径（批06 C1 已证），`notifyMotion` 的直入队分支不执行（:4066-4071 在 `mInputFilterEnabled` 假分支内），`injectInputEvent` 无 trackListener 调用（grep 全函数无命中）——**该事件从一开始就没有 Timeline 账本**，即便采集了 atom 也不会有它的分位数样本（已证，源码级；旧样本同构，批06 Q7 同走 filter 路径）。与 FrameTimeline 的关系定界：FrameTimeline（#28）是 SF 侧结构（批12），输入链的 graphicsTimeline 是应用经概念 4 信号回报的两个时刻，两者在源码层无共享对象（已证，各自定义文件）；本 trace 中两者记录均未查询到可见条目（盲区沿用批00 §3.7 口径）。

## 8. 堆积排空：oq/wq 视角下的后续事件（机制四问② 的延伸）

批06 §3.6/§7.4 已证 iq 侧堆积（1→4 于 6,831,978,418/6,832,281,418/6,832,668,957/6,834,162,765，排空至 6,856,607,149）。本篇用 oq/wq/Q3/Q4 把"这些事件何时、以何节奏被发布"补全（新数值留痕）：

- 分发线程在第一轮分发后 S 615,769 → R 6,164,154（waker=tid 756，第一次注入的 needWake）→ Running 194,230（6,838,155,188–6,838,349,418：处理了 AppList 回执，§5.4）→ R 4,906,116（6,838,349,418–6,843,255,534）→ Running 1,147,923（Q4）。
- **四个堆积事件的发布节奏**（Q3 prepare 三元组）：0x2ff164c 于 6,843,564,188/6,843,840,072/6,844,031,303；0x2f3ae6d0 于 6,853,621,534/6,853,853,072/6,854,095,111；0x1a730a6a 于 6,854,590,111/6,854,780,188/6,854,960,726；0x221627f3 于 6,855,990,495/6,856,197,495/6,856,380,765；随后新入队事件 0x32c99b34 于 6,856,667,380 起（Q2 中 6,856,747,765 的 oq 采样即它）。每个事件的三通道 prepare 全序列完整发生——**堆积期间无一条事件被丢弃**（无 STALE：10 s 阈值；无 BLOCKED：mNextUnblockedEvent 仅在触摸 DOWN 落点命中他窗或存在响应中 spy 窗时置位，批06 §4.8——本段四条均为连续 MOVE，无新 DOWN）。
- **oq 轨始终只在 0/1 间摆动**（Q2 全窗口 oq:AppList 无 ≥2 采样）：每个事件发布后 oq 即清零，下一事件要等 `dispatchOnce` 下一圈——**一圈一条**的节奏（批06 §3.5 已证 done 后 LONG_LONG_MIN 立即下一圈）叠加分发线程 R/R+ 6.164 ms＋4.906 ms＋9.023 ms 的 CPU 窗口，把四条事件的发布拉开到 6,843–6,856 的 13 ms 级。
- **wq 轨的堆积**：wq:AppList 峰值 3（6,856,819,342）、wq:PointerEventDispatcher2 峰值 4（6,857,127,072）、wq:GestureMonitor 峰值 2（6,856,973,918）（Q2 尾段）——应用与两个 monitor 侧的回执节奏跟不上发布节奏时，已发布未回执条目按发布序堆在各自 waitQueue 里；**这只是"等待回执"的账面堆积**，不阻塞发布（§4.3 写满才阻塞，32KB 容量按消息数计远未到）。
- 回执出队与发布交错可见：wq:AppList 两次 2→1 采样于 6,854,428,649 与 6,855,603,072（Q2）——堆积期应用仍在逐条追。

**机制小结（已证）**：分发暂停只发生在"单连接 socket 写满"（§4.3）与"分发线程无 CPU"（本节 R/R+ 窗口，归因批14）两种条件下；队列排队（iq/oq/wq 计数）本身不产生丢弃，丢弃只由批06 §5 的 DropReason 判定链产生，本窗口未触发。

## 9. 辅助路径：key repeat 合成、全量复位、fallback 重投

### 9.1 synthesizeKeyRepeatLocked 与 resetKeyRepeatLocked

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:1268-1284
std::shared_ptr<KeyEntry> InputDispatcher::synthesizeKeyRepeatLocked(nsecs_t currentTime) {
    std::shared_ptr<KeyEntry> entry = mKeyRepeatState.lastKeyEntry;

    uint32_t policyFlags = entry->policyFlags &
            (POLICY_FLAG_RAW_MASK | POLICY_FLAG_PASS_TO_USER | POLICY_FLAG_TRUSTED);

    std::shared_ptr<KeyEntry> newEntry =
            std::make_unique<KeyEntry>(mIdGenerator.nextId(), currentTime, entry->deviceId,
                                       entry->source, entry->displayId, policyFlags, entry->action,
                                       entry->flags, entry->keyCode, entry->scanCode,
                                       entry->metaState, entry->repeatCount + 1, entry->downTime);

    newEntry->syntheticRepeat = true;
    mKeyRepeatState.lastKeyEntry = newEntry;
    mKeyRepeatState.nextRepeatTime = currentTime + mConfig.keyRepeatDelay;
    return newEntry;
}
```

走读：长按重复在 dispatcher 层合成（批04 D9 勘误口径）：取上一条 DOWN 的 KeyEntry，生成 `repeatCount+1` 的新条目、标 `syntheticRepeat`、按 `mConfig.keyRepeatDelay` 排下一次（`keyRepeatTimeout` 首拍间隔在登记点 ：1497）。取出点 ：756-764（批06 §3.4 已引）；`resetKeyRepeatLocked`（:1262-1266）清 lastKeyEntry，五个调用点（:725 禁用时、:1293/:1313 配置/焦点变化、:1492-1507 键序变化、:5190 复位）。本样本触摸手势无按键，零触发（无断言）。

### 9.2 resetAndDropEverythingLocked

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:5182-5199
void InputDispatcher::resetAndDropEverythingLocked(const char* reason) {
    if (DEBUG_FOCUS) {
        ALOGD("Resetting and dropping all events (%s).", reason);
    }

    CancelationOptions options(CancelationOptions::CANCEL_ALL_EVENTS, reason);
    synthesizeCancelationEventsForAllConnectionsLocked(options);

    resetKeyRepeatLocked();
    releasePendingEventLocked();
    drainInboundQueueLocked();
    resetNoFocusedWindowTimeoutLocked();

    mAnrTracker.clear();
    mTouchStatesByDisplay.clear();
    mLastHoverWindowHandle.clear();
    mReplacedKeys.clear();
}
```

走读（源码学习副本的批注位置 :5334-5340，本批新增）：全量复位的两个触发点——`setInputDispatchMode`（:4911 起，分发被禁用时 ：4928）与 `setInputFilterEnabled`（:4950 起，filter 启停 ：4962，批06 §4.5 的 `IMS.setInputFilter` 到此落地）；均为 Binder 调用线程持 mLock 执行。动作五件：向所有连接广播 CANCEL（追加进各连接 outbound 后照常发布，§6.4 同路）、清 key repeat、释放 mPendingEvent、清空 mInboundQueue、清无焦点挂起；随后五张状态表清零——**mAnrTracker.clear() 把全部超时登记一次性抹掉，仍在各连接 waitQueue 里的条目不再计时**（`AnrTracker::erase` 对空表安全 ：34-40）。本 trace 的 filter 启停只发生在采集前（窗口内 `mInputFilterEnabled` 恒真，批06 Q2 十七条 filterInputEvent 判据），复位段零触发（无断言）。

### 9.3 fallback 重投与 dispatchMotionEvent 名勘误

契约批07 作业单所列 `publishMotionEvent/sendDispatchSignal`、`dispatchMotionEvent` 两个名字：Android 13 基准源码树 grep 结果——`publishMotionEvent` 仅 InputTransport 侧发布函数（:541）与 `startDispatchCycleLocked` 调用点（:3294），**无 `sendDispatchSignal`、无名为 `dispatchMotionEvent` 的函数**（树内零命中）；旧版名称的 A13 对应物即 §4 的发布循环与 §3 的分发函数。此为作业单旧名勘误（已证，grep 判据），与批06 §4.8 的 `onAnrDelayStarted` 勘误同型。fallback 重投见 §5.3（:5974-6148）。

## 10. 机制四问索引表

各机制的四问在正文对应节内作答，此处汇总供批15 引用：

| 机制 | 规则 | 谁设置 | 谁执行 | 后果 | 正文 |
|---|---|---|---|---|---|
| prepare/enqueue 分工 | 准入＋拆分 → 模式过滤＋改写＋入队，两道门串联 | status/flags | 分发线程 | 门不过则该通道零 oq/wq 活动 | §3.3-§3.5 |
| 发布-回执周期 | 出队→publish→迁 wq→回执出队→无条件重启 | DispatchEntry 生命周期 | 分发线程（每连接独立） | 三通道互不等待 | §2/§4/§5 |
| socket 写满 | WOULD_BLOCK：wq 空→拆链；非空→留 oq 等回执重试 | 32KB 缓冲 | send 返回码 | 发布暂停不丢事件、不阻塞他连接 | §4.2-§4.3/§8 |
| ANR 计时 | 起点＝发布成功 insert；终点＝回执 erase/移除 eraseToken/复位 clear | 分发线程 | processAnrsLocked 每圈 | 到期 responsive=false→onAnrLocked | §6.1-§6.4 |
| 回执处理时刻 | fd 可读回调需分发线程得 CPU | 调度器（批14） | handleReceiveCallback→命令 | 回执处理被 R/R+ 窗口拉开 | §5.4 |
| LatencyAggregator 通道 | KLL 草图×14 pull atom＋慢事件 push atom | 服务端 flag | statsd | trace 内不可见（盲区） | §7.4-§7.5 |
| 堆积排空 | 一圈一条＋回执重启补发；无丢弃 | DropReason 链（未触发） | 分发线程 | 13 ms 级逐条排空、wq 账面堆积 | §8 |

## 11. 与两个问题的关联

**prepare→deliver 的 429,654 ns（`00:00:06.830577` prepare → `00:00:06.831007` deliver，契约 ：72；旧样本同段 585,192 ns，批0 复验二 §三）**。本篇把它拆到动作级（全部数值已留痕，Q2/Q4/Q5 与批0/批06 既有值）：

| 段 | 区间（rel ns） | 时长 | 段内事实 |
|---|---|---|---|
| P1 | 6,830,576,688 → 6,830,592,265 | 15,577 | prepare slice→enqueueDispatchEntries slice 起（if 作用域 slice，§3.1） |
| P2 | 6,830,592,265 → 6,830,707,457 | 115,192 | 六模式小 slice 六次（AS_IS 入队 oq 0→1 于 6,830,672,995）→startDispatchCycle slice |
| P3 | 6,830,707,457 → 6,830,764,842 | ≤57,385 | publishMotionEvent 序列化＋send 写 socket；终点=Launcher sched_wakeup（waker=tid 1488 直证，批0 复验二） |
| P4 | 6,830,764,842 → 6,830,811,995 | 47,153 | 分发线程簿记（oq 1→0、wq 0→1、mAnrTracker.insert）与应用侧被唤醒（R 33,923→Running 1,984,307 于 6,830,798,765）并行推进——两线程轨独立 |
| P5 | 6,830,811,995 → 6,831,006,342 | 194,347 | 应用主线程 Running 至 deliverInputEvent slice 打点（批08 §11.3 的醒后四段拆解管辖 P4-P5 的应用侧） |

求和校验：15,577+115,192+57,385+47,153+194,347 = 429,654 ns，与契约数值逐 ns 闭合（P3 左端点取 startDispatchCycle slice 起点；若改取 slice 结束 6,830,712,726，则 P2=120,461、P3=52,116，其余段不变，总和不变）。走读：**这段 0.43 ms 里没有等待**——prepare 到 send 是纯代码推进，send 后到 deliver 是应用侧调度延迟（P4-P5，批08 已拆），dispatcher 侧无空转。旧样本 585,192 ns 同段结构（批0 复验二 §二 的旧样本三通道表可同法拆分，本篇不重复，批15 对照）。

**50.928 ms Launcher 主线程 S（问题 A）**：本篇补齐其终点侧机制——S 结束（6,830,764,842）的直接原因是 :3293-3311 的 `publishMotionEvent` 调用（其内部 `InputTransport.cpp:621` 的 `mChannel->sendMessage(&msg)`，P3 段内），waker=tid 1488（批0 复验二直证）与该行代码的调用线程一致。S 的等待对象含 AppList 通道 fd 可读（批08 §3.1 已证注册关系）；"是否只等这一条件"保持批00 §3.7 盲区口径。dispatcher 自身在该窗口内的行为已由批06 §3.6（S 50.446 ms 零 slice）与本篇 §5.4/§8（醒后动作逐点）全部闭合。

**Dispatcher 醒后短 R 与 system_server waker（契约 ：73）**：批06 §3.6 已细化到线程级（waker=tid 756）；本篇补该 R 窗口的后续意义——它结束后分发线程还有 Running 1,009,154 内的三通道全序列（§4.5），与 §8 的 R 6,164,154（同 waker 756，第二次注入）共同说明：分发线程的 R 起点都对应主线程的 wake 调用（:4343-4345/:984-986 的 needWake 链）。

## 12. 未证边界与盲区清单

1. **盲区**：LatencyAggregator/LatencyTracker 的全部输出（十四张草图、慢事件 atom）在本 trace 不可见——三文件零 ATRACE（grep 已证）＋采集无 statsd atom（批00 §3.7 同源边界）；且本事件无 Timeline 账本（filter 回注路径无 trackListener，§7.5 已证源码侧）。
2. **未证**：wq:AppList 1→0 采样点（6,838,299,572）配套的 `startDispatchCycleLocked` restart slice 在 trace 中缺失（Q6b/Q6c 零 slice）——trace 事件缺失候选（tokenizer errors 422/chunks overwritten 658 背景）；该点代码归属（:5774）已证，slice 是否发出不可判。GM/PED2 同构点有完整 slice（§5.4 对照样本）。
3. **未证**：publish send() 的单点耗时——只可夹逼上界 52,116 ns（P3），send 系统调用本身的内部耗时无 syscall 事件可分（批00 §3.7）。
4. **盲区**：概念 4 Timeline 信号（sendTimeline）在本 trace 有无发生不可判（应用侧无 atrace、dispatcher 侧 TIMELINE 分支无 slice；批08/批10 段位若补采可判）。
5. **未证**：`wq:1b4998d com.yadea.launcher/com.yadea` 轨名截断（char[40]，:6166）后的完整窗口名——窗口全名可从 prepare slice 名读出（`1b4998d com.yadea.launcher/com.yadea.launcher.function.applist.AppListActivity`），轨名与全名的前缀一致性已证、截断点后内容按 snprintf 语义丢弃（机制已证，无歧义）。
6. **未证**：§8 堆积排空期四事件的逐事件应用侧回执时刻——wq 出队采样只有部分（6,854,428,649/6,855,603,072 等可见点），完整的逐 seq 配对需要 Timeline 消息级数据（无采集）。
7. **盲区（全局沿用）**：基准源码树与设备 build 的一致性未验证（批00 §3.6）；本文机制结论适用于 A13 源码，套用到设备行为须带此前提。
8. **勘误记录**：契约批07 作业单三处旧名/旧位——`dispatchMotionEvent`/`sendDispatchSignal` 在 A13 不存在（§9.3）；`onAnrDelayStarted/onAnrDelayEnded/processAnrsShouldRaise` 为旧版名，A13 对应物为 `mAnrTracker.insert/erase`＋`processAnrsLocked`（批06 E22 同口径）；`android_view_InputEventReceiver.cpp` 实际在 `frameworks/base/core/jni/`（非 services/core/jni）。批06 §10 第 7 项"oq/wq 轨可见性未查证"由本篇 §4.5 翻转为已证。

## 13. 断言与三态汇总

本篇机制性结论的编号与三态（断言-证据对照的完整表在《过程记录/批07-过程记录.md》，编号一致）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| E1 | dispatchMotionLocked 全函数结构：丢弃出口/PENDING 仅焦点路径/失败广播 CANCEL/monitor 追加/冲突广播后照常派发 | 已证 | :1634-1697 |
| E2 | ATRACE 两种作用域：ATRACE_CALL 函数级，四函数 ATRACE_NAME 为 if 块级（slice 不覆盖函数体） | 已证 | :2898-2903/:2960-2965/:2993-2998/:3223-3227；Q5+Q2 时序 |
| E3 | publishMotionEvent 的 ATRACE 在本 trace 失效：InputTransport.cpp 无 ATRACE_TAG 定义→默认 NEVER | 已证 | :550-555；cutils/trace.h:86-87；Q5 无该 slice |
| E4 | dispatchEventLocked 扇出序＝vector 序；pokeUserActivity 在扇出前调用 | 已证 | :1753-1780；Q5 |
| E5 | prepare=连接准入（status/split）；enqueueDispatchEntry=模式过滤+action/id 改写+一致性校验+入队 | 已证 | :2894-2954/:2989-3133 |
| E6 | 非 AS_IS 模式改写 action 并生成新 id（Transmute 记录）；本样本全 AS_IS 无 transmute | 已证 | :3040-3094；Q5 无该名 slice |
| E7 | 六模式循环+wasEmpty 启动条件：空→非空才就地 startDispatchCycle | 已证 | :2967-2986；Q5 |
| E8 | 出队即定 deliveryTime/timeoutTime；timeout=三源超时值 | 已证 | :3232-3236/:705-716/:80-82 |
| E9 | SEQPACKET 整包原子写；32KB 双端缓冲；MSG_DONTWAIT；错误码映射 | 已证 | :334-363/:47/:365-401 |
| E10 | 写满两种处置：wq 空→拆链；非空→留 oq 等回执，不丢不重试不阻塞他连接 | 已证 | :3360-3388/:3464-3495 |
| E11 | oq→wq 迁移点唯一；responsive 才登记 mAnrTracker（ANR 计时起点=发布成功） | 已证 | :3390-3400 |
| E12 | oq/wq 计数轨存在：六轨各 1,790 采样；发布逐动作三点×三通道 | 已证 | :6163-6177；Q1/Q2 |
| E13 | publish 写 socket 时刻被夹逼于 6,830,712,726–6,830,764,842（上界 52,116 ns） | 已证（夹逼） | Q2+批0 复验二 waker |
| E14 | 回执链：FINISHED/TIMELINE 分流；finish 只投命令；出队+erase+无条件重启 | 已证 | :3512-3586/:3444-3462/:5724-5785 |
| E15 | restartEvent 来源=fallback 键就地改写重投；触摸恒不重投 | 已证 | :5974-6148/:6151-6155 |
| E16 | 回执 dispatcher 侧处理时刻可被调度延迟拉开：AppList 回执采样 6,838,299,572 落在 R 6,164,154 之后的 Running 194,230 | 已证（采样）＋未证（竞争归因，批14） | Q2/Q4 |
| E17 | GM 回执出队与 restart slice 配对（6,831,311,957→6,831,322,226）；PED2 同构 | 已证 | Q2/Q5/Q6 |
| E18 | 三通道 waitQueue 驻留 310,385/7,487,577/12,192,693 ns，互不等待 | 已证 | Q2 端点差 |
| E19 | ANR 计时注销三点（erase/eraseToken/clear）；AnrTracker.erase 只删一笔 | 已证 | :5766/:5720/:5195；AnrTracker.cpp:26-50 |
| E20 | ANR 落地：waitQueue 空则放弃；最老条目算时；上报经 notifyWindowUnresponsive→AnrController；CANCEL 追加不清队 | 已证 | :5812-5845/:5936-5954/:3614-3685；JNI :831-848；IMS.java:2957；InputManagerCallback.java:103 |
| E21 | 本 trace 无 ANR 判定（驻留远小于超时；responsive 恒真） | 已证 | §6.5 判据两条 |
| E22 | LatencyTracker 三登记点与五元组取值点；本事件无 trackListener（filter 路径） | 已证 | :4066-4071/:5738-5742/:3546-3554；批06 C1 |
| E23 | LatencyAggregator 双通道：KLL×14 pull atom＋慢事件 push（200ms/60s）；trace 盲区 | 已证（源码）＋盲区（trace） | :98-107/:128-167/:208-257；grep ATRACE=0 |
| E24 | ConnectionTimeline 两半只写一次、isComplete 才入统计；A13 无 Expected/Actual 实体 | 已证 | InputEventTimeline.cpp:21-54；Input.h:305-314 |
| E25 | 堆积排空：四事件 13 ms 级逐条完整发布、零丢弃；oq 恒 0/1、wq 峰值 3/4/2 | 已证 | Q2/Q3/Q4 |
| E26 | prepare→deliver 429,654 ns 五段分解逐 ns 闭合；段内无 dispatcher 侧等待 | 已证 | §11 表；Q2/Q4/Q5 |
| E27 | 作业单旧名勘误：dispatchMotionEvent/sendDispatchSignal/onAnrDelayStarted 等不存在；JNI 收端在 core/jni | 已证 | 树内 grep；文件定位 |
| E28 | key repeat 合成在 dispatcher 层（repeatCount+1、syntheticRepeat、keyRepeatDelay 节奏） | 已证 | :1268-1284/:1497/:756-764 |
| E29 | resetAndDropEverythingLocked 双触发点、五表清零、mAnrTracker.clear 后 waitQueue 残条不再计时 | 已证 | :5182-5199/:4928/:4962 |
| E30 | handleReceiveCallback 对 monitor/非 responsive 连接跳过时延统计 | 已证 | :381-392/:3546-3554 |

## 14. 批注审计与补缺摘要

原写作及批注验收记录见[过程性段落存档](过程记录/整体优化-R1-过程性段落存档.md)。
