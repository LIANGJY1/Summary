# 复核 R5——10/11/12 HWUI/BLAST/SF 三篇全量复核记录（2026-11-14）

复核者：战役协调者（用户令"进行全量复核，尤其是源码原理分析讲解，整个链路正确性"）。
方法：全文逐行读＋行号锚点机械核对（python 逐条对精树取行，±2 行窗口）＋初报 MISMATCH 逐条人工裁决＋机制链逐环对完整函数实现＋grep/wc 类论断全量重跑＋trace 独立重查＋算术 python 闭合。
基准：Android `/home/liang/Project/AAOS13` 精树；新样本 `20261113-151358-vertical-1912967`（rel 基准 start_ts=6,073,694,444,196 ns）；旧样本 finger.perfetto（rel 基准 1,968,722,243,568 ns）；trace_processor_shell-2434129a3af4dc9e。
脚本存档：/tmp/review-applist/check_anchors10.py（165 条）、rv_r5_10_counts/threads/seq/q6/q7/old.sql。

## 一、10 篇 HWUI 与 RenderThread：机制全过；26 处瑕疵已修（全引文精度/计数，无机制结论错）

### 1.1 锚点机械核对
165 条锚点（全部以精树权威行号入脚本，含待修目标）0 MISMATCH。行号均先经 grep/awk 实读确认。wc 复核 13 文件：CanvasContext 1001/RenderProxy 442/RenderThread 507/DrawFrameTask 321/DrawFrameTask.h 145/Frame 44/WorkQueue 144/SkiaOpenGLPipeline 224/SkiaVulkanPipeline 185/RecordingCanvas 1123/ThreadedRenderer 890/HardwareRenderer 1494 全吻合；**ThreadBase.h 实 89（文章 §0 表 85 错——把类闭 :85 误作全文件行数，namespace :87/endif :89 在后）**。

### 1.2 关键发现：CanvasContext/RenderNode 引文基准混用（系统性）
本篇声明精树基准（"本文全部行号在该树实际读取核对"），但 **CanvasContext.cpp 内部行号系统误用 study 批注树**（函数头却引精树，混用）：study hasSurface:413=文章:413、reserveNext:449=文章:449、SyncQueued:388=文章:388，逐字命中 study 而非精树（:387/:417/:362）。代码块内容逐字精树正确（仅行号标注错）。RenderNode.cpp 同类（study syncProperties:306/prepareTreeImpl:231/pushStaging:310，文章 :305/:232/:311 系 study+1 偏移；精树 :287/:217/:291）。其余 hwui 文件（EglManager/SkiaOpenGLPipeline/RenderThread/RenderProxy/WorkQueue/ThreadBase/Frame/Bitmap/RecordingCanvas）均精树基准正确——故为 CanvasContext/RenderNode 两文件特有（study 批注插入致内部行移，作者审计时取 study 行号未换算）。EglManager fence() 块头 :645-647 与 DrawFrameTask post/wait :139/:140 则是独立偏移（非 study：study fence:651/post:155/wait:156，文章值两不靠）。

### 1.3 机制链逐环验证（H1-H22 全部成立）
- 提交链：ViewRootImpl.draw:4628 硬件分支 isHardwareEnabled:4376-4378→dirty.setEmpty:4746→mThreadedRenderer.draw:4768→ThreadedRenderer.draw:790（markDrawStart/updateRootDisplayList:693/getUpdatedFrameInfo:809/syncAndDrawFrame:811）→HardwareRenderer.syncAndDrawFrame:456→JNI android_view_ThreadedRenderer_syncAndDrawFrame:261（GetLongArrayRegion:268/LOG_ALWAYS_FATAL_IF:264）→RenderProxy.syncAndDrawFrame:140→DrawFrameTask.drawFrame:125（mSyncQueued:129）→postAndWait:135（ATRACE:136/AutoMutex:137/post:138/wait:139）。
- 提前放行：run:142（vsyncId:143/ATRACE_FORMAT "DrawFrames":144/syncDelayDuration:145）→syncFrameState:153→canUnblockUiThread 真→unblockUiThread:174-176（AutoMutex:277/mSignal.signal:278）在 context->draw:191 之前；晚放行兜底 :207-209。postAndWait 止＝mSignal.wait 返回（非 run 结束）——H2 成立。
- 同步段：syncFrameState:233（ATRACE:234/vsync:235/vsyncId:237/deadline:238/vsyncReceived:240/makeCurrent:242/unpinImages:243/layers apply:245-247/prepareTree:250/return prepareTextures:273）。
- 双账本：RenderNode.h mutateStagingProperties:149/mStagingProperties:254；prepareTreeImpl:217→pushStagingPropertiesChanges:291→syncProperties:287（mProperties=mStagingProperties:288）——交接点唯一，H5 成立。
- 三道跳帧判定（CanvasContext prepareTree:351-461）：①!hasSurface:387-391；②同 vsync 去重 vsyncDelta:396<2_ms:401（:393-408）；③reserveNext:417 失败分诊 TIMED_OUT:422 跳帧/其他 setSurface(nullptr):424（:416-427）。
- 绘制段：draw:475→getFrame 块:504-513（ScopedActiveContext:504/getFrame:510/computeDirtyRect:511/Drawing:513）→SkiaOpenGLPipeline.draw:75（MakeFromBackendRenderTarget:107/renderFrame:112/flush commands:124/flushAndSubmit:125）→swapBuffers:137→EglManager.swapBuffers:592（waitForGpuCompletion fence():595/eglSwapBuffersWithDamageKHR:600）→swap 错误分诊:555-570（TIMED_OUT:559/abandon:568）→SwapHistory.next:572/swapCompletedTime:579/markSwapBuffersCompleted:607→return DequeueBufferDuration:653。
- GL/Vulkan 取缓冲差异：EglManager.beginFrame:563-573（makeCurrent/eglQuerySurface WIDTH:568 HEIGHT:569/queryBufferAge/eglBeginFrame:571）无显式 dequeue；SkiaVulkanPipeline.getFrame:66→dequeueNextBuffer:68——H9 成立。
- fence：产生 EglManager.fence:619-621（eglCreateSyncKHR:620/eglClientWaitSyncKHR:621）；fenceWait:660/createReleaseFence:703（eglDupNativeFenceFDANDROID:717）/waitForeverOnFence:646；waitOnFences CanvasContext:531/跳绘分支 DrawFrameTask:200。
- RT 装置：frameCallback:130-141（mVsyncRequested=false:132/postAt dispatchFrameCallbacks:139）；initializeChoreographer:230（ChoreographerSource:150/DummyVsyncSource:167）；threadLoop:429（setpriority PRIORITY_DISPLAY:430/pending 合并:441/伤疤补丁:450-454）；requestVsync:422；dispatchFrameCallbacks:404（先 requestVsync:414）。
- RT 栅栏：RenderProxy.fence:216-218（runSync 空任务）；WorkQueue.process:62/post:93-94（postAt(0)）/runSync:106；ThreadBase.class:30/waitForWork:53-66/processQueue:68/threadLoop:70-78。
- 软件分支：drawSoftware:4813（lockCanvas:4835/mView.draw:4888/unlockCanvasAndPost:4893）全 UI 线程；分叉 :4796；中间态不降级 :4778。
- damage：computeDirtyRect:933-981（bufferAge 0/1/>1 分档）；Frame.map:24-40（左上→左下原点）。
- computePalette：Bitmap.cpp:398/ATRACE_CALL:399；Bitmap.h palette:142-148（generationId:143 变化才重算）；RecordingCanvas DrawVectorDrawable:488 palette(tree->computePalette()):493；Op:81（type:8 :82/skip:24 :83）/DisplayListData::map:613。

