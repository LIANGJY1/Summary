# 06 · InputDispatcher 入队与裁决

InputDispatcher 接收事件，决定是否先交给 InputFilter，再执行入队和目标窗口裁决。

> 术语沿用：#6 EventEntry、#8 notifyMotion、#9 prepareDispatchCycleLocked、#10 deliverInputEvent、#11 InputChannel、#12 InputMessage、#16 S/R/R+/D 线程状态、#17 waker、#29 mInboundQueue、#30 waitQueue、#32 QueuedInputListener（00 篇术语表）。本篇新定义四个概念，见 §1。

## 0. 本篇范围与上下游边界

本篇走读 `dispatcher/InputDispatcher.cpp`（6401 行）的入队与裁决段——`notifyKey`/`notifyMotion`（:3904-4080）、`enqueueInboundEventLocked`（:978-1049）、`dispatchOnce`/`dispatchOnceInnerLocked`（:602-911）、`dispatchMotionLocked` 前半（:1634-1697）、`findTouchedWindowTargetsLocked`（:1990-2496）、`TouchState`/`TouchedWindow`/`InputTarget`/`FocusResolver`/`Monitor`/`InjectionState` 六个配套文件，外加策略侧三条链：`InputManagerService.java` 的 `filterInputEvent`（:3014）与 monitor 双方法（:703/:760）、JNI 层 `com_android_server_input_InputManagerService.cpp` 的 filter/intercept 段（:1183-1297）、`GestureMonitorSpyWindow.java`（91 行全量）。

- **上游边界**：批04/批05 交接点——`NotifyMotionArgs` 沿监听器链到达 `InputDispatcher::notifyMotion`（`InputDispatcher.cpp:3982`）。同 ID 三条 notifyMotion slice 的打点机制已由批04 D21 定案（三个 #32 QueuedInputListener 共用 `traceEvent` 三次独立打点，`InputListener.cpp:340-345/:361-364`），本篇用 trace 数据复核其时刻（§4.9）。
- **下游边界**：本篇止于 `InputTarget` 列表组装完成、`dispatchEventLocked` 开始逐目标循环（`InputDispatcher.cpp:1753`）的入口。`prepareDispatchCycleLocked` 之后的发布-回执状态机（#30 waitQueue、Connection、`publishMotionEvent`、AnrTracker 细节、LatencyAggregator）一律"见批07"。本篇引用它们的行号只为划定边界。
- **硬结论预告一**：契约要归因的 34.738 ms 空档（notifyMotion 首条 `00:00:06.795839` → AppList 通道 prepare `00:00:06.830577`）**不是在 mInboundQueue 里排出来的**——trace 内 `iq` 计数轨（§1 概念 2）证明 mInboundQueue 在整个空档期间深度为 0，事件在 `6,830,307,188` 才第一次入队（§4.9、§7）。
- **硬结论预告二**：空档的主体在 InputFilter 的消费-回注路径（§1 概念 1）里：本设备 `mInputFilterEnabled` 为真，每条触摸事件在 `notifyMotion` 内被转交系统侧 InputFilter、**不入队**，由 system_server 主线程在 34 ms 后经 `injectInputEvent` 以同一事件 id 送回。空档 34,738,039 ns 的九段里程碑分解逐段相加与契约数值闭合（§4.9）。
- 主线先闭环：§3 走读完主循环、§4 走读完 notifyMotion→入队、§6 走读完目标裁决，入队与裁决的机制即闭合；其后 §7 是空档候选全表，§8-§12 是索引、关联、盲区与汇总。

## 1. 本篇新定义的四个概念

1. **filter 消费-回注路径**：`mInputFilterEnabled` 为真时一条触摸事件的完整入队路径——`notifyMotion` 把 `MotionEvent` 副本转交 `IInputFilter.filterInputEvent` 后直接返回（不入 #29 mInboundQueue），过滤器侧处理后经 `InputFilterHost.sendInputEvent` → `InputDispatcher::injectInputEvent` 以**原事件 id** 重新入队。两个 trace 时刻由此分离：notifyMotion slice 时刻（Reader 线程，转交完成）与入队时刻（注入线程，`enqueueInboundEventLocked`）。转交点 `InputDispatcher.cpp:4046-4049`，回注入队点 `:4335-4345`。
2. **iq 计数**：`traceInboundQueueLengthLocked`（`InputDispatcher.cpp:6157-6161`）经 `ATRACE_INT("iq", mInboundQueue.size())` 写入的整型计数轨，入队（:982）、出队（:774）、清队（:1238）三个动作各采样一次。两次采样之间队列深度不变——本 trace 该轨存在且有样本（批00 §3.7 的"input 队列 counter 无记录"结论对 `iq` 不成立，已按新证据修正，见 §7 C2 与过程记录 §2 Q4）。
3. **全局 monitor 通道与 spy 窗**：system_server 侧两条"旁路"注册路径。全局 monitor 经 `monitorInput`→`createInputMonitor`（`InputDispatcher.cpp:5500`）登记进 `mGlobalMonitorsByDisplay[displayId]`，不做落点命中、每帧追加（:2638-2653）；spy 窗以窗口身份（`InputConfig::SPY`）进窗口列表（`GestureMonitorSpyWindow.java:65-66`），按落点命中（`findTouchedSpyWindowsAtLocked`，:1090）。trace 中的 `[Gesture Monitor] CustomGesture-touch (server)` 是 spy 窗，`PointerEventDispatcher2` 是全局 monitor（§6.5）。
4. **DropReason 丢弃判定链**：`dispatchOnceInnerLocked` 在目标解析之前给 #6 EventEntry 标定的丢弃原因（`DropReason`，`InputDispatcher.h:156-164`）：POLICY/DISABLED 在取事件后立即判（:788-792），APP_SWITCH/STALE/BLOCKED 按类型在分发前判（:848-899）。被判为丢弃的事件走 `dropInboundEventLocked`（:1110）广播合成 CANCEL 后释放，不进入目标裁决。

## 2. 数据底座：EventEntry、MotionEntry、DispatchEntry 与 InjectionState

入队之后的可调度单元不是 `MotionEvent`，而是 dispatcher 自己的条目对象。三问三答——规则：条目在入队时构造、分发时引用、释放时回收；谁设置：notifyX/injectInputEvent 构造、分发线程消费；谁执行：单线程（分发线程）持 mLock 消费 mInboundQueue；后果：条目生命周期覆盖"入队→裁决→（批07）发布→回执"，字段在全程被反复读取。

```cpp
// frameworks/native/services/inputflinger/dispatcher/Entry.h:32-53
struct EventEntry {
    enum class Type {
        CONFIGURATION_CHANGED,
        DEVICE_RESET,
        FOCUS,
        KEY,
        MOTION,
        SENSOR,
        POINTER_CAPTURE_CHANGED,
        DRAG,
        TOUCH_MODE_CHANGED,

        ftl_last = TOUCH_MODE_CHANGED
    };

    int32_t id;
    Type type;
    nsecs_t eventTime;
    uint32_t policyFlags;
    InjectionState* injectionState;

    bool dispatchInProgress; // initially false, set to true while dispatching
```

走读：#6 EventEntry 携带事件 id（批04 概念 1：Reader 侧来源段 INPUT_READER，`Input.h:324-346`）、eventTime（批05 §7 八站复制链的第 6 站照抄 `args->eventTime`）、policyFlags 与 `dispatchInProgress` 标志。`isSynthesized()`（`Entry.h:68-70`）的判据是 InjectionState 非空或来源段≠INPUT_READER——回注事件的 id 保留原值（来源段仍 INPUT_READER），但 `injectInputEvent` 给条目挂了 InjectionState（:4327-4333），故其 isInjected()/isSynthesized() 均为真；`POLICY_FLAG_INJECTED` 在 policyFlags 层另行标记（:4186）。

```cpp
// frameworks/native/services/inputflinger/dispatcher/Entry.cpp:210-239（节选）
MotionEntry::MotionEntry(int32_t id, nsecs_t eventTime, int32_t deviceId, uint32_t source,
                         int32_t displayId, uint32_t policyFlags, int32_t action,
                         ...
      : EventEntry(id, Type::MOTION, eventTime, policyFlags),
        deviceId(deviceId),
        ...
        pointerCount(pointerCount) {
    for (uint32_t i = 0; i < pointerCount; i++) {
        this->pointerProperties[i].copyFrom(pointerProperties[i]);
        this->pointerCoords[i].copyFrom(pointerCoords[i]);
    }
}
```

走读：MotionEntry（`Entry.h:162-191`）深拷贝 pointerProperties/pointerCoords 数组（`MAX_POINTERS` 上限，批05 C13 同口径）——构造完成即与 `NotifyMotionArgs` 脱钩，Reader 侧后续复用缓冲不再影响分发侧。eventTime 在此第 6 站保持原值（批05 C30 链）。分发侧对 `MotionEntry` 的全部读取（裁决、坐标缩放、发布）在批07 展开。

```cpp
// frameworks/native/services/inputflinger/dispatcher/Entry.h:221-253（节选）
struct DispatchEntry {
    const uint32_t seq; // unique sequence number, never 0

    std::shared_ptr<EventEntry> eventEntry; // the event to dispatch
    int32_t targetFlags;
    ui::Transform transform;
    ...
    nsecs_t deliveryTime; // time when the event was actually delivered
    // An ANR will be triggered if a response for this entry is not received by timeoutTime
    nsecs_t timeoutTime;
    ...
};
```

走读：DispatchEntry（`Entry.h:221-253`，构造 `Entry.cpp:310`）是"条目×目标"的派生单位：seq 序号、targetFlags、deliveryTime/timeoutTime 两个发布期字段（批07）。本篇只在 §6.6 引它划界。InjectionState（`InjectionState.h:27-40`）是定向注入的鉴权载荷（targetUid/注入结果/前台计数），普通硬件事件为 nullptr；`EventEntry::releaseInjectionState`（`Entry.cpp:70-77`）在条目回收时释放。

## 3. 线程与主循环：dispatchOnce 的节奏（机制四问②）

InputDispatcher 是 Android 系统中负责分发输入事件的组件。它接收上游事件，根据系统策略和窗口状态选择目标，再交给目标连接；应用内具体由哪个 View 处理，则属于后续阶段。dispatchOnce 是分发线程每轮检查和推进工作的入口。

### 3.1 线程来源与循环体

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:584-591
status_t InputDispatcher::start() {
    if (mThread) {
        return ALREADY_EXISTS;
    }
    mThread = std::make_unique<InputThread>(
            "InputDispatcher", [this]() { dispatchOnce(); }, [this]() { mLooper->wake(); });
    return OK;
}
```

走读：分发线程名 `InputDispatcher`（本样本 tid=1488，契约 :62）由 `InputThread` 创建，循环体即 `dispatchOnce`；线程退出钩子调 `mLooper->wake()` 把 pollOnce 睡眠打断。谁设置：`InputManager::start()` 启动链（源码学习副本的批注位置：InputDispatcher.cpp:598）。谁执行：本线程独占。后果：下述 §3.2 的每圈节奏。本篇走读的 `notifyMotion`/`notifyKey`/`injectInputEvent` 却不在这一线程——它们分别由 InputReader 线程（tid=1489）与注入方线程调用，经 `mLock` 与分发线程互斥（四问：规则=读侧写、写侧分发；谁执行=各调用方持锁入队、分发线程持锁出队；后果=trace 中入队时刻与分发 slice 分属两条线程轨）。

### 3.2 dispatchOnce 每圈四段

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:602-636
void InputDispatcher::dispatchOnce() {
    nsecs_t nextWakeupTime = LONG_LONG_MAX;
    { // acquire lock
        std::scoped_lock _l(mLock);
        mDispatcherIsAlive.notify_all();

        // Run a dispatch loop if there are no pending commands.
        // The dispatch loop might enqueue commands to run afterwards.
        if (!haveCommandsLocked()) {
            dispatchOnceInnerLocked(&nextWakeupTime);
        }

        // Run all pending commands if there are any.
        if (runCommandsLockedInterruptable()) {
            nextWakeupTime = LONG_LONG_MIN;
        }

        // If we are still waiting for ack on some events,
        // we might have to wake up earlier to check if an app is anr'ing.
        const nsecs_t nextAnrCheck = processAnrsLocked();
        nextWakeupTime = std::min(nextWakeupTime, nextAnrCheck);
        ...
    } // release lock

    nsecs_t currentTime = now();
    int timeoutMillis = toMillisecondTimeoutDelay(currentTime, nextWakeupTime);
    mLooper->pollOnce(timeoutMillis);
}
```

