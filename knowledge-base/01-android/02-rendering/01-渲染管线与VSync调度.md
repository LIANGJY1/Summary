# 渲染管线与 VSync 调度

> 学习资料（文章模式沉淀）。主线：以 Android 17（API 37）源码语境串联「VSync 预测调度 → Choreographer → ViewRootImpl/HWUI → RenderThread/GPU → SurfaceFlinger → 显示」主链路，并覆盖帧率与显示模式决策、硬件层、过度绘制、文本渲染四条支线的机制与边界。源文档：android-internals-wiki §2.1《Android 渲染架构与版本演进》§2.2《帧率、刷新率与显示模式选择》§2.3《VSync、Choreographer 与 SurfaceFlinger 调度》§2.4《MainThread、RenderThread 与 Hardware Layer》§2.5《过度绘制》§2.6《文字渲染性能》（Android 17 语境）；Choreographer 回调顺序、ARR 机制与版本、Surface.setFrameRate 常量语义、Debug GPU Overdraw 颜色含义已于 2026-09-25 与 android.googlesource.com、source.android.com、developer.android.com 核对。BLAST 提交与 A13 全链路时序的既有沉淀见 [../framework/Android13渲染架构全链路硬核解析.md](../../others/framework/Android13渲染架构全链路硬核解析.md)，VSync 硬件来源与软件时钟的既有沉淀见 [../framework/Android显示系统/Vsync流程.md](../../others/framework/Android显示系统/Vsync流程.md)，本文只补两文未覆盖的增量视角。Q 序列即结构，供 atlas 同源直读。

**Q1: 同一界面里普通 View、TextureView 视频、SurfaceView 地图各自的内容画到哪个 Surface 上，选型边界是什么？**

普通 View 与 Compose 内容统一画在 ViewRootImpl 的宿主 Surface 上，由 RenderThread 一次 DrawFrameTask 提交；TextureView 的外部内容（如解码器输出）写入 SurfaceTexture 的 BufferQueue，再被 HWUI 当作纹理在宿主 Surface 上采样合成；SurfaceView 则拥有独立 Surface 与独立 Layer，内容完全不经宿主 Surface。前提是 ViewRootImpl 在 attach 时向 WindowManager 申请宿主 Surface 并绑定 ThreadedRenderer。机制上，普通 View 的 RenderNode 全部挂在 RootRenderNode 下，随帧统一绘制；TextureView 因此跟随 View 变换（圆角、缩放、动画都生效），但付出一次纹理采样成本；SurfaceView 的 Z 序由 SurfaceControl 打洞控制，视频解码与游戏的 EGL/Vulkan 可直接渲染到该 Surface，省掉宿主合成路径。结果与做法：全屏视频用 SurfaceView 最省电省合成；需要与 UI 同步做变换动效（位移、圆角遮罩）用 TextureView；普通列表走默认宿主 Surface 即可，不要为局部内容滥用 TextureView。

**Q2: 一帧没有显示出来，如何用证据链区分是 App 没画、SurfaceFlinger 没合成，还是显示没翻转？**

渲染责任分三个调度域：App 域（Choreographer 唤醒到 RenderThread 出 buffer）、SF 域（收 Transaction、latch、合成、提交 HWC）、显示域（DRM 翻转与扫描输出），每个域有各自的可观测证据，卡在哪一段就看哪一段的证据缺失或超时。机制上，App 域的证据是 Choreographer#doFrame 与 RenderThread 的 DrawFrame 任务，结束标志是 BLAST 提交 Transaction，SF 侧 trace 中的 `BufferTX - <layer>` 表示 SF 已收到 pending buffer transaction；SF 域的证据是 onMessageInvalidate/onMessageRefresh 对应的 flushTransactionQueues、latchBuffers 与合成提交；显示域的证据是 HWC present 与 DRM atomic commit。三段之间由 fence 传递：acquire fence 证明 App 是否写完 buffer，release fence 证明 SF/HWC 是否已用完 buffer，present fence 证明显示是否采纳了这一帧。做法：把「谁在等谁的 fence」对上号，App 域看 FrameTimeline 的 jank 分类与 FrameInfo 五阶段，SF 域看 SurfaceFlinger 轨道与 BufferTX 时序，显示域看 present fence 信号时间，即可把掉帧归因到具体域，而不是笼统结论「卡了」。

**Q3: 从 Android 3.0 到 Android 17，哪些版本节点真正改变了渲染性能模型？**

