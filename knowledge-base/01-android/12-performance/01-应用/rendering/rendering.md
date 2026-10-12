# 界面渲染与交互响应

**Q1: [learning] 用户反馈“页面有点卡”，为什么必须先区分渲染 jank、响应延迟与 ANR 三类工程口径，各自动用哪些证据？**

三种感受对应不同的系统行为与证据入口，先确认用户看到了画面停顿、点击无反馈，还是系统 ANR 提示：

1. **渲染 jank：**画面节奏异常，沿 FrameTimeline、Choreographer、RenderThread、SurfaceFlinger 与 HWC 的帧链路取证。
2. **响应延迟：**输入到可见反馈过慢，画面本身可以稳定；沿 Input 分发、主线程消息、Binder 与业务路径取证。
3. **ANR：**系统判定应用没有在规定时限内响应；核对 ANR reason、超时类型与主线程及相关进程栈。

混淆口径会修错位置：渲染慢帧不一定引发 ANR，平均 FPS 正常也不能排除输入延迟。三类问题可以一起治理，但诊断时必须保留各自边界。

**Q2: [learning] FrameTimeline 全绿为什么不能证明运动轨迹均匀，“无掉帧卡顿”应该怎样继续拆？**

帧在 deadline 前完成只证明调度与渲染没有触发 jank 判定，而 FrameTimeline 不保存 `scrollY`、`translationX`、相机视角或动画进度，画面对象是否沿预期轨迹移动还取决于输入采样、运动模型、数值精度、像素取整、buffer 提交与 present 节拍，因此连续绿色帧无法单独证明运动均匀。排查先按现象分类：

1. **帧按期、模型坐标偏离目标曲线：**周期残差指向时间量化、插值或整数像素取整。
2. **模型坐标平稳、Actual/Present 间隔波动：**指向 buffer、SurfaceFlinger 或刷新率切换等显示侧。
3. **跟手异常、fling 正常：**指向输入采样与速度估算。
4. **跟手正常、fling 异常：**指向 `OverScroller` 或自定义物理模型。做法：同时记录帧时间、运动模型输出、View 消费结果与 FrameTimeline 四组数据，用 surface/display token 把 UI 线程 counter 与对应显示帧关联，精度要求高时使用与 VSync 同步的高速相机。“无掉帧卡顿”只适合作为用户现象描述，不能直接当根因结论。

**Q3: [learning] Android vitals 的慢帧与冻结帧阈值是什么口径，为什么不能替代逐帧 deadline 归因？**

vitals 对 View/Canvas 传统渲染统计的口径是：慢帧为渲染时间 16 ms 至 700 ms，冻结帧超过 700 ms。16 ms 是面向 60 FPS 的传统阈值，官方同时说明这组数据不覆盖 Vulkan、Unity、Unreal、OpenGL 等绕过 Android UI Toolkit 的画面。它的定位是发现严重停顿的告警指标，而非归因工具：90/120 Hz 或动态刷新率设备上，FrameTimeline 的 expected/actual 更贴近该帧当时的调度条件。 平均 FPS 只表达单位时间的帧数，可能掩盖少量长帧，也可能把静止界面主动少绘制误判为退化。应用可用 API 24+ 的 FrameMetrics 记录窗口帧阶段耗时；API 31+ 的 GPU_DURATION 与 DEADLINE 能提供进一步线索，缺失值不能记为 0。

ANR 则是另一套判定——系统针对输入、广播、前台服务、ContentProvider 等不同响应义务分别计时，“超过 5 秒就是 ANR”只适用部分常见输入场景，一帧超过 700 ms 时系统可能仍未触发 ANR。做法：版本回归至少保留帧间隔分布、jank 类型、最长连续异常区间与场景，比较 `janky frames / observed frames` 时必须保证刷新率、交互路径、统计窗口与帧来源一致，不能把 60 Hz 与 120 Hz、持续滑动与页面首帧混在一起。

**Q4: [learning] 帧卡顿分析前为什么要先固定出图路径的四个坐标？**

同一画面可能由不同组件生产、写入不同目标，再在不同位置合成。先固定对象身份，最长的 slice 才能归到正确路径：

