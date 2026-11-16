# 09 · 帧调度与RecyclerView布局：Choreographer doFrame＋performTraversals＋回收复用＋fling

本层将帧请求安排到 Choreographer 回调，再执行遍历、RecyclerView 布局或滚动和绘制记录。

> 与任务预置证据声明的一处实证勘误（同批05 accumulator、批08 ProcessInputEventStage 的勘误口径）：
> **本 trace 的 doFrame 内部并非无子 slice。** 批09 补查（SQL q2/q3/q5，命令与输出留痕于 `过程记录/`）实测：tid 1909 全 trace 508 个 #14 doFrame slice，每个内部都有 depth=1 的 `traversal` 子 slice（508/508）与 depth=2 的 `draw` 子 slice（508/508）；名为 `measure`/`layout`/`input`/`animation`/`insets_animation`/`commit` 的 slice 在 tid 1909 全程零命中（q3 精确名匹配＋q6 前缀模糊）。因此本篇对 doFrame 155812 的阶段构成给出实测分账（§5.3），"无子 slice 盲区"声明按勘误处理，替换为"无到期回调段不打点"的机制解释（Choreographer.java:858-860）。
> 与契约作业单的其他差异（沿用与勘误）：
> ① 契约 ：143 所列 RecyclerView "scrollBy→scrollStep" 沿用批08 勘误②：internal RV 无 scrollStep，实际链 `scrollByInternal`（RecyclerView.java:1653）→ `LinearLayoutManager.scrollBy`（LinearLayoutManager.java:1293）。
> ② 契约 ：145 "token 不是连续帧序号"沿用（#15 token）：vsyncId 是 SurfaceFlinger 时间线档位编号，连续两次 doFrame 的编号差 7~9（§10）。
> 输入消费与分发见 08、06、07 篇；performDraw 进入 ThreadedRenderer 后的路径见 10 篇。
> 术语沿用 00 篇术语表：#7 MotionEvent、#10 deliverInputEvent、#13 vsync-app/vsync-sf、#14 doFrame、#15 token（FrameToken）、#16 S/R/R+/D 线程状态、#17 waker、#31 performTraversals。本篇概念：**帧档位**（§2.1）、**到期回调**（§2.3）、**回收复用**（§7.3）。

## 0. 本篇范围与上下游边界

批08 止于 `scheduleVsyncLocked`（Choreographer.java:936 打点，trace slice `Choreographer#scheduleVsyncLocked` 6,831,246,265 dur 88,384）。本篇从这一行继续走读：vsync 请求如何发出（§3）、到达后 onVsync→doFrame 如何执行（§4-§5）、doFrame 的 traversal 段进入 #31 performTraversals 三段（§6）、RecyclerView 的滚动消费与回收复用在其中如何发生（§7）、fling 如何形成逐帧循环（§8）。文件清单与走读范围：

| 文件 | 行数（实测） | 本篇走读范围 |
|---|---|---|
| `frameworks/base/core/java/android/view/Choreographer.java` | 1313 | 全量 |
| `frameworks/base/core/java/android/view/ViewRootImpl.java` | 11231 | 失效链（:1979-2057）、排程三方法（:2314-2357）、performTraversals（:2785-3689）、三段入口（:4015-4135/:4449-4541）、draw（:4628-4837）、InvalidateOnAnimationRunnable（:9341-9444）、TraversalRunnable（:9191-9197） |
| `frameworks/base/core/java/com/android/internal/widget/RecyclerView.java` | 12371 | scrollBy/scrollByInternal（:1563-1701）、fling/smoothScrollBy（:1948-2050）、onMeasure/onLayout（:3072-3142/:3910-3924）、dispatchLayout 三步（:3365-3720）、ViewFlinger（:4747-4920）、RecycledViewPool/Recycler（:5060-5960/:6060-6200） |
| `frameworks/base/core/java/com/android/internal/widget/LinearLayoutManager.java` | — | scrollVerticallyBy（:1043-1060）、scrollBy（:1293-1317）、recycle 系（:1332-1458）、fill/layoutChunk（:1471-1521） |
| `frameworks/base/core/java/android/view/View.java` | 31831 | invalidate 链（:19268-19342）、postOnAnimation（:19604-19647）、postInvalidateOnAnimation（:19769-19816） |
| `frameworks/base/core/java/android/view/ViewGroup.java` | 9321 | invalidateChild 硬加速快路径（:6052-6068） |
| `frameworks/base/core/java/android/widget/OverScroller.java` | 953 | fling 入口（:385-438）、SplineOverScroller 摩擦模型（:730-810） |
| `frameworks/base/core/java/android/view/DisplayEventReceiver.java` | 317 | scheduleVsync/dispatchVsync（:260-317） |
| `frameworks/base/core/jni/android_view_DisplayEventReceiver.cpp` | 327 | dispatchVsync JNI（:137-152）、nativeScheduleVsync（:245 区） |
| `frameworks/native/libs/gui/DisplayEventDispatcher.cpp` | 205 | initialize/scheduleVsync/handleEvent（:29-175） |
| `frameworks/base/core/java/com/android/internal/widget/GapWorker.java` | 379 | postFromTraversal（:169-181）/run（:349-379） |

边界如下：

- **上游边界**：批08 的交接点——`RV Scroll` 内 `invalidate()`（RecyclerView.java:1676/:1698）→ ViewRootImpl 失效链（§9）→ `scheduleTraversals`（ViewRootImpl.java:2321）→ `scheduleVsyncLocked` slice（6,831,246,265）。输入消费本体（InputStage 链、VelocityTracker 采样）全部"见批08"。
- **下游边界**：本篇止于 `mAttachInfo.mThreadedRenderer.draw(mView, mAttachInfo, this)`（ViewRootImpl.java:4768）——ThreadedRenderer 录制、`Record View#draw()`/`postAndWait` 子 slice 的内部机制、RenderThread 侧"见批10"（契约 :149）。
- **系统侧边界**：vsync 的产生与分发在 SurfaceFlinger（DispSync/EventThread，#13 vsync-app 的对端），本篇只走到应用侧 BitTube 的读写两端，SF 侧"见批12"。
- **AppList 应用层盲区声明**（契约 :136 指定，批08 §0 沿用）：AppList 的 Adapter/LayoutManager 配置源码不在基准源码树。本篇对回收复用量的断言只到 internal RV＋LinearLayoutManager 通用机制；"本手势每帧实际回收/新建多少 ViewHolder"未证（盲区，§14）。

主线先闭环：§3→§6 走完"请求 vsync → 到达 → doFrame → traversal 三段"即闭合；其后 §7/§8 展开滚动与 fling 两条内容线，§9 归纳防重入，§10 给 65.630 ms 分账框架，§11 机制四问汇总。

## 1. 主线问题：从 scheduleVsyncLocked 到 doFrame 155812，主线程经历了什么

本战役最新样本（`20261113-151358-vertical-1912967`，Launcher 主线程 tid=1909）与本篇直接相关的 trace 事实（前三条逐字引自契约核心证据包，其后为批0/批08/批09 已留痕数值）：

- `RV Scroll` 内 scheduleVsync `00:00:06.831246`（契约 :75）。
- doFrame 155812 起点 `00:00:06.842075`（契约 :75）。
- 两 doFrame 起点差 65.630 ms（契约 :75）。
- 批0 复验一 §二.5：`Choreographer#onVsync 155812` rel 6,842,036,457 dur 24,885 → `Choreographer#doFrame 155812` rel 6,842,074,918 dur 564,193；scheduleVsyncLocked（6,831,246,265）→ onVsync（6,842,036,457）间隔 10,790,192 ns；窗口内 doFrame 序列 155787（6,725,385，599 μs）/155795（6,759,164，2.041 ms）/155803（6,776,445，1.186 ms）/155812（6,842,075，0.564 ms）/155820（6,865,863，8.364 ms）/155827（6,892,729，2.722 ms）。
- 批08 补查一：`RV Scroll` rel 6,831,161,226 dur 238,231；主线程状态序列 R 33,923（waker=InputDispatcher tid 1488）→ Running 1,984,307（6,830,798,765 起）→ S 9,106,616（6,832,783,072 起）。
- 批09 补查一（q1/q4/q6，本批新）：tid 1909 全 trace `Choreographer#doFrame` 508 条、`Choreographer#onVsync` 508 条、`Choreographer#scheduleVsyncLocked` 508 条，三者计数相等；`RV Scroll` 855 条。
- 批09 补查二（q2/q5，本批新）：doFrame 155812 内部子 slice——`traversal` rel 6,842,085,803 dur 546,654（depth 1）、`draw` rel 6,842,147,649 dur 480,116（depth 2）、`Record View#draw()` rel 6,842,162,226 dur 99,577（depth 3）、`postAndWait` rel 6,842,268,957 dur 353,769（depth 3）。
- 批09 补查三（q7，本批新）：doFrame 155803 止点（6,776,444,919＋1,185,500＝6,777,630,419）到主线程 S 起点（6,779,836,842）之间只有两条 slice：`RV Prefetch` rel 6,777,711,688 dur 61,807 与 `reportTimeline` rel 6,779,772,265 dur 31,423。

本篇的任务：把 6,831,246,265（scheduleVsyncLocked 起）到 6,842,074,918（doFrame 155812 起）之间的 10.829 ms 逐段落到代码上——请求写进了哪个 fd（§3）、主线程在 S 里等什么（§3.3）、vsync 到达后先跑哪几行（§4）、doFrame 的五段为何只留下 traversal 段的痕迹（§5）、traversal 段里 performTraversals 的三段裁决为什么只剩 draw（§6）、RecyclerView 在其中消费了什么（§7）。

## 2. 数据对象底座：帧档位与回调队列

### 2.1 帧档位：FrameTimeline 与 VsyncEventData（#15 token 的载体）

**帧档位**在本篇定义为：SurfaceFlinger 随每条 vsync 事件下发的一组候选上屏方案（Choreographer.FrameTimeline，含 vsyncId/期望呈现时刻/完成期限），应用侧载体是 `DisplayEventReceiver.VsyncEventData`。slice 名尾部的编号即首选档位的 vsyncId：

```java
// frameworks/base/core/java/android/view/Choreographer.java:1000-1008/:1014-1020
    public static class FrameTimeline {
        static final FrameTimeline INVALID_FRAME_TIMELINE = new FrameTimeline(
                FrameInfo.INVALID_VSYNC_ID, Long.MAX_VALUE, Long.MAX_VALUE);
        ...
        /**
         * The id that corresponds to this frame timeline, used to correlate a frame
         * produced by HWUI with the timeline data stored in Surface Flinger.
         */
        public long getVsyncId() {
            return mVsyncId;
        }
```

走读（#15 token 语义的源码落点）：vsyncId 的用途是"把 HWUI 产出的帧与 SurfaceFlinger 存储的时间线数据对账"（:1015-1016 注释原文）——它是 SF 侧时间线的档位编号，不是应用帧的执行序号。应用两帧之间的编号差可以大于 1：应用只在有工作可做时才排帧，SF 的编号按它自己的脉冲序列推进。本 trace 的实测：六条 doFrame 编号 155787/155795/155803/155812/155820/155827，相邻差 8/8/9/8/7（批0 复验一 §二.5 的起点值＋q1 全序列，算术）——契约 :75 "token 不是连续帧序号"的直接证据。档位数据在 Java 侧的拼装：