关键节点是 3.0 硬件加速与 DisplayList、4.1 Project Butter 与 Choreographer、5.0 RenderThread 分离、11 BLAST 与 MRR、15 ARR，其余节点属于能力补充。机制逐条看：3.0 把绘制指令录制为 DisplayList，硬件加速成为默认，绘制从「立即执行」变为「录制后回放」；4.1 引入 Choreographer，让 UI 统一由 VSync 驱动，消灭了无节制的 invalidate；5.0 把 RenderThread 独立出来，UI 线程只构建 RenderNode 树，绘制执行移出 UI 线程；6.0 增加 COMMIT 回调标记帧提交边界；7.0 提供 FrameMetrics 与 Vulkan NDK；9.0 起 SkiaGL 成为默认后端；11 引入 BLAST 原子提交、MRR 多刷新率与 Surface.setFrameRate；12 引入 FrameTimeline；13 公开 FrameData 与 AGSL；15 引入 ARR 与 ANGLE 可选；16 增加 Display.hasArrSupport 等 ARR 公共 API；17 语境下 WebGPU 仍以 Jetpack alpha 形态存在而非框架 API，另有 getFrameRateVelocityMapping 等速度类 API。边界：以上均为版本敏感结论，引用时需标注对应 API 级别，尤其 BLAST 全面替代旧提交路径、ARR 依赖 Composer3 标准接口这两点与设备实际版本强相关。

**Q4: 同一台设备上 HWUI 何时走 SkiaGL、何时走 SkiaVulkan，由什么决定，怎么复现对比？**

由 HWUI 启动时的 `Properties::peekRenderPipelineType()` 决定：默认 SkiaGL，`debug.hwui.renderer` 设为 skiavulkan（或设备预配置/ANGLE 偏好介入）时走 SkiaVulkan。前提是两条管线共享同一 RenderNode/DisplayList 前端，差异只在后端提交与 fence 处理。机制上，HWUI 读取系统属性与开发者选项选择管线，实例化 SkiaOpenGLPipeline 或 SkiaVulkanPipeline；Android 9 起 skiagl 是默认值，Vulkan 后端长期受驱动兼容性限制未全面默认，Android 15 引入的 ANGLE 是 OpenGL 之上的可选翻译层（prefer_angle 控制），Android 17 语境仍非强制。边界：切换后端不改变上层 Canvas 语义，但会改变 shader 编译时机与 fence 行为，部分 GPU 专属问题只在一个后端出现。做法：复现渲染疑难时可用 `setprop debug.hwui.renderer skiavulkan`（进程重启生效）做两个后端的 A/B 对比，把「框架逻辑问题」与「驱动/后端问题」分开。

**Q5: 引入 BLAST 之后，BufferQueue 及其 slot 状态机还存在吗，三缓冲是固定的吗？**

存在。BLAST 改变的只是「谁替 App 把 buffer 交给 SF」：App 侧的 BLAST 消费者把渲染完成的 GraphicBuffer 包进 SurfaceControl.Transaction 调 setBuffer() 提交，底层 BufferQueue 的 FREE/DEQUEUED/QUEUED/ACQUIRED 状态机与动态深度管理照旧工作。机制上，传统路径 queueBuffer 会直接触发 SF 侧取用，buffer 与窗口几何属性的提交时序可能错开导致不同步；BLAST 路径把两者合并为原子 Transaction，SF 在 `BufferTX - <layer>` 记录 pending buffer transaction，等下一个合成周期统一 latch。结果是把问题域从「buffer 与属性不同步」转为「Transaction 提交时序与合并策略」。边界：buffer 数量不是固定 3 个，由 `min_undequeued_buffers + 2` 计算得出（典型为 3，随生产者约束可变）；slot 状态迁移仍是分析 buffer 阻塞的基本模型。

**Q6: FPS 达到 60 就等于流畅吗，FPS、刷新率、present 间隔、输入延迟各度量什么？**

不等于。FPS 是吞吐量（单位时间提交的帧数），显示刷新率是面板物理扫描频率，present-to-present 间隔是相邻两帧实际上屏的时间差（度量平滑度），input-to-present 是输入事件到其生效帧上屏的延迟（度量跟手度），四者不一致时高 FPS 依然会卡。机制上，FPS 高但 present 间隔忽长忽短就是抖动，FrameTimeline 用 present 间隔偏离预期帧时长的程度判定 jank；刷新率限制 FPS 上限，但 MRR/ARR 下两者可解耦，60fps 内容在 120Hz 面板上按 2 倍间隔均匀呈现照样平滑；input-to-present 必须把输入时间戳与目标帧的 vsyncId 对齐才能算准，只看 FPS 完全测不出跟手性。边界与做法：以 present 间隔方差和 FrameTimeline 的 jank 分类作为流畅度主指标，FPS 只做吞吐概览；Perfetto 的 FrameTimeline 轨道可同时观察这四个量。

**Q7: MRR、ARR、VRR、LTPO 四个词分别指什么，最容易混淆的点在哪？**

