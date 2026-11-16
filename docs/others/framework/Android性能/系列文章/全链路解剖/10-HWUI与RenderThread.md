# 10 · HWUI 与 RenderThread：performDraw 之后的提交、放行与缓冲流转

UI 线程记录绘制内容并同步给 RenderThread，后者执行绘制与缓冲提交。提交返回不代表 GPU 已完成绘制。

> 与任务预置声明的一处实证勘误（沿用批07 JNI 路径勘误的口径）：
> **任务作业单所列 `frameworks/base/core/jni/android_view_HardwareRenderer.cpp` 在本基准源码树不存在。** A13 的实际位置是 `frameworks/base/libs/hwui/jni/android_graphics_HardwareRenderer.cpp`（JNI 函数名保留旧前缀 `android_view_ThreadedRenderer_syncAndDrawFrame`，:261-270）；Java 侧 `HardwareRenderer.java` 亦不在 core/java/android/view/，而在 `frameworks/base/graphics/java/android/graphics/HardwareRenderer.java`（1494 行）。本文全部 JNI 与 Java 引文按实际路径与行号引用。
> 与契约作业单的其他差异（沿用与勘误）：
> ① 契约 :149 所列 pipeline "RecordingCanvas/DisplayList 构建"按概述级走读（§3.2），符合契约"记录/同步/绘制"的定位；DisplayList 指令集全表不展开。
> ② 任务口径"DrawFrame slice 打点位置"实测定名：**本基准源码树唯一的帧 slice 打点是 `DrawFrameTask.cpp:144` 的 `ATRACE_FORMAT("DrawFrames %" PRId64, vsyncId)`——slice 名是复数 DrawFrames 且带帧档位编号**；整棵 hwui 树（grep `DrawFrame`）无单数 DrawFrame 打点。旧样本图03/04 的 "DrawFrames" 与此唯一产生点对应（§5 复验）。
> ③ 任务口径"SkiaOpenGLPipeline::dequeueBuffer"实测勘误：**SkiaOpenGLPipeline 没有 dequeueBuffer 方法**——GL 管道的取缓冲在 EGL 内部（EglManager::beginFrame 只做 makeCurrent＋查询，:581-591）；显式 dequeue 只在 Vulkan 管道（SkiaVulkanPipeline::getFrame :66-69 → `dequeueNextBuffer`）。本样本 trace 的 dequeueBuffer slice 出现在 getFrame 窗口内（EGL 查询路径内部取缓冲，§6.1）。
> ④ 批09 遗留问题收口：doFrame 内 depth 4 的 `computePalette` slice 产生点已核对为 `Bitmap.cpp:399` 的 ATRACE_CALL（§2.3）。
> 时间口径：trace 数值一律 Perfetto 相对时间 `00:00:06.xxxxxx`（rel 基准 start_ts=6,073,694,444,196 ns）；旧样本 finger.perfetto 的 rel 基准 start_ts=1,968,722,243,568 ns（批0 复验二口径），两样本 rel 数值各自成系、不可互比。本篇新增数值全部来自批10 补查（SQL q1~q6 存档于 `过程记录/批10-q*.sql`，输出在过程记录 §三）或对既有实测端点的算术，逐条标明出处。
> 术语沿用 00 篇术语表：#14 doFrame、#15 token（FrameToken）、#16 S/R/R+/D 线程状态、#25 BLAST、#26 BufferQueue、#31 performTraversals。本篇概念：**提前放行**（§4.2）、**双账本**（§2.2）、**RT 栅栏**（§3.5）。

## 0. 本篇范围与上下游边界

批09 止于 `mAttachInfo.mThreadedRenderer.draw(mView, mAttachInfo, this)`（ViewRootImpl.java:4768）。本篇从这一行的函数体内部继续走读：ThreadedRenderer.draw 先在 UI 线程把 View 树录成 display list（`Record View#draw()` slice 窗口），再经 `syncAndDrawFrame` 把整棵树交接给 RenderThread——UI 线程在 `postAndWait` slice 里入睡，RenderThread 执行 `DrawFrames` slice 的同步段与绘制段，直到缓冲经 queueBuffer 交给 BLASTBufferQueue。文件清单与走读范围：

| 文件 | 行数（实测） | 本篇走读范围 |
|---|---|---|
| `frameworks/base/core/java/android/view/ViewRootImpl.java` | 11231 | performDraw（:4449-4541）、draw（:4628-4808）、drawSoftware（:4813-4906）、isHardwareEnabled（:4376-4378）、getUpdatedFrameInfo（:555-564） |
| `frameworks/base/core/java/android/view/ThreadedRenderer.java` | 890 | updateViewTreeDisplayList（:684-691）、updateRootDisplayList（:693-751）、draw（:790-822） |
| `frameworks/base/graphics/java/android/graphics/HardwareRenderer.java` | 1494 | SYNC_* 常量（:88-130）、FrameRenderRequest（:400-432）、syncAndDrawFrame（:456-458）、fence（:675-676） |
| `frameworks/base/libs/hwui/jni/android_graphics_HardwareRenderer.cpp` | — | syncAndDrawFrame JNI（:261-270）、注册表（:913） |
| `frameworks/base/libs/hwui/renderthread/RenderProxy.cpp` | 442 | 构造（:38-46）、syncAndDrawFrame（:140-142）、fence（:216-218）、post/runSync 两型调用（:62-131/:226-232） |
| `frameworks/base/libs/hwui/renderthread/RenderThread.cpp` | 507 | 单例（:202-210）、Choreographer 装配（:230-261）、frameCallback 两拍（:130-141）、threadLoop（:454 区） |
| `frameworks/base/libs/hwui/thread/ThreadBase.h` | 89 | threadLoop/waitForWork/processQueue（:53-84） |
| `frameworks/base/libs/hwui/thread/WorkQueue.h` | 144 | process/post/runSync（:62-110/:121-134） |
| `frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp` | 321 | drawFrame（:125-133）、postAndWait（:135-140）、run（:142-231）、syncFrameState（:233-274）、unblockUiThread（:276-279） |
| `frameworks/base/libs/hwui/renderthread/DrawFrameTask.h` | 145 | SyncResult 位（:43-51）、mLock/mSignal（:110-111）、mSyncQueued（:126） |
| `frameworks/base/libs/hwui/renderthread/CanvasContext.cpp` | 1001 | makeCurrent（:285-305）、prepareTree（:351-461）、draw（:475-653）、notifyFramePending（:470-473） |
| `frameworks/base/libs/hwui/renderthread/EglManager.cpp` | — | beginFrame（:563-573）、damageFrame（:575-586）、swapBuffers（:592-617）、fence（:619-621） |
| `frameworks/base/libs/hwui/renderthread/Frame.cpp` | 44 | map（damage 坐标换算 :24-40） |
| `frameworks/base/libs/hwui/pipeline/skia/SkiaOpenGLPipeline.cpp` | 224 | makeCurrent（:55-67）、getFrame（:69-73）、draw（:75-135）、swapBuffers（:137-152） |
| `frameworks/base/libs/hwui/pipeline/skia/SkiaVulkanPipeline.cpp` | 185 | getFrame（:66-69）、draw（:71-113）、swapBuffers（:115-128）概述级 |
| `frameworks/base/libs/hwui/pipeline/skia/SkiaPipeline.cpp` | — | renderFrame（:441-475 概述）、pinImages（:66-75 区） |
| `frameworks/base/libs/hwui/RecordingCanvas.cpp` | 1123 | Op 结构（:75-150）、回放分派 map（:613-627）概述级 |
| `frameworks/base/libs/hwui/hwui/Bitmap.cpp` | — | computePalette（:398-430） |

边界如下：

- **上游边界**：批09 的交接点——draw slice（ViewRootImpl.java:4463 traceBegin，本 trace 508 条）内的 `Record View#draw()` 与 `postAndWait` 两个 depth 3 子 slice；performDraw 的裁决段（跳过 measure/layout 的机制）全部"见批09"。
- **下游边界**：queueBuffer 之后的缓冲流转——BufferQueue 槽位状态机、BLASTBufferQueue 的 Transaction 组装、`acquireNextBufferLocked` 的细节全部"见批11"（本篇只走到 RT 轨上可见的 acquireBuffer/acquireNextBufferLocked slice 端点）。
- **系统侧边界**：GPU 完成的证据链止于 fence 信号（本篇给出产生点与等待点的代码位置）；virtio-gpu 呈现"见批13"，SurfaceFlinger 消费"见批12"。
- **机制声明**：本篇源码走读给出的是 A13 代码的机制；把机制结论套用到设备行为时必须标注 00 篇 §3.6 的"build 与基准源码树一致性未验证"前提。

主线先闭环：§3→§5 走完"UI 线程提交→入睡→RT 同步段放行→RT 继续绘制"即闭合（机制①⑤）；其后 §6 展开绘制与缓冲段（机制②），§7 给 fence 产生点与等待点（机制③），§8 给软件分支（机制④），§10 给新旧样本的 DrawFrames 配对总表。

## 1. 主线问题：doFrame 155812 的 0.564 ms 里，两个线程各做了什么

本战役最新样本（`20261113-151358-vertical-1912967`）与本篇直接相关的 trace 事实（前两条逐字引自契约核心证据包与批09 已留痕数值，其余为批10 补查，SQL 与输出留痕于过程记录）：

- doFrame 155812 起点 `00:00:06.842075`、dur 564,193（契约 :75；tid 1909 主线程）。
- `Record View#draw()` rel 6,842,162,226 dur 99,577、`postAndWait` rel 6,842,268,957 dur 353,769（批09 补查 q2，depth 3，均在 doFrame 窗口内）。
- 批10 补查一（q1，本批新）：com.yadea.launcher 进程线程表——主线程 tid 1909、**RenderThread tid 2079**、hwuiTask0 tid 2236、hwuiTask1 tid 2237、GPU completion tid 28353。00 篇 §3.3 "新样本 RenderThread tid 未核对"空位由本查询补上。
- 批10 补查二（q2/q3，本批新）：tid 2079 全 trace `DrawFrames %` slice 与 doFrame 同计数（q4：DrawFrames 508、doFrame 508、syncFrameState 508、flush commands 507）；vsyncId 155812 帧的 `DrawFrames 155812` rel 6,842,332,265 dur 3,361,884。
- 批10 补查三（q3，本批新）：DrawFrames 155812 窗口内 RenderThread 轨全部子 slice 的端点序列（§6.3 表）。