走读：每圈四段——①无命令时 `dispatchOnceInnerLocked` 处理一条事件（:610-612）；②清 `mCommandQueue`（:614-618）：命令是"延迟到持锁安全点再执行"的闭包（`mCommandQueue`，`InputDispatcher.h:199-200`），`runCommandsLockedInterruptable`（:1214-1227）逐个执行且允许命令内部放锁回调 policy，跑过命令就把下一轮唤醒提前到"立即"（LONG_LONG_MIN）；③`processAnrsLocked` 算 ANR 侧下一次到期（:622-623）；④按 min 换算毫秒睡进 pollOnce（:632-635）。

唤醒期限的三类来源（谁设置）：本次分发的下一步期限（§4 的 nextWakeupTime 出参）、命令副作用（LONG_LONG_MIN 立即）、ANR 检查——`processAnrsLocked`（:670-703）返回无焦点窗口超时与 mAnrTracker 最近超时的较小者，到期才真正进 ANR 处理（`onAnrLocked`，:5812 起，批07）。谁执行：分发线程。后果：`nextWakeupTime == LONG_LONG_MAX` 时 `timeoutMillis` 为 -1，pollOnce 无限睡眠（:625-629 还会 notify `mDispatcherEnteredIdle`）。

**S 态对应点**：分发线程的 S（TASK_INTERRUPTIBLE，#16）即阻塞在 `mLooper->pollOnce` 的 epoll 等待里；"S=等待事件（含命令、fd 回执、ANR 定时三类唤醒源），不能定死 epoll 空/满"的既有口径维持（契约 :124；批00 §3.7 盲区声明沿用）。三类唤醒源的 trace 对应：notifyX/injectInputEvent 的 `mLooper->wake()`（写 wake fd，S 即刻转 R）、已注册通道 fd 可读（应用回执，批07）、超时到期（无 waker）。

### 3.3 processAnrsLocked：ANR 侧的定时检查

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:670-703（节选）
nsecs_t InputDispatcher::processAnrsLocked() {
    const nsecs_t currentTime = now();
    nsecs_t nextAnrCheck = LONG_LONG_MAX;
    // Check if we are waiting for a focused window to appear. Raise ANR if waited too long
    if (mNoFocusedWindowTimeoutTime.has_value() && mAwaitedFocusedApplication != nullptr) {
        if (currentTime >= *mNoFocusedWindowTimeoutTime) {
            processNoFocusedWindowAnrLocked();
            mAwaitedFocusedApplication.reset();
            mNoFocusedWindowTimeoutTime = std::nullopt;
            return LONG_LONG_MIN;
        } else {
            // Keep waiting. We will drop the event when mNoFocusedWindowTimeoutTime comes.
            nextAnrCheck = *mNoFocusedWindowTimeoutTime;
        }
    }

    // Check if any connection ANRs are due
    nextAnrCheck = std::min(nextAnrCheck, mAnrTracker.firstTimeout());
    if (currentTime < nextAnrCheck) { // most likely scenario
        return nextAnrCheck;          // everything is normal. Let's check again at nextAnrCheck
    }
    ... // 略 ：692-702 连接级 ANR 落地（connection->responsive=false、onAnrLocked）
}
```

走读：本函数不产生等待，只计算"下一次必须醒来的时刻"。无焦点窗口挂起（§4.7 的 mNoFocusedWindowTimeoutTime）到期走 `processNoFocusedWindowAnrLocked`（:644-663，二次校验焦点应用未变且仍无焦点窗才真报 ANR）；连接级到期走 `onAnrLocked`（:5812 起，批07）。返回 LONG_LONG_MIN 表示"有紧急事，立即再醒"。对 §7 C8 的意义：即使没有任何事件，分发线程也会按 nextAnrCheck 定时醒——所以"长时间 S"不能证明"无人唤醒"以外的含义，S 的终点来源必须靠 waker 字段判定（§7 C8）。

### 3.4 mInboundQueue 空时做什么

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:744-781（节选，study 批注位置：737-744/773）
    // If we don't already have a pending event, go grab one.
    if (!mPendingEvent) {
        if (mInboundQueue.empty()) {
            if (isAppSwitchDue) {
                ...
                resetPendingAppSwitchLocked(false);
                isAppSwitchDue = false;
            }
            // Synthesize a key repeat if appropriate.
            if (mKeyRepeatState.lastKeyEntry) {
                if (currentTime >= mKeyRepeatState.nextRepeatTime) {
                    mPendingEvent = synthesizeKeyRepeatLocked(currentTime);
                } else {
                    ... // 略 ：760-763
                }
            }

            // Nothing to do if there is no pending event.
            if (!mPendingEvent) {
                return;
            }
        } else {
            // Inbound queue has at least one entry.
            mPendingEvent = mInboundQueue.front();
            mInboundQueue.pop_front();
            traceInboundQueueLengthLocked();
        }
        ... // 略 ：777-781
    }
```

走读：取数优先级三档（study:773 批注同口径）——上轮 PENDING 留下的 mPendingEvent → key repeat 合成 → #29 mInboundQueue 队首。队列空且无挂起条目时本函数直接 return——`nextWakeupTime` 保持 LONG_LONG_MAX，回 `dispatchOnce` 睡进 pollOnce。返回前两件小事：app switch 到期收尾（:748-753，`resetPendingAppSwitchLocked` :1198-1208）与 key repeat 合成检查（:756-764，A13 长按重复的合成者在此层，批04 D9；repeat 节奏由 policy 下发的 `mConfig.keyRepeatTimeout/Delay` 控制）。出队时 `pop_front()` 后立即 `traceInboundQueueLengthLocked()` 采样 iq（:774）。

**结论（已证）**：队列空→睡眠，队列非空→不可能持续睡眠（每圈必弹一条处理），例外只有 `mDispatchFrozen` 提前返回（:729-734，`setInputDispatchMode` 由 WMS 冻结时置位）与 `!mDispatchEnabled` 的 repeat 复位分支（不 return，照常处理）。这一结论是 §7 候选 C2 的机制前提。

### 3.5 dispatchOnceInnerLocked 的收尾与条目释放

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:902-911
    if (done) {
        if (dropReason != DropReason::NOT_DROPPED) {
            dropInboundEventLocked(*mPendingEvent, dropReason);
        }
        mLastDropReason = dropReason;

        releasePendingEventLocked();
        *nextWakeupTime = LONG_LONG_MIN; // force next poll to wake up immediately
    }
}
```

走读：分发"完成"（done=true）即释放 mPendingEvent（`releasePendingEventLocked` :1241-1247 → `releaseInboundEventLocked` :1248-1261：注入条目回灌注入结果、普通条目进 mRecentQueue（`addRecentEventLocked` :1051-1059，调试环形账）并减引用计数），并把下一轮唤醒提前到"立即"——**一圈处理一条，队列排空靠连续圈**。done=false 只发生在 PENDING（§6.1 证明触摸路径无 PENDING）。trace 判据：排空段表现为 Running 窗口内连续多组 dispatch slice。

### 3.6 trace 配对：Dispatcher 线程状态序列

契约 :73 与批0 复验 §4 的序列：S `6,779,885,149` 起 50,445,654 ns → R 35,308 → Running 1,009,154 → S 615,769 → R 6,164,154 → Running 194,230 → R 4,906,116。批06 新增配对（过程记录 §2 Q1/Q3）：

- S 终点 `6,830,330,803` 的 waker 直证为 system_server **主线程** tid=756（thread_state waker_utid=52→tid 756，Q3 输出）——契约"随后短 R，waker 关联 system_server"细化到线程级（新数值已留痕）。
- S 窗口内 dispatcher 线程（tid 1488）**无任何 slice**：Q1 查询显示 tid 1488 在 `6,779,729,726`（上一事件的 `startDispatchCycleLocked(PointerEventDispatcher2)`，5,808 ns）与 `6,830,475,418`（本次 `dispatchMotionLocked`，725,270 ns）之间零 slice。50.446 ms 的 S 期间该线程没有跑过一行带 atrace 的代码。
- S 之后的 Running 1,009,154 ns 内完成：`dispatchMotionLocked`（起点 `6,830,475,418`）→ 内层 `findTouchedWindowTargetsLocked`（`6,830,483,688`，39,538 ns）→ `dispatchEventLocked`（`6,830,543,842`，649,230 ns）→ 三通道 prepare/enqueue/startDispatchCycle 全序列（§6.7 逐点表）→ `pokeUserActivity` 两条（`6,831,220,072`/`6,831,234,226`，:2841 起）。
- 空档之后的事件堆积与排空：iq 计数 6,831,978,418→1、6,832,281,418→2、6,832,668,957→3、6,834,162,765→4（回注条目在分发线程 R 6,164,154 窗口期间陆续入队），排空至 6,856,607,149→0（Q4/Q8）——分发线程的 R/R+ 交替（6.164 ms R、4.906 ms R+）把每圈 194 μs 级的处理拉长到 22.4 ms 级，归因留批14。

## 4. notifyKey 与 notifyMotion：从 JNI 调用到 mInboundQueue（机制四问①）

### 4.1 notifyKey：对照路径

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:3936-3976（节选）
    KeyEvent event;
    event.initialize(args->id, args->deviceId, args->source, args->displayId, INVALID_HMAC,
                     args->action, flags, keyCode, args->scanCode, metaState, repeatCount,
                     args->downTime, args->eventTime);

    android::base::Timer t;
    mPolicy->interceptKeyBeforeQueueing(&event, /*byref*/ policyFlags);
    if (t.duration() > SLOW_INTERCEPTION_THRESHOLD) { ... }

    bool needWake = false;
    { // acquire lock
        mLock.lock();

        if (shouldSendKeyToInputFilterLocked(args)) {
            mLock.unlock();

            policyFlags |= POLICY_FLAG_FILTERED;
            if (!mPolicy->filterInputEvent(&event, policyFlags)) {
                return; // event was consumed by the filter
            }

            mLock.lock();
        }

        std::unique_ptr<KeyEntry> newEntry = std::make_unique<KeyEntry>(...);
        needWake = enqueueInboundEventLocked(std::move(newEntry));
        mLock.unlock();
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
```

走读：notifyKey 与 notifyMotion 同构——锁外 policy 预处理（`interceptKeyBeforeQueueing`，**任何状态都回调 Java**，与 motion 的交互态短路不同）、filter 分支（`shouldSendKeyToInputFilterLocked` :3978-3980 同样只看 `mInputFilterEnabled`）、KeyEntry 构造入队（:3963-3969）、needWake 唤醒（:3973-3975）。差异有两处：①KEY 分支在 filter 后才 `POLICY_FLAG_TRUSTED`（:3931，motion 在 :4016）；②`interceptKeyResult` 四态（TRY_AGAIN_LATER 时分发线程挂起等 policy 定义的唤醒时刻，`Entry.h:144-151`，批07 段位）。按键在本 trace 无 slice 关联（触摸手势），本篇不展开按键分发链（`dispatchKeyLocked` 四道关卡的 源码学习副本的批注在 :1509-1515）。

### 4.2 notifyMotion 入口：校验与信任标记

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:4010-4024
    if (!validateMotionEvent(args->action, args->actionButton, args->pointerCount,
                             args->pointerProperties)) {
        return;
    }

    uint32_t policyFlags = args->policyFlags;
    policyFlags |= POLICY_FLAG_TRUSTED;

    android::base::Timer t;
    mPolicy->interceptMotionBeforeQueueing(args->displayId, args->eventTime, /*byref*/ policyFlags);
    if (t.duration() > SLOW_INTERCEPTION_THRESHOLD) {
        ALOGW("Excessive delay in interceptMotionBeforeQueueing; took %s ms",
              std::to_string(t.duration().count()).c_str());
    }
