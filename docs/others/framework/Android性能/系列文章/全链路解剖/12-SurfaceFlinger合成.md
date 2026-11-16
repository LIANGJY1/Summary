# 12 · SurfaceFlinger 合成：事务落账、双周期拍点与四路输出上屏

SurfaceFlinger 接收事务，在显示调度中提交状态、锁存缓冲、选择合成方式并调用 HWC。

> 与契约作业单的实证勘误（沿用批07/批10 勘误口径，逐条附证据）：
> ① **作业单所列 `onMessageReceived(INVALIDATE/REFRESH)` 在 Android 13 基准源码树不存在。** A13 的 SF 主循环没有两条消息的分工：`MessageQueue::Handler::handleMessage` 一条消息内先 `commit` 后 `composite`（Scheduler/MessageQueue.cpp:45-57），`commit` 返回 false 即整帧止步（§5）。旧版 INVALIDATE/REFRESH 两消息模型属于 A11 及更早。
> ② 作业单"VSYNC-app/sf 双周期（mVsyncConfiguration/VsyncSchedule——grep 定位实际类名）"：实际类名为 `VsyncConfiguration`（实现 `PhaseOffsets`，Scheduler/VsyncConfiguration.cpp）、`VsyncSchedule`（Scheduler/VsyncSchedule.h，由 `Scheduler::createVsyncSchedule` 装配，Scheduler.cpp:135-137）、`DispSyncSource`（两个实例，名 "app"/"appSf"）、`VSyncDispatchTimerQueue`（定时队列，其 TimeKeeper 线程名 TimerDispatch）、`VSyncPredictor`（节拍模型）、`VsyncModulator`（early/late 档位切换）。计数轨名 "VSYNC-app"/"VSYNC-sf" 的产生点分别在 DispSyncSource.cpp:123 与 MessageQueue.h（§2）。
> ③ 作业单与任务书所写"surfaceflinger 进程（tid 684）"实测勘误：**tid 684 不是合成线程。** 本样本 SF 进程 pid=661；tid 661（线程名 surfaceflinger）才是 commit/composite 主线程，tid 684 的全部 slice 是 `TransactionTracing::addEntry`（Tracing/TransactionTracing.cpp:197，SF 内部事务回放缓冲线程，其数据不进本 perfetto 文件，§1）。本文全部 SF 主线程数值按 tid 661 引用。
> ④ 作业单"BufferStateLayer.cpp（A13 默认 layer 类型——与 BufferQueueLayer 的关系）"：A13 的 `createLayer` 对 `eFXSurfaceBufferQueue` 与 `eFXSurfaceBufferState` 两种旗**一律走 `createBufferStateLayer`**（SurfaceFlinger.cpp:4682-4697、:4786-4791），BufferQueueLayer 在主路径不再被创建；其差异残余在 `isBufferDue`——BufferStateLayer 恒真（BufferStateLayer.h:49），BufferQueueLayer 按时间戳（BufferQueueLayer.cpp:83）。吸收来源《一帧的生命周期.md》§2 的同名断言经本篇重验证成立（§3.5）。
> ⑤ 作业单"本虚拟机的 HWC 是什么——hwcomposer 是 drm/virtio-gpu 相关实现"：**实测定位为 `external/drm_hwcomposer`（DRM 原子提交实现）**，运行于 `/vendor/bin/hw/android.hardware.graphics.composer@2.4-service`（pid 659）。证据：该进程内唯一帧类 slice 是 `CommitFrame`，与 `DrmAtomicStateManager::CommitFrame` 的 `ATRACE_CALL`（DrmAtomicStateManager.cpp:43-44）在全树唯一对应（§4.5）；device/generic/goldfish-opengl 的 hwcomposer.ranchu 虽在 device 配置中列出（device/generic/goldfish/vendor.mk `hwcomposer.ranchu \`），其代码树无 CommitFrame 字样、无 "vsync" 线程名，与本 trace 证据不符。
> 术语沿用 00 篇术语表：#13 vsync-app/vsync-sf、#14 doFrame、#15 token、#25 BLAST、#26 BufferQueue、#27 jank 标签、#28 FrameTimeline。本篇新定义只定义一次：**双状态账本**（§3.3）、**事务屏障**（§3.4）、**client 合成／device 合成**（§4.2）、**sf 相位回调**（§2.3）、**投机执行**（§4.3）。acquire fence/release fence/present fence 三词的产生侧属批11/批13 走读范围，本篇只按消费侧使用，不重复下定义。

## 0. 本篇范围与上下游边界

批11 止于应用进程内 `Transaction::apply` 把缓冲与图层状态打成一笔 binder 调用（批11 走读主线，其事务到达机制详见批11）；批10 止于 RenderThread 的 `onFrameAvailable → acquireNextBufferLocked frame=396`（批10 过程记录 §三 q3 实测）。本篇从 SF 进程收到这笔调用的入口 `setTransactionState` 起步，走完：事务入队 → sf 相位回调唤醒 → commit 落账 → latch 锁存 → composite 合成 → present 上屏，到 `HwcPresentDisplay` 把 DRM 原子提交交给 composer HAL 为止。HAL 内部 `CommitFrame → drmModeAtomicCommit` 的 DRM/KMS 细节、vg-kit 计数对账与"物理呈现"的语义边界归批13（§6.2 只交付衔接点清单）。

本篇回答契约作业单的五个机制（编号沿用任务书）：

| 机制 | 结论 | 三态 | 落点 |
|---|---|---|---|
| ① VSYNC-app 与 VSYNC-sf 双周期 | app 相位与 sf 相位各差 1ms（本机配置反推），vsyncId 由 TokenManager 统一发号、按连接增发 | 已证（代码＋trace 计数轨） | §2、§7 |
| ② SF 收帧 | 缓冲以事务内 setBuffer 随 binder 到达写 mDrawingState；latch 才进合成侧；frame=396 全链逐 ns 闭合 | 已证（代码＋trace） | §3 |
| ③ 合成路径选择 | 每输出独立裁决：HWC validate 声明分工，client 层走 RenderEngine；本机 Display0 四层全走 client；HAL=drm_hwcomposer | 已证（代码＋trace）/输出身份部分未证 | §4 |
| ④ INVALIDATE 与 REFRESH 分工 | A13 无两消息模型；等价物是 commit 返回值与两类触发源 | 已证（代码＋trace 实例） | §5 |
| ⑤ present 完成→SF 帧计数 | SF/HAL 侧计数点五处；与 vg-kit 的衔接点 = DRM 原子提交 | 已证（代码）/计数对象终判留批13 | §6 |

## 1. 数据底座：SF 进程线程表与窗口 slice 总账

### 1.1 线程表（q1）

查询 `批12-q1-sf线程表.sql` 对本样本 trace 给出 surfaceflinger 进程（pid 661）与 composer HAL 进程（pid 659）的线程表：

```text
pid 661 /system/bin/surfaceflinger
  tid 661  surfaceflinger     ← commit/composite 主线程（本篇全部主线程数值的轨）
  tid 684  surfaceflinger     ← TransactionTracing::addEntry（事务回放缓冲，§1.2）
  tid 685  RenderEngine       ← client 合成的 GPU 执行线程（§4.4）
  tid 746  HwcAsyncWorker     ← HWC validate 的异步执行线程（§4.3）
  tid 748  TimerDispatch      ← VSyncDispatchTimerQueue 的 TimeKeeper alarm 线程（§2.3）
  tid 749  app                ← "app" EventThread（应用 vsync 的分发线程，§2.4）
  tid 751  RegionSampling / tid 752 RegSampIdle / tid 764、766（窗口内零 slice）
  tid 741  HwBinder:661_1     ← composer HAL 回调（vsync）入口（§2.1）
  tid 682/1388/1486/1841 binder:661_1/3/4/5 ← setTransactionState 等 binder 入口（§3.1）
  tid 27726 GPU completion
pid 659 /vendor/bin/hw/android.hardware.graphics.composer@2.4-service
  tid 659 composer@2.4-se     ← HAL 主线程（CommitFrame 所在，§4.5）
  tid 742/743 vsync           ← 两根 vsync 线程（§4.5 定位为 drm_hwcomposer VSyncWorker）
```

两个无 slice 线程（tid 764/766）的名称与角色**未证**（零打点，推断其一为 "appSf" EventThread——SurfaceFlinger.cpp:3451-3457 装配、其 InterceptVSyncsCallback 只在拦截器开启时工作——另一为 RegionSampling 空闲伴生线程；不外推）。

### 1.2 tid 684 的身份与事务回放缓冲不可见性

tid 684 的身份由 q9b（`批12-q9b-tid684.sql`）判定：该线程窗口内 slice 只有一种名字 `addEntry`（如 rel 6,791,517,115 区多条），与 `TransactionTracing::addEntry`（Tracing/TransactionTracing.cpp:197）唯一对应。TransactionTracing 是 SF 自己的事务回放环形缓冲（`mTransactionTracing->addQueuedTransaction` 在 setTransactionState :4185-4187、`addCommittedTransactions` 在 applyTransactions :3916-3918 打账），数据写入 SF 进程内的 tracing 缓冲而非 perfetto——q8（`批12-q8-事务表.sql`）验证：`__intrinsic_surfaceflinger_transactions` 表 **0 行**（同族 `__intrinsic_surfaceflinger_layers_snapshot` 亦 0 行）。**含义**：本 trace 无法从 perfetto 表直接读事务清单，事务侧证据全部来自 slice 名与计数轨。**已证**（查询＋代码对应）；SF 事务回放（LayerTracing/TransactionTracing 文件级导出）**盲区**（采集未启用文件导出）。

### 1.3 窗口 slice 总账与主线程节奏表

窗口 [6.7s, 6.9s] 内（q12，`批12-q12-类名计数.sql`）SF 进程合成类 slice 计数：`commit *` 7、`composite *` 6、latchBuffers 7、present 27、presentAndGetReleaseFences 16、HwcPresentDisplay 16、composeSurfaces 21、prepareFrameAsync 6、prepareFrame 15。**SF 合成类 slice 在本 trace 完全可见**——任务书"SF 相关 slice 可见性查证"的结论为"有"，后续各节逐点引用；与批10 实测的 DrawFrames 155812（rel 6,842,332,265，dur 3,361,884，tid 2079）的衔接在 §3.7 逐 ns 闭合。

主线程节奏全表（q6，`批12-q6-sf节奏.sql`，窗口 6.7~6.9s，单位 ns）：

| commit slice（起点，dur） | composite slice（起点，dur） | 语义 |
|---|---|---|
| commit 155785（6,725,091,495，874,347） | composite 155785（6,725,973,034，15,835,000） | 有事可合成 |
| commit 155793（6,742,143,342，903,884） | composite 155793（6,743,055,034，17,801,077） | 有事可合成 |
| commit 155794（6,760,921,419，458,730） | （无 composite slice） | commit 返回 false（§5） |
| commit 155802（6,775,173,726，1,555,769） | composite 155802（6,776,740,842，15,235,077） | 有事可合成 |
| commit 155810（6,792,019,957，1,147,577） | composite 155810（6,793,175,380，21,449,962） | 有事可合成（S 窗口内，§8） |
| （6,792,019,957 → 6,858,454,957 无 commit） | （无） | SF 无排程即不醒（§2.4） |
| commit 155818（6,858,454,957，999,461，vsyncIn 15.41ms） | composite 155818（6,859,462,380，16,924,500） | frame=396 的合成（§3） |
| commit 155826（6,892,061,649，1,609,962） | composite 155826（6,893,680,418，15,482,462） | 有事可合成 |

相邻 composite 的 vsyncId 增量：155785→155793（+8，间隔 17,082,000 ns）、155793→155802（+9，33,685,808 ns）、155802→155810（+8，16,434,538 ns）——token 增量与间隔不成比例，根源在 §2.5。

### 1.4 窗口内 per-thread slice 计数（q11）

窗口 [6.7s, 6.9s] 内 SF 进程各线程的 slice 总量（q11，`批12-q11-窗口线程统计.sql`）：RenderEngine 832（client 合成的 GPU 段，含大量 SkiaGL 子 slice）、主线程 720、"app" EventThread 47、TimerDispatch 40、GPU completion 22、HwcAsyncWorker 18、HwBinder:661_1 18（HAL 回调）、binder:661_5 16（事务入口）、tid 684（TransactionTracing）5、binder:661_1 3、binder:661_4 2、RegionSampling 2。线程轨分工与 §2~§6 的代码归属一一对应；RenderEngine 计数量级（主线程的 1.16 倍）来自每帧 fill/op 级打点，不改变"合成决策在主线程"的结构。**已证**（查询）。

## 2. 机制①：VSYNC-app 与 VSYNC-sf 双周期

VSync 是用于协调显示节奏的时序信号。应用侧和 SurfaceFlinger 侧使用不同的调度时点，分别安排应用产帧与系统合成；二者的区别是工作时机，不能简单理解为两块独立屏幕的刷新信号。

### 2.1 周期的源头：HWC vsync 进口

`onComposerHalVsync`（SurfaceFlinger.cpp:1856）是硬件 vsync 进入 SF 的唯一入口：

```cpp
// SurfaceFlinger.cpp:1866-1888（摘）
    ATRACE_FORMAT("onComposerHalVsync%s", tracePeriod.c_str());

    Mutex::Autolock lock(mStateLock);
    const auto displayId = getHwComposer().toPhysicalDisplayId(hwcDisplayId);
    if (displayId) {
        const auto token = getPhysicalDisplayTokenLocked(*displayId);
        const auto display = getDisplayDeviceLocked(token);
        display->onVsync(timestamp);
    }

    if (!getHwComposer().onVsync(hwcDisplayId, timestamp)) {
        return;
    }
    const bool isActiveDisplay = ...
    if (!isActiveDisplay) {
        // For now, we don't do anything with non active display vsyncs.
        return;
    }
    bool periodFlushed = false;
    mScheduler->addResyncSample(timestamp, vsyncPeriod, &periodFlushed);
