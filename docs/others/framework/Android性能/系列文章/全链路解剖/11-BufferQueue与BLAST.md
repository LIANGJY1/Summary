# 11 · BufferQueue 与 BLAST：槽号账本与事务提交

BufferQueue 管理缓冲的获取、提交、消费与归还，BLAST 将应用缓冲装入 Transaction 交给 SurfaceFlinger。

## 0. 三态结论摘要

1. 已证：Android 13 基准源码树的 BLASTSyncEngine 实体在 `frameworks/base/services/core/java/com/android/server/wm/BLASTSyncEngine.java`（WMS 侧，397 行），native 侧无同名源文件（全树 grep 仅 winscope 文档与构建产物命中）——批11 作业单"如存在"的判定为"存在，但在 WMS 侧"。
2. 已证：A13 普通窗口默认走 BLAST 路径（WMS mUseBLAST 缺省 1 → ViewRootImpl 建 BLASTBufferQueue 并把 BBQ 生产端 Surface 交给 ThreadedRenderer）。
3. 已证：本样本 trace 里应用帧提交段可见——不是盲区。RenderThread 上 dequeueBuffer 1016 条、queueBuffer 1014 条、acquireBuffer 507 条、onFrameAvailable 507 条、acquireNextBufferLocked 507 条，四条 binder 线程上 releaseBufferCallback/releaseBuffer/transactionCallback 各 507 条，另有 4 条 BLAST 计数轨。契约批11 作业单预置的"若无则盲区声明"不成立（留痕：过程记录 §三，SQL 与输出已存档）。
4. 已证：BLAST 在途缓冲数在本样本于 1~3 间摆动（onFrameAvailable/releaseBufferCallback slice 名内 (f:0,a:N) 直读），帧 395 从提交到归还回执在途 98.202 ms（端点算术）。
5. 已证：`aq:pending:...AppListActivity` 计数轨不是 buffer 计数，是 ViewRootImpl 待处理输入事件队列长度计数（ViewRootImpl.java:1419）——批08 留的"aq:* counter 未证项"在此定性。
6. 已证：dequeueBuffer 的"超额"走报错（INVALID_OPERATION）而非等待；真正的睡等只发生在无槽/队列积压分支的 `mDequeueCondition.wait`。
7. 未证：本设备 BLASTBufferQueue 构造时从 SF 查回的 mMaxAcquiredBuffers 具体数值（公式已证，实值未采 dumpsys）。
8. 盲区：SF 进程内 setTransactionState 之后的入队/放行/latch 在本 trace 的对应 slice 细节，本篇只给入口行号（批12 划界）。
9. 前提声明：设备 build 与基准源码树的逐文件一致性未验证（00 篇 §3.6；契约 :140）——本篇机制结论是 Android 13 基准源码树代码的机制，套用到设备行为时以本样本 trace 实测为交叉证据；两者不符之处以 trace 为准并记录。本篇 trace 实测与基准源码树机制逐段相符（§8 全表）。

## 1. 主线闭环：一帧缓冲的全链

从批09/10 交接处起：UI 线程 doFrame 里 Record 出 display list 后 postAndWait 交给 RenderThread（RT），RT 接手后的 dequeue/queue 调用点属批10；本篇从"RT 调用 dequeueBuffer 之后"接起，到"SF 收到 setTransactionState"止。全链（BLAST 路径，A13 默认）：

```text
[UI 线程 tid 1909]                    [RT tid 2079]                    [本进程 libs/gui]                 [SF 进程]
Choreographer#doFrame ──▶ Record View#draw() ──▶ postAndWait(批09/10)──▶ DrawFrameTask::run(批10)
                                          │ CanvasContext::draw(批10)
                                          │   └─ getFrame → Surface::dequeueBuffer
                                          │        └─ BufferQueueProducer::dequeueBuffer   ← 机制① 状态机
                                          │   └─ SkiaPipeline::renderFrame(批10)
                                          │   └─ swapBuffers → Surface::queueBuffer
                                          │        └─ BufferQueueProducer::queueBuffer      ← 机制① 状态机
                                          │             └─ onFrameAvailable（同线程直调）
                                          │                  └─ BLASTBufferQueue::onFrameAvailable
                                          │                       └─ acquireNextBufferLocked   ← 机制②
                                          │                            ├─ BufferQueueConsumer::acquireBuffer
                                          │                            └─ Transaction::apply
                                          │                                 └─ setTransactionState ──▶ SF:4138  ← 机制③
                                          │                                                                    （批12 接手）
[四条 binder 线程] ◀══ releaseBufferCallback / transactionCallback（SF 用完归还，fence 回程）═╝   ← 机制⑤fence
```

三个事实先立住（全部有本样本 trace 直证，§8 配对表）：

- queueBuffer、onFrameAvailable、acquireNextBufferLocked 在 trace 里是嵌套 slice，全在 tid 2079 一条线程轨上——应用侧提交段零线程切换（J22）。
- 每帧两层同名 slice：外层 `Surface::dequeueBuffer`（Surface.cpp:637 ATRACE_CALL）套内层 `BufferQueueProducer::dequeueBuffer`（BufferQueueProducer.cpp:383 ATRACE_CALL）；queueBuffer 同理（Surface.cpp:1164 / BufferQueueProducer.cpp:809）——所以 508 帧对应 1016 条 dequeueBuffer、1014 条 queueBuffer（J20）。
- 帧提交段与批09 的 doFrame 155812（6,842,074,918，dur 564,193）逐段衔接：postAndWait 6,842,268,957（353,769 ns，与批09 q2 逐字一致）结束后 RT 才真正进入 dequeue/绘制（J23）。

### 1.1 机制四问总览（本篇覆盖五个机制）

| 机制 | 规则是什么 | 谁设置 | 谁执行 | 后果 |
|---|---|---|---|---|
| ① BufferQueue 状态机 | 槽位在 FREE/DEQUEUED/QUEUED/ACQUIRED 间按计数向量迁移；dequeue 超额报错、无槽睡等 | BufferQueueCore 构造与 set* 调用方（缺省 acquired 1/dequeued 1/总槽 64；BLAST 改 dequeued=2） | BufferQueueProducer/Consumer 在 mMutex 下执行，release 侧 notify_all | 背压第一闸是报错、第二闸是睡等；槽位循环由消费端归还驱动 |
| ② BLAST 提交 | 应用进程内持整条队列，帧完成后组装 Transaction 直投 SF | WMS mUseBLAST（缺省 1）+ ViewRootImpl | BLASTBufferQueue（onFrameAvailable→acquire→apply） | 提交原子化；应用侧 0 次线程切换 + 1 次 one-way binder |
| ③ setTransactionState→SF | 事务打包（含 buffer 缓存协议）经 binder 进 SF 入口 | SurfaceComposerClient::Transaction::apply 调用方 | Bp 侧打包→SF:4138 | SF 收文即本篇边界；flush/latch 见批12 |
| ④ 三缓冲与丢帧 | 在途缓冲上限 = dequeued(2) + acquired(公式值) | BLASTBufferQueue 构造三件套 | maxBuffersAcquired 闸门 + mDequeueCondition | 在途满时 acquire 停、等归还；本样本未发生 dequeue 长等待 |
| ⑤ fence 传递 | queueBuffer 带 acquire fence → SF；SF 归还带 release fence → 下次 dequeue | 生产端/合成端各自生成 | Fence 信号链 + FenceMonitor 监视线程 | 未信号时 SF latch 跳层；本样本 dequeue 取回的 fence 均已信号 |

BLASTSyncEngine（WMS 侧，§7）单独成节：它不在每帧主线上，只在 WMS 同步场景收拢事务，合并语义作为机制⑤问答的扩展。

## 2. 机制① BufferQueue 状态机

BufferQueue 是生产者与消费者之间交换图像缓冲的机制。生产者获取可用缓冲、写入画面后提交；消费者取得缓冲使用，完成后归还。队列和槽位用于协调缓冲归属与复用。

### 2.1 三件套装配

队列的唯一装配入口是静态工厂，Core 与两个门面同生共死在调用方进程，本体没有 Binder 接口，跨进程靠把门面代理传出去。

BufferQueue.cpp:106-128（基准源码树，全文件 158 行）：

```cpp
void BufferQueue::createBufferQueue(sp<IGraphicBufferProducer>* outProducer,
        sp<IGraphicBufferConsumer>* outConsumer,
        bool consumerIsSurfaceFlinger) {
    LOG_ALWAYS_FATAL_IF(outProducer == nullptr,
            "BufferQueue: outProducer must not be NULL");
    LOG_ALWAYS_FATAL_IF(outConsumer == nullptr,
            "BufferQueue: outConsumer must not be NULL");

    sp<BufferQueueCore> core(new BufferQueueCore());
    LOG_ALWAYS_FATAL_IF(core == nullptr,
            "BufferQueue: failed to create BufferQueueCore");

    sp<IGraphicBufferProducer> producer(new BufferQueueProducer(core, consumerIsSurfaceFlinger));
```

- `consumerIsSurfaceFlinger=true` 的分支只有 SF 自己的 BufferQueueLayer 用（legacy 路径）；BLASTBufferQueue 用自己的覆盖版 `BLASTBufferQueue::createBufferQueue`（BLASTBufferQueue.cpp:1068-1087，:1076 换成 BBQBufferQueueProducer、:1081 给 consumer 开 `setAllowExtraAcquire(true)`）。
- BLASTBufferQueue.cpp:143 调的就是本工厂的另一份等价实现——两个 createBufferQueue 函数体一致，差别只在 Producer 子类。
- 三个使用方已证：ViewRootImpl（窗口主路径，§3.1）、SurfaceControl::generateSurfaceLocked（SurfaceControl.cpp:136-150，:141 建 "bbq-wrapper" 子层、:143 建 BLASTBufferQueue("bbq-adapter")；getSurface :152-159 懒初始化时调用）、SurfaceView（SurfaceView.java:1143 copyFrom(mBlastBufferQueue)）。

源码学习副本的批注位置：BufferQueue.cpp:33-39/:113-126/:135-140（块首行号，下同）——总览块与工厂块已审，机制一致（过程记录 §五 A 组）。

### 2.2 状态的表达：计数向量而非枚举