```java
// frameworks/base/core/java/android/view/Choreographer.java:1055-1068（FrameData 构造）
        FrameData(long frameTimeNanos, DisplayEventReceiver.VsyncEventData vsyncEventData) {
            FrameTimeline[] frameTimelines =
                    new FrameTimeline[vsyncEventData.frameTimelines.length];
            for (int i = 0; i <  vsyncEventData.frameTimelines.length; i++) {
                ...
                frameTimelines[i] = new FrameTimeline(frameTimeline.vsyncId,
                        frameTimeline.expectedPresentTime, frameTimeline.deadline);
            }
            this.mFrameTimeNanos = frameTimeNanos;
            ...
            this.mPreferredFrameTimeline =
                    frameTimelines[vsyncEventData.preferredFrameTimelineIndex];
```

走读：native 下发的 `VsyncEventData` 带多条候选时间线与首选下标（android_view_DisplayEventReceiver.cpp:111-135 的 `createJavaVsyncEventData` 逐字段拷入 Java 对象，已证）。doFrame 的 slice 名取首选档位 vsyncId（Choreographer.java:749）。本 trace 未提取 deadline/expectedPresentTime 的具体值（未查 counter/slice 载荷字段，未证，§14）。

### 2.2 CallbackRecord 与 CallbackQueue：五条按到期时间有序的链表

每类回调一条 `CallbackQueue`（Choreographer.java:282-285 构造 `mCallbackQueues = new CallbackQueue[CALLBACK_LAST + 1]`），节点从对象池取（obtainCallbackLocked :947-959，recycleCallbackLocked :961-966）：

```java
// frameworks/base/core/java/android/view/Choreographer.java:1244-1269（摘）
    private final class CallbackQueue {
        private CallbackRecord mHead;

        public boolean hasDueCallbacksLocked(long now) {
            return mHead != null && mHead.dueTime <= now;
        }

        public CallbackRecord extractDueCallbacksLocked(long now) {
            CallbackRecord callbacks = mHead;
            if (callbacks == null || callbacks.dueTime > now) {
                return null;
            }
            CallbackRecord last = callbacks;
            CallbackRecord next = last.next;
            while (next != null) {
                if (next.dueTime > now) {
                    last.next = null;
                    break;
                }
                ...
```

走读：链表按 dueTime 升序插入（addCallbackLocked :1271-1292）；提取时把"到期前缀"整段摘下返回、未到期余链原地保留（:1256-1268）。"到期"的判定基准由调用方传入（§4.2 的 `now`），不是插入时刻——这是"本帧前段给后段投递新回调"能被下一执行阶段带走的前提（代码注释 ：852-854 原文）。五类的常量与执行顺序声明：

```java
// frameworks/base/core/java/android/view/Choreographer.java:218-220/:226/:233/:249/:256/:268
    private static final String[] CALLBACK_TRACE_TITLES = {
            "input", "animation", "insets_animation", "traversal", "commit"
    };
    public static final int CALLBACK_INPUT = 0;
    public static final int CALLBACK_ANIMATION = 1;
    public static final int CALLBACK_INSETS_ANIMATION = 2;
    public static final int CALLBACK_TRAVERSAL = 3;
    public static final int CALLBACK_COMMIT = 4;
```

走读：数组下标即类型编号，trace 中五段的 slice 名即这五个字符串（产生点 doCallbacks :892，§5.2）。ViewRootImpl 的遍历挂 CALLBACK_TRAVERSAL（§3.1），RecyclerView fling 的续帧与 postInvalidateOnAnimation 挂 CALLBACK_ANIMATION（§8.2/§8.3）。

### 2.3 状态位与帧时间：mFrameScheduled/mLastFrameTimeNanos

```java
// frameworks/base/core/java/android/view/Choreographer.java:184-187/:210 区（摘）
    private boolean mFrameScheduled;
    private boolean mCallbacksRunning;
    @UnsupportedAppUsage
    private long mLastFrameTimeNanos;
```

**到期回调**在本篇定义为：extractDueCallbacksLocked 按 `now` 判定 dueTime<=now 而被整段摘出的回调链（Choreographer.java:1251-1269，定义见 §2.2）。`mFrameScheduled` 是"已排帧未执行"的防重入位：scheduleFrameLocked 置位（:678-679）、doFrame 校验后复位（:753-756/:815）。`mLastFrameTimeNanos` 是上一已执行帧的起点时间，用于跳帧改写与帧序回退判定（:792-800）。`mCallbacksRunning` 保护 getFrameTimeNanos 只在回调窗口内可用（:655-663）。

## 3. 请求侧：scheduleTraversals 到 BitTube 的写入（机制①前半）

Choreographer 是应用侧的帧调度组件。它将输入、动画和界面遍历等工作安排到帧回调中，并通过显示事件接收机制等待 VSync 信号；本节从界面请求更新开始追踪该过程。

### 3.1 scheduleTraversals：五步排程与两级去重的第一级

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:2321-2330
    void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
            notifyRendererOfFramePending();
            pokeDrawLockIfNeeded();
        }
    }
```

走读（机制四问"排遍历"的规则与执行者）：mTraversalScheduled 置位后，同窗口内后续 scheduleTraversals 全部在 ：2322 被拦——这是第一级去重（第二级在 Choreographer，§3.2）。mTraversalRunnable 是 `TraversalRunnable`（:9191-9197），run() 调 doTraversal()。同步屏障挡住普通同步消息；本回调走 Choreographer 的异步消息路径不受影响（Choreographer.java:1204 msg.setAsynchronous(true)，§4.1）。批08 已证输入事件是异步消息可穿屏障（ViewRootImpl.java:9033），所以排帧之后到达的输入仍能在同帧窗口内被消费。

postCallback 的落点（机制四问"谁执行"）：

```java
// frameworks/base/core/java/android/view/Choreographer.java:470-492（摘）
    private void postCallbackDelayedInternal(int callbackType,
            Object action, Object token, long delayMillis) {
        ...
        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            final long dueTime = now + delayMillis;
            mCallbackQueues[callbackType].addCallbackLocked(dueTime, action, token);

            if (dueTime <= now) {
                scheduleFrameLocked(now);
            } else {
                Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_CALLBACK, action);
                msg.arg1 = callbackType;
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, dueTime);
            }
        }
    }