MRR 是 Android 11 引入的离散显示模式切换（在多个固定 DisplayMode 之间按约束切换）；ARR 是 Android 15 起引入的自适应刷新率（同一配置内按面板 TE/VSync 的整数分频连续步进）；VRR 是可变刷新率的统称（ARR 实现细节在 AOSP 中也以 vrr 命名）；LTPO 是面板硬件技术，是前两者能低功耗运行长刷新的物理基础。机制上，MRR 在 Composer 的 mode 集合间切换，跨模式切换可能非无缝，需要 seamlessness 约束保护；ARR 通过 vsyncPeriod 与 minFrameIntervalNs 表达「在 minFrameIntervalNs 之后按 VSync 整数倍呈现」，把帧放在更细的 VSync 网格上，避免频繁跨 mode 切换的功耗与可见闪烁，由 Composer3（v3）提供标准接口。混淆点常在把 LTPO 当成系统 API、把 ARR 当成厂商私有 VRR，以及以为高刷屏都有 ARR。边界：ARR 要求面板支持 TE 信号与分频步进，不支持时回落 MRR；Android 16 起可用 Display.hasArrSupport() 查询。做法：兼容代码按 hasArrSupport 分支，不要假设所有高刷设备具备 ARR。

**Q8: 视频播放器如何用 Surface.setFrameRate 让系统选对刷新率，参数怎么选？**

在 Surface 创建或播放开始时调用 `surface.setFrameRate(frameRate, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_*)`，把内容固有帧率告诉 DisplayModeDirector；非视频 UI 内容用 FRAME_RATE_COMPATIBILITY_DEFAULT。机制上，FIXED_SOURCE 表示内容有固定源帧率（如 23.976/29.97 的视频），允许系统为匹配它而切换刷新率（包括非无缝切换）；DEFAULT 只是提示语义，系统倾向选择不低于该帧率的模式。CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS 是默认策略，只同意无缝切换；CHANGE_FRAME_RATE_ALWAYS 允许可见切换，长视频场景建议用它。边界：该 API 面向 targetSdk 30+（Android 11 起可用）；传值要给精确帧率（29.97f 而非 30f），否则取整可能匹配不到模式；传 0 清除声明（API 34 起另有 clearFrameRate）；它只影响投票，不保证立即切换，最终由 RefreshRateSelector 仲裁。做法：播放器在获知内容帧率时（如 onVideoSizeChanged）立即声明，暂停或销毁时清除。

**Q9: UI 内容的运动速度如何影响刷新率，Android 15 到 17 加了哪些速度类 API？**

系统对刷新率的投票分显式与启发式两类：显式来自 setFrameRate/setRequestedFrameRate 类 API，启发式来自 SurfaceFlinger 的 LayerHistory 对每层内容更新频率的采样；Android 15 起新增按「内容速度」投票的 API，让快速滚动拉高刷新率、静止降回低刷新率由框架自动完成。机制上，View.setRequestedFrameRate 支持类别语义（如触摸加速、省电等类别常量），Window 层提供 touch boost 与 power savings 类请求；Android 17 语境补充 setFrameContentVelocity 与 getFrameRateVelocityMapping，把「内容移动速度 → 刷新率档位」的映射交给系统，App 不再手写帧率切换逻辑；setProducerThrottlingEnabled（API 37）可约束生产者提交节奏。边界：这批 API 是 Android 15（setRequestedFrameRate、Transaction.setFrameTimeline）到 17 逐步加入的版本敏感面，低版本设备上调用需降级。做法：滚动与动画场景优先用类别投票表达意图，只有视频这类固定源内容才用精确帧率。

**Q10: 多个图层投票冲突时，SurfaceFlinger 如何裁决最终刷新率？**

由 RefreshRateSelector 汇总所有图层的 LayerVoteType 投票（NoVote、Heuristic、ExplicitDefault、ExplicitExactOrMultiple、ExplicitExact、ImplicitExactOrMultiple、Min、Max、Kernel 等 9 类）与各层帧率，先按距离打分筛掉不满足约束的模式，再在剩余模式中结合 seamlessness 选出 desired mode。机制上，显式投票（ExplicitExact 等）来自 setFrameRate 类 API，Heuristic 来自 LayerHistory 采样，触屏 boost 表现为短时的 Max 投票；打分公式对 min/max 边界投票按 (min/max)² 计分，平方级惩罚意味着精确帧率匹配（Exact）的约束力远强于「不低于」语义，冲突时通常向满足强约束的模式收敛。边界：投票只产生 desired mode，真正生效还要经过 desired→pending→active 状态机与无缝约束检查，被 EX_SEAMLESS_NOT_ALLOWED/EX_NOT_POSSIBLE 拒绝时停留在 desired。做法：调试时在 SF trace 中看 RefreshRateSelection 过程与各层 vote 值，先确认投票分布再查约束。