```

走读：`validateMotionEvent` 校验 action 与 pointer 位集一致性，失败直接丢弃（不进队列）。来自 Reader 的事件在此打 `POLICY_FLAG_TRUSTED`；后续 `enqueueInboundEventLocked` 对 MOTION 分支见到非 trusted 即 FATAL（:1025-1026）。随后锁外调用 policy 的 `interceptMotionBeforeQueueing`——`SLOW_INTERCEPTION_THRESHOLD` = 50 ms（:95），超过只打 WARN 日志不改行为。注意本函数自身**没有 ATRACE_CALL**：trace 中 `notifyMotion(id=0x…)` slice 来自 #32 QueuedInputListener 的 `traceEvent`（批04 D21），不是本函数打的。

### 4.3 policy 预处理：交互态在 JNI 层短路

```cpp
// frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp:1254-1286
void NativeInputManager::interceptMotionBeforeQueueing(const int32_t displayId, nsecs_t when,
        uint32_t& policyFlags) {
    ATRACE_CALL();
    ...
    bool interactive = mInteractive.load();
    if (interactive) {
        policyFlags |= POLICY_FLAG_INTERACTIVE;
    }
    if ((policyFlags & POLICY_FLAG_TRUSTED) && !(policyFlags & POLICY_FLAG_INJECTED)) {
        if (policyFlags & POLICY_FLAG_INTERACTIVE) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        } else {
            ... // 略 ：1270-1279 回调 Java interceptMotionBeforeQueueingNonInteractive
        }
    } else {
        ...
    }
}
```

走读：触摸类 policy 预处理只有 ATRACE_CALL 一层 slice。交互态（亮屏可交互）下只补两个标志位、**不回调 Java**；非交互才经 `IMS.interceptMotionBeforeQueueingNonInteractive`（`InputManagerService.java:3037-3041`）进窗口层，由 `handleInterceptActions`（:1288-1297）按返回的 WM_ACTION_PASS_TO_USER 决定是否保留 `POLICY_FLAG_PASS_TO_USER`——该标志为假的事件在 §5 的丢弃链中被 POLICY 原因丢弃（:788-789）。**谁设置**：mInteractive 由 `setInteractive`（`InputManagerService.java` LocalService 转发）维护；**谁执行**：分发侧输入线程；**后果**：本样本触摸发生在交互态，policy 预处理退化为标志位运算。trace 证据：事件 0x3b3b7f02 的 `interceptMotionBeforeQueueing` slice 仅 5,962 ns（`6,796,128,072` 起，过程记录 Q2）——已证，候选 C5"policy 拦截耗时"由此排除。

### 4.4 policy 拦截的按键对照：interceptKeyBeforeQueueing

```cpp
// frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp:1216-1252（节选）
void NativeInputManager::interceptKeyBeforeQueueing(const KeyEvent* keyEvent,
        uint32_t& policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Ask the window manager what to do with normal events and trusted injected events.
    // - For normal events wake and brighten the screen if currently off or dim.
    bool interactive = mInteractive.load();
    if (interactive) {
        policyFlags |= POLICY_FLAG_INTERACTIVE;
    }
    if ((policyFlags & POLICY_FLAG_TRUSTED)) {
        nsecs_t when = keyEvent->getEventTime();
        JNIEnv* env = jniEnv();
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        jint wmActions;
        if (keyEventObj) {
            wmActions = env->CallIntMethod(mServiceObj,
                    gServiceClassInfo.interceptKeyBeforeQueueing,
                    keyEventObj, policyFlags);
            ...
        }
        ...
        handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
    } else {
        ...
    }
}
```

走读：按键的 policy 预处理与 motion 的差异至此闭合——KEY 在**任何状态**都构造 KeyEvent 对象回调 Java（`IMS.interceptKeyBeforeQueueing:3031-3033` → WMS 决定亮屏/唤醒等 wmActions），motion 只有非交互态才回调（§4.3）。`handleInterceptActions`（:1288-1297）是两者共用的标志翻译点：WM_ACTION_PASS_TO_USER 在位→保留 POLICY_FLAG_PASS_TO_USER，否则摘除（§5.1 的 POLICY 丢弃由此触发）。四问：规则=入队前问一次 policy；谁设置=PhoneWindowManager（WMS 侧实现 `WindowManagerCallbacks`）；谁执行=输入调用线程经 JNI；后果=交互态 motion 只花一次 ATRACE slice 的开销（5,962 ns 实测），按键多了对象转换与 Java 调用。

### 4.5 filter 转交：有 filter 时事件"当场不在了"

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:4029-4052
        if (shouldSendMotionToInputFilterLocked(args)) {
            ui::Transform displayTransform;
            if (const auto it = mDisplayInfos.find(args->displayId); it != mDisplayInfos.end()) {
                displayTransform = it->second.transform;
            }

            mLock.unlock();

            MotionEvent event;
            event.initialize(args->id, args->deviceId, args->source, args->displayId, INVALID_HMAC,
                             args->action, args->actionButton, args->flags, args->edgeFlags,
                             args->metaState, args->buttonState, args->classification,
                             displayTransform, args->xPrecision, args->yPrecision,
                             args->xCursorPosition, args->yCursorPosition, displayTransform,
                             args->downTime, args->eventTime, args->pointerCount,
                             args->pointerProperties, args->pointerCoords);

            policyFlags |= POLICY_FLAG_FILTERED;
            if (!mPolicy->filterInputEvent(&event, policyFlags)) {
                return; // event was consumed by the filter
            }

            mLock.lock();
        }
```

走读：`shouldSendMotionToInputFilterLocked` 只返回 `mInputFilterEnabled`（:4118-4120）——该标志由 `IMS.setInputFilter`（`InputManagerService.java:815-845`）随过滤器安装置位（`mNative.setInputFilterEnabled(filter != null)`，:843），唯一安装方是无障碍服务（`AccessibilityManagerService.java:2349-2356` 经 `WindowManagerService.setInputFilter`（`WindowManagerService.java:7729-7730`）转发，已证，机制层）。装了过滤器的事件走：构造 `MotionEvent` 副本（保留 args->id）→ `policyFlags |= POLICY_FLAG_FILTERED` → 调 policy 的 `filterInputEvent` → **返回 false 即本函数 return：这次调用不入 mInboundQueue**。事件没有消失，它进入了概念 1 的消费-回注路径，回程见 §4.6。

Java 侧转交点：

```java
// frameworks/base/services/core/java/com/android/server/input/InputManagerService.java:3014-3027
    final boolean filterInputEvent(InputEvent event, int policyFlags) {
        synchronized (mInputFilterLock) {
            if (mInputFilter != null) {
                try {
                    mInputFilter.filterInputEvent(event, policyFlags);
                } catch (RemoteException e) {
                    /* ignore */
                }
                return false;
            }
        }
        event.recycle();
        return true;
    }
```

走读：`mInputFilter` 非空时转交并**恒返回 false**——native 侧 `notifyMotion` 在 :4047-4049 return，事件在此刻从分发主线"消失"。转交对象是 `android.view.InputFilter` 的 `filterInputEvent`（`InputFilter.java:152-154`），它只做一件事：把事件打包成 `MSG_INPUT_EVENT` 消息 post 到过滤器自己的 Handler（`mH = new H(looper)`，:123-124）；`AccessibilityInputFilter` 构造用 `super(context.getMainLooper())`（`AccessibilityInputFilter.java:191`）——**system_server 主线程**。谁执行：转交在 Reader 线程（JNI 调用方），处理在主线程。后果：转交与处理之间隔着主线程的消息队列与 CPU 竞争，这正是 34.738 ms 空档的发生地（§7）。

四问归档（filter 消费-回注路径）：规则=装了过滤器的事件先转交后回注、转交即 return 不入队；谁设置=AccessibilityManagerService 经 setInputFilter 安装；谁执行=Reader 线程转交、主线程处理与回注、分发线程消费队列；后果=notifyMotion slice 时刻与入队时刻分离，回注经 `injectInputEvent` 保留原 id。

### 4.6 无过滤器的直入队路径与未装过滤器的判据

`mInputFilterEnabled` 为假时，:4029 的分支整体跳过，事件直接构造 `MotionEntry` 入队；同时 :4066-4071 对 Reader 来源的触摸调用 `mLatencyTracker.trackListener(args->id, isDown, args->eventTime, args->readTime)` 记录时延站点（LatencyAggregator 的 Timeline 语义见批07）。本样本该标志为真（判据：`filterInputEvent` slice 存在——JNI 函数只在 :4029 分支内被调用，`com_android_server_input_InputManagerService.cpp:1183-1214` 首行即 ATRACE_CALL；窗口内 17 条 slice 的 tid 都是 InputReader 1489，过程记录 Q2），故本样本走的是消费-回注路径。

### 4.7 回注：injectInputEvent 以同一 id 入队

```java
// frameworks/base/services/core/java/com/android/server/input/InputManagerService.java:3515-3530
        @Override
        public void sendInputEvent(InputEvent event, int policyFlags) {
            if (!checkCallingPermission(android.Manifest.permission.INJECT_EVENTS,
                    "sendInputEvent()")) {
                throw new SecurityException(
                        "The INJECT_EVENTS permission is required for injecting input events.");
            }
            Objects.requireNonNull(event, "event must not be null");

            synchronized (mInputFilterLock) {
                if (!mDisconnected) {
                    mNative.injectInputEvent(event, false /* injectIntoUid */, -1 /* uid */,
                            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, 0 /* timeout */,
                            policyFlags | WindowManagerPolicy.FLAG_FILTERED);
                }
            }
        }
```