1. **Producer：**HWUI RenderThread、GL/Vulkan 线程、Chromium、Flutter raster、Camera HAL、MediaCodec 或其他引擎。
2. **输出目标：**App Window Surface、独立 Surface、SurfaceTexture、离屏 HardwareBuffer 或 sideband 流。
3. **Layer 拓扑：**内容进入宿主窗口，还是形成独立 child layer。
4. **合成与呈现位置：**宿主采样、SurfaceFlinger CLIENT、HWC DEVICE 或专用媒体路径。

同一个 Flutter 页面可用 SurfaceView 或 TextureView 作宿主，线程名 GLThread 不证明存在独立 layer。用线程 slice、GPU submission、BufferQueue/SurfaceControl 和 layer dump 将生产者、目标与对象标识对齐，再看耗时。

**Q5: [learning] SurfaceView 视频加半透明控制栏后画面从 DEVICE 变 CLIENT，如何设计单变量实验找出触发属性？**

视频 layer、宿主 UI、系统栏与弹窗一起参与 HWC 协商，改成 CLIENT 的原因可能是 blending、色彩、缩放或资源竞争，不能归结为“多了一个浮层”。按以下步骤做单变量实验：

1. 固定同一台设备、视频分辨率、HDR 状态、刷新率与窗口尺寸。
2. 每轮只改变一个浮层：先无浮层，再依次增加控制栏、字幕、弹幕。
3. 每组记录最终 composition、FrameTimeline、RenderEngine 耗时、GPU/DPU 频率与 fence。

对照 TextureView 场景——它的视频 buffer 已被宿主 HWUI 采样进 App Window，HWC 看不到独立视频 layer，即使宿主窗口最终是 DEVICE 也不能宣称视频获得了 overlay。优化动作按已确认的触发属性选择：

1. **多余的独立 layer：**不需要独立更新的小 UI Surface 可并入宿主树。
2. **视频或相机图层：**用 SurfaceView 保留 device composition 机会。
3. **混合成本：**缩小持续半透明、模糊、圆角遮罩的面积与时长。
4. **转场属性：**避免同一轮同时改变大比例 scale、rotation、crop 与 alpha。

不能为追求 DEVICE 破坏 dataspace 正确性。

验收标准是目标 DisplayFrame 改善、合成或 fence 证据按假设变化、同机复测可复现，且视觉与功耗没有新问题。

**Q6: [learning] SurfaceFlinger 合成变慢时，应用侧怎样确认自己的图层变化是否增加了成本？**

应用侧先确认本次变化是否增加或改变了可见 Surface，再用受控对照验证它与显示帧变慢是否同时发生。SurfaceFlinger 的 CLIENT 合成、GPU fence 与 display deadline 属于系统侧证据，应用不能仅凭图层数量推断 HWC plane 限制。

1. 固定设备、视频/画面格式、刷新率、窗口尺寸与内容，只改变一个应用属性，例如增加半透明层、模糊或独立 Surface。
2. 对齐应用的 SurfaceFrame、图层集合与显示帧时间，确认变化后的图层是否进入更昂贵的合成路径，并观察应用侧帧提交是否仍按时。
3. 若只在属性变化后出现 CLIENT 合成或显示 deadline miss，再评估合并无独立更新需求的 Surface、缩小半透明/模糊区域等应用改动。
4. 对照组必须保持视觉内容与其他属性不变；最终合成与 fence 根因的完整判断见[系统渲染与合成分析](../../02-系统/01-rendering-input.md)。

**Q7: [learning] 把同步 Binder 归为某帧卡顿根因需要什么证据，为什么只统计 Binder 次数无效？**

需要两个条件同时成立：客户端 transaction 与责任线程的帧窗口重叠，并且能沿 Perfetto flow 找到服务端的执行、排队、锁等待或嵌套调用，说明服务端这段工作确实解释了 finish/present 变晚。只统计某进程的 Binder 次数或给 dispatch 耗时设固定毫秒阈值都无效，因为同步 Binder 会阻塞调用线程直到服务端返回，但单笔长事务可能与其他工作并行，大量短事务也可能才是累积主因。多个嵌套事务的 `client_dur` 区间互相包含，直接求和会重复计时。做法：用 `android_binder_txns` 按帧窗口过滤同步事务，再用 `android.binder_breakdown` 按 `binder_txn_id` 汇总等待原因（monitor 竞争、I/O、reclaim、嵌套 Binder）。判断服务端线程池饱和还要看同一窗口内多客户端是否同时受影响、服务端 worker 是否集中阻塞在同一资源，不能凭一笔长事务下结论。