**Q11: 刷新率从 desired 到 active 要经过哪些状态，上线后用什么计数器验证切换生效？**

状态机是 desired mode（DisplayModeDirector 依据投票与约束选出）→ pending mode（SF 确认可切换并排程）→ active mode（Composer/HWC 完成实际切换）；验证用 HasDesiredMode、PendingModeFps、ActiveModeFps、RenderRateFps 四个 trace 计数器。机制上，生效路径有两条：走 `setActiveModeWithConstraints()` 时返回 VsyncPeriodChangeTimeline，按时间线异步生效；Composer3 的 DisplayCommand setDisplayMode 则随提交立即 finalize。切换请求被 EX_SEAMLESS_NOT_ALLOWED 或 EX_NOT_POSSIBLE 拒绝时会停在 desired 不前进。结果判读：HasDesiredMode 长期为 1 说明卡在约束或拒绝；ActiveModeFps 已变但 RenderRateFps 没跟上，说明刷新率切了而 App 提交节拍没跟上；PendingModeFps 有值说明切换在排程中。做法：用 Perfetto 观察四个计数器随时间的变化，与投票 API 调用时刻对齐，区分「没投票」「投了没生效」「生效了没用上」三类问题。

**Q12: 说「VSync」时到底指哪个：VBlank、TE、HWC 回调，还是 App 收到的 VSync？**

这是四个不同层的概念：VBlank 是扫描消隐期的物理时窗，TE 是面板向 SoC 发出的 tearing effect 信号，HWC VSync 是 HAL 层向 SF 上报的回调事件，App/SF 的 VSync 是 EventThread 基于预测模型派发的软件唤醒时刻。机制上，物理层的 VBlank 与 TE 提供真实节拍来源，硬件 VSync 回调（HWC onVsync）把真实节拍送给 SF 用于校准预测模型，预测器再为 app 与 sf 两条 EventThread 各自派发相位可配置的软件 VSync；App 在 frameTimeNanos 里拿到的是预测值而非硬件中断时间。边界：混用这四个词会让延迟分析差出半个周期甚至整周期；判断对齐关系时「硬件回调是真值、App 时间戳是预测值」。做法：分析抖动先把 HWC VSync 回调时间线作为基准，再看 App 帧时间戳相对它的偏差，而不是直接拿 frameTimeNanos 自比。

**Q13: Android 17 的 VSync 预测由哪些组件组成，各组件的边界条件是什么？**

VsyncSchedule 由三个组件构成：VSyncPredictor（滑动窗口线性回归，20 条历史样本、最少 6 个样本参与、按 20% 阈值剔除离群）、VSyncReactor（仲裁硬件时钟与软件模型切换）、VSyncDispatchTimerQueue（把回调安排到定时器上精确派发）。机制上，预测器输入历史 VSync 时间戳，输出下一周期的相位与周期；间隔超过 200ms（kPredictorThreshold）的样本不再参与外推；Reactor 在预测偏差小于约 10% 时不切换时钟源，避免硬件 VSync 频繁开关造成抖动；定时器派发带 500µs slack 与最小 3ms 间隔约束，防止过于密集的唤醒。无更新需求时 SF 可关闭硬件 VSync 仅靠模型预测，有新回调或模式变化时再开启校准（省电机制）。边界：以上常量是 android-17 源码语境值，跨版本可能调整；低温/变频场景预测误差增大，靠周期性硬件回调纠偏。做法：排查唤醒时刻异常时，先看硬件 VSync 使能状态与预测偏差 trace，再怀疑业务代码。

**Q14: App 与 SurfaceFlinger 的 VSync 唤醒点为何不在同一时刻，workDuration/readyDuration 如何参与计算？**

两者从同一节拍派发，但按各自负载用 workDuration/readyDuration 反推提前量：earliest 取 max(lastVsync, now + workDuration + readyDuration)，向预测器询问下一 VSync，再倒推 nextWakeup = nextVsync − workDuration − readyDuration；App 侧经 EventThread 注册回调，SF 侧走 MessageQueue 的 "sf" 连接。机制上，workDuration 是该域完成工作所需的时长（App 端对应 UI 线程渲染预算，SF 端对应合成预算），readyDuration 是提交后到 VSync 死线的富余，唤醒点因此早于 VSync 两者之和的距离；两端预算不同，唤醒点自然错开，这正是流水线并行的来源。边界：计数器上 VSync-app/VSync-sf 的出现频率不等于 doFrame 执行次数，EventThread 回调可能丢失或合并，两者解耦；相位偏移属于版本敏感实现细节。做法：把 app/sf 两条轨道的 wakeup 与各自工作段长对齐观察，判断是哪一端的预算溢出导致掉帧。