BufferSlot.h:43-46（字段）与 :48-57（状态表，基准源码树原文）：

```cpp
    uint32_t mDequeueCount;
    uint32_t mQueueCount;
    uint32_t mAcquireCount;
    bool mShared;

    // A buffer can be in one of five states, represented as below:
    //
    //         | mShared | mDequeueCount | mQueueCount | mAcquireCount |
    // --------|---------|---------------|-------------|---------------|
    // FREE    |  false  |       0       |      0      |       0       |
    // DEQUEUED|  false  |       1       |      0      |       0       |
    // QUEUED  |  false  |       0       |      1      |       0       |
    // ACQUIRED|  false  |       0       |      0      |       1       |
```

判定函数与转移函数一一对应（BufferSlot.h:87-105 与 :113-171）：`dequeue()` 加 mDequeueCount（:113-115）、`queue()` 减 dequeue 加 queue（:127-132）、`cancel()` 只减 dequeue（:134-138）、`acquire()` 减 queue 加 acquire（:146-151）、`release()` 减 acquire（:157-161）。计数为 0 即 FREE（isFree :87-89）。共享缓冲模式（mShared=true）允许同槽多态并存，枚举表达不了，计数向量可以——这就是用三个计数器而不是一个枚举的全部原因（J1）。

BufferSlot 本体（BufferSlot.h:174-196）每槽存 mGraphicBuffer、mBufferState、mFrameNumber、mFence、mAcquireCalled、mNeedsReallocation 等运行字段（构造初始化 :176-186）；mFence 永远是"上一任持有者留给下一任的完工承诺"（:62-67 注释原文："The producer may modify the buffer's contents as soon as the associated release fence is signaled"）。

### 2.3 槽集合与 mQueue：可用性账本

BufferQueueCore.h:200-216（基准源码树）：

```cpp
    // mQueue is a FIFO of queued buffers used in synchronous mode.
    Fifo mQueue;
    // mFreeSlots contains all of the slots which are FREE and do not currently
    // have a buffer attached.
    std::set<int> mFreeSlots;
    // mFreeBuffers contains all of the slots which are FREE and currently have
    // a buffer attached.
    std::list<int> mFreeBuffers;
    // mUnusedSlots contains all slots that are currently unused. They should be
    // free and not have a buffer attached.
    std::list<int> mUnusedSlots;
    // mActiveBuffers contains all slots which have a non-FREE buffer attached.
    std::set<int> mActiveBuffers;
```

- mQueue 是 QUEUED 缓冲的 FIFO（BufferItem 序列）；mFreeSlots 是 FREE 且无缓冲的槽（dequeue 到它要新建 GraphicBuffer）；mFreeBuffers 是 FREE 且缓冲还在的槽（dequeue 直接复用）；mUnusedSlots 是容量收缩后暂不调度的槽；mActiveBuffers 是 DEQUEUED/QUEUED/ACQUIRED 的在用槽。
- 构造时把前 N 个槽放入 mFreeSlots、其余放 mUnusedSlots（BufferQueueCore.cpp:134-141）；N 来自 getMaxBufferCountLocked（:238-254：mMaxAcquiredBufferCount + mMaxDequeuedBufferCount，异步/不可阻塞形态再加 1，与 mMaxBufferCount 取 min）。
- 缺省值在构造初始化表（BufferQueueCore.cpp:113-115）：mMaxBufferCount = BufferQueueDefs::NUM_BUFFER_SLOTS，即 64（libs/ui/include/ui/BufferQueueDefs.h:25 `static constexpr int NUM_BUFFER_SLOTS = 64;`；gui 侧 BufferQueueDefs.h:27 的 SlotsType 数组类型引用它）、mMaxAcquiredBufferCount(1)、mMaxDequeuedBufferCount(1)（J2）。BLAST 构造随后把 dequeued 覆盖为 2（§3.2）。

四个转移操作的执行点（机制①"谁执行"）：

| 操作 | 执行函数（基准源码树） | 状态迁移 | 集合变更 |
|---|---|---|---|
| dequeueBuffer | BufferQueueProducer.cpp:491 `mSlots[found].mBufferState.dequeue();` | FREE→DEQUEUED | mActiveBuffers.insert :483 |
| queueBuffer | BufferQueueProducer.cpp:908 `mSlots[slot].mBufferState.queue();` | DEQUEUED→QUEUED | BufferItem 入 mQueue :956/:996 |
| acquireBuffer | BufferQueueConsumer.cpp:281 `mSlots[slot].mBufferState.acquire();` | QUEUED→ACQUIRED | mQueue.erase(front) :293 |
| releaseBuffer | BufferQueueConsumer.cpp:479 `mSlots[slot].mBufferState.release();` | ACQUIRED→FREE | mFreeBuffers.push_back :490 |

cancelBuffer（Producer.cpp:1113 `mBufferState.cancel()`）与丢弃路径（queueBuffer 挤代 :966 `freeQueued()`、acquireBuffer 积压丢帧 :180）是旁支，语义同表内函数。

### 2.4 dequeue 的阻塞条件（机制①问答）

规则：dequeue 的"超额"与"无槽"是两条不同纪律。

第一条（报错不等待）：在手（已 dequeue 未 queue/cancel）缓冲数达到 mMaxDequeuedBufferCount 后，再取直接返回 INVALID_OPERATION——BufferQueueProducer.cpp:295-307（关键行）：

```cpp
        // Producers are not allowed to dequeue more than
        // mMaxDequeuedBufferCount buffers.
        // This check is only done if a buffer has already been queued
        if (mCore->mBufferHasBeenQueued &&
                dequeuedCount >= mCore->mMaxDequeuedBufferCount) {
            // Supress error logs when timeout is non-negative.
            if (mDequeueTimeout < 0) {
                BQ_LOGE("%s: attempting to exceed the max dequeued buffer "
                        "count (%d)", callerString,
                        mCore->mMaxDequeuedBufferCount);
            }
            return INVALID_OPERATION;
        }
```

注意 mBufferHasBeenQueued 前置（:298）：队列尚未收到过第一帧时不受此限（首帧允许一次多取）。BLAST 设上限 2（§3.2），即 RT 同时至多攥 2 个 DEQUEUED 槽，第三帧必须先交卷（J3）。

第二条（睡等）：无槽可给、或队列积压超过 maxBufferCount 时进入等待分支——BufferQueueProducer.cpp:351-373（waitForFreeSlotThenRelock 内）：

```cpp
        tryAgain = (*found == BufferQueueCore::INVALID_BUFFER_SLOT) ||
                   tooManyBuffers;
        if (tryAgain) {
            ...
            if ((mCore->mDequeueBufferCannotBlock || mCore->mAsyncMode) &&
                    (acquiredCount <= mCore->mMaxAcquiredBufferCount)) {
                return WOULD_BLOCK;
            }
            if (mDequeueTimeout >= 0) {
                std::cv_status result = mCore->mDequeueCondition.wait_for(lock,
                        std::chrono::nanoseconds(mDequeueTimeout));
                if (result == std::cv_status::timeout) {
                    return TIMED_OUT;
                }
            } else {
                mCore->mDequeueCondition.wait(lock);
            }
        }
```

四问齐答：

- 谁在等：queueBuffer 的调用线程。BLAST 路径下即应用 RenderThread（tid 2079，trace 实证见 J20）；它睡在这把条件变量上时，本进程所有后续帧的提交都停在同一处。
- 等什么：消费端把 ACQUIRED 槽归还（releaseBuffer→mFreeBuffers）或队列深度下降（acquireBuffer erase/queueBuffer 可丢帧挤代）。两个 notify_all 点：BufferQueueConsumer.cpp:298（acquire 后）、:498（release 后）；queueBuffer 侧 :1002 也有一次。
- 期望显示时间裁决不在此处：`tooManyBuffers` 判据是 mQueue.size() > maxBufferCount（:315-317），与 fence 无关；fence 的等待在 dequeue 返回后由 EGL/Skia 侧做（§6）。
- 违反的后果：非阻塞形态返回 WOULD_BLOCK、设了有限超时返回 TIMED_OUT，调用方丢帧；BLAST 用 INT64_MAX 超时把语义钉死为"队满就睡、绝不失败"（BLASTBufferQueue.cpp:146 + connect 旗标 :1287-1290，源码学习副本的批注 BLASTBufferQueue.cpp:170-172 已审）。

等待前的选槽优先级：先 mFreeBuffers（缓冲还在免分配）耗尽才动 mFreeSlots（要新建）——BufferQueueProducer.cpp:328-335（getFreeBufferLocked :255-262 / getFreeSlotLocked :264-271）；attachBuffer 相反优先空槽（:336-344）。锁外分配三段式见 :546-585（mIsAllocating 协议 :503/:566-567）。

### 2.5 queueBuffer 的交卷协议

BufferQueueProducer.cpp:907-914（关键段）：

```cpp
        mSlots[slot].mFence = acquireFence;
        mSlots[slot].mBufferState.queue();

        // Increment the frame counter and store a local version of it
        // for use outside the lock on mCore->mMutex.
        ++mCore->mFrameCounter;
        currentFrameNumber = mCore->mFrameCounter;
        mSlots[slot].mFrameNumber = currentFrameNumber;
```

三个要点（J4）：

1. acquire fence 进槽（:907）并随 BufferItem 出货（:930 `item.mFence = acquireFence;`）——这是机制⑤fence 传递链的第一站。
2. mFrameCounter 发全队列单调帧号（:912-914）——trace 里 `acquireNextBufferLocked ... frame=396` 的 396 就是它，与 Choreographer 的 vsyncId（155812）是两套编号（§8.3）。
3. 队尾可丢帧挤代（:961-998）：`item.mIsDroppable` 四来源（:932-935，mAsyncMode / SF 消费端 / legacy 放开 / 共享槽）；被挤帧的 damage 区并入新帧（:985-990），回调走 onFrameReplaced（:994）。BLAST 队列的消费者不是 SF 进程（mConsumerIsSurfaceFlinger=false），四来源里前两条不成立，BLAST 帧在 mQueue 里不会互相挤代——积压时走 acquireBuffer 的丢帧循环（Consumer.cpp:123-204）。