**Q8: [learning] 进入复杂页面时，怎样依据 trace 重排主线程初始化任务？**

示意场景：若 trace 显示一条主线程消息中的服务实例创建耗时突出，先确认具体消息与调用链，再检查是否把无依赖的预加载或 SDK 初始化串入同一同步路径。长 slice 只能定位待拆解区间，不能单独证明所有预加载都应异步；依赖关系和单变量复测决定任务应移到何处。

修复动作按影响关键路径的顺序选择：

1. 删除当前进房不用的预加载，改为按需创建。
2. 将不依赖 UI 的 bitmap 处理、配置解析和部分 SDK 初始化转到工作线程，并检查它们与主线程的 CPU/I/O 竞争。
3. 对马上会用到且类加载昂贵的组件做有条件预热。
4. 将必须在主线程执行的生命周期任务拆成可调度的小段，避免关闭日志后仍构造昂贵参数。

如果现有实现用 `postAtFrontOfQueue()` 控制顺序，要注意它可能推迟输入与 traversal；迁移时应使用显式状态机或有预算的阶段队列。本文场景用于说明排查方法，不代表实测数据。

**Q9: [learning] 版本升级后线程变多且卡顿上升，怎样证明调度竞争是根因？**

线程数增加只构成相关性；要把调度竞争写成根因，必须证明新增线程在异常帧窗口占用目标线程可运行的 CPU，并通过单变量干预复测恢复。按证据强度推进：

1. **定位变化：**用版本二分与线程来源记录确认新增线程归属，同时记录线程数、FD 和退出页面后的存活情况；这些只说明资源变化。
2. **验证竞争：**在同一帧窗口核对新增线程的 Running、UI Thread/RenderThread 的 Runnable 等待、allowed CPU 与实际竞争者，再看频率和温控是否改变容量。
3. **干预复测：**只关闭或限制已确认的新增工作，按相同设备和场景比较卡顿分布及调度等待；恢复才支持因果结论。

多数新增线程若处于 Sleeping，对调度影响可能很小；少数持续 Running 的 worker 也可能形成竞争。治理时给线程稳定名称、为线程池设置并发与队列上限、页面退出停止 worker，并分别统计线程数、CPU time、Runnable latency 与 FD。不要把 UI 线程手工固定到大核，避免固化 SoC 与厂商调度差异。

跨层边界：CPU 调度与线程状态见 [CPU 调度与线程状态](../../02-系统/03-scheduling-power.md)。

**Q10: [learning] 列表滑动的拖动阶段与 fling 阶段驱动方式有何不同，RecyclerView 留在 trace 里的三组线索分别指向什么？**

拖动阶段由输入分发驱动：检查 MotionEvent 到主线程处理的间隔、输入回调耗时与同一帧 traversal。手指抬起进入 fling 后由动画时钟与滚动计算推进，没有持续的触摸移动事件，应检查动画回调是否及时、每帧滚动工作是否稳定。两段都可能再受 RenderThread、GPU、BufferQueue 与显示合成影响，主线程短只排除了部分应用 CPU 工作。

RecyclerView 在系统跟踪中留三组内建线索：

1. **`RV OnBindView`：**指向数据绑定，如格式化、同步读取、复杂 span 或监听器反复创建。
2. **`RV CreateView`：**指向 inflate 与 ViewHolder 创建，如 item 树深、viewType 多或复用不足。
3. **`RV Prefetch`：**指向 GapWorker 预取，如嵌套列表与预取参数不匹配。
`onCreateViewHolder` 偶尔出现是正常行为，只有反复出现且与异常帧对齐才需要处理。

`setHasFixedSize(true)` 只表达 RecyclerView 自身尺寸不受内容变化影响，不会跳过 bind 也不阻止 item 内部 requestLayout。做法：把异常帧与相邻正常帧对照，比较 UI Thread、RenderThread、App Window buffer 与 DisplayFrame，而不是孤立看某个长 slice。