```

走读：零延迟投递（postCallback :438-440 → postCallbackDelayed :458-468，delayMillis=0）→ dueTime==now → 当场 scheduleFrameLocked。TraversalRunnable 从此躺在 CALLBACK_TRAVERSAL 队列里，等 §4 的 doFrame 来摘。

### 3.2 scheduleFrameLocked→scheduleVsyncLocked：第二级去重与 native 去重

```java
// frameworks/base/core/java/android/view/Choreographer.java:677-706（摘）
    private void scheduleFrameLocked(long now) {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            if (USE_VSYNC) {
                ...
                if (isRunningOnLooperThreadLocked()) {
                    scheduleVsyncLocked();
                } else {
                    Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_VSYNC);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtFrontOfQueue(msg);
                }
```

走读（机制四问"谁设置/后果"）：`mFrameScheduled` 已为真时整个分支跳过——同一帧窗口内第二次 scheduleTraversals 到这里只会把回调追加进队列（:481 addCallbackLocked），不再重复请求 vsync。这就是"两次 scheduleTraversals 合并为一次 doFrame"的机制（§9 汇总）：ViewRootImpl 层 mTraversalScheduled（:2322）＋Choreographer 层 mFrameScheduled（:678）串联，任一为真即短路。USE_VSYNC 默认 true（:141-142，`debug.choreographer.vsync` 属性，本设备属性值未采，按默认值推定——未证，§14）。scheduleVsyncLocked：

```java
// frameworks/base/core/java/android/view/Choreographer.java:934-941
    private void scheduleVsyncLocked() {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Choreographer#scheduleVsyncLocked");
            mDisplayEventReceiver.scheduleVsync();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }
```

走读：trace 中 6,831,246,265 dur 88,384 的 `Choreographer#scheduleVsyncLocked` slice 即 ：936 打点（已证，代码与 slice 名逐字一致；批08 §8.3 配对）。88,384 ns 的主体是本次跨 JNI 的调用链开销（构成不可逐行归因，trace 粒度到 slice——盲区，§14）。Java 侧最后一站：

```java
// frameworks/base/core/java/android/view/DisplayEventReceiver.java:274-281
    public void scheduleVsync() {
        if (mReceiverPtr == 0) {
            Log.w(TAG, "Attempted to schedule a vertical sync pulse but the display event "
                    + "receiver has already been disposed.");
        } else {
            nativeScheduleVsync(mReceiverPtr);
        }
    }
```

native 侧（DisplayEventReceiver.java:86 声明 `private static native void nativeScheduleVsync(long receiverPtr)`）落到 libgui：

```cpp
// frameworks/native/libs/gui/DisplayEventDispatcher.cpp:71-94
status_t DisplayEventDispatcher::scheduleVsync() {
    if (!mWaitingForVsync) {
        ALOGV("dispatcher %p ~ Scheduling vsync.", this);
        ...
        if (processPendingEvents(&vsyncTimestamp, &vsyncDisplayId, &vsyncCount, &vsyncEventData)) {
            ALOGE("dispatcher %p ~ last event processed while scheduling was for %" PRId64 "", this, ...);
        }

        status_t status = mReceiver.requestNextVsync();
        if (status) {
            ALOGW("Failed to request next vsync, status=%d", status);
            return status;
        }

        mWaitingForVsync = true;
        mLastScheduleVsyncTime = systemTime(SYSTEM_TIME_MONOTONIC);
    }
    return OK;
```

走读：第三级去重在 native——`mWaitingForVsync` 为真期间重复的 scheduleVsync 调用被静默吞掉（:72 条件）。`mReceiver.requestNextVsync()`（:85）经 DisplayEventReceiver（gui）向 SurfaceFlinger 的 vsync 连接写入请求；fd 的注册在 initialize 时完成（:53-58 `mLooper->addFd(mReceiver.getFd(), 0, Looper::EVENT_INPUT, this, NULL)`）——这条 BitTube fd 与输入 socket（批08 §3）同样挂主线程 Looper 的 epoll。SF 侧的脉冲生成与写回"见批12"。

### 3.3 请求之后主线程在做什么：S 态的等待对象（机制四问①）

机制四问①：**vsync 请求与到达之间应用线程在做什么**。

- **规则**：请求写完后，主线程的消息队列里没有必须立即处理的同步消息（同步屏障已在 scheduleTraversals 时插入，ViewRootImpl.java:2324），下一次 Looper 派发只能由异步消息或 fd 事件触发；vsync 尚未到达、无新异步消息时线程进入 poll 等待，内核侧记 S（#16）。
- **本 trace 实测**：scheduleVsyncLocked 止（6,831,246,265＋88,384＝6,831,334,649）之后，主线程把 deliver/回执收尾做完，于 6,832,783,072 进入 S 9,106,616 ns（批08 补查二，已证）；S 止于 6,841,889,688（算术：起点＋dur），再经一段 Running 后 onVsync 155812 于 6,842,036,457 到达。S 的等待对象是 vsync fd 的可读（§3.2 的 addFd 注册）与任何其他被监听 fd 事件的并集；本 trace 无 syscall/irq 事件，"该 S 是否只有 vsync 一个等待条件"保持 00 篇 §3.7 盲区口径（已证部分：vsync 到达后立即有了 onVsync slice，时序咬合）。
- **后果**：scheduleVsyncLocked→onVsync 的间隔 10,790,192 ns（10.790 ms，批0 复验一 §二.5）由 SF 的脉冲相位决定（应用在两个脉冲之间发出请求，只能等下一个相位），加上到达后消息派发的前置（§4.1）。10.790 ms 远超一个帧间隔（本帧的 frameInterval 见 §4.2 的 VsyncEventData，本 trace 未提取该字段值——未证）且小于两个，说明请求落在某个脉冲刚过、下一个未到的相位窗内；具体相位归属由 SF 侧数据判定（盲区，批12）。

## 4. 到达侧：onVsync 到 doFrame（机制①后半＋②）

### 4.1 vsync fd 可读：handleEvent→dispatchVsync→Java onVsync

```cpp
// frameworks/native/libs/gui/DisplayEventDispatcher.cpp:110-131（摘）
int DisplayEventDispatcher::handleEvent(int, int events, void*) {
    ...
    // Drain all pending events, keep the last vsync.
    nsecs_t vsyncTimestamp;
    PhysicalDisplayId vsyncDisplayId;
    uint32_t vsyncCount;
    VsyncEventData vsyncEventData;
    if (processPendingEvents(&vsyncTimestamp, &vsyncDisplayId, &vsyncCount, &vsyncEventData)) {
        ALOGV(...);
        mWaitingForVsync = false;
        mLastVsyncCount = vsyncCount;
        dispatchVsync(vsyncTimestamp, vsyncDisplayId, vsyncCount, vsyncEventData);
    }
```

走读：fd 可读 → 取事件 → `mWaitingForVsync = false`（:130，native 第三级去重的复位点；超时兜底路径的复位在 :140）→ `dispatchVsync`（JNI，android_view_DisplayEventReceiver.cpp:137-152 调 Java `dispatchVsync`）。Java 侧：

```java
// frameworks/base/core/java/android/view/DisplayEventReceiver.java:290-295
    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchVsync(long timestampNanos, long physicalDisplayId, int frame,
            VsyncEventData vsyncEventData) {
        onVsync(timestampNanos, physicalDisplayId, frame, vsyncEventData);
    }
```

Choreographer.FrameDisplayEventReceiver.onVsync（slice 名产生点）：

```java
// frameworks/base/core/java/android/view/Choreographer.java:1171-1209（摘）
        public void onVsync(long timestampNanos, long physicalDisplayId, int frame,
                VsyncEventData vsyncEventData) {
            try {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                            "Choreographer#onVsync "
                                    + vsyncEventData.preferredFrameTimeline().vsyncId);
                }
                ...
                long now = System.nanoTime();
                if (timestampNanos > now) {
                    Log.w(TAG, "Frame time is " + ((timestampNanos - now) * 0.000001f)
                            + " ms in the future!  Check that graphics HAL is generating vsync "
                            + "timestamps using the correct timebase.");
                    timestampNanos = now;
                }
                ...
```

走读：trace 中 6,842,036,457 dur 24,885 的 `Choreographer#onVsync 155812` 即 ：1175-1179 打点（已证，slice 名与代码逐字一致）。`timestampNanos` 是 SF 下发的帧预期起点（事件头时戳，批12 划界）：若它晚于当前时钟则判为 HAL 时基错误并钳到 now（:1186-1191）。本 trace 未捕获该告警日志（logcat 不在 trace 采集范围——不可判，不引）。onVsync 自身 24,885 ns 就结束，真正的帧执行被转成消息：

```java
// frameworks/base/core/java/android/view/Choreographer.java:1193-1215（摘）
                if (mHavePendingVsync) {
                    Log.w(TAG, "Already have a pending vsync event.  There should only be "
                            + "one at a time.");
                } else {
                    mHavePendingVsync = true;
                }

                mTimestampNanos = timestampNanos;
                mFrame = frame;
                mLastVsyncEventData = vsyncEventData;
                Message msg = Message.obtain(mHandler, this);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, timestampNanos / TimeUtils.NANOS_PER_MS);
            } finally {
                ...
        @Override
        public void run() {
            mHavePendingVsync = false;
            doFrame(mTimestampNanos, mFrame, mLastVsyncEventData);
        }
```

走读（机制四问②的前半"时间戳语义"）：消息按 `timestampNanos/NANOS_PER_MS` 排队——若队列里存在更早的消息，它们先派发（英文注释 ：1181-1184 原文：防止 vsync 事件饿死消息队列）；若队列空，该异步消息立即被派发。**帧预期时间 vs 实际执行时间**的差值由两部分组成：排队等待（队列内更早消息的执行时长）与派发间隙。本 trace 实测：onVsync 起（6,842,036,457）→ doFrame 起（6,842,074,918）差 38,461 ns，其中 onVsync 自身 24,885 ns（止于 6,842,061,342），onVsync 止→doFrame 起的消息派发与 run() 前置间隙 13,576 ns（已证端点，构成未证）。`mHavePendingVsync` 防同刻两个 vsync 消息堆积（:1193-1198）。

### 4.2 doFrame：帧时间校验与跳帧判定（机制四问②）

```java
// frameworks/base/core/java/android/view/Choreographer.java:742-760（摘）
    void doFrame(long frameTimeNanos, int frame,
            DisplayEventReceiver.VsyncEventData vsyncEventData) {
        final long startNanos;
        final long frameIntervalNanos = vsyncEventData.frameInterval;
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                        "Choreographer#doFrame " + vsyncEventData.preferredFrameTimeline().vsyncId);
            }
            FrameData frameData = new FrameData(frameTimeNanos, vsyncEventData);
            synchronized (mLock) {
                if (!mFrameScheduled) {
                    traceMessage("Frame not scheduled");
                    return; // no work to do
                }
```

走读：slice 名 `Choreographer#doFrame 155812` 即 ：748-749 拼出（已证）。第一道闸：`!mFrameScheduled` 时直接返回——收到 vsync 但没有待执行帧（回调已被 removeCallbacks 撤掉，或帧已被别的消息执行过）就不产生任何工作。这是 trace 上"onVsync 有而 doFrame 无"这类缺帧的代码依据；本 trace onVsync 与 doFrame 计数 508:508（q4/q6），且 q1 全序列中 doFrame dur 最小值 508,885 ns——508 条全部是实质帧，无 :754 `Frame not scheduled` 的空帧形态（已证计数与时长下界）。跳帧判定：

```java
// frameworks/base/core/java/android/view/Choreographer.java:764-800（摘）
                long intendedFrameTimeNanos = frameTimeNanos;
                startNanos = System.nanoTime();
                final long jitterNanos = startNanos - frameTimeNanos;
                if (jitterNanos >= frameIntervalNanos) {
                    long lastFrameOffset = 0;
                    ...
                        lastFrameOffset = jitterNanos % frameIntervalNanos;
                        final long skippedFrames = jitterNanos / frameIntervalNanos;
                        if (skippedFrames >= SKIPPED_FRAME_WARNING_LIMIT) {
                            Log.i(TAG, "Skipped " + skippedFrames + " frames!  "
                                    + "The application may be doing too much work on its main "
                                    + "thread.");
                        }
                    ...
                    frameTimeNanos = startNanos - lastFrameOffset;
                    frameData.updateFrameData(frameTimeNanos);
                }

                if (frameTimeNanos < mLastFrameTimeNanos) {
                    ...
                    traceMessage("Frame time goes backward");
                    scheduleVsyncLocked();
                    return;
                }
```

走读（机制四问②的"doFrame 执行超时后的下一个 vsync 排队机制"）：实际开跑时刻 `startNanos` 与帧预期起点 `frameTimeNanos` 的差即 jitterNanos；滞后超过一个帧间隔（:767）说明本帧在队列里等了不止一个脉冲——`skippedFrames = jitterNanos / frameIntervalNanos` 计算跳过档数，帧时间被改写为 `startNanos - lastFrameOffset`（:788），`Skipped N frames!` 日志的阈值 30（:150-151）。**"超时帧"不会在本帧内被取消重排**：改写后的帧时间继续用于本帧全部回调（:789 updateFrameData），保证帧时间单调；被跳过的档位不补偿。帧序回退（:792）与 FPSDivisor（:802-809）两个分支各自 `scheduleVsyncLocked()` 后 return——立即请求下一脉冲、本帧放弃。校验通过后：

```java
// frameworks/base/core/java/android/view/Choreographer.java:811-818（摘）
                mFrameInfo.setVsync(intendedFrameTimeNanos, frameTimeNanos,
                        vsyncEventData.preferredFrameTimeline().vsyncId,
                        vsyncEventData.preferredFrameTimeline().deadline, startNanos,
                        vsyncEventData.frameInterval);
                mFrameScheduled = false;
                mLastFrameTimeNanos = frameTimeNanos;
                mLastFrameIntervalNanos = frameIntervalNanos;
                mLastVsyncEventData = vsyncEventData;
```

走读：`mFrameScheduled = false`（:815）在锁内、五段回调执行前复位——从这一刻起本线程（或其它持锁线程）的 scheduleFrameLocked 可以再次请求下一个 vsync。`mFrameInfo.setVsync` 把预期帧时间/修正帧时间/vsyncId/deadline/startNanos/frameInterval 记入 FrameInfo（jank 统计的数据源，批12 FrameTimeline 衔接）。本 trace 未提取 FrameInfo 载荷字段（未查，§14）。

机制四问②完整回答：**doFrame 执行超时（含回调跑超一个帧间隔）之后，下一个 vsync 不由系统主动排**——vsync 是拉式请求：本帧回调内新排的工作（如 ViewFlinger.postOnAnimation、ViewRootImpl.scheduleTraversals）会在 mFrameScheduled 已复位（:815）后经 scheduleFrameLocked→scheduleVsyncLocked 请求下一个相位；若回调跑完无人再请求，就没有下一帧。执行中的超时只体现为 :767 的跳帧改写与下一次请求落在更晚相位，不在本帧内排队补偿。

## 5. doFrame 五段回调与 trace 子 slice 实测（机制③）

### 5.1 五段的执行顺序（以代码为证）

```java
// frameworks/base/core/java/android/view/Choreographer.java:821-834
            AnimationUtils.lockAnimationClock(frameTimeNanos / TimeUtils.NANOS_PER_MS);

            mFrameInfo.markInputHandlingStart();
            doCallbacks(Choreographer.CALLBACK_INPUT, frameData, frameIntervalNanos);

            mFrameInfo.markAnimationsStart();
            doCallbacks(Choreographer.CALLBACK_ANIMATION, frameData, frameIntervalNanos);
            doCallbacks(Choreographer.CALLBACK_INSETS_ANIMATION, frameData,
                    frameIntervalNanos);

            mFrameInfo.markPerformTraversalsStart();
            doCallbacks(Choreographer.CALLBACK_TRAVERSAL, frameData, frameIntervalNanos);

            doCallbacks(Choreographer.CALLBACK_COMMIT, frameData, frameIntervalNanos);
```

走读（机制③的主线断言）：五段顺序 INPUT→ANIMATION→INSETS_ANIMATION→TRAVERSAL→COMMIT，锁外串行（:824/:827/:828-829/:832/:834 五次调用即顺序的全部依据，已证）。依赖关系由顺序保证：动画先算新值（ANIMATION），遍历再把新值记入 display list（TRAVERSAL）；输入回调里启动的动画会在本帧的 ANIMATION 段被执行——因为 doCallbacks 用"当下"判定到期（§5.2）。COMMIT 段的帧时间修正规则在 doCallbacks :871-889（迟到超两帧时把 mLastFrameTimeNanos 修正为不重复值）。

### 5.2 doCallbacks：段 slice 的产生与"无到期回调不打点"

```java
// frameworks/base/core/java/android/view/Choreographer.java:848-860/:891-900（摘）
    void doCallbacks(int callbackType, FrameData frameData, long frameIntervalNanos) {
        CallbackRecord callbacks;
        long frameTimeNanos = frameData.mFrameTimeNanos;
        synchronized (mLock) {
            ...
            final long now = System.nanoTime();
            callbacks = mCallbackQueues[callbackType].extractDueCallbacksLocked(
                    now / TimeUtils.NANOS_PER_MS);
            if (callbacks == null) {
                return;
            }
            mCallbacksRunning = true;
            ...
        try {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, CALLBACK_TRACE_TITLES[callbackType]);
            for (CallbackRecord c = callbacks; c != null; c = c.next) {
                ...
                c.run(frameData);
            }
```

走读：`callbacks == null`（无**到期回调**）时在 traceBegin 之前 return（:858-860）——该段不产生任何 slice。这是把"trace 里某段缺席"翻译回代码语义的钥匙：段 slice 缺席＝该帧该段队列在提取时刻无到期回调（已证：代码路径唯一；结合 q3/q6 的全程零命中，本 trace 508 帧全部无 input/animation/insets_animation/commit 段 slice）。`traversal` 段 slice（508/508，q3）覆盖的是整段执行窗口——从 traceBegin 到 traceEnd，内含 mTraversalRunnable.run()→doTraversal→performTraversals 的全部时间。

### 5.3 doFrame 155812 的实测分账（勘误任务预置盲区声明）

批09 补查（q2，窗口 [6,842,074,918, ＋564,193) 内 tid1909 全部 slice）：

| rel 起点 | dur（ns） | slice | depth | 产生点 |
|---|---|---|---|---|
| 6,842,074,918 | 564,193 | Choreographer#doFrame 155812 | 0 | Choreographer.java:748-749 |
| 6,842,085,803 | 546,654 | traversal | 1 | Choreographer.java:892（CALLBACK_TRAVERSAL 段） |
| 6,842,147,649 | 480,116 | draw | 2 | ViewRootImpl.java:4463（performDraw 内 Trace.traceBegin） |
| 6,842,162,226 | 99,577 | Record View#draw() | 3 | ThreadedRenderer.java:694 |
| 6,842,268,957 | 353,769 | postAndWait | 3 | DrawFrameTask::postAndWait（frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp:135，批10 走读对象） |

算术分账（全部由上表端点相减，已证）：

- doFrame 起点→traversal 起点：6,842,085,803 − 6,842,074,918 = **10,885 ns**——五段循环里 INPUT/ANIMATION/INSETS_ANIMATION 三个空段（无到期回调，直接 return）加 traversal 段 traceBegin 前置的总开销。
- traversal 占 doFrame：546,654 / 564,193 = **96.9%**；traversal 之外 6,654 ns（doFrame 止 6,842,639,111 − traversal 止 6,842,632,457）。
- draw 占 traversal：480,116 / 546,654 = **87.8%**；draw 之外 66,538 ns（performDraw 的裁决段＋performTraversals 其余部分）。
- draw 之内：Record View#draw()（99,577）＋postAndWait（353,769）合计 453,346，占 94.4%；draw 内除两子 slice 外的未打点段合计 26,770 ns（draw 起→Record 起 14,577＋Record 止→postAndWait 起 7,154＋postAndWait 止→draw 止 5,039），为 updateViewTreeDisplayList 与 onPostDraw 等（构成未证，§14）。

对照帧的同一结构（q5，全部 rel 起点/dur ns）：

| 帧 | doFrame dur | traversal（起点，dur） | draw（起点，dur） | Record View#draw()（起点，dur） | postAndWait（起点，dur） | 附加子 slice |
|---|---|---|---|---|---|---|
| 155787 | 599,000 | 6,725,395,265，582,269 | 6,725,447,495，525,577 | 6,725,462,226，174,539 | 6,725,644,188，325,154 | 无 |
| 155795 | 2,041,154 | 6,759,180,342，2,013,384 | 6,759,307,919，1,878,461 | 6,759,326,803，769,808 | 6,760,107,111，1,071,461 | computePalette ×2（depth 4：6,759,639,803 dur 8,962；6,759,980,919 dur 9,076） |
| 155803 | 1,185,500 | 6,776,469,649，1,150,500 | 6,776,634,726，979,808 | 6,776,671,649，525,154 | 6,777,207,919，399,653 | 无 |
| 155820 | 8,363,692 | 6,865,907,226，8,309,231 | 6,866,128,072，8,081,654 | 6,866,172,226，4,402,116 | 6,870,584,726，3,615,923 | computePalette ×5（dur 11,154/9,384/8,770/9,885/8,461） |
| 155827 | 2,721,884 | 6,892,751,534，2,687,731 | 6,892,904,572，2,527,616 | 6,892,975,495，241,539 | 6,893,230,149，2,191,731 | 无 |

注：computePalette/Record View#draw()/postAndWait 的机制全部"见批10"；本篇只用它们的端点做占用分账（q5 输出逐行存档于过程记录 `批09-q5-关键帧子slice输出.csv`）。

155820 的 8.364 ms 与 155827 的 2.722 ms 说明：在同一个手势窗口内，帧体量在 0.564 ms 与 8.364 ms 之间摆动，摆动主体在 Record View#draw()（99,577 ↔ 4,402,116，差 44 倍）——display list 重录量的波动。"该波动与列表条目量的对应"未证（AppList Adapter 配置盲区，§14）。

### 5.4 结论

doFrame 155812 的 0.564 ms 不是均质的"帧开销"：96.9% 在 traversal 段，其中 87.8% 在 draw，draw 的 94.4% 在 Record View#draw() 与 postAndWait 两个子 slice。五段中的另外四段在本 trace 的每一帧都无到期回调（input 段的批量消费走另一路径——批08 §4.3 的 doConsumeBatchedInput 挂在本段但本帧未触发；已证联合：段 slice 零命中＋代码提前返回条件）。任务预置的"内部阶段无子 slice 盲区"声明按实测勘误（本篇头部），"不得推断各阶段耗时"的限制相应解除为"已实测"。

## 6. traversal 段之内：performTraversals 的三段裁决

### 6.1 doTraversal：拔屏障再遍历

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:2341-2357
    void doTraversal() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);

            if (mProfile) {
                Debug.startMethodTracing("ViewAncestor");
            }

            performTraversals();
            ...