**Q15: Choreographer 一帧内按什么顺序执行回调，多次 post 为什么只请求一次 VSync？**

五类回调按固定顺序执行：INPUT → ANIMATION → INSETS_ANIMATION → TRAVERSAL → COMMIT；同一帧内多次 postCallback 会合并进各类型队列，mFrameScheduled 标志保证只请求一次 VSync，回调执行后才清标志。机制上，doCallbacks 按上述优先级逐队列执行：输入最先保证本帧响应用户；动画更新属性值先于遍历；INSETS 处理系统栏动画；TRAVERSAL 里 ViewRootImpl 执行 performTraversals 完成测量布局绘制；COMMIT 记录提交完成时机。同步屏障由 ViewRootImpl 在 scheduleTraversals 时插入 MessageQueue，保证 TRAVERSAL 类消息优先于普通消息——加屏障的是 ViewRootImpl 而非 Choreographer。边界：某类队列执行过久时，doCallbacks 会把后续类型顺延到下一 VSync（reschedule 逻辑），这是掉帧的一种内部形态，表现为某帧缺失后半段回调。做法：自定义动画统一走 Choreographer 回调或 ValueAnimator，与帧调度合并，不要自建 Handler 循环另起节拍。

**Q16: Choreographer.doFrame 里除执行回调外还做哪些诊断，buffer stuffing 卡住后如何恢复？**

doFrame 先做帧诊断再执行回调：接收 FrameData 并记录 preferredFrameTimeline、计算 jitterNanos = 当前时间 − frameTimeNanos 做抖动回归检查、填充 FrameInfo 各阶段时间，然后依次执行五类回调；buffer stuffing（提交节奏快于消费、缓冲池被塞满）时走 waitForBufferRelease 等待，若等待超过半个帧间隔判定为 stuffed，按 DELAY_FRAME/OFFSET/NONE 策略恢复，并有约 100ms 阈值与多次恢复标志（buffer_stuffing_multi_recovery 等）防止恢复循环。机制上，抖动检查面向「预期 VSync 时间与真实唤醒时间」的偏差，`debug.choreographer.skipwarning=30` 可调「Skipped N frames」日志告警阈值；stuffing 的本质是生产快于消费，恢复策略选择取决于当前是否还有 pending buffer。边界： stuffing 恢复是较新版本的防御逻辑，旧版本只会阻塞在 dequeue；诊断结论依赖 trace 而非日志猜测。做法：trace 中看到 waitForBufferRelease 等待超半帧，按 stuffing 分析提交节奏（是否重复提交、是否消费端变慢），而不是直接怀疑 GPU 绘制慢。

**Q17: frameTimeNanos、deadline、expectedPresentationTime、vsyncId 各是什么语义，线上如何采集？**

frameTimeNanos 是本帧的预测 VSync 时间（同时是动画时间基准），deadline 是本帧必须提交的最后期限，expectedPresentationTime 是预计上屏时间，vsyncId 唯一标识该 VSync 周期、用于与 FrameTimeline 对账。机制上，这四个量由 FrameData（API 33 引入 postVsyncCallback 时公开）承载，包含多个候选 frame timeline 及其 deadline 与预计上屏时间；进程内聚合采集用 FrameMetrics（API 24 引入，31、36 有字段扩展），它把一帧分解为 unknownDelay → input → animation → measure/layout → draw → sync → swap 等时长分量。边界：frameTimeNanos 晚于真实唤醒即为 jitter；动画以它为时间基准意味着丢帧时动画会跳值而不是整体变慢；FrameMetrics 是聚合视图，单帧归因要用 FrameTimeline 按 vsyncId 对齐。做法：线上用 FrameMetrics 监控总时长与分量占比，线下用 Perfetto FrameTimeline 把单帧各分量与 vsyncId 串联定位异常帧。

**Q18: UI 线程与 RenderThread 如何分工，什么情况下 UI 线程会被「晚解锁」拖住？**

UI 线程构建并更新 RenderNode 树（updateDisplayListIfDirty），RenderThread 执行 syncFrameState 与 CanvasContext::draw 完成 GPU 绘制和 buffer 提交，两者经 DrawFrameTask::postAndWait 用条件变量握手；正常情况同步阶段结束后 UI 线程立即解锁（early unlock）继续下一帧，当 syncFrameState 返回 info.prepareTextures=false 时解锁推迟到绘制完成（late unlock）。机制上，prepareTextures=false 意味着纹理缓存耗尽或位图上传阻塞，RenderThread 拿不到本帧纹理资源，只能原地等待，UI 线程与 RenderThread 的并行性被破坏，整帧时长接近两者串行之和。边界：常见诱因是大位图首次绘制、超出 maxTextureSize 的纹理走软件路径、纹理缓存被频繁淘汰；Bitmap.prepareToDraw（Android N 起支持异步上传）与 Bitmap.Config.HARDWARE（API 26）可把上传成本提前消化。做法：trace 中 UI 线程在 syncAndDrawFrame 内长时间等待时，先查纹理上传与缓存状态，而不是优化绘制指令本身。