跨层边界：Perfetto 轨道与线程状态见 [Perfetto 轨道与线程状态](../../04-工具/perfetto/01-trace-capture-and-analysis.md)。

**Q11: [learning] OverScroller 的 fling 轨迹里藏着哪三种离散化，120 Hz 下 8/9 ms 交替是随机抖动吗？**

不是随机抖动，是整数换算的量化结果。AAOS13 的 `OverScroller.java` 有三处离散化：

1. **时间：**`SplineOverScroller.update()` 经 `AnimationUtils.currentAnimationTimeMillis()` 取得整数毫秒时间，VSync 纳秒时间进入 legacy 动画时钟时被截断。
2. **曲线：**SPLINE 使用固定采样表，`NB_SAMPLES = 100` 形成 101 个采样点，再在相邻点之间线性插值。
3. **位置：**`mCurrentPosition` 经 `Math.round` 取整到整数像素。

`Choreographer.doFrame()` 保留纳秒 `frameTimeNanos`，但调用 legacy 动画时钟时执行整数除法 `frameTimeNanos / NANOS_PER_MS`，理想的 120 Hz 周期约 8.333 ms 映射到整数毫秒后，相邻动画时间差必然分布在 8 ms 与 9 ms（60 Hz 常见 16/17 ms，90 Hz 常见 11/12 ms）。“1 ms 除以 8.33 ms 约等于 12%”只能描述两个时间数值的比例，不能直接写成位移误差或感知概率：只有当曲线正处于斜率较大的高速区间时，整数毫秒采样才可能形成不同的整数像素步幅。

做法：是否可感知要靠轨迹采样判断，而不是从机制推导。

**Q12: [learning] 怎样量化一段滚动的步幅波动，为什么不能对减速 fling 直接计算 Δx 方差？**

减速 fling 的每帧位移本来就应逐渐缩小，直接算 Δx 方差会把设计曲线中的减速也算成抖动。用同一时间基准比较实际轨迹与目标轨迹：

1. 采样 `(t_i, x_i)`，计算时间步长、位移步长与观测速度。
2. 按模型计算目标位置 `x_expected(t_i)`。
3. 用残差 `e_i = x_i - x_expected(t_i)` 衡量偏离。只有在期望速度近似恒定的短时间窗口内，才比较 Δx 或速度的标准差与极差，并观察残差是否按 8/9 ms、11/12 ms 或刷新率切换呈现周期。

采样点选择上，RecyclerView 的 `computeVerticalScrollOffset()` 可能受 LayoutManager 滚动条估算策略影响，更稳妥的是记录同一稳定 item 的 adapter ID、decorated top 与列表累计位移，并合并同一 VSync 周期内的多次 `onScrolled()`。报告边界：UI 线程 counter 记录的是应用在该时刻准备的状态，不代表像素已经上屏。不能只报平均值，应包含设备、刷新模式、初速度、中位数、P95/P99、残差分布与重复次数，离群点还要回查跳帧、GC、调度与 item 布局。

**Q13: [learning] 自有动画应该用什么时间基准，为什么 EMA 平滑和 Android 17 的 Buffer Stuffing Recovery 都不是通用修复？**

可控制的动画或物理模型应根据同一个 VSync 纳秒时间基准计算绝对 elapsed time：不要在帧回调里额外读 `uptimeMillis()`，也不要把每帧截断后的 `dt` 累加成总时间。发生一次帧延迟后按绝对时间重新求值，轨迹才能继续对应正确时间。物理模拟需要固定步长时用累加器执行次数受限的模拟步进再插值，并限制补算量。对 `dt` 或坐标做 EMA 会增加相位延迟并改变速度与手感，只适合处理经过测量确认的传感噪声，不适合掩盖时间量化、掉帧或输入预测错误——任何插值曲线在斜率大处都会把时间误差放大成位移误差，不存在“换成 PathInterpolator 就平滑”的保证。