回调保序（票据）：锁内领票 :1017（`callbackTicket = mNextCallbackTicket++;`），锁外按票兑现 :1046-1067——onFrameAvailable 在这段被调用。这就是 §3.3"同线程直调"的代码位置。

### 2.6 acquireBuffer 的消费侧细节：积压丢帧与省流协议

Consumer.cpp:78-315 是 acquireBuffer 全函数。除 §2.3 表的容量闸外，两段对卡顿语义重要（J28）：

积压丢帧循环（Consumer.cpp:123-204，关键行）：

```cpp
            while (mCore->mQueue.size() > 1 && !mCore->mQueue[0].mIsAutoTimestamp) {
                const BufferItem& bufferItem(mCore->mQueue[1]);

                // If dropping entry[0] would leave us with a buffer that the
                // consumer is not yet ready for, don't drop it.
                if (maxFrameNumber && bufferItem.mFrameNumber > maxFrameNumber) {
                    break;
                }
                ...
                nsecs_t desiredPresent = bufferItem.mTimestamp;
                if (desiredPresent < expectedPresent - MAX_REASONABLE_NSEC ||
                        desiredPresent > expectedPresent) {
                    ...
                    break;
                }
```

丢的是队头（较旧帧），条件是队里积压多帧、队头时间戳非自动生成、下一帧已到点且落在 expectedPresent 的 ±1 秒窗内（MAX_REASONABLE_NSEC 护栏 :160-172 防"垃圾时间戳"导致疯狂丢帧，源码学习副本的批注 Consumer.cpp:172-174 已审）。被丢帧的槽当场 freeQueued 回 mFreeBuffers（:178-194）并记入 onBufferReleased 监听（:196-198，实际回调在锁外 :308-311）——积压丢帧发生在消费侧入队时刻，不在合成时。

到点裁决（Consumer.cpp:206-222）：队头帧的期望显示时间未到（desiredPresent > expectedPresent 且差值 ≤ 1 秒）或消费端未就绪（mFrameNumber > maxFrameNumber，后者是 SF 消费端 latch 传参的形态，批12）返回 PRESENT_LATER 并打同名 ATRACE_NAME（:220）。注意这两段裁决的入口条件是 `expectedPresent != 0`（:123）——BLASTBufferQueue 调 acquireBuffer 时 expectedPresent 传 0（BLASTBufferQueue.cpp:510），整段跳过：BLAST 队列不在队列层做显示时间丢帧，帧序由 Choreographer 与 SF 的 barrier 保证。

Binder 省流协议（Consumer.cpp:286-291）：

```cpp
        // If the buffer has previously been acquired by the consumer, set
        // mGraphicBuffer to NULL to avoid unnecessarily remapping this buffer
        // on the consumer side
        if (outBuffer->mAcquireCalled) {
            outBuffer->mGraphicBuffer = nullptr;
        }
```

同一槽第二次被 acquire 时 BufferItem 不再携带 GraphicBuffer 指针（消费端镜像槽表已缓存）；例外是 attachBuffer 把 mAcquireCalled 置回 false（:435，StreamSplitter 类场景，源码学习副本的批注 Consumer.cpp:305-310 已审）。

### 2.7 旁支操作与断连语义

- cancelBuffer（Producer.cpp:1080-1136）：DEQUEUED 直接回 FREE（`mBufferState.cancel()` :1113），槽回 mFreeBuffers（:1122-1125），notify_all（:1132）。调用方：Skia/EGL 在绘制失败或宽高不符时退槽（批10 域）。
- detachBuffer/detachNextBuffer（:619-712）/attachBuffer（:738-805）：槽与缓冲解耦的跨队列转移用途，BLAST 主链不用。
- 断连不对称（Consumer.cpp:535-554）：消费者 disconnect 直接 `mCore->mIsAbandoned = true;`（:547）并清队列与全部槽——此后生产端一切调用返回 NO_INIT；生产者 disconnect（Producer.cpp:1297 起）只拆自己的连接与监听，队列仍可服务新生产者（源码学习副本的批注 Consumer.cpp:562-566 已审）。排查"应用绘制突然 NO_INIT"，先查消费端是否先销毁（J29）。
- requestBuffer（Producer.cpp:90-118）：dequeueBuffer 返回 BUFFER_NEEDS_REALLOCATION 标志后，客户端凭槽号调它取回 GraphicBuffer 指针（:116 `*buf = mSlots[slot].mGraphicBuffer;`）——缓冲本体过进程边界的唯一一次（BLAST 队列同进程无此开销，Surface.cpp:708-714 客户端侧对应段）。

## 3. 机制② BLAST 路径与 A13 默认

BLASTBufferQueue 是 Android 图形栈中的缓冲与事务衔接组件。它接收应用提交的缓冲，并将缓冲及相关状态放入 SurfaceControl Transaction，供 SurfaceFlinger 按事务处理。

### 3.1 谁决定用 BLAST：三段开关链

第一段（WMS 侧总开关，缺省开）：WindowManagerService.java:1239-1240：

```java
        mUseBLAST = Settings.Global.getInt(resolver,
            Settings.Global.DEVELOPMENT_USE_BLAST_ADAPTER_VR, 1) == 1;
```

getInt 第三参 1 是缺省值——不设 Settings 即启用（J5）。第二段（加窗回执）：WindowManagerService.java:1752-1754 `if (mUseBLAST) { res |= WindowManagerGlobal.ADD_FLAG_USE_BLAST; }`。第三段（应用侧）：ViewRootImpl.java:1368-1370：

```java
                if ((res & WindowManagerGlobal.ADD_FLAG_USE_BLAST) != 0) {
                    mUseBLASTAdapter = true;
                }
```

relayout 时的分岔（ViewRootImpl.java:8317-8326）：

```java
        if (mSurfaceControl.isValid()) {
            if (!useBLAST()) {
                mSurface.copyFrom(mSurfaceControl);
            } else {
                updateBlastSurfaceIfNeeded();
            }
            if (mAttachInfo.mThreadedRenderer != null) {
                mAttachInfo.mThreadedRenderer.setSurfaceControl(mSurfaceControl);
                mAttachInfo.mThreadedRenderer.setBlastBufferQueue(mBlastBufferQueue);
            }
```

useBLAST() = mUseBLASTAdapter && !mForceDisableBLAST（ViewRootImpl.java:10947-10948）。BLAST 命中时 updateBlastSurfaceIfNeeded（:2176-2200）建/更 BLASTBufferQueue 并把 BBQ 生产端 Surface 换进 mSurface（:2196-2199 `mSurface.transferFrom(blastSurface)`），再把 mBlastBufferQueue 交给 ThreadedRenderer——RT 侧从此经 BBQSurface 出入队列（批10 承接）。这是"A13 实际默认 ViewRootImpl 用哪条"的代码答案：默认 BLAST（J6）。

### 3.2 BLASTBufferQueue 构造：在途深度三件套

BLASTBufferQueue.cpp:143-165（关键行）：

```cpp
    createBufferQueue(&mProducer, &mConsumer);
    // since the adapter is in the client process, set dequeue timeout
    // explicitly so that dequeueBuffer will block
    mProducer->setDequeueTimeout(std::numeric_limits<int64_t>::max());

    // safe default, most producers are expected to override this
    mProducer->setMaxDequeuedBufferCount(2);
    mBufferItemConsumer = new BLASTBufferItemConsumer(mConsumer,
                                                      GraphicBuffer::USAGE_HW_COMPOSER |
                                                              GraphicBuffer::USAGE_HW_TEXTURE,
                                                      1, false, this);
    ...
    ComposerService::getComposerService()->getMaxAcquiredBufferCount(&mMaxAcquiredBuffers);
    mBufferItemConsumer->setMaxAcquiredBufferCount(mMaxAcquiredBuffers);
```

三件套（J7）：dequeue 超时 INT64_MAX（等待不失败）；dequeue 上限 2（覆盖 Core 缺省 1）；acquire 上限 mMaxAcquiredBuffers——数值不在客户端定，向 SF 查询（:163）。SF 侧公式已证（SurfaceFlinger.cpp:7229-7236）：

```cpp
int SurfaceFlinger::calculateMaxAcquiredBufferCount(Fps refreshRate,
                                                    std::chrono::nanoseconds presentLatency) {
    auto pipelineDepth = presentLatency.count() / refreshRate.getPeriodNsecs();
    if (presentLatency.count() % refreshRate.getPeriodNsecs()) {
        pipelineDepth++;
    }
    return std::max(1ll, pipelineDepth - 1);
}
```

presentLatency = appWorkDuration + sfWorkDuration（:7265-7269 取 late 配置）。60 Hz 下该值随设备 phase 配置浮动；本样本实测在途数到过 3（§8.4 (f:0,a:3)），可反推 mMaxAcquiredBuffers ≥ 1，精确实值未采 dumpsys——三态：公式已证、实值未证（J8）。

### 3.3 一帧提交链：onFrameAvailable → acquireNextBufferLocked → apply

RT 侧 swapBuffers 调到 Surface::queueBuffer（Surface.cpp:1163，ATRACE_CALL :1164），进 BufferQueueProducer::queueBuffer（:807，ATRACE_CALL :809）。票据段（:1046-1067）在本线程同步调 `frameAvailableListener->onFrameAvailable(item)`（:1053）——监听者就是 BLASTBufferItemConsumer→BLASTBufferQueue。BLASTBufferQueue.cpp:671-738 的入口：

```cpp
void BLASTBufferQueue::onFrameAvailable(const BufferItem& item) {
    std::function<void(SurfaceComposerClient::Transaction*)> prevCallback = nullptr;
    SurfaceComposerClient::Transaction* prevTransaction = nullptr;
    bool waitForTransactionCallback = !mSyncedFrameNumbers.empty();

    {
        BBQ_TRACE();
        std::unique_lock _lock{mMutex};
        const bool syncTransactionSet = mTransactionReadyCallback != nullptr;
```

mNumFrameAvailable++（:706）后按是否处于同步期分岔：普通路径直接 acquireNextBufferLocked(std::nullopt)（:732）；同步期并入 mSyncTransaction（:716-730，§7.3）。acquireNextBufferLocked（:483-624）是提交中枢，分段（J9）：