### 1.4 trace 独立复验（全部吻合）
- 计数（rv_r5_10_counts）：DrawFrames 508/doFrame 508/syncFrameState 508/flush commands 507 ✅。
- 线程表（rv_r5_10_threads）：1909 .yadea.launcher/2079 RenderThread/2236 hwuiTask0/2237 hwuiTask1/28353 GPU completion ✅。
- DrawFrames 155812 全序列（rv_r5_10_seq，tid 2079）：DrawFrames 6842332265/3361884、syncFrameState 6842365957/172385（prepareTree 6842401957/131808）、eglQuerySurface 6842585534/2846、dequeueBuffer 6842618303/87192（内 40731@6842624918）、HWC release fence 653 6842683726/2577、eglBeginFrame 6842728918/3847、Drawing 6842752957/2925808、flush commands 6843017803/587308（onPrepare 57769/onExecute 417423）、waitOnFences 6843637034/176692、dispatchSetFrameTimelineInfo 6843827495/7500、eglSwapBuffersWithDamageKHR 6843850265/1449961（st_context_flush 196923/st_flush 183307）、queueBuffer 6844165188/1116077（内 699884@6844199188）、onFrameAvailable 6844347803/509539、acquireBuffer 6844366418/29270、acquireNextBufferLocked frame=396 6844405072/442000、waitForever 6844867534/24500、Trace GPU completion fence 653 6845066495/98193、reportMetricsWithPresentTime 6845319265/60038、notifyFramePending 6845735226/5923——§6.3 表 19 行逐字吻合。
- q6（rv_r5_10_q6）：S 窗口 [6779837000,6830765000] tid 2079 slice 计数=0 ✅（H16）。
- q7（rv_r5_10_q7）：S 止→DrawFrames 155812 起之间 RT 仅 notifyFramePending 6831402765/4615 一条 ✅。
- 旧样本（rv_r5_10_old）：doFrame 340845 9717203823/531807 tid1913、DrawFrames 340845 9717448861/2280038 tid2107 ✅（契约图03/04 逐字）。
- 算术 python 闭合：doFrame 止 6842639111/Record 止 6842261803/postAndWait 止 6842622726/DrawFrames 止 6845694149/syncFrameState 止 6842538342；paw 起→DF 起 63308、paw∩DF 290461、doFrame∩DF 306846、DF 止−doFrame 止 3055038、sfs 止→paw 止 84384、doFrame 收尾 16385、paw 止早于 Drawing 起 130231、paw 止早于 DF 止 3071423、新重叠 54.4%；旧 doFrame 止 9717735630/DF 止 9719728899、旧∩286769、旧 DF 起−doFrame 起 245038、旧 DF 止−doFrame 止 1993269、旧重叠 53.9%≈54%；155820 paw−sfs 2086846；S 止→DF 起 11.57ms——全 True。

### 1.5 修正清单（26 处）
CanvasContext study→pristine（13）：
1. §0 表 makeCurrent:285-320→:285-305、prepareTree:351-499→:351-461、draw:475-701→:475-653。
2. §2.4 代码块头 :413-462→:387-427。
3. §2.4 走读①:413-417→:387-391、②:431 区→:393-408、③:449-459→:416-427（study :428/:447 块口径保留）。
4. §4.2 makeCurrent:285-320→:285-305、prepareTree:351-499→:351-461。
5. §4.3 postFrameCallback:468-478→:433-443。
6. §6.3 DequeueBufferDuration 返回 :700→:653。
7. §6.3 表 reportMetricsWithPresentTime:720 区→:670。
8. §9.3 setDequeueTimeout:189-192→:180-183。
9. §12 SyncQueued:388→:362。
10. H19 :465-478→:433-443/:796（prepareAndDraw 定义 :796）。
11. H21 :413-462→:387-427。
（上述按行计 13 处编辑点）
RenderNode study+1→pristine（3）：
12. §2.2 syncProperties:305→:287。
13. §2.2 prepareTree 主循环 :232 区→:217 区、pushStagingPropertiesChanges:311 区→:291 区（"study :311 块"保留）。
14. H5 :305/:232 区→:287/:217 区。
DrawFrameTask 偏移（4）：
15. §2.1 vsyncId:162→:143（:162 是 "// Grab a copy" 注释）。
16. §3.4 入队 :139→:138、入睡 :140→:139。
17. §9.1 mSignal.wait:140→:139。
18. §12 跳绘分支 :216 区→:192-201 区（:216 实为 ADPF sanity check）。
EglManager 范围（5）：
19. §0 表 swapBuffers:592-620→:592-617、fence:645-647→:619-621。
20. 篇头读取范围 EglManager:645-647→:619-621。
21. §7 fence 代码块头 :645-647→:619-621。
22. §7 走读 ":621 的 fence()"→"fence()（:619-621；waitForGpuCompletion 打开时由 swapBuffers :595 调用）"。
23. §6.3 表 eglQuerySurface:568/587 区→:568-569（:587 是空行；WIDTH:568/HEIGHT:569）。
24. H10 :645-647→:619-621。
计数/注释（2）：
25. §0 表 ThreadBase.h wc 85→89。
26. §3.2 HardwareRenderer 注释 :107→:105（"the reward is GPU" 注释实 :105，常量 SYNC_LOST_SURFACE_REWARD_IF_FOUND :107）。
性质：E1-E14 引文基准混用（study→pristine），E15-E18 独立行号偏移，E19-E24 范围端点，E25 计数，E26 注释行精度。**无机制结论错误**——H1-H22 三态判定全部维持，代码块逐字内容本就正确（仅行号标注错）。