走读：过滤器主线程处理完调 `InputFilter.sendInputEvent`（`InputFilter.java:162-178`）→ `mHost.sendInputEvent`（宿主是 `InputManagerService.InputFilterHost`）→ `mNative.injectInputEvent`，MODE_ASYNC 免等待。过滤器侧的处理入口是 `H.handleMessage` 的 MSG_INPUT_EVENT 分支（`InputFilter.java:244-254`）→ `onInputEvent(event, policyFlags)`（基类默认实现 :193-195 直接 `sendInputEvent` 回传；AccessibilityInputFilter 覆写 :234 起按启用特性走 EventStreamTransformation 链）。JNI 侧 `nativeInjectInputEvent`（`com_android_server_input_InputManagerService.cpp:1696-1737`）把 Java MotionEvent 转回 native 指针后调 `InputDispatcher::injectInputEvent`（`InputDispatcher.cpp:4173`）。三处关键差异使回注事件与原事件同源：

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:4194-4197 / 4282-4284
    int32_t resolvedDeviceId = VIRTUAL_KEYBOARD_ID;
    if (policyFlags & POLICY_FLAG_FILTERED) {
        resolvedDeviceId = event->getDeviceId();
    }
    ...
            std::unique_ptr<MotionEntry> injectedEntry =
                    std::make_unique<MotionEntry>(motionEvent.getId(), *sampleEventTimes,
                                                  resolvedDeviceId, motionEvent.getSource(),
                                                  displayId, policyFlags, action, actionButton,
```

走读：`POLICY_FLAG_FILTERED` 在位使 device id 保留原值（:4194-4197）并跳过 `interceptMotionBeforeQueueing` 二次拦截（:4264-4272）；`MotionEntry` 用 `motionEvent.getId()`——**事件 id 原样保留**（:4282）。因此 trace 中 prepare slice 的 `id=0x3b3b7f02` 与 Reader 侧 notifyMotion slice 同 id 并不矛盾：经过滤器回注的条目就是同一条事件（§6.7 的 prepare slice 名含该 id，已证链）。合批 MotionEvent 的每个样本各成一个 MotionEntry（:4297-4318，historySize 条数=批内消息数，批05 C28）。入队与唤醒收尾：

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:4335-4345
    bool needWake = false;
    while (!injectedEntries.empty()) {
        needWake |= enqueueInboundEventLocked(std::move(injectedEntries.front()));
        injectedEntries.pop();
    }

    mLock.unlock();

    if (needWake) {
        mLooper->wake();
    }
```

走读：注入方线程（此处为 system_server 主线程，证据见 §4.9）持锁逐条入队并负责 `mLooper->wake()`。MODE_ASYNC（:4328-4330）使 `injectInputEvent` 不等注入结果立即返回；同步注入（WAIT_FOR_RESULT/WAIT_FOR_FINISHED）才在 `mInjectionResultAvailable` 条件变量上等待（:4347-4396，超时 TIMED_OUT）。**who wakes**：S 终点 `6,830,330,803` 的 waker=tid 756（主线程），与此处 wake 的调用线程一致（已证，Q3）。

### 4.8 入队本身：enqueueInboundEventLocked（机制⑥ 之一）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:978-1049（节选）
bool InputDispatcher::enqueueInboundEventLocked(std::unique_ptr<EventEntry> newEntry) {
    bool needWake = mInboundQueue.empty();
    mInboundQueue.push_back(std::move(newEntry));
    EventEntry& entry = *(mInboundQueue.back());
    traceInboundQueueLengthLocked();

    switch (entry.type) {
        ... // KEY 分支 ：985-1022（app switch 0.5s 窗口、TRY_AGAIN_LATER 复位）
        case EventEntry::Type::MOTION: {
            LOG_ALWAYS_FATAL_IF((entry.policyFlags & POLICY_FLAG_TRUSTED) == 0,
                                "Unexpected untrusted event.");
            if (shouldPruneInboundQueueLocked(static_cast<MotionEntry&>(entry))) {
                mNextUnblockedEvent = mInboundQueue.back();
                needWake = true;
            }
            break;
        }
        ... // 略 ：1033-1045
    }

    return needWake;
}
```

走读：四问——规则：新事件挂**尾部**（push_back），`needWake` 的判据是入队前队列是否为空；空→分发线程多半睡在 pollOnce 里，必须 wake。谁设置：队列只在此处与 `dispatchOnceInnerLocked` 出队（:772-774）、`drainInboundQueueLocked`（:1232-1240）三处变更。谁执行：调用方线程（Reader 侧 notifyX 或注入方 injectInputEvent）持 mLock 执行。后果：needWake=true 时调用方 `mLooper->wake()` 打断 pollOnce；MOTION 分支附加"削队"预判——`shouldPruneInboundQueueLocked`（:921-976）在触摸 DOWN 落到与等待中应用不同的窗口、或落点存在响应中 spy 窗时置 `mNextUnblockedEvent`（:1027-1030），§5 的 BLOCKED 丢弃据此后续执行；指针 DOWN 还会提前终止按键对旧事件的等待（:970-974 复位 mKeyIsWaitingForEventsTimeout）。KEY 分支的 app switch 窗口（:992-1004）在抬起时定 `mAppSwitchDueTime = eventTime + APP_SWITCH_TIMEOUT`（0.5 s，:87/:1000）。

**注意作业单旧名勘误**：本函数不做 dropped 判定，A13 树中不存在 `isAppStolen`/`hasDefaultDisplayEventTarget`/`onAnrDelayStarted`（旧版 Android 的函数名，树内 grep 无命中）。A13 的对应机制=§5 的 DropReason 判定链与 ANR 计时起点——无焦点窗口 ANR 的起点在 `findFocusedWindowTargetsLocked:1898-1910`（挂起时定 `mNoFocusedWindowTimeoutTime = currentTime + 应用分发超时`，默认 5000 ms×硬件倍率，`InputDispatcher.cpp:80-81`）；连接级 ANR 的起点在 `startDispatchCycleLocked:3396-3399` 的 `mAnrTracker.insert(timeoutTime, token)`（发布写 socket 时，timeoutTime=currentTime+通道分发超时；详述见批07）。

### 4.9 trace 配对：0x3b3b7f02 在 Reader 线程的逐 slice 时序与空档九段账

过程记录 Q2（新数值留痕）对 tid=1489 在窗口 6.79–6.84 s 的查询：

| rel_ts（ns） | dur | slice | 归属 |
|---|---|---|---|
| 6,795,838,649 | 6,577 | `notifyMotion(id=0x3b3b7f02)` | 首条打点（#32 traceEvent） |
| 6,796,093,957 | 5,692 | `notifyMotion(id=0x3b3b7f02)` | 第二条打点（Blocker 级队列） |
| 6,796,110,226 | 5,231 | `notifyMotion(id=0x3b3b7f02)` | 第三条打点（Classifier 级队列） |
| 6,796,128,072 | 5,962 | `interceptMotionBeforeQueueing` | JNI ATRACE_CALL（:1256） |
| 6,796,151,188 | 109,961 | `filterInputEvent` | JNI ATRACE_CALL（:1184），转交完成于 6,796,261,149 |

走读：三条打点的间隔 255,308 ns / 16,269 ns 与批0 复验二的"255/16 μs"逐字一致（契约 :124 要求的双证：代码侧=InputListener.cpp:340-345/:361-364 的 traceEvent＋UnwantedInteractionBlocker.cpp:335-342、InputClassifier.cpp:430-441 的转发链；trace 侧=上表）。第三条打点结束时 Classifier 的 `mQueuedListener.flush()` 才执行 `InputDispatcher::notifyMotion` 本体（`InputClassifier.cpp:430-441`：`mQueuedListener.notifyMotion(args)` 后紧跟 `mQueuedListener.flush()`），所以**真正的 policy/filter 调用在第三个 slice 之外**——上表后两行 slice 与三条 notifyMotion 打点首尾相接，时序闭合。

主线程（tid 756）同期状态序列（Q6，新数值留痕）：R 9,313,231（起点 6,796,227,034，**waker=InputReader tid 1489**——转交中的 Handler.post 触发）→ Running 79,346（6,805,540,265）→ R+ 31,692 → Running 58,077 → R 189,346 → Running 201,769 → S 1,149,039（6,806,100,495）→ R 2,707,692（6,807,249,534，waker=InputReader）→ Running 403,308 → R+ 1,893,923 → Running 88,577 → R+ 118,115 → Running 108,116 → R+ 148,538 → Running 72,308 → R 17,289,307（6,812,790,111，无 waker 记录）→ Running 286,693（6,830,079,418）→ R+ 1,009,154（6,830,366,111）→ Running 1,480,730（6,831,375,265）。

**空档九段里程碑分解**（34,738,039 ns，以相邻里程碑切分）：

| 段 | 区间（rel ns） | 时长 | 段内事实 | 三态 |
|---|---|---|---|---|
| S1 | 6,795,838,649 → 6,796,261,149 | 422,500 | 三条 notifyMotion 打点＋intercept（5,962）＋filterInputEvent（109,961）；转交完成 | 已证 |
| S2 | 6,796,261,149 → 6,805,540,265 | 9,279,116 | 主线程 R 9,313,231（起点 6,796,227,034，waker=InputReader——转交中的 Handler.post 触发）；可运行未运行 | 已证（窗口）／未证（竞争归因） |
| S3 | 6,805,540,265 → 6,812,790,111 | 7,249,846 | 主线程间歇 Running（合计 927,862 ns）＋S 1,149,039＋R/R+；此段是否处理了该事件消息不可判（无 slice） | 已证（状态）／盲区（语义） |
| S4 | 6,812,790,111 → 6,830,079,418 | 17,289,307 | 主线程 R（无 waker 记录）；可运行未运行 17.289 ms | 已证（窗口）／未证（竞争归因） |
| S5 | 6,830,079,418 → 6,830,307,188 | 227,770 | 主线程 Running 286,693 窗口内：iq 计数 0→1 采样点 6,830,307,188＝入队（injectInputEvent 内 enqueueInboundEventLocked） | 已证 |
| S6 | 6,830,307,188 → 6,830,330,803 | 23,615 | needWake→mLooper->wake；主线程继续 Running | 已证（链） |
| S7 | 6,830,330,803 → 6,830,366,111 | 35,308 | 分发线程 R 35,308（waker=tid 756） | 已证 |
| S8 | 6,830,366,111 → 6,830,475,418 | 109,307 | 分发线程 Running 1,009,154 前段（pollOnce 返回→dispatchOnce→弹出队首，iq 1→0 采样于 6,830,457,303） | 已证 |
| S9 | 6,830,475,418 → 6,830,576,688 | 101,270 | `dispatchMotionLocked`（725,270）前段至首条 prepare：findTouchedWindowTargetsLocked 39,538＋setInjectionResult 等 | 已证 |

求和：422,500+9,279,116+7,249,846+17,289,307+227,770+23,615+35,308+109,307+101,270 = **34,738,039 ns**，与契约数值逐 ns 闭合（已证，算式与输入在过程记录 §2.9 留痕）。等价二段式：notify→入队 34,468,539 ＋ 入队→prepare 269,500。

## 5. dispatchOnceInnerLocked 的丢弃判定链（机制四问⑥）

### 5.1 取出后先判丢弃

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:785-796
    ALOG_ASSERT(mPendingEvent != nullptr);
    bool done = false;
    DropReason dropReason = DropReason::NOT_DROPPED;
    if (!(mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER)) {
        dropReason = DropReason::POLICY;
    } else if (!mDispatchEnabled) {
        dropReason = DropReason::DISABLED;
    }

    if (mNextUnblockedEvent == mPendingEvent) {
        mNextUnblockedEvent = nullptr;
    }
```

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:868-882
        case EventEntry::Type::MOTION: {
            std::shared_ptr<MotionEntry> motionEntry =
                    std::static_pointer_cast<MotionEntry>(mPendingEvent);
            if (dropReason == DropReason::NOT_DROPPED && isAppSwitchDue) {
                dropReason = DropReason::APP_SWITCH;
            }
            if (dropReason == DropReason::NOT_DROPPED && isStaleEvent(currentTime, *motionEntry)) {
                dropReason = DropReason::STALE;
            }
            if (dropReason == DropReason::NOT_DROPPED && mNextUnblockedEvent) {
                dropReason = DropReason::BLOCKED;
            }
            done = dispatchMotionLocked(currentTime, motionEntry, &dropReason, nextWakeupTime);
            break;
        }
```

走读：MOTION 的丢弃判定先于目标裁决。三问三答——规则：按优先级短路（APP_SWITCH→STALE→BLOCKED）。谁设置：`isAppSwitchDue = mAppSwitchDueTime <= currentTime`（:739），mAppSwitchDueTime 在 app switch 键抬起时定（§4.7）；STALE 的阈值 `isStaleEvent`（:913-915）＝`currentTime − eventTime ≥ mStaleEventTimeout`（默认 10 s×倍率，:89）；BLOCKED 由 §4.7 的 `mNextUnblockedEvent` 触发。谁执行：分发线程。后果：dropReason 非 NOT_DROPPED 时 `dispatchMotionLocked` 直接 `setInjectionResult` 返回 true（:1645-1650），随后 `dropInboundEventLocked`（:902-909）按原因向所有连接广播合成 CANCEL 并释放条目——被丢的事件不产生任何 prepare/deliver slice，trace 判据为"该 id 无三通道 prepare"。本样本 0x3b3b7f02 正常走完三通道（Q1），未被判丢（已证）。

### 5.2 丢弃的收尾：CANCEL 广播

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:1149-1165（节选）
    switch (entry.type) {
        case EventEntry::Type::KEY: {
            CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS, reason);
            synthesizeCancelationEventsForAllConnectionsLocked(options);
            break;
        }
        case EventEntry::Type::MOTION: {
            const MotionEntry& motionEntry = static_cast<const MotionEntry&>(entry);
            if (motionEntry.source & AINPUT_SOURCE_CLASS_POINTER) {
                CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS, reason);
                synthesizeCancelationEventsForAllConnectionsLocked(options);
            } else {
                ... // 略 ：1160-1163
            }
            break;
        }
        ... // 略 ：1166-1180
    }
```

走读：丢弃的收尾不只是释放条目：按键丢一至多影响一笔，但指针手势断了中间帧会让应用侧手势状态悬空，所以按事件类向所有连接广播合成 CANCEL（`CANCEL_POINTER_EVENTS`/`CANCEL_NON_POINTER_EVENTS`，`CancelationOptions.h`）；丢弃原因串（reason）随选项一路带给应用（六种 reason 文案 :1112-1146）。FOCUS/TOUCH_MODE_CHANGED/CONFIGURATION_CHANGED/DEVICE_RESET 走丢弃即 FATAL（:1173-1179）。丢弃原因逐条对照（四问收口）：POLICY=policy 摘掉 PASS_TO_USER；DISABLED=`setInputDispatchMode(false,…)`；APP_SWITCH=0.5 s 窗口到期时队列里还有事件；STALE=事件比当前时刻老 10 s×倍率以上；BLOCKED=应放行的新手势在队列里等待；NO_POINTER_CAPTURE=捕获窗消失。

### 5.3 与 34.738 ms 的关系

丢弃链只发生在事件**已入队之后**；本样本事件在空档末段才入队（§4.9 S5），STALE 的 10 s 阈值与 34 ms 相差两个数量级，APP_SWITCH/BLOCKED 均无触发条件（无 app switch 键、无 mNextUnblockedEvent——判据：三通道 prepare 全部发生）。该候选（"空档是丢弃链造成的"）机制上不可能承载此空档——已证排除（§7 候选并入 C2/C3 的排除论证）。

## 6. dispatchMotionLocked 与目标裁决（机制四问③④）

触摸事件进入分发阶段后，系统需要确定接收它的窗口或其他合法目标。目标选择会参考触点位置、窗口状态和策略；选定目标只是准备分发，不表示应用已经收到或处理事件。

### 6.1 dispatchMotionLocked 前半：分流与失败出口

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:1634-1697（节选，ATRACE_CALL 在 :1636）
bool InputDispatcher::dispatchMotionLocked(nsecs_t currentTime, std::shared_ptr<MotionEntry> entry,
                                           DropReason* dropReason, nsecs_t* nextWakeupTime) {
    ATRACE_CALL();
    if (!entry->dispatchInProgress) {
        entry->dispatchInProgress = true;
        logOutboundMotionDetails("dispatchMotion - ", *entry);
    }

    if (*dropReason != DropReason::NOT_DROPPED) {
        setInjectionResult(*entry, ...);
        return true;
    }

    const bool isPointerEvent = isFromSource(entry->source, AINPUT_SOURCE_CLASS_POINTER);

    std::vector<InputTarget> inputTargets;
    ...
    if (isPointerEvent) {
        injectionResult =
                findTouchedWindowTargetsLocked(currentTime, *entry, inputTargets, nextWakeupTime,
                                               &conflictingPointerActions);
    } else {
        injectionResult =
                findFocusedWindowTargetsLocked(currentTime, *entry, inputTargets, nextWakeupTime);
    }
    if (injectionResult == InputEventInjectionResult::PENDING) {
        return false;
    }
    ... // 略 ：1673-1684（TARGET_MISMATCH / 失败时给 monitor 发合成 CANCEL）
```

走读：触摸屏事件 source 带 `AINPUT_SOURCE_CLASS_POINTER`，走 `findTouchedWindowTargetsLocked`；轨迹球类走焦点路径（`findFocusedWindowTargetsLocked:1869-1962`，批07 详述，本篇只引其 ANR 计时起点 :1898-1910）。本函数有 ATRACE_CALL（:1636）——trace 的 `dispatchMotionLocked` slice 即此。PENDING 返回（:1669-1671）只可能来自焦点路径；触摸路径的 injectionResult 从 PENDING 初值（:2002）出发，所有出口都是 SUCCEEDED/FAILED/TARGET_MISMATCH，**触摸事件不会挂起在裁决中**——这是 §7 C3 排除论证的机制前提（已证）。dispatchInProgress 置位（:1638-1642）防止同一 Entry 重复预处理；`logOutboundMotionDetails`（:1722-1751）只在 DEBUG_OUTBOUND_EVENT_DETAILS 打开时输出（本样本无对应日志轨）。

### 6.2 findTouchedWindowTargetsLocked：状态机骨架

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2009-2055（节选，函数头 :1990-1996 有 ATRACE_CALL）
    const TouchState* oldState = nullptr;
    TouchState tempTouchState;
    if (const auto it = mTouchStatesByDisplay.find(displayId); it != mTouchStatesByDisplay.end()) {
        oldState = &(it->second);
        tempTouchState = *oldState;
    }

    bool isSplit = tempTouchState.split;
    bool switchedDevice = ...;
    ...
    const bool newGesture = (maskedAction == AMOTION_EVENT_ACTION_DOWN ||
                             maskedAction == AMOTION_EVENT_ACTION_SCROLL || isHoverAction);
    ...
    if (newGesture) {
        ...
        tempTouchState.reset();
        tempTouchState.down = down;
        tempTouchState.deviceId = entry.deviceId;
        tempTouchState.source = entry.source;
        tempTouchState.displayId = displayId;
        isSplit = false;
    } else if (switchedDevice && maskedAction == AMOTION_EVENT_ACTION_MOVE) {
        ... // 略 ：2046-2054 设备切换拒收
    }
```

走读：裁决的底本是 `TouchState`（`TouchState.h:31-59`：down/split/preventNewTargets＋deviceId/source/displayId 三个"这场按下只认一家"的拒绝位＋windows 列表；源码学习副本的批注位置 TouchState.h:40）。每帧先把 `mTouchStatesByDisplay[displayId]` 拷进 tempTouchState，按 action 演进，成功后写回（:2483-2489），SCROLL 特例只在本帧有效（:2481-2482 不写回）。本样本是单指连续 MOVE：非 DOWN 分支沿用既有窗口集合（Case 2，:2202-2293），落点命中不重新做——**DOWN 帧**（手势开头，事件 id 不同）才走完整命中。

### 6.3 DOWN 帧命中：三判据与 spy 收集

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:462-477
bool windowAcceptsTouchAt(const WindowInfo& windowInfo, int32_t displayId, int32_t x, int32_t y,
                          bool isStylus) {
    const auto inputConfig = windowInfo.inputConfig;
    if (windowInfo.displayId != displayId ||
        inputConfig.test(WindowInfo::InputConfig::NOT_VISIBLE)) {
        return false;
    }
    const bool windowCanInterceptTouch = isStylus && windowInfo.interceptsStylus();
    if (inputConfig.test(WindowInfo::InputConfig::NOT_TOUCHABLE) && !windowCanInterceptTouch) {
        return false;
    }
    if (!windowInfo.touchableRegionContainsPoint(x, y)) {
        return false;
    }
    return true;
}
```

走读（命中三判据）：同屏且可见→可触摸（NOT_TOUCHABLE 排除，触笔拦截窗例外 :469）→点在 touchableRegion 内。区域由 WMS 在窗口列表推送前算好（WindowInfo 携带），分发侧只做点测。`findTouchedWindowAtLocked`（:1061-1088）从前往后（Z 序，`windowHandles` 由 `setInputWindows:4660-4680` 推送）扫，第一个非 spy 且过三判据的窗口即主目标，同时按 `addOutsideTargets` 给声明 `WATCH_OUTSIDE_TOUCH` 的更上层窗挂 OUTSIDE 目标（:1081-1085）。`findTouchedSpyWindowsAtLocked`（:1090-1108）同样从前往后收集 spy 窗，**遇到第一个命中的非 spy 窗即停**——spy 只收主目标之上的。主目标随后插到 spy 列表最前：

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2118-2132（节选）
        std::vector<sp<WindowInfoHandle>> newTouchedWindows =
                findTouchedSpyWindowsAtLocked(displayId, x, y, isStylus);
        if (newTouchedWindowHandle != nullptr) {
            // Process the foreground window first so that it is the first to receive the event.
            newTouchedWindows.insert(newTouchedWindows.begin(), newTouchedWindowHandle);
        }
        ...
        for (const sp<WindowInfoHandle>& windowHandle : newTouchedWindows) {
            const WindowInfo& info = *windowHandle->getInfo();
            ...
            if (info.inputConfig.test(WindowInfo::InputConfig::PAUSE_DISPATCHING)) {
                ... continue;
            }
            const bool isResponsive = hasResponsiveConnectionLocked(*windowHandle);
            if (!isResponsive) {
                ... continue;
            }
            ... // 略 ：2154-2177 遮挡信任与 input feature 丢弃
            int32_t targetFlags = InputTarget::FLAG_DISPATCH_AS_IS;
            if (canReceiveForegroundTouches(*windowHandle->getInfo())) {
                targetFlags |= InputTarget::FLAG_FOREGROUND;
            }
            ... // 略 ：2187-2200 split 与 obscured 标志、tempTouchState.addOrUpdateWindow
        }
```

走读：逐窗口过五道闸（定向注入校验 `verifyTargetedInjection` :510-523/:2136、PAUSE_DISPATCHING :2140、连接响应性 `hasResponsiveConnectionLocked` :4576-4597：无连接或连接非 responsive 即跳过、不可信触摸遮挡 :2155-2172 `computeTouchOcclusionInfoLocked:2708` 与 `isTouchTrustedLocked:2776`、input feature 丢弃 `shouldDropInput` :2175），再落成 `TouchedWindow`（`TouchedWindow.h:29-33`：windowHandle＋targetFlags＋pointerIds）挂进 tempTouchState；`TouchState::addOrUpdateWindow`（`TouchState.cpp:32-57`）对已在集合的窗口做"或并"（派发模式按位或、指针子集按位或、SLIPPERY_EXIT 清 AS_IS），`preventNewTargets` 置位时拒收新窗（:50）。foreground 资格由 `canReceiveForegroundTouches`（:490-494：非 NOT_TOUCHABLE 且非 spy）决定。**作业单词勘误**：A13 的 findTouchedWindowTargetsLocked 内不存在"IdleTimeout"机制（树内 grep 无该函数/常量）；时间类判定在本篇段位的只有 §5 的 STALE（isStaleEvent）与批07 的分发超时（startDispatchCycleLocked 的 timeoutTime）。OUTSIDE 判定即上引 :1081-1085（WATCH_OUTSIDE_TOUCH）；"INTERCEPTIVE"对应 `interceptsStylus()`（:469，触笔拦截）。重叠窗口/分屏：非 spy 重叠窗不并列命中（第一个即止），分屏由 `supportsSplitTouch`（:2094-2101）与 FLAG_SPLIT 控制，`prepareDispatchCycleLocked` 侧按 pointerIds 拆子事件（:2924-2950，机制详述见批07）。

### 6.4 Case 2（MOVE/UP）与 hover、slippery、wallpaper

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2219-2277（节选）
        if (maskedAction == AMOTION_EVENT_ACTION_MOVE && entry.pointerCount == 1 &&
            tempTouchState.isSlippery()) {
            ...
            newTouchedWindowHandle =
                    findTouchedWindowAtLocked(displayId, x, y, &tempTouchState, isStylus);
            ...
            if (oldTouchedWindowHandle != newTouchedWindowHandle &&
                oldTouchedWindowHandle != nullptr && newTouchedWindowHandle != nullptr) {
                ...
                tempTouchState.addOrUpdateWindow(oldTouchedWindowHandle,
                                                 InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT,
                                                 BitSet32(0));
                ...
                tempTouchState.addOrUpdateWindow(newTouchedWindowHandle, targetFlags, pointerIds);
            }
        }
```

走读：Case 2 的常规路径是沿用集合＋`addDragEventLocked`（:2216，拖拽状态机入口）；只有单指 MOVE 且 `TouchState::isSlippery()`（`TouchState.cpp:97-111`：恰好一个前台窗且其 SLIPPERY 位置位）才重做落点命中，窗口变更时给旧窗挂 SLIPPERY_EXIT、新窗挂 SLIPPERY_ENTER（MOVE→DOWN/CANCEL 的动作改写发生在批07 的发布侧）。hover 进出在 :2296-2323（`mLastHoverWindowHandle` 差分挂 HOVER_EXIT/HOVER_ENTER）；前台窗资格校验在 :2329-2339（集合里连一个可作前台/本就不可作前台的窗都没有→FAILED）；DOWN 时按窗外 uid 对 OUTSIDE 副本补 FLAG_ZERO_COORDS（:2364-2380，坐标抹零防泄露）；壁纸连带（DUPLICATE_TOUCH_TO_WALLPAPER）在 :2388-2413；POINTER_UP 时从各窗 pointerIds 清位、空集窗移除（:2465-2479）。裁决通过后按 tempTouchState.windows 登记序转成 InputTarget（:2418-2421）并 `filterNonAsIsTouchWindows()`（`TouchState.cpp:68-80`：OUTSIDE/HOVER 副本不入下一帧）。

### 6.5 FocusResolver 与 monitor 两通道：从哪里注册（机制③ 的 focus 与 monitor 部分）

**FocusResolver**（按键路径的焦点依据）：

```cpp
// frameworks/native/services/inputflinger/dispatcher/FocusResolver.cpp:57-90（节选）
std::optional<FocusResolver::FocusChanges> FocusResolver::setInputWindows(
        int32_t displayId, const std::vector<sp<WindowInfoHandle>>& windows) {
    std::string removeFocusReason;

    const sp<IBinder> currentFocus = getFocusedWindowToken(displayId);
    if (currentFocus) {
        Focusability result = isTokenFocusable(currentFocus, windows);
        if (result == Focusability::OK) {
            return std::nullopt;
        }
        removeFocusReason = ftl::enum_string(result);
    }
    ... // 留存请求复核 ：73-85
    return updateFocusedWindow(displayId, removeFocusReason, nullptr);
}
```

走读：焦点不在 findTouchedWindowTargetsLocked 里算，而在窗口列表每次推送（`setInputWindows`，`InputDispatcher.cpp:4660-4672`，推送后 wake 分发线程 :4679）时由 FocusResolver 重算：当前焦点仍可获焦→维持；否则复核留存请求（`mFocusRequestByDisplay`，`FocusResolver.h:106`）；都不行→摘除焦点。可获焦三查（`isTokenFocusable`，FocusResolver.cpp:177-190 区间）：有窗口、全部可获焦、有可见（源码学习副本的批注位置 FocusResolver.h:86/:116/:131）。裁决出的焦点供两条路径消费：按键类事件的目标（`findFocusedWindowTargetsLocked:1875` 的 `getFocusedWindowHandleLocked`）与焦点事件本身的分发（`dispatchFocusLocked` :1347）。触摸路径不查焦点——触摸目标是按落点算的，这是"触摸裁决"与"焦点裁决"两套并行依据的分界。

**全局 monitor**（`PointerEventDispatcher2`）：

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:5518-5531（节选）
        sp<Connection> connection = new Connection(serverChannel, true /*monitor*/, mIdGenerator);
        const sp<IBinder>& token = serverChannel->getConnectionToken();
        const int fd = serverChannel->getFd();
        ...
        mConnectionsByToken.emplace(token, connection);
        std::function<int(int events)> callback = std::bind(&InputDispatcher::handleReceiveCallback,
                                                            this, std::placeholders::_1, token);

        mGlobalMonitorsByDisplay[displayId].emplace_back(serverChannel, pid);

        mLooper->addFd(fd, 0, ALOOPER_EVENT_INPUT, new LooperEventCallback(callback), nullptr);
```

走读：`IMS.monitorInput`（`InputManagerService.java:703-711`）→ JNI `createInputMonitor`（`com_android_server_input_InputManagerService.cpp:518-521`）→ 此处。server 端成 Connection（monitor=true）＋`mGlobalMonitorsByDisplay[displayId]` 登记＋fd 注册进分发 Looper。调用方：WMS `DisplayContent` 构造——`mWmService.mInputManager.monitorInput("PointerEventDispatcher" + mDisplayId, mDisplayId)`（`DisplayContent.java:1113-1115`）。通道名构成 `PointerEventDispatcher<displayId>`，trace 中的 `PointerEventDispatcher2 (server)` 即 displayId=2 的实例（名字构成已证；该显示的拓扑归属本篇不判，盲区声明 §10）。消费端 `PointerEventDispatcher extends InputEventReceiver`（`PointerEventDispatcher.java:30-36`，跑在 UiThread looper）逐事件回调注册的 PointerEventListener 后 `finishInputEvent`（:39-58）。

**spy 窗**（`[Gesture Monitor] CustomGesture-touch (server)`）：

```java
// frameworks/base/services/core/java/com/android/server/input/GestureMonitorSpyWindow.java:59-73（节选）
        mWindowHandle.name = name;
        mWindowHandle.token = mClientChannel.getToken();
        mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        ...
        mWindowHandle.inputConfig =
                InputConfig.NOT_FOCUSABLE | InputConfig.SPY | InputConfig.TRUSTED_OVERLAY;
        ...
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setLayer(mInputSurface, Integer.MAX_VALUE);
```

走读：`IMS.monitorGestureInput`（`InputManagerService.java:760-784`，通道名强制 `"[Gesture Monitor] " + requestedName`，:772；权限门禁 MONITOR_INPUT :762-765）→ `createSpyWindowGestureMonitor`（:714-739，普通通道＋spy 窗 Surface＋linkToDeath 回收登记）→ 此类把 `InputConfig::SPY` 写进 InputWindowHandle 并以整层 MAX_VALUE 上屏（:70）。spy 窗随窗口列表（setInputWindows）进入分发侧，被 §6.3 的 `findTouchedSpyWindowsAtLocked` 按落点命中；触点区域用父容器边界（`replaceTouchableRegionWithCrop(null)`，:64）——整屏。**两条注册路径的判定后果**：全局 monitor 不做命中、每帧追加（`addGlobalMonitoringTargetsLocked`，:2638-2653，只保留 responsive 连接 `selectResponsiveMonitorsLocked` :1968-1988）；spy 窗参与命中、与主目标一起组成 targets 前段。`[Gesture Monitor]` 不在 mGlobalMonitorsByDisplay、`PointerEventDispatcher2` 不在窗口列表——trace 中三通道由两套机制产出（已证，机制+名字双证）。

### 6.6 InputTarget 组装：为什么三 target、顺序由什么决定（机制④）

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2418-2425
    for (const TouchedWindow& touchedWindow : tempTouchState.windows) {
        addWindowTargetLocked(touchedWindow.windowHandle, touchedWindow.targetFlags,
                              touchedWindow.pointerIds, inputTargets);
    }

    // Drop the outside or hover touch windows since we will not care about them
    // in the next iteration.
    tempTouchState.filterNonAsIsTouchWindows();
```

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2599-2636（节选）
void InputDispatcher::addWindowTargetLocked(const sp<WindowInfoHandle>& windowHandle,
                                            int32_t targetFlags, BitSet32 pointerIds,
                                            std::vector<InputTarget>& inputTargets) {
    std::vector<InputTarget>::iterator it =
            std::find_if(inputTargets.begin(), inputTargets.end(),
                         [&windowHandle](const InputTarget& inputTarget) {
                             return inputTarget.inputChannel->getConnectionToken() ==
                                     windowHandle->getToken();
                         });
    ...
    if (it == inputTargets.end()) {
        InputTarget inputTarget;
        std::shared_ptr<InputChannel> inputChannel =
                getInputChannelLocked(windowHandle->getToken());
        if (inputChannel == nullptr) {
            ALOGW("Window %s already unregistered input channel", windowHandle->getName().c_str());
            return;
        }
        inputTarget.inputChannel = inputChannel;
        ...
        inputTargets.push_back(inputTarget);
        ...
    }
    it->addPointers(pointerIds, windowInfo->transform);
}
```

走读：一个事件产生三个 target 的原因——①AppList 主通道：DOWN 帧落点命中的前台窗口（FLAG_FOREGROUND|DISPATCH_AS_IS），MOVE 帧沿用 `mTouchStatesByDisplay` 登记的同一集合；②Gesture Monitor：spy 窗随 DOWN 帧命中登记进 tempTouchState（`InputConfig::SPY` 窗过三判据即入集合）；③PointerEventDispatcher2：全局 monitor 由 `dispatchMotionLocked` 在窗口 target 之后追加（:1687）。顺序规则（已证，代码序）：tempTouchState.windows 的登记序＝主目标（insert 到 begin）→ spy 窗（命中序），随后 `addGlobalMonitoringTargetsLocked` 追加全局 monitor（每显示器一张表、一律 DISPATCH_AS_IS、指针变换用显示默认变换 :2643-2652）。`addWindowTargetLocked`（:2599-2636）按 token 去重、首见创建（查 InputChannel 失败即丢目标）、追加 pointer 变换（`InputTarget::addPointers`，`InputTarget.cpp:46` 区间）。`InputTarget` 的 dispatch 模式位表（`InputTarget.h:34-91`：AS_IS/AS_OUTSIDE/HOVER_ENTER/HOVER_EXIT/SLIPPERY_EXIT/SLIPPERY_ENTER 六种＋ZERO_COORDS）在 `enqueueDispatchEntryLocked`（:2989-3003）逐模式过滤——六模式循环的 trace 侧证据见 §6.7。`dispatchEventLocked`（:1753-1780）按此 vector 顺序逐 target 查 Connection 后调 `prepareDispatchCycleLocked`——**三通道 prepare 的先后顺序＝该 vector 的遍历顺序**（连接不存在则跳过，:1770-1778）。发布与 waitQueue 的机制（`enqueueDispatchEntriesLocked` 的 wasEmpty→`startDispatchCycleLocked`→`publishMotionEvent` 写 socket）见批07；本篇只引其边界：AppList 通道 enqueue 后立即 startDispatchCycle，写 socket 唤醒应用——Launcher 主线程 R 起点 `6,830,764,842` 的 waker=tid 1488（批0 复验二直证）与该时序一致。

### 6.7 trace 配对：三通道 prepare/enqueue 逐点表（0x3b3b7f02）

批0 复验 §2.2 数值＋过程记录 Q1 全序列（slice 名与 `ATRACE_NAME` 格式对应，`InputDispatcher.cpp:2898-2903/:2960-2965/:2993-2998/:3223-3227`）：

| 通道 | prepare（rel ns/dur） | enqueueDispatchEntriesLocked | startDispatchCycleLocked |
|---|---|---|---|
| AppList 主通道 | 6,830,576,688 / 5,846 | 6,830,592,265 / 5,769 | 6,830,707,457 / 5,269 |
| Gesture Monitor | 6,830,822,342 / 5,615 | 6,830,835,226 / 5,616 | 6,830,937,342 / 5,538 |
| PointerEventDispatcher2 | 6,831,012,842 / 5,461 | 6,831,025,495 / 5,423 | 6,831,128,611 / 5,500 |

走读：顺序与 §6.6 的 vector 遍历序完全一致（AppList→Gesture Monitor→PointerEventDispatcher2）。每个通道 prepare 后六条 `enqueueDispatchEntry(dispatchMode=…)` 小 slice（HOVER_EXIT/OUTSIDE/HOVER_ENTER/AS_IS/SLIPPERY_EXIT/SLIPPERY_ENTER 六模式循环，`InputDispatcher.cpp:2970-2981`）——本样本每通道只有 DISPATCH_AS_IS 产生条目，其余五模式空转（Q1 六条小 slice 但 #30 waitQueue 侧无对应，见批07）。`prepareDispatchCycleLocked` 对 status 非 NORMAL 的连接直接跳过（:2912-2921）、FLAG_SPLIT 时拆子事件（:2924-2950）——本样本无 split（单窗单指）。应用线程 deliver slice（`6,831,006,342`，#10）与 PointerEventDispatcher2 的 prepare（`6,831,012,842`）并发：deliver 是应用主线程 slice（tid 1909），prepare 是分发线程 slice（tid 1488）——两条线程轨独立推进，不是因果先后（批0 复验二已证口径，机制对应=AppList 通道 startDispatchCycle 写 socket 唤醒应用（批07），分发线程继续处理 vector 后续项）。

### 6.8 六模式循环与 pokeUserActivity：本样本的 trace 实测

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp:2967-2987（节选）
    bool wasEmpty = connection->outboundQueue.empty();

    // Enqueue dispatch entries for the requested modes.
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_OUTSIDE);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_IS);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
                               InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER);

    // If the outbound queue was previously empty, start the dispatch cycle going.
    if (wasEmpty && !connection->outboundQueue.empty()) {
        startDispatchCycleLocked(currentTime, connection);
    }