1. 闸门（:487-492）：mNumFrameAvailable==0 或 maxBuffersAcquired(includeExtraAcquire) 为真即返回，等 releaseBufferCallback 重入——整条提交链是回调驱动，无轮询。
2. acquire（:509-517）：`mBufferItemConsumer->acquireBuffer(&bufferItem, 0 /* expectedPresent */, false)`——expectedPresent 传 0，不做显示时间仲裁（§2.5 的丢帧裁决对 BLAST 队列不生效；BLAST 的帧节奏由 Choreographer 决定）。BufferQueueConsumer::acquireBuffer 的 ATRACE_CALL 即 trace 里的 acquireBuffer slice。
3. 组装（:561-569，关键段）：

```cpp
    auto releaseBufferCallback =
            std::bind(releaseBufferCallbackThunk, wp<BLASTBufferQueue>(this) /* callbackContext */,
                      std::placeholders::_1, std::placeholders::_2, std::placeholders::_3);
    sp<Fence> fence = bufferItem.mFence ? new Fence(bufferItem.mFence->dup()) : Fence::NO_FENCE;
    t->setBuffer(mSurfaceControl, buffer, fence, bufferItem.mFrameNumber, releaseBufferCallback);
    t->setDataspace(mSurfaceControl, static_cast<ui::Dataspace>(bufferItem.mDataSpace));
    t->setHdrMetadata(mSurfaceControl, bufferItem.mHdrMetadata);
    t->setSurfaceDamageRegion(mSurfaceControl, bufferItem.mSurfaceDamage);
    t->addTransactionCompletedCallback(transactionCallbackThunk, static_cast<void*>(this));
```

随后 :582-588 补 crop/transform/期望显示时间、:590-593 FrameTimelineInfo、:595-604 dequeue 时间元数据、:606 mergePendingTransactions 并账。
4. 提交（:606-615）：

```cpp
    mergePendingTransactions(t, bufferItem.mFrameNumber);
    if (applyTransaction) {
        // All transactions on our apply token are one-way. See comment on mAppliedLastTransaction
        t->setApplyToken(mApplyToken).apply(false, true);
        mAppliedLastTransaction = true;
        mLastAppliedFrameNumber = bufferItem.mFrameNumber;
    } else {
        t->setBufferHasBarrier(mSurfaceControl, mLastAppliedFrameNumber);
        mAppliedLastTransaction = false;
    }
```

apply(false, true)：同步参 false、单向参 true——one-way binder（机制③）。同步事务路径改挂帧号 barrier（:613，setBufferHasBarrier 在 SurfaceComposerClient.cpp:1482-1492 把 barrierFrameNumber 写进 bufferData）。

### 3.4 与 legacy 路径的区别表

| | legacy（队列在 SF 进程） | BLAST（队列在应用进程） |
|---|---|---|
| 队列位置 | BufferQueueLayer 内（createBufferQueue(consumerIsSurfaceFlinger=true)） | 应用进程（BLASTBufferQueue.cpp:143） |
| dequeue/queue 通道 | 每步都是 binder 往返（IGraphicBufferProducer 代理） | 同进程直调，无 binder |
| 提交协议 | IGraphicBufferProducer::queueBuffer，固定参数集 | Transaction::apply，buffer+属性一笔原子 |
| 帧内属性生效 | 缓冲与图层属性两条链路先后生效 | 同笔事务一次生效（window resize 不再内容/边框错位） |
| 归还通道 | SF 进程内回调 | SF → 客户端 binder（releaseBufferCallbackThunk，wp 绑定 :383-392） |
| trace 形态 | queueBuffer slice 在 SF 进程线程上 | queueBuffer/onFrameAvailable/acquireNextBufferLocked 嵌套在 RT 轨上（§8.2 实测） |

机制②问答——"应用 queueBuffer 到 SF 收到 transaction 经过几次线程切换"：

- 应用进程内：0 次。queueBuffer→onFrameAvailable→acquireNextBufferLocked→Transaction::apply 全在调用线程（RT），trace 嵌套直证（J22）；唯一的常驻线程 AsyncWorker（:970-1018，进程级单例）只服务 ProducerListener 回调（AsyncProducerListener :1021-1036 包的是 connect 时传入的 listener），不在帧提交链上。
- 跨进程：1 次。apply 的 eOneWay 旗（SurfaceComposerClient.cpp:1018-1025）使 setTransactionState 为 one-way binder，RT 调用即返回；SF 在 binder 池线程收到（此段细节批12）。
- 源码学习副本的批注勘误对应：原稿 BLASTBufferQueue.cpp:160"内部一条处理线程串行化回调"与本机 trace 矛盾，已改为"同线程直调＋mMutex/票据串行化"（过程记录 §五 E1）。

### 3.5 三缓冲的构成（机制④前半）

在途深度 = DEQUEUED（≤2）+ ACQUIRED（≤mMaxAcquiredBuffers+1，BLAST 的 consumer 还开了 mAllowExtraAcquire，BLASTBufferQueue.cpp:1081，配 Consumer.cpp:97-104 的"非可丢帧多持一格"）。闸门函数 BLASTBufferQueue.cpp:831-834：

```cpp
bool BLASTBufferQueue::maxBuffersAcquired(bool includeExtraAcquire) const {
    int maxAcquiredBuffers = mMaxAcquiredBuffers + (includeExtraAcquire ? 2 : 1);
    return mNumAcquired >= maxAcquiredBuffers;
}
```

includeExtraAcquire 在常态调用（无外挂事务）为 true（:487 `const bool includeExtraAcquire = !transaction;`）。本样本 (f:0,a:N) 的 N 在 1/2/3 摆动——在途 3 的实测时刻即 §8.4 帧 395 归还点（J12）。

### 3.6 BBQSurface：RT 拿到的生产端是什么

BLASTBufferQueue::getSurface（:912-919）返回的不是裸 Surface，是子类 BBQSurface（:836-887）——它包装 mProducer（本进程 BufferQueueProducer），并覆盖三处行为（J30）：

```cpp
    BBQSurface(const sp<IGraphicBufferProducer>& igbp, bool controlledByApp,
               const sp<IBinder>& scHandle, const sp<BLASTBufferQueue>& bbq)
          : Surface(igbp, controlledByApp, scHandle), mBbq(bbq) {}
    ...
    status_t setFrameRate(float frameRate, int8_t compatibility,
                          int8_t changeFrameRateStrategy) override {
        ...
        return mBbq->setFrameRate(frameRate, compatibility, changeFrameRateStrategy);
    }
```

- setFrameRate/setFrameTimelineInfo 不直接操作 Surface，转交 BLASTBufferQueue（:859-878）——setFrameRate 转成一笔 Transaction apply（:891-897），setFrameTimelineInfo 进 mNextFrameTimelineInfoQueue（:899-903）等下一帧组装时带上（:590-593）。
- allocateBuffers 在独立线程预分配（:847-857），供想要提前建池的调用方。
- query(NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER) 恒答 1（BBQBufferQueueProducer::query :1055-1061）——告知上层"本队列直连合成器"，这是 BLAST 队列的自述身份。
- connect 时经 AsyncProducerListener 包装（:1045-1053）：producer 侧的 onBufferReleased/onBuffersDiscarded 回调改投进程级单例 AsyncWorker 线程（:1021-1036/:970-1018），模拟 one-way binder、防回调反抢队列锁——这是该进程唯一的 BLAST 相关常驻线程，不在帧提交链上（§3.4）。
- ViewRootImpl 拿到 BBQSurface 后用 transferFrom 换绑（:2196-2199），SurfaceView 用 copyFrom（SurfaceView.java:1143）——两条换绑路径的差别在 generationId（Surface.java:591-601 updateNativeObject，批09/批10 有接引）。

## 4. 机制③ Transaction::apply → SF 入口

### 4.1 setBuffer：缓冲与归属装进 layer_state_t

SurfaceComposerClient.cpp:1494-1546 的 setBuffer（关键段）：

```cpp
    std::shared_ptr<BufferData> bufferData = std::make_shared<BufferData>();
    bufferData->buffer = buffer;
    uint64_t frameNumber = sc->resolveFrameNumber(optFrameNumber);
    bufferData->frameNumber = frameNumber;
    bufferData->flags |= BufferData::BufferDataChange::frameNumberChanged;
    if (fence) {
        bufferData->acquireFence = *fence;
        bufferData->flags |= BufferData::BufferDataChange::fenceChanged;
    }
    bufferData->releaseBufferEndpoint =
            IInterface::asBinder(TransactionCompletedListener::getIInstance());
```

releaseBufferEndpoint 指回本进程的 TransactionCompletedListener——SF 用完缓冲就按这个 endpoint 反向 binder 调 releaseBufferCallback（机制⑤的归还通道，J10）。同图层重复 setBuffer 先走 releaseBufferIfOverwriting（:808-827，:1504 调用）归还被顶帧。

### 4.2 缓存协议：第二帧起缓冲本体不过线

apply 前的 cacheBuffers（SurfaceComposerClient.cpp:998 调用，实现 :918-962）：

```cpp
        uint64_t cacheId = 0;
        status_t ret = BufferCache::getInstance().getCacheId(s->bufferData->buffer, &cacheId);
        if (ret == NO_ERROR) {
            // Cache-hit. Strip the buffer and send only the id.
            s->bufferData->buffer = nullptr;
        } else {
            // Cache-miss. Include the buffer and send the new cacheId.
            cacheId = BufferCache::getInstance().cache(s->bufferData->buffer);
        }
```

同一 GraphicBuffer 反复提交时（三缓冲循环里每块缓冲每 2~3 帧回来一次），缓存命中后 parcel 里只有 token+id，不再 flatten GraphicBuffer——BLAST 路径"槽号即句柄"的跨进程版（J11）。

### 4.3 apply 的旗、token 与清账

SurfaceComposerClient.cpp:964-1051 主链（源码学习副本的批注 :1039-1045 已审）：eSynchronous/eAnimation/eOneWay/eEarlyWakeup* 四旗（:1012-1034，eSynchronous 与 eOneWay 同设强制同步 :1019-1022）；applyToken 缺省绑 TransactionCompletedListener 的 binder（:1036-1038）——同 token 事务在 SF 侧按提交序放行（批12）；`sf->setTransactionState(...)` :1040-1043 发出后 clear() :1047 归零待下一帧。