### 1.6 复核口径声明
- study 块引用合法保留：RenderNode.cpp :311 块、CanvasContext study :428/:447/:465/:996/:676/:720 块、EglManager study :726 块、DrawFrameTask.h study :121/:130-137 块、SkiaPipeline study :66 块、RenderThread.h study :276/:272/:436/:454/:483 块——这些是批注审计口径（指向 study 批注块），非机制引文，不修。
- §6.3 表称"完整序列"实为代表性主 slice 选取（trace 另有 query/addAndGetFrameTimestamps/setSurfaceDamage 等子 slice 未列）；所列 19 行数值全部正确，省略项为次级子 slice，不构成事实错误，不修。
- 未证边界维持（不得当缺陷）：flush commands 507:508 缺帧定位；fence 653 与 vsyncId 对账；postAndWait 拉长构成；RT 自驱动画帧是否出现；vendor EGL 内部 dequeue 路径；queueBuffer→BLAST 全链（批11）；Record 摆动归因（批15）；SyncQueued 下游用途（批12）；HintSession 是否生效；mExpectSurfaceStats 取值。
- 本次复核未改动任何源码树；trace/采集文件只读；文章改动 10×26。

## 二、11 篇 BufferQueue 与 BLAST：机制全过；9 处瑕疵已修（8 引文精度＋1 机制精度，无机制结论错）

### 2.1 锚点机械核对
253 条锚点（全部以精树权威行号入脚本，含 9 处待修目标）0 MISMATCH。初报 20 处全为脚本路径/代表行猜测错误，逐条人工裁决无一是文章错：BufferSlot.h/BufferQueueCore.h 实在 `include/gui/`（非 libs/gui 根）；Surface.java 实在 `core/java/android/view/`（非 graphics）；BufferQueueCore.cpp:134-141 正是 mFreeSlots/mUnusedSlots 填充段（ctor 头在 :133 前，文章引填充段正确）；wait 实 :365 wait_for/:371 wait（块 :351-373 涵盖）；onFrameReplaced 调用 :1055（:994 是锁内捕获 frameReplacedListener）；freeQueued() :180/回 mFreeBuffers :193（:178-194 精确）；fakeRelease :353-365；numPendingBuffersToHold :432。wc 复核：BLASTSyncEngine.java 397 ✅（§0/§7.1）、BufferQueue.cpp 158 ✅（§2.1）。

### 2.2 关键发现
1. **§7.3 BLASTSyncEngine 引文 study 混入**：study 树该文件实 510 行（精树 397，+113 批注）。文章 ANR 引 :235-236 逐字命中 study（study :235-236＝Slog.e "Application ANR likely to follow"），精树实 :194-195；postDelayed 引 :244 指向 study 批注注释行（study 代码实 :248），精树代码实 :203。同篇 §7.2 :202-203 本就精树正确——内部不一致坐实 §7.3 两处为 study 混入（与 10 篇 CanvasContext 同类，但本篇仅 §7.3 两行，余 §7 全精树正确）。
2. **§2.1 SurfaceControl 函数名归属错**：建 bbq-wrapper/bbq-adapter 的代码（:136-150，:141/:143）在 `generateSurfaceLocked()`，文章误标 `getSurface`；getSurface 实 :152-159（懒初始化包装，:156 调 generateSurfaceLocked）。行号 :136-150/:141/:143 本身正确，仅函数名错（同 08 篇 getPointerEvent 性质）。
3. **§2.7 Surface requestBuffer 客户端段错区**：文章引 Surface.cpp:737-747，该区实为 dequeueBuffer 尾/dequeueBuffers:747 头；客户端 requestBuffer 实在 dequeueBuffer 内 :708（BUFFER_NEEDS_REALLOCATION 判定）→:712（mGraphicBufferProducer->requestBuffer）。