**Q19: RenderThread 的 waitOnFences 在等什么，一帧缓冲池到底有几个 buffer？**

waitOnFences 等待的是先前已排队帧的 present/release fence 信号，实现上通过 CommonPool 的 std::future 异步轮询 fence 状态，并不是在 RenderThread 上直接阻塞等单个 sync_file；缓冲池大小不是固定 3，由 BufferQueue 的 min_undequeued_buffers + 2 计算得出（典型三缓冲，随生产者约束可变）。机制上，App 出 buffer 时 acquire fence 告诉消费者何时可读，SF/HWC 用完后给出 release fence 告诉 App 何时可写；当上一帧的 fence 尚未信号化而下一帧又需要 buffer 时，同步阶段就要等待，表现为 RenderThread 上的 fence 等待耗时。fence 在 trace 中按 acquire/release/present 三类命名区分。边界：fence 等待集中出现通常指向下游（SF 合成或显示翻转）消费变慢，或上游提交快于消费，属于流水线背压而非绘制本身的问题。做法：先确认 buffer 数量与 release fence 信号时间，再决定是降低提交频率还是排查下游耗时。

**Q20: 什么样的属性动画能完全不经过 UI 线程运行，边界条件是什么？**

RenderNode 属性类动画（RenderNodeAnimator，如 RenderNode 上的 translationX/Y、alpha 等属性动画）启动后挂在 RenderThread 上按 VSync 自驱，UI 线程零参与；前提是动画目标属于 RenderNode 支持的属性集，且没有耦合需要 UI 线程参与的机制。机制上，这类动画启动时把初值、终值与插值器同步给 RenderThread，之后 RenderThread 每帧直接更新 RenderNode 属性并触发重绘，因为不触碰 View 属性、不触发 measure/layout，UI 线程可以完全休眠，这正是「动画不掉帧但主线程空闲」的来源。边界：ObjectAnimator 直接改 View 属性的动画仍走 UI 线程；带 UpdateListener/AnimatorListener 每帧回调的动画会拉回 UI 线程；节点内含 functors（如 TextureView）时不能走完整的 RT 动画路径。做法：大图位移、淡入淡出优先使用 ViewPropertyAnimator 的 RenderNode 属性或 Compose graphicsLayer 动画，避免逐帧回调 UI 线程。

**Q21: 「layer」在渲染语境里有哪几种含义，setLayerType 的三个值分别做什么？**

至少三种含义：View 硬件层（HWUI 内部为某子树建的离屏 RenderTarget/纹理）、TextureLayer（SurfaceTexture 内容形成的纹理节点）、SurfaceFlinger 的 Layer（SF 侧合成单元），三者处在不同层，混用术语会掩盖真正的开销位置。机制上，setLayerType(LAYER_TYPE_NONE) 是默认不建层；LAYER_TYPE_SOFTWARE 让该子树在 UI 线程画进一张 Bitmap（脱离硬件加速路径，用于滤镜输出或规避 GPU 兼容问题）；LAYER_TYPE_HARDWARE 在 RenderThread 上建 GPU 纹理层，子树先画进层，后续帧只更新层或变换层本身，适合 alpha/变换动画。显式 setLayerType 为 HARDWARE 但不提供 paint 时不会立即建层，配合 buildLayer() 才会预建。边界：SOFTWARE 层把绘制拉回 UI 线程且受位图尺寸限制，超大 View 会内存暴涨甚至失败；硬件层受 maxTextureSize 限制，超出则建层失败退回直绘。做法：只有当子树「内容少变、变换频繁」时才值得建层，否则两次渲染的成本反而更高。

**Q22: 没有设置 LAYER_TYPE_HARDWARE 的 View 什么情况下会被自动晋升成硬件层？**

当节点满足 promotedToLayer() 条件时 HWUI 会自动建临时层：包含 GL functors、应用了 ImageFilter、应用了 StretchEffect，或 alpha 处于 0 与 1 之间且存在重叠渲染；同时必须通过 fitsOnLayer() 的 maxTextureSize 检查，超限则晋升失败。机制上，临时层让半透明合成不必每帧重画整棵子树——把子树先画进一张纹理，再对纹理整体调 alpha，动画结束后层销毁；ViewPropertyAnimator 做 alpha/变换动画时会自动走这条路径。显式的 buildLayer() 可在动画开始前预建层，避免首帧建层开销（内部经 mPrefetchedLayers 预取管理）。边界：自动晋升失败（纹理尺寸超限）会退回逐帧混合，性能不升反降且难以察觉；setUseCompositingLayer 这类底层开关带有 clipToBounds=true 的副作用，会意外裁剪内容。做法：对即将做 alpha/位移动画的大子树，动画前调用 buildLayer() 预建，动画结束后恢复 LAYER_TYPE_NONE 释放显存。