### 4.4 SF 入口（本篇边界）

binder 侧打包转发：ISurfaceComposer.cpp:1100（BpSurfaceComposer::setTransactionState 尾部 `return setTransactionState(frameTimelineInfo, state, displays, stateFlags, applyToken, ...)`，打包后 transact）；SF 进程侧入口：frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp:4138 `status_t SurfaceFlinger::setTransactionState(`。SF 收文后的队列、放行（帧号 barrier/期望时间）、latch 在批12 走读（J13）。机制③问答补一刀——max acquired/dequeued 缺省值与卡顿的关系：

- Core 缺省 acquired 1/dequeued 1（BufferQueueCore.cpp:114-115）只对未经调教的队列生效；BLAST 窗口实际是 dequeued 2 + acquired 公式值（J7），在途 3~4。
- 关系一：在途上限决定了"应用可以比 SF 快多少帧"。上限内应用不等；触顶后 acquire 停（BLASTBufferQueue.cpp:489）、dequeue 睡等（若 mFreeBuffers 也耗尽）——卡顿形态是 RT 睡在 mDequeueCondition 而非报错。
- 关系二：上限与归还速度联动。SF 归还慢（合成滞后），在途位不释放，acquireNextBufferLocked 提前返回，帧在 shadow queue（mNumFrameAvailable）里排队——本样本帧 395 在途 98.202 ms（§8.4）即归还侧慢的实测形态。

### 4.5 Transaction 对象的其他账目

一笔 Transaction 不只装缓冲，本篇涉及的其余账目（J31）：

- merge（:828-883）：把另一笔事务并入本笔——同 handle 的 layer_state_t 逐字段并（:836），buffer 冲突时先 releaseBufferIfOverwriting 归还被顶帧（:833-835）；flags 取或、mApplyToken 取 other 的（:877）、FrameTimelineInfo 并集（:879）。BLAST 的 mergePendingTransactions（§7.4）与 ViewRootImpl 的 bounds layer 并帧都用它。
- listener callbacks（:971-996）：有 SurfaceControl 关联的回调写进对应 layer_state_t（:985-994），无关联的直接列进 listenerCallbacks 参数（:980-982）——SF 侧按层回执。
- clear（:885-900）：apply 发出即清账（:1047），mApplyToken/mDesiredPresentTime/各旗归零——Transaction 对象是一次性信封，重用靠重新组装。
- doUncacheBufferTransaction（:906-916）：单独一笔空事务携带 uncacheBuffer 通知 SF 释放缓存槽——BufferCache 逐出时的对账通道（配合 §4.2）。
- addTransactionCompletedCallback（:1691 起）/addTransactionCommittedCallback（:1697 起）：前者等 SF"事务已按序应用"，后者只等"已提交"；BLASTBufferQueue 在 setBuffer 时强制挂一个空完成回调（SurfaceComposerClient.cpp:1539-1542，注释说明：没有完成回调就没有 release 回调，legacy 兼容，TODO b/230380821）。

## 5. 机制④ 三缓冲与丢帧：dequeue 等待时的下一帧怎么排

先立时序（全部相对时间，逐 ns 表见 §8.2）：

- UI 线程（tid 1909）的帧节奏由 Choreographer 驱动：批09 实测 508 doFrame : onVsync : scheduleVsyncLocked = 508:508:508，无空帧。
- RT 线程的缓冲操作在 UI 帧内消化：q4 覆盖帧的 dequeueBuffer 内层 dur 27,000~80,000 ns、queueBuffer 内层 dur 340,693~699,884 ns，无一次撞上睡等（若撞上，dequeueBuffer slice 会被拉长到毫秒级）。
- postAndWait（批09 G14：6,842,268,957，353,769 ns）等的不是 RT 画完，是 RT 的同步段：DrawFrameTask.cpp:142-175 run() 先 syncFrameState（:153）后 unblockUiThread（:174-175），UI 线程随即醒来跑下一帧；RT 继续 CanvasContext::draw 的 dequeue/绘制。本样本直接证据：帧 396 的 RT dequeueBuffer（6,842,618,303）早于 UI postAndWait 结束（6,842,268,957+353,769=6,842,622,726）4.4 μs 开始——双线程并行窗口的实测形态（J23；RT 侧调用点归属批10）。

规则（机制④问答"dequeue 等待时 doFrame 的下一帧如何排"）：

- 下一帧的 doFrame 由 vsync 决定，不由缓冲决定。Choreographer 在 draw 阶段已 scheduleVsync（批09 G3），下一帧的 UI 段照常排队。
- 若 RT 因在途满而睡等（dequeueBuffer 阻塞），阻塞发生在 RT；UI 线程在 postAndWait 等到的是同步段放行，与 RT 是否睡等解耦——但若 RT 的睡等发生在 syncFrameState 之前（getFrame 在 draw 内，晚于 sync），UI 已放行，表现为 RT 轨上长 dequeueBuffer slice，UI 下一帧照跑；若连续多帧积压，UI 帧的 postAndWait 会逐帧后移（因为 DrawFrameTask 排队），最终在 Choreographer 层显形为 doFrame 间隔拉大。
- 本样本判决：65.630 ms 的 doFrame 空档（155803→155812）期间 RT 无任何长 slice（q4 窗口内 RT 仅两条 μs 级 slice：RV Prefetch 相关与 reportTimeline 见批09 q7；RT dequeue/queue 全集中在 6.842s 之后）——空档不是缓冲等待造成，主线程 S 50.928 ms（契约 :69）与 RT 无关（RT 在 6.78~6.84s 无 buffer 操作 slice，J24）。三缓冲机制在本样本未丢帧：508 doFrame 对应 507 次 acquire（第一帧的 acquire 在 trace 起点前，留一条 dur=−1 的悬置 dequeueBuffer 在 rel 0，J21）。

### 5.1 帧号 395→398 的提交时刻线（q4 全表节选）

| BufferItem 帧号 | 对应 doFrame（vsyncId 及起点） | RT 提交终点（acquireNextBufferLocked 止） |
|---|---|---|
| 395 | 155803（6,776,444,919） | 6,779,448,303 |
| 396 | 155812（6,842,074,918） | 6,844,847,072 |
| 397 | 155820（6,865,862,842，dur 8,363,692） | 6,876,970,226（acquire 6,876,692,842 dur 14,807；acquireNextBufferLocked 6,876,711,765 dur 258,461） |
| 398 | 155827（6,892,728,765，dur 2,721,884） | 6,898,846,995（dequeue 6,893,774,303 dur 70,192；queue 内层 6,898,335,034 dur 534,346；onFrameAvailable (f:0,a:2) 6,898,488,072 dur 363,346；acquire 6,898,497,995 dur 16,385；acquireNextBufferLocked 6,898,518,534 dur 328,461） |

读法：帧 397 的提交横跨 doFrame 155820 的 8.364 ms——UI 段长（Record 4,402,116 ns，批09 q2）不牵制 RT 的 dequeue（6,872,408,226 起，dur 158,077/80,000 两层），缓冲等待未参与该帧（J32）。帧 398 在 (f:0,a:2) 下完成 acquire——归还 395（§8.4）腾出的在途位被 398 用上，回归 1↔2 的常态摆动。

## 6. 机制⑤ fence 在 buffer 生命周期中的传递

### 6.1 正向：queueBuffer 带 acquire fence

- 生产端：RT 绘制完成后由 EGL 产出 fence，Surface::queueBuffer 打包（Surface.cpp:1184 `getQueueBufferInputLocked(buffer, fenceFd, mTimestamp, &input)`），BufferQueueProducer::queueBuffer 解包存槽（:907 `mSlots[slot].mFence = acquireFence;`）并随 BufferItem 出货（:930）。
- BLAST 侧：acquireBuffer 拿到 BufferItem.mFence 后 dup 一份装进事务（BLASTBufferQueue.cpp:564 `sp<Fence> fence = bufferItem.mFence ? new Fence(bufferItem.mFence->dup()) : Fence::NO_FENCE;`），setBuffer 把它写成 bufferData->acquireFence（SurfaceComposerClient.cpp:1518-1520）——SF latch 时按这个 fence 判"生产端写完没有"。
- SF 侧拒收点（机制④问答"acquire fence 未信号时 SF 合成线程的行为"）：BufferLayer.cpp:458-474（latchBuffer）：

```cpp
bool BufferLayer::latchBuffer(bool& recomputeVisibleRegions, nsecs_t latchTime,
                              nsecs_t expectedPresentTime) {
    ATRACE_CALL();
    ...
    // If the head buffer's acquire fence hasn't signaled yet, return and
    // try again later
    if (!fenceHasSignaled()) {
        ATRACE_NAME("!fenceHasSignaled()");
        mFlinger->onLayerUpdate();
        return false;
    }
```

fenceHasSignaled 的 BufferStateLayer 实现（BufferStateLayer.cpp:576-589）：LatchUnsignaledConfig 非 Disabled 时恒真（可 latch 未信号 fence），否则查 acquireFence->getStatus()。未信号时本层本帧被跳过（return false），SF 请求下一帧再来——不报错、不阻塞（合成线程行为的精确定义在批12 的合成主循环，此处只给 latch 判定点，J14）。

### 6.2 反向：SF 归还带 release fence

回程四站（J15）：

1. SF 用完缓冲 → 按 setBuffer 时登记的 releaseBufferEndpoint 反向 binder → 客户端 TransactionCompletedListener 派发 releaseBufferCallbackThunk（wp 绑定，BLASTBufferQueue.cpp:383-392，对象销毁后静默失效）。
2. BLASTBufferQueue::releaseBufferCallback :403-411 → releaseBufferCallbackLocked :413-463：进 mPendingRelease 队列（:435-443，fakeRelease 兜底也在此），按持有策略归还（EGL 客户端低刷时多攥几帧 :432-433），逐个 releaseBuffer 并续接 acquireNextBufferLocked（:445-457）；打 PendingRelease/QueuedBuffer 两条计数（:459-461）。
3. releaseBuffer :465-481：mNumAcquired--（:473）、`mBufferItemConsumer->releaseBuffer(it->second, releaseFence)`（:476）——release fence 一路带到 BufferQueueConsumer::releaseBuffer。
4. BufferQueueConsumer.cpp:442-508：帧号校验（:459-467 STALE_BUFFER_SLOT 防旧释放误伤）、`mSlots[slot].mFence = releaseFence;`（:478）、release() 置 FREE、槽回 mFreeBuffers（:488-491）、notify_all（:498）唤醒 §2.4 的 dequeue 等待者。下一次 dequeueBuffer 返回该槽时把这个 fence 交给 RT（Producer.cpp:525-527 `*outFence = ... mSlots[found].mFence;`）——RT/EGL 在写缓冲前等它信号，"SF/显示读完"的承诺兑现，闭环。