### 2.3 机制链逐环验证（五机制＋BLASTSyncEngine 全部成立）
- 机制①状态机：计数向量非枚举（BufferSlot.h:43-46 字段/:48-57 状态表/isFree:87/dequeue:113/queue:127/cancel:134/acquire:146/release:157）；四转移执行点 Producer:491 dequeue/908 queue、Consumer:281 acquire/479 release，集合变更 mActiveBuffers.insert:483/mQueue.erase:293/mFreeBuffers.push_back:490 逐环核。dequeue 两条纪律：超额报错 INVALID_OPERATION（mBufferHasBeenQueued 前置:298＋dequeuedCount>=max:299-306）、无槽睡等 mDequeueCondition.wait_for:365/wait:371（tooManyBuffers:316 判据 mQueue.size()>maxBufferCount:315-317 与 fence 无关）；notify_all 三点 Consumer:298/:498、Producer:1002。选槽优先 mFreeBuffers→mFreeSlots（getFreeBufferLocked:255/getFreeSlotLocked:264）。
- queueBuffer 交卷：mFence=acquireFence:907→queue():908→++mFrameCounter:912→item.mFence:930；mIsDroppable 四来源:932-935（BLAST mConsumerIsSurfaceFlinger=false 前两条不成立→不挤代）；damage 并:985-990、frameReplacedListener 捕获:994/调用:1055；票据锁内领:1017 锁外兑:1046-1067（onFrameAvailable:1053 同线程直调）。
- acquireBuffer 消费侧：积压丢帧循环:142（队头较旧帧、MAX_REASONABLE_NSEC 护栏:161、freeQueued:180 回 mFreeBuffers:193、onBufferReleased 记:196-198 锁外回调:308-311）；到点裁决 PRESENT_LATER:220（入口 expectedPresent!=0:123，BLAST 传 0:509-517 整段跳过）；省流 mAcquireCalled:289-290（attachBuffer 置回 false:435）。
- 机制②BLAST：开关三段 WMS mUseBLAST 缺省 1:1239-1240→ADD_FLAG_USE_BLAST:1753→VRI mUseBLASTAdapter:1368-1369；relayout 分岔:8317-8326（useBLAST:10947-10948＝mUseBLASTAdapter&&!mForceDisableBLAST）→updateBlastSurfaceIfNeeded:2176（transferFrom:2199）。构造三件套 createBufferQueue:143/setDequeueTimeout(INT64_MAX):146/setMaxDequeuedBufferCount(2):149/getMaxAcquiredBufferCount 向 SF 查:163-164。提交中枢 acquireNextBufferLocked:483-624 四段：闸门:487-492（maxBuffersAcquired:831-834，includeExtraAcquire=!transaction:487）→acquire:509-517（expectedPresent 0）→组装:561-569（fence dup:564/setBuffer:565/FrameTimelineInfo:590-593）→提交:606-615（mergePendingTransactions:606/apply(false,true):609 one-way/barrier:613）。onFrameAvailable:671（mNumFrameAvailable++:706/同步期分岔:716-730/普通 acquireNextBufferLocked(nullopt):732）。**应用内 0 线程切换**（queueBuffer→onFrameAvailable→acquireNextBufferLocked→apply 全 RT 嵌套，trace 直证）＋**1 one-way binder**（eOneWay:1023）；AsyncWorker:970 唯一常驻线程只服务 ProducerListener（AsyncProducerListener:1021）不在帧链。
- 机制③apply→SF：setBuffer:1494（releaseBufferIfOverwriting:1504/bufferData:1514-1517/acquireFence:1518-1520/releaseBufferEndpoint:1522-1523 指回 TransactionCompletedListener）；cacheBuffers:918-962（getCacheId:943 命中剥 buffer/cache:949 未命中存）调用:998；apply:964（四旗:1012-1034/applyToken:1036-1038/setTransactionState:1040-1043/clear:1047）；Bp 打包 ISurfaceComposer:1100→SF:4138。
- 机制④三缓冲：在途＝DEQUEUED(≤2)+ACQUIRED(≤mMaxAcquiredBuffers+1，mAllowExtraAcquire:1081 配 Consumer:97)；SF 公式 calculateMaxAcquiredBufferCount:7229-7236（pipelineDepth=presentLatency/period 向上取整−1，max(1,·)）/presentLatency=app+sf:7265-7269；实值未采 dumpsys（未证）。
- 机制⑤fence：正向 queueBuffer 带 acquire fence（Surface getQueueBufferInputLocked:1184→Producer mFence:907→item:930→BBQ dup:564→setBuffer acquireFence:1518-1520）；SF 拒收 BufferLayer.latchBuffer:458（!fenceHasSignaled:470 return false）/BufferStateLayer:576（LatchUnsignaledConfig:577 非 Disabled 恒真）。反向归还四站 releaseBufferCallbackThunk:383（wp 绑定）→releaseBufferCallback:403→releaseBufferCallbackLocked:413（mPendingRelease:436-449/持有策略:432-433/计数:459-461）→releaseBuffer:465（mNumAcquired--:473/Consumer releaseBuffer:476）→Consumer:442（STALE 校验:466/mFence=releaseFence:478/release():479/mFreeBuffers:490/notify_all:498）→下次 dequeue outFence:525 交 RT。wait/merge 来源 ConsumerBase.addReleaseFenceLocked:419（getStatus:437/:443→Fence::wait Fence.cpp:48/同态 merge:452-455→Fence::merge Fence.cpp:90/异态取未信号:465）。FenceMonitor "HWC release":696/queueFence "has signaled":560；gpuCompletionThread:1158。
- BLASTSyncEngine（WMS 侧 §7）：实体 BLASTSyncEngine.java 397 行（native 无同名，WMS:1242 new）；SyncGroup 三步 startSyncSet:306（单活动约束:307-311）→addToSyncSet:333→addToSync:238（prepareSync:244）→setReady:337/:228（requestTraversal:235）→onSurfacePlacement:136（全员 isSyncFinished:142 才 finishNow:148）；finishNow:151-226 合并 orphan:127-134/finishSync:161/committed 监听:202/超时 postDelayed:203/onTransactionReady:206/尾部启下一组:211-225；queueSyncSet:386 排队；超时 BLAST_TIMEOUT_DURATION=5000 WindowState:797/ANR Slog.e:194-195；Trace SyncGroupReady:116-119。客户端半边 syncNextTransaction:755-784（Java:95-109）/mSyncedFrameNumbers:724/transactionCommittedCallback:299-302/flushShadowQueue:394-401/mergeWithNextTransaction:921-932/mergePendingTransactions:943-959（VRI prepareSurfaces:2230）。

### 2.4 trace 独立复验（全部吻合）
- 计数（rv_r5_11_rt/rt2/binder，**RT 2079 线程 scoped**）：dequeueBuffer 1016/queueBuffer 1014/acquireBuffer 507/onFrameAvailable 507/acquireNextBufferLocked 507/HWC release fence 507 ✅；releaseBufferCallback binder 分布 2833:231/2837:171/2021:74/2370:31＝507 ✅；aq:pending 1710 采样 0↔1 ✅。**口径辨析**：全 trace 无过滤 dequeueBuffer 4043/queueBuffer 3036/acquireBuffer 2531/HWC fence 1515（含 SF 与其他进程），文章计数明确标 "RenderThread 2079" 故线程 scoped 正确；BLAST 专属 slice（onFrameAvailable/acquireNextBufferLocked/releaseBufferCallback/releaseBuffer-BBQ/transactionCallback）全 trace 即 507（仅 launcher 用 BLAST）。tid 2079/2833/2837/2021/2370 唯一属 com.yadea.launcher pid1909（无 tid 复用）。
- 帧 396 提交段（§8.2，承 rv_r5_10_seq 同源）：doFrame 155812 6842074918/564193、traversal 6842085803/546654、draw 6842147649/480116、Record 6842162226/99577、postAndWait 6842268957/353769；RT dequeue 外 6842618303/87192 内 6842624918/40731、HWC fence 653 6842683726/2577、queue 外 6844165188/1116077 内 6844199188/699884、onFrameAvailable 6844347803/509539、acquireBuffer 6844366418/29270、acquireNextBufferLocked frame=396 6844405072/442000 ✅。
- 计数轨翻转（rv_r5_11_cnt396）：(BLAST Consumer)8 0→1@6844237957、QueuedBuffer=2@6844355111、1→0@6844389265 ✅（mQueue 停留 151308 ns）。
- 帧 395 归还（rv_r5_11_395ret，tid2837）：releaseBufferCallback 6877650303/102577(f:0,a:3)→releaseBuffer frame=395 6877657649/86154→wait 6877661842/5500＋6877669726/6154→merge 6877680534/21769→Consumer releaseBuffer 6877707880/29538→transactionCallback 6877757726/13192(f:0,a:2)→setTransformHint 6877763495/2693 ✅（§8.4 表 7 行逐字）。
- 帧 395 提交（rv_r5_11_395sub）：dequeue 内 6777671303/32923、queue 内 6778917765/559730（外 6778900111/659846 止 6779559957）、onFrameAvailable(f:0,a:2) 6779024572/427962、acquireNextBufferLocked frame=395 6779051649/396654 止 6779448303 ✅。
- 帧 397/398（rv_r5_11_397398）：397 dequeue 6872408226/158077＋6872412803/80000、acquire 6876692842/14807、anbl 6876711765/258461 止 6876970226；398 dequeue 6893774303/70192、queue 内 6898335034/534346、ofa(f:0,a:2) 6898488072/363346、acquire 6898497995/16385、anbl 6898518534/328461 止 6898846995 ✅（§5.1 表逐字）。
- 算术 python 闭合 14/14：395 在途 6877650303−6779448303=98202000 ns=98.202 ms；mQueue 停留 6844389265−6844237957=151308；postAndWait 止 6842268957+353769=6842622726、RT dequeue 早于 paw 止 6842622726−6842618303=4423 ns≈4.4 μs；396 anbl 止 6844405072+442000=6844847072；395 queue 外止 6778900111+659846=6779559957；395/397/398 anbl 止各闭合；binder 231+171+74+31=507；507=508−1；396 queue 内 699884≥ofa 509539；398 ofa 止 6898488072+363346=6898851418——全 OK。