边界：RecyclerView 1.4.0 的 `ViewFlinger` 使用 `OverScroller`，应用没有公开 API 替换其 time source，只能调整 fling 参数、对特定控件做受控自定义滚动或向 AndroidX 提交证据。Android 17 的 Choreographer 包含内部缓冲积压恢复状态机（`BufferStuffingState`/`onWaitForBufferRelease`，AAOS13 树中无此实现），带 `@hide` 且会改变动画时间线，应用侧应处理 buffer 生产过快或消费变慢的原因，不能调用内部 recovery。

跨层边界：BufferQueue 与显示侧恢复机制见 [BufferQueue 与显示侧恢复机制](../../02-系统/01-rendering-input.md)。

**Q14: [learning] 游戏性能分析为什么不能从 UI 线程 doFrame 开始？60 FPS 能推出输入延迟只有一帧吗？**

不能。普通 View 页面由事件触发 invalidate、Choreographer 驱动 traversal，而游戏由自有 game loop 主动生产帧：输入采样、simulation（物理与玩法更新）、render 准备、RHI 提交、present 各有节奏，很多引擎用固定时间步长更新物理、再用插值生成渲染快照（外部框架，未本地核对）。

“游戏 60 FPS”只说明每秒完成约 60 帧，推不出 simulation 每秒 60 次，更推不出触控到显示只有一帧——CPU、GPU 与显示可以跨帧并行（game 准备第 N+1 帧时 render 处理第 N 帧、GPU 执行第 N-1 帧），队列中的在途帧会让输入结果晚一到两轮才显示。做法：分析应从某次输入被哪一轮 simulation 读取开始，沿以下阶段追到对应帧：

1. simulation 读取输入并产出该帧的状态。
2. Render/RHI 准备和提交 GPU 工作。
3. GPU、swapchain 与 BufferQueue 传递帧。
4. SurfaceFlinger 合成并 present。

给同一输入和同一帧分配稳定 ID，写入各阶段 marker。

同时统计 frame-time 分布、P90/P99、present-to-present 间隔与输入到显示延迟，不只看平均 FPS。游戏仍运行在 Android 窗口系统中，自有 game loop 不会绕开 SurfaceFlinger。

**Q15: [learning] Unity 的 Gfx.WaitForPresentOnGfxThread 变长能直接判定 GPU bound 吗？Perfetto 里为什么看不到 PlayerLoop？**

不能。该 marker 下的等待可能来自 VSync、frame pacer 主动等待、swapchain 或 BufferQueue 背压，也可能是 GPU 本身，要结合 GPU 完成时间、BufferQueue 深度与目标 interval 判断。短等待也可能是在主动限制 in-flight 深度，不能因为表现为 CPU idle 就一概当瓶颈删除（外部框架，未本地核对）。

Perfetto 看不到 `PlayerLoop` 是数据源边界：Unity Profiler marker、Unreal Insights 的 task 事件属于引擎自己的 trace 体系，Perfetto 默认只显示 OS 线程、调度状态、futex、binder、图形与 GPU/SurfaceFlinger 信息——没有 `PlayerLoop` 不能证明 Unity 主线程没有工作。引擎线程要按职责识别（Game/Logic、Render、RHI、worker）再用线程名辅助确认：线程名随版本、渲染后端与构建变化，名为 RenderThread 的线程也不一定是引擎渲染。

做法：Perfetto 看系统调度、频率、GPU 与 SF，Unity Profiler 或 Unreal Insights 看引擎阶段，两边写入统一 frame id，才能把某次触控对应到哪一帧。

**Q16: [learning] 游戏的 CPU、GPU、pacing 与 display 瓶颈分别有什么证据？**

先把目标帧关联到引擎和显示链，再按资源瓶颈分类；平均 FPS 或 draw call 数不能单独定因：

1. **CPU bound：**Game/Render/RHI 阶段超预算，GPU 队列出现空闲；降低分辨率帮助有限，应检查对象、pass、状态切换与任务依赖。
2. **GPU bound：**CPU 已提前提交，GPU 持续忙且 producer fence 很晚；用 GPU stage/counter 分辨 vertex、fragment、overdraw、带宽和频率。动态分辨率主要缓解像素与带宽成本。
3. **pacing bound：**acquire、swap、present 或 Swappy wait 周期性变长；检查 pending buffer、release fence、目标 interval 与 pipeline mode。
4. **display bound：**buffer 已 ready，但 SurfaceFlinger 错过 latch 或 present 变晚；检查 desired present、HWC CLIENT 合成和显示模式切换。