### 6.3 trace 里的 fence 实证

- `HWC release fence <N> has signaled` slice 507 条，产生点 Surface.cpp:560（FenceMonitor::queueFence，监视线程名 "HWC release" :696）：dequeue 取回的 release fence 在入队时已信号就打这条——本样本 507 条全是"已信号"形态，无一例等待（J16）。帧 396 实测：RT dequeueBuffer 内层止于 6,842,665,649，`HWC release fence 653 has signaled` 打在 6,842,683,726（dur 2,577 ns）。
- queueBuffer 侧对称有 "GPU completion" 监视线程（Surface.cpp:1158 `static FenceMonitor gpuCompletionThread("GPU completion");`）登记 acquire fence；counter 轨 "GPU completion" 在本样本存在（q2 输出）。
- 归还 slice 链（binder 线程，tid 2837）实测见 §8.4：releaseBufferCallback 内嵌 releaseBuffer，内嵌 Consumer::releaseBuffer，其内的 wait/merge 小 slice 是 fence 合并与等待的痕迹（Fence::merge 与 wait 调用，BLASTBufferQueue 的归还路径）。

### 6.4 归还链里 wait 与 merge 两个 slice 的精确来源

§8.4 表里 releaseBuffer 之内的 `wait`（5,500/6,154 ns）与 `merge`（21,769 ns）不在 BLASTBufferQueue 自身，而在 ConsumerBase 的槽位 fence 对账（ConsumerBase.cpp:425-478）：BLASTBufferItemConsumer 继承 BufferItemConsumer→ConsumerBase，releaseBufferLocked（:480）归还前经 addReleaseFenceLocked（:419-420）决定"新 release fence 与槽内旧 fence 怎么合并"：

```cpp
    // Check status of fences first because merging is expensive.
    // Merging an invalid fence with any other fence results in an
    // invalid fence.
    auto currentStatus = mSlots[slot].mFence->getStatus();
    ...
    if (currentStatus == incomingStatus) {
        char fenceName[32] = {};
        snprintf(fenceName, 32, "%.28s:%d", mName.string(), slot);
        sp<Fence> mergedFence = Fence::merge(
                fenceName, mSlots[slot].mFence, fence);
```

- `wait` slice 来自 Fence::wait（Fence.cpp:48-49，ATRACE_CALL）——getStatus 对 sync fd 查询信号的 ioctl 等待；两次 wait 对应 :437/:443 两次 getStatus。
- `merge` slice 来自 Fence::merge（Fence.cpp:90-92，ATRACE_CALL）——只在两 fence 状态相同（同信号/同未信号）时执行（ConsumerBase.cpp:450-464），fence 名 "…:槽号"；一已信号一未信号时直接取未信号者（:465-473，时间戳近似），不再 merge。
- 该对账的产物 mSlots[slot].mFence 就是下一次 dequeueBuffer 交还 RT 的 release fence（§6.2 第 4 站）——`wait`/`merge` 的耗时（本样本 μs~20 μs 级）计入归还回执，不计入合成（J33）。

## 7. BLASTSyncEngine（A13 实体在 WMS 侧）

BLASTSyncEngine 是窗口管理服务中的同步协调组件，用于把多个窗口相关的更新组织到同步组中。它与负责缓冲和事务衔接的 BLASTBufferQueue 不是同一个对象。

### 7.1 判定：存在，但不在 native

全树 grep "BLASTSyncEngine"：源码命中仅 frameworks/base/services/core/java/com/android/server/wm/BLASTSyncEngine.java（397 行）与 WindowManagerService.java:1242 `mSyncEngine = new BLASTSyncEngine(this);`；frameworks/native 无同名 .cpp/.h（winscope.html 与 out/ 构建产物中的字符串不算源码）。批11 作业单"如存在"的答案：存在于 WMS 侧，职责是 WMS 的多窗口事务同步，不在每帧提交主线上（J17）。

### 7.2 合并语义：SyncGroup 三步走

BLASTSyncEngine.java:37-63 类注释给出流程：startSyncSet（开组）→ addToSyncSet（收成员，:333-335 转发到 SyncGroup::addToSync :238-248，成员经 prepareSync 把后续事务重定向进组）→ setReady（:337-343，置真即请求一轮遍历 :228-236）→ onSurfacePlacement 巡检（:136-149，全员 isSyncFinished 才 finishNow）。

finishNow 的合并动作（BLASTSyncEngine.java:151-162，关键段）：

```java
        private void finishNow() {
            if (mTraceName != null) {
                Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, mTraceName, mSyncId);
            }
            ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "SyncGroup %d: Finished!", mSyncId);
            SurfaceControl.Transaction merged = mWm.mTransactionFactory.get();
            if (mOrphanTransaction != null) {
                merged.merge(mOrphanTransaction);
            }
            for (WindowContainer wc : mRootMembers) {
                wc.finishSync(merged, false /* cancel */);
            }
```

所有成员子树暂存的 sync 事务并成一笔 merged（孤儿事务兜底 :127-134），挂 committed 监听与超时兜底（:202-203），交 mListener.onTransactionReady(mSyncId, merged)（:206）。合并语义：全组或全不——成员在 finishSync 前外界看不到更新，merged 一笔呈现。

单活动约束：startSyncSet :307-311 依据（"We currently only support one sync at a time"），后来者经 queueSyncSet :386-391 排队，finishNow 尾部 :211-225 启动下一组。

### 7.3 超时与客户端半边

- 超时值：BLAST_TIMEOUT_DURATION = 5000（WindowState.java:797，注释 /* milliseconds */）；CommitCallback 的强制清算在 :203 `mWm.mH.postDelayed(callback, BLAST_TIMEOUT_DURATION);`——merged 交出去 5 秒无 committed 回执即强制放行并打 "Application ANR likely to follow"（:194-195）。
- 客户端半边：WMS 同步场景下 ViewRootImpl 经 mBlastBufferQueue.syncNextTransaction（BLASTBufferQueue.java:95-109 → native syncNextTransaction :755-784）把帧收进 mSyncTransaction；帧号记入 mSyncedFrameNumbers（:724），transactionCommittedCallback 逐帧清账（:299-302），清空后 flushShadowQueue（:394-401）把暂存帧提交——与引擎的"等全组 buffer 到齐再合并"对齐（J18）。
- 另一处客户端对账：mergeWithNextTransaction/mergePendingTransactions（:921-932/:943-959）按帧号挂账并入——ViewRootImpl.prepareSurfaces 的 bounds layer 裁剪即走此口（ViewRootImpl.java:2230）。

### 7.4 引擎在本 trace 的可见性（未查证声明）

SyncGroup 构造时的异步追踪段（BLASTSyncEngine.java:116-119，mTraceName = name+"SyncGroupReady"，Trace.asyncTraceBegin）属于 window manager 类目打点，本篇未对 trace 做该类目查询——WMS 同步引擎在本采集窗口内是否活动过为未查证项（声明"未查"，不作"不存在"推断；判据与类目核对留批12/15，J34）。客户端半边的间接形态：q4 所列帧的 (f,a) 快照在 1↔3 间摆动、无同步期特征序列可辨，但 slice 名不含 sync 分支标记，"本窗口无进行中的 BLAST 同步组"保持未证（J35）。

## 8. trace 配对：应用帧提交段可见性（已证）

### 8.1 可见性清单（新采集证据，SQL 与输出存档：批11-q1~q5）

com.yadea.launcher 进程（tid 只适用于本采集）：

| 对象 | 线程 | 计数 | 源码产生点 |
|---|---|---|---|
| dequeueBuffer（两层/帧） | RenderThread 2079 | 1016 | Surface.cpp:637 + Producer.cpp:383 |
| queueBuffer（两层/帧） | RenderThread 2079 | 1014 | Surface.cpp:1164 + Producer.cpp:809 |
| acquireBuffer | RenderThread 2079 | 507 | Consumer.cpp:80 |
| onFrameAvailable - VRI[AppListActivity]#8(f:0,a:N) | RenderThread 2079 | 507 | BLASTBufferQueue.cpp:677 BBQ_TRACE |
| acquireNextBufferLocked - ...(frame=F) | RenderThread 2079 | 507 | BLASTBufferQueue.cpp:521 BBQ_TRACE("frame=%") |
| releaseBufferCallback / releaseBuffer / transactionCallback | binder:1909_5(2833)/_7(2837)/_3(2021)/_4(2370) | 231/171/74/31，合计各 507 | :406/:474/:328 |
| HWC release fence N has signaled | RenderThread 2079 | 507 | Surface.cpp:560 |
| counter: QueuedBuffer - VRI[AppListActivity]#8BLAST#8 | 进程轨 | — | BLASTBufferQueue.cpp:157 命名、:710-711/:459-461 打点 |
| counter: VRI[AppListActivity]#8(BLAST Consumer)8 | 进程轨 | — | Producer.cpp:1011 / Consumer.cpp:300（consumerName :156 命名） |
| counter: PendingRelease | 进程轨 | — | BLASTBufferQueue.cpp:459 |
| counter: aq:pending:com.yadea.launcher/...AppListActivity | 进程轨 | 1710 个采样点（0↔1） | ViewRootImpl.java:1419（输入侧，非 buffer） |

slice 名里的 (f,a) 即 BBQ_TRACE 宏（BLASTBufferQueue.cpp:61-63）展开的 mNumFrameAvailable/mNumAcquired 进入时快照。507 = 508−1：trace 起点恰在一帧中段，该帧的 dequeueBuffer 以 dur=−1 悬置在 rel 0（q1 首行），其 acquire 未在 trace 内发生（J21）。

### 8.2 帧 396（doFrame 155812）逐 ns 提交段