### 2.5 修正清单（9 处）
引文精度（8）：
1. §2.1 SurfaceControl::getSurface（:136-150）→generateSurfaceLocked（:136-150；getSurface :152-159 懒初始化调用）——函数名归属错（建 bbq 实在 generateSurfaceLocked）。
2. §2.4 mBufferHasBeenQueued 前置 :310→:298。
3. §2.7 attachBuffer :748-805→:738-805。
4. §2.7 Surface requestBuffer 客户端段 :737-747→:708-714（原区是 dequeueBuffer 尾，实在 dequeueBuffer 内 BUFFER_NEEDS_REALLOCATION:708→requestBuffer:712）。
5. §3.3 提交块头 :607-615→:606-615（块示 mergePendingTransactions:606）。
6. §7.3 postDelayed :244→:203（study 批注行误作代码行；精树代码 :203）。
7. §7.3 ANR :235-236→:194-195（study 行号；精树 Slog.e :194-195）。
8. §8.1 counter 产生点 Producer.cpp:1066/Consumer.cpp:322→Producer.cpp:1011/Consumer.cpp:300（:1066 实 notify_all、:322 空行；ATRACE_INT 实在 :1011/:300）。
机制精度（1）：
9. §6.4 "releaseBufferLocked 归还前要决定"→"releaseBufferLocked（:480）归还前经 addReleaseFenceLocked（:419-420）决定"（fence 对账代码 :425-478 实在 addReleaseFenceLocked，releaseBufferLocked:480 调用它；行号本就正确，补函数归属）。
性质：E1/E6/E7 函数名/study 混入，E2-E5/E8 行号精度，E9 机制精度。**无机制结论错误**——五机制＋BLASTSyncEngine 三态判定全部维持，代码块逐字内容本就正确。

### 2.6 复核口径声明
- 可接受引文（不修）：§2.5 "回调走 onFrameReplaced（:994）"——:994 是锁内捕获 frameReplacedListener（调用实在 :1055，属票据段 :1046-1067，文章 §2.5 另述），捕获点引用合法、机制正确；§7.2 startSyncSet :307-311 引单活动约束 if 块（含注释 :308，函数头 :306）合法。
- study 块引用合法保留：BufferQueue.cpp:33-39/:113-126/:135-140、BLASTBufferQueue.cpp:170-172、Consumer.cpp:172-174/:305-310/:562-566、SurfaceComposerClient.cpp:1039-1045——批注审计口径，非机制引文，不修。
- 未证边界维持（不得当缺陷）：mMaxAcquiredBuffers 实值（公式已证、dumpsys 未采）；apply binder 段精确时刻（binder 类目未记录）；SF 收文后全程（批12）；155820 8.364 ms 与 397/398 因果；(f,a) 全序列逐点换算；BLASTSyncEngine 本 trace 可见性（window manager 类目未查）；本窗口有无进行中 WMS 同步组；本机 BLAST 实建 GraphicBuffer 数。
- 本次复核未改动任何源码树；trace/采集文件只读；文章改动 11×9。
- 脚本与数据存档：/tmp/review-applist/check_anchors11.py（253 条）、rv_r5_11_counts2/rt/rt2/binder/aq/395ret/395sub/cnt396/397398.sql。

## 三、12 篇 SurfaceFlinger：机制全过；21 组瑕疵已修（27 处定点：17 组引文精度＋2 算术＋1 tid 归属＋1 trace 时刻，无机制结论错）

### 3.1 锚点机械核对
- check_anchors12.py（242 条）＋check_anchors12b.py（95 条）＝337 条精树锚点，修后 0 问题。覆盖 SurfaceFlinger.cpp（commit/composite/postComposition/flushTransactionQueues/transactionIsReadyToBeApplied/setTransactionState/createLayer/latchBuffers/initScheduler/setVsyncConfig）、Scheduler 七件（MessageQueue/DispSyncSource/EventThread/VSyncDispatchTimerQueue/VsyncConfiguration/Scheduler/src/Timer/VsyncModulator）、FrameTimeline、CompositionEngine 四件（CompositionEngine/Output/RenderSurface/Display）、DisplayHardware（HWComposer/HidlComposerHal/AidlComposerHal）、BufferStateLayer/BufferLayer/BufferQueueLayer/Layer、Tracing/TransactionTracing、external/drm_hwcomposer（DrmAtomicStateManager/VSyncWorker/HwcDisplay）、goldfish Device。
- 14 文件 wc 与文章头全吻合（SurfaceFlinger.cpp 7640/.h 1500/BufferStateLayer.cpp 1085/.h 211/BufferLayer.cpp 814/.h 222/CompositionEngine.cpp 163/Output.cpp 1598/RenderSurface.cpp 297/MessageQueue.cpp 215/DispSyncSource.cpp 202/EventThread.cpp 700/VSyncDispatchTimerQueue.cpp 446/DisplayDevice.cpp 580）。
- 第二批 9 处初判 MISMATCH 全为脚本代表行猜测误（scheduleFrame 实 :1831 在 :1826-1832 内、:4166-4178 postTime/originPid/originUid、:3814-3816 flushPendingTransactionQueues、:3847-3858 bufferLayersReadyToPresent、:4038-4047 屏障块、:4054-4073 fence NotReady、:4062-4068 告警块、Output:1456-1489 onLayerDisplayed 循环、:2148 mustComposite|=latchBuffers），文章范围引文全部正确，修脚本后 0 问题。