```

走读：HAL 经 HwBinder 把（displayId, timestamp, 周期）送进来；`HWComposer::onVsync` 内做同戳去重（DisplayHardware/HWComposer.cpp:162 区，源码学习副本的批注块在位）后返回真才继续；只有活动屏的样本送 `addResyncSample` 喂 VSyncPredictor 模型（`periodFlushed` 非零时另触发 VsyncModulator 档位回落，:1889-1891）。trace 实测（q2）：tid 741（HwBinder:661_1）在 rel 6,707,505,880 有 `onComposerHalVsync(16673882)` slice——括号内 16,673,882 是 HAL 报告的 vsync 周期（本机 60Hz 档；该值的 HAL 侧来源在 §4.5）。**已证**（代码＋trace slice 名逐字）。

### 2.2 双计数轨：VSYNC-app 与 VSYNC-sf 的产生点

两条计数轨各自有产生点。app 侧在 `DispSyncSource`（术语 #13 的锚点，00 篇引 DispSyncSource.cpp:123）：

```cpp
// Scheduler/DispSyncSource.cpp:122-128（摘）
      : mName(name),
        mValue(base::StringPrintf("VSYNC-%s", name), 0),
        mTraceVsync(traceVsync),
        mVsyncOnLabel(base::StringPrintf("VsyncOn-%s", name)),
        mVSyncTracker(vSyncTracker),
        mWorkDuration(base::StringPrintf("VsyncWorkDuration-%s", name), workDuration),