```

走读：traversal 回调被 doFrame 摘出执行时，先复位 mTraversalScheduled、拔掉同步屏障（:2343-2344），再进 performTraversals。先拔后插的原因（遍历过程中可能再次 scheduleTraversals，屏障必须不叠加）与 源码学习副本的批注一致（ViewRootImpl.java study :2555-2560，批09 审计确认无误）。mTraversalScheduled 复位后，本次遍历期间的新失效会排到**下一帧**——本帧不再重入（§9）。

### 6.2 performTraversals 的裁决表与滚动帧的捷径

#31 performTraversals（ViewRootImpl.java:2785-3689）消化窗口状态并按位裁决三段。对滚动帧（invalidate 驱动）起作用的两个判定：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:2921-2924/:3434-3438（摘）
        boolean layoutRequested = mLayoutRequested && (!mStopped || mReportNextDraw);
        if (layoutRequested) {
            ...
            // Ask host how big it wants to be
            windowSizeMayChange |= measureHierarchy(host, lp, mView.getContext().getResources(),
                    desiredWindowWidth, desiredWindowHeight, shouldOptimizeMeasure);
        }
        ...
        final boolean didLayout = layoutRequested && (!mStopped || mReportNextDraw);
        ...
        if (didLayout) {
            performLayout(lp, mWidth, mHeight);
```

走读：`mLayoutRequested` 由 requestLayout（:1979-1985，`mLayoutRequested = true; scheduleTraversals()`）置位；invalidate 链（§9）不碰它。performTraversals 内另在首帧（:2843）/兼容模式变化（:2830）/窗口尺寸变化（:2888）置位，draw/relayout 路径为下次请求新 surface 也置位（:4012/:4847/:4896）——纯滚动帧这些条件全假，故 mLayoutRequested 保持 false。滚动帧的 chain：RecyclerView.scrollByInternal 调 invalidate（RecyclerView.java:1676/:1698）→ mLayoutRequested=false → layoutRequested=false → measureHierarchy 跳过；didLayout=false → performLayout 跳过。relayoutWindow 的入口条件（:3048-3049 `if (mFirst || windowShouldResize || viewVisibilityChanged || params != null || mForceNextWindowRelayout)`）在纯滚动帧全假——跳过 WMS 往返。剩余路径直接走到 ：3666 `performDraw()`。

traversal 段的三个 Trace 打点与 trace 实测的对应（已证联合：打点行＋q3 计数）：