“每帧超过 1000 draw call”没有跨设备阈值意义。要把 draw call 数与 Render/RHI driver 时间、pipeline 创建、GPU counter、pass 数和减少后的实测变化一起看。SRP Batcher 降低兼容材质的 CPU 状态设置成本，不等于合并 mesh 为一次 draw（外部框架，未本地核对）。

## 交互响应延迟

**Q17: [learning] 长时间游戏后帧率波动，什么样的证据链才能把原因落到温控而不是巧合？**

把温控写成根因，需要同一场景中的证据链闭合：

1. 相同内容与输入下，frame time 随会话时间变差。
2. thermal status/headroom 或 cooling state 同期变化。
3. CPU/GPU 可用容量或频率上限下降。
4. 排除内存泄漏、后台负载、亮度与充电等替代解释。
5. 冷却或降负载后，持续 frame time 恢复。机制上温控策略由 SoC、传感器与 OEM 配置决定，不存在通用的 45 度或 60 度卡顿阈值。trace 中频率下降也可能来自负载降低或 governor 决策，ADPF 的 `PerformanceHintManager` 只向系统报告线程组与目标/实际时长，不承诺固定频率或核心、也不能越过温控安全限制。

[Android Developers 的 Netmarble ADPF 案例](https://developer.android.com/stories/games/netmarble-got-adpf?hl=en)报告：动态分辨率与目标帧率调整后，平均 thermal headroom 从 1.04 降至 0.92（约 11%），持续帧率通常在 50–60 FPS。该结果只描述该游戏与测试设备，不是其他产品的 SLA。复现实验应持续到目标设备热状态趋稳；15 分钟可作初始测试窗口，但需根据设备温度曲线延长或缩短。实验固定亮度、充电与环境温度，策略加入滞回和最短保持时间，同时报告画质档位与帧率分布，而非只比平均 FPS。

**Q18: [learning] 同一应用内启动 Activity 的成本为什么随目标状态变化？**

`startActivity()` 即使在同一应用内也经过 system_server；目标进程与 Activity 当前状态决定后续工作，因而没有通用的单段耗时预算。AAOS13 的请求从 `Instrumentation.execStartActivity()` 经 `IActivityTaskManager.startActivity()` 进入 ATMS/ActivityStarter，处理 Intent、权限、Task 和 launch mode，再按状态走不同分支：

1. **目标 Activity 已在 Task 中：**可能只需 `onNewIntent()` 或恢复，并完成必要重绘。
2. **进程存在但目标 Activity 未创建：**系统发送 ClientTransaction，执行实例创建、生命周期、窗口和首帧工作。ClientTransaction 体系自 Android 9 引入。
3. **目标进程不存在：**还要经 Zygote 创建进程，安装 Provider、创建 Application，再创建首个 Activity，接近冷启动路径。

定位页面切换慢时分别标记输入、ATMS 接收、目标 UI 创建与首帧，把系统调度和应用构建分开比较。

**Q19: [learning] Fragment 的 commit() 与 commitNow() 怎样影响页面切换时序？**

选择取决于当前调用必须同步得到 Fragment 状态，还是允许事务进入主线程队列：

1. **`commit()`：**经 `enqueueAction()` 异步投递，与 Choreographer 帧回调共享 Looper；调用返回不表示页面已创建，也没有固定的“下一帧之前”保证。
2. **`commitNow()`：**在调用线程同步执行创建、生命周期与布局工作，不能与 `addToBackStack()` 同用。它可满足必须立刻得到状态的约束，但会把这些成本压进当前主线程消息。

比较切换延迟时在导航入口、事务执行和目标页首帧加 trace 标记。只为追求“更快”而改用 `commitNow()`，可能反而阻塞输入和帧回调。

**Q20: [learning] ViewPager2 保留邻页时怎样权衡响应速度与内存？**

`offscreenPageLimit` 用保留相邻页面换取往返切换时较少的重建成本；增大值会同时保留更多 View、图片、Compose composition 和订阅。选择前要区分页面保留、数据加载和动态数据集三个问题：

1. **页面保留：**默认 `OFFSCREEN_PAGE_LIMIT_DEFAULT (-1)` 沿用 RecyclerView 缓存策略，不保证固定保留邻页。设置为 N 时两侧各保留 N 页；传 0 抛 `IllegalArgumentException`。公开 API 不允许替换内部 LayoutManager。
2. **数据加载：**FragmentStateAdapter 使选中页达到 RESUMED，其他已添加页停在 STARTED。View 在 STARTED 用 `repeatOnLifecycle` 收集可见数据，RESUMED 时触发幂等的 `ensureLoaded()`；把状态放 ViewModel。`onViewCreated()` 会因邻页预创建而过早加载，`onResume()` 无条件刷新又会重复请求。
3. **受控预取：**按页面 key 与账号设置容量、TTL 和取消规则，避免与首次请求重复。动态增删页时实现稳定的 `getItemId()`/`containsItem()` 并发差分通知，防止重建 adapter 放大切换成本。

旧的 `setUserVisibleHint()` 已废弃。调高保留页数后，应同时测往返切换延迟和内存峰值。

**Q21: [learning] 实时搜索的延迟应怎样分段，旧结果怎样避免覆盖新查询？**

搜索总延迟要把主动等待与实际执行分开记录；只看请求耗时会漏掉输入处理和结果上屏。按时间分成四段：

1. **输入：**IME 或 TextField 写入查询状态。
2. **抑制重复：**规范化 query 后做 debounce 和 `distinctUntilChanged()`。debounce 主动等待用户继续输入，不属于后端执行耗时；`distinctUntilChanged()` 只去掉相邻相同值，`foo → bar → foo` 仍会再次查询 foo。
3. **执行：**本地索引、数据库或网络请求。`flatMapLatest` 取消旧 Flow 的收集，但底层 Room 或网络请求仍须支持协作式取消，服务器已收到的请求也可能继续执行。
4. **反馈：**结果 Diff、列表布局及呈现帧。每个结果携带 query 或 generation，UI 只接受当前 generation，避免旧回调覆盖新结果。

空查询应立即清空结果，可对该输入使用 0 debounce 并返回空状态流。重复查询的缓存键至少包含规范化 query、账号和数据版本。debounce 时长按输入到反馈延迟、请求数、取消率和完成率实测选择；本地索引更新及大列表 Diff 不应阻塞按键帧，Diff 的提交仍会回到主线程。

**Q22: [learning] 课程完成后先显示反馈再提交数据，在什么条件下能改善感知延迟？**

当产品语义允许本地先确认“课程已完成”，且后续提交可以可靠重试时，提前显示完成页能缩短点击到可见反馈的等待。Duolingo 案例原先等学习数据、广告和奖励信息全部返回才离开全屏加载；改为先显示完成动画和文案，后台继续提交。官方报告该路径的感知 session end 延迟降低 60% 以上，但未披露绝对毫秒，不能把这个数字用于别的业务预算。

测量时保留三个时间点：

1. `input_received`：主线程收到点击。
2. `meaningful_feedback_drawn`：完成反馈已提交到显示管线。
3. `operation_committed`：服务端确认，或本地可靠队列完成持久化。

前两点构成感知响应，第三点代表业务完成。支付、安全操作或必须由服务端确认的动作不能提前显示“已完成”；允许乐观反馈时仍需重试、离线持久化、失败提示及进程死亡恢复。同步 I/O 和锁等待也要移出点击回调。

**Q23: [learning] 点击与拖动的输入到反馈延迟怎样定义起止点？**

交互延迟必须从对应动作的输入样本起算，到用户所需的反馈帧呈现结束。按压反馈、点击结果和连续拖动的起点不同，混用会让相同实现得到不同数字：

1. **按压反馈：**普通 View 通常在 ACTION_DOWN 后显示按下态，从 `ACTION_DOWN.eventTime` 到包含该状态的帧呈现。
2. **点击结果：**普通点击在抬手后确认，从 `ACTION_UP.eventTime` 到包含操作结果的帧呈现。
3. **拖动反馈：**逐个输入样本计算 event-to-photon 延迟并比较整段手势的分布；只量“开始移动到列表首次滚动”会漏掉中段波动。

`MotionEvent.getEventTimeNanos()` 接近系统输入样本时刻，不是手指接触玻璃的物理时刻；present 信号也不是面板像素完成发光。因此 Perfetto 适合量软件路径，实验室级 touch-to-photon 还需高速相机或光电传感器。报告要同时写明动作起点、完成标志与帧呈现口径，不能把回调结束当成可见反馈。

**Q24: [learning] 触摸 ACK 很快，为什么画面仍可能迟迟没有反馈？**

ACK 只表示应用完成输入事件处理，反馈还须等帧调度、渲染、合成与呈现。AAOS13 的输入链可按先后拆开：

1. **读取和选目标：**驱动经 Linux input 子系统产生事件。system_server 内的 InputReader 通过 EventHub 读取并转换坐标，InputDispatcher 选择目标窗口。
2. **交给应用：**InputPublisher 经 InputChannel 写入 Unix domain socket。Binder 用于建立窗口和通道，不承载每条 MotionEvent；应用的 NativeInputEventReceiver 收到后交给 ViewRootImpl 和 `dispatchTouchEvent()`。
3. **确认已处理：**应用调用 `finishInputEvent()` 回送 ACK。Perfetto `android.input` 标准库的 `total_latency_dur` 到系统收到 ACK 为止。
4. **产生可见反馈：**Choreographer 调度帧，应用渲染并提交 buffer，SurfaceFlinger 合成和 present。`end_to_end_latency_dur` 延伸到关联帧呈现；缺少可信帧关联时为 NULL。

两列相差大时检查 ACK 之后的 VSync 相位、渲染、buffer 和合成，不能直接认定输入分发慢。AAOS13 的 `InputManager::start()` 在 system_server 内启动 InputReader 和 InputDispatcher 两条 native 线程，不存在独立的 InputFlinger 进程。

**Q25: [learning] 触控采样率与刷新率分别能缩短哪段输入反馈延迟？**

两者改变的是不同环节，不能把标称参数直接换算成触摸到屏幕发光的总时长：

1. **1000 Hz 触控采样：**理想采样间隔约 1 ms。驱动批处理、事件预测、游戏逻辑、GPU 队列和显示扫描仍增加延迟。
2. **高刷新率：**60 Hz 与 120 Hz 的刷新间隔约为 16.67 ms 与 8.33 ms；应用和 GPU 能按时出帧时，显示侧等待可缩短。应用已经积压时，提高刷新率不能让 MotionEvent 更早送达。窗口请求的模式还受 SurfaceFlinger 图层投票与设备能力约束，Game Mode 的 FPS throttling 只能限速。

`requestDisallowInterceptTouchEvent(true)` 只影响 ViewGroup 的应用内拦截，改变不了 InputDispatcher 的目标选择。测量时按 Perfetto 输入事件的 read、dispatch、handling、ack 和关联帧 present 分段，记录帧关联覆盖率与输入源；量产 user 构建可结合 FrameTimeline 与自定义标记，详细 `android.input.inputevent` 数据源仅供 userdebug/eng。报告 P50/P90/P95/P99 和样本量，自动化注入样本单独分组。

**Q26: [learning] 异步输入注入的调用返回能当作交互响应终点吗？**

不能。`INJECT_INPUT_EVENT_MODE_ASYNC` 在事件入队后就让调用方返回，不等待目标窗口处理，也不等待反馈帧呈现；事件仍须经历窗口选择、输入通道、应用 ACK、渲染和 present。该模式需要系统级 `INJECT_EVENTS` 权限，适用于有授权的自动化环境，不是普通应用减少输入排队的开关。

自动化实验把三个时间点分别记录：

1. **注入返回：**只证明事件已经入队。
2. **目标 ACK：**证明应用完成输入处理。
3. **关联帧 present：**证明反馈进入软件定义的呈现终点。

注入样本单独分组，并记录注入模式、输入源、设备刷新率和帧关联覆盖率；把异步调用的快速返回写成“用户已看到反馈”会系统性低估延迟。