**Q23: 硬件层的成本模型是什么，Compose 的 graphicsLayer 对应什么语义？**

硬件层成本 = 一次离屏渲染 + 纹理内存（尺寸按 64 像素粒度向上取整计费）+ 后续按 damage 区域的局部更新（不重建层）；内容少变、以变换为主的动画场景层是划算的，内容每帧全变的场景反而多付两次渲染。机制上，damage 更新只把脏区重画到既有纹理上，因此层把「每帧重画子树」换成「每帧变换纹理」，这是流畅与省电的来源；64 像素取整意味着尺寸比实际内容略大的层也要为取整买单。Compose 的 Modifier.graphicsLayer 与 View 硬件层同构（compose ui 1.11.4 语境），用 CompositingStrategy 控制策略：Auto 由框架判定、Offscreen 强制离屏层、ModulateAlpha 逐节点调制 alpha 而不建离屏层。边界：ModulateAlpha 在半透明重叠区域与 Offscreen 的视觉结果不同（不做统一离屏混合），需要严格 alpha 混合语义时应显式 Offscreen。做法：Compose 中动画 alpha/旋转/缩放一律包 graphicsLayer，并把状态读取放进 lambda 以便跳过未变化的重组。

**Q24: 开发者选项里的过度绘制颜色各代表什么，这个可视化是怎么实现的？**

颜色含义：无色（真彩）表示像素最多画 1 次；蓝色 = 多画 1 次（共 2 次）；绿色 = 多画 2 次（共 3 次）；粉色 = 多画 3 次（共 4 次）；红色 = 多画 4 次及以上。实现上，Skia 渲染管线把场景重放到一张 A8 离屏 surface 上，每画一次计数加 1，再按计数映射颜色叠加显示（SkOverdrawCanvas 诊断回放），因此它是软件诊断回放，不是 GPU 硬件 overdraw 计数器的读数。该模式由 `debug.hwui.overdraw` 开启，取值 show 与 show_deuteranomaly（色盲友好配色）。机制上，overdraw 回放时 RenderNodeDrawable 按层快照边界处理，层内容的计数方式与逐层直绘存在差异。边界：可视化计数与真实 GPU fragment 数可能不一致（快照与离屏的差异），只能作为定位线索而非精确度量。做法：红色集中区优先排查重复背景与不可见的层叠控件，再用 GPU 时长验证优化收益。

**Q25: 过度绘制该在哪个层面度量，为什么有些 overdraw 不值得优化？**

有三个测量层级：逻辑绘制次数（Canvas 调用层数）、GPU fragment（真实像素着色次数）、SF 合成层叠；开发者选项可视化近似第二层。是否优化取决于 GPU 实际多做的工作量，而不是颜色面积本身。机制上，现代 tile-based GPU 对小面积 overdraw 有较好的局部性处理，quickReject（API 30+ 语义）与裁剪让矩形不相交的绘制近乎零成本，被完全遮挡或裁掉的绘制在 GPU 侧可能根本不发生，因此「逻辑上多画」不等于「GPU 真多算」；真正要优化的是大面积、半透明、多层叠的场景，比如全屏背景叠列表叠悬浮层。边界：优化收益随 GPU 架构与分辨率变化，低端机与高分辨率屏幕收益更明显。做法：先用 Profile GPU Rendering 或 Perfetto 确认 GPU 时长有下降空间，再动手优化，优化前后用 AGI/GPU 计数器做 A/B 验证。

**Q26: 常见的过度绘制来源有哪些，哪些修复手法是安全的？**

高频来源是：Activity 默认 windowBackground 与布局根背景叠加、列表 item 双重背景、selector 与前景 drawable 叠放、不可见但仍参与绘制的层叠卡片。安全修复包括：去掉主题或布局中多余的一层背景、合并 Drawable 层级为单一 drawable、用裁剪（setClipToOutline 等）替代叠层遮挡、对不可见内容做懒加载避免参与绘制。机制上，windowBackground 与根布局背景重复是「每屏一片红/粉」的最常见原因，删掉其中一层通常零风险；selector 的状态层多数可以预合成或用单一状态 drawable 表达。边界：水波纹、深浅主题切换等效果依赖背景叠层时不能盲删；逐屏验证比套规则更重要，因为颜色可视化会高估 tile GPU 上的真实开销。做法：用 Layout Inspector 看实际绘制层级，配合 Perfetto FrameTimeline 观察优化前后 GPU 段的时长变化。