```

回调到达时翻转（DispSyncSource.cpp:180-182 `mValue = (mValue + 1) % 2`，仅在 mTraceVsync 时打）。sf 侧在 `MessageQueue`（MessageQueue.cpp:100-103）：

```cpp
// Scheduler/MessageQueue.cpp:100-115（摘）
void MessageQueue::vsyncCallback(nsecs_t vsyncTime, nsecs_t targetWakeupTime, nsecs_t readyTime) {
    ATRACE_CALL();
    // Trace VSYNC-sf
    mVsync.value = (mVsync.value + 1) % 2;
    ...
    const auto vsyncId = mVsync.tokenManager->generateTokenForPredictions(
            {targetWakeupTime, readyTime, vsyncTime});

    mHandler->dispatchFrame(vsyncId, vsyncTime);
```

轨名 "VSYNC-sf" 声明在 MessageQueue.h 的 TracedOrdinal 成员。q7（`批12-q7-vsync_sf.sql`）实测窗口 6.7~6.9s 的脉冲：VSYNC-sf 翻 1 于 6,724,980,919、翻 0 于 6,741,745,226、翻 1 于 6,758,425,611、翻 0 于 6,775,011,534、翻 1 于 6,791,660,034，之后直接跳到 6,858,326,649（翻 0）——**6.792~6.858 s 间 VSYNC-sf 无脉冲**；VSYNC-app 同窗在 6,841,683,880（翻 1）与 6,858,395,957（翻 0）之间亦无脉冲。无脉冲的含义在 §2.4 解释（按需启动，非连续运行）。00 篇术语 #13 标注"本样本轨可见性未证"，本篇升级为**已证**（轨存在且按需出脉冲）。

### 2.3 相位从哪来：VsyncConfiguration 的偏移→工作时长换算

三个 workDuration 计数轨在本窗口可见（q5，`批12-q5-计数轨.sql`）：`VsyncWorkDuration-app` = 16,673,882、`VsyncWorkDuration-appSf` = 16,673,882、`VsyncWorkDuration-sf` = 15,673,882（单位 ns）。数值由 `PhaseOffsets` 按 sysprop 相位偏移换算（Scheduler/VsyncConfiguration.cpp:88-91，缺省 `vsync_event_phase_offset_ns(1000000)`、`vsync_sf_event_phase_offset_ns(1000000)`）：

```cpp
// Scheduler/VsyncConfiguration.cpp:146-158（摘）
std::chrono::nanoseconds sfOffsetToDuration(nsecs_t sfOffset, nsecs_t vsyncDuration) {
    return std::chrono::nanoseconds(vsyncDuration - sfOffset);
}
std::chrono::nanoseconds appOffsetToDuration(nsecs_t appOffset, nsecs_t sfOffset,
                                             nsecs_t vsyncDuration) {
    auto duration = vsyncDuration + (sfOffset - appOffset);
    if (duration < vsyncDuration) {
        duration += vsyncDuration;
    }
```

走读：sfWorkDuration = 周期 − sfOffset；appWorkDuration = 周期 + (sfOffset − appOffset)，不足一周期再补一周期。本机实测 VsyncWorkDuration-sf=15,673,882、-app=16,673,882，周期 16,673,882（§2.1 的 HAL 报告值）反推 **appOffset = sfOffset = 1,000,000 ns**——恰为 sysprop 缺省 1ms 档。**已证**（代码＋计数轨算术闭合）。

两个 EventThread 连接的装配在 `initScheduler`（SurfaceFlinger.cpp:3446-3460）：

```cpp
// SurfaceFlinger.cpp:3446-3460（摘）
    mAppConnectionHandle =
            mScheduler->createConnection("app", mFrameTimeline->getTokenManager(),
                                         /*workDuration=*/configs.late.appWorkDuration,
                                         /*readyDuration=*/configs.late.sfWorkDuration,
                                         impl::EventThread::InterceptVSyncsCallback());
    mSfConnectionHandle =
            mScheduler->createConnection("appSf", mFrameTimeline->getTokenManager(),
                                         /*workDuration=*/std::chrono::nanoseconds(vsyncPeriod),
                                         /*readyDuration=*/configs.late.sfWorkDuration, ...
    mScheduler->initVsync(mScheduler->getVsyncDispatch(), *mFrameTimeline->getTokenManager(),
                          configs.late.sfWorkDuration);
```

"app" 连接的 workDuration=appWorkDuration(16,673,882)、readyDuration=sfWorkDuration(15,673,882)；"appSf" 连接 workDuration=整周期（其 vsync 只供拦截器记账）；SF 主线程自己的 MessageQueue 注册名 "sf"（MessageQueue.cpp:128），workDuration=sfWorkDuration、readyDuration=0（MessageQueue.cpp:182-184）。定时核 `VSyncDispatchTimerQueueEntry::update`（VSyncDispatchTimerQueue.cpp:126-141）给出唤醒公式：

```cpp
// Scheduler/VSyncDispatchTimerQueue.cpp:136-141（摘）
    const auto earliestReadyBy = now + mScheduleTiming.workDuration + mScheduleTiming.readyDuration;
    const auto earliestVsync = std::max(earliestReadyBy, mScheduleTiming.earliestVsync);

    const auto nextVsyncTime = tracker.nextAnticipatedVSyncTimeFrom(earliestVsync);
    const auto nextReadyTime = nextVsyncTime - mScheduleTiming.readyDuration;
    const auto nextWakeupTime = nextReadyTime - mScheduleTiming.workDuration;
```

**相位结论（机制①的定量部分）**：目标 vsync 取"now+workDuration+readyDuration 之后的第一个模型预测 vsync"。app 的 "app" 连接回调时刻 = 目标 vsync − 32,347,764 ns（16,673,882+15,673,882），按 16.67ms 周期落在目标 vsync 前两周期处＝上一硬件 vsync 后约 1ms；SF 主线程的 sf 相位回调时刻 = 目标 vsync − 15,673,882 ns＝目标 vsync 的前一硬件 vsync 后约 1ms。q7 同窗的 "app alarm in 14371us; VSYNC in 46719us" slice（rel 6,710,522,572，tid 749）两值之差 46,719−14,371=32,348μs 与 32,347,764 ns 逐位吻合；该 slice 的产生点是 `rearmTimerSkippingUpdateFor` 的 ATRACE_NAME（VSyncDispatchTimerQueue.cpp:254-257），"sf alarm in ..." 同型 slice 出现在 binder 线程（如 6,845,136,418 后续的 6,873,865 预测——见 §3.1 的 scheduleFrame 链）。**已证**。

### 2.3a TimerDispatch 线程与 alarm 纪律

全部 sf/app 定时回调都在 TimerDispatch 线程（tid 748）上由内核 alarm 驱动：`TimeKeeper` 每次迭代打 `TimerIteration #<序号>` slice（Scheduler/src/Timer.cpp:191，q2 实测 rel 6,724,916,726 的 `TimerIteration #1391`），随后 `VSyncDispatchTimerQueue::timerCallback`（VSyncDispatchTimerQueue.cpp:266-303）锁内收集到点条目（`lagAllowance` 容忍迟到，:287-288）、锁内重排下一次 alarm（:296 rearmTimer）、锁外逐个执行回调（:299-302）——MessageQueue::vsyncCallback 与 DispSyncSource 的 CallbackRepeater::callback 都在此线程起跑，随后各自派发（sf：sendMessage 到主线程；app：EventThread 入待发队列）。源码学习副本的批注（VSyncDispatchTimerQueue.cpp:283 区）已述"先重排后执行"纪律，与基准源码树一致。

### 2.4 双周期不是双常开：按需启动与 EventThread 三态

VSYNC-sf 在 6.792~6.858 s 无脉冲的原因是 sf 相位回调按需武装：`MessageQueue::scheduleFrame`（MessageQueue.cpp:168-185）把注册排到下一个 sf 相位，触发源是事务入队（§3.2 的 setTransactionFlags→scheduleCommit）、背压重排与 HWC 请求（scheduleComposite，SurfaceFlinger.cpp:1834-1837）；无事可做时没有任何 sf 排程。app 侧同理由 EventThread 线程主循环按连接需求切换三态（Idle/VSync/SyntheticVSync）并开关源：

```cpp
// Scheduler/EventThread.cpp:486-502（摘）
        State nextState;
        if (mVSyncState && vsyncRequested) {
            nextState = mVSyncState->synthetic ? State::SyntheticVSync : State::VSync;
        } else {
            ALOGW_IF(!mVSyncState, "Ignoring VSYNC request while display is disconnected");
            nextState = State::Idle;
        }

        if (mState != nextState) {
            if (mState == State::VSync) {
                mVSyncSource->setVSyncEnabled(false);
            } else if (nextState == State::VSync) {
                mVSyncSource->setVSyncEnabled(true);
            }
            mState = nextState;
        }
```

无人请求 vsync 时进入 Idle，`setVSyncEnabled(false)` 经 CallbackRepeater::stop 停掉自续约（DispSyncSource.cpp:67-72、:96-98 回调内查 mStarted）。**机制①"SF 在没有新 buffer 到达时是否合成"的答案**：不合成也不醒——本样本 6,814,625,342（composite 155810 结束）到 6,858,454,957（commit 155818 起）之间主线程无任何 commit/composite slice，直到 rel 6,845,032,265 的事务（§3.1）触发 scheduleFrame 才排程下一次唤醒。**已证**（窗口计数 q6＋q7 双向）。

EventThread 主循环另有一道自愈：VSync 态等事件超时 1000ms 伪造 vsync 并 dump 源信息（EventThread.cpp:512-528），SyntheticVSync 态以 16ms 周期喂客户端（:514-515）——本窗口未触发（无 "Faking VSYNC" 日志证据，属代码机制陈述）。

### 2.4a 应用请求 vsync 的通路（与本样本的两条 requestNextVsync slice）

应用侧 `scheduleVsync` 的对端是 SF 进程内的 binder 对象 `EventThreadConnection`（批09 的 scheduleVsyncLocked 经此跨进程）：

```cpp
// Scheduler/EventThread.cpp:195-198（摘，study 批12 X4 块在位）
binder::Status EventThreadConnection::requestNextVsync() {
    ATRACE_CALL();
    mEventThread->requestNextVsync(this);
    return binder::Status::ok();
}
```

`EventThread::requestNextVsync`（:337-350）只翻连接的 vsyncRequest 标志（None→Single）并 `mCondition.notify_all()` 唤醒分发线程；先经 `connection->resyncCallback` 触发一次模型重同步（去抖 kIgnoreDelay=ms2ns(750)，Scheduler.cpp:444，study Scheduler.cpp:450 批注块在位）。分发在 threadMain 内 `dispatchEvent`（:630 起）对每个消费者 `postEvent` 写各自 BitTube（:481-484、:641），应用进程的 DisplayEventReceiver 排干后走批09 已证的 onVsync→doFrame 链。trace 实测（q4/q2）：tid 1841（binder:661_5）rel 6,832,935,457 的 `requestNextVsync`（5,133,077）与 tid 1486（binder:661_4）rel 6,844,505,072 的同名 slice（15,346）——两笔来自不同客户端连接的 vsync 请求；tid 749（app EventThread）在 6,710,522,572 有 "app alarm in 14371us; VSYNC in 46719us" 打点（§2.3），即 app 连接武装时 VSyncDispatch 的重排 ATRACE。**已证**（代码＋slice）。

### 2.5 vsyncId 的分配：一个进程级计数器，不是硬件节拍计数

app 的 doFrame 序号（批09/批10 的 155803/155812）与 SF 的 composite 序号（155785/155810/155818）出自同一发号器——`TokenManager::generateTokenForPredictions`（FrameTimeline/FrameTimeline.cpp:743-751）：

```cpp
// FrameTimeline/FrameTimeline.cpp:743-751（摘）
int64_t TokenManager::generateTokenForPredictions(TimelineItem&& predictions) {
    ATRACE_CALL();
    std::scoped_lock lock(mMutex);
    while (mPredictions.size() >= kMaxTokens) {
        mPredictions.erase(mPredictions.begin());
    }
    const int64_t assignedToken = mCurrentToken++;
    mPredictions[assignedToken] = predictions;
```

调用方与增量：SF 主线程的 sf 相位回调每次调 1 次（MessageQueue.cpp:111-112）；"app" EventThread 每向一个连接分发一次 vsync 事件，`generateFrameTimeline` 生成 6 条候选时间线、各调一次 `generateToken`（EventThread.cpp:596-628）：

```cpp
// Scheduler/EventThread.cpp:605-628（摘）
void EventThread::generateFrameTimeline(VsyncEventData& outVsyncEventData, nsecs_t frameInterval,
                                        nsecs_t timestamp,
                                        nsecs_t preferredExpectedPresentationTime,
                                        nsecs_t preferredDeadlineTimestamp) const {
    // Add 1 to ensure the preferredFrameTimelineIndex entry (when multiplier == 0) is included.
    for (int64_t multiplier = -VsyncEventData::kFrameTimelinesLength + 1, currentIndex = 0;
         currentIndex < VsyncEventData::kFrameTimelinesLength; multiplier++) {
        nsecs_t deadlineTimestamp = preferredDeadlineTimestamp + multiplier * frameInterval;
        if (deadlineTimestamp > timestamp) {
            ...
            outVsyncEventData.frameTimelines[currentIndex] =
                    {.vsyncId =
                             generateToken(timestamp, deadlineTimestamp, expectedPresentationTime),
```

因此 token 增长速率 = 1 + 6×(本事件消费者数) 每 vsync 事件——**token 不是连续帧序号**的机制根源（批09 实测 doFrame 155803→155812 起点差 65.630 ms 而 token 差 9）。本篇补一条 SF 侧实测：composite 155802→155810 起点差 16,434,538 ns 而 token 差 8（q6）。应用侧 Choreographer 取 preferredFrameTimelineIndex 的 vsyncId 作帧号（批09 已证），SF 侧 composite 取 sf 回调生成的那个（§2.2 代码）。**已证**（代码＋两侧实测）。

### 2.6 档位调制：VsyncModulator 在两相位之外的第二自由度

early/earlyGpu/late 三组 workDuration 配置（VsyncConfiguration.cpp:160-198 的 VsyncConfigSet）之间由 `VsyncModulator` 切换（SurfaceFlinger.cpp:3481-3490 `setVsyncConfig` 把档位的 app/sf workDuration 推给两个连接与 MessageQueue）：

```cpp
// Scheduler/VsyncModulator.cpp:49-56（摘，study 批注块 :51 区在位）
VsyncModulator::VsyncConfigOpt VsyncModulator::setTransactionSchedule(TransactionSchedule schedule,
                                                                      const sp<IBinder>& applyToken) {
```

触发与回落（源码学习副本的批注块四块已述，本篇只列接口面）：`setTransactionSchedule`（事务带 eEarlyWakeupStart/End 时提前，:3701-3703 经 modulateVsync 调用）、`onRefreshRateChangeInitiated/Completed`（切档期提前，:1890 调用）、`onDisplayRefresh(usedGpuComposition)`（composite 尾调用，:2286，GPU 合成帧维持 earlyGpu 档）。**后果**：档位切换改变 app/sf 的唤醒时刻，帧间相位不是常数——本 trace 未观测到档位切换事件（无 "adjusted" 后缀的 commit slice、无 early 档特征），本节只陈述机制，不给样本归因。**已证**（代码）；本 trace 是否发生切档**未证**。

## 3. 机制②：SF 收帧——从 setTransactionState 到 latch

SurfaceFlinger 是 Android 的系统合成服务。它接收图层状态和缓冲相关的事务，更新图层状态并在合适时机锁存缓冲，为后续合成和显示提交做准备。

### 3.1 入口：setTransactionState（Binder 线程）

frame=396 的缓冲以事务形式到达。批10/批11 实测链（RenderThread tid 2079）：queueBuffer 6,844,165,188（1,116,077）→ onFrameAvailable 6,844,347,803（509,539）→ acquireBuffer 6,844,366,418 → acquireNextBufferLocked frame=396 6,844,405,072（442,000，止 6,844,847,072）。SF 侧（q4，`批12-q4-binder_tx.sql`）：

```text
rel 6,845,032,265  dur 146,038  binder:661_5  setTransactionState
rel 6,845,136,418  dur  34,885  binder:661_5  └ scheduleFrame
```

`setTransactionState` 起点 − `acquireNextBufferLocked` 止点 = 6,845,032,265 − 6,844,847,072 = **185,193 ns**（跨进程 binder 单程＋BLAST 侧 apply 组装，方向为应用→SF；两数值均为实测端点，差值为本文派生）。入口函数（SurfaceFlinger.cpp:4138-4196，源码学习副本的批注块 :4237 区在位）做四件事：权限剥离（:4160-4164，非特权调用方剥 eEarlyWakeup*）、打包 TransactionState（postTime/originPid/originUid，:4166-4178）、对含缓冲事务计在途数（:4181-4183）、入队：

```cpp
// SurfaceFlinger.cpp:4181-4188（摘）
    // Check for incoming buffer updates and increment the pending buffer count.
    state.traverseStatesWithBuffers([&](const layer_state_t& state) {
        mBufferCountTracker.increment(state.surface->localBinder());
    });

    if (mTransactionTracing) {
        mTransactionTracing->addQueuedTransaction(state);
    }
    queueTransaction(state);
```

`queueTransaction`（:4089-4114）把 TransactionState 压入 `mTransactionQueue`（:4102），打 `ATRACE_INT("TransactionQueue", ...)`（:4103），再 `setTransactionFlags(eTransactionFlushNeeded, schedule, applyToken, frameHint)`（:4113）；`setTransactionFlags`（:3701-3708）在标志翻转成功时调 `scheduleCommit`（:1826-1832）→ `mScheduler->scheduleFrame()` → MessageQueue::scheduleFrame → VSyncDispatch 重排。trace 的嵌套 `scheduleFrame`（6,845,136,418，34,885）就是这条链的打点（MessageQueue.cpp:169 ATRACE_CALL）。**要点：setTransactionState 在 binder 线程上直接排程了 sf 相位回调**——事务到达与 sf 节拍在这条链上会合。**已证**。

### 3.2 commit：sf 相位回调后的第一段

`MessageQueue::Handler::handleMessage`（MessageQueue.cpp:45-57，study 批12 X1 扩展块在位）在主线程先调 `compositor.commit(frameTime, mVsyncId, mExpectedVsyncTime)`，返回 true 才 composite、sample。commit 的 slice 名即 SurfaceFlinger.cpp:2017-2018 的 `ATRACE_FORMAT("%s %" PRId64 " vsyncIn %.2fms%s", ...)`；`vsyncIn` = mExpectedPresentTime − now（:2013-2016）。mExpectedPresentTime 一帧只算一次（:2003-2008，expectedVsyncTime 可用时直取，否则按 DisplayStatInfo 推算并可能带 "(adjusted)" 后缀——本窗口 commit slice 名均无该后缀）。commit 155818 起点 6,858,454,957、vsyncIn 15.41ms ⇒ **本帧 mExpectedPresentTime ≈ 6,873,864,957**（=起点+15.41ms；与 sf 相位回调公式"目标 vsync − 15,673,882"给出的 6,874,128,839 预测值差 264μs，属 VSyncPredictor 预测、ATRACE 打印时刻与模型微调的合成偏差——两值均为推算）。**已证**（公式＋slice 字段）；推算值本身标**未证**。

commit 段内与背压相关的两步（SurfaceFlinger.cpp:2023-2086）：`previousFramePending(graceTimeMs)` 用上一帧 present fence 判 framePending——fence 选取走 `previousFrameFence()`（:1960-1966，距本帧期望上屏不足一个周期取 [0] 否则 [1]，study 批12 X6 块在位），未信号时 `fence->wait(graceTimeMs)` 最多等 1ms（graceTimeMs 取 1：`mPropagateBackpressureClientComposition || !mHadClientComposition`，:2023-2024）；frameMissed 分 HWC/GPU 两路计数（:2046-2062，TracedOrdinal "PrevFramePending"/"PrevFrameMissed"/"PrevHwcFrameMissed"/"PrevGpuFrameMissed"）；`mSetActiveModePending` 期间 fence 未信号直接重排退场（:2066-2079）；HWC 慢且 GPU 不慢时 scheduleCommit 重排让路（:2081-2086）。trace 实测（q3）：commit 155818 内 `previousFramePending` 6,858,463,688（27,500 ns）——一次 wait(1ms) 内返回的探测（fence 已信号或快速确认）。**已证**。

### 3.2a commit 内的 FrameTimeline 埋点（术语 #28 的 SF 侧用法）

commit 在事务处理前后向 FrameTimeline 记账（批09 的 Expected/Actual Timeline 在 SF 侧的产生点）：

```cpp
// SurfaceFlinger.cpp:2129-2129（摘）
        mFrameTimeline->setSfWakeUp(vsyncId, frameTime, Fps::fromPeriodNsecs(stats.vsyncPeriod));
```

trace 对应（q3，composite 155818 一帧）：`setSfWakeUp`（6,858,588,980，7,192）在 commit 内 flushTransactionQueues 之前；事务侧的 `createSurfaceFrameForToken`（6,858,899,572，8,308，setBuffer :428 经 setFrameTimelineVsyncForBufferTransaction → createSurfaceFrameForBuffer 建立，Layer.cpp:1313-1317）；latch 侧的 `addSurfaceFrame`（6,859,150,611，6,000，Layer.cpp:1291 的 presented 登记）；composite 收尾的 `setSfPresent`（6,875,710,149，177,000，§6.1）。四个打点把"应用帧 token ↔ SF 帧 token"在同一数据结构里配对——jank 标签（#27）的原料在此汇齐，但 **本 trace 无 FrameTimeline 表数据**（q8 的 0 行结论同样适用于 expected/actual frame timeline——解析器未产出该表），标签值不可从本样本读取。**已证**（slice×代码对应）；jank 标签值**盲区**。

### 3.3 落账：flushTransactionQueues 与双状态账本

**双状态账本（本篇定义）**：每个图层持 `mCurrentState`（事务直接写入的账）与 `mDrawingState`（合成读取的账）两份状态；事务应用写前者，`commitTransactions`（:2666 起）把前者整体过到后者：

```cpp
// SurfaceFlinger.cpp:3526-3528（摘）
    mDrawingState = mCurrentState;
    // clear the "changed" flags in current state
    mCurrentState.colorMatrixChanged = false;
```

`flushTransactionQueues`（:3796-3894）在 commit 内执行（:2132-2135：`clearTransactionFlags(eTransactionFlushNeeded)` 成功才扫队列；含 `commitCreatedLayers` :7396 的新建层并入）：

- 先扫 `mPendingTransactionQueues`（此前未就绪、按 applyToken 分桶的旧账，:3814-3816），再扫 `mTransactionQueue`（:3821-3863）；
- 每笔的就绪性交给 `transactionIsReadyToBeApplied`（§3.4），判定结果打 `ATRACE_INT("TransactionReadiness", ...)`（:3756/:3840）；
- 未就绪的推入 `mPendingTransactionQueues[applyToken]`（:3845），就绪的进本帧清单并登记 `bufferLayersReadyToPresent[surface] = frameNumber`（:3847-3858）；
- 屏障链多轮重扫直到计数不降（:3875-3881，源码学习副本的批注块 :3955 区在位）；兜底轮 `flushUnsignaledPendingTransactionQueues` 允许未信号锁存重扫（:3886-3889）；
- 最后 `applyTransactions`（:3896-3920）逐笔 `applyTransactionState` 落 mCurrentState，同步事务的 latch 攒进 `mTransactionCommittedSignals`（:3910-3913）。

**事务屏障（本篇定义）**：BLAST 同步事务可声明 `setBufferHasBarrier`＋barrierFrameNumber——带屏障的缓冲不得早于某帧号 latch；`transactionIsReadyToBeApplied` 对 `layer->getDrawingState().frameNumber < barrierFrameNumber` 且本帧不会应用该帧号的事务返回 `NotReadyBarrier`（:4038-4047）。**已证**（代码）；本 trace 窗口内未出现 NotReadyBarrier 分支（q3 无对应 ATRACE_NAME），分支触发**未观测**。

### 3.4 就绪裁决：transactionIsReadyToBeApplied 的五道判

```cpp
// SurfaceFlinger.cpp:3994-4020（摘）
auto SurfaceFlinger::transactionIsReadyToBeApplied(TransactionState& transaction, ...
    ATRACE_FORMAT("transactionIsReadyToBeApplied vsyncId: %" PRId64, info.vsyncId);
    const nsecs_t expectedPresentTime = mExpectedPresentTime.load();
    // Do not present if the desiredPresentTime has not passed unless it is more than one second
    // in the future. We ignore timestamps more than 1 second in the future for stability reasons.
    if (!isAutoTimestamp && desiredPresentTime >= expectedPresentTime &&
        desiredPresentTime < expectedPresentTime + s2ns(1)) {
        ATRACE_NAME("not current");
        return TransactionReadiness::NotReady;
    }
    if (!mScheduler->isVsyncValid(expectedPresentTime, originUid)) { ...
    if (isAutoTimestamp && frameIsEarly(expectedPresentTime, info.vsyncId)) {
        ATRACE_NAME("frameIsEarly");
        return TransactionReadiness::NotReady;
```

五道判：①desiredPresentTime 未到（限 1 秒内）；②uid 帧率节流不匹配（isVsyncValid）；③`frameIsEarly`（:3927-3941）——事务 vsyncId 的预测上屏时刻 ≥ 本周期 mExpectedPresentTime 加半个周期即"太早"，留给后续周期；④屏障未齐（NotReadyBarrier）；⑤acquire fence 未信号且 `allowLatchUnsignaled` 不成立 → NotReady（:4049-4073，排队超 4 秒发 `onTransactionQueueStalled` 告警 :4062-4068）。`frameIsEarly` 依赖 TokenManager 按 vsyncId 反查预测（:3933-3935），这是事务携带的 vsyncId（应用侧 doFrame 的 token，批09）在 SF 侧的第一个用途；第二个用途在 BufferStateLayer::setBuffer 的 SurfaceFrame 登记（§3.5）。`shouldLatchUnsignaled` 的四道闸（:3948-3991，源码学习副本的批注块 :4037 区在位）：单层、简单缓冲更新、本帧首笔、默认合成节奏档。**已证**（代码）。

trace 配对（q3）：commit 155818 内 `transactionIsReadyToBeApplied vsyncId: 155812`（6,858,607,572，67,193）——**事务的 vsyncId 155812 与应用 doFrame 155812 同号**，即批09/批10 的那一帧；随后 `com.yadea.launcher/com.yadea.launcher.function.applist.AppListActivity#245 allowLatchUnsignaled=false`（6,858,642,842，26,000）与 `wait`（6,858,650,265，12,961）——第⑤判分支的打点（本笔未走未信号锁存）。**已证**（slice 名逐字＋行号对应）。

### 3.5 setBuffer：缓冲落进 BufferStateLayer 的 drawing 态

`applyTransactionState` 对每个 ComposerState 调 `setClientStateLocked`（:4223-4224），缓冲最终落 `BufferStateLayer::setBuffer`（BufferStateLayer.cpp:342-443，源码学习副本的批注块 :350 区在位）：

```cpp
// BufferStateLayer.cpp:388-396（摘）
    mDrawingState.frameNumber = frameNumber;
    mDrawingState.releaseBufferListener = bufferData.releaseBufferListener;
    mDrawingState.buffer = std::move(buffer);
    mDrawingState.clientCacheId = bufferData.cachedBuffer;

    mDrawingState.acquireFence = bufferData.flags.test(BufferData::BufferDataChange::fenceChanged)
            ? bufferData.acquireFence
            : Fence::NO_FENCE;
    mDrawingState.acquireFenceTime = std::make_unique<FenceTime>(mDrawingState.acquireFence);
```

要点：缓冲与 acquire fence 一起写 **mDrawingState**（BufferStateLayer 的缓冲走 drawing 态账本，几何属性走 mCurrentState，`doTransaction` 在 latch 时统一过账）；帧号 = bufferData.frameNumber（应用侧 buffer 的 frameNumber，本帧=396）；若旧帧注定被顶掉当场归还（:358-386，callReleaseBufferCallback＋decrementPendingBufferCount，EARLY_RELEASE 分支 :378-385）；presentTime 按 `info.vsyncId` 的预测登记（:415-423 `getPredictionsForToken`），并 `recordLayerHistory`（:426）供刷新率决策。**已证**（代码）。trace 配对：`setBuffer` 6,858,771,188（149,884）＋`createSurfaceFrameForToken` 6,858,899,572（8,308）在 commit 155818 内（q3）。**frame=396 与层 #245（AppListActivity 的 BLAST 层）的绑定由本段代码＋§3.6 latch 层级共同证**。

A13 默认层类型（作业单第④条勘误的正文落点）：

```cpp
// SurfaceFlinger.cpp:4682-4697（摘）
status_t SurfaceFlinger::createLayer(LayerCreationArgs& args, sp<IBinder>* outHandle, ...
    switch (args.flags & ISurfaceComposerClient::eFXSurfaceMask) {
        case ISurfaceComposerClient::eFXSurfaceBufferQueue:
        case ISurfaceComposerClient::eFXSurfaceBufferState: {
            result = createBufferStateLayer(args, outHandle, &layer);
            std::atomic<int32_t>* pendingBufferCounter = layer->getPendingBufferCounter();
            if (pendingBufferCounter) {
                std::string counterName = layer->getPendingBufferCounterName();
                mBufferCountTracker.add((*outHandle)->localBinder(), counterName,
                                        pendingBufferCounter);
            }
        } break;
```

BufferQueueLayer 与 BufferStateLayer 的差异残余在 `isBufferDue`：BufferStateLayer 恒真（BufferStateLayer.h:49 `bool isBufferDue(nsecs_t) const override { return true; }`）——状态层没有"到点"概念，事务就绪即上；BufferQueueLayer 按时间戳（BufferQueueLayer.cpp:83）。**已证**。

### 3.6 latch：从 drawing 态搬进合成侧

`latchBuffers`（SurfaceFlinger.cpp:3562-3647，源码学习副本的批注块 :3626 区在位）遍历图层树：几何/属性变更的层先 `doTransaction` 落实（:3582-3588），`hasReadyFrame()` 且 `shouldPresentNow` 的层收进 `mLayersWithQueuedFrames`（:3590-3597），离屏层强制 latch＋release 防生产者饿死（:3604-3610），逐层 `latchBuffer`（:3617-3623），帧已排队但本轮没锁存就 `scheduleCommit` 重排（:3631-3633），返回 `!mLayersWithQueuedFrames.empty() && newDataLatched`（:3646）。单层锁存 `BufferLayer::latchBuffer`（BufferLayer.cpp:458-526）：

```cpp
// BufferLayer.cpp:468-482（摘）
    // If the head buffer's acquire fence hasn't signaled yet, return and
    // try again later
    if (!fenceHasSignaled()) {
        ATRACE_NAME("!fenceHasSignaled()");
        mFlinger->onLayerUpdate();
        return false;
    }

    // Capture the old state of the layer for comparisons later
    const State& s(getDrawingState());
    const bool oldOpacity = isOpaque(s);

    BufferInfo oldBufferInfo = mBufferInfo;

    status_t err = updateTexImage(recomputeVisibleRegions, latchTime, expectedPresentTime);
```

`BufferStateLayer::fenceHasSignaled`（BufferStateLayer.cpp:576-589）：档位非 Disabled 时直接报已信号（等待责任后移给 RenderEngine 或 HWC，§7 问题③）；Disabled 档才真查并给 TimeStats 记 LateAcquire。锁存三步 `updateTexImage → updateActiveBuffer → updateFrameNumber`：updateTexImage 盖 latchTime（:648-652，transaction complete 回调的数据源）、打 TimeStats/FrameTracer 双路（:654-664）、未上屏 SurfaceFrame 标 presented（:666-676）；updateActiveBuffer 把 drawing 态缓冲/fence/帧号搬进 `mBufferInfo` 并把旧缓冲记入 `mPreviousReleaseCallbackId`（:688-705，合成完成后 releaseBufferCallback 按它定位归还哪个缓冲）。**已证**（代码）。trace 配对（q3，commit 155818 内）：`latchBuffers` 6,859,008,226（186,231）→ `doTransaction` 6,859,077,726（8,116）→ `latchBuffer` 6,859,132,649（54,769）→ `addSurfaceFrame` 6,859,150,611（6,000）；随后 `updateLayerGeometry` 6,859,225,765（24,230）、`chooseRefreshRateForContent` 6,859,257,611（105,923）、`updateInputFlinger` 6,859,436,457。**已证**。

### 3.7 全链逐 ns 闭合（frame=396，机制②的最终配对；衔接 DrawFrames 155812）

```text
DrawFrames 155812 (RT tid 2079)     起 6,842,332,265  dur 3,361,884   （批10 q3 实测）
queueBuffer(RT tid 2079)            起 6,844,165,188  dur 1,116,077   （批10 q3/批11 q4）
onFrameAvailable                    起 6,844,347,803  dur   509,539   （同上）
acquireNextBufferLocked frame=396   起 6,844,405,072  dur   442,000   （同上；止 6,844,847,072）
setTransactionState(binder:661_5)   起 6,845,032,265  dur   146,038   （批12 q4；与上者止点差 185,193）
scheduleFrame（嵌套）               起 6,845,136,418  dur    34,885   （批12 q4）
—— SF 主线程静默：等待 sf 相位回调（6,845.2→6,858.5，§2.4）——
commit 155818                       起 6,858,454,957  dur   999,461   （批12 q6）
transactionIsReadyToBeApplied(155812) 起 6,858,607,572 dur    67,193  （批12 q3）
setBuffer                           起 6,858,771,188  dur   149,884   （批12 q3）
commitTransactions                  起 6,858,977,765  dur    24,692   （批12 q3；内层 doCommitTransactions 8,115）
latchBuffers / latchBuffer          起 6,859,008,226 / 6,859,132,649   （批12 q3）
composite 155818                    起 6,859,462,380  dur 16,924,500   （批12 q6）
```

应用帧 token（doFrame 155812）与 SF 合成 token（composite 155818）不同号、事务携带应用 token（vsyncId: 155812），三者关系由 §2.5 的发号器统一解释。**已证**（全部端点实测，算术为派生）。

## 4. 机制③：合成路径选择与 present

图层合成是把多个图层组织成显示结果的过程。SurfaceFlinger 与硬件合成器 HWC 协作，按设备能力选择由系统侧或显示硬件承担相应合成工作，再提交显示；具体分工需要结合实际设备判断。

### 4.1 composite 的帧上下文与 CompositionEngine::present 两轮结构

`SurfaceFlinger::composite`（:2186-2304，源码学习副本的批注块 :2219/:2228 区在位）组装 refreshArgs：

```cpp
// SurfaceFlinger.cpp:2232-2244（摘）
    const auto expectedPresentTime = mExpectedPresentTime.load();
    const auto prevVsyncTime = mScheduler->getPreviousVsyncFrom(expectedPresentTime);
    const auto hwcMinWorkDuration = mVsyncConfiguration->getCurrentConfigs().hwcMinWorkDuration;
    refreshArgs.earliestPresentTime = prevVsyncTime - hwcMinWorkDuration;
    refreshArgs.previousPresentFence = mPreviousPresentFences[0].fenceTime;
    refreshArgs.scheduledFrameTime = mScheduler->getScheduledFrameTime();
    refreshArgs.expectedPresentTime = expectedPresentTime;

    // Store the present time just before calling to the composition engine so we could notify
    // the scheduler.
    const auto presentTime = systemTime();

    mCompositionEngine->present(refreshArgs);
```

`earliestPresentTime`（上一 vsync 时刻 − hwcMinWorkDuration）就是 §4.6 "wait for earliest present time" 的等待目标，用于阻止 HWC 提前上屏。`mCompositionEngine->present`（CompositionEngine.cpp:91-113，源码学习副本的批注块 :105 区在位）：

```cpp
// CompositionEngine/src/CompositionEngine.cpp:95-112（摘）
    preComposition(args);
    {
        // latchedLayers is used to track the set of front-end layer state that
        // has been latched across all outputs for the prepare step, and is not
        // needed for anything else.
        LayerFESet latchedLayers;
        for (const auto& output : args.outputs) {
            output->prepare(args, latchedLayers);
        }
    }
    updateLayerStateFromFE(args);
    for (const auto& output : args.outputs) {
        output->present(args);
    }
```

第一轮对每个输出 prepare（rebuildLayerStacks 可见性/覆盖计算），第二轮逐输出 present。trace 结构完全同构（q13，`批12-q13-155810骨架.sql`）：composite 155810 → present(1)（6,793,501,842，20,214,615）内含 prepare(2)（4 个输出，窗口 dur>50μs 过滤后可见 1 个 101,923）与四段 present(2)，收尾 postComposition(1)（6,813,757,111，780,654）→ setSfPresent(2)（180,577）。**本机有四个输出**：A（8,215,385 ns）、B（954,500）、C（838,884）、D（9,792,807）。composite 尾段还做 per-output 合成策略记账（mHadClientComposition/mHadDeviceComposition，:2266-2277）与 `modulateVsync(&VsyncModulator::onDisplayRefresh, usedGpuComposition)`（:2286，GPU 合成帧驱动 early/late 档位切换）。**已证**。

### 4.2 Output::present 管线、beginFrame 门与 client 合成／device 合成定义

**client 合成／device 合成（本篇定义）**：某输出的某层若由 HWC 硬件叠加，称 device 合成；若由 SF 进程内 RenderEngine 画进 framebuffer 后备缓冲、再整体作为一层送 HWC，称 client 合成。单输出管线（Output.cpp:429-452，源码学习副本的批注块 :433 区在位）：

```cpp
// CompositionEngine/src/Output.cpp:440-451（摘）
    beginFrame();

    GpuCompositionResult result;
    const bool predictCompositionStrategy = canPredictCompositionStrategy(refreshArgs);
    if (predictCompositionStrategy) {
        result = prepareFrameAsync(refreshArgs);
    } else {
        prepareFrame();
    }

    devOptRepaintFlash(refreshArgs);
    finishFrame(refreshArgs, std::move(result));
    postFramebuffer();
    renderCachedSets(refreshArgs);
```

`beginFrame`（:966-993）有一道重组门：`mMustRecompose = dirty && !(empty && wasEmpty)`——无脏区、或"无可见层且上帧也无"时跳过重组（对应 B/C 两个小输出 prepare/present 仍跑但内容不变的形态）。**已证**（代码）；本窗口四输出都产出了 present slice，beginFrame 门在 slice 形态上的体现（skipping 无独立 slice）**未逐帧核对**。

### 4.3 分工裁决与投机执行：prepareFrameAsync 与 HwcAsyncWorker

分工由 validate 裁决：`chooseCompositionStrategy → getDeviceCompositionChanges → HwcValidateDisplay/HwcPresentOrValidateDisplay`（HWC 侧代码 §4.6）。同步路 `prepareFrame`（Output.cpp:995-1014，源码学习副本的批注块 :1028 区在位）串行做 validate——B/C 两输出即此形态：

```cpp
// CompositionEngine/src/Output.cpp:1004-1013（摘）
    std::optional<android::HWComposer::DeviceRequestedChanges> changes;
    bool success = chooseCompositionStrategy(&changes);
    resetCompositionStrategy();
    outputState.strategyPrediction = CompositionStrategyPredictionState::DISABLED;
    outputState.previousDeviceRequestedChanges = changes;
    outputState.previousDeviceRequestedSuccess = success;
    if (success) {
        applyCompositionStrategy(changes);
    }
    finishPrepareFrame();
```

trace（composite 155810 的输出 B）：prepareFrame(3) 6,802,194,765（432,307）→ chooseCompositionStrategy(4) 410,423 → getDeviceCompositionChanges(5) 395,077 → HwcPresentOrValidateDisplay(6) 357,654——三层嵌套与代码链逐一对应（q3）。Display0 走 **投机执行（本篇定义：按上一帧策略先行 apply 并直接做 client 合成，HWC validate 异步并行，命中则免串行等待）**——`prepareFrameAsync`（Output.cpp:1022-1067）：

```cpp
// CompositionEngine/src/Output.cpp:1028-1042（摘）
    resetCompositionStrategy();
    auto hwcResult = chooseCompositionStrategyAsync(&changes);
    if (state.previousDeviceRequestedSuccess) {
        applyCompositionStrategy(previousChanges);
    }
    finishPrepareFrame();

    base::unique_fd bufferFence;
    std::shared_ptr<renderengine::ExternalTexture> buffer;
    updateProtectedContentState();
    const bool dequeueSucceeded = dequeueRenderBuffer(&bufferFence, &buffer);
    GpuCompositionResult compositionResult;
    if (dequeueSucceeded) {
        std::optional<base::unique_fd> optFd =
                composeSurfaces(Region::INVALID_REGION, refreshArgs, buffer, bufferFence);
```

validate 的异步投递走 `mHwComposerAsyncWorker->send(...)`（:1016-1020），在 HwcAsyncWorker 线程（tid 746）执行；主线程做完投机 client 合成后 `hwcResult.get()` 收结果，`predictionSucceeded = dequeueSucceeded && changes == previousChanges`（:1049-1060），命中打 `ATRACE_NAME("CompositionStrategyPredictionHit")`（:1062）并复用已画的 fence，失手打 Miss 并 reset 重做。trace（q3，composite 155810 的输出 A）：prepareFrameAsync(3) 6,794,085,726（3,917,846）内含 dequeueBuffer(4) 153,885（再嵌 126,154/42,730 两层——FramebufferSurface 的 ANativeWindow dequeue，§4.4）与 composeSurfaces(4) 3,634,731；HwcAsyncWorker 线程 chooseCompositionStrategy(0) 916,192 → getDeviceCompositionChanges(1) 897,231 → HwcValidateDisplay(2) 855,077；`CompositionStrategyPredictionHit`（6,797,990,765，6,038）在 prepareFrameAsync 收尾（其父 slice 止点 6,798,003,572 内）。投机命中后 finishFrame 不再重画（§4.5）。**已证**（层级＋代码）。

### 4.4 client 段：composeSurfaces 的缓存判定与 RenderEngine 调用

`composeSurfaces`（Output.cpp:1175 起，study 批12 X9 块在位）先判 `hasClientComposition`（假则空手而归——B/C 两输出的 composeSurfaces 18,192/21,115 ns 即此早退形态）；真路径组装 DisplaySettings/LayerSettings 后过请求缓存：

```cpp
// CompositionEngine/src/Output.cpp:1239-1251（摘）
    if (mClientCompositionRequestCache) {
        if (mClientCompositionRequestCache->exists(tex->getBuffer()->getId(),
                                                   clientCompositionDisplay,
                                                   clientCompositionLayers)) {
            ATRACE_NAME("ClientCompositionCacheHit");
            outputCompositionState.reusedClientComposition = true;
            setExpensiveRenderingExpected(false);
            // b/239944175 pass the fence associated with the buffer.
            return base::unique_fd(std::move(fd));
        }
        ATRACE_NAME("ClientCompositionCacheMiss");
        mClientCompositionRequestCache->add(tex->getBuffer()->getId(), clientCompositionDisplay,
                                            clientCompositionLayers);
    }
```

随后 `renderEngine.drawLayers(clientCompositionDisplay, clientRenderEngineLayers, tex, useFramebufferCache, fd)`（:1287-1291）——该 future 由 RenderEngine 线程（tid 685）执行，即 trace 的 `REThreaded::drawLayers`/`SkiaGL::drawLayers`。本帧实测（composite 155810，输出 A；q3）：

```text
REThreaded::drawLayers   6,794,954,995  dur 2,978,000   （tid 685）
  SkiaGL::drawLayers     6,795,066,149  dur 2,937,539
    DrawLayer: SurfaceView[...MainActivity](BLAST)#119   304,884
    DrawLayer: ...AppListActivity#245                    372,154
      └ BlurRegion 85,076 + drawBlurRegion 30,962
    DrawLayer: StatusBar#103                             178,577
    DrawLayer: NavigationBar#102                         173,462
  flush surface          6,796,380,880  dur 1,151,385（OpsTask onPrepare×7/onExecute×9＋FillRectOp×18）
  flush / st_context_flush / st_flush / Submit(sync=false)
                         315,039 / 171,038 / 158,731 / 9,538
```

四层为何全走 client：AppListActivity#245 携带背景模糊（BlurRegion/drawBlurRegion slice 在其 DrawLayer 内），模糊属 HWC 不接的强制 client 项（吸收来源《一帧的生命周期.md》§6 同方向断言经 §4.6 的 validate 结果印证）；其余层是否被强制由 drm_hwcomposer 的 backend 决策（§4.5 的 HwcDisplay 侧），本 trace 的可见结果是四层全在 client 清单。`ClientCompositionCacheMiss`（6,794,691,495，23,039）为缓存判定的分支打点（:1249）。`expensiveRenderingExpected`（:1258-1268）对模糊帧置位（disableExpensiveRendering 的对端，q2 窗口内 6,710,313,072 有一次该 slice）。**已证**（slice 层级＋代码路径）；"模糊是唯一强制原因"**未证**（backend 判据未逐条核对）。

### 4.5 finishFrame／framebuffer 出队入队与 postFramebuffer

投机命中时 finishFrame 直接复用 result.fence；否则 `dequeueRenderBuffer`（Output.cpp:1156-1173）取 framebuffer 后备缓冲后重画（源码学习副本的批注块 :1155 区在位）：

```cpp
// CompositionEngine/src/Output.cpp:1117-1128（摘）
        // Repaint the framebuffer (if needed), getting the optional fence for when the
        // composition completes.
        optReadyFence = composeSurfaces(Region::INVALID_REGION, refreshArgs, buffer, bufferFence);
    }
    if (!optReadyFence) {
        return;
    }
    ...
    // swap buffers (presentation)
    mRenderSurface->queueBuffer(std::move(*optReadyFence));
```

framebuffer 的出队入队在 `RenderSurface`（CompositionEngine/src/RenderSurface.cpp:150-248）：dequeueBuffer 经 ANativeWindow（即 FramebufferSurface 的 producer 端，BufferQueue 概念 #26 的消费侧形态），按缓冲 id 查纹理缓存复用包装（:178-198，源码学习副本的批注块 :152/:157 区在位）；queueBuffer 带 GPU 完成 fence 入队（:201-230）。trace 对应：输出 A 的 queueBuffer(4) 6,798,033,457（272,192，内层 114,923，伴随 `FramebufferSurface: 1` 与 `addAndGetFrameTimestamps` 6,154/7,961）；输出 D 的 dequeueBuffer 2,600,577（内嵌 2,556,654，期间 "HWC release fence 1471 has signaled" 6,154 于 6,806,576,842）与 queueBuffer 2,849,808（伴随 "Trace GPU completion fence 1470" 58,077）。**已证**。

`postFramebuffer`（Output.cpp:1440-1493，源码学习副本的批注块 :1507 区在位）：

```cpp
// CompositionEngine/src/Output.cpp:1448-1454（摘）
    auto& outputState = editState();
    outputState.dirtyRegion.clear();
    mRenderSurface->flip();

    auto frame = presentAndGetFrameFences();

    mRenderSurface->onPresentDisplayCompleted();
```

`presentAndGetFrameFences`（:1519-1525，study 批12 X10 块在位）本层只收 client target 的 acquire fence；HWC 的 present fence 与逐层 release fence 由 `Display::presentAndGetFrameFences`（CompositionEngine/src/Display.cpp:363-364）经 `HWComposer::presentAndGetReleaseFences`（DisplayHardware/HWComposer.cpp:508-546）补齐：

```cpp
// DisplayHardware/HWComposer.cpp:526-544（摘）
    const bool waitForEarliestPresent =
            !mComposer->isSupported(Hwc2::Composer::OptionalFeature::ExpectedPresentTime) &&
            previousPresentFence->getSignalTime() != Fence::SIGNAL_TIME_PENDING;

    if (waitForEarliestPresent) {
        ATRACE_NAME("wait for earliest present time");
        std::this_thread::sleep_until(earliestPresentTime);
    }

    auto error = hwcDisplay->present(&displayData.lastPresentFence);
    ...
    std::unordered_map<HWC2::Layer*, sp<Fence>> releaseFences;
    error = hwcDisplay->getReleaseFences(&releaseFences);
```

`hwcDisplay->present` 经 HidlComposerHal.cpp:550 的 `ATRACE_NAME("HwcPresentDisplay")` 进入 HAL binder 调用（本机 composer@2.4 = HIDL 2.4，走 HidlComposerHal 而非 AidlComposerHal——同型打点在 AidlComposerHal.cpp:519 亦存在，进程名 2.4-service 判定走 HIDL 侧）。trace：输出 A 的 presentAndGetReleaseFences(4) 6,798,394,688（3,379,884）内含 wait for earliest present time（6,154）＋HwcPresentDisplay(5)（3,334,692）；composite 155818 的对应值为 PAGRF 6,864,101,226（4,400,539）内含 HwcPresentDisplay 6,864,121,072（4,355,885，止 6,868,476,957）。**HwcPresentDisplay 的 3.3~4.4 ms 全部消耗在 HAL binder 往返＋HAL 内部 DRM 提交**（§4.6）。postFramebuffer 收尾把逐层 release fence 与 client target acquire fence 合并后 `onLayerDisplayed` 上抛（:1456-1489，BufferLayer 的 release 链对端，批11 的 releaseBufferCallback 消费）。**已证**。

### 4.6 HWC 侧：getDeviceCompositionChanges 的快慢路与 drm_hwcomposer 定位

HWC 交互的两条路（DisplayHardware/HWComposer.cpp:399-490，源码学习副本的批注块 :420 区在位）：`canSkipValidate`（:424-440）要求无 client 合成、且（composer 支持 ExpectedPresentTime 或确定不提前上屏）——快路 `presentOrValidate`（:446-459，state==1 即已 present，slice 名 HwcPresentOrValidateDisplay 来自 HidlComposerHal.cpp:653）；否则慢路 `validate`（:462）→ getChangedCompositionTypes/getRequests/getClientTargetProperty（:469-482）→ acceptChanges（:486），slice 名 HwcValidateDisplay（HidlComposerHal.cpp:636）。trace：输出 A 走慢路（有 client 合成必 validate，:426-428），B/C 走快路。**已证**。

**HWC 是谁（本节核心交付）**——三个证据环（q10/q10b，`批12-q10-hwc线程.sql`/`批12-q10b-hwc窗口.sql`）：

1. **进程与线程**：pid 659 = `/vendor/bin/hw/android.hardware.graphics.composer@2.4-service`，主线程 composer@2.4-se，两根 "vsync" 线程（tid 742/743）。
2. **slice 名唯一对应**：该进程窗口内唯一的帧类 slice 是 `CommitFrame`（全 trace 504 条；composite 155818 的 HwcPresentDisplay 窗口内一条：rel 6,864,745,880，dur 3,429,923）。基准源码树全树 grep `CommitFrame`，图形域唯一命中 `external/drm_hwcomposer/drm/DrmAtomicStateManager.cpp:43`：

```cpp
// external/drm_hwcomposer/drm/DrmAtomicStateManager.cpp:43-53（摘）
auto DrmAtomicStateManager::CommitFrame(AtomicCommitArgs &args) -> int {
  ATRACE_CALL();
  if (args.active && *args.active == active_frame_state_.crtc_active_state) {
    /* Don't set the same state twice */
    args.active.reset();
  }
  if (!args.HasInputs()) {
    /* nothing to do */
    return 0;
  }
```

  其提交动作是 `drmModeAtomicCommit(drm->GetFd(), pset.get(), flags, drm)`（:136，flags=DRM_MODE_ATOMIC_ALLOW_MODESET，test_only 时加 TEST_ONLY）——**guest 内核 DRM 的原子提交**，批13 的 virtio-gpu 走读从此接手。调用链：`HwcDisplay::PresentDisplay`（HwcDisplay.cpp:511-541，`++total_stats_.total_frames_` :518、`++frame_no_` :536，BadLayer 时 present_fence=-1 无提交 :526-530）→ `CreateComposition`（:410-490，按 z 序组 DrmKmsPlan、client 层落 client_layer_）→ `ExecuteAtomicCommit`（:488）→ `CommitFrame`。HAL 侧的层归属仲裁在 `HwcDisplay::ValidateDisplay`（HwcDisplay.cpp:670-676）委托 `backend_->ValidateDisplay`——`backend_` 是 drm_hwcomposer 的可插拔后端（backend/ 目录），决定每层报 Device 还是 Client，SF 收到的 validate 结果（哪些层 HWC 接）即出自这里；输出 A 四层全报 Client 的直接原因在这一层（后端判据未逐条核对，§9 边界 5）。**已证**（接口链）；后端判据**未证**。
3. **vsync 线程名对应**："vsync" 线程名与 `VSyncWorker::VSyncWorker() : Worker("vsync", HAL_PRIORITY_URGENT_DISPLAY)`（drm/VSyncWorker.cpp:32）一致；其 Routine 先试 `drmWaitVBlank`（:130-133），失败（virtio-gpu 无真实 vblank）走 `SyntheticWaitVBlank` 按显示模式刷新率 clock_nanosleep 合成节拍（:75-101）——两根 vsync 线程即本机两个 DRM 管线的合成 vsync 源，§2.1 的 onComposerHalVsync(16673882) 的 16,673,882 ns 周期即由此按 60Hz 模式合成后经 HAL 回调送入。

排除项：device/generic/goldfish-opengl/system/hwc2（hwcomposer.ranchu，device/generic/goldfish/vendor.mk 列出）的代码树 grep 无 CommitFrame、无 "vsync" 线程名（其 VsyncThread 类命名不同），且其 composer 分支按属性选择（Device.cpp:79-91 NoOp/Client/Guest/Host）——与本 trace 证据不符，判定本机 HAL 不是该实现。**HWC 路径定位结论：composer HAL = external/drm_hwcomposer，经 DRM 原子提交落到 guest 内核 DRM（virtio-gpu）**。**已证**（trace slice×代码唯一对应）；"产品配置为何实际加载 drm_hwcomposer"（ro.hardware.hwcomposer 运行时值）**盲区**（设备离线，属性不在 trace 内）。

### 4.7 四输出的身份与帧时延构成

四个输出的身份判定（结构证据，无 display id 字段）：

| 输出 | present(2) dur（155810 内） | 结构特征 | 身份判定 | 三态 |
|---|---|---|---|---|
| A | 8,215,385 | prepareFrameAsync＋HwcValidateDisplay（异步）＋FramebufferSurface dequeue/queue＋client 四层（含 #245 模糊） | Display0（本篇全部应用层的载体） | 未证（结构推断；"含全部应用层"强支持） |
| B | 954,500 | prepareFrame＋HwcPresentOrValidateDisplay＋composeSurfaces 18,192（早退） | Display1 或 2（vg-kit 计 0 的无内容屏） | 未证 |
| C | 838,884 | 同 B | Display1 或 2 | 未证 |
| D | 9,792,807 | prepareFrame＋finishFrame 内 dequeue 2,600,577＋client 四层重画＋queueBuffer 2,849,808 | 虚拟屏（录屏链路；`debug.sf.enable_hwc_vds` 缺省 false 时虚拟屏恒 client，SurfaceFlinger.cpp:806-808） | 虚拟屏恒 client 机制已证（代码）＋setSwapInterval(0)（:2884-2886）；D=录屏虚拟屏身份未证 |

**帧时延构成（机制四问④的定量面）**：client 合成（A/D）＝framebuffer dequeue＋GPU 重画（RE 线程 2,978,000/3,199,692）＋framebuffer queueBuffer（272,192/2,849,808）＋"等最早上屏时刻＋HWC present"（PAGRF 3,379,884）；device 合成（B/C）＝presentOrValidate 一次 HWC binder（410,423/335,577）＋present fence 等待（PAGRF 331,576/269,192）——本 trace 中小一个数量级。**已证**（同帧四输出对照，单帧样本；跨帧分布**未证**——本篇只做机制分级，不做帧率统计）。

composite 155818（frame=396 那帧）的四输出同构分账（q14，`批12-q14-155818骨架.sql`）：

| 输出 | present(2) 起点，dur | 内部构成（dur>50μs） |
|---|---|---|
| A | 6,859,898,688，8,910,961 | prepareFrameAsync 3,750,961（dequeueBuffer 126,693＋composeSurfaces 3,545,885）→ finishFrame 328,539（queueBuffer 235,962）→ postFramebuffer 4,711,923（PAGRF 4,400,539→HwcPresentDisplay 4,355,885） |
| B | 6,868,816,303，871,346 | prepareFrame 368,962（chooseCompositionStrategy 349,385）→ finishFrame 59,154 → postFramebuffer 342,539（PAGRF 314,961） |
| C | 6,869,695,111，779,384 | prepareFrame 315,961（chooseCompositionStrategy 294,654）→ finishFrame 61,692 → postFramebuffer 293,884（PAGRF 264,615） |
| D | 6,870,481,534，5,177,923 | finishFrame 4,992,385（dequeueBuffer 431,346＋composeSurfaces 3,454,385＋queueBuffer 1,048,846）→ postFramebuffer 74,307 |

postComposition(1) 6,875,685,572（641,500，setSfPresent 177,000）收尾后，主线程紧接 `setVsyncEnabled`（6,876,408,072，322,462）——composite 尾段 modulateVsync/needsAnotherUpdate 链的动作之一。两帧（155810/155818）四输出形态完全同构，输出身份判据在两帧上自洽。**已证**（q13/q14 双帧层级）。

## 5. 机制④：INVALIDATE 与 REFRESH 的分工（A13 形态）

A13 无两条消息（作业单勘误①）。等价的分工载体是 `commit` 的返回值（MessageQueue.cpp:51-55）：

```cpp
// Scheduler/MessageQueue.cpp:51-56（摘）
    if (!compositor.commit(frameTime, mVsyncId, mExpectedVsyncTime)) {
        return;
    }
    compositor.composite(frameTime, mVsyncId);
    compositor.sample();
```

commit 返回 `mustComposite`（SurfaceFlinger.cpp:2183：`mustComposite && CC_LIKELY(mBootStage != BootStage::BOOTLOADER)`），其取值 = 事务落账（shouldCommit，:2137-2147：`getTransactionFlags() & ~eTransactionFlushNeeded` 或 needsTraversal）或 latch 到新数据（:2148）。两类触发源决定"要不要醒来"：

- `scheduleCommit`（:1826-1832）——事务入队（§3.1）、背压重排（:2083）、latch 重排（:3632）、`needsAnotherUpdate`（:2297-2299，preComposition 有层要求下一帧）；
- `scheduleComposite`（:1834-1837，mMustComposite=true＋scheduleCommit）——HWC 请求（onComposerHalRefresh :1936-1939、模式切换 timeline refreshRequired :1926-1928）、`scheduleRepaint`（:1839-1842，几何脏）。

**只收事务不合成**的实例：commit 155794（6,760,921,419，458,730）后无 composite slice（q6）——该轮没有 shouldCommit 也没有新 latch，且其 commitTransactions slice 亦缺（commit 内层 :2140 的 shouldCommit=false）。**"等下一拍再合成"**的实例：latchBuffers 收了帧但 shouldPresentNow 不过时 `scheduleCommit` 重排（:3631-3633）。**已证**（代码＋两个窗口实例）。

## 6. 机制⑤：present 完成→SF 侧的帧计数

### 6.1 postComposition：fence 归档、CompositorTiming 与回收链启动

composite 收尾调 `postComposition`（:2262，函数 :2435 起，源码学习副本的批注块 :2491 区在位）：

```cpp
// SurfaceFlinger.cpp:2451-2481（摘）
    mPreviousPresentFences[1] = mPreviousPresentFences[0];
    mPreviousPresentFences[0].fence =
            display ? getHwComposer().getPresentFence(display->getPhysicalId()) : Fence::NO_FENCE;
    mPreviousPresentFences[0].fenceTime =
            std::make_shared<FenceTime>(mPreviousPresentFences[0].fence);

    nsecs_t now = systemTime();

    // Set presentation information before calling Layer::releasePendingBuffer, such that jank
    // information from previous' frame classification is already available when sending jank info
    // to clients, so they get jank classification as early as possible.
    mFrameTimeline->setSfPresent(/* sfPresentTime */ now, mPreviousPresentFences[0].fenceTime,
                                 glCompositionDoneFenceTime);
    ...
        layer->releasePendingBuffer(/*dequeueReadyTime*/ now);
```

归档双 fence 进 mPreviousPresentFences 双槽（下一帧 commit 的 framePending 判据，§3.2）；`mFrameTimeline->setSfPresent` 先于归还调用（代码注释明示为让 jank 分类早于回调——术语 #28 的 SF 侧记账点）；遍历 `mLayersWithQueuedFrames` 逐层 `releasePendingBuffer`（BufferStateLayer 的 releaseBufferCallback 对端，批11 的 releaseBufferCallback 消费侧）；另有 `updateCompositorTiming`（:2470，用 CompositionEngine::getLastFrameRefreshTimestamp 更新合成节拍模型，供下一帧期望呈现时间计算）。trace：composite 155810 的 postComposition(1) 6,813,757,111（780,654）内 setSfPresent(2) 180,577＋4 条 wait/merge；composite 155818 的同段在 6,876.3 区（q3）。**已证**。

### 6.2 SF 侧计数点清单与 vg-kit 衔接边界

SF 侧（含其 HAL）每帧会推进的计数点，全列如下（机制⑤的落点）：

| 计数点 | 位置 | 本 trace 可见性 |
|---|---|---|
| composite slice 名的 vsyncId | SurfaceFlinger.cpp:2188 | 已证（composite 155785/155793/155802/155810/155818/155826） |
| commit slice 名的 vsyncId | SurfaceFlinger.cpp:2017 | 已证（同上 commit 序列） |
| PrevFramePending/PrevFrameMissed/PrevHwcFrameMissed/PrevGpuFrameMissed | SurfaceFlinger.cpp:2027-2049 | TracedOrdinal 计数轨打点在位；本 trace 未逐轨取值（未证） |
| HAL 内 `total_frames_`/`frame_no_` | drm_hwcomposer HwcDisplay.cpp:518/:536 | 进程内变量，不进 trace（盲区） |
| HAL 的 CommitFrame 次数 | DrmAtomicStateManager.cpp:44 | 已证（全 trace 504 条；无内容可提交时 CommitFrame 早退不产生 drm 提交，:48-53；BadLayer 同理，HwcDisplay.cpp:526-530） |
| DRM 原子提交→virtio-gpu 2D 命令 | drmModeAtomicCommit（:136）之后 | 批13 走读（盲区边界） |

**与 vg-kit Display0 序列 16/39/34/30/31/40/35 的衔接点**（契约 :77）：vg-kit 计数对象是 scanout 相关计数（00 篇 #23），其 guest 侧上游即 DRM 原子提交——每次 CommitFrame 若携带 compositionplan 会经 drmModeAtomicCommit 走一次 scanout 更新路径；无输入/BadLayer 时无提交。**SF 侧能提供的最强口径是"CommitFrame 次数（每 DRM 管线）"与"composite 次数（SF 帧）"，两者都不等价于物理新帧率**（同一 SF 帧四输出各有 present，27 次 present 对 16 次 HwcPresentDisplay——部分输出部分帧无 device 提交）；vg-kit 序列与哪个计数对齐属计数对象问题，**留批13 终判**（本篇只交付衔接点清单，不做对齐声明）。**已证**（衔接点存在性）／计数对象**未证**。

### 6.3 虚拟屏与 DisplayDevice 侧的两个定制（作业单"虚拟屏/DisplayDevice"项）

虚拟屏在 SF 侧有两处专用逻辑：其一，消费速度不可控故强制异步队列（SurfaceFlinger.cpp:2884-2886，源码学习副本的批注块 :2945 区在位）：

```cpp
// SurfaceFlinger.cpp:2881-2886（摘）
    // Make sure that composition can never be stalled by a virtual display
    // consumer that isn't processing buffers fast enough. We have to do this
    // here, in case the display is composed entirely by HWC.
    if (state.isVirtual()) {
        nativeWindow->setSwapInterval(nativeWindow.get(), 0);
    }
```

其二，虚拟屏恒视为上电（:2892-2894 `initialPowerMode = hal::PowerMode::ON`）且在 `debug.sf.enable_hwc_vds` 缺省 false 时不进 HWC（:806-808 `enableHalVirtualDisplays(true)` 仅属性开启时调用）——对应 §4.7 输出 D 的恒 client 形态。DisplayDevice 构造四步（DisplayDevice.cpp:64 区，源码学习副本的批注块在位）与 vsync 周期查询降级链（:270 区）均已有批注，本篇只引用不重复。**已证**（代码）。

## 7. 机制四问汇总（问题①~⑤逐条三态）

**① SF 在没有新 buffer 到达时是否合成？** 规则：commit/composite 只在 sf 相位回调里执行，回调由 scheduleFrame 按需武装（MessageQueue.cpp:168-185）；谁设置：事务入队（queueTransaction:4113→setTransactionFlags:3705-3707）、背压重排（:2083/:3632）、HWC 请求（scheduleComposite:1834-1837）；谁执行：SF 主线程（tid 661）；后果：无事务时段 VSYNC-sf 无脉冲、主线程无 slice（6,814.6→6,858.5 实测静默 43,829,615 ns），屏幕保持上一帧内容。**已证**。

**② 应用 doFrame（vsync-app）与 SF composite（vsync-sf）的相位差与最小帧间隔后果？** 规则：app 相位=硬件 vsync 后约 1ms（workDuration 16,673,882＝整周期），sf 相位=下一硬件 vsync 后约 1ms（workDuration 15,673,882＝周期−1ms，§2.3）；谁设置：sysprop 相位偏移（VsyncConfiguration.cpp:88-91）经 initScheduler 装配（:3446-3460）；谁执行：VSyncDispatchTimerQueue 的 alarm（§2.3a）；后果：应用帧要赶上"下一拍合成"必须在 SF 的 sf 相位回调前把事务送达（本帧链：doFrame 6,842.07 → 事务 6,845.03 → SF 下一次回调 6,858.45，赶上的是再下一拍，present 目标 ≈6,874.1，app 到 present ≈32.06ms＝2 周期）；连续帧的最小显示间隔由 SF 每 cycle 至多 latch 一帧限定为 1 个 vsync 周期；应用跳拍（65.630ms 实测）期间无新事务则无合成、无 CommitFrame。**相位差与换算已证；"32.06ms 是本帧实际呈现间隔"未证**（present fence 信号时刻不在 trace，呈现上界语义批13 划）。

**③ acquire fence 未信号时 composite 线程的行为？** 规则：三道闸——事务收集期 NotReady 退桶（SurfaceFlinger.cpp:4054-4073）；latch 期 `fenceHasSignaled` 不过则本层放弃、`onLayerUpdate` 排下拍（BufferLayer.cpp:468-474）；AutoSingleLayer 档允许首笔单层简单更新未信号 latch（:3948-3991），此后等待责任在 RenderEngine（client）或 HWC（device）。谁设置：档位由 `debug.sf.latch_unsignaled`/`auto_latch_unsignaled` 定（:454-463，源码学习副本的批注块 :454 区在位）；谁执行：主线程 flush/latch；后果：未信号锁存把 GPU 等待计进合成帧时延，且阻塞发生在 RenderEngine/HWC 而非 latch。本 trace 实例：allowLatchUnsignaled=false slice（6,858,642,842）显示 AppListActivity#245 走"非未信号锁存"分支。**已证**（代码＋分支实例）；"本帧 acquire fence 已信号"由该分支打点间接支持（若走未信号路径会现 ReadyUnsignaled 打点），**未证**（fence 信号时刻无 trace 字段）。

**④ client 合成与 HWC 合成各自的帧时延构成？** 见 §4.7：client＝dequeue＋GPU 重画＋queueBuffer＋present 等待（本 trace 8.2/9.8ms 级），device＝validate/presentOrValidate＋present 等待（0.84/0.95ms 级）。谁设置：validate 的逐层裁决（HwcValidateDisplay/HwcPresentOrValidateDisplay，§4.6）；谁执行：RenderEngine 线程（client）/composer HAL（device）；后果：client 层越多 GPU 段越长且占 SF 主线程的 present 预算；投机执行命中时 client 段与 validate 并行、省一次串行等待（§4.3）。**已证**（单帧四输出对照）。

**⑤ 本设备虚拟化 HWC 路径的证据边界？** 已证段：SF→HidlComposerHal（HwcPresentDisplay，HidlComposerHal.cpp:550）→composer@2.4-service（pid 659）→drm_hwcomposer（CommitFrame，DrmAtomicStateManager.cpp:43）→drmModeAtomicCommit（:136）→guest DRM。盲区段：HAL 产品配置为何加载 drm_hwcomposer（设备离线）；drmModeAtomicCommit 之后 virtio-gpu 命令流与 host 行为（批13）；present fence 的实际信号时刻（无 trace 字段）。

## 8. 空档侧写：S 窗口内的 SF（供批15 归因取用）

Launcher 主线程 S 窗口 6,779,836,842–6,830,765,000（契约 :69）与 SF 主线程的相交段（q6/q13）：

- 6,779,836,842 → 6,792,019,957：SF 主线程无 commit slice（上一轮 composite 155802 已于 6,791,975,919 结束——6,776,740,842+15,235,077）。
- 6,792,019,957（commit 155810 起）→ 6,814,625,342（composite 155810 止）：SF 整帧 busy（21,449,962 ns），含 RE 线程 2,978,000 ns 的四层 client 重画（§4.4）与 HwcPresentDisplay 3,334,692。
- 6,814,625,342 → 6,858,454,957：SF 主线程零 commit/composite（43,829,615 ns 静默），跨过应用 doFrame 155812 的整段绘制（6,842,075 起，批0 :55）与 DrawFrames 155812（6,842,332,265，批10），直到 frame=396 事务触发排程。静默段内主线程可见 slice 仅两条：`setVsyncEnabled`（6,841,041,995，237,231，q2）与 tid 684 的 addEntry（TransactionTracing 后台记账，§1.2）——前者的触发源（哪条链把 HWC vsync 开关翻转）**未证**（setVsyncEnabled 的调用方有电源态/热插拔/注入多条，SurfaceFlinger.cpp:1946-1958 只证执行体，无调用方 trace 证据）。

**判定**：50.928 ms S 的前 12.2 ms 无 SF 帧工作、中段 22.6 ms SF 在合成（与主线程 S 并行的另一条线程轨）、后段 16.1 ms SF 静默（三段以 S 止 6,830,765,000 为界；S 止后至 commit 155818 起 6,858,454,957 另有 27.7 ms 静默，属窗外，见上条 43.8 ms 全段）。"SF 合成占 CPU3 与主线程 S 的资源竞争关系"属批14/批15 的归因域，本篇只交付轨上事实，不做因果断言。

## 9. 未证边界清单

1. 设备 build 与基准源码树一致性（全局前提，00 篇 §3.6）。
2. commit 155818 的 mExpectedPresentTime 推算值 6,873.9/6,874.1ms 两个候选（vsyncIn 反推 vs 相位公式），预测时点无直接 trace 字段。
3. 本帧（frame=396）acquire fence 的信号时刻与是否未信号锁存（无 fence 时刻字段；分支打点为间接证据）。
4. 四输出与 Display0/1/2/虚拟屏的身份映射（slice 无 display id；结构推断标未证）。
5. 输出 A 四层全走 client 的完整判据链（drm_hwcomposer backend 决策细节未逐条核对；模糊为强制 client 项的方向已证）。
6. PrevFramePending/PrevFrameMissed/PrevHwcFrameMissed/PrevGpuFrameMissed 四条 TracedOrdinal 计数轨在本 trace 的取值（打点在位，未取值）。
7. TransactionReadiness/TransactionQueue 计数轨取值（打点在位，值未查询——窗口内 slice 证据已足，未再扩查）。
8. tid 764/766 两个零打点线程的身份（§1.1 推断未证）。
9. beginFrame 的 skipping 分支在四输出上的逐帧分布（无独立 slice，未核对）。
10. HAL 加载 drm_hwcomposer 的产品配置链（ro.hardware.hwcomposer 运行时值盲区）。
11. vg-kit 序列与哪个 SF/HAL 计数对齐（留批13 终判，§6.2）。
12. present fence 信号时刻与物理呈现（批13 语义边界）。
13. composite 155810 的 postComposition 内 4 条 wait/merge slice 的 fence 等待对象逐条归属（§6.1 结构性解释，未逐条核对）。
14. 投机执行在窗口内的命中率（CompositionStrategyPredictionHit/Miss 全窗口计数未统计——155810 一帧实测 Hit；6 处 prepareFrameAsync 的命中分布未查询）。

## 10. 批注审计与新增清单（源码学习副本）

审计范围：SF 相关 17 文件的全部中文批注行（【】标签块＋无标签块）。逐块与基准源码树比对结果：

| 文件 | 中文批注行 | 审计结论 |
|---|---|---|
| SurfaceFlinger.cpp | 100 | 17 个【】标签块逐块核对全部与基准源码树一致（getLatchUnsignaledConfig/bootFinished/init 五步/commit/composite×2/postComposition/getModes 重试/虚拟屏 swapInterval/latchBuffers/flushTransactionQueues/屏障定点判据/applyTransactions/shouldLatchUnsignaled 四道闸/queueTransaction 双锁/setTransactionState） |
| BufferStateLayer.cpp | 34 | 5 个标签块＋1 无标签块；**1 处机制错误修正**（:659"FrameMetrics 的 SYNC_DURATION 消费的就是它"——latchTime 实际消费链是 BLASTBufferQueue::transactionCallback 收 stat.latchTime 进 mFrameEventHistory.addLatch（BLASTBufferQueue.cpp:110），即 Surface.getFrameTimestamps 的 latchTime 时刻来源；FrameMetrics 是 HWUI FrameInfo 体系（android.view.FrameMetrics），不消费 SF latchTime。已改写批注行） |
| BufferLayer.cpp | 9 | latchBuffer 块与基准源码树一致 |
| CompositionEngine/src/Output.cpp | 66 | present 管线/prepareFrame/prepareFrameAsync 三连块/finishFrame/postFramebuffer/planComposition×3/writeCompositionState×2 全部一致 |
| CompositionEngine/src/CompositionEngine.cpp | 16 | 工厂块/present 四步块一致 |
| CompositionEngine/src/RenderSurface.cpp | 13 | dequeueBuffer 三细节块一致 |
| CompositionEngine/src/Display.cpp | 19 | setColorProfile 三块一致 |
| Scheduler/MessageQueue.cpp | 12 | 类头/dispatchFrame/handleMessage 三块一致（本批扩展 handleMessage 块） |
| Scheduler/MessageQueue.h | 9 | 提交信号账本＋Task 块一致 |
| Scheduler/DispSyncSource.cpp | 14 | 类头/CallbackRepeater/onVsyncCallback 块一致（本批扩展 onVsyncCallback 块） |
| Scheduler/EventThread.cpp | 31 | threadMain/shouldConsumeEvent 块一致；文件头 :57 声明 generateFrameTimeline 豁免（本批不再补） |
| Scheduler/VSyncDispatchTimerQueue.cpp | 24 | schedule 武装块/timerCallback 块一致 |
| Scheduler/VsyncConfiguration.cpp | 4 | 偏移缓存块一致 |
| Scheduler/VsyncModulator.cpp | 24 | 4 块抽查一致 |
| Scheduler/Scheduler.cpp | 24 | 8 块抽查一致 |
| DisplayDevice.cpp | 26 | 9 块抽查一致（构造四步/输入方向抵消/亮度钩子/切档三件事/vsync 周期降级链/投影三步/亮度暂存协议/空闲计时器/期望模式三态） |
| DisplayHardware/HWComposer.cpp | 15 | 同戳去重/getDeviceCompositionChanges/presentAndGetReleaseFences/热插拔定基调 4 块一致 |

审计总账：**修正 1 处**（BufferStateLayer.cpp latch 过账块的 FrameMetrics 归因）；其余全部与基准源码树机制一致。

新增批注清单（纯插入批注行＋2 处块扩展；每文件 ≤20 合规）：

| 编号 | 文件 | 位置 | 行数 | 内容要点 |
|---|---|---|---|---|
| X1 | Scheduler/MessageQueue.cpp | handleMessage 块扩展 | 4 | commit 155818/composite 155818 配对实测＋commit 155794 无 composite 实例＋vsyncIn 语义 |
| X2 | Scheduler/MessageQueue.cpp | vsyncCallback 前 | 9 | VSYNC-sf 轨翻转点＋vsyncId 现生成＋scheduleFrame 触发源＋静默实例 |
| X3 | Scheduler/DispSyncSource.cpp | onVsyncCallback 块扩展 | 5 | VSYNC-app 轨＋VsyncWorkDuration 实测三值反推相位 1ms＋按需停源 |
| X4 | Scheduler/EventThread.cpp | requestNextVsync 前 | 5 | binder 入口＋resyncCallback 去抖＋本样本 requestNextVsync slice 实测（6,844,505,072 binder:661_4） |
| X5 | SurfaceFlinger.cpp | onComposerHalVsync 前 | 6 | HAL vsync 进口＋同戳去重＋addResyncSample＋非活动屏丢弃 |
| X6 | SurfaceFlinger.cpp | previousFrameFence 前 | 5 | 双槽选取＋graceTimeMs=1ms＋previousFramePending 实测 27,500 |
| X7 | SurfaceFlinger.cpp | transactionIsReadyToBeApplied 前 | 9 | 五道判＋slice 名两个实测对应（6,858,607,572/6,858,642,842） |
| X8 | BufferLayer.cpp | shouldPresentNow 前 | 6 | 三段裁决＋BufferStateLayer isBufferDue 恒真（BufferStateLayer.h:49） |
| X9 | CompositionEngine/src/Output.cpp | composeSurfaces 前 | 6 | 早退实例＋REThreaded 跨线程＋CacheMiss/Hit＋fence 语义 |
| X10 | CompositionEngine/src/Output.cpp | presentAndGetFrameFences 前 | 5 | 本层 fence 收集＋Display 子类叠加＋155818 实测 |
| X11 | Scheduler/VsyncConfiguration.cpp | sfOffsetToDuration 前 | 7 | 相位→工作时长代数＋本机三计数轨反推 app/sf 偏移各 1ms |
| X12 | BufferStateLayer.cpp | tracePendingBufferCount 前 | 6 | "BufferTX - " 计数轨增减点＋对端为应用侧 "QueuedBuffer - ...BLAST#"（BLASTBufferQueue.cpp:157/:460）；并勘误性注明 "aq:pending:"（ViewRootImpl.java:1419）是输入事件队列计数、与缓冲无关 |

数量：新增独立块 10（X2、X4~X12）＋块扩展 2（X1、X3）＝12 处改动；修正 1 处（批注文本行替换）。git diff --stat：8 文件 82 insertions(＋)/1 deletion(−)（删除为被修正的旧批注行）。过程留痕：diff 存档 `批12-批注diff存档-sf.txt`（206 行）、CJK 剥离自检 `批12-cjk剥离diff自检.txt`（31 行）。

## 11. 自检结果与复核指引

1. **剥离 CJK 行 diff**（grep -v '[一-龥]'，基准源码树→study）：11 个相关文件 del 全部 0（BufferLayer.cpp/MessageQueue.h/DispSyncSource.h/VsyncConfiguration.cpp 连 ins 也为 0 或与基线相同）；ins 残留全部为历史批注块内的空行与"//"空注释行（SurfaceFlinger.cpp 5、BufferStateLayer.cpp 1、Output.cpp 6、CompositionEngine.cpp 1、MessageQueue.cpp 1、DispSyncSource.h 3——DispSyncSource.h 的 3 行为本批未触碰文件的历史遗留）。本批新增批注行全部含 CJK。
2. **括号平衡**（{ 计数 基准源码树/study）：SurfaceFlinger.cpp 1289/1289、BufferLayer.cpp 117/117、BufferStateLayer.cpp 184/184、Output.cpp 236/236、MessageQueue.cpp 36/36、DispSyncSource.cpp 28/28、EventThread.cpp 124/124、VsyncConfiguration.cpp 42/42——逐对相等。
3. **过程事故披露**：X8 插入时曾把锚点行误写为 `shouldPresentNow(nsecsedTime)`，同会话内立即以第二次 Edit 复原为 `shouldPresentNow(nsecs_t expectedPresentTime)`；最终 CJK 剥离 diff del=0 与括号平衡证明代码行零净变更。
4. **git 纪律**：仅 git status/diff 只读命令；无 commit/push；设备未连接。
5. **SQL/输出留痕**：批12-q1~q14 共 16 个 SQL 与两份原始输出（q2 窗口 slice 全量、q3 合成树 1120 行）存档于过程记录目录；其余查询输出摘录于批12 过程记录 §三。
6. **复核指引**：本文断言编号与逐断言验证表（K1~K22）见 `过程记录/批12-过程记录.md` §四；新数值的 SQL 与输出见 §三；引文行号复核基准为基准源码树（study 行号仅作批注位置辅助）。

## 12. 关联阅读

- 上游：[10-HWUI与RenderThread.md](10-HWUI与RenderThread.md)（DrawFrames 155812 与 RT 侧 queueBuffer/onFrameAvailable）、[11-BufferQueue与BLAST.md](11-BufferQueue与BLAST.md)（apply 之前的 BLAST 提交链与 QueuedBuffer/PendingRelease 计数）。
- 下游：13-virtio_gpu与呈现（CommitFrame 之后的 DRM 命令流、vg-kit 计数对账、present fence 与物理呈现的语义边界）。
- 调度横轴：14-CFS调度与线程状态（SF 主线程/RenderEngine 线程的 S/R/R+ 语义与 CPU3 竞争窗口）。
- 归因：15-诊断实战全时序归因（§8 空档侧写的归位）。

## 附录 A · 本篇 trace slice 名 → 代码产生点对照表（复核用）

本样本窗口内出现过的 SF/HAL 侧 slice 名与其打点行号一一对应（同一打点多次出现按同名归并；打点均在基准源码树实读核对）：

| slice 名 | 产生点 | 首次引用 |
|---|---|---|
| commit <vsyncId> vsyncIn <x>ms(adjusted) | SurfaceFlinger.cpp:2017-2018（commit 内 ATRACE_FORMAT） | §3.2 |
| composite <vsyncId> | SurfaceFlinger.cpp:2188（composite 内 ATRACE_FORMAT） | §4.1 |
| latchBuffers / latchBuffer / doTransaction | SurfaceFlinger.cpp:3563（latchBuffers ATRACE_CALL）/ BufferLayer.cpp:460（latchBuffer ATRACE_CALL）/ latchBuffers 内 doTransaction 调用 | §3.6 |
| transactionIsReadyToBeApplied vsyncId: <id> | SurfaceFlinger.cpp:4000（ATRACE_FORMAT） | §3.4 |
| <层名> allowLatchUnsignaled=true/false | SurfaceFlinger.cpp:4051-4052（ATRACE_FORMAT） | §3.4 |
| previousFramePending | SurfaceFlinger.cpp:1969（previousFramePending ATRACE_CALL） | §3.2 |
| setSfWakeUp / setSfPresent | SurfaceFlinger.cpp:2129（setSfWakeUp）/ :2462（setSfPresent 调用点；FrameTimeline.cpp:831-832/:841-842 为函数体 ATRACE） | §3.2a/§6.1 |
| setBuffer / createSurfaceFrameForToken / addSurfaceFrame | BufferStateLayer.cpp:347（setBuffer ATRACE_CALL）→ :428 setFrameTimelineVsyncForBufferTransaction → Layer.cpp:1313-1317 createSurfaceFrameForBuffer → FrameTimeline.cpp:787-790（createSurfaceFrameForToken，ATRACE_CALL :790）/ FrameTimeline.cpp:826（addSurfaceFrame ATRACE_CALL，调用点 Layer.cpp:1291） | §3.5/§3.2a |
| setTransactionState / scheduleFrame | SurfaceFlinger.cpp:4144（ATRACE_CALL）/ MessageQueue.cpp:169（ATRACE_CALL） | §3.1 |
| commitTransactions / doCommitTransactions | SurfaceFlinger.cpp:2667/:3493（两函数 ATRACE_CALL） | §3.7 |
| present / prepare / rebuildLayerStacks | CompositionEngine.cpp:92（present ATRACE_CALL）/ Output.cpp:423（prepare ATRACE_CALL）/ Output.cpp:456（rebuildLayerStacks ATRACE_CALL） | §4.1 |
| updateCompositionState / writeCompositionState / planComposition | Output.cpp:737/:771/:764（各 ATRACE_CALL） | §4.2 |
| beginFrame / prepareFrame / prepareFrameAsync | Output.cpp:966 区/:996/:1023（各 ATRACE_CALL） | §4.2/§4.3 |
| chooseCompositionStrategy / getDeviceCompositionChanges | Output.cpp 内（同步路 :1004 区调用）/ HWComposer.cpp:404（ATRACE_CALL） | §4.3/§4.6 |
| HwcValidateDisplay / HwcPresentOrValidateDisplay / HwcPresentDisplay | HidlComposerHal.cpp:636/:653/:550（各 ATRACE_NAME） | §4.5/§4.6 |
| finishFrame / postFramebuffer / presentAndGetFrameFences | Output.cpp:1093/:1441/:1519（各 ATRACE_CALL） | §4.5 |
| composeSurfaces / prepareClientComposition / ClientCompositionCacheMiss | Output.cpp:1178（ATRACE_CALL）/:1389 区/:1249（ATRACE_NAME） | §4.4 |
| dequeueBuffer / queueBuffer / FramebufferSurface: N | RenderSurface.cpp:152（ATRACE_CALL）/ :201 区（ATRACE_CALL）/ FramebufferSurface 侧 | §4.5 |
| presentAndGetReleaseFences / wait for earliest present time | HWComposer.cpp:511（ATRACE_CALL）/ :530-534（ATRACE_NAME＋sleep_until） | §4.5 |
| REThreaded::drawLayers / SkiaGL::drawLayers / flush surface / flush / Submit(sync=false) | RenderEngine 线程侧（renderengine 库内打点，本文按 tid 685 轨引用，产生点行号留批10/批13 域） | §4.4 |
| CommitFrame | DrmAtomicStateManager.cpp:44（ATRACE_CALL） | §4.6 |
| onComposerHalVsync(周期) | SurfaceFlinger.cpp:1866（ATRACE_FORMAT） | §2.1 |
| requestNextVsync | EventThread.cpp:196（ATRACE_CALL） | §2.4a |
| generateTokenForPredictions | FrameTimeline.cpp:744（ATRACE_CALL） | §2.5 |
| TimerIteration #N / cancel timer / <名> alarm in Xus; VSYNC in Yus | Scheduler/src/Timer.cpp:191（ATRACE_NAME）/ VSyncDispatchTimerQueue.cpp:261（ATRACE_NAME）/ :254-257（ATRACE_NAME） | §2.3a |
| VSYNC-app / VSYNC-sf / VsyncWorkDuration-*（计数轨） | DispSyncSource.cpp:123（"VSYNC-%s"）/:180-182（翻转）/ MessageQueue.h:120（"VSYNC-sf"）/ MessageQueue.cpp:103（翻转）/ DispSyncSource.cpp:127（"VsyncWorkDuration-%s"） | §2.2 |
| TransactionQueue / TransactionReadiness（计数轨） | SurfaceFlinger.cpp:4103/:3756/:3840（各 ATRACE_INT） | §3.1/§3.3 |
| BufferTX - <层名>（计数轨） | BufferStateLayer.cpp:865（ATRACE_INT，mBlastTransactionName） | §3.5 |
| addEntry | TransactionTracing.cpp:197（addEntry） | §1.2 |

RenderEngine 侧（REThreaded::drawLayers 及其子 slice）与 FramebufferSurface 侧的打点行号属 hwui/renderengine/libs/gui 走读域，本篇只按线程轨引用实测值，不重复给行号——RE 侧产生点核对留批10 存档（DrawFrameTask/CanvasContext 域）与批13（若涉及）。本表用途：复核 agent 在 trace 中见任意上述 slice 名时，可直接按"产生点"列回源码核对语义。