本篇的任务：把 `postAndWait`（UI 线程，353,769 ns）与 `DrawFrames 155812`（RT，3,361,884 ns）这两个窗口按代码拆开——UI 线程在哪一行入睡、RT 在哪一行把它叫醒、叫醒后 RT 继续做了 2.7 ms 的什么、为什么两个 slice 的时间重叠不能相加（§5）、以及绘制段最后把缓冲交给了谁（§6）。

## 2. 数据底座：FrameInfo 与 RenderNode 的双账本

### 2.1 FrameInfo：UI 线程填充、RT 消费的帧计时数组

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:555-564
    protected @NonNull FrameInfo getUpdatedFrameInfo() {
        // Since Choreographer is a thread-local singleton while we can have multiple
        // ViewRootImpl's, populate the frame information from the current viewRootImpl before
        // starting the draw
        FrameInfo frameInfo = mChoreographer.mFrameInfo;
        mViewFrameInfo.populateFrameInfo(frameInfo);
        mViewFrameInfo.reset();
        mInputEventAssigner.notifyFrameProcessed();
        return frameInfo;
    }
```

走读：ThreadedRenderer.draw（:809）在 `syncAndDrawFrame` 之前调用它，把 vsyncId/deadline/帧时间等装入 `mChoreographer.mFrameInfo`。JNI 侧把这个 long 数组整体拷进 RenderProxy 的 `mDrawFrameTask.frameInfo()`（android_graphics_HardwareRenderer.cpp:268 `env->GetLongArrayRegion(frameInfo, 0, frameInfoSize, proxy->frameInfo())`），RT 侧的 run() 与 syncFrameState 从同一数组读 vsyncId（DrawFrameTask.cpp:143/:237）与 deadline（:170/:238）。数组长度在 JNI 入口校验（:264-266 `LOG_ALWAYS_FATAL_IF(frameInfoSize != UI_THREAD_FRAME_INFO_SIZE, ...)`）。

### 2.2 双账本：RenderNode 的 staging 侧与正式侧

**双账本**在本篇定义为：RenderNode 上并存的两组状态——UI 线程写的 staging 侧（RenderNode.h:149 `mutateStagingProperties()`、:254 `mStagingProperties` 成员）与 RT 侧读取的正式属性，两账在 RT 的同步段合一（RenderNode.cpp:287 `syncProperties() { mProperties = mStagingProperties; }`）。

走读（机制①的数据前提）：display list 树是两个线程共享的数据结构。UI 线程录制期间随时改 staging（View 属性动画、失效重录都只写 staging），RT 只在 `DrawFrameTask::run` 的同步段里通过 prepareTree 把 staging 推进正式侧（RenderNode.cpp:217 区 prepareTree 主循环 → :291 区 pushStagingPropertiesChanges）。源码学习副本的批注（RenderNode.cpp :311 块）记录了推送顺序约束：先推 animator（其 setupStartValue 要读旧属性）再 syncProperties 覆盖正式属性。**跨线程交接只有这一个点**——这是 UI 线程提前放行后"其余时间各画各的"得以成立的结构基础（DrawFrameTask.cpp study 块 ：130-137 同口径）。

### 2.3 位图的 palette 子 slice（批09 遗留收口）

```cpp
// frameworks/base/libs/hwui/hwui/Bitmap.cpp:398-407（study 批注块 :403-405 后）
BitmapPalette Bitmap::computePalette(const SkImageInfo& info, const void* addr, size_t rowBytes) {
    ATRACE_CALL();
```

走读（批09 未证边界 #10 收口）：`computePalette` slice（批09 q5 实测 depth 4，155795 帧两条 dur 8,962/9,076、155820 帧五条 dur 8,461~11,154）的产生点即 ：399 的 ATRACE_CALL。触发链：`Bitmap.palette()`（Bitmap.h:142-148，generationId 变化才重算）在录制期被调用（RecordingCanvas.cpp:493 的 VectorDrawable palette 取值等）——所以这些 slice 落在 UI 线程的 `Record View#draw()` 窗口内，与帧内新位图（列表条目图片）出现对应。批09 表中 155795/155820 两条 computePalette 位于 Record 窗口（起止端点比对）与此链一致（已证联合）。

### 2.4 录制入口：RenderNode.beginRecording 与 prepareTree 的判定链

录制侧（UI 线程）的入口在 Java：

```java
// frameworks/base/graphics/java/android/graphics/RenderNode.java:444-451
    public @NonNull RecordingCanvas beginRecording(int width, int height) {
        if (mCurrentRecordingCanvas != null) {
            throw new IllegalStateException(
                    "Recording currently in progress - missing #endRecording() call?");
        }
        mCurrentRecordingCanvas = RecordingCanvas.obtain(this, width, height);
        return mCurrentRecordingCanvas;
    }
```

走读：mRootNode.beginRecording（ThreadedRenderer.java:733）取得 RecordingCanvas——每个 View 的 updateDisplayListIfDirty 也走同一入口录自己的节点（树内逐节点各持一条指令流）。endRecording（RenderNode.java:476）把指令流定稿挂到节点上；此后节点的 display list 在 RT 侧由 prepareTree 读走。

消费侧（RT 同步段）进入 CanvasContext::prepareTree 后的 canDrawThisFrame 判定链：

```cpp
// frameworks/base/libs/hwui/renderthread/CanvasContext.cpp:387-427（摘）
    if (CC_UNLIKELY(!hasSurface())) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
        info.out.canDrawThisFrame = false;
        return;
    }

    if (CC_LIKELY(mSwapHistory.size() && !info.forceDrawFrame)) {
        nsecs_t latestVsync = mRenderThread.timeLord().latestVsync();
        SwapHistory& lastSwap = mSwapHistory.back();
        nsecs_t vsyncDelta = std::abs(lastSwap.vsyncTime - latestVsync);
        ...
        if (vsyncDelta < 2_ms) {
            // Already drew for this vsync pulse, UI draw request missed
            // the deadline for RT animations
            info.out.canDrawThisFrame = false;
        }
    } else {
        info.out.canDrawThisFrame = true;
    }
    ...
    if (info.out.canDrawThisFrame) {
        ...
        int err = mNativeSurface->reserveNext();
        if (err != OK) {
            mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
            info.out.canDrawThisFrame = false;
            ALOGW("reserveNext failed, error = %d (%s)", err, strerror(-err));
            if (err != TIMED_OUT) {
                // A timed out surface can still recover, but assume others are permanently dead.
                setSurface(nullptr);
                return;
            }
        }
    }
```

走读（机制①的补充判定）：即使 UI 线程按时送达了任务，RT 还有三道本侧判定——①无 Surface 直接跳帧（:387-391）；②**同 vsync 去重**：上一帧 swap 记录的 vsync 与当前相差小于 2 ms 说明这一拍已画过（RT 动画迟到的重复请求），跳过（:393-408，study :428 块口径）；③`reserveNext()` 预占本帧缓冲——预占失败分两档：TIMED_OUT 只跳本帧等下一拍，其他错误判表面死亡当场 setSurface(nullptr)（:416-427，study :447 块口径）。AppList 的帧全部通过三道判定（q4 的 DrawFrames:doFrame 508:508 且 q3 内各帧都有 Drawing/flush 序列——无跳帧形态；已证联合）。判定为跳帧时 run() 走 §4.2 的 else 分支（flush 纹理上传＋waitOnFences，不产缓冲），UI 线程的 syncResult 带 SYNC_FRAME_DROPPED 位（HardwareRenderer.java:122）。

## 3. 提交链：UI 线程从 draw slice 到入睡（机制①前半）

HWUI 是 Android 的硬件加速界面渲染体系。UI 线程准备和记录要绘制的内容，再与 RenderThread 协作完成渲染提交；RenderThread 是执行渲染工作的线程，不等同于 GPU。

### 3.1 performDraw 的入口裁决（承批09，只列交接事实）

批09 §6.3 已走读 performDraw 的前置裁决（屏幕灭/无根视图放弃、fullRedrawNeeded 判定、:4463 `"draw"` traceBegin、:4467 usingAsyncReport）。本篇从 draw() 的硬件分支继续：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4727-4768（摘）
        if (!dirty.isEmpty() || mIsAnimating || accessibilityFocusDirty) {
            if (isHardwareEnabled()) {
                ...
                dirty.setEmpty();

                // Stage the content drawn size now. It will be transferred to the renderer
                // shortly before the draw commands get send to the renderer.
                final boolean updated = updateContentDrawBounds();
                ...
                if (forceDraw) {
                    mAttachInfo.mThreadedRenderer.forceDrawNextFrame();
                }
                mAttachInfo.mThreadedRenderer.draw(mView, mAttachInfo, this);
```

走读（机制④的判定点，先立此存照）：`isHardwareEnabled()`（:4376-4378，`mAttachInfo.mThreadedRenderer != null && mAttachInfo.mThreadedRenderer.isEnabled()`）为真即进入 ThreadedRenderer 路径，且硬件路径当场 `dirty.setEmpty()`（:4746）——矩形脏区概念不进 RT（study 块 ：5229/:5233 口径）。软件分支在 §8 展开。

### 3.2 ThreadedRenderer.draw：录制与提交两段

```java
// frameworks/base/core/java/android/view/ThreadedRenderer.java:790-822（摘）
    void draw(View view, AttachInfo attachInfo, DrawCallbacks callbacks) {
        attachInfo.mViewRootImpl.mViewFrameInfo.markDrawStart();

        updateRootDisplayList(view, callbacks);
        ...
        final FrameInfo frameInfo = attachInfo.mViewRootImpl.getUpdatedFrameInfo();

        int syncResult = syncAndDrawFrame(frameInfo);
        if ((syncResult & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
            Log.w("OpenGLRenderer", "Surface lost, forcing relayout");
            // We lost our surface. For a relayout next frame which should give us a new
            // surface from WindowManager, which hopefully will work.
            attachInfo.mViewRootImpl.mForceNextWindowRelayout = true;
            attachInfo.mViewRootImpl.requestLayout();
        }
        if ((syncResult & SYNC_REDRAW_REQUESTED) != 0) {
            attachInfo.mViewRootImpl.invalidate();
        }
    }
```

走读：三步——markDrawStart（FrameInfo 打点，gfxinfo 帧耗时基准）、updateRootDisplayList（录制，其内部 `Record View#draw()` slice 已在批09 q2 实测 99,577 ns）、syncAndDrawFrame（提交，返回 SYNC_* 位）。两个结果位各自映射回 ViewRootImpl：丢面 → 强制下帧 relayout 换新 Surface（HardwareRenderer.java:105 的注释原文给该位起名 "the reward is GPU-accelerated drawing, better find that Surface!"）；REDRAW_REQUESTED → RT 动画无法自驱时要求 UI 侧立即重排。

录制段的增量语义（概述级，契约"DisplayList 构建概述"落点）：

```java
// frameworks/base/core/java/android/view/ThreadedRenderer.java:693-695/:732-750（摘）
    private void updateRootDisplayList(View view, DrawCallbacks callbacks) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Record View#draw()");
        updateViewTreeDisplayList(view);
        ...
        if (mRootNodeNeedsUpdate || !mRootNode.hasDisplayList()) {
            RecordingCanvas canvas = mRootNode.beginRecording(mSurfaceWidth, mSurfaceHeight);
            ...
                canvas.enableZ();
                canvas.drawRenderNode(view.updateDisplayListIfDirty());
                canvas.disableZ();
            ...
        }
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }
```

走读：`updateViewTreeDisplayList`（:684-691）逐 View 把 PFLAG_INVALIDATED 换手进 mRecreateDisplayList 并调 `updateDisplayListIfDirty()`——树内各节点按需重录，未失效节点直接复用已有 display list。`RecordingCanvas` 是记录器：drawRenderNode 不执行绘制，只把"引用该子节点的指令"写进 mRootNode 的指令缓冲（native 侧 RecordingCanvas.cpp:76-85 的 Op 结构带 8 位 type 与 24 位 skip，RT 侧重放按 DisplayListData::map 的函数表逐 Op 分派——RecordingCanvas.cpp:613-627，概述至此，指令集全表不展开）。`Record View#draw()` slice 的宽度因此是"树内重录量"的观测面：批09 q5 中六帧的 Record 端点从 99,577 到 4,402,116 摆动 44 倍，摆动主体即失效节点数量的差异（归因留批15）。

### 3.3 syncAndDrawFrame：一次 JNI 调用交接全部内容

```java
// frameworks/base/graphics/java/android/graphics/HardwareRenderer.java:455-458
    @SyncAndDrawResult
    public int syncAndDrawFrame(@NonNull FrameInfo frameInfo) {
        return nSyncAndDrawFrame(mNativeProxy, frameInfo.frameInfo, frameInfo.frameInfo.length);
    }
```

```cpp
// frameworks/base/libs/hwui/jni/android_graphics_HardwareRenderer.cpp:261-270
static int android_view_ThreadedRenderer_syncAndDrawFrame(JNIEnv* env, jobject clazz,
                                                          jlong proxyPtr, jlongArray frameInfo,
                                                          jint frameInfoSize) {
    LOG_ALWAYS_FATAL_IF(frameInfoSize != UI_THREAD_FRAME_INFO_SIZE,
                        "Mismatched size expectations, given %d expected %zu", frameInfoSize,
                        UI_THREAD_FRAME_INFO_SIZE);
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    env->GetLongArrayRegion(frameInfo, 0, frameInfoSize, proxy->frameInfo());
    return proxy->syncAndDrawFrame();
}
```

```cpp
// frameworks/base/libs/hwui/renderthread/RenderProxy.cpp:140-142
int RenderProxy::syncAndDrawFrame() {
    return mDrawFrameTask.drawFrame();
}
```

走读（机制①的主线断言一）：整棵 RenderNode 树的交接只发生在这一次 native 调用——Java 侧没有第二条路径把内容递给 RT。JNI 层不做任何同步逻辑，只拷数组、转发；RenderProxy 也不排队，直接进 DrawFrameTask。

### 3.4 drawFrame 与 postAndWait：入队与入睡

```cpp
// frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp:125-140
int DrawFrameTask::drawFrame() {
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    mSyncResult = SyncResult::OK;
    mSyncQueued = systemTime(SYSTEM_TIME_MONOTONIC);
    postAndWait();

    return mSyncResult;
}

void DrawFrameTask::postAndWait() {
    ATRACE_CALL();
    AutoMutex _lock(mLock);
    mRenderThread->queue().post([this]() { run(); });
    mSignal.wait(mLock);
}
```

走读（机制①的主线断言二，`postAndWait` slice 的两个端点）：`postAndWait` slice 从 ：136 ATRACE_CALL 起到 **mSignal.wait 返回**止——不是到 run() 执行完。入队（:138 `mRenderThread->queue().post(...)`）与入睡（:139）在同一临界区内：持 mLock 投递，RT 侧 run() 里 `unblockUiThread`（:276-279）也要拿 mLock 才能 `mSignal.signal()`，所以不存在"signal 落在 wait 之前"的丢失窗口（study DrawFrameTask.h :121 块同口径）。`mSyncQueued`（:129）在入队时刻打点，RT 侧 run() 用它算 `syncDelayDuration`（:145）——任务在 RT 队列里的等待时长，即机制四问④"RT 忙时 UI 线程的可见后果"的记账字段。

WorkQueue 的执行模型（机制⑤的载体）：

```cpp
// frameworks/base/libs/hwui/thread/WorkQueue.h:62-80（摘）
    void process() {
        auto now = clock::now();
        std::vector<WorkItem> toProcess;
        {
            std::unique_lock _lock{mLock};
            if (mWorkQueue.empty()) return;
            toProcess = std::move(mWorkQueue);
            auto moveBack = find_if(std::begin(toProcess), std::end(toProcess),
                                    [&now](WorkItem& item) { return item.runAt > now; });
            ...
        }
        for (auto& item : toProcess) {
            item.work();
        }
    }
```

```cpp
// frameworks/base/libs/hwui/thread/ThreadBase.h:70-78
    virtual bool threadLoop() override {
        Looper::setForThread(mLooper);
        while (!exitPending()) {
            waitForWork();
            processQueue();
        }
        Looper::setForThread(nullptr);
        return false;
    }
```

走读：RenderThread 继承 ThreadBase（RenderThread.h:130 `class RenderThread : private ThreadBase`），主循环＝"poll 等待→取整批到期 WorkItem→逐个执行"。`post` 是 `postAt(0, ...)`（WorkQueue.h:93-95，立即到期），`runSync` 是"post 一个 packaged_task 后 get() 阻塞等 future"（:105-110）——两种调用型的区分即机制四问⑤的"阻塞/非阻塞"答案（:62-110 一处定义）。DrawFrameTask::postAndWait 用 post 型（非阻塞投递，自己另用 mSignal 等待同步段），RenderProxy::fence 用 runSync 型（§3.5）。

### 3.5 RT 栅栏：runSync 空任务

**RT 栅栏**在本篇定义为：`RenderProxy::fence()`（RenderProxy.cpp:216-218，`mRenderThread.queue().runSync([]() {});`）——把一个空任务排到 RT 队列尾，返回即此前投递的全部任务（含在途绘制的同步段）已执行完。HardwareRenderer.fence()（:675-676，`nFence(mNativeProxy)`）是它的 Java 面。调用点：performDraw 的 mReportNextDraw 分支（ViewRootImpl.java:4530，本批补注）；FrameRenderRequest.setWaitForPresent(true) 的 syncAndDraw 路径（HardwareRenderer.java:427-429）。普通帧不走栅栏——提前放行（§4.2）让 UI 线程不等 RT。

## 4. RenderThread 侧：同步段与提前放行（机制①后半＋机制⑤）

### 4.1 run() 的骨架

```cpp
// frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp:142-176（摘）
void DrawFrameTask::run() {
    const int64_t vsyncId = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameTimelineVsyncId)];
    ATRACE_FORMAT("DrawFrames %" PRId64, vsyncId);
    nsecs_t syncDelayDuration = systemTime(SYSTEM_TIME_MONOTONIC) - mSyncQueued;

    bool canUnblockUiThread;
    bool canDrawThisFrame;
    {
        TreeInfo info(TreeInfo::MODE_FULL, *mContext);
        info.forceDrawFrame = mForceDrawFrame;
        mForceDrawFrame = false;
        canUnblockUiThread = syncFrameState(info);
        canDrawThisFrame = info.out.canDrawThisFrame;
        ...
    }

    // Grab a copy of everything we need
    CanvasContext* context = mContext;
    ...
    // From this point on anything in "this" is *UNSAFE TO ACCESS*
    if (canUnblockUiThread) {
        unblockUiThread();
    }
```

走读（`DrawFrames` slice 的两个端点）：slice 从 ：144 的 ATRACE_FORMAT 起到 **run() 返回**止。run() 在 RenderThread 执行（postAndWait 投递的 lambda）；名字里的编号是 FrameInfo 的 FrameTimelineVsyncId——与 UI 线程 doFrame slice 名尾编号（Choreographer.java:749 拼入的首选档位 vsyncId）同源，所以新旧样本里 DrawFrames 与 doFrame 能按编号配对（§5/§10）。

### 4.2 syncFrameState：同步段做什么、提前放行由什么决定

```cpp
// frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp:233-274（摘）
bool DrawFrameTask::syncFrameState(TreeInfo& info) {
    ATRACE_CALL();
    int64_t vsync = mFrameInfo[static_cast<int>(FrameInfoIndex::Vsync)];
    ...
    mRenderThread->timeLord().vsyncReceived(vsync, intendedVsync, vsyncId, frameDeadline,
            frameInterval);
    bool canDraw = mContext->makeCurrent();
    mContext->unpinImages();

    for (size_t i = 0; i < mLayers.size(); i++) {
        mLayers[i]->apply();
    }
    mLayers.clear();
    mContext->setContentDrawBounds(mContentDrawBounds);
    mContext->prepareTree(info, mFrameInfo, mSyncQueued, mTargetNode);
    ...
    // If prepareTextures is false, we ran out of texture cache space
    return info.prepareTextures;
}
```

走读：同步段四件事——TimeLord 记 vsync 元数据、makeCurrent 绑定 EGL 上下文（CanvasContext.cpp:285-305 的三态结果处理：Failed 即 setSurface(nullptr) 自愈）、延迟层逐个 apply、`prepareTree` 把 staging 双账本推进正式侧并做动画/脏区/缓冲预占（CanvasContext.cpp:351-461）。**返回值是 `info.prepareTextures`**：SkiaPipeline::pinImages 在 prepareTree 期间把 CPU 位图上传进 GrContext 缓存并 pin 住（study SkiaPipeline.cpp :66 块），任一张 pin 失败（缓存满）即整体返回 false。

**提前放行**在本篇定义为：`run()` 在 syncFrameState 返回真后、`context->draw()` 之前调用 `unblockUiThread()` 的路径（DrawFrameTask.cpp:174-176）——UI 线程的 postAndWait slice 在这里结束，与本帧的 GPU 绘制并行。

```cpp
// frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp:189-209（摘）
    nsecs_t dequeueBufferDuration = 0;
    if (CC_LIKELY(canDrawThisFrame)) {
        dequeueBufferDuration = context->draw();
    } else {
        // Do a flush in case syncFrameState performed any texture uploads. Since we skipped
        // the draw() call, those uploads (or deletes) will end up sitting in the queue.
        // Do them now
        if (GrDirectContext* grContext = mRenderThread->getGrContext()) {
            grContext->flushAndSubmit();
        }
        // wait on fences so tasks don't overlap next frame
        context->waitOnFences();
    }
    ...
    if (!canUnblockUiThread) {
        unblockUiThread();
    }
```

走读（机制①的主线断言三）：放行只有两条路径——同步段后早放行（canUnblockUiThread 为真，常态）与帧尾晚放行（:207-209 兜底：prepareTextures 失败或需 UI 重录时，UI 线程一直睡到绘制结束）。提前放行成立时，`context->draw()` 的全部耗时（取缓冲、Skia 绘制、flush、swap、queue）都发生在 UI 线程已被放行之后——**这就是 doFrame 与 DrawFrames 两 slice 时间重叠的机制根源**（§5 用新旧样本端点验证）。

### 4.3 RenderThread 自身的 vsync 装置（机制⑤的另一半）

```cpp
// frameworks/base/libs/hwui/renderthread/RenderThread.cpp:130-141
void RenderThread::frameCallback(int64_t vsyncId, int64_t frameDeadline, int64_t frameTimeNanos,
                                 int64_t frameInterval) {
    mVsyncRequested = false;
    if (timeLord().vsyncReceived(frameTimeNanos, frameTimeNanos, vsyncId, frameDeadline,
                                 frameInterval) &&
        !mFrameCallbackTaskPending) {
        ATRACE_NAME("queue mFrameCallbackTask");
        mFrameCallbackTaskPending = true;
        nsecs_t runAt = (frameTimeNanos + mDispatchFrameDelay);
        queue().postAt(runAt, [=]() { dispatchFrameCallbacks(); });
    }
}
```

走读：RT 有自己的 Choreographer（:230-246 initializeChoreographer：AChoreographer 的事件 fd 挂 RT Looper）与 VsyncSource 双实现（ChoreographerSource/DummyVsyncSource，:150-187）。这条装置只服务 **RT 自驱动动画**：CanvasContext.prepareTree 检出动画未完时 `mRenderThread.postFrameCallback(this)`（CanvasContext.cpp:433-443），下一拍 vsync 由 dispatchFrameCallbacks 直接调 CanvasContext::prepareAndDraw——全程不回 UI 线程（study CanvasContext :465 块口径）。AppList 的界面帧不在这条线上（应用帧由 UI 线程 Choreographer 驱动，批09 主线）；本 trace tid 2079 的 DrawFrames 508 条全部由 postAndWait 投递触发、无 prepareAndDraw 独立形态（q4 计数与 q3 序列联合支持；"RT 自驱动画帧在本 trace 是否出现过"未单独验证——未证，§12）。

### 4.4 threadLoop：RT 主循环与帧回调的双集合

```cpp
// frameworks/base/libs/hwui/renderthread/RenderThread.cpp:429-458（摘）
bool RenderThread::threadLoop() {
    setpriority(PRIO_PROCESS, 0, PRIORITY_DISPLAY);
    Looper::setForThread(mLooper);
    ...
    initThreadLocals();

    while (true) {
        waitForWork();
        processQueue();

        if (mPendingRegistrationFrameCallbacks.size() && !mFrameCallbackTaskPending) {
            mVsyncSource->drainPendingEvents();
            mFrameCallbacks.insert(mPendingRegistrationFrameCallbacks.begin(),
                                   mPendingRegistrationFrameCallbacks.end());
            mPendingRegistrationFrameCallbacks.clear();
            requestVsync();
        }

        if (!mFrameCallbackTaskPending && !mVsyncRequested && mFrameCallbacks.size()) {
            // TODO: Clean this up. This is working around an issue where a combination
            // of bad timing and slow drawing can result in dropping a stale vsync
            // on the floor (correct!) but fails to schedule to listen for the
            // next vsync (oops), so none of the callbacks are run.
            requestVsync();
        }
    }
```

走读（机制⑤的完整答案）：主循环每圈做四件事——①waitForWork（ThreadBase.h:53-66，poll 等队列或 fd 事件，定时到 vsync 分派的 postAt 期限）；②processQueue（整批执行到期 WorkItem，DrawFrameTask 的 run 就在这里被执行）；③把 pending 注册集合合并进正式回调集合并 requestVsync（:441-447，study RenderThread.h :276 块口径：注册延迟到队列与 vsync 事件都排空后，保证新回调对齐下一拍而不消费处理中的这一拍）；④伤疤补丁（:449-454）——坏时机＋慢绘制组合会把过期 vsync 正确丢弃却忘了预约下一拍，主循环每圈补查"有回调、无 pending 任务、无 vsync 请求"就补一次 requestVsync（study :483 块口径）。线程优先级在入口即提至 PRIORITY_DISPLAY（:430，与主 UI 线程同档，study :454 块口径）。

```cpp
// frameworks/base/libs/hwui/renderthread/RenderThread.cpp:422-427
void RenderThread::requestVsync() {
    if (!mVsyncRequested) {
        mVsyncRequested = true;
        mVsyncSource->requestNextVsync();
    }
}
```

走读：vsync 请求去重位 mVsyncRequested（RenderThread.h:225 声明，study :272 块口径）——frameCallback 收到脉冲即复位（:132）。dispatchFrameCallbacks 执行前先 requestVsync 预约下一拍（:414，"假设大概率继续动画"，study :436 块口径），多余的 vsync 请求由去重位吸收。

## 5. 重叠不可相加：新旧样本 DrawFrames 配对

### 5.1 机制陈述

`doFrame`（UI 线程）与 `DrawFrames`（RT）的时间窗口重叠，来源是 §4.2 的提前放行：UI 线程在 RT 完成同步段后即被唤醒并继续跑 doFrame 的收尾（COMMIT 段、消息循环），而 RT 继续做取缓冲、GPU 绘制、flush、swap、queue。两个 slice 各自记录**各自线程上的占用时长**，共同区间是"两线程并行工作"的窗口，不是同一段工作的两份耗时——**不可相加**。相加会重复计入并行区间，把帧产出时间高估。

### 5.2 新样本配对（vsyncId 155812）

| 量 | rel 起点 | dur（ns） | 止点（算术） | 线程/轨 | 出处 |
|---|---|---|---|---|---|
| Choreographer#doFrame 155812 | 6,842,074,918 | 564,193 | 6,842,639,111 | tid 1909 UI | 契约 :75 |
| Record View#draw() | 6,842,162,226 | 99,577 | 6,842,261,803 | tid 1909 UI（depth 3） | 批09 q2 |
| postAndWait | 6,842,268,957 | 353,769 | 6,842,622,726 | tid 1909 UI（depth 3） | 批09 q2 |
| DrawFrames 155812 | 6,842,332,265 | 3,361,884 | 6,845,694,149 | tid 2079 RT | 批10 q3 |

重叠算术（全部端点相减，已证）：

- postAndWait 起 → DrawFrames 起：6,842,332,265 − 6,842,268,957 = **63,308 ns**（UI 入睡到 RT 开跑，含 WorkQueue 唤醒与 run() 前置）。
- postAndWait ∩ DrawFrames：[6,842,332,265, 6,842,622,726] = **290,461 ns**——UI 线程睡眠窗口内 RT 在跑同步段。
- doFrame ∩ DrawFrames：[6,842,332,265, 6,842,639,111] = **306,846 ns**——postAndWait 止（6,842,622,726）之后 UI 线程还有 16,385 ns 的 doFrame 收尾（COMMIT 段与 traceEnd）。
- DrawFrames 止 − doFrame 止：6,845,694,149 − 6,842,639,111 = **3,055,038 ns**——UI 线程完全离开后 RT 独占工作的时长。
- syncFrameState（q3：6,842,365,957 dur 172,385，内含 prepareTree 6,842,401,957 dur 131,808）止于 6,842,538,342，与 postAndWait 止（6,842,622,726）差 **84,384 ns**——syncFrameState 返回→unblockUiThread signal→UI 线程 wait 返回的唤醒传播。

### 5.3 旧样本配对（vsyncId 340845，契约数值复验）

批10 补查（q5 系，finger.perfetto，rel 基准 1,968,722,243,568）：

| 量 | rel 起点 | dur（ns） | 止点（算术） | tid | 出处 |
|---|---|---|---|---|---|
| Choreographer#doFrame 340845 | 9,717,203,823 | 531,807 | 9,717,735,630 | 1913 UI | q5e（契约图04 数值 531.807μs 逐字命中） |
| DrawFrames 340845 | 9,717,448,861 | 2,280,038 | 9,719,728,899 | 2107 RT | q5d（契约图03 数值 2.280038ms 逐字命中） |

- doFrame ∩ DrawFrames：[9,717,448,861, 9,717,735,630] = **286,769 ns**。
- DrawFrames 起 − doFrame 起 = 245,038 ns；DrawFrames 止 − doFrame 止 = **1,993,269 ns**（RT 独占延伸）。
- 旧样本 RenderThread tid=2107（q5 线程表）——00 篇 §3.3 旧样本 tid 记录一致。

两样本独立得到同一形态：UI 线程 slice 短（531,807/564,193）、RT slice 长（2,280,038/3,361,884）、重叠段占 UI slice 的 54%/54.4%（286,769/531,807 与 306,846/564,193）。图03/04 的"重叠"在两个样本上都是**提前放行**的 trace 形态，与 DrawFrameTask.cpp:174-176 的代码一一对应（已证：代码路径唯一＋两端点在各自线程轨上可测）。

## 6. 绘制段：CanvasContext::draw 到缓冲提交（机制②）

CanvasContext 是 HWUI 中负责组织窗口绘制及帧提交的对象。本节从 RenderThread 侧说明它如何取得绘制目标、执行绘制并提交缓冲；提交结束不代表显示器已经显示该帧。

### 6.1 getFrame：Frame 的装配（GL 与 Vulkan 两管道）

```cpp
// frameworks/base/libs/hwui/renderthread/CanvasContext.cpp:504-513（摘）
    ScopedActiveContext activeContext(this);
    mCurrentFrameInfo->set(FrameInfoIndex::FrameInterval) =
            mRenderThread.timeLord().frameIntervalNanos();

    mCurrentFrameInfo->markIssueDrawCommandsStart();

    Frame frame = mRenderPipeline->getFrame();
    SkRect windowDirty = computeDirtyRect(frame, &dirty);

    ATRACE_FORMAT("Drawing " RECT_STRING, SK_RECT_ARGS(dirty));
```

GL 管道的 getFrame（SkiaOpenGLPipeline.cpp:69-73）→ `mEglManager.beginFrame(mEglSurface)`：

```cpp
// frameworks/base/libs/hwui/renderthread/EglManager.cpp:563-573
Frame EglManager::beginFrame(EGLSurface surface) {
    LOG_ALWAYS_FATAL_IF(surface == EGL_NO_SURFACE, "Tried to beginFrame on EGL_NO_SURFACE!");
    makeCurrent(surface);
    Frame frame;
    frame.mSurface = surface;
    eglQuerySurface(mEglDisplay, surface, EGL_WIDTH, &frame.mWidth);
    eglQuerySurface(mEglDisplay, surface, EGL_HEIGHT, &frame.mHeight);
    frame.mBufferAge = queryBufferAge(surface);
    eglBeginFrame(mEglDisplay, surface);
    return frame;
}
```

走读（本批对 study :513 批注偏差的修正依据）：GL 管道的 Frame 只携带宽/高/bufferAge/EGLSurface 句柄（Frame.cpp:24-40 另提供 damage 坐标从左上原点到左下原点的 map），**没有缓冲指针**——ANativeWindow::dequeueBuffer 不在本函数内显式调用。Vulkan 管道不同：SkiaVulkanPipeline::getFrame（:66-69）直接 `vulkanManager().dequeueNextBuffer(mVkSurface)`。本样本 trace 的 dequeueBuffer slice（q3：155812 帧两条嵌套 87,192/40,731，位于 eglQuerySurface 之后、eglBeginFrame 之前）是 vendor EGL 在 bufferAge 查询路径内部取缓冲的产物——两层同名 slice 说明 ANativeWindow 层与 BufferQueueProducer 层各打一点（层内归属"见批11"）。

### 6.2 Skia 渲染与提交

```cpp
// frameworks/base/libs/hwui/pipeline/skia/SkiaOpenGLPipeline.cpp:107-126（摘）
    sk_sp<SkSurface> surface(SkSurface::MakeFromBackendRenderTarget(
            mRenderThread.getGrContext(), backendRT, this->getSurfaceOrigin(), colorType,
            mSurfaceColorSpace, &props));

    LightingInfo::updateLighting(lightGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface,
                SkMatrix::I());
    ...
    {
        ATRACE_NAME("flush commands");
        surface->flushAndSubmit();
    }
```

走读：fbo0（FBOID 0＝窗口系统帧缓冲）按颜色模式包成 GrBackendRenderTarget 再包成 SkSurface（study SkiaOpenGLPipeline :83 块口径）；`renderFrame` 是 SkiaPipeline 的 GPU 帧渲染总入口（基准源码树 SkiaPipeline.cpp:441，源码学习副本的批注块在其前：tryCapture 直通 → renderLayersImpl :455 先画离屏层 → renderFrameImpl :475 画主内容）——播放 UI 线程录下的 Op 流。`flush commands` slice（本批补注：155812 帧 rel 6,843,017,803 dur 587,308）是 flushAndSubmit 把 Op 流提交给 GL 驱动的窗口，trace 上内含 `OpsTask::onPrepare`（57,769）与 `OpsTask::onExecute`（417,423）及单个 FillRectOp/TextureOp/AtlasTextOp 子 slice。**CPU 侧命令提交到此结束；GPU 是否画完由 fence 承接（§7）**。

### 6.3 swapBuffers 与 queueBuffer：缓冲离手

```cpp
// frameworks/base/libs/hwui/pipeline/skia/SkiaOpenGLPipeline.cpp:137-152
bool SkiaOpenGLPipeline::swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                                     FrameInfo* currentFrameInfo, bool* requireSwap) {
    GL_CHECKPOINT(LOW);

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    *requireSwap = drew || mEglManager.damageRequiresSwap();

    if (*requireSwap && (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty)))) {
        return false;
    }

    return *requireSwap;
}
```

```cpp
// frameworks/base/libs/hwui/renderthread/EglManager.cpp:592-603（摘）
bool EglManager::swapBuffers(const Frame& frame, const SkRect& screenDirty) {
    if (CC_UNLIKELY(Properties::waitForGpuCompletion)) {
        ATRACE_NAME("Finishing GPU work");
        fence();
    }

    EGLint rects[4];
    frame.map(screenDirty, rects);
    eglSwapBuffersWithDamageKHR(mEglDisplay, frame.mSurface, rects, screenDirty.isEmpty() ? 0 : 1);
    ...
```

走读（本批修正 study :149 批注的落点）：真正调用的是 `eglSwapBuffersWithDamageKHR`（:600）而非无 damage 的 eglSwapBuffers；damage 为空时 rect 数传 0。CanvasContext::draw 在 swap 后处理错误与帧账本（CanvasContext.cpp:555-570 区：TIMED_OUT 重试、其他错误 abandon 表面），返回值是 `mCurrentFrameInfo->get(FrameInfoIndex::DequeueBufferDuration)`（:653）——run() 的 `dequeueBufferDuration`（DrawFrameTask.cpp:191/:230）即来自这里，并成为 ADPF 实际工时上报的扣减项（:223-225）。

本样本 155812 帧的 RT 轨完整序列（批10 q3，全部 rel/dur ns）：

| rel 起点 | dur | slice | 对应代码 |
|---|---|---|---|
| 6,842,332,265 | 3,361,884 | DrawFrames 155812 | DrawFrameTask.cpp:144 |
| 6,842,365,957 | 172,385 | syncFrameState | :234（内含 prepareTree 131,808） |
| 6,842,585,534 | 2,846 | eglQuerySurface | EglManager.cpp:568-569 |
| 6,842,618,303 | 87,192 | dequeueBuffer（内层 40,731 @6,842,624,918） | EGL 内部（§6.1） |
| 6,842,683,726 | 2,577 | HWC release fence 653 has signaled | 消费端 fence 已信号 |
| 6,842,728,918 | 3,847 | eglBeginFrame | EglManager.cpp:571 |
| 6,842,752,957 | 2,925,808 | Drawing 0.00 158.00 1280.00 640.00 | CanvasContext.cpp:513 |
| 6,843,017,803 | 587,308 | flush commands（onPrepare 57,769／onExecute 417,423） | SkiaOpenGLPipeline.cpp:124 |
| 6,843,637,034 | 176,692 | waitOnFences | CanvasContext.cpp:531 |
| 6,843,827,495 | 7,500 | dispatchSetFrameTimelineInfo | CanvasContext.cpp:539 区 |
| 6,843,850,265 | 1,449,961 | eglSwapBuffersWithDamageKHR（内 st_context_flush 196,923/st_flush 183,307） | EglManager.cpp:600 |
| 6,844,165,188 | 1,116,077 | queueBuffer（内层 699,884 @6,844,199,188） | EGL 内部→Surface::queueBuffer |
| 6,844,347,803 | 509,539 | onFrameAvailable - VRI[AppListActivity]#8(f:0,a:1) | 缓冲可用回调 |
| 6,844,366,418 | 29,270 | acquireBuffer | BLAST 消费 |
| 6,844,405,072 | 442,000 | acquireNextBufferLocked - …(f:0,a:1)frame=396 | BLASTBufferQueue（批11） |
| 6,844,867,534 | 24,500 | waitForever | — |
| 6,845,066,495 | 98,193 | Trace GPU completion fence 653 | GPU completion 线程侧信号记录 |
| 6,845,319,265 | 60,038 | reportMetricsWithPresentTime | CanvasContext.cpp:670 |
| 6,845,735,226 | 5,923 | notifyFramePending（在 DrawFrames 止后） | CanvasContext.cpp:470 |

序列判读（已证，端点＋代码双侧）：①dequeueBuffer 轻松（<90μs）说明本帧取缓冲无等待（§9 问题③）；②"HWC release fence 653 has signaled" 是**上一个**帧的 release fence 已被显示控制器消费的记录——缓冲槽位可复用的证据（q3 中 155820 帧对应 fence 654）；③queueBuffer 之后 1.2 ms 内 BLAST 路径的 onFrameAvailable/acquireBuffer/acquireNextBufferLocked 在同一 RT 轨出现——**BLAST 的消费端逻辑与 HWUI 生产端同线程**（frame=396 是缓冲帧号，与 vsyncId 155812 是两套编号，#15/#25 口径）；④DrawFrames 止（6,845,694,149）与最后可见 slice（notifyFramePending 止 6,845,741,149）错位——notifyFramePending 是下一帧排程时 ViewRootImpl.scheduleTraversals 的 notifyRendererOfFramePending（批09 §0 引用链）在 RT 侧的执行（RenderProxy.cpp:230-232 post 型），不属于本帧 run()。

### 6.4 damage 合成：computeDirtyRect 的 bufferAge 分档

```cpp
// frameworks/base/libs/hwui/renderthread/CanvasContext.cpp:933-981（摘）
SkRect CanvasContext::computeDirtyRect(const Frame& frame, SkRect* dirty) {
    if (frame.width() != mLastFrameWidth || frame.height() != mLastFrameHeight) {
        // can't rely on prior content of window if viewport size changes
        dirty->setEmpty();
        ...
    } else if (mHaveNewSurface || frame.bufferAge() == 0) {
        // New surface needs a full draw
        dirty->setEmpty();
    } else {
        if (!dirty->isEmpty() && !dirty->intersect(SkRect::MakeIWH(frame.width(), frame.height()))) {
            ...
        }
        profiler().unionDirty(dirty);
    }

    if (dirty->isEmpty()) {
        dirty->setIWH(frame.width(), frame.height());
    }
    ...
    if (frame.bufferAge() > 1) {
        if (frame.bufferAge() > (int)mSwapHistory.size()) {
            // We don't have enough history to handle this old of a buffer
            // Just do a full-draw
            dirty->setIWH(frame.width(), frame.height());
        } else {
            for (int i = mSwapHistory.size() - 1;
                 i > ((int)mSwapHistory.size()) - frame.bufferAge(); i--) {
                dirty->join(mSwapHistory[i].damage);
            }
        }
    }

    return windowDirty;
}
```

走读（Frame.mBufferAge 的消费端）：UI 线程的矩形脏区概念在硬件路径不直接可用（:4746 已 setEmpty），RT 侧的伤害计算靠 bufferAge 分档——视口尺寸变化或新面（bufferAge 0）全量重画；bufferAge 1（同一缓冲连续使用）沿用传入脏区；bufferAge>1（该缓冲缺了 N 帧内容）从 SwapHistory 逐帧 union 补齐，历史不够只能全画（study CanvasContext :996 块口径）。`windowDirty`（截存副本）随后作为 queueBuffer 的 damage 随缓冲交给消费者（§6.3 表的 swap.damage 写入在 :572-579 区）。本 trace 的 invalidate 全屏脏区（批09 §6.3 口径）下，damage 合成的减量路径实际未启用——AppList 每帧的 dirty 为全屏（fullRedrawNeeded 或多源 union），bufferAge 优化的收益在本样本不可观测（已证联合：Drawing slice 名内矩形 0 158 1280 640 非全屏但 UI 脏区为全屏——两者的差是 RT 侧裁剪的产物）。

### 6.5 帧账本收尾：SwapHistory 与双路径回执

```cpp
// frameworks/base/libs/hwui/renderthread/CanvasContext.cpp:579-607（摘）
        swap.swapCompletedTime = systemTime(SYSTEM_TIME_MONOTONIC);
        swap.vsyncTime = mRenderThread.timeLord().latestVsync();
        if (didDraw) {
            nsecs_t dequeueStart =
                    ANativeWindow_getLastDequeueStartTime(mNativeSurface->getNativeWindow());
            if (dequeueStart < mCurrentFrameInfo->get(FrameInfoIndex::SyncStart)) {
                // Ignoring dequeue duration as it happened prior to frame render start
                // and thus is not part of the frame.
                swap.dequeueDuration = 0;
            } else {
                swap.dequeueDuration =
                        ANativeWindow_getLastDequeueDuration(mNativeSurface->getNativeWindow());
            }
            swap.queueDuration = 0;
        }
        mCurrentFrameInfo->set(FrameInfoIndex::DequeueBufferDuration) = swap.dequeueDuration;
        mCurrentFrameInfo->set(FrameInfoIndex::QueueBufferDuration) = swap.queueDuration;
        ...
    mCurrentFrameInfo->markSwapBuffersCompleted();
```

走读：SwapHistory 环形账本记录每帧的 damage/vsync/swap 时刻与 dequeue/queue 耗时（:572 `mSwapHistory.next()` 摘自环形缓冲），它同时是 §6.4 的 damage 合成与 §2.4 同 vsync 去重的输入。dequeueDuration 的取数规则（:582-591）有一个边界：dequeue 若发生在 SyncStart 之前（跨帧排队），不计入本帧——这就是 §9.3 的"dequeue 等待的帧时延后果"记账口径。回执分两路（:631-649，study :676 块口径）：SurfaceControl 模式（mExpectSurfaceStats）走 reportMetricsWithPresentTime＋4 帧环补报（present 时间等 SF 消费后才存在，study :720 块口径）；普通模式当场 markFrameCompleted 并 finishFrame 进 JankTracker。两路的分支在本 trace 的具体取值未查（mExpectSurfaceStats 由 setSurfaceControl 决定，BLAST 窗口是否走 SurfaceControl 模式未验证——未证，§12）。

## 7. fence 的产生点与等待点（机制③）

fence 是表示某项异步工作是否完成的同步对象。缓冲在生产者与消费者之间交接时，双方可借助 fence 避免过早读取或复用；分析时必须区分它等待的是哪项工作。

机制四问（fence）：**规则**——GPU 命令异步执行，跨进程/跨线程的缓冲交接需要一根"GPU 完成时间线"对象（sync fence fd）；**谁设置**——生产/消费两端在缓冲交接点由驱动生成；**谁执行**——内核在 GPU 完成时信号 fence，等待方在 fd 上阻塞或由 EGL/ANativeWindow 代等；**后果**——未信号的 fence 阻止缓冲内容被读或被复用，违反即读到撕裂内容或覆盖未读内容。

本篇只覆盖应用侧（HWUI 生产端）的产生点与等待点；acquire fence 在 SF 侧的等待与 release fence 的回传归还"见批11"，present fence"见批12"。

**产生点（release fence，本进程发出）**：

```cpp
// frameworks/base/libs/hwui/renderthread/EglManager.cpp:619-621
void EglManager::fence() {
    EGLSyncKHR fence = eglCreateSyncKHR(mEglDisplay, EGL_SYNC_FENCE_KHR, NULL);
    eglClientWaitSyncKHR(mEglDisplay, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER_KHR);
```

走读：fence()（:619-621；waitForGpuCompletion 调试开关打开时由 swapBuffers :595 调用）用 EGL_SYNC_FENCE_KHR 在 GL 命令流中插同步原语并 CPU 阻塞等信号——这是"eglSwapBuffers 返回≠GPU 已画完"的直接代码证据：**常态下 eglSwapBuffersWithDamageKHR 只把命令入队就返回，GPU 完成与 CPU 无关**。随 queueBuffer 交给消费者的 release fence 由 GL 驱动在 swap 路径内部生成（EGL 契约面，驱动内部代码不在树内——产生动作已证存在于该路径，内部实现盲区）。EglManager.cpp:726 区（study 块 ：726）另有一处生成释放 fence 交还生产者的路径（native fence 扩展优先，glFlush 后导出 fd）——SurfaceTexture 纹理消费方向，不属于窗口帧主线。

**等待点（本进程内）**：

- `waitOnFences`（CanvasContext.cpp:531 调用，trace 155812 帧 176,692 ns）：等本帧依赖的异步任务完成（纹理上传、FrameCommitCallback 的 CommonPool future 等），保证下一帧开始前收尾——study CanvasContext :970 块口径。
- `DrawFrameTask::run` 跳绘分支的 `context->waitOnFences()`（DrawFrameTask.cpp:200）：跳绘不等于跳清理，本帧没画的纹理上传也要等完，不与下一帧重叠（study :216 块口径）。
- **RT 栅栏**（§3.5）：UI 线程等待 RT 队列清空，mReportNextDraw 场景付出此等待。
- acquire fence 的等待在 SF 侧 latchBuffers（批12/批11 划界）；本进程 RT 不等 acquire fence。

**本 trace 的 fence 证据**：q3 中 155812 帧的 "HWC release fence 653 has signaled"（6,842,683,726）与 "Trace GPU completion fence 653"（6,845,066,495）说明 fence 信号事件在 RenderThread 与 GPU completion 线程（tid 28353）双轨可见；fence 编号 653/654 是显示控制器侧的 fence 序号（与 vsyncId、frame=396 三套编号并存，各自成系）。"GPU completion fence 653 信号时刻＝本帧 GPU 真实画完"的等式未在本 trace 验证（653 是哪一帧的 fence 未与 vsyncId 对账——未证，§12）。

### 7.1 fence 的两个通用原语：fenceWait 与 createReleaseFence

EglManager 还有一对不挂在帧主线上、由纹理消费（SurfaceTexture）方向使用的原语：

```cpp
// frameworks/base/libs/hwui/renderthread/EglManager.cpp:660-677（摘）
status_t EglManager::fenceWait(int fence) {
    if (!hasEglContext()) {
        ALOGE("EglManager::fenceWait: EGLDisplay not initialized");
        return INVALID_OPERATION;
    }

    if (EglExtensions.waitSync && EglExtensions.nativeFenceSync) {
        // Block GPU on the fence.
        ...
        eglWaitSyncKHR(mEglDisplay, sync, 0);
        ...
    } else {
        // Block CPU on the fence.
        status_t err = waitForeverOnFence(fence, "EglManager::fenceWait");
```

```cpp
// frameworks/base/libs/hwui/renderthread/EglManager.cpp:703-718（摘）
status_t EglManager::createReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence, int* nativeFence) {
    *nativeFence = -1;
    ...
    if (EglExtensions.nativeFenceSync) {
        EGLSyncKHR sync = eglCreateSyncKHR(mEglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
        ...
        glFlush();
        int fenceFd = eglDupNativeFenceFDANDROID(mEglDisplay, sync);
```

走读（等待点与产生点的另一半，study EglManager :681/:726 块口径）：`fenceWait` 是"等生产者 fence"——扩展齐时用 eglWaitSyncKHR 把等待挂进 GPU 命令流（CPU 不阻塞），否则 CPU 在 fence fd 上无限等（waitForeverOnFence :646）；`createReleaseFence` 是"生成释放 fence 交还生产者"——native fence 扩展优先（glFlush 后 eglDupNativeFenceFDANDROID 导出 fd，:716-717），降级路径用 EGL_SYNC_FENCE_KHR＋1 秒超时等旧 fence。两个原语的等待/产生方向与 §7 的主线互为镜像：主线（窗口帧）的 release fence 产生在 swap 内部由驱动生成，这两条在 SurfaceTexture 纹理路径（TextureView/相机预览场景）显式成对出现。AppList 主窗口不在这两条路径上（AppList 本 trace 无 SurfaceTexture 类 slice 佐证——未查证，不外推）。

## 8. 硬件加速关闭时：drawSoftware 分支（机制④）

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4796-4799（分叉点）
                if (!drawSoftware(surface, mAttachInfo, xOffset, yOffset,
                        scalingRequired, dirty, surfaceInsets)) {
                    return false;
                }
```

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4835/:4888-4893（摘）
            canvas = mSurface.lockCanvas(dirty);
            ...
            mView.draw(canvas);
            ...
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas);
```

走读（机制四问④）：`isHardwareEnabled()` 为假时走 drawSoftware——UI 线程 `lockCanvas` 直接取一块可绘位图、`mView.draw(canvas)` **同步**把整树画进位图、`unlockCanvasAndPost` 当场提交。与硬件路径的四个差异：①**没有 RenderThread 参与**——绘制与提交全部在 UI 线程串行；②**没有 display list 复用**——每帧整树重画（脏区矩形裁剪是唯一减量）；③queueBuffer 发生在 UI 线程的 unlockCanvasAndPost 内，`postAndWait`/`DrawFrames` 两个 slice 都不存在；④硬件"请求了但未启用"的中间态不降级软件（:4778-4793，重新初始化后 scheduleTraversals 等下一帧——study :5285 块口径）。AppList 在本 trace 全部 508 帧走硬件路径（traversal→draw→Record/postAndWait 三层 slice 508/508/508，批09 q3＋q2；software 路径的 lockCanvas 类 slice 未在窗口查到——q3 的 draw 计数与 Record/postAndWait 计数联合支持，已证联合）。

## 9. 机制四问汇总

### 9.1 syncAndDrawFrame 返回时 GPU 是否已画完（机制①）

- **规则**：syncAndDrawFrame 只保证同步段完成——staging 双账本推进正式侧（§2.2）、纹理 pin 成功；随后 run() 早放行 UI 线程（DrawFrameTask.cpp:174-176），context->draw()（取缓冲/Skia/flush/swap/queue）全部在放行之后执行；flush commands 只是 CPU 侧命令提交（§6.2），GPU 完成由 release fence 信号承接（§7）。
- **谁设置**：DrawFrameTask::run 的放行顺序（AOSP A13 代码既定）；PinImages 的返回值决定走早放行还是晚放行。
- **谁执行**：RT 执行 run()；UI 线程睡在 postAndWait 的 mSignal.wait（:139）。
- **后果（trace 证据）**：vsyncId 155812 帧的 postAndWait 止（6,842,622,726）早于 Drawing 起（6,842,752,957）130,231 ns、早于 DrawFrames 止（6,845,694,149）3,071,423 ns——**syncAndDrawFrame 返回时 GPU 必然未画完，且大概率尚未开始提交**（Drawing slice 未开始）。已证。

### 9.2 DrawFrame(s) slice 起止对应代码哪两点（机制②）

- **规则**：slice 名 `DrawFrames <vsyncId>` 的唯一产生点是 DrawFrameTask.cpp:144 的 ATRACE_FORMAT（复数＋编号，grep 全树无双数外打点）；起点＝run() 第一行执行时刻（UI 线程 postAndWait 入队后 RT 从 WorkQueue 取出 lambda 的时刻），终点＝run() 返回（含 context->draw()、waitOnFences、晚放行分支、HintSession 上报的全程）。
- **谁设置/执行**：DrawFrameTask::run 在 RenderThread 执行；编号取 FrameInfo 的 FrameTimelineVsyncId（与 UI 线程 doFrame slice 名尾编号同源）。
- **后果**：新旧样本按编号配对成立（§5.2/§5.3）；DrawFrames 内部子 slice（syncFrameState/Drawing/flush commands/eglSwapBuffersWithDamageKHR/queueBuffer）与代码函数一一对应（§6.3 表）。已证。

### 9.3 dequeue buffer 的等待场景与帧时延后果（机制③）

- **规则**：GL 管道的取缓冲在 EGL 内部（§6.1）；BufferQueue 无 FREE 槽时 dequeue 阻塞等待消费端归还（等待机制在 producer 侧，细节"见批11"）；CanvasContext::setSurface 对启用了 enableTimeout 的窗口设 4 秒 dequeue 超时（CanvasContext.cpp:180-183，`ANativeWindow_setDequeueTimeout(window, 4000_ms)`——study :179 块口径），超时经 ReliableSurface 暂存为 TIMED_OUT 错误码，draw 循环按 §6.3 的错误分诊重试或弃帧。
- **谁设置**：WindowManager 建立窗口时决定 enableTimeout；阻塞窗口由 BufferQueue 的槽位状态决定。
- **谁执行**：RT（dequeue 发生在 RT 的 Drawing 前窗口）。
- **后果（本 trace 实测）**：155812 帧 dequeue 87,192/40,731 ns、155820 帧 158,077/80,000 ns（q3）——无等待场景；长等待的 trace 形态是 Drawing slice 起点被推迟而 postAndWait（UI 侧）不变，本样本未出现（已证本样本无；长等待的帧时延后果按机制陈述为未触发）。

### 9.4 RT 阻塞时 UI 线程的可见后果（机制④，与 9.1 呼应）

- **规则**：UI 线程睡在 postAndWait（mSignal.wait）；RT 队列里排在前面的任务（上一帧的绘制、setSurface 的 runSync 等）不执行完，本帧 run() 不开始——`syncDelayDuration`（DrawFrameTask.cpp:145）记账这段排队时长，ADPF 上报时扣减（:223-225 只扣 min(syncDelay, mLastDequeue)）。
- **谁设置**：RT 的单线程串行队列（ThreadBase::threadLoop 逐项执行，§3.4）。
- **谁执行**：RT；UI 线程被动等待。
- **后果**：UI 线程 doFrame slice 拉长（postAndWait 子 slice 拉长）——RT 忙的代价以"UI 线程也在等"的形态出现在 doFrame 内；本 trace 六个关键帧的 postAndWait 从 325,154 到 3,615,923 ns 摆动（批09 q5），155820 帧 3,615,923 中 RT 侧 DrawFrames 155820 的同步段占 1,529,077（q3：syncFrameState 1,529,077 含 5 条 Texture upload 96x96——新位图上传，与 Record View#draw() 4,402,116 的重录量同帧呼应）。已证（端点）；"postAndWait 拉长的全部构成"未逐项归因（§12）。

### 9.5 硬件加速关闭时的路径差异（机制⑤）

- **规则**：isHardwareEnabled（ViewRootImpl.java:4376-4378）判定；假分支走 drawSoftware（:4796→:4813-4906）：lockCanvas→mView.draw(canvas)→unlockCanvasAndPost 全在 UI 线程。
- **谁设置**：mThreadedRenderer 的创建与 enabled 状态由窗口初始化与 surface 状态决定（ViewRootImpl.java:3212 区与 :4396 区使用点）。
- **谁执行**：UI 线程单线程完成绘制与提交。
- **后果**：无 RenderThread 参与、无 display list 复用、queueBuffer 在 UI 线程；postAndWait/DrawFrames slice 不存在。AppList 本 trace 全硬件路径（§8 已证联合）。已证。

## 10. trace 配对：本篇数值总表

全部相对时间口径；新样本 tid 1909（UI）/2079（RT），旧样本 tid 1913（UI）/2107（RT）。出处列 C=契约第三节、R09=批09 补查、B10=批10 补查（q1~q7，SQL 存档于过程记录）。

| rel 起点 | dur（ns） | slice/事实 | 样本 | 出处 | 产生点 |
|---|---|---|---|---|---|
| 6,842,074,918 | 564,193 | Choreographer#doFrame 155812 | 新 | C :75 | Choreographer.java:748 |
| 6,842,147,649 | 480,116 | draw | 新 | R09 q2 | ViewRootImpl.java:4463 |
| 6,842,162,226 | 99,577 | Record View#draw() | 新 | R09 q2 | ThreadedRenderer.java:694 |
| 6,842,268,957 | 353,769 | postAndWait | 新 | R09 q2 | DrawFrameTask.cpp:136 |
| 6,842,332,265 | 3,361,884 | DrawFrames 155812 | 新 | B10 q3 | DrawFrameTask.cpp:144 |
| 6,842,365,957 | 172,385 | syncFrameState | 新 | B10 q3 | DrawFrameTask.cpp:234 |
| 6,842,618,303 | 87,192 | dequeueBuffer | 新 | B10 q3 | EGL 内部 |
| 6,842,752,957 | 2,925,808 | Drawing 0 158 1280 640 | 新 | B10 q3 | CanvasContext.cpp:513 |
| 6,843,017,803 | 587,308 | flush commands | 新 | B10 q3 | SkiaOpenGLPipeline.cpp:124 |
| 6,843,637,034 | 176,692 | waitOnFences | 新 | B10 q3 | CanvasContext.cpp:531 |
| 6,843,850,265 | 1,449,961 | eglSwapBuffersWithDamageKHR | 新 | B10 q3 | EglManager.cpp:600 |
| 6,844,165,188 | 1,116,077 | queueBuffer | 新 | B10 q3 | EGL 内部→Surface::queueBuffer |
| 6,844,405,072 | 442,000 | acquireNextBufferLocked frame=396 | 新 | B10 q3 | BLASTBufferQueue（批11） |
| 6,845,066,495 | 98,193 | Trace GPU completion fence 653 | 新 | B10 q3 | GPU completion 线程 |
| 9,717,203,823 | 531,807 | Choreographer#doFrame 340845 | 旧 | B10 q5e | 同上（契约图04 逐字） |
| 9,717,448,861 | 2,280,038 | DrawFrames 340845 | 旧 | B10 q5d | DrawFrameTask.cpp:144（契约图03 逐字） |

计数类（B10 q4，tid 2079/1909 全 trace）：DrawFrames 508、doFrame 508、syncFrameState 508、flush commands 507（flush 少 1 条＝存在一帧跳绘或无实绘路径——哪一帧未逐帧定位，未证，§12）。

线程表（B10 q1/q5）：新样本 RenderThread tid 2079、hwuiTask0 2236、hwuiTask1 2237、GPU completion 28353；旧样本 RenderThread tid 2107。

重叠类（算术，端点均实测）：新样本 doFrame∩DrawFrames = 306,846；postAndWait∩DrawFrames = 290,461；DrawFrames 止−doFrame 止 = 3,055,038；postAndWait 起→DrawFrames 起 = 63,308；syncFrameState 止→postAndWait 止 = 84,384。旧样本 doFrame∩DrawFrames = 286,769；DrawFrames 止−doFrame 止 = 1,993,269。

## 11. 与两个问题的关联

**50.928 ms 主线程 Sleeping（00:00:06.779837–00:00:06.830765）**：本篇补一条机制旁证——批10 补查 q6：S 窗口 [6,779,837, 6,830,765] 内 RenderThread（tid 2079）slice 零命中（无 DrawFrames、无任何子 slice）。**50.928 ms 期间 RT 无任何帧工作**——主线程睡眠不是等 RT。已证（窗口计数）。S 结束（6,830,765）到 DrawFrames 155812 起（6,842,332,265）之间 RT 轨只有一条 slice：notifyFramePending（6,831,402,765 dur 4,615，q7）——它是 UI 线程 scheduleTraversals 的 notifyRendererOfFramePending（批09 引用链）经 RenderProxy.cpp:230-232 post 到 RT 的执行，属于下一帧排程动作而非帧工作；155812 帧 RT 起点距 S 结束 11.57 ms（算术）。

**34.738 ms notifyMotion→prepare**：本篇无新增归因（该空档在 S 内结束，批08 §12/批09 §10 已定界）。本篇补的对照锚点：旧样本同链路 notify→prepare 5.2 ms（批0 复验二 §三），旧样本的 DrawFrames/doFrame 重叠形态（§5.3）与新样本同构——两个问题的三段时延差异不落在 HWUI 层的线程模型差异上（HWUI 双线程形态两样本一致），归因主战场仍在批06 的 filter 路径（批06 已证）。

**"上下 40 多 fps"的应用侧计数链**：本篇补最后一个应用侧环节——doFrame 508 条全部在 RT 上有 DrawFrames 对应（q4 计数 508:508，一一配对已证），即**应用侧排出的每一帧都完成了缓冲产出**（无"排帧未画"形态）；帧间隔的稀疏化（批09 §13 的 65.630/23.788/26.866 ms 序列）因此不是 HWUI 丢弃帧，而是上游排帧节奏的产物。显示侧呈现节奏"见批11/12/13"。

## 12. 未证边界与盲区清单

1. **盲区（build 一致性）**：本篇机制结论的设备适用性受 00 篇 §3.6 前提约束（基准源码树与设备 build 逐文件一致性未验证）。
2. **未证（flush commands 507:508）**：q4 的 flush commands 计数比 DrawFrames 少 1——缺的哪一帧、走的是跳绘分支（DrawFrameTask.cpp:192-201 区）还是 CanvasContext::draw 的空脏区分支（CanvasContext.cpp:485-502）未逐帧定位。
3. **未证（fence 653 与帧的对应）**：GPU completion fence 编号未与 vsyncId/frame 对账，"653 信号＝155812 帧 GPU 画完"的等式未验证（§7）。
4. **未证（postAndWait 拉长的构成）**：155820 帧 postAndWait 3,615,923 ns 与 DrawFrames 155820 同步段 1,529,077 的差值（2,086,846）中含等待 RT 取出任务的排队时长（syncDelayDuration 语义）与唤醒传播，两项的占比未提取（q3 未含 UI 线程侧细分）。
5. **未证（RT 自驱动画帧）**：CanvasContext::prepareAndDraw 形态（无 postAndWait 投递、由 RT Choreographer 驱动）在本 trace 是否出现未验证（q4 计数 508:508:508 的整齐配对倾向"未出现"，但无独立计数证据）。
6. **盲区（vendor EGL 内部）**：dequeueBuffer 在 eglQuerySurface 与 eglBeginFrame 之间发生的驱动内部路径（vendor GL/EGL 不在树内）；dequeueBuffer 双层同名 slice 的两层归属（ANativeWindow 层/BufferQueueProducer 层）留批11 用 libs/gui 源码判定。
7. **盲区（queueBuffer→BLAST 的全链）**：acquireNextBufferLocked 之后 Transaction 组装与 setTransactionState 全部在批11；本篇只取 RT 轨上的 slice 端点。
8. **未证（Record View#draw() 摆动归因）**：六帧 99,577~4,402,116 的重录量波动与列表条目量的对应（批09 §5.3 口径沿用，AppList Adapter 配置盲区）。
9. **未证（mSyncQueued 与 SyncQueued 帧时间）**：DrawFrameTask.cpp:129 的 mSyncQueued 同时写入 FrameInfo 的 SyncQueued（CanvasContext.cpp:362），该字段在 FrameTimeline 的下游用途未展开（批12）。
10. **盲区（HintSession 是否生效）**：ADPF 会话受 Properties::useHintManager 开关（DrawFrameTask.cpp:282）与本设备性能提示服务可用性控制，属性值与服务状态未采（DrawFrames 内部的 hint 段耗时未单独打点）。

## 13. 断言与三态汇总

本篇机制性结论的编号与三态（断言-证据对照的完整表在《过程记录/批10-过程记录.md》，编号一致；批注审计与自检结论同录于该记录 §五/§七）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| H1 | DrawFrames slice 唯一产生点 DrawFrameTask.cpp:144，复数＋vsyncId；树内无单数 DrawFrame 打点 | 已证 | grep 全树＋q3 slice 名逐字 |
| H2 | postAndWait 止点＝mSignal.wait 返回（提前放行），非 run() 结束 | 已证 | DrawFrameTask.cpp:135-140/:276-279＋§5.2 端点 |
| H3 | 提前放行：syncFrameState 返回真即 unblockUiThread，draw 在放行后 | 已证 | DrawFrameTask.cpp:172-176/:189-191 |
| H4 | syncAndDrawFrame 返回时 GPU 未画完且大概率未开始提交（postAndWait 止早于 Drawing 起 130,231 ns） | 已证 | §5.2/§6.3 端点算术 |
| H5 | 双账本交接点唯一：prepareTree 推 staging 进正式侧 | 已证 | RenderNode.cpp:287/:217 区；DrawFrameTask.cpp:250 |
| H6 | 新样本 155812 配对：doFrame∩DrawFrames=306,846；重叠 54.4% | 已证（算术） | C :75＋R09 q2＋B10 q3 |
| H7 | 旧样本 340845 配对：531,807/2,280,038 逐字命中契约图03/04；重叠 286,769（54%） | 已证 | B10 q5d/q5e（SQL 留痕） |
| H8 | DrawFrames/doFrame/syncFrameState 计数 508:508:508（新样本全程一一配对） | 已证 | B10 q4 |
| H9 | GL 管道 getFrame 不显式 dequeue；dequeue 由 EGL 内部在查询窗口完成；Vulkan 显式 dequeueNextBuffer | 已证 | EglManager.cpp:563-573；SkiaVulkanPipeline.cpp:66-69；q3 slice 顺序 |
| H10 | flush commands＝CPU 侧提交结束点；GPU 完成由 fence 承接 | 已证 | SkiaOpenGLPipeline.cpp:124-126；EglManager.cpp:592-603/:619-621 |
| H11 | swap 调用名是 eglSwapBuffersWithDamageKHR（非 eglSwapBuffers） | 已证 | EglManager.cpp:600 |
| H12 | queueBuffer 后 BLAST 消费（onFrameAvailable/acquireBuffer/acquireNextBufferLocked）同在 RT 轨 | 已证（端点） | q3 序列；细节批11 |
| H13 | RT 栅栏 fence()=runSync 空任务；普通帧不走 | 已证 | RenderProxy.cpp:216-218；ViewRootImpl.java:4530 |
| H14 | drawSoftware：UI 线程 lockCanvas→draw→unlockCanvasAndPost 串行，无 RT 无重用 | 已证 | ViewRootImpl.java:4796/:4813-4906 |
| H15 | 本 trace 全 508 帧硬件路径 | 已证（联合） | 批09 q3（draw 508）＋q2（Record/postAndWait 508/508） |
| H16 | 50.928 ms S 窗口内 RT 无帧工作 | 已证（窗口计数） | B10 q6（tid 2079 窗口 slice 零命中）＋S 端点（C :69） |
| H17 | 应用排出的每帧都产出缓冲（无排帧未画） | 已证（计数） | q4 508:508 配对 |
| H18 | computePalette 产生点 Bitmap.cpp ATRACE_CALL（批09 遗留收口） | 已证 | Bitmap.cpp:399＋批09 q5 端点 |
| H19 | RT 自驱动画通道存在（frameCallback 两拍＋prepareAndDraw）但不服务 AppList 界面帧主线 | 已证（代码）；本 trace 是否出现未证 | RenderThread.cpp:130-141；CanvasContext.cpp:433-443/:796 |
| H20 | 任务预置三处勘误：JNI 实际路径 hwui/jni/android_graphics_HardwareRenderer.cpp；DrawFrame 定名 DrawFrames；SkiaOpenGLPipeline 无 dequeueBuffer 方法 | 已证 | find/grep 实测（本篇头部） |
| H21 | RT 侧三道跳帧判定：无 Surface／同 vsync 2 ms 去重／reserveNext 预占失败；AppList 本样本全通过 | 已证（代码＋联合） | CanvasContext.cpp:387-427；q4 508:508＋q3 各帧 Drawing 序列 |
| H22 | damage 合成按 bufferAge 分档（0/1/>1），历史不足全画；AppList 全屏脏区下减量路径未启用 | 已证（代码）；收益未观测 | CanvasContext.cpp:933-981；Drawing slice 名矩形 |

（完）