| 打点 | 基准源码树行号 | slice 名 | 本 trace 计数 | 滚动帧是否出现 |
|---|---|---|---|---|
| performMeasure | ViewRootImpl.java:4019 | measure | 0（q3） | 否（layoutRequested=false 跳过） |
| performLayout | ViewRootImpl.java:4095 | layout | 0（q3） | 否（didLayout=false 跳过） |
| performDraw | ViewRootImpl.java:4463 | draw | 508（q3） | 是 |

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4015-4028（摘）
    private void performMeasure(int childWidthMeasureSpec, int childHeightMeasureSpec) {
        if (mView == null) {
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "measure");
        try {
            mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        mMeasuredWidth = mView.getMeasuredWidth();
        mMeasuredHeight = mView.getMeasuredHeight();
        mViewMeasureDeferred = false;
    }
```

走读：measure slice 全 trace 零命中不只覆盖滚动帧——整个 14.878 s 内没有任何一次 performMeasure 执行（q3 全程口径），即采集窗口内 Launcher/AppList 无 requestLayout 级事件（无配置变化/无尺寸请求）。这不是"测量不存在"，是"该窗口内未触发"（q3 是精确计数查询，结论限于 tid 1909 全 trace）。

### 6.3 performDraw 的分叉点

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4449-4479（摘）
    private boolean performDraw() {
        mLastPerformDrawSkippedReason = null;
        if (mAttachInfo.mDisplayState == Display.STATE_OFF && !mReportNextDraw) {
            ...
            return false;
        } else if (mView == null) {
            ...
            return false;
        }

        final boolean fullRedrawNeeded = mFullRedrawNeeded || mSyncBufferCallback != null;
        mFullRedrawNeeded = false;

        mIsDrawing = true;
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "draw");

        addFrameCommitCallbackIfNeeded();
        ...
        try {
            boolean canUseAsync = draw(fullRedrawNeeded, usingAsyncReport && mSyncBuffer);
```

走读（机制四问"遍历三段"的第三段）：draw slice 从 :4463 到 ：4486 traceEnd（q2 实测 155812 帧内 480,116 ns，含 performTraversals 收尾段之后的 draw 全程）。invalidate 设 mDirty 为整窗（:2004），draw()（:4683-4685 fullRedrawNeeded 分支）把脏区置全屏；硬件加速分支：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4727-4768（摘）
        boolean useAsyncReport = false;
        if (!dirty.isEmpty() || mIsAnimating || accessibilityFocusDirty) {
            if (isHardwareEnabled()) {
                ...
                dirty.setEmpty();

                // Stage the content drawn size now. It will be transferred to the renderer
                // shortly before the draw commands get send to the renderer.
                final boolean updated = updateContentDrawBounds();
                ...
                mAttachInfo.mThreadedRenderer.draw(mView, mAttachInfo, this);
```

走读：ThreadedRenderer.draw（:4768）内产生 `Record View#draw()`（ThreadedRenderer.java:694，updateRootDisplayList 的 traceBegin）与 `postAndWait`（DrawFrameTask::postAndWait，hwui native）——155812 帧内两者实测 99,577/353,769 ns（§5.3 表）。这两条的内部（RenderThread 同步、dequeue/queue）"见批10"。本篇结论止于：**doFrame 155812 的 0.564 ms 中，应用主线程側可解释的 87.8% 在 draw slice 内、其中 display list 重录与跨线程等待是两个主体子段（子段归属批10）**。

## 7. RecyclerView 滚动链：scrollByInternal 内部的回收复用（机制④）

RecyclerView 的回收复用机制让移出可见区域的条目视图有机会被后续条目使用，减少反复创建视图的成本。具体是否复用以及是否重新布局，取决于滚动与布局状态。

### 7.1 滚动消费本体（承批08，本篇补链内细节）

批08 已证 MOVE 分支 → scrollByInternal（RecyclerView.java:1653）→ `mLayout.scrollVerticallyBy`（:1667）。本篇补两处链内事实：

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:1653-1673（摘）
    boolean scrollByInternal(int x, int y, MotionEvent ev) {
        int unconsumedX = 0, unconsumedY = 0;
        int consumedX = 0, consumedY = 0;

        consumePendingUpdateOperations();
        if (mAdapter != null) {
            eatRequestLayout();
            onEnterLayoutOrScroll();
            Trace.beginSection(TRACE_SCROLL_TAG);
            if (x != 0) {
                consumedX = mLayout.scrollHorizontallyBy(x, mRecycler, mState);
                unconsumedX = x - consumedX;
            }
            if (y != 0) {
                consumedY = mLayout.scrollVerticallyBy(y, mRecycler, mState);
                unconsumedY = y - consumedY;
            }
            Trace.endSection();
            repositionShadowingViews();
            onExitLayoutOrScroll();
            resumeRequestLayout(false);
        }
```

走读：RV Scroll slice（:1661）内执行 `scrollVerticallyBy`；`eatRequestLayout()`（:1659）把期间子视图的 requestLayout 折叠为 mLayoutRequestEaten（:3918-3924 的 requestLayout 覆写：`mEatRequestLayout == 0 && !mLayoutFrozen` 才放行 super）——滚动消费中不允许遍历重排插队。LinearLayoutManager 侧：

```java
// frameworks/base/core/java/com/android/internal/widget/LinearLayoutManager.java:1293-1317（摘）
    int scrollBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (getChildCount() == 0 || dy == 0) {
            return 0;
        }
        mLayoutState.mRecycle = true;
        ensureLayoutState();
        final int layoutDirection = dy > 0 ? LayoutState.LAYOUT_END : LayoutState.LAYOUT_START;
        final int absDy = Math.abs(dy);
        updateLayoutState(layoutDirection, absDy, true, state);
        final int consumed = mLayoutState.mScrollingOffset
                + fill(recycler, mLayoutState, state, false);
        ...
        final int scrolled = absDy > consumed ? layoutDirection * consumed : dy;
        mOrientationHelper.offsetChildren(-scrolled);
        ...
        return scrolled;
    }
```

走读：`mLayoutState.mRecycle = true`（:1297）打开本次滚动的回收许可；`fill`（:1303）负责补齐与回收，`offsetChildren`（:1311）整体平移既有子视图。返回值是实际消费量（内容到边界时小于 dy，余量走 EdgeEffect，scrollByInternal :1688-1692）。

### 7.2 回收复用的触发时机（机制四问③）

**回收复用**在本篇定义为：ViewHolder 从挂载态转入可复用池的过程——先入 Recycler 的 scrap/缓存层（mAttachedScrap/mCachedViews），溢出后入 RecycledViewPool（RecyclerView.java:5799-5881 的 recycleViewHolderInternal；结构定义 §7.3）。

**触发时机有两条路径，均以代码为证：**

路径一：**scroll 即时回收**——不在 layout 阶段、在 scrollByInternal 的消费循环内：

```java
// frameworks/base/core/java/com/android/internal/widget/LinearLayoutManager.java:1471-1519（摘）
    int fill(RecyclerView.Recycler recycler, LayoutState layoutState,
            RecyclerView.State state, boolean stopOnFocusable) {
        // max offset we should set is mFastScroll + available
        final int start = layoutState.mAvailable;
        if (layoutState.mScrollingOffset != LayoutState.SCROLLING_OFFSET_NaN) {
            ...
            recycleByLayoutState(recycler, layoutState);
        }
        int remainingSpace = layoutState.mAvailable + layoutState.mExtra;
        LayoutChunkResult layoutChunkResult = mLayoutChunkResult;
        while ((layoutState.mInfinite || remainingSpace > 0) && layoutState.hasMore(state)) {
            layoutChunkResult.resetInternal();
            layoutChunk(recycler, state, layoutState, layoutChunkResult);
            ...
            if (layoutState.mScrollingOffset != LayoutState.SCROLLING_OFFSET_NaN) {
                layoutState.mScrollingOffset += layoutChunkResult.mConsumed;
                ...
                recycleByLayoutState(recycler, layoutState);
            }
```

```java
// frameworks/base/core/java/com/android/internal/widget/LinearLayoutManager.java:1449-1458
    private void recycleByLayoutState(RecyclerView.Recycler recycler, LayoutState layoutState) {
        if (!layoutState.mRecycle || layoutState.mInfinite) {
            return;
        }
        if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
            recycleViewsFromEnd(recycler, layoutState.mScrollingOffset);
        } else {
            recycleViewsFromStart(recycler, layoutState.mScrollingOffset);
        }
    }
```

走读：fill 在补新条目**之前**先按 mScrollingOffset 回收将出界者（:1480），每个 layoutChunk 消费后再回收一次（:1509）——滚动方向前端的越界子视图被即时移出（recycleViewsFromStart/End :1360-1436 → recycleChildren :1332-1348 → removeAndRecycleViewAt）。所以"回收发生在哪一次 traversal 的 layout 阶段"的答案：**不发生在 layout 阶段——滚动帧没有 layout 阶段（§6.2），回收在 scroll 消费循环内即时发生**；onLayoutChildren 的 fill（§7.4）在结构变化帧才走，其内部回收机制相同。

路径二：**layout 阶段回收**——dispatchLayoutStep2 → mLayout.onLayoutChildren（RecyclerView.java:3651）→ LinearLayoutManager.onLayoutChildren（LinearLayoutManager.java:460）→ fill（同一函数），触发条件是 requestLayout 级事件（onLayout :3910-3915 的 dispatchLayout 或 consumePendingUpdateOperations :1587-1623 的适配器更新分支）。

### 7.3 缓存结构：Recycler 的三级供给与两级去向

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:5290-5306
    public final class Recycler {
        final ArrayList<ViewHolder> mAttachedScrap = new ArrayList<>();
        ArrayList<ViewHolder> mChangedScrap = null;

        final ArrayList<ViewHolder> mCachedViews = new ArrayList<ViewHolder>();

        private final List<ViewHolder>
                mUnmodifiableAttachedScrap = Collections.unmodifiableList(mAttachedScrap);

        private int mRequestedCacheMax = DEFAULT_CACHE_SIZE;
        int mViewCacheMax = DEFAULT_CACHE_SIZE;

        RecycledViewPool mRecyclerPool;

        private ViewCacheExtension mViewCacheExtension;

        static final int DEFAULT_CACHE_SIZE = 2;
```

走读（结构级，机制四问③的"谁执行"）：取视图侧多级查找按序为——changed scrap（预布局）→ scrap/隐藏/缓存按位置 → 稳定 id → ViewCacheExtension → RecycledViewPool → 新建（tryGetViewHolderForPositionByDeadline，RecyclerView.java:5531-5684；池命中 :5609，新建 :5624 带创建耗时滑动平均入账 :5634）。回收侧去向：mCachedViews 未满（默认 2，:5829-5858）先入一级缓存，满或带 INVALID/REMOVED 等标志则入 RecycledViewPool（:5860 → putRecycledView，RecycledViewPool :5133-5144，每类型默认上限 5，:5061）。回收时不重绑定，复用脏视图才 tryBindViewHolderByDeadline（:5661-5667）。**AppList 场景每帧实际回收/命中级数未测**（trace 无 Recycler 内部打点，盲区，§14）。

### 7.4 onLayoutChildren 的触发条件（批08 遗留问题收口）

批08 §8.2 留给本篇的"条目回收与缓存细节"与"mLayout.onLayoutChildren 触发条件"现收口：

- onLayoutChildren 的两个调用点：dispatchLayoutStep1 的预布局分支（RecyclerView.java:3602，仅 mRunPredictiveAnimations 时）与 dispatchLayoutStep2 的正式布局（:3651）。
- dispatchLayout 的入口三个：onLayout（:3910-3915，ViewRootImpl performLayout 的 host.layout 链终点）、consumePendingUpdateOperations 的两个适配器更新分支（:1590/:1609/:1620）、onMeasure 的 autoMeasure 路径（:3086-3105 的 step1/step2）。
- 滚动帧（invalidate 驱动）三条入口全不触发（§6.2 裁决表＋q3 的 layout/RV OnLayout 零命中——RV OnLayout 名未单独查询，其跳转由 layout 零命中＋didLayout=false 联合推出：已证联合）。

### 7.5 滚动之后的失效与下一帧

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:1675-1700（摘）
        if (!mItemDecorations.isEmpty()) {
            invalidate();
        }

        if (dispatchNestedScroll(consumedX, consumedY, unconsumedX, unconsumedY, mScrollOffset)) {
            ...
        } else if (getOverScrollMode() != View.OVER_SCROLL_NEVER) {
            ...
        }
        if (consumedX != 0 || consumedY != 0) {
            dispatchOnScrolled(consumedX, consumedY);
        }
        if (!awakenScrollBars()) {
            invalidate();
        }
        return consumedX != 0 || consumedY != 0;
```

走读：消费非零 → invalidate（:1698，装饰非空时 ：1676 也有一次；两次都在 mTraversalScheduled/mFrameScheduled 去重下合并，§9）→ ViewRootImpl 失效链 → 下一次 doFrame 的 traversal 段重录 display list（§5.3 的 Record View#draw()）。**一条 MOVE 事件的完整生命周期**：deliver（批08）→ RV Scroll（即时滚动＋回收）→ invalidate → scheduleVsyncLocked → 下一 doFrame 的 draw → （批10 提交）。

## 8. fling 链：能量来源与逐帧循环（机制⑤）

fling 是手指抬起后根据当前速度继续进行的惯性滚动。RecyclerView 将初始速度交给滚动计算器，再在后续帧逐步计算和消费位移，直到滚动结束或被中断。

### 8.1 起点：UP 的速度输出进入 fling()

批08 §9.1 已证：ACTION_UP 时 `mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity)` 输出 xvel/yvel（RecyclerView.java:2903-2914，取负号换算滚动方向），随后 `fling((int) xvel, (int) yvel)`。本篇走读 fling 的门与去向：

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:2003-2048（摘）
        final boolean canScrollHorizontal = mLayout.canScrollHorizontally();
        final boolean canScrollVertical = mLayout.canScrollVertically();

        if (!canScrollHorizontal || Math.abs(velocityX) < mMinFlingVelocity) {
            velocityX = 0;
        }
        if (!canScrollVertical || Math.abs(velocityY) < mMinFlingVelocity) {
            velocityY = 0;
        }
        if (velocityX == 0 && velocityY == 0) {
            // If we don't have any velocity, return false
            return false;
        }
        ...
        if (canScroll) {
            velocityX = Math.max(-mMaxFlingVelocity, Math.min(velocityX, mMaxFlingVelocity));
            velocityY = Math.max(-mMaxFlingVelocity, Math.min(velocityY, mMaxFlingVelocity));
            mViewFlinger.fling(velocityX, velocityY);
            return true;
        }
```

走读：低于 mMinFlingVelocity 的轴清零（:2006-2011），两轴全零直接放弃（回 IDLE，UP 分支 ：2912 setScrollState）；通过后限幅 mMaxFlingVelocity（:2042-2043）交 ViewFlinger.fling。mMinFlingVelocity/mMaxFlingVelocity 来自 ViewConfiguration（构造区赋值，本篇未逐行引用；值未采——未证）。

### 8.2 ViewFlinger.fling：OverScroller 一次解算、逐帧取值

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:4904-4910
        public void fling(int velocityX, int velocityY) {
            setScrollState(SCROLL_STATE_SETTLING);
            mLastFlingX = mLastFlingY = 0;
            mScroller.fling(0, 0, velocityX, velocityY,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
            postOnAnimation();
        }
```

走读：状态置 SETTLING；边界给到整型极限（实际截停在消费侧，run() 的 overscroll 分支 ：4823-4843）。`postOnAnimation()`（:4909）经 View.postOnAnimation 把 run() 投进 Choreographer：

```java
// frameworks/base/core/java/android/view/View.java:19613-19623
    public void postOnAnimation(Runnable action) {
        final AttachInfo attachInfo = mAttachInfo;
        if (attachInfo != null) {
            attachInfo.mViewRootImpl.mChoreographer.postCallback(
                    Choreographer.CALLBACK_ANIMATION, action, null);
        } else {
            // Postpone the runnable until we know
            // on which thread it needs to run.
            getRunQueue().post(action);
        }
    }
```

走读：fling 的续帧挂 **CALLBACK_ANIMATION 档**（doFrame 第二段，§5.1）——fling 帧与 traversal 帧同帧先后执行，这与批08 §4.3 的批量输入（也挂 CALLBACK_INPUT 档）构成"一帧五段各自吃自己工作"的实例。OverScroller.fling 的一次性解算：

```java
// frameworks/base/core/java/android/widget/OverScroller.java:422-438（摘）
    public void fling(int startX, int startY, int velocityX, int velocityY,
            int minX, int maxX, int minY, int maxY, int overX, int overY) {
        // Continue a scroll or fling in progress
        if (mFlywheel && !isFinished()) {
            float oldVelocityX = mScrollerX.mCurrVelocity;
            ...
        }

        mMode = FLING_MODE;
        mScrollerX.fling(startX, velocityX, minX, maxX, overX);
        mScrollerY.fling(startY, velocityY, minY, maxY, overY);
    }
```

```java
// frameworks/base/core/java/android/widget/OverScroller.java:751-789（摘 SplineOverScroller）
            mState = SPLINE;
            double totalDistance = 0.0;

            if (velocity != 0) {
                mDuration = mSplineDuration = getSplineFlingDuration(velocity);
                totalDistance = getSplineFlingDistance(velocity);
            }

            mSplineDistance = (int) (totalDistance * Math.signum(velocity));
            mFinal = start + mSplineDistance;
            ...
    private double getSplineFlingDistance(int velocity) {
        final double l = getSplineDeceleration(velocity);
        final double decelMinusOne = DECELERATION_RATE - 1.0;
        return mFlingFriction * mPhysicalCoeff * Math.exp(DECELERATION_RATE / decelMinusOne * l);
    }
```

走读（机制四问⑤的能量来源）：VelocityTracker 的输出速度在此换成"总距离＋总时长"（摩擦模型参数 mFlingFriction/mPhysicalCoeff/DECELERATION_RATE，OverScroller.java:580 区），此后每帧只按已过时间取曲线值（computeScrollOffset），**不再依赖输入事件**——fling 阶段即使零报点也逐帧产出位移。本 trace 该手势未见 UP（契约 :76：接触只见一次 DOWN/TRACKING_ID 无 UP），**fling 是否在本 trace 发生未证**；§8.3 的循环机制按代码给出，不套用本 trace 断言。

### 8.3 ViewFlinger.run：每帧一步的循环与终止

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:4765-4870（摘）
        public void run() {
            ...
            disableRunOnAnimationRequests();
            consumePendingUpdateOperations();
            final OverScroller scroller = mScroller;
            ...
            if (scroller.computeScrollOffset()) {
                final int x = scroller.getCurrX();
                final int y = scroller.getCurrY();
                final int dx = x - mLastFlingX;
                final int dy = y - mLastFlingY;
                ...
                if (mAdapter != null) {
                    eatRequestLayout();
                    onEnterLayoutOrScroll();
                    Trace.beginSection(TRACE_SCROLL_TAG);
                    if (dx != 0) {
                        hresult = mLayout.scrollHorizontallyBy(dx, mRecycler, mState);
                        overscrollX = dx - hresult;
                    }
                    if (dy != 0) {
                        vresult = mLayout.scrollVerticallyBy(dy, mRecycler, mState);
                        overscrollY = dy - vresult;
                    }
                    Trace.endSection();
```

走读：每帧从 scroller 取增量 dx/dy 交 `scrollVerticallyBy`——**与触拖拽路径共用同一消费函数（LinearLayoutManager.scrollBy）与同一回收机制（§7.2 路径一）**；RV Scroll slice 在此有第二产生点（:4789，批08 C21 已证）。循环的续期与终止：

```java
// frameworks/base/core/java/com/android/internal/widget/RecyclerView.java:4852-4870（摘）
                final boolean fullyConsumedVertical = dy != 0 && mLayout.canScrollVertically()
                        && vresult == dy;
                final boolean fullyConsumedHorizontal = dx != 0 && mLayout.canScrollHorizontally()
                        && hresult == dx;
                final boolean fullyConsumedAny = (dx == 0 && dy == 0) || fullyConsumedHorizontal
                        || fullyConsumedVertical;

                if (scroller.isFinished() || !fullyConsumedAny) {
                    setScrollState(SCROLL_STATE_IDLE); // setting state to idle will stop this.
                    ...
                } else {
                    postOnAnimation();
                    if (mGapWorker != null) {
                        mGapWorker.postFromTraversal(RecyclerView.this, dx, dy);
                    }
                }
```

走读：曲线走完（isFinished）或消费不全（到边界）→ IDLE 止；否则 `postOnAnimation()` 把 run() 再投 CALLBACK_ANIMATION——下一帧继续。**每帧的 RV Scroll＋回收＋invalidate 都发生在这个循环里**，其帧节奏就是 doFrame 的节奏（CALLBACK_ANIMATION 段在 traversal 段之前，同一帧内 fling 位移先于重录）。

### 8.4 postInvalidateOnAnimation 的去向

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:9416-9421（摘）
        private void postIfNeededLocked() {
            if (!mPosted) {
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, this, null);
                mPosted = true;
            }
        }
```

走读：View.postInvalidateOnAnimation（View.java:19778-19785）→ ViewRootImpl.dispatchInvalidateOnAnimation（:9437-9439）→ InvalidateOnAnimationRunnable 入列并 `postCallback(CALLBACK_ANIMATION, this)`（:9418）；其 run()（:9383-9414）在下一帧的 ANIMATION 段逐个调 `view.invalidate()`——失效请求对齐帧节奏，防止"帧中途失效导致当帧重录不一致"。RecyclerView 在 setScrollState 等四处使用（RecyclerView.java:2115/:2138/:2161/:2183，批09 未逐处展开，机制如上）。

## 9. invalidate 与 scheduleTraversals 的防重入：四级串联（机制四问⑤）

机制四问⑤：**invalidate 与 scheduleTraversals 的防重入**。

- **规则**：从 View.invalidate 到 vsync 请求之间有四级去重位，按链序：
  1. `mWillDrawSoon`（ViewRootImpl.java:2802 置位/:3619 复位）：performTraversals 进行中，ViewRootImpl.invalidate（:2003-2008）只更新 mDirty 不排帧——本帧稍后的 draw 用掉脏区；
  2. `mTraversalScheduled`（ViewRootImpl.java:2322）：本帧窗口内第二次 scheduleTraversals 被拦（unscheduleTraversals :2332-2339 与 doTraversal :2343 复位）；
  3. `mFrameScheduled`（Choreographer.java:678）：帧已排定时不重复请求 vsync，新回调只追加进队列（:481）；doFrame :815 复位；
  4. `mWaitingForVsync`（DisplayEventDispatcher.cpp:72）＋`mHavePendingVsync`（Choreographer.java:1193）：native 层与 onVsync 层防止重复请求/重复消息。
- **谁设置**：1/2 由 ViewRootImpl 排程与遍历生命周期；3/4 由 Choreographer 与 libgui。
- **谁执行**：全部在主线程（invalidate 与 scheduleTraversals 同线程，ViewGroup.invalidateChild 硬加速快路径最终调到的就是主线程的 ViewRootImpl.invalidate；ViewGroup.java:6062-6068）。
- **后果**：同一帧窗口内 N 次 invalidate/requestLayout 合并为一次 doFrame 一次 performTraversals；`Frame not scheduled`（Choreographer.java:754）与"段 slice 缺席"是去重漏斗在 trace 上的可见形态（§5.2）。失效链的完整上行（View.invalidateInternal :19294-19342 → mParent.invalidateChild :19331 → ViewGroup 硬加速快路径 :6066 onDescendantInvalidated → ViewRootImpl.onDescendantInvalidated :1993-2000 → invalidate :2003）批09 已逐行核对，与 源码学习副本的批注一致。

## 10. 两 doFrame 起点差 65.630 ms 的分账框架（归因留批15）

### 10.1 分账表

以实测端点为界把 65.630 ms（契约 :75；端点逐 ns 减法 6,842,074,918 − 6,776,444,919 = 65,629,999，批0 复验一 §一 记 65,630,000，1 ns 记法差）切成四段：

| 段 | 起点 | 终点 | 时长（ns） | 段内内容（已证部分） |
|---|---|---|---|---|
| A. doFrame 155803 自身 | 6,776,444,919 | 6,777,630,419 | 1,185,500 | traversal 1,150,500（97.0%）内 draw 979,808；Record 525,154；postAndWait 399,653（q5） |
| B. 帧止→S 起 | 6,777,630,419 | 6,779,836,842 | 2,206,423 | 仅两条小 slice：`RV Prefetch` 61,807（6,777,711,688，GapWorker.run 打点，RecyclerView.java:252 TRACE_PREFETCH_TAG；触发链 postFromTraversal→recyclerView.post(this)，GapWorker.java:169-181）与 `reportTimeline` 31,423（6,779,772,265，InputEventReceiver.java:225 打点、ViewRootImpl.java:9308 调用）；其余 ≈2.11 ms 无 slice（Running，构成未证） |
| C. 主线程 S | 6,779,836,842 | 6,830,764,842 | 50,928,000 | 契约 :69 的 50.928 ms；waker=InputDispatcher tid=1488（批0 复验二 §一）＝等待输入交付 |
| D. 醒后→doFrame 155812 起 | 6,830,764,842 | 6,842,074,918 | 11,310,076 | R 33,923＋Running 1,984,307（含输入消费 1.73 ms，批08 §11.3）＋S 9,106,616 等 vsync（批08 补查二）＋onVsync 前余段 |

合计 1,185,500＋2,206,423＋50,928,000＋11,310,076 = 65,629,999（与端点减法一致，已证算术）。

### 10.2 段语义与任务口径的对应

- 段 C＋D 的 S 部分（50,928,000＋9,106,616）是"输入等待"的主体：C 等 Dispatcher 交付（waker 直证），D 内 S 等 vsync（§3.3）。
- "回调后等待 vsync 10.829 ms"（任务口径）＝scheduleVsyncLocked 起（6,831,246,265）→ doFrame 155812 起（6,842,074,918）＝10,828,653 ns；其中 vsync 到达占 10,790,192（至 onVsync 起），到达后占 38,461 ns（onVsync 自身 24,885＋其后派发间隙 13,576；全部为实测端点算术）。
- 段 B 的 ≈2.11 ms 无 slice 段：候选（收尾消息处理、其他无打点回调）无证据可判——未证（本 trace tid 1909 该窗口仅两条 slice，q7）。
- token 差 9（155803→155812）的机制语义：**两个已执行帧之间 SF 推进了 9 个档位**（§2.1）。65.630 ms 内应用侧没有排帧（段 C/D 的 S 均非排帧等待），档位推进不产生应用帧——"token 有分配无执行"表述中"分配"的确切含义（SF 是否为每个脉冲生成 vsyncId）属 SF 侧机制（批12 划界，本篇不判）。
- 最终归因（50.928 ms 为何等待、2.11 ms 无 slice 段构成、fling 缺席与否对帧序列的影响）留批15。

## 11. 机制四问汇总

### 11.1 vsync 请求与到达之间应用线程在做什么（机制①）

- **规则**：scheduleTraversals→postCallback(CALLBACK_TRAVERSAL)→scheduleFrameLocked→scheduleVsyncLocked→native requestNextVsync 写 BitTube；此后主线程无同步消息可处理（屏障在），Looper poll 等待 fd 事件，无事件即 S（§3.1/§3.3）。
- **谁设置**：ViewRootImpl.scheduleTraversals（失效/requestLayout 链终点）与 Choreographer.scheduleFrameLocked（去重后唯一请求点）。
- **谁执行**：主线程（写请求）；等待由内核 poll 承接；脉冲由 SF 生成（批12）。
- **后果**：本 trace 中该间隔为 10,790,192 ns（6,831,246,265→6,842,036,457，批0 §二.5）；期间主线程 S 9,106,616 ns（批08 补查二）＋收尾 Running；到达侧见 11.2。

### 11.2 onVsync→doFrame 与跳帧（机制②）

- **规则**：vsync fd 可读→handleEvent 取事件（mWaitingForVsync 复位，DisplayEventDispatcher.cpp:130）→dispatchVsync→onVsync（slice 打点、时间戳钳位）→异步消息按帧预期时刻排队→run()→doFrame；doFrame 校验 mFrameScheduled、按 jitterNanos 改写帧时间（跳帧不补偿）、帧序回退则放弃本帧并立即再请求（§4.1/§4.2）。
- **谁设置**：SF 下发时戳；Choreographer 设定排队时刻与跳帧改写。
- **谁执行**：主线程 Looper 派发；doFrame 锁内校验锁外执行。
- **后果**：本 trace onVsync/doFrame/scheduleVsyncLocked 计数 508:508:508（q4/q6），q1 全序列 doFrame dur 最小值 508,885 ns，无 :754 空帧形态（已证计数与时长下界）；跳帧告警日志不在采集范围（盲区）。

### 11.3 doFrame 五段与段缺席语义（机制③）

- **规则**：INPUT→ANIMATION→INSETS_ANIMATION→TRAVERSAL→COMMIT 固定顺序；每段 extractDueCallbacksLocked 无到期回调即不打点直接返回（§5.1/§5.2）。
- **谁设置**：各业务方 postCallback 到对应档（ViewRootImpl→TRAVERSAL；ViewFlinger/InvalidateOnAnimationRunnable→ANIMATION；ViewRootImpl 批量输入→INPUT，批08 §4.3）。
- **谁执行**：doFrame 主线程串行；段内回调 c.run(frameData)（:899）。
- **后果**：本 trace 508 帧全部只有 traversal 段 slice（q3）；输入批量消费段零出现说明该窗口 CALLBACK_INPUT 无到期回调（已证联合）。

### 11.4 回收复用的触发时机（机制④）

- **规则**：滚动帧无 measure/layout 段（mLayoutRequested=false）；回收在 scrollByInternal 的消费循环内即时发生（fill→recycleByLayoutState，LinearLayoutManager.java:1480/:1509），layout 阶段（dispatchLayoutStep2→onLayoutChildren）只在 requestLayout 级事件出现（§7.2/§7.4）。
- **谁设置**：mLayoutState.mRecycle=true 由 LinearLayoutManager.scrollBy :1297 置位。
- **谁执行**：主线程 scroll 消费栈（RV Scroll slice 内）；Recycler 三级供给两级去向（§7.3）。
- **后果**：出界条目即时让位给 fill 的新条目；AppList 每帧回收量未测（盲区）。

### 11.5 fling 的能量来源（机制⑤）

- **规则**：UP 时 VelocityTracker 输出速度→fling() 阈值/限幅→ViewFlinger.fling→OverScroller 一次解算总距离/时长（摩擦模型）→postOnAnimation 挂 CALLBACK_ANIMATION→每帧 run 取曲线增量 scrollVerticallyBy→postOnAnimation 循环直至 IDLE（§8.1-§8.3）。
- **谁设置**：速度样本由应用层三采样点喂入（批08 §9.1）；解算参数是 OverScroller 常量。
- **谁执行**：主线程；每帧一步。
- **后果**：fling 阶段位移与输入事件解耦；本 trace 无 UP（契约 :76），fling 是否发生未证。

### 11.6 invalidate 与 scheduleTraversals 的防重入（机制⑥，承 §9）

四级去重位串联（mWillDrawSoon→mTraversalScheduled→mFrameScheduled→mWaitingForVsync/mHavePendingVsync），同一帧窗口内多次失效合并为一次 doFrame 一次遍历；后果、执行者与漏斗可见形态见 §9。

## 12. trace 配对：本篇数值总表

全部相对时间口径，tid 1909；出处列 C=契约第三节（战役计划与台账.md:68-78）、R1=批0-证据包复验、R2=批0-证据复验二、B08=批08 补查、B09=批09 补查（q1~q7）。

| rel 起点 | dur（ns） | slice/状态 | 出处 | 产生点 |
|---|---|---|---|---|
| 6,725,385,149 | 599,000 | Choreographer#doFrame 155787 | R1 §二.5（rel 6,725,385）/B09 q1/q5 | Choreographer.java:748 |
| 6,725,395,265 | 582,269 | traversal | B09 q5 | Choreographer.java:892 |
| 6,759,164,226 | 2,041,154 | Choreographer#doFrame 155795 | R1（6,759,164）/B09 q5 | 同上 |
| 6,776,444,919 | 1,185,500 | Choreographer#doFrame 155803 | C :68 | Choreographer.java:748 |
| 6,776,469,649 | 1,150,500 | traversal | B09 q5 | Choreographer.java:892 |
| 6,777,630,419 | — | doFrame 155803 止（算术） | 算术 | — |
| 6,777,711,688 | 61,807 | RV Prefetch | B09 q7 | GapWorker.run（RecyclerView.java:252） |
| 6,779,772,265 | 31,423 | reportTimeline | B09 q7 | InputEventReceiver.java:225 |
| 6,779,836,842 | 50,928,000 | 主线程 S（问题 A） | C :69 | — |
| 6,830,764,842 | 33,923 | 主线程 R（waker=InputDispatcher） | B08 补查二/R2 §一 | — |
| 6,830,798,765 | 1,984,307 | 主线程 Running | B08 补查二 | — |
| 6,831,161,226 | 238,231 | RV Scroll | B08 补查一 | RecyclerView.java:1661 |
| 6,831,246,265 | 88,384 | Choreographer#scheduleVsyncLocked | C :75/R1 | Choreographer.java:936 |
| 6,832,783,072 | 9,106,616 | 主线程 S（等 vsync） | B08 补查二 | — |
| 6,842,036,457 | 24,885 | Choreographer#onVsync 155812 | R1 §二.5 | Choreographer.java:1175-1179 |
| 6,842,074,918 | 564,193 | Choreographer#doFrame 155812 | C :75 | Choreographer.java:748 |
| 6,842,085,803 | 546,654 | traversal | B09 q2 | Choreographer.java:892 |
| 6,842,147,649 | 480,116 | draw | B09 q2 | ViewRootImpl.java:4463 |
| 6,842,162,226 | 99,577 | Record View#draw() | B09 q2 | ThreadedRenderer.java:694 |
| 6,842,268,957 | 353,769 | postAndWait | B09 q2 | DrawFrameTask.cpp:135（批10） |
| 6,865,862,842 | 8,363,692 | Choreographer#doFrame 155820 | R1 §二.5/B09 q5 | Choreographer.java:748 |
| 6,892,728,765 | 2,721,884 | Choreographer#doFrame 155827 | R1 §二.5/B09 q5 | 同上 |

计数类（B09 补查，tid 1909 全 trace）：doFrame 508、onVsync 508、scheduleVsyncLocked 508、RV Scroll 855、measure 0、layout 0、input 0、animation 0、insets_animation 0、commit 0（q3/q4/q6）。

间隔类（算术，端点均实测）：scheduleVsyncLocked→onVsync 155812 ＝ 10,790,192；onVsync→doFrame 155812 ＝ 38,461；scheduleVsyncLocked→doFrame 155812 ＝ 10,828,653；155803→155812 起点差 ＝ 65,629,999（契约记 65.630 ms）；155812→155820 ＝ 23,787,924（R1 记 23.788 ms）；155820→155827 ＝ 26,865,923（R1 记 26.866 ms）；155787→155795 ＝ 33,779,077；155795→155803 ＝ 17,280,693。

## 13. 与两个问题的关联

**50.928 ms 主线程 Sleeping（00:00:06.779837–00:00:06.830765）**：本篇把它放进 65.630 ms 帧间空档的分账框架（§10）——它是段 C，且段语义为"等待 Dispatcher 交付输入"（waker 直证，批0 复验二）；同时给出两条机制旁证：①S 止点（6,830,764,842）与下一个 onVsync（6,842,036,457）相距 11,271,615 ns（11.27 ms，算术），该间隔内无 doFrame（批0 复验一 §二.5）——vsync 相位与 S 止点错位，S 的解除不是 vsync 到达；②S 止后主线程第一个可见活动是输入消费链（processInputEventForCompatibility 6,830,952,534、deliverInputEvent 6,831,006,342，批08 补查一）而非帧回调。S 的具体等待条件仍保持 00 篇 §3.7 盲区（本 trace 无 syscall/irq）。

**34.738 ms notifyMotion→prepare**：本篇无新增归因（该空档在 S 内结束，批08 §12 已定界）。本篇为批15 补充的两个对照点：①doFrame 155803 止于 6,777,630,419，比空档起点（6,779,836,842 S）早 2,206,423 ns——**帧调度侧在该窗口没有排队工作**（§10.1 段 B 无 doFrame，q1/q7），50.928 ms 不是渲染积压；②RV Prefetch/reportTimeline 两条小 slice 说明主线程在 S 之前仍在处理帧后收尾（已证存在；与空档的关系未证）。

**"上下 40 多 fps"的应用侧计数链**（契约 :138，批08 §12 环节的延伸）：本篇补两个实测环节——RV Scroll 855 次 vs doFrame 508 次（B09 q4：855/508＝1.68，多报点合并进帧的实测形态）；doFrame 序列 155803→155812→155820→155827 的起点间隔 65.630/23.788/26.866 ms（批0/B09），与 event0 的 >25 ms 间隔占比 17.5%（契约 :76）同量级——帧间隔的稀疏化在应用帧调度侧已可见，但两者因果不判（报点间隔与帧间隔的联动机制未证，批15）。

## 14. 未证边界与盲区清单

1. **盲区（本 trace 无 syscall/irq）**：S 的具体等待条件（00 篇 §3.7 全局口径，§3.3/§13 沿用）。
2. **未证（USE_VSYNC 与属性）**：`debug.choreographer.vsync` 默认 true（Choreographer.java:141-142），本设备属性值未采集；非 vsync 兜底路径（:696-704）在设备上是否启用不可判。
3. **盲区（scheduleVsyncLocked 88,384 ns 的构成）**：JNI 链开销与锁等待不可分（trace 粒度）。
4. **未证（onVsync 24,885 ns 的构成）**：traceBegin/钳位判定/消息构造的逐行归因（同口径）。
5. **未证（VsyncEventData 载荷值）**：frameInterval/deadline/expectedPresentTime 未提取（q 系列只取 slice）；frameInterval 用于跳帧判定的本帧实际值未知。
6. **盲区（SF 侧相位）**：10.790 ms 等待的相位归属、vsyncId 的分配规则——批12 划界。
7. **盲区（AppList Adapter/LayoutManager 配置）**：每帧回收量、缓存命中率、LayoutManager 子类——契约 :136 声明沿用。
8. **未证（fling 是否发生）**：本 trace 接触无 UP（契约 :76）；ViewFlinger.run/SCROLL_STATE_SETTLING 相关 slice 未在窗口提取（RV Scroll 的第二产生点存在性由代码与批08 C21 证明，本 trace 内的触发实例未查）。
9. **未证（段 B ≈2.11 ms 无 slice 段构成）**：q7 仅证明该窗口只有两条 slice；无 slice 不等于无执行。
10. **未证（computePalette 归属）**：depth 4 子 slice 的产生文件未核对（hwui 侧，批10 范围；本篇只引用端点）。
11. **未证（RV OnLayout 计数）**：`RV OnLayout`（RecyclerView.java:224）未单独查询；layout 零命中＋didLayout=false 联合推出其未执行（§7.4）。
12. **未证（mMinFlingVelocity/mMaxFlingVelocity 设备值）**：ViewConfiguration 数值未采。
13. **批注树遗留披露**：study vs 基准源码树剥离 CJK 后的 diff 中存在非本批的插入型差异（View.java 28 行/ViewRootImpl 22 行/RecyclerView 9 行/Choreographer 1 行——全部为历史批次批注块中的英文注释行、`//` 空注释行与块间空行，插入型，无 d/c 操作；细目见过程记录 §七）。

## 15. 断言与三态汇总

本篇机制性结论的编号与三态（断言-证据对照的完整表在《过程记录/批09-过程记录.md》，编号一致）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| G1 | vsyncId 是 SF 时间线档位编号（#15 token），非应用帧序号；本 trace 六帧编号差 8/8/9/8/7 | 已证 | Choreographer.java:1014-1020 注释＋:748-749 拼名；批0 §二.5＋q1 算术 |
| G2 | doFrame slice 名产生点 ：748-749；onVsync 名产生点 ：1175-1179；scheduleVsyncLocked 名产生点 :936 | 已证 | 三处 traceBegin 与 slice 名逐字一致 |
| G3 | 请求链五站：scheduleTraversals:2321→postCallbackDelayedInternal:470→scheduleFrameLocked:677→scheduleVsyncLocked:934→DisplayEventReceiver.scheduleVsync:274 | 已证 | 五段代码读取 |
| G4 | native 第三级去重 mWaitingForVsync（libgui）＋复位在 handleEvent:130 | 已证 | DisplayEventDispatcher.cpp:71-94/:110-131 |
| G5 | vsync fd 经 addFd 注册主线程 Looper（DisplayEventDispatcher.cpp:53-58） | 已证 | 同上 |
| G6 | onVsync 将帧转异步消息按 timestampNanos 排队；onVsync 起→doFrame 起 38,461 ns（含 onVsync 自身 24,885，派发间隙 13,576） | 已证（端点） | Choreographer.java:1203-1205；q2 端点算术 |
| G7 | timestampNanos 为帧预期起点，晚于 now 时钳位告警 | 已证（代码）；告警未捕获（盲区） | Choreographer.java:1185-1191 |
| G8 | 跳帧判定：jitterNanos>=frameIntervalNanos 改写帧时间，跳档不补偿；回退/FPSDivisor 分支放弃本帧再请求 | 已证 | Choreographer.java:764-809 |
| G9 | mFrameScheduled 在五段执行前复位（:815），下一帧靠回调内再请求（拉式）；执行超时无补偿排队 | 已证 | Choreographer.java:811-818＋§4.2 走读 |
| G10 | 五段顺序 INPUT→ANIMATION→INSETS_ANIMATION→TRAVERSAL→COMMIT（:824/:827/:828/:832/:834） | 已证 | 代码顺序 |
| G11 | 段 slice 产生点 doCallbacks:892；无到期回调 :858-860 提前 return 不打点 | 已证 | 代码＋q3/q6 零命中 |
| G12 | 本 trace 508 帧 doFrame 全部含 traversal(508)/draw(508) 子 slice；input/animation/commit/measure/layout 全程 0 | 已证 | q2/q3/q5/q6 |
| G13 | doFrame 155812 分账：traversal 占 96.9%、draw 占 traversal 87.8%、Record+postAndWait 占 draw 94.4% | 已证（算术） | q2 端点 |
| G14 | Record View#draw() 产生点 ThreadedRenderer.java:694；postAndWait 产生点 DrawFrameTask::postAndWait（批10） | 已证（存在与归属）；内部机制见批10 | 两文件读取 |
| G15 | 滚动帧跳过 measure/layout/relayout：invalidate 链不置位 mLayoutRequested；requestLayout 置位（:1982），performTraversals 仅首帧/兼容/尺寸变化时另行置位（:2843/:2830/:2888），滚动帧均不触发 | 已证 | ViewRootImpl.java:1979-1985/:2921/:3048/:3434＋§9 失效链 |
| G16 | doTraversal 先复位再拔屏障再遍历；防屏障叠加 | 已证 | ViewRootImpl.java:2341-2357 |
| G17 | 滚动消费链 scrollByInternal:1653→scrollVerticallyBy:1667→LinearLayoutManager.scrollBy:1293（无 scrollStep，沿用批08 勘误） | 已证 | 三处代码 |
| G18 | 回收即时发生在 scroll 消费循环：fill:1480/:1509→recycleByLayoutState:1449→recycleViewsFromStart/End:1360/:1405 | 已证 | 代码 |
| G19 | layout 阶段回收路径存在：dispatchLayoutStep2:3651→onLayoutChildren→fill；触发条件 requestLayout 级事件 | 已证 | RecyclerView.java:3651/:3910/:1587-1623 |
| G20 | Recycler 结构：mAttachedScrap/mChangedScrap/mCachedViews（默认 2）/RecycledViewPool（默认每类型 5）＋多级供给序 | 已证 | RecyclerView.java:5290-5306/:5531-5684/:5061 |
| G21 | RV Scroll 第二产生点 ViewFlinger.run:4789；续帧 postOnAnimation→CALLBACK_ANIMATION | 已证 | RecyclerView.java:4789/:4865；View.java:19613-19623 |
| G22 | fling 门：mMinFlingVelocity 清零、mMaxFlingVelocity 限幅、嵌套询问后才交 ViewFlinger | 已证 | RecyclerView.java:2003-2048 |
| G23 | OverScroller.fling 一次解算距离/时长；run 每帧取增量消费，IDLE 止 | 已证 | OverScroller.java:422-438/:738-789；RecyclerView.java:4776-4870 |
| G24 | postInvalidateOnAnimation→InvalidateOnAnimationRunnable→CALLBACK_ANIMATION，run 逐 View invalidate | 已证 | ViewRootImpl.java:9416-9421/:9383-9414 |
| G25 | 四级防重入串联（mWillDrawSoon/mTraversalScheduled/mFrameScheduled/mWaitingForVsync＋mHavePendingVsync）合并同窗失效 | 已证 | §9 各行号 |
| G26 | 65.630 ms 分账：1,185,500＋2,206,423＋50,928,000＋11,310,076＝65,629,999 | 已证（算术＋实测端点） | C/R1/B08/B09 q7 |
| G27 | S 的解除由输入驱动而非 vsync（S 止与下一个 onVsync 相距 11.27 ms；间隔内无 doFrame；S 止后首活动为输入消费链） | 已证（时序联合） | C :69＋R1 §二.5 算术＋批08 补查一 |
| G28 | 帧后收尾两 slice：RV Prefetch（GapWorker.post→普通消息）、reportTimeline（InputEventReceiver.java:225） | 已证（存在）；与空档关系未证 | q7＋GapWorker.java:169-181＋InputEventReceiver.java:224-225 |
| G29 | RV Scroll 855 vs doFrame 508＝多报点合并进帧的实测形态 | 已证（计数）；与报点数比例未提取 | q4 |
| G30 | doFrame 155803 止→S 起之间帧调度侧无排队工作（段 B 无 doFrame） | 已证（q1/q7 窗口计数） | q1＋q7 |
| G31 | 本 trace 无 UP→fling 是否发生未证 | 盲区/未证 | 契约 :76 |
| G32 | 任务预置"doFrame 内部无子 slice"声明勘误为"有 traversal/draw 子 slice；四段无到期回调缺席" | 已证 | 本篇版本说明＋q2/q3/q5/q6 |

（完）