### 3.2 关键发现
- §6.1 postComposition 段 study 行号混入精树基准（系统性，同 10 篇 CanvasContext）：块头 :2516-2545 实 study（精树 :2451-2481，偏移约 +65）、"函数 :2480 区" 精树 def 实 :2435、study 批注块实 :2491 区（非 :2480）、updateCompositorTiming :2530 实 :2470。附录A setSfPresent:2462 本就精树正确→内部不一致坐实 study 混入孤立于 §6.1。
- Timer.cpp 路径误：实在 Scheduler/src/Timer.cpp:191（其余 Scheduler 文件在根目录），文章头/§2.3a/附录A 三处 "Scheduler/Timer.cpp" 漏 src/。
- drm_hwcomposer 三处独立行号误（study 无此文件，纯精树引文）：drmModeAtomicCommit :139 实 :136（:139 是 ALOGE 失败日志）、total_frames_ :514 实 :518（:514 是 headless 早退 return）、BadLayer :522-526 实 :526-530（present_fence=-1 在 :528）。
- 两处算术误：§1.3 composite155785→155793 间隔 17,081,953 实 17,082,000（差 47ns，后两间隔精确）；§8 S 窗口三段分账后段 27.6ms 实 16.1ms（27.6 是 S 止→commit155818 起的窗外静默 27.7ms 误植为 S 后段；12.2+22.6+16.1=50.9 闭合）。
- 一处 tid 归属误：§2.4a "同一 tid 1841 在 6,710,522,572 有 app alarm" 实 tid 749（app EventThread；§2.3 本就正确标 749，内部不一致）。
- 一处 trace 时刻误：§4.5 输出 A queueBuffer 起点 6,800,334,572 实 6,798,033,457（dur 272,192/内 114,923 正确，起点差 2.3ms）。

### 3.3 机制链逐环验证（五机制全部成立）
- 机制①双周期：onComposerHalVsync:1856（ATRACE:1866）→HWComposer::onVsync:147 同戳去重 :163→addResyncSample:1888→VSyncPredictor；DispSyncSource ctor:118（VSYNC-%s:123/VsyncWorkDuration-%s:127）翻转 :180；MessageQueue vsyncCallback:100（VSYNC-sf flip:103/generateTokenForPredictions:111/dispatchFrame:114）；VsyncConfiguration sfOffsetToDuration:146/appOffsetToDuration:150（sfWork=周期−sfOffset、appWork=周期+(sfOffset−appOffset)）；VSyncDispatchTimerQueueEntry::update:126（earliestReadyBy:136→nextWakeupTime:141）；TimerDispatch（src/Timer.cpp:191 TimerIteration）→timerCallback:266（lagAllowance:287/rearmTimer:296/锁外 callback:299）；EventThread 三态 :486-502（Idle/VSync/SyntheticVSync，setVSyncEnabled:496/:498）＋自愈 Faking:518/16ms:515；按需 scheduleFrame:168。相位算术 app/sf 偏移各 1ms 由三计数轨 16673882/16673882/15673882 反推闭合。token 发号器 TokenManager::generateTokenForPredictions:743（速率 1+6×消费者数，非连续帧序号）。
- 机制②收帧：setTransactionState:4138（权限剥离 :4160/打包 :4166-4178/计在途 :4181-4183/addQueuedTransaction:4186/queueTransaction:4188）→queueTransaction:4089（mTransactionQueue:4102/ATRACE_INT:4103/setTransactionFlags:4113）→setTransactionFlags:3701（scheduleCommit:3705）→scheduleCommit:1826（scheduleFrame:1831）→MessageQueue::scheduleFrame:168（binder 线程直接排程 sf 相位，trace 嵌套 scheduleFrame 直证）；commit:1998（vsyncIn:2013/mExpectedPresentTime:2003/previousFramePending:1969 graceTimeMs:2023/PrevFrame 四 TracedOrdinal:2027-2049）；flushTransactionQueues:3796（pending:3814→mTransactionQueue:3821→TransactionReadiness:3840→未就绪退桶 :3845/就绪登记 bufferLayersReadyToPresent:3847-3858→屏障重扫 :3875→兜底 :3886→applyTransactions:3896）；双状态账本 mDrawingState=mCurrentState:3526；transactionIsReadyToBeApplied:3994 五道判（desiredPresentTime/isVsyncValid/frameIsEarly:3927/NotReadyBarrier:4038-4047/fence:4054-4073＋onTransactionQueueStalled:4062-4068）；shouldLatchUnsignaled:3948 四道闸；setBuffer:342（mDrawingState:388-396/EARLY_RELEASE:378/getPredictionsForToken:419/recordLayerHistory:426/setFrameTimelineVsync:428）；latchBuffers:3562（doTransaction:3584/hasReadyFrame:3590/offscreen:3607/latchBuffer:3618/scheduleCommit:3632/return:3646）→BufferLayer::latchBuffer:458（!fenceHasSignaled:470）→BufferStateLayer fenceHasSignaled:576（非 Disabled 恒真）→updateTexImage:635/updateActiveBuffer:688/updateFrameNumber:707；isBufferDue BufferStateLayer.h:49 恒真/BufferQueueLayer:83 按时间戳。
- 机制③合成：composite:2186（refreshArgs:2232-2244/earliestPresentTime:2235/mCompositionEngine->present:2244/mHadClientComposition:2266/onDisplayRefresh:2286）→CompositionEngine::present:91（preComposition:95→四输出 prepare:104→updateLayerStateFromFE:108→四输出 present:111 两轮）→Output::present:429（beginFrame:440/canPredictCompositionStrategy:442→prepareFrameAsync:444 或 prepareFrame:446/finishFrame:449/postFramebuffer:450）；beginFrame:966 门 mMustRecompose:980；prepareFrameAsync:1022（resetCompositionStrategy:1028/chooseCompositionStrategyAsync:1029/投机 apply:1030-1031/dequeueRenderBuffer:1038/composeSurfaces:1042→hwcResult.get:1048/predictionSucceeded:1049/Miss:1053/Hit:1062）；composeSurfaces:1175（缓存 :1239-1251 CacheHit:1243/CacheMiss:1249/drawLayers:1289 RE 线程）；finishFrame:1092（dequeueRenderBuffer:1156/queueBuffer:1128）；RenderSurface dequeueBuffer:150（ATRACE:152/纹理缓存 :178-198）/queueBuffer:201；postFramebuffer:1440（flip:1450/presentAndGetFrameFences:1452/onLayerDisplayed:1456-1489）；HWComposer getDeviceCompositionChanges:399（canSkipValidate:424 快路 presentOrValidate:446/慢路 validate:462→getChangedCompositionTypes:471→acceptChanges:486）/presentAndGetReleaseFences:508（wait:531/sleep_until:532/present:535/getReleaseFences:539）；HidlComposerHal HwcPresentDisplay:550/HwcValidateDisplay:636/HwcPresentOrValidateDisplay:653（AidlComposerHal:519 同型）。
- 机制④A13 分工：无 INVALIDATE/REFRESH 两消息；MessageQueue handleMessage:45（commit:51 返回 false 即停/composite:55/sample:56）；commit 返回 mustComposite:2183（shouldCommit:2137-2147 或 latchBuffers:2148）；scheduleCommit:1826 触发源（事务/背压 :2083/latch:3632/needsAnotherUpdate:2297）/scheduleComposite:1834（onComposerHalRefresh:1936/refreshRequired:1926/scheduleRepaint:1839）；commit155794 无 composite 无 commitTransactions 实例直证。
- 机制⑤present 完成：postComposition:2435（mPreviousPresentFences 双槽 :2451-2455/setSfPresent:2462 先于归还/updateCompositorTiming:2470/onPostComposition:2479/releasePendingBuffer:2481）；SF 计数点 composite vsyncId:2188/commit:2017；HAL total_frames_:518/frame_no_:536（进程内盲区）/CommitFrame:43（ATRACE:44，全 trace 504）/drmModeAtomicCommit:136→guest DRM（批13）；虚拟屏 setSwapInterval(0):2884-2886/initialPowerMode ON:2892-2894/enableHalVirtualDisplays:806-808 缺省 false 恒 client。
- drm_hwcomposer 定位：CommitFrame slice 全树唯一对应 DrmAtomicStateManager:43（goldfish hwcomposer.ranchu 无 CommitFrame/无 vsync 线程名）；VSyncWorker:32 Worker("vsync")/Routine:103 drmWaitVBlank:131 失败→SyntheticWaitVBlank:75；HwcDisplay PresentDisplay:511→CreateComposition:410→ExecuteAtomicCommit:488→CommitFrame；ValidateDisplay:670→backend_->ValidateDisplay:676（层归属仲裁，判据未逐条核 §9 边界 5）。