```

走读：`enqueueDispatchEntryLocked`（:2989-3003）对每个 dispatch 模式做一次"目标 flags 含该模式才建条目"的过滤（:3000-3002 `!(inputTargetFlags & dispatchMode)` 即 return），每次调用都有 ATRACE_NAME 小 slice——这就是 trace 中每通道六条 5 μs 级 `enqueueDispatchEntry(dispatchMode=…)` slice 的来源。六条小 slice 中只有 AS_IS 实际建了 DispatchEntry（目标 flags=DISPATCH_AS_IS），其余五次空转返回。wasEmpty 判定（:2983-2986）把"启动分发周期"限定在队列从空变非空的时刻——上一个周期未完成时只排队（批07）。`pokeUserActivityLocked`（:2841 起）在出队点（:777-780）与 `dispatchEventLocked`（:1765）两处调用，向 PowerManagerService 报告用户活动；trace 中 `pokeUserActivity` 两条 slice（`6,831,220,072`/`6,831,234,226`，8,308/5,654 ns）落在三通道全部 prepare 之后，与 :1765 的调用位置一致（Q1 depth 0）。

## 7. 34.738 ms 空档候选全表（本篇核心交付）

判据说明：本 trace 可判的依据有三类——slice（带名/带 id）、thread_state（含 waker）、counter（iq）。凡候选判为"已证排除"，给排除判据；判为"已证"，给成立判据；"未证/盲区"给可判定所需补采点。总表先行，随后逐候选展开。

| # | 候选 | 机制位置（源码） | 本 trace 判据 | 三态 |
|---|---|---|---|---|
| C1 | **filter 消费-回注路径耗时**：事件转交过滤器后不入队，回注前的时间全算进空档 | `InputDispatcher.cpp:4046-4049`（转交即 return）＋`:4335-4345`（回注入队）＋`InputFilter.java:152-154/:123-124`＋`AccessibilityInputFilter.java:191`（主线程 Handler） | ①`filterInputEvent` slice 6,796,151,188/109,961 存在→mInputFilterEnabled=true；②iq 恒 0 至 6,830,307,188→此间不入队；③主线程 Running 286,693 窗口内 iq 0→1＋其后续 wake 链；④waker 链 tid756→tid1488→tid1909 逐跳成立；⑤prepare slice id 与 notifyMotion id 相同→回注保留 id | **已证（本空档的实际机制）** |
| C1a | filter 内部分解：主线程消息排队＋可运行窗口 | 主线程 thread_state：R 9,313,231（waker Reader）＋混合段 7,249,846＋R 17,289,307＋Running 227,770 至入队 | 窗口数值已证；**窗口内竞争归因（谁占了 CPU）未证**（§10 第 1 项，批14 收口）；filter 内部是否改写/缓存事件不可判（无 slice） | 已证（窗口）／未证（归因）／盲区（filter 内部） |
| C2 | **inbound queue 前序事件未清**：空档=队列排队 | §3.4 机制＋`enqueueInboundEventLocked:978-981` | iq 计数 6,771,783,765 采样 0 → 6,830,307,188 采样 1，中间零采样→队列全程空；且 S 窗口无 dispatch slice | **已证排除** |
| C3 | **前事件分发周期未回执（waitQueue 占用）阻塞本事件** | `Connection` waitQueue＋`shouldWaitToSendKeyLocked:1839-1867`（仅按键）＋触摸路径无 PENDING 出口（:1669-1671 仅焦点路径） | ①触摸裁决无 PENDING（机制）；②空档内 dispatcher S 连续（无 Running→无任何分发活动可"被阻塞"）；③iq 全程 0（无待发事件）。waitQueue 机制与 ANR 判定见批07 | **已证排除** |
| C4 | **monitor 通道队列堆积** | `addGlobalMonitoringTargetsLocked:2638-2653` 追加；publish 失败只停该连接（`startDispatchCycleLocked:3361-3388`，WOULD_BLOCK 留 outboundQueue） | 空档终点前无任何 prepare slice（Q1）→monitor 侧不可能先于主通道；堆积实际发生在**空档之后**（iq 1→4，6,831,978/6,832,281/6,832,668/6,834,162；排空至 6,856,607，Q8）——它是后续事件延迟的候选，不是本空档的成因 | **已证排除（本空档）**；对后续事件**已证存在**（iq 上升段），归因未证 |
| C5 | **policy 拦截耗时** | `interceptMotionBeforeQueueing` JNI :1254-1286（交互态短路）＋Java :3037-3041 | 该事件对应 slice 5,962 ns；交互态不回调 Java（机制） | **已证排除** |
| C6 | **filter 转交调用本身耗时**（JNI 拷贝＋Java 调用） | `NativeInputManager::filterInputEvent` :1183-1214（ATRACE_CALL :1184） | slice 109,961 ns（0.110 ms），只占空档 0.3%；同窗其余 15 条 0.016–1.830 ms（Q2） | **已证排除** |
| C7 | **dispatchOnce 两次调用间被 runCommands 占用** | `dispatchOnce:614-618`（命令队列清空，命令可放锁回调 policy） | S 连续 50.446 ms 且窗口内 tid1488 零 slice（Q1）——命令执行发生在 dispatchOnce 的 Running 段，S 段不可能在跑命令；命令作为空档成因被排除 | **已证排除** |
| C8 | **Looper pollOnce 超时返回节奏**（分发线程睡到超时才醒） | `dispatchOnce:632-635`＋`processAnrsLocked:670-703`（nextAnrCheck 定时） | S 终点 6,830,330,803 的 waker=tid 756 直证是 wake 而非超时返回（超时无 waker）；S 起点侧的唤醒源不可判（无 syscall/irq 记录，批00 §3.7 盲区沿用） | **已证排除（终点侧）**；起点侧**盲区** |
| C9 | **低优先级线程竞争／调度延迟**（S2/S4 的 R 窗口成因） | kernel 调度层（批14 域） | 主线程 R 9,313,231＋17,289,307 已证；CPU3 竞争窗口的裁剪统计与归因未做（批00 §3.7 的 Reader 竞争清单不适用于主线程窗口，需批14 重做） | **未证→批14** |
| C10 | **logd／其他子系统占用**（旧结论"日志税"类候选） | — | 分发线程与主线程在本空档无 D 态记录（Q3/Q6 状态序列无 D）；Reader 线程的 D 3,716,500（write_pmsg）发生在 6,801,837–6,805,553，与主线程 R 窗口重叠但因果未证（契约 :74 口径维持） | 排除（本空档两线程无 D）＋未证（Reader D 与空档的因果） |

### 7.1 C1 的五项判据逐一核对

①`filterInputEvent` slice 的存在与 tid：Q2 全窗口（6.7–7.0 s）17 条，tid 全部为 1489——JNI 函数只在 `:4029` 分支被调用，故 `mInputFilterEnabled` 在这些时刻为 true（已证）。②iq 零采样段：Q4 显示 6,771,783,765→6,830,307,188 之间无任何采样，队列操作三处（入队/出队/清队）都会采样——零采样等价于零操作（已证）。③入队落点：iq 0→1 采样于 6,830,307,188，落在主线程 Running 286,693 窗口（6,830,079,418–6,830,366,111）内——入队线程=主线程（已证）。④waker 链：主线程 Running→wake（`:4343-4345`）→分发线程 R（6,830,330,803，waker tid 756）→分发线程 Running 内 startDispatchCycle 写 socket→Launcher R（6,830,764,842，waker tid 1488，批0 复验二）——三跳 waker 全部直证（已证）。⑤id 一致：notifyMotion slice `id=0x3b3b7f02` 与三通道 prepare slice `id=0x3b3b7f02` 相同，`injectInputEvent` 保 id（:4282）——回注事件与原事件同一（已证）。

### 7.2 C2/C3 的排除论证展开

C2（队列排队）：机制上"队列非空则分发线程不睡"（§3.4 已证），trace 上队列全程空（iq 恒 0）——两条独立证据同向。若候选成立需要"队列里有前序事件"，则 iq 必然先出现 >0 采样，实际没有。C3（waitQueue 占用）：A13 中能挂起分发循环的 PENDING 出口只有两处——`findFocusedWindowTargetsLocked` 的无焦点窗挂起（:1898-1919，触摸不走）与按键的 shouldWaitToSendKeyLocked（:1948-1953，仅 KEY）；触摸路径的 `dispatchMotionLocked` 从不返回 false（:1669-1671 的 false 只可能来自焦点路径）。且"waitQueue 有未回执条目"的效果是 publish 阶段 WOULD_BLOCK 留在 outboundQueue（:3361-3388，批07），**不影响 mInboundQueue 的继续弹出**——即便 waitQueue 占用，事件照样入队、prepare slice 照样产生，与本 trace "空档内零 prepare"矛盾（已证排除）。

### 7.3 旧样本对照与成立条件

旧样本（finger.perfetto，0x37ee8b11，批0 复验二）：同链路 notify→prepare 5,167,308 ns。批06 新增 Q7 查询：旧样本 iq 计数在同窗的采样为 0→1（`9,799,102,246`）→0（`9,799,933,476`）→…，即**旧样本同样走消费-回注路径**：notify 首条 9,794,905,399 → 入队 9,799,102,246＝4,196,847 ns（filter 路径耗时）＋ 入队→prepare 970,461 ns，两项相加恰为 5,167,308 ns（逐 ns 闭合，已证）。**候选成立条件**：C1 是两样本共有的结构；样本间差异全部落在 filter 路径耗时（4.197 ms vs 34.469 ms）与入队→prepare 段（0.970 vs 0.2695 ms）——prepare→deliver 两样本接近（0.4–0.6 ms，批0 复验二口径）不变。旧样本 filter 路径内部 4.2 ms 的分解未做（方法同 Q6，留批15）。

### 7.4 C4：monitor 通道堆积——对本空档排除、对后续事件成立

机制：全局 monitor 的条目派发在窗口目标之后（:1687），每通道独立出队（`enqueueDispatchEntriesLocked` 的 wasEmpty→startDispatchCycle）；publish 遇 WOULD_BLOCK 只把条目留在该连接的 outboundQueue（:3361-3388），不阻塞其他连接也不回灌 mInboundQueue——monitor 侧堆积不可能让"主通道 prepare"晚出现。判据：Q1 显示空档终点（6,830,576,688）之前 tid 1488 无任何 prepare slice；iq 在空档内恒 0。iq 上升段（1→4，6,831,978,418/6,832,281,418/6,832,668,957/6,834,162,765）与排空（6,843,415,303→3、6,853,459,303→2、…、6,856,607,149→0，Q8）证明堆积发生在空档之后、由分发线程 R/R+ 窗口（6,831,991,034–6,838,155,188 等）拉长——这部分对后续事件的延迟贡献已证（计数侧），对每个事件的具体归因未证（批14/批15）。

### 7.5 C5/C6：policy 与转交调用的量化排除

C5：`interceptMotionBeforeQueueing` slice 5,962 ns，且机制上交互态不回调 Java（§4.3）——即使把整个 slice 算进空档也只占 0.017%。C6：`filterInputEvent` slice 109,961 ns（0.110 ms），占空档 0.3%；该 slice 的内部构成=MotionEvent 副本构造（JNI `android_view_MotionEvent_obtainAsCopy`，:1194-1195）＋Java 方法调用＋（mInputFilter 路径的）Handler post——全部在本 slice 内完成，slice 结束（6,796,261,149）后事件已离开分发主线，剩余 34,046,039 ns 与这两个调用无重叠（里程碑分段 S2–S9 的区间互斥，§4.9）。

### 7.6 C7：命令队列排除

`runCommandsLockedInterruptable`（:1214-1227）只在 dispatchOnce 的持锁段执行；命令的来源（doDispatchCycleFinishedCommand、onPointerDownOutsideFocus 等，`postCommandLocked` :1228-1231）全部产生于分发线程自己的处理过程——S 窗口（无 Running）内既无命令产生也无命令执行。判据：Q1 的 tid 1488 在 S 窗口内零 slice＋Q6 分发线程状态序列无 Running。命令作为"两圈之间占用"的候选被排除（已证）。

### 7.7 C8：pollOnce 超时节奏排除（终点侧）与起点侧盲区

终点侧：分发线程 S 终点（6,830,330,803）的 thread_state 带 waker=tid 756——Perfetto 的 waker 字段来自 sched_waking/sched_wakeup 事件（#17），超时到期返回不产生这类事件（无唤醒者）。故 S 终点由 wake 造成，不是 nextAnrCheck 定时到点（已证排除）。起点侧：S 起点 6,779,885,149 是上一轮处理的收尾睡点，其等待条件（epoll_wait 的哪类源）无 syscall/irq 记录可判——批00 §3.7 盲区沿用；批14 若能补采 ftrace 的 sys_enter/exit 或 irq 事件可升级。

### 7.8 C9：调度竞争窗口（未证→批14）

已证事实：主线程两个可运行窗口 R 9,313,231（6,796,227,034 起，waker=InputReader）与 R 17,289,307（6,812,790,111 起，无 waker 记录），加上 S3 段的 S 1,149,039 与 R/R+ 交替。未证事实：这两个 R 窗口内哪些线程在哪些 CPU 上运行、各自贡献多少等待。批00 §3.7 的 CPU3 竞争裁剪统计（getevent 1.717/ADB svc 1.096/slogmodem 1.087 ms 等）针对的是 InputReader 的 R 7.951 ms 窗口，**不能外推到主线程窗口**（契约 :185 纪律的同型条款）——批14 需对 6,796–6,830 s 的主线程可运行窗口重做同一方法论的裁剪统计。

### 7.9 C10：logd/其他子系统

分发线程与主线程在本空档（6,796–6,830 s）的 thread_state 序列均无 D 态（Q3/Q6）——不存在 write_pmsg 类阻塞路径参与。Reader 线程的 D 3,716,500 ns（write_pmsg，6,801,837–6,805,553，契约 :74）与主线程 R 9,313,231 窗口在时间上重叠，但两者无共享锁、无信号关系（分发侧与 Reader 的交汇点只有 mLock，§3.1），因果未证（契约口径维持）。"日志税"旧表述按 00 篇 §5 禁复活口径处理。

### 7.10 补采点

①filter 侧带事件 id 的打点（AccessibilityInputFilter onInputEvent 进入/回注 sendInputEvent）；②system_server 主线程消息队列深度或 Handler 消息名打点；③sched_waking/wakeup 对主线程 6,812,790,111 与 6,796,227,034 两个 R 起点的唤醒源（本 trace waker 缺失，Q6）；④`AccessibilityManagerService` 的 feature 位（哪些过滤特性启用）；⑤主线程窗口内 CPU 竞争裁剪统计（批14 方法论）。

## 8. 机制四问索引表

各机制的四问在正文对应节内作答，此处汇总供批15 引用：

| 机制 | 规则 | 谁设置 | 谁执行 | 后果 | 正文 |
|---|---|---|---|---|---|
| filter 消费-回注路径 | 转交即 return 不入队；回注保 id | AccessibilityManagerService（安装） | Reader 线程转交／主线程处理回注 | notify 与入队时刻分离 | §4.5/§4.7 |
| dispatchOnce 节奏 | 无命令分发一条→清命令→查 ANR→pollOnce | InputThread 构造＋policy 配置 | 分发线程 | 空队列即睡，非空不睡 | §3.2/§3.4 |
| 入队 needWake | 空队列入队才 wake | mInboundQueue 状态 | 调用方线程 | S 终点的 waker=入队线程 | §4.8 |
| DropReason 判定链 | POLICY/DISABLED→APP_SWITCH/STALE/BLOCKED 短路 | app switch 键/超时常量/shouldPrune | 分发线程 | 被丢事件广播 CANCEL、无 prepare slice | §5.1/§5.2 |
| 触摸命中三判据 | 可见∧可触摸∧区域内 | WMS 窗口列表＋InputConfig | findTouchedWindowAtLocked | DOWN 帧定主目标 | §6.3 |
| spy 窗收集 | 前往后扫，遇非 spy 命中即停 | GestureMonitorSpyWindow（SPY 位） | findTouchedSpyWindowsAtLocked | targets 前段顺序 | §6.3/§6.5 |
| 全局 monitor 追加 | 每帧复制给 responsive monitor | createInputMonitor 注册 | addGlobalMonitoringTargetsLocked | targets 尾段顺序 | §6.5/§6.6 |
| FocusResolver 焦点 | 窗口推送时重算、请求留存 | FocusRequest（WMS 侧） | FocusResolver | 按键目标与焦点事件依据 | §6.5 |
| ANR 计时起点 | 发布时登记 mAnrTracker；无焦点=挂起即起表 | getDispatchingTimeout | startDispatchCycleLocked／findFocusedWindowTargetsLocked | 超时进 onAnrLocked（批07） | §4.7/§6.1 |

## 9. 与两个问题的关联

**34.738 ms notifyMotion→prepare（`00:00:06.795839`→`00:00:06.830577`）**：本篇把空档整体归位到 filter 消费-回注路径（§7 C1 已证），并给出九段分解（§4.9，逐 ns 闭合）。空档前段 422,500 ns 是 Reader 线程的转交开销；主体 33.8 ms 是 system_server 主线程从被 post 唤醒到完成回注的时间，其中可运行未运行窗口合计 26.6 ms（9.313＋17.289），竞争归因未证（批14）。旧样本同构（Q7），差异全部落在该路径的内部耗时。**批15 引用本篇时**：空档的"机制归属"已证（不是队列、不是 waitQueue、不是 monitor 堆积），"竞争归因"与"filter 内部语义"未证/盲区。

**50.928 ms Launcher 主线程 Sleeping（`00:00:06.779837–00:00:06.830765`）**：分发侧对该问题的贡献点只有一个——`startDispatchCycleLocked` 写 InputChannel socket 唤醒应用（waker=tid 1488 直证，批0 复验二；机制位置 `InputDispatcher.cpp:3232-3402`，详见批07）。本篇排除两个分发侧候选：①dispatcher 自己"卡住"——其 S 50.446 ms 是 pollOnce 等待（§3.6），且醒后 1.009 ms 内完成三通道全序列；②主线程 S 期间分发侧无事件可送——事件在 S 尾段才入队（iq 0→1 于 6,830,307,188）。主线程 S 的等待条件本身保持批00 §3.7 盲区口径，批14 收口。

**Dispatcher 侧 S 50.446 ms 与 Launcher S 50.928 ms 的关系**：两窗重叠 50.445 ms 但语义独立——前者是分发线程 pollOnce 等待，后者是应用主线程等待；两者在 6.8303–6.8308 之间以"wake 链"（tid756→tid1488→tid1909）首尾相接（批0 复验二 + Q3/Q6），间隔账：入队 6,830,307,188 → Launcher R 6,830,764,842 共 457,654 ns，内含分发线程调度延迟（35,308）＋ dispatchMotionLocked 全序列＋发布写 socket＋应用侧调度延迟——逐跳数值见 §4.9/§6.7，应用侧段位归批08。

## 10. 未证边界与盲区清单

1. **未证**：主线程 R 9,313,231 ns 与 R 17,289,307 ns 窗口的 CPU 竞争归因（谁在占 CPU、是否与 34.738 ms 构成因果）——批14 的调度窗口分析对象；本篇只证"窗口存在"。
2. **盲区**：InputFilter（AccessibilityInputFilter）在空档内对事件做了什么——onInputEvent 分支取决于启用特性位（`AccessibilityInputFilter.java:234-347` 的 mEnabledFeatures 分支），设备配置未采集；该段无任何 trace slice。
3. **盲区**：S3 段（6,805,540,265→6,812,790,111）主线程是否已处理该事件消息、回注为何未在该段发生——主线程该段无 slice，消息语义不可判。
4. **未证**：C8 起点侧——分发线程进入 S（6,779,885）的唤醒源与上一事件的尾段处理节奏关系（上一事件 0x107fbc97 的 deliver 在 6,772,756,188，批0 复验 §2.2；两者之间队列动态只有 iq 单点 0 可用）。
5. **未证**：旧样本 filter 路径 4,196,847 ns 的内部分解（主线程状态序列查询未做，方法同 Q6，留批15）。
6. **盲区（全局沿用）**：基准源码树与设备 build 的一致性未验证（批00 §3.6）；本文机制结论适用于 A13 源码，套用到设备行为须带此前提。
7. **盲区**：本 trace 无 syscall/irq/poll 事件（批00 §3.7），S/pollOnce 的具体等待条件不可判；`oq:`/`wq:` 计数轨（`traceOutboundQueueLength`/`traceWaitQueueLength`，:6163-6177）在本样本可见性未查证（批07 查）。
8. **勘误记录**：契约 :124 的"IdleTimeout"在 A13 findTouchedWindowTargetsLocked 无对应机制（§6.3 勘误）；`isAppStolen`/`hasDefaultDisplayEventTarget`/`onAnrDelayStarted` 为旧版函数名，A13 对应物见 §4.7；批00 §3.7"input 队列 counter 无记录"对 `iq` 轨不成立（§7 C2 修正，新证据 Q4）。
9. **未证**：三条同 ID notifyMotion 打点条间间隔（255,308/16,269 ns）的内部归因——三队列 flush 链的执行结构已证（§4.9），间隔差异未证（批04 D21 同口径延续）。
10. **未证**：`PointerEventDispatcher2` 通道名中 displayId=2 的显示拓扑归属（多屏实例中哪一个，名称构成已证、拓扑未判）；`[Gesture Monitor] CustomGesture-touch` 的请求方（进程）在基准源码树内无调用点（`monitorGestureInput` 的 Java 公开入口在 `InputManager.java:1266-1268`，请求方为本设备厂商组件——盲区）。
11. **留待批07**：`prepareDispatchCycleLocked` 之后的发布-回执状态机（Connection/outboundQueue/waitQueue/AnrTracker/LatencyAggregator）、`splitMotionEvent` 拆分、`oq:`/`wq:` counter 可见性、monitor 通道堆积（iq 1→4 段）的分发侧对应、`injectInputEvent` 同步模式的等待细节。

## 11. 断言与三态汇总

本篇机制性结论的编号与三态（断言-证据对照的完整表在《过程记录/批06-过程记录.md》，编号一致）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| E1 | 分发线程由 InputThread 创建，循环体 dispatchOnce，退出钩子 wake | 已证 | InputDispatcher.cpp:584-591；Q1 tid1488 |
| E2 | dispatchOnce 四段节奏与 pollOnce 超时来源 | 已证 | :602-636；InputDispatcher.h:199-200 |
| E3 | 队列空→睡、非空→不睡（除 mDispatchFrozen） | 已证 | :744-781/:729-734 |
| E4 | Dispatcher S 50,446 ms 窗口内零 slice、零分发活动 | 已证 | Q1（6,779,729,726→6,830,475,418 无 slice） |
| E5 | notifyMotion 两道锁外预处理（intercept＋filter），交互态 policy 在 JNI 短路 | 已证 | :4015-4024；JNI :1254-1286；Q2 |
| E6 | filter 装载时事件转交即 return、不入队；未装载才直入队 | 已证 | :4029-4052；IMS.java:3014-3027 |
| E7 | mInputFilterEnabled 由 setInputFilter 同步置位，安装方为 AccessibilityManagerService | 已证 | IMS.java:815-845；AMS:2349-2356；WMS:7729-7730 |
| E8 | 转交对象是 Handler post（主线程 looper），转交与处理跨线程 | 已证 | InputFilter.java:152-154/:123-124；A11F:191 |
| E9 | 回注经 InputFilterHost.sendInputEvent→injectInputEvent，MODE_ASYNC、id 保留、FILTERED 位保留 device id | 已证 | IMS.java:3515-3530；:4194-4197/:4282/:4335-4345 |
| E10 | enqueueInboundEventLocked 尾插＋needWake=入队前空＋MOTION 削队预判 | 已证 | :978-1049/:921-976 |
| E11 | 同 ID 三条 notifyMotion＝三队列 traceEvent 打点，间隔 255,308/16,269 ns | 已证（代码＋trace） | InputListener.cpp:340-364；Q2 |
| E12 | iq 计数轨存在且有样本；空档内深度恒 0，入队点 6,830,307,188 | 已证 | :6157-6161/:982/:774；Q4/Q8 |
| E13 | 空档机制=filter 消费-回注路径；九段分解逐 ns 闭合 34,738,039 | 已证 | Q2/Q3/Q4/Q6 联合；§4.9 求和 |
| E14 | 主线程 R 两窗（9,313,231＋17,289,307）存在；竞争归因未证 | 已证（窗口）＋未证（归因） | Q6；批14 |
| E15 | 入队→分发线程 R→Running→dispatchMotionLocked 的 wake 链，waker tid756 | 已证 | Q3/Q6；:4343-4345 |
| E16 | DropReason 判定链先于目标裁决；被丢事件无 prepare slice | 已证 | :786-899/:1645-1650/:1110-1181 |
| E17 | 触摸裁决无 PENDING 出口；PENDING 仅焦点路径 | 已证 | :1669-1671/:2002-2416 |
| E18 | 命中三判据＋Z 序首中即止＋spy 收集停于非 spy | 已证 | :462-477/:1061-1088/:1090-1108 |
| E19 | targets 顺序=主窗→spy→全局 monitor；三通道 prepare 顺序=vector 遍历序 | 已证 | :2118-2123/:2418-2421/:1687/:1767-1779；Q1 |
| E20 | [Gesture Monitor] 为 spy 窗（窗口列表）、PointerEventDispatcher2 为全局 monitor（mGlobalMonitorsByDisplay） | 已证 | GestureMonitorSpyWindow.java:65-66；IMS.java:772/:714-739/:703-711；:5529；DisplayContent.java:1113-1115 |
| E21 | FocusResolver 在窗口推送时重算焦点，触摸路径不查焦点 | 已证 | FocusResolver.cpp:57-90；:4660-4672 |
| E22 | A13 无 isAppStolen/hasDefaultDisplayEventTarget/onAnrDelayStarted/IdleTimeout（作业单旧名勘误）；ANR 计时起点=mAnrTracker 登记（发布时）与无焦点挂起时 | 已证 | :3396-3399/:1898-1910；树内 grep |
| E23 | 旧样本同走 filter 回注路径：notify→入队 4,196,847＋入队→prepare 970,461＝5,167,308 | 已证 | Q7；批0 复验二 |
| E24 | C2/C3/C5/C6/C7/C8（终点侧）/C10（本空档两线程）排除；C4 对本空档排除、对后续事件存在 | 已证（排除判据见表） | §7 各行 |
| E25 | startDispatchCycle 写 socket 唤醒应用（waker tid1488→tid1909） | 已证（waker）＋机制详述见批07 | :3232-3402；批0 复验二 |
| E26 | iq 上升段（1→4）与排空（→0 于 6,856,607）为空档之后的事件堆积 | 已证（计数）＋归因未证 | Q4/Q8 |
| E27 | notifyKey 与 notifyMotion 同构（policy＋filter＋入队＋wake），KEY 的 policy 回调不分状态 | 已证 | :3904-3976；JNI :1216-1252 |
| E28 | 六 dispatch 模式循环每通道六条空转小 slice，仅 AS_IS 产生条目 | 已证 | :2970-2981；Q1 |
| E29 | MotionEntry 深拷贝 pointer 数组，构造即与 args 脱钩 | 已证 | Entry.cpp:210-239 |
| E30 | 注入事件合批逐样本拆条目入队 | 已证 | :4297-4318 |

## 12. 批注审计与补缺摘要

原写作及批注验收记录见[过程性段落存档](过程记录/整体优化-R1-过程性段落存档.md)。