| rel_ts (ns) | dur (ns) | 线程 | slice | 出处 |
|---|---|---|---|---|
| 6,842,074,918 | 564,193 | 1909 | Choreographer#doFrame 155812 | 契约 :75 |
| 6,842,085,803 | 546,654 | 1909 | traversal | 批09 q2 |
| 6,842,147,649 | 480,116 | 1909 | draw | 批09 q2 |
| 6,842,162,226 | 99,577 | 1909 | Record View#draw() | 批09 q2 |
| 6,842,268,957 | 353,769 | 1909 | postAndWait | 批09 q2（逐字一致） |
| 6,842,618,303 | 87,192 | 2079 | dequeueBuffer（外层） | q4 |
| 6,842,624,918 | 40,731 | 2079 | dequeueBuffer（内层） | q4 |
| 6,842,683,726 | 2,577 | 2079 | HWC release fence 653 has signaled | q4 |
| 6,844,165,188 | 1,116,077 | 2079 | queueBuffer（外层） | q4 |
| 6,844,199,188 | 699,884 | 2079 | queueBuffer（内层） | q4 |
| 6,844,237,957 | — | 轨 | (BLAST Consumer)8 计数 0→1 | q5 |
| 6,844,347,803 | 509,539 | 2079 | onFrameAvailable - ...(f:0,a:1)（嵌套于 queueBuffer） | q4 |
| 6,844,355,111 | — | 轨 | QueuedBuffer - ... 计数 = 2 | q5 |
| 6,844,366,418 | 29,270 | 2079 | acquireBuffer（嵌套于 onFrameAvailable） | q4 |
| 6,844,389,265 | — | 轨 | (BLAST Consumer)8 计数 1→0 | q5 |
| 6,844,405,072 | 442,000 | 2079 | acquireNextBufferLocked - ...(frame=396) | q4 |

读法：UI doFrame 止于 6,842,639,111；RT dequeue 早于 postAndWait 结束 4.4 μs；queueBuffer 内层 699,884 ns 期间嵌套完成了 onFrameAvailable（509,539 ns）→ acquire（29,270 ns）→ 组装；acquireNextBufferLocked 止于 6,844,847,072，apply 的 one-way binder 即在此 slice 尾部发出。队列计数 0→1→0 的 151,308 ns 是 BufferItem 在 mQueue 里的停留时长（J25）。

### 8.2.1 基准源码树代码行号 × trace 时刻对照（帧 396）

| 基准源码树锚点 | 代码事件 | trace 时刻（rel ns） |
|---|---|---|
| Surface.cpp:1164 / Producer.cpp:809 | queueBuffer 双层 slice 起点 | 6,844,165,188 / 6,844,199,188 |
| Producer.cpp:907-908 | 槽存 fence、状态 queue() | （锁内，无独立 slice） |
| Producer.cpp:956 | BufferItem 入 mQueue | （对应计数 0→1：6,844,237,957） |
| Producer.cpp:1017 | 领回调票据 | （锁内） |
| Producer.cpp:1053 | onFrameAvailable 直调 | 6,844,347,803 |
| BLASTBufferQueue.cpp:677/:706 | BBQ_TRACE、mNumFrameAvailable++ | （对应 QueuedBuffer 计数 2：6,844,355,111） |
| Consumer.cpp:80/:281 | acquireBuffer、状态 acquire() | 6,844,366,418（对应计数 1→0：6,844,389,265） |
| BLASTBufferQueue.cpp:509-517 | acquireBuffer 调用点 | 6,844,405,072 起（acquireNextBufferLocked slice） |
| BLASTBufferQueue.cpp:561-569 | setBuffer 组装 | （slice 内） |
| BLASTBufferQueue.cpp:606-609 | mergePendingTransactions + apply | （slice 尾部，止 6,844,847,072） |
| SurfaceFlinger.cpp:4138 | SF 收文 | 本 trace 不可见（盲区，§11.2） |
| ConsumerBase.cpp:455 | Fence::merge（归还链） | 6,877,680,534（帧 395 归还，§8.4） |

表内"无独立 slice"的行以相邻 slice 边界与计数轨翻转为界（已证区间、未证精确内部时刻）。

### 8.3 两套编号的对照

- vsyncId（155812）：Choreographer slice 名（Choreographer.java:748-749，批09 G1/G2），SF 时间线档位编号。
- BufferItem 帧号（frame=396）：BufferQueueProducer.cpp:912-914 的 ++mCore->mFrameCounter，本进程 BLAST 队列单调计数。
- 两者的关联只经帧内容（一帧 UI 产出对应一次 queue），不能互推：trace 里 doFrame 155812 的 RT 段提交 frame=396，doFrame 155803 → frame=395，doFrame 155820 → frame=397，依序一一对应（q4 全表，J26）。

### 8.4 在途深度与归还实测

帧 395 的提交（doFrame 155803 的 RT 段）：dequeueBuffer 内层 6,777,671,303（32,923 ns）、queueBuffer 内层 6,778,917,765（559,730 ns）、onFrameAvailable (f:0,a:2) 6,779,024,572（427,962 ns）、acquireNextBufferLocked frame=395 止于 6,779,448,303（apply 发出）。

帧 395 的归还（binder:1909_7，tid 2837）：

| rel_ts (ns) | dur (ns) | slice | 出处 |
|---|---|---|---|
| 6,877,650,303 | 102,577 | releaseBufferCallback - ...(f:0,a:3) | q1/q4 |
| 6,877,657,649 | 86,154 | releaseBuffer - ...(frame=395)（嵌套） | q4 |
| 6,877,661,842 | 5,500 | wait（嵌套，fence/锁等待小段） | q4 |
| 6,877,680,534 | 21,769 | merge（嵌套，fence 合并） | q4 |
| 6,877,707,880 | 29,538 | releaseBuffer（Consumer::releaseBuffer ATRACE_CALL，嵌套） | q4 |
| 6,877,757,726 | 13,192 | transactionCallback - ...(f:0,a:2) | q4 |
| 6,877,763,495 | 2,693 | setTransformHint（嵌套） | q4 |

- 在途 3 的实测：(f:0,a:3) 表示该回调进入时 mNumAcquired=3（395/396/397 三帧已提交未归还）——BLAST 在途深度的直接读数（J12）。
- 帧 395 在途时长：6,877,650,303 − 6,779,448,303 = 98,202,000 ns = 98.202 ms（端点算术，已证）。其中包含 50.928 ms 主线程 S 与 65.630 ms doFrame 空档窗口——SF 侧为何迟迟未消费/归还属批12 的合成节拍问题，本篇只立"缓冲在途 98.202 ms 未引发 dequeue 报错或长等待"这一事实：6.78~6.87s 窗口内 RT 无新增长 slice（帧 395 的 queueBuffer 止于 6,779,559,957，下一站 dequeueBuffer 在 6,842,618,303，q4），在途满未触顶（a 最大 3 ≤ 闸门），三缓冲深度吸收了整段空闲（J27）。

### 8.5 归还侧的 binder 线程分布

507 次 releaseBufferCallback 分摊在四条 binder 线程：binder:1909_5（tid 2833，231 次）、binder:1909_7（tid 2837，171 次）、binder:1909_3（tid 2021，74 次）、binder:1909_4（tid 2370，31 次）（q1/q3）。它们是应用进程的 binder 池线程，执行的是 TransactionCompletedListener::onTransactionCompleted（SurfaceComposerClient.cpp:311 起）派发出的两类回调：

- releaseBufferCallbackThunk（BLASTBufferQueue.cpp:383-392，wp 绑定）：归还缓冲，§6.2。
- transactionCallbackThunk（:314-322，强引用 + incStrong 保活 :553）：SF 已应用该事务后的回执——更新 mTransformHint（:337-338，值来自 SurfaceControlStats）、补 frameEventHistory（:342-351）、对早于当前帧号的残留提交做 fakeRelease 清账（:353-366）。

两个回调共用一个 binder 线程池，所以同一帧的 release 与 transactionCallback 常落在同一线程相邻时刻（§8.4 表：6,877,650,303 与 6,877,757,726 同在 tid 2837）。queue stall listener（构造时注册，BLASTBufferQueue.cpp:169-177）也在同一监听器上：onTransactionQueueStalled（SurfaceComposerClient.cpp:452）触发 mTransactionHangCallback——这是 BLAST 提交链的"堵车"报警通道，ViewRootImpl 挂的 sTransactionHangCallback（ViewRootImpl.java:893-894）接收后弹 ANR 提示路径（J36）。

### 8.6 vg-kit 与本篇的边界

vg-kit Display0 序列 16/39/34/30/31/40/35（契约 :77）是 host/Yocto 域的约 2 秒平均 scanout 计数（00 篇 #23），与本篇的客户端提交段之间隔着 SF 合成与 drm 提交（批12/批13 的地界）。本篇不对该序列做任何归因；序列与 SF present 计数的衔接边界在批12 划（契约 :166）。

## 9. 机制四问汇总

见 §1.1 表。正文四问落点索引：

1. dequeue 阻塞谁在等、等什么——§2.4（谁在等：RT；等什么：releaseBuffer/队列深度下降的 notify_all）。
2. BLAST 下 queueBuffer→SF 收文几次线程切换——§3.4（应用内 0 次＋one-way binder 1 次）。
3. max acquired/dequeued 缺省值与卡顿的关系——§2.1 缺省（1/1/64）＋§3.2 BLAST 实配（2/公式值）＋§4.4 两条关系。
4. acquire fence 未信号时 SF 合成线程的行为——§6.1（latch 跳层 return false，批12 接合成主循环）。
5. BLASTSyncEngine 合并语义——§7（SyncGroup 全组合并＋5 s 超时＋客户端 mSyncedFrameNumbers 半边）。

五个机制的正向/反向证据各自独立成段（§2~§7 源码、§8 trace），同一结论两处口径一致；机制②与机制⑤的交接对象（SF）超出本篇边界的部分一律以"见批12"标注，不提前下结论。

四问的执行主体在源码侧与 trace 侧各自可指认：源码侧给函数与锁（谁执行），trace 侧给线程与时刻（何时执行）——两列证据在 §8.2.1 的对照表里逐行对接。