### 3.4 trace 独立复验（全部吻合）
- q1 线程表：pid661 upid50（tid661 主/684 TransactionTracing/685 RenderEngine/746 HwcAsyncWorker/748 TimerDispatch/749 app/751 RegionSampling/752 RegSampIdle/764·766 零 slice/741 HwBinder:661_1/682·1388·1486·1841 binder_1/3/4/5/27726 GPU completion）；pid659 composer@2.4-service（tid659 主/742·743 vsync）——逐行吻合。
- q12 九类计数：present27/composeSurfaces21/PAGRF16/HwcPresentDisplay16/prepareFrame15/latchBuffers7/commit*7/prepareFrameAsync6/composite*6 ✅。q11 per-thread 12 项：RE832/主720/app47/Timer40/GPU22/HwcAsync18/HwBinder18/binder_5 16/tid684 5/binder_1 3/binder_4 2/RegionSampling2 ✅（832/720=1.16）。q8 事务表 0 行 ✅。
- q6 节奏表 7 commit/6 composite 逐行吻合（commit155794 无 composite 无 commitTransactions ✅）；composite 间隔 17,082,000/33,685,808/16,434,538（首间隔修后）；vsyncId 增量 +8/+9/+8。
- q7 VSYNC-sf 六脉冲（6,724,980,919→6,858,326,649，6.792~6.858 无脉冲）＋VSYNC-app（6,841,683,880/6,858,395,957 间无脉冲）✅；VsyncWorkDuration app/appSf/sf=16673882/16673882/15673882 ✅；onComposerHalVsync tid741@6,707,505,880(16673882) ✅。
- q4 binder：requestNextVsync tid1841@6,832,935,457/5,133,077＋tid1486@6,844,505,072/15,346；setTransactionState@6,845,032,265/146,038＋嵌套 scheduleFrame@6,845,136,418/34,885 ✅；app alarm@6,710,522,572 实 tid749（修 §2.4a）。
- q3 frame396 链（commit155818）：previousFramePending27,500/setSfWakeUp7,192/transactionIsReadyToBeApplied155812 67,193/allowLatchUnsignaled=false26,000/wait12,961/setBuffer149,884/createSurfaceFrameForToken8,308/commitTransactions24,692(doCommitTransactions8,115)/latchBuffers186,231/doTransaction8,116/latchBuffer54,769/addSurfaceFrame6,000/updateLayerGeometry24,230/chooseRefreshRateForContent105,923/updateInputFlinger ✅。
- q3 155810 四输出：A8,215,385(prepareFrameAsync3,917,846/dequeue153,885 嵌 126,154·42,730/composeSurfaces3,634,731/PredictionHit6,797,990,765·6,038/REThreaded2,978,000/SkiaGL2,937,539/BlurRegion85,076/drawBlurRegion30,962/CacheMiss23,039/queueBuffer6,798,033,457·272,192[修]/PAGRF3,379,884/wait6,154/HwcPresent3,334,692)；B954,500(composeSurfaces18,192/PAGRF331,576)；C838,884(composeSurfaces21,115/PAGRF269,192)；D9,792,807(dequeue2,600,577 嵌 2,556,654/REThreaded3,199,692)；HwcAsyncWorker chooseCompositionStrategy916,192/getDeviceCompositionChanges897,231/HwcValidateDisplay855,077 ✅。
- q14 155818 四输出：A6,859,898,688·8,910,961(prepareFrameAsync3,750,961/dequeue126,693/composeSurfaces3,545,885/finishFrame328,539/queueBuffer235,962/postFramebuffer4,711,923/PAGRF4,400,539)；B6,868,816,303·871,346；C6,869,695,111·779,384；D6,870,481,534·5,177,923(finishFrame4,992,385/dequeue431,346/composeSurfaces3,454,385/queueBuffer1,048,846/postFramebuffer74,307)；postComposition 双帧 6,813,757,111·780,654(setSfPresent180,577)/6,875,685,572·641,500(setSfPresent6,875,710,149·177,000)；setVsyncEnabled6,841,041,995·237,231/6,876,408,072·322,462 ✅。
- 算术 python 闭合：setTransactionState 起−anbl 止=185,193；commit155818 起+vsyncIn15.41ms=6,873,864,957；相位预测−反推=263,882ns≈264μs；三 composite 间隔；S 三段 12,183,115+22,605,385+16,139,658=50,928,158（修后闭合）；S 止→commit155818=27,689,957≈27.7ms；composite155810 止 6,814,625,342/155802 止 6,791,975,919；静默 43,829,615；app workDuration+readyDuration=32,347,764——全 OK。app→present≈32.05~32.06ms（doFrame 起点取舍，文章标未证，近似合法）。