**Q27: TextView 的 measure/layout 何时走 BoringLayout、DynamicLayout、StaticLayout？**

BoringLayout 是快路径：文本单行且 isBoring 校验通过（不含换行、tab、双向文本、RTL、ParagraphStyle 等结构复杂性）时直接使用；可选中文本，或非 PrecomputedText 的 Spannable 文本，走 DynamicLayout（为编辑与 span 变更维护监听结构）；其余情况走 StaticLayout。机制上，isBoring 的失败项都是「行结构复杂度」信号：CharacterStyle 这类纯外观 span 不会让它失败，而任何影响行结构与度量的字符或 span 会；DynamicLayout 为 Spannable 的变更通知预留 watcher 结构，构建与更新成本高于 StaticLayout，这就是可编辑文本更贵的原因。边界：同一段文本在可选与不可选状态下布局实现不同，性能基线不可跨状态比较；把可选属性误留在只读列表项上会白白付出 DynamicLayout 成本。做法：长列表项关闭 selectable、避免复杂 spannable；固定文案优先静态化，让快路径生效。

**Q28: 一次 TextView 绘制背后文本引擎做了什么，LayoutCache 为什么有时不命中？**

Minikin 先 itemize（按字体与脚本切分 run），逐 run 调 HarfBuzz（Wiki 基线 11.4.1）的 hb_shape 完成字形整形，再经 LineBreaker 断行、Layout 生成字形位置，结果进入 LayoutCache（5000 条 LRU，文本长度 ≥128 个 UTF-16 单元时旁路缓存）；命中即可跳过成形与断行。不命中的常见原因：缓存键不含目标宽度，宽度变化（父布局变化、ellipsize 配置差异）时同一文本仍需重算；可变字体的轴值参与缓存键，轴值变化即失效。机制上，缓存键包含文本、方向、排版样式（字号/字重/字距等），刻意排除宽度以控制键复杂度，这是「同文本不同宽度命中失效」的根源。边界：连字（hyphenation）自 Android 10 起默认 NONE，开启后断行计算成本上升且对宽度更敏感；超长文本被排除在缓存外是刻意的内存保护。做法：宽度会变化的场景不要依赖缓存命中，用 PrecomputedText 锁定排版参数复用。

**Q29: PrecomputedText 预计算了什么、没预计算什么，setTextFuture 有什么坑？**

PrecomputedText（API 28）在后台线程完成文本测量与断行所需的大部分工作（成形、逐 run 度量），但不包含最终换行结果——因为其 Params 不含宽度，宽度仍是主线程构建 Layout 时的参数；AppCompatTextView.setTextFuture 把 Layout 构建挪到后台线程，但 setText 时会等待 future 完成，若后台计算尚未结束，主线程仍会阻塞在等待上。机制上，PrecomputedText 必须与 TextView 的实际参数严格一致（textSize/typeface/行距等），不一致时抛 IllegalArgumentException；它与 setText 传入同一段文本的路径互斥，混用同样异常。边界：开启连字后收益下降（断行对宽度更敏感）；Params 构造必须来自目标 TextView 的 getTextMetricsParams，手工拼装容易不一致。做法：RecyclerView 场景在后台用相同 Params 预计算并复用；宽度未定（如 waterfall 布局）前不要做 PrecomputedText；setTextFuture 场景先确保 future 已完成或接受首帧等待。

**Q30: 不同类型的 Span 对 TextView 性能影响差别在哪，emoji 处理走哪条路径？**

影响结构重建的 span 最贵：改变度量与行结构的 span（如字号、样式类）会让 BoringLayout 快路径失效并触发 StaticLayout 重排，而纯外观绘制类 span（前景色、背景色）只影响 draw 阶段，成本差一个量级。emoji 处理经 EmojiCompat/emoji2：初始化（App 内置字体或可下载字体两种配置）后，文本进入 TextView 前把 emoji 码点包装为 EmojiSpan，EmojiSpan 属于度量影响型 span，参与断行与度量。机制上，度量型 span 的变更会引发整段重排，频繁动态修改（如滚动中改字号）时，DynamicLayout 的变更监听结构本身也构成开销；emoji 的替换发生在测量管线早期，因此 emoji2 初始化延迟会导致首帧回退系统 emoji 渲染。边界：emoji 初始化失败会静默回落；span 数量极大时遍历与监听成本超过 span 本身。做法：动态着色避免重建 span 结构（复用可变 span 对象）；emoji 场景在 Application 阶段提前初始化 emoji2。