## 10. 与前后批的边界

- 批10（写作中）：RT 从 postAndWait 到 dequeue/queue 的调用点（DrawFrameTask/CanvasContext/SkiaOpenGLPipeline）与 fence 的 EGL 侧等待；本篇只讲 libs/gui 层。批09 的 postAndWait 353,769 ns 在本篇获得解释：等的是 RT 同步段放行（DrawFrameTask.cpp:153→:174-175），不是 RT 画完（§5）。
- 批12：SF 侧 setTransactionState :4138 之后的 transaction queue、flushTransactionQueues、latch 与合成；帧背压（上帧 present fence 未信号整帧放弃）也在批12。
- 批13：vg-kit 计数与 host 渲染盲区（§8.6）。
- 批08 未证项处理：`aq:*` 计数已定性为输入侧（ViewRootImpl.java:1409-1419 四个计数名：aq:native-post-ime/aq:ime/aq:native-pre-ime/aq:pending），本样本 aq:pending:...AppListActivity 轨有 1710 个采样点（0↔1 脉冲）——"aq:* counter 是否存在"从未证升级为已证存在且非 buffer 计数（J19）。

### 10.1 本层读 trace 的四个易错点

1. dequeueBuffer/queueBuffer 是双层同名 slice：按名字去重统计会把 508 帧数成 1016 次"出队"（§1）。区分靠 depth 与内外层 dur（外层含 EGL 交接，内层才是队列操作）。
2. queueBuffer slice 时长 ≠ 绘制耗时：内层 699,884 ns 里嵌着 onFrameAvailable→acquire→组装→apply（509,539 ns 属提交段），把 queueBuffer 当"GPU 画完的时长"会双算（批09/10 的分工口径）。
3. `aq:pending` 不是 buffer 队列深度：名字以 aq 开头但语义是待处理输入事件数（§10，J19）；BLAST 的队列深度看 (BLAST Consumer) 与 QueuedBuffer 两条轨。
4. vsyncId（155812）与 BufferItem 帧号（396）不能互推差值：前者是 SF 时间线档位编号（有跳号，批09 G1），后者是本进程队列单调计数（§8.3）。

### 10.2 对契约批11 作业单预置表述的三处更正

1. 作业单"frameworks/native/libs/gui/BLASTBufferQueue.cpp（…mNumFramesPending）"：Android 13 基准源码树无 mNumFramesPending 成员（全文件 grep 无命中），对应实体是 mNumFrameAvailable 与 mNumAcquired（BLASTBufferQueue.cpp:706-707 递增、:473 递减，日志宏 :50-59 同时引用）——后续批次引用时用后者（J37）。
2. 作业单"BLASTSyncEngine.cpp（如存在）"：native 侧不存在，实体为 WMS 侧 BLASTSyncEngine.java（§7.1）——已证，非盲区。
3. 作业单"trace 配对：…BLAST/transaction slice 若无则盲区声明"：本样本提交段可见，预置盲区不成立（§8.1，J20~J27）；需保留的只有 SF 收文后的段（§11.2）与 binder 段时刻（§11.2）。

## 11. 未证边界清单

1. mMaxAcquiredBuffers 在本设备的实值（公式已证；dumpsys 未采）——未证。补采方向：`dumpsys SurfaceFlinger` 无该项时，可采 BLASTBufferQueue 构造期的 ALOGV 或直接算 phase 配置。
2. apply 的 binder 段在 trace 中的精确时刻（binder slice 类目在本 trace 未记录，00 篇 §3.7 盲区沿用；acquireNextBufferLocked slice 止点只给出上界）——盲区。补采：atrace binder 类目重采。
3. SF 收文后到 latch 的全程（批12 地界）——本篇不判。
4. doFrame 155820 的 8.364 ms 与帧 397/398 提交段的因果（UI 段长、RT 段短，是否受 SF 归还慢牵制未证）——未证。
5. trace 内 (f,a) 快照的 (f:0,a:N) 中 a=1/2/3 的完整序列表（本篇只引 6,877,650,303 一例 a:3 与 6,844,347,803 一例 a:1，其余样本点在 q1 输出 CSV 可复算）——已证存在、全序列未逐条展开。
6. BLASTSyncEngine 在本 trace 的可见记录（"xxxSyncGroupReady" async slice 属 window manager 类目，本篇未查）——未查证，留给批12/15 的类目核对。
7. 帧 395 在途 98.202 ms 中 SF 侧的状态（是否曾 latch/为何晚归还）——批12。
8. 本窗口是否存在进行中的 WMS BLAST 同步组（§7.4 判据不足）——未证。
9. 帧提交段 slice 名内 (f,a) 快照与 trace 后各时刻的 mNumAcquired 差一个归还计数（快照口径是进入时刻）——已证口径、未逐点换算。
10. 本机 BLAST 队列实际建了几个 GraphicBuffer（dequeue 上限 2+acquired 上限给出上界 4~5，dumpsys 未采）——未证。

## 12. 断言索引（J1~J37，逐条验证见过程记录 §四）

J1 计数向量状态机（BufferSlot.h:43-105/:113-171）；J2 Core 缺省容量（BufferQueueCore.cpp:113-115；BufferQueueDefs.h:27 + libs/ui/include/ui/BufferQueueDefs.h:25）；J3 dequeue 超额报错（Producer.cpp:295-307）；J4 queueBuffer 交卷协议（Producer.cpp:907-914/:930/:1017）；J5 WMS 缺省启用 BLAST（WMS.java:1239-1240）；J6 ViewRootImpl 默认分支（ViewRootImpl.java:8317-8326/:10947-10948）；J7 BLAST 三件套（BLASTBufferQueue.cpp:143-165）；J8 acquired 上限公式（SurfaceFlinger.cpp:7229-7236/:7265-7269）；J9 acquireNextBufferLocked 四段（:483-624）；J10 releaseBufferEndpoint（SurfaceComposerClient.cpp:1522-1523）；J11 缓存协议（:918-962/:998）；J12 在途 3 实测（q1/q4 (f:0,a:3)）；J13 SF 入口（SurfaceFlinger.cpp:4138；ISurfaceComposer.cpp:1100）；J14 latch fence 拒收（BufferLayer.cpp:458-474；BufferStateLayer.cpp:576-589）；J15 归还四站（:383-392/:403-463/:465-481/Consumer.cpp:442-508）；J16 HWC release fence slice（Surface.cpp:547-625/:696）；J17 BLASTSyncEngine 归属（WMS.java:1242；BLASTSyncEngine.java）；J18 客户端同步半边（BLASTBufferQueue.cpp:299-302/:394-401/:716-730/:755-784）；J19 aq:* 定性（ViewRootImpl.java:1409-1419；q2/q5）；J20 两层同名 slice 与 1016/1014 计数（Surface.cpp:637/:1164；Producer.cpp:383/:809；q1/q3）；J21 507=508−1 与悬置 dequeueBuffer（q1 首行；批09 q1 508 帧）；J22 提交段零线程切换（q4 嵌套形态）；J23 postAndWait 等待语义与 4.4 μs 重叠（DrawFrameTask.cpp:142-175；q4 端点）；J24 65.630 ms 空档非缓冲等待（q4 窗口计数）；J25 mQueue 停留 151,308 ns（q5 端点）；J26 两套编号按序对应（q4 全表）；J27 帧 395 在途 98.202 ms 且未触发等待（q1/q4 端点算术）；J28 积压丢帧与省流协议（Consumer.cpp:123-204/:160-172/:286-291）；J29 断连不对称（Consumer.cpp:535-554；Producer.cpp:1297 起）；J30 BBQSurface 三处覆盖（BLASTBufferQueue.cpp:836-887/:1055-1061/:891-903）；J31 Transaction 账目（SurfaceComposerClient.cpp:828-916/:971-996/:1539-1542）；J32 帧 397/398 提交时刻线（q4；§5.1 表）；J33 wait/merge slice 来源（ConsumerBase.cpp:425-478/:455；Fence.cpp:48-49/:90-92）；J34 SyncGroupReady 类目未查（BLASTSyncEngine.java:116-119）；J35 同步组活动未证（q4 (f,a) 判据不足）；J36 归还 binder 线程分布与监听器双回调（q1/q3；SurfaceComposerClient.cpp:311/:452；BLASTBufferQueue.cpp:169-177/:314-377；ViewRootImpl.java:893-894）；J37 计数成员名勘误（BLASTBufferQueue.cpp:50-59/:473/:706-707，无 mNumFramesPending）。

## 13. 术语引用与关联阅读

术语沿用 00 篇编号：#25 BLAST、#26 BufferQueue、#14 doFrame、#31 performTraversals、#13 vsync-app/vsync-sf、#27 jank 标签、#28 FrameTimeline。本篇新增指认的概念（bbq-wrapper 子层、mPendingRelease、mSyncedFrameNumbers、FenceMonitor、AsyncWorker、票据回调）在正文首现处已配行号，不另立编号。

- 上一站：[09-帧调度与RecyclerView布局.md](09-帧调度与RecyclerView布局.md)（doFrame 分账、postAndWait 归属）与批10（RT 调用点，写作中，本篇凡 RT 内部一律"见批10"）。
- 下一站：[12-SurfaceFlinger合成.md](12-SurfaceFlinger合成.md)（SF 收文后的全部）。
- 吸收来源：AAOS13_study/docs/md/Surface与BufferQueue.md（全量吸收，逐断言重验映射表见过程记录 §六；其中 SF 合成引擎两章与 HWUI 章分别留批12/批10，本篇只吸收与 libs/gui 直接相关的部分）。

收束：本篇把契约批11 走读主线（dequeue/acquire/queue/release 状态与回调、A12+ BLAST 单缓冲路径、setTransactionState→apply）按基准源码树行号闭环，并把契约预置的"应用帧提交可见性盲区"改写为已证——提交段每一站（dequeue→draw→queue→onFrameAvailable→acquire→apply）在本样本 trace 都有 slice 或计数轨，帧 396/395 的逐 ns 表与计数轨翻转可作为后续批12（SF 收文后）与批15（全时序归因）的对接锚点。遗留问题按 §11 清单移交：mMaxAcquiredBuffers 实值、binder 段时刻、SF 侧全程，分别在补采与批12 内解决。