### 3.5 修正清单（21 组 27 处定点）
引文精度（17 组 23 处）：
1. §6.1 函数 :2480 区→:2435 起（精树 postComposition def）。
2. §6.1 study 批注块 :2480 区→:2491 区（study 实际批注块位置）。
3. §6.1 块头 :2516-2545→:2451-2481（study 混入，精树 mPreviousPresentFences[1]:2451→releasePendingBuffer:2481）。
4. §6.1 updateCompositorTiming :2530 区→:2470。
5. §2.6 setVsyncConfig :3475-3490→:3481-3490（:3475-3479 是 updatePhaseConfiguration）。
6. Timer.cpp 路径 Scheduler/Timer.cpp→Scheduler/src/Timer.cpp ×3（头/§2.3a/附录A）。
7. §2.3 VsyncConfiguration 块头 :149-158→:146-158（sfOffsetToDuration def:146）。
8. §2.3 VSyncDispatch 块头 :133-139→:136-141（earliestReadyBy:136→nextWakeupTime:141）。
9. §2.4a postEvent :652-656→:641（:652-656 是 EPIPE 处理，postEvent call:641）。
10. §4.3 predictionSucceeded :1054-1060→:1049-1060（公式 :1049，:1054-1060 是 Miss 分支体）。
11. 附录A RenderSurface dequeue ATRACE :151→:152。
12. drmModeAtomicCommit :139→:136 ×3（§4.6/§6.2/§7⑤；:139 是 ALOGE）。
13. total_frames_ :514→:518 ×2（§4.6/§6.2；:514 是 headless 早退）。
14. BadLayer :522-526→:526-530 ×2（§4.6/§6.2；present_fence=-1:528）。
15. §4.3 prepareFrame 块头 :1003-1012→:1004-1013（块末 finishPrepareFrame:1013）。
16. §4.3 prepareFrameAsync 块头 :1030-1044→:1028-1042（块首 resetCompositionStrategy:1028/chooseCompositionStrategyAsync:1029）。
17. 附录A VSYNC-sf 归属澄清 MessageQueue.h mVsync/:103→MessageQueue.h:120（"VSYNC-sf"）/MessageQueue.cpp:103（翻转）。
算术（2 组 2 处）：
18. §1.3 composite155785→155793 间隔 17,081,953→17,082,000。
19. §8 S 后段 27.6ms→16.1ms（并注窗外 27.7ms 静默归属，三段闭合 50.9）。
tid 归属（1 组 1 处）：
20. §2.4a "同一 tid 1841 在 6,710,522,572"→tid 749（app EventThread）。
trace 时刻（1 组 1 处）：
21. §4.5 输出 A queueBuffer 起点 6,800,334,572→6,798,033,457。
性质：E1-E4 study 混入/行号，E5-E17 行号/路径/归属精度，E18-E19 算术，E20 tid 归属，E21 trace 时刻。**无机制结论错误**——五机制＋drm 定位＋A13 分工三态判定全部维持，代码块逐字内容本就正确。

### 3.6 复核口径声明
- 可接受引文（不修）：§3.3 "先扫 mPendingTransactionQueues（:3814-3816）"——:3814-3816 是 flushPendingTransactionQueues 调用点（内部扫 mPendingTransactionQueues），调用点引用合法；§2.6 ":1890 调用" 指 onRefreshRateChangeCompleted（文章并列 Initiated/Completed，:1890 是其一）合法；§2.3 alarm "逐位吻合" 为 μs 截断近似（32,348 vs 32,347.764μs，差 0.236μs）合法。
- study 块引用合法保留：§3.1 :4237 区、§3.2 X1/X6、§3.3 :3955 区、§3.4 :4037 区、§3.5 :350 区、§3.6 :3626 区、§4.1 :2219/:2228/:105 区、§4.2 :433 区、§4.3 :1028 区、§4.4 X9、§4.5 :1155/:1507/X10、§6.3 :2945 区、§2.3a :283 区、§2.4a X4、§2.6 :51 区——批注审计口径，非机制引文，不修（§6.1 study 批注块行号 :2480→:2491 已修，因行号本身错）。
- 未证边界维持（§9 十四项，不得当缺陷）：设备 build 一致性/mExpectedPresentTime 推算两候选/acquire fence 信号时刻/四输出身份映射/四层全 client 完整判据/PrevFrame 四轨取值/TransactionReadiness·TransactionQueue 取值/tid764·766 身份/beginFrame skipping 逐帧分布/HAL 加载配置链/vg-kit 计数对齐/present fence 物理呈现/postComposition 4 wait·merge 逐条归属/投机命中率全窗口。
- 本次复核未改动任何源码树；trace/采集文件只读；文章改动 12×21 组（27 处定点）。
- 脚本与数据存档：/tmp/review-applist/check_anchors12.py（242）、check_anchors12b.py（95）、rv_r5_12_wd/hvsync/alarm/post/async810.sql；复跑批12-q1/q4/q5/q6/q7/q8/q11/q12/q14 留痕 SQL。