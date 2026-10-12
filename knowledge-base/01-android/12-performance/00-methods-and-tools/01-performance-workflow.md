# 性能学习、分析与测量方法

## 性能学习方法与排查清单

**Q1: [learning] 怎样用一次可复查的实验组织 Android 性能学习？**

以复现能力作为学习完成标准：每轮练习明确现象，采集可对照的证据，用对应版本的机制解释，再验证单变量修改。读书章节服务于当前问题，不以通读目录代替实际分析。

一轮练习按顺序保留四类产物：

1. **问题定义：**用户场景、测量起止点、设备与构建条件。
2. **采集记录：**原始数据、采集配置、设备状态和正常对照样本。
3. **机制解释：**事件间的时间与依赖关系，源码 tag 与目标设备的差异，尚未排除的候选原因。
4. **修改与复测：**单变量改动、相同协议下的结果分布和另一位工程师可复现的步骤。

trace 是按时间排列的事件记录，dump 是某一时刻的状态快照。源码能解释路径，却不能单独证明目标设备当时走过该路径；缺少关键事件时应调整采集配置再测。Perfetto 阶段可用未见过的 trace 写报告，跨层案例还要标注 AOSP 与 Android Common Kernel（ACK）的版本坐标。

**Q2: [learning] Android 性能分析为什么需要分别固定平台和内核源码版本？**

平台与内核分属不同仓库、演进节奏不同；只记录“Android 版本”不足以复核调度、Binder、回收或文件系统路径。分析时分别固定 AOSP tag（例如 `android-17.0.0_r1`）与 ACK（Android Common Kernel）tag，进入相应机制后只引用对应 tag 的实现。旧文章的操作步骤、阈值和工具界面需先与目标版本对照，长期有效的观测思路才可迁移。

商用设备还要记录 build fingerprint、API level、内核版本、SoC、刷新率、电源模式与温控状态。厂商可能修改 Framework、HAL、内核配置和性能策略，因此 AOSP 是公共机制参照系，目标设备上的 trace、配置和符号才是执行证据。

**Q3: [learning] 性能排查前应固定哪些测试条件和对照证据？**

先固定环境再采样，否则差值可能来自设备状态而非目标改动。至少记录以下四组：

1. **软件与硬件：**应用版本、构建类型、设备型号、Android/API 版本、CPU ABI 和厂商 build。
2. **运行状态：**刷新率、电源模式、电量、充电状态、温控状态、账号数据和网络条件。
3. **复现过程：**可执行步骤、发生概率、用户可见现象，以及日志和 trace 可对齐的起止时间。
4. **对照样本：**同机同场景下至少一组正常与一组异常样本，尽量保持账号、数据、网络和操作一致。

异常样本只说明当时发生了什么；正常样本的差值才帮助区分候选原因。记录中还要把观测与假设分开：主线程等待 Binder 回复 800 ms 是观测，远端服务持锁仍需远端线程或调度证据验证。

**Q4: [learning] 卡顿排查为什么先确认出图路径，再选择 Perfetto 与方法跟踪？**

出图路径决定要采集哪些线程和 layer；先搜最长方法可能把无关工作当根因。按下面顺序缩小范围：

1. **辨认现象与生产者：**区分列表掉帧、动画停顿、点击无反馈和画面撕裂；确认页面由 View、Compose、SurfaceView、TextureView 还是 OpenGL/Vulkan 出图，并记录悬浮窗、Dialog、多窗口或画中画。
2. **定位责任阶段：**应用未按时交帧时看 UI、RenderThread 和 GPU；buffer 已交而显示迟到时看 SurfaceFlinger、HWC 与 present。FrameTimeline 的 Expected/Actual 和目标 layer 比单个长 slice 更适合确定时间窗。
3. **缩小方法范围：**系统跟踪定位区间后才打开 method tracing。它记录方法进出并会扰动执行，应先保存无插桩对照，确认新出现的卡顿不是跟踪开销造成。

帧预算随刷新率变化，60/90/120 Hz 分别约 16.7/11.1/8.3 ms，不能对所有设备套 16 ms 固定阈值。

**Q5: [learning] 启动排查为什么必须先对齐测量口径与编译状态？**

不同工具的起点、终点和对设备状态的控制不同，同名“启动耗时”不能直接比较。先记录冷、温、热启动类型，再按来源标明所量区间：

1. **`am start -W`：**记录 TotalTime 以及该命令采用的测量起止点。
2. **Logcat：**记录 Displayed 对应的首次显示事件。
3. **Macrobenchmark：**记录测试脚本的 StartupMode、起止点及 TTID/TTFD 指标。TTID 是首帧显示；TTFD 依赖应用报告关键内容可用。
4. **业务埋点：**记录应用自定义起点和完成条件，不与系统指标同名混用。

编译状态要区分产物存在与规则被采用。`/data/app/.../oat/.../base.odex` 存在不能证明 Baseline Profile 已安装或被 ART 采用；用 ProfileVerifier 或 `dumpsys package dexopt` 核对实际状态。减少启动线程后同时比较 TTID 与 TTFD，并检查后台任务是否与主线程竞争 CPU，防止只是把工作推迟。

**Q6: [learning] 内存排查怎样先区分故障类型，再解读曲线形态？**

入口取决于故障种类，不能用 Java 堆余量解释所有内存问题：

1. **Java 堆 OOM：**记录 `OutOfMemoryError` 消息、堆上限、失败分配大小和调用栈。
2. **Native 分配失败：**查 tombstone、malloc 日志和 Native Heap。
3. **系统压力杀进程：**用 Android 11+ 的 `ApplicationExitInfo` 核对 lmkd 退出原因；应用内通常没有 Java OOM。
4. **文件描述符耗尽：**`EMFILE` 是句柄问题，不能并入堆 OOM。

用 `dumpsys meminfo` 判断增长来自 Java Heap、Native Heap、Graphics 还是 mmap；PSS 估算进程对系统的贡献，不是堆硬上限。多轮操作后基线持续抬升，查对象是否被 GC Root 意外持有；高频锯齿伴随停顿，查分配速率及 GC，尤其是 `onDraw()`、`onBindViewHolder()` 和重组热点。Heap Dump 中仍存活的对象须按预期生命周期判断，不能一概称泄漏。

**Q7: [learning] 待机耗电应怎样从现象找到持续唤醒来源？**

先把发热、前台续航缩短、后台静默掉电和待机无法休眠分开复现。固定亮度、刷新率、网络信号、位置、音量、电量区间与温度并保留正常对照；温度升高本身不能指出耗电模块。

待机异常沿唤醒链排查：

1. **休眠状态：**确认设备是否进入 Deep Sleep；未进入时对齐唤醒源、定时器、中断和 WakeLock 的覆盖区间。
2. **后台执行：**检查 `PARTIAL_WAKE_LOCK` 是否在异常、取消与超时路径成对释放；周期任务是否频繁用 AlarmManager 唤醒。可延迟的持久工作可用 WorkManager 合并并附带网络或充电约束，精确闹钟留给面向用户的定时事件。
3. **硬件与画面：**检查静止页面是否仍提交帧、定位或传感器扫描是否随生命周期停止、蜂窝小包是否高频上传。

Android 15 及以上、target API 35+ 的 `dataSync` 和 `mediaProcessing` 前台服务有各类型任意 24 小时累计 6 小时的限制，同类型服务共享时限；到期触发 `Service.onTimeout(int, int)`。该限制不能外推到所有版本和 FGS 类型。

**Q8: [learning] 一份可复查的性能案例报告应保留哪些证据？**

报告要让未参与排查的人复原环境、找到同一时间窗并验证改动。按诊断顺序保留：

1. **问题与条件：**用户场景、指标、阈值、发生比例，以及设备、版本、构建、数据、网络、温控和操作脚本。
2. **证据时间轴：**正常与异常样本、目标窗口、线程状态、系统资源、跨进程关系及原始 trace/dump/benchmark。
3. **机制与假设：**对应 tag 的源码路径、事件记录点、版本差异、候选原因、反证和缺失证据。
4. **干预与复测：**单变量改动、预期中间量、复测原始样本与分布、功能回归、副作用和回退条件。

工具截图的一条长区间只能证明区间存在。线程可能在运行，也可能等待锁、Binder、I/O 或 CPU；先用状态分解和跨线程证据解释，再决定责任。另一位工程师复测方向不一致时，要追查环境噪声或缩小结论范围。

## 性能分析与测量方法

**Q9: [learning] 性能优化中“道、术、器”各承担什么工作？为什么“应用很慢”这类反馈必须先改写成关键用户旅程才能开工？**

「道」决定要改善哪个用户场景、用什么数据判断改善是否成立。「术」规定从现象到证据、从假设到验证的分析顺序。「器」负责采集、查询和呈现证据，且每种工具都有可见范围与扰动成本。“应用很慢”无法直接转成实验，必须先改写成有起点、终点和成功条件的关键用户旅程（CUJ，Critical User Journey）：例如“进程不存在时点击图标，直到首页达到可交互状态”，对应冷启动的 TTID/TTFD 与首帧前主线程任务。“固定数据集、固定手势滚动同一列表”对应超时帧分布与 FrameTimeline。

一次卡顿可能同时包含主线程排队、Binder 等待、RenderThread 提交、GPU 执行和 SurfaceFlinger 合成，只盯某个函数耗时容易把症状当原因。因此性能结论至少要写成一组条件：CUJ × App 版本 × 构建类型 × 设备档位 × Android 版本 × 编译状态 × 环境条件 × 统计量，缺任何一项，后续复测就可能换了实验对象。同一指标也不能替代用户旅程：冷启动 P50 改善不证明温启动或低端机改善，平均帧耗时降低也覆盖不了少量冻结帧。

**Q10: [learning] AOSP 把性能问题分为 capacity 与 jitter 两类，这个分类如何决定优化方向和报告口径？**

capacity 是一段时间内可用的 CPU、GPU、I/O 与内存带宽总量。布局、绘制或计算在稳定状态下持续超过 deadline，说明资源容量不足或工作量过大，优化方向是减少工作量或增加有效资源。jitter 是偶发工作阻塞了可感知路径，常见来源包括：

- 调度延迟、IRQ 与 softirq；
- 长时间禁占区与锁竞争；
- Binder 等待与 I/O；
- idle 切换与页缓存波动。

提高频率能缩短计算时间，却无法消除固定时长的驱动等待或锁持有，所以要先判断类型再动手。平均值会掩盖 jitter，报告至少保留 P50、P90、P99、最大值、超过目标的样本占比与总样本量，按设备档位、刷新率、温度与交互类型分组。

边界：RAIL 模型的 100 ms 响应目标来自浏览器环境，远大于高刷设备一帧期限（120 Hz 约 8.33 ms），一个 40 ms 主线程任务在 120 Hz 上已可能阻塞多帧，它不是 Android API 或 ANR 的硬阈值。Web INP 衡量交互到下一次绘制，Android 没有同口径的公开 Vitals 指标，借用其“以完整交互为单位、关注高分位”的思想即可，内部指标要自定义起点终点并注明不能写成官方标准。

**Q11: [learning] 一次性能调查的六步分析流程是什么？每一步最容易被省略的东西是什么？**

六步依次是：定义现象、建立基线、采集能区分假设的证据、提出可证伪的假设、一次只改变一个主要变量、复测并检查副作用。流程的目的（「术」）是让结论可复查，而不是尽快找到一个“像原因”的东西。

1. **定义现象**：记录 CUJ、出现频率、受影响版本、设备与网络条件。来自线上告警时还要记录指标口径、时间窗口和样本量。此时不要提前指定根因。
2. **建立基线**：修改代码前用同一套测试跑基线，保留构建产物标识、设备温度、刷新率、编译模式和系统版本。P50 看典型情况，P90/P95/P99 看长尾，小样本应保留每次测量值。
3. **采集能区分假设的证据**：采集配置由假设决定——启动问题要进程启动、主线程、Binder、I/O、ART 事件，掉帧要 FrameTimeline、Choreographer、RenderThread、SurfaceFlinger 与频率信息。把所有数据源全开只会增大 trace、提高扰动、降低分析效率。
4. **提出可证伪的假设**：假设中要包含证据、改动和预期结果，例如“冷启动首帧前主线程同步调用该 SDK 初始化并占据连续执行时间。延后该调用后 TTID 分布应下降，且 TTFD 与功能可用性不回退”。“某 SDK 很慢”这种写法无法证伪。
5. **一次只改一个主要变量**：同时改线程模型、缓存、布局和编译配置，即使指标改善也无法判断贡献来自哪里。高风险改动分阶段提交，每阶段保留独立测量。
6. **复测并检查副作用**：同协议复测、比较分布与置信区间，并检查内存、功耗、稳定性和业务正确性——把主线程工作移到后台线程可能改善帧耗时，也可能增加 CPU 竞争、启动后尾部延迟或耗电。

最常被省略的是第 2 步（没有基线，收益无法复查）、第 5 步（多变量同改，归因失效）和第 6 步（只看目标指标，副作用漏检）。

**Q12: [learning] 性能报告的结论为什么要按证据等级分级？“观测、机制、干预、复现”四级分别允许写出什么结论？**

性能调查很容易从“两个事件同时出现”跳到“其中一个导致另一个”，分级是给结论的强度划边界：每一级回答一个不同的问题，允许写出的结论范围不同，报告应写明当前停在哪一级。

1. **观测**：回答“目标窗口里发生了什么”，只能写“两个事件在时间上重叠”或“某项指标发生变化”。
2. **机制**：回答“调用、等待或资源关系怎样连接两者”，可以写“存在一条能解释影响方向的路径”。
3. **干预**：回答“改变该机制后预期中间量是否随之变化”，可以写“当前改动与改善一致”。
4. **复现**：回答“不同轮次、设备或灰度对照是否保持同方向”，结论才适用于已覆盖的总体。

每个候选原因写成“假设—预期观测—反证—状态”。例如假设主线程 CPU bound（耗时主要来自 CPU 计算），预期 wall time 接近 on-CPU time 且采样栈集中在稳定调用链。如果大部分时间实际是 Runnable、Binder 等待或锁等待，这条假设就应降级或排除。被证伪的假设要保留记录，避免下一位排查者重复同一条无效路径。

**Q13: [learning] 性能测量有哪些常见偏差来源？为什么说测量协议比“多跑几次”更有价值？**

同一条件下重复运行仍会出现随机波动，测量协议的作用是让偏差可控制、结果可比较，而不是靠次数堆出“稳定感”。常见来源与控制方法：

1. **Debug 与 Release 构建差异**：优化、插桩、断言、资源与代码布局都不同，应使用接近发布配置的可测构建并记录签名与混淆状态。
2. **编译状态**：JIT/AOT/Baseline Profile 状态直接改变启动与热点执行时间。Macrobenchmark 中固定 `CompilationMode`，不混合比较不同编译状态。
3. **设备温度与 DVFS**：记录 thermal 状态、随机化实验顺序，必要时等设备回到同一温度区间。
4. **刷新率变化**：帧 deadline 与 jank 判定随之改变，记录实际刷新率，优先用 FrameTimeline 的 deadline，不套固定 16.67 ms。
5. **缓存与进程状态**：明确清理范围。清进程、清数据、清页缓存不能混为同一种“冷启动”。
6. **采样或插桩扰动**：使用能回答问题的最低采样率与最小数据源集合，并做有/无采集对照。
7. **自动化脚本不稳定**：用语义条件等待页面，不用固定 `sleep` 代替业务完成信号。

工具定位也要分清：AndroidX 官方把 Macrobenchmark 定位为进程外的端到端测量（可控制启动与编译状态），Microbenchmark 用于进程内代码片段，二者的结果范围不同、不能互换。

**Q14: [learning] 实验室数据与线上数据各自能回答什么问题？为什么"GC""Binder""I/O"这类工具标签不能当根因？**

实验室测量（Macrobenchmark、Perfetto、受控环境）适合控制变量、复现问题和验证改动。线上数据（Android vitals、自建监控）适合判断影响范围、设备分布和长尾趋势，聚合数据通常不足以直接定位代码。JankStats 能把帧耗时与 UI 状态一起交给回调，但采样、聚合、上传和隐私维度仍要应用自己设计。Perfetto 只包含采集配置启用且生产者实际写入的事件，未启用的数据源不会事后出现在 trace 里。推荐顺序是：用线上数据选择场景和设备层级，用实验室复现并定位，再用基准测试和按版本、设备分组的线上数据验证改动。

工具标签只是观察到的活动，不是原因：“trace 里显示 GC”还需要回答分配来自哪里、GC 是否跨过该帧 deadline、能否在相同条件下复现。“Perfetto 只看线程、Simpleperf 只看 Native”这类记忆法也会误导选型——Perfetto 可包含调用栈与多类 profiling 数据，Simpleperf 在符号与权限齐全时也能覆盖 Java、Native 与部分内核。证据范围取决于本次实际采集的字段，不能由工具名称推断。优化方向按瓶颈形态选：线程长时间 Runnable 查竞争与调度约束，blocked 查锁、Binder、futex 与 I/O，Java heap 持续增长查保留路径与缓存上限。优先删掉不必要的关键路径工作，异步化只改变执行位置，后台线程仍消耗 CPU、内存带宽和电量。

**Q15: [learning] 性能优化的优先级怎么排？团队要固定哪些治理机制，优化收益才不会随版本回退？**

排优先级看四个维度：

1. **影响人数：**受影响用户、机型、系统版本占比。
2. **出现频率：**问题在目标场景中的出现频度。
3. **体验损失：**延迟长度、是否中断、是否丢数据。
4. **修复把握与成本：**证据是否充分、改动范围、副作用风险。

“影响人数 × 频率 × 损失程度”可帮助排序，但不要把主观评分伪装成精确模型——低频的严重 ANR、OOM 或数据损坏可能高于高频轻微波动。实证研究只能做覆盖审计：某论文样本中用户评论 62.3% 归入 Responsiveness、开发者问答与修复提交主导的是 Memory Consumption、69/85 篇论文研究能耗，这说明各来源主导类别不同，团队应把自家线上数据映射到 Responsiveness、Memory、Energy、Storage、CPU、GPU、Internet Data Usage 七类后果，检查是否持续忽略用户可感知类别。论文比例不能换算成人力比例或门禁阈值。

单次专项只能改变当时的基线，可持续的治理要固定五类要素：

1. **性能预算：**写成版本化测量合同，包含 CUJ 与起止信号、构建与编译状态、设备与刷新率、统计口径与允许噪声、超限后的复测审批回滚规则。预算分用户体验 SLO、实验室回归预算、资源预算三层，互相不能替代。
2. **基线：**记录 git commit、版本、依赖、设备 fingerprint、编译模式与环境；基线不是目标，不能靠更新基线隐藏回归。
3. **回归门禁：**确定性门禁直接阻断、测量门禁按样本与噪声规则阻断、线上门禁暂停扩量或回滚。
4. **灰度观测：**对照同时间窗，按设备与版本分群。
5. **发布验收：**线下复测结论、构建产物检查、灰度指标与批准记录写回版本。

异常要走完“发现 → 归因 → 修复 → 验收”，告警关闭不等于修复成功——采样切换、上报中断或用户构成变化也会让曲线下降。

**Q16: [learning] “系统问题”和“App 问题”为什么不是归因终点？一份可复查的归因报告应该分哪四层？**

二分标签只适合分派工作，描述不了因果链：一次输入延迟可能经过 App 主线程、Binder 跨进程服务、CPU 调度器、驱动和硬件。SurfaceFlinger 错过 deadline 时，待合成的 Layer 也可能由 App 提交。线程处于 Runnable 只证明它可运行但尚未获得 CPU，竞争者可能来自同一 App。主线程正在 Running 也不自动指向 App 代码，它可能在执行 Framework、系统调用或共享库。

可复查的归因报告分四层，每层回答一个问题：

1. **观测事实**：trace 中发生了什么，例如“主线程在目标窗口内有较长 Runnable 区间”。
2. **直接原因**：延迟花在哪种状态或组件，例如“唤醒后未及时获得 CPU，同一 cpuset 内存在竞争”。
3. **所有者**：哪段代码、配置或工作负载制造了条件——App 后台线程、另一个进程、system_server 或厂商调度配置。
4. **改动责任**：哪一侧能以最低风险修复——App 减少工作、Framework 限制后台负载、厂商调整策略，或多侧配合。

归因前先固定问题窗口（起点、终点对齐用户旅程），不要先看整份 trace 的 CPU 排行——问题窗口可能只有几十毫秒，整段录制中的后台任务会稀释局部竞争。报告结尾写清主要工作量由谁产生、哪些背景条件由多方共享、哪一侧能先缓解，这比一句“系统问题”更容易推动修复。

**Q17: [learning] 主线程长时间处于 Runnable 应该按什么顺序归因？“全核满载”和“整机利用率低”分别会误导什么判断？**

Runnable 只证明线程具备运行条件但未获得 CPU。按时间与资源约束依次检查：

1. **唤醒时刻：**定时器、Binder 回复或生产者是否本身就晚到。
2. **可运行 CPU：**结合 affinity、cpuset、在线核、异构容量和 RT/DL 任务判断，不能只看整机平均利用率。
3. **竞争者：**目标 CPU 或可选 CPU 当时运行的是本进程后台线程、其他 App、system_server、内核线程还是 IRQ。
4. **执行容量：**频率、uclamp 与 thermal 是否限制执行速度，把调度等待与获得 CPU 后执行变慢分开统计。
5. **对照回归：**在相同条件下是否比基线变差；单次延迟不能单独证明策略回归。

两个总量指标容易误导：

1. **全核满载：**只说明算力紧张，还要核对它是否覆盖问题窗口、目标线程可运行在哪些 CPU、竞争者属于谁。若主要负载是 App 自己的线程池，App 就是工作量所有者；外部竞争也不免除 App 对关键路径余量的检查。
2. **整机仍有空闲核：**异构 CPU、affinity、cpuset、RT 任务和 idle balance 仍可能让目标线程得不到可用核。报告应给出 allowed CPU、实际运行 CPU 和同窗口竞争者。

频率下降也不等于 thermal throttling；确认热影响还需 thermal 状态、频率上限变化和对照实验。

**Q18: [learning] kswapd 活跃、App 频繁 GC、进程被 lmkd 杀掉，这三者之间能直接推出因果吗？**

不能。kswapd 运行只证明后台内存页回收被唤醒，它不能单独证明：目标 App 正在使用 swap、主线程的 D 状态来自页换入、lmkd 已经或即将杀进程、ART GC 由系统内存压力触发，更不能证明增加物理内存是唯一修复。GC 频率主要与分配速率、堆容量、对象存活和 ART 策略相关，“kswapd 活跃，所以 App 频繁 GC”缺少中间证据，应核对 GC 区间、分配速率、Java/native heap 与对象保留关系。

继续归因要组合证据：PSI 的 memory `some`/`full`、direct reclaim、major fault、zRAM、进程 RSS/swap、lmkd kill 事件与 `oom_score_adj`（AAOS13 的 `system/memory/lmkd/lmkd.cpp` 核对到其读取 PSI 与内存统计选择目标）。判断压力制造者用对照方向：App 的 RSS 或 heap 持续增长，它可能是贡献者。App 内存稳定而 PSI 与其他进程 RSS 同时上升，系统侧资源管理更值得调查。lmkd 的候选选择还受 `oom_score_adj` 与厂商配置影响，不能把一次 kill 简化成“内存最大的进程被杀”。

**Q19: [learning] 系统升级后应用出现性能回退，怎样用 A/B 对照方法把“版本回归”从噪声里分离出来？**

A/B 指只改变待验证因素（系统版本）的对照实验。先固定其余变量：同一 App 构建、账号数据、操作脚本、设备型号、刷新率、编译状态和 thermal 区间。同时记录 `ro.build.fingerprint` 与安全补丁级别、ART/Profiling 等 Mainline 模块版本、kernel release 与厂商补丁标识、GPU 驱动、Perfetto 版本与采集配置。同一台设备无法在同一时刻运行两个系统版本：用两台同型号设备要先量化个体差异。刷机往返则要控制数据恢复、dexopt、缓存和后台初始化。

比较阶段而不只比较总时间：把 CUJ 分成 App Running、Runnable、Binder、I/O、RenderThread/GPU、SurfaceFlinger 等阶段，总时长回退可能来自不同阶段，只有阶段差异稳定出现，才继续映射到源码或配置变更。源码差异用来验证假设：怀疑调度策略就比较 kernel/厂商 config、cpuset、uclamp 与 power hint。怀疑 Framework 服务就比较对应 AOSP tag 与 DeviceConfig。怀疑显示链路就比较 SurfaceFlinger、HWC、GPU 驱动与 Layer 输入。版本号上的时间相关性只能提出假设，不能代替源码或实验证据。

**Q20: [learning] 一个长期性能指标的定义要写清哪些字段？指标按职责分为哪四类？**

“启动耗时”“掉帧率”只是名字，可比较的数据来自可审查的指标合同。合同至少写清：

1. **测量对象：**用户场景、起点和终点事件、观测单位（frame、launch、operation、session 或 user-device-day）与数值单位。
2. **计算口径：**分子和分母、排除条件、统计窗口、聚合方法（分位数、比例、直方图或 sketch）。
3. **样本口径：**版本、设备、刷新率、启动类型等维度，以及采样概率、稳定抽样键和权重。
4. **维护坐标：**Android、SDK、schema 与采集配置版本和负责人。合同变化时建立新 schema 版本，不拼接新旧趋势。

按用途再分四类指标：

1. **用户结果：**用户是否完成操作，如 TTFD、click-to-display 和播放首帧。
2. **线上健康：**影响范围，如 user-perceived ANR/crash、slow startup、excessive wake lock。
3. **发布门禁：**候选版本能否发布，如固定 CUJ 的 Macrobenchmark 分布和内存峰值。
4. **诊断解释：**为何回退，如 thread state、frame overrun、Binder latency 和 RSS/heap。

同一数值承担不同用途时合同通常不同。Play 的 bad-behavior threshold 是外部健康线，团队仍需与自身场景匹配的发布目标。

**Q21: [learning] FPS、Android Vitals 的 slow/frozen frame、FrameMetrics 三种帧口径各描述什么？为什么 16.67 ms 不能当所有设备的统一帧预算？**

三种帧口径分别回答不同问题：

1. **FPS：**单位时间内呈现帧数的比值。平均 FPS 会被少量长帧稀释，静止页面主动减少帧数也可能是正确的节能行为，因此它适合描述持续动画或游戏吞吐。
2. **Android Vitals slow/frozen frame：**对使用 View/Canvas UI Toolkit 的应用记录 slow frame（渲染 16–700 ms）与 frozen frame（超过 700 ms），是 Play 的固定报表口径；Vulkan、Unity、Unreal 等直接渲染的主画面不能假定进入这组数据（游戏另看 Slow Sessions）。
3. **FrameMetrics：**API 24 起提供窗口帧分阶段耗时，API 31 起有 `GPU_DURATION` 与 `DEADLINE` 字段；源码注释定义 `TOTAL_DURATION < DEADLINE` 表示命中该帧预算——AAOS13（API 33）源码可核对到这些字段与该注释。缺失值不能当作 0。

普通 UI 回归更适合结合 frame deadline、jank 比例和场景状态。

16.67 ms 只是 60 Hz 的一个周期：90 Hz、120 Hz、可变刷新率与 SurfaceFlinger 调度都会改变每帧 deadline，Android 12+ 应用 FrameTimeline 或 `frameOverrunMs` 判断是否错过平台分配的窗口。使用 jank rate 前必须定义分子——可以是 `frameOverrunMs > 0` 的帧数、JankStats 启发式判定的帧数、FrameTimeline 的某组 jank type 或业务自定阈值，不同分子不能共用一个“卡顿率”名称。分位数也要求样本来自同一合同：同 CUJ、同刷新率或按刷新率分桶、同版本与设备档位、同帧类型与采样规则。

Android vitals 的 Slow sessions 是面向游戏会话的口径，与传统 UI Toolkit 的慢帧/冻结帧统计范围不同，不能把两者分母混在同一比率中。

**Q22: [learning] TTID 和 TTFD 的起点与终点有什么不同？Play 的 slow startup 口径是什么，为什么内部预算不能直接抄它？**

TTID（Time to Initial Display）从系统收到启动请求到 App 第一帧显示，Android Framework 自动报告。TTFD（Time to Full Display）覆盖 TTID 之外首帧后异步加载的主要内容，终点由 App 调用 `reportFullyDrawn()` 标记——不调用就没有可用的 TTFD。冷、温、热启动路径不同：Cold 从头创建进程。Warm 复用进程但可能重建 Activity 或借助 saved instance state。Hot 把仍驻留内存的 Activity 带回前台。因此 TTID 不能一律写成“进程创建到首帧”，三类启动也不能放进同一个分布比较。

Play 当前（材料于 2026-08-14 与官方文档核对）按 TTID 判定 slow startup：Cold ≥ 5 s、Warm ≥ 2 s、Hot ≥ 1.5 s。这组值用于解释 Play Console，内部预算应更严格，并绑定设备档位、构建、编译模式和分位数。TTFD 的埋点位置要对应“主要内容完成且用户可交互”的业务状态：调用过早把空壳页面记作完成，过晚把非首屏工作混入启动。UI 或导航改版后要复核 fully drawn 条件。TTID 与 TTFD 要分开看：前者适合观察系统启动、Application/Activity 创建与首帧，后者覆盖首屏数据与业务准备。

**Q23: [learning] 分位数为什么不能跨分片求平均？线上采样为什么必须区分基线样本与异常触发样本？**

分位数对分布形状敏感、不可加总：机型 A 的 P90 与机型 B 的 P90 平均不是总体 P90，每日 P99 的平均也不是周期 P99。跨分片聚合应合并原始直方图或 t-digest/DDSketch 这类可合并的分布摘要，或重新计算原始样本。看板还要标明分位数基于 frame、launch、session 还是 user 聚合：高频用户产生更多事件，event-level 的 P99 会偏向高频用户，user-level 指标应先在用户内聚合、再在用户间聚合。均值没有被废除——CPU time、energy、bytes 这类可加总成本适合均值，长尾敏感的交互延迟才必须配分位数、超阈值比例和样本量。

采样分两条逻辑通道：`baseline_sample` 用稳定的头部采样（对隐私审查过的用户或设备标识做哈希，整个采样单元内概率稳定），每条记录保存入选概率 p，服务端按 1/p 加权估计总体，用于趋势与 SLO。`diagnostic_sample` 由 ANR 预警、严重停顿或极端启动等事件触发，入选概率依赖结果，只能用于聚类与 trace 回查，不能估计总体发生率或总体分位数。只采“启动很慢”的会话无法估计慢启动率，因为正常会话没有进入分母。报警则要同时满足四类条件：指标超过绝对 SLO、相对稳定版本或灰度对照回归、达到最小样本量、数据健康（覆盖率、延迟、丢弃率）正常——缺数据健康检查的告警会把 SDK 关闭或上传故障误判成性能改善。

**Q24: [learning] 一套线上监控系统分哪三层？ANR 相关的线上事件至少要分成哪三类，为什么不能互相替换？**

线上监控按数据处理阶段分三层：

1. **信号层：**用低开销事件记录现象，如 JankStats、FrameMetrics、ApplicationStartInfo、ApplicationExitInfo 与业务埋点。
2. **证据层：**按预算在异常前后保存现场，如本地环形缓冲区、ProfilingManager 和 trace。
3. **分析层：**确定分母、校正采样、分群并检测回归，可结合 Android Vitals、APM 或自建系统。

分层后，客户端可独立调整采样，服务端也能区分系统判定、库的启发式判定与业务规则。

ANR 事件至少分三类，因为它们的来源和证据强度不同：

1. **main_looper_stall_candidate**：自建 watchdog 在后台线程向主 Handler 投递探针，超预算未确认时记录主线程栈、页面与消息上下文。它是应用侧现场，能捕获“恢复且未退出”的长停顿，只能叫候选，不能计入系统 ANR 率。
2. **anr_warning**：API 37 的 `ActivityManager.registerAnrWarningListener()`，系统认为某条 ANR 路径接近超时尽力回调。回调可能不执行也可能来不及完成，预警未兑现不代表没有发生（App 可能已恢复）。
3. **process_exit_anr**：API 30+ 的 `ApplicationExitInfo.REASON_ANR`，后续进程读取系统保留的退出历史。受环形缓冲区容量限制，可能被新记录覆盖。

Android Vitals 的 user-perceived ANR 口径（材料 2026-08-14 核对）只计入 input dispatching timed out、分母是日活用户，与本地事件率、退出记录口径都不同，三类数据要并列展示，不能用一个数替换另一个数。

**Q25: [learning] 设备分级为什么要把“稳定能力画像、工作负载策略、会话期压力”分成三层，而不是给整机算一个高/中/低总分？**

因为三类信息的含义与生命周期不同：

1. **稳定能力画像：**low-RAM 标记、应用堆上限、Media Performance Class、ABI、明确硬件特性；在安装、系统升级或配置版本变化时才重算。
2. **工作负载策略：**图片缓存预算、预取条数、视频规格；随应用版本和远端策略版本变化。
3. **会话期压力：**温控状态、省电模式、内存压力、丢帧率；在运行期间随时变化。

压成一个总分，会让某项能力很强、另一项较弱的设备收到错误策略——`isLowRamDevice()` 适合影响缓存和预加载，MPC 只覆盖媒体体验，热节流和省电模式只描述此刻条件。一次发热也不应把设备永久记成“低端机”。

策略名称应描述约束而非档次：`memory_constrained`、`media_enhanced`、`render_reduced` 比 `low/middle/high` 在几个月后仍能解释含义。策略数量宜少但“三档”不是平台规则，一项业务只有两个有效配置时就保留两个。同一台设备完全可以同时得到“内存受限”和“媒体能力较强”两个判断，策略解析器按业务维度分别读取，不要先算总分再分配功能。

**Q26: [learning] 设备能力评估应该用哪些公开 API 信号？它们各自的版本边界和常见误读是什么？**

只用公开、可解释的信号，不读隐藏 `SystemProperties`、私有 DeviceConfig 命名空间或内核节点。核心信号与边界：

1. **内存**：`isLowRamDevice()` 是厂商声明的稳定提示，适合收缩高内存功能。`getMemoryClass()` 是当前应用的 Java 堆上限（MB），不表示整机 RAM，也不含 native、图形与共享内存。`MemoryInfo` 各字段语义不同：`totalMem`（API 16）是内核可访问内存，不含基带、TEE 等保留区。`advertisedMem`（API 34）才接近零售标称。`freeMem`（API 37）只统计未使用 RAM、不含可回收页——Linux 会主动用闲置内存做页缓存，单看 freeMem 极易误判压力（AAOS13 源码核对：该版本只有 totalMem/availMem/threshold/lowMemory，后两个字段是后续版本加入）。
2. **CPU/SoC**：`Runtime.availableProcessors()` 允许在同一进程生命周期内变化，只返回调用时刻可用数，不表达大小核性能与频率，并发策略依赖它时应在非关键路径偶尔重读。`Build.SOC_MANUFACTURER`/`SOC_MODEL`（API 31）适合按机型定位异常，不适合单独映射为分档。
3. **媒体**：`Build.VERSION.MEDIA_PERFORMANCE_CLASS`（API 31）返回 0 表示设备未声明等级，官方定义的等级是 30、31、33、34、35（不存在 32）。它覆盖媒体、相机、编解码等 CDD 范围，没有承诺所有工作负载的 CPU/GPU 吞吐，不能当整机跑分，选中高等级后仍要查询目标编解码器或图形特性。
4. **图形/屏幕**：Vulkan 版本与扩展适合做功能门控（接口存在不等于吞吐足够）。120 Hz 面板每帧预算约 8.3 ms，更高分辨率增加像素量与带宽——屏幕规格同时是能力与负载，不能简单加进“高端分”。

缓存预算应同时受应用堆上限、资源类型和内存回调约束。按整机 `totalMem` 固定取百分比会漏掉进程堆限制与图形内存开销。另外不要在冷启动期间跑微基准探测硬件：它会延长启动、增加发热，并产生受后台负载影响很大的样本。

**Q27: [learning] 会话期的动态降级为什么需要迟滞和最短驻留时间？查询 thermal 与 CPU/GPU headroom 有什么限制？**

没有迟滞的质量控制会来回振荡：降级阈值必须高于恢复阈值（例如丢帧比例 ≥ 10% 降级、≤ 3% 且 thermal 状态回到 LIGHT 以下才恢复），再加每个档位的最短驻留时间（如 30 秒），短时抖动就不会反复改变画质。恢复时逐级增加工作量，避免一次恢复全部特效后立刻再次触发降级。

headroom 类查询各有约束：`getCurrentThermalStatus()` 与 `isPowerSaveMode()` 自 Android 10 可用。`getThermalHeadroom(forecastSeconds)`（Android 11 / API 30）预测未来 0–60 秒，返回 1.0 对应预计达到 `THERMAL_STATUS_SEVERE`，设备不支持返回 NaN，它跟踪皮肤温度这类慢变量，一秒内重复调用没有收益。

CPU/GPU headroom（API 36 的 `SystemHealthManager`）有效范围 0–100、0 表示没有更多容量，不支持的设备抛 `UnsupportedOperationException`。每次有效查询至少包含一次同步 Binder 调用、耗时可能超过 1 ms，不能在主线程、渲染线程或其他关键线程等待，且要遵守设备给出的最小轮询间隔。headroom 描述当前负载还能获得多少余量，适合参与会话期调节，不适合当稳定机型等级，也不应成为首帧前必须同步取得的数据。

**Q28: [learning] 远端下发的设备策略为什么必须能离线启动？验证分级收益时要注意哪些陷阱？**

远端配置可以修正规则和控制灰度，但冷启动必须能使用本地配置完成。数据契约按生效顺序检查：

1. **离线默认值：**APK 内置已验证的安全配置，本地保存上一份通过校验的配置；拉取失败继续使用本地版本。
2. **版本与有效期：**配置携带 `schemaVersion`、`policyRevision`、适用应用版本和过期时间；未识别字段按兼容规则忽略，越界值拒绝生效。
3. **降级与切换：**高风险开关有独立 kill switch，同一前台会话维持稳定策略，新配置到下一次会话再切换。
4. **曝光记录：**只有策略实际应用到用户行为的样本才记为实验曝光。

验证收益时分别排除三种误判：

1. **分组偏差：**用符合隐私规则的稳定标识确定性分组，并在能力画像或机型层内随机分配；直接比较高端组与低端组会混入用户和系统差异。
2. **汇总与副作用：**分层检查流量构成，防止辛普森悖论。减少预取可用内存峰值作主指标，同时以滚动卡顿、图片等待、请求数和成功率作护栏，比较 P50–P99 而非只看平均值。
3. **安全越权：**设备等级是可篡改的客户端参数，服务端不能据此授予权限或跳过风控。

**Q29: [learning] 为什么性能测试测量的是分布而不是一个数字？测试合同要固定哪三部分？**

调频、缓存、GC、调度、I/O、温度和网络使性能结果成为分布；单次结果只说明那次执行。比较版本时固定三部分合同：

1. **工作负载：**入口、数据、手势、终止条件和正确性断言。
2. **设备状态：**硬件、系统镜像、电量、温度、显示、网络与后台活动。
3. **统计协议：**预热、编译状态、迭代次数、排除规则、聚合方法与回归判定。

Macrobenchmark 不能自动补齐合同。每个 benchmark 的代码和报告还要标明目标指标与方向、起止点、数据与编译状态、环境、重复次数、判定阈值及原始 JSON/trace 保存位置。测量块末尾要断言目标页面或业务状态已出现；否则崩溃后快速返回错误页也可能得到“更快”的结果。

**Q30: [learning] 性能回归测试的环境标准化有哪些关键约束？为什么 cmd thermalservice override-status 不能用来制造“未降频”基准？**

数值判定用专用真机：模拟器结果受宿主机、虚拟化、GPU 和存储影响，官方不建议用于性能判定，只适合安装、导航、断言与产物产出的冒烟检查。参考设备要固定序列号、build fingerprint、安全补丁、vendor 镜像、ART Mainline 版本、电池健康与实验室散热方式。目标应用应接近 release：`debuggable=false`、启用 `profileable`（允许 shell 在不打开调试时采集）、与发布一致的 R8 配置、包含 ProfileInstaller。eng 构建、debuggable 目标包和 method tracing 都会改变执行路径。显示模式与亮度保持一致或记录每帧实际 deadline，测动画时保留发布配置的 animation scale。`adb shell am kill-all` 只清理符合条件的后台进程，不能当作“设备已经干净”的证明，串行运行并禁止同设备并行测试。

温度用状态门控而不是统一摄氏度：不同设备的 thermal zone、传感器位置和厂商策略不同，`/sys/class/thermal/thermal_zone*/temp` 在量产设备上可能不可读。做法是为参考机建立冷机基线（记录室温与散热配置、从 `dumpsys thermalservice` 与 Perfetto 记录热状态、试运行确定降频前的状态范围），超出门控就暂停冷却并把样本标记为环境无效，保存每轮起止热状态。

`cmd thermalservice override-status` 只覆盖 `ThermalManagerService` 向 Framework 暴露的状态等级，不会关闭 Thermal HAL、内核 cpufreq、GPU 降频或厂商 thermal engine——它适合测试应用的 thermal callback，不能用来伪造“未降频”的测量环境。短路径（启动、短滑动）关心冷机状态。游戏、直播等持续负载要测热稳定态，预热数据单独保存、不混入稳态统计，稳态区间由预先定义的滑动窗口条件判定而不是看曲线后现选。

**Q31: [learning] Macrobenchmark 的 StartupMode 和 CompilationMode 分别控制什么？哪些边界最容易误读？**

`StartupMode` 控制测量前的进程与 Activity 状态：

1. **COLD：**测量前终止目标进程，但不清 app data 或业务缓存，也不模拟全新安装。
2. **WARM：**保留进程，重建或启动 Activity。
3. **HOT：**保留进程和 Activity，再恢复到前台。

`setupBlock` 在每轮测量前运行；COLD 会在 setup 与 measure 之间终止进程，不能把必须留在目标进程内存中的准备工作放在 setup 中。

`CompilationMode`（AndroidX Benchmark 1.4.1 口径）控制测量前的 ART 编译状态：

1. **DEFAULT：**API 24+ 等价于 `Partial(UseIfAvailable)`；Profile 或 ProfileInstaller 缺失时记录而不报错。API 23 使用 Full。
2. **Partial(Require, 0)：**要求 APK 包含 Baseline Profile 与 ProfileInstaller，且由 AGP 7.0+ 正确打包 Profile；条件不满足时报错，适合 Profile 回归门禁。无参 `Partial()` 的默认参数是 Require 与 0。
3. **Partial(Disable, N)：**先运行 N 次工作负载收集运行时 profile，再进行部分编译；N 必须大于 0。
4. **None()：**清除 Profile 和预编译状态，测无预编译路径；不清应用数据。
5. **Full()：**全量 AOT，代码体积变大时可能慢于 Partial，不代表性能上限。
6. **Ignore()：**不重置或改变编译状态，只用于外部已精确控制 ART 状态的实验。

“预热”还对应三种不同操作：

1. **Microbenchmark warmup：**让 JIT 与缓存达到稳定判定，不纳入正式统计。
2. **Partial.warmupIterations：**运行工作负载收集 ART profile 后编译，会改变目标应用的测试条件。
3. **持续负载预热：**让温度与频率进入稳态，数据应单独保存。

编译模式的默认值及失败条件按 AndroidX Benchmark 官方 `CompilationMode`、`CompilationMode.Partial`、`BaselineProfileMode` API 参考核对；固定库版本后再复现实验。

**Q32: [learning] 性能回归判定为什么要“排除规则预先定义 + 噪声下限 + 效应量”三件套？基线需要保存哪些内容？**

排除规则须在运行前写入协议，至少覆盖：

1. **环境失效：**热状态门控失败、设备更新或重启、已知设备池故障。
2. **场景失效：**目标页面断言失败、脚本未完成目标动作。
3. **证据失效：**预先要求的 trace 缺失或损坏。

看到慢值后不能以“可能遇到 GC”为由事后剔除有效样本；应保留它并用 trace 判断是否属于用户路径。每次排除都保存原因、原始记录和排除前后样本量。

回归判定不能套跨 workload 的固定百分比，要依次确认：

1. 数据质量与预定义排除规则通过。
2. 候选版本超过产品或平台 SLO。
3. 相对基线的效应量超过该测试的历史噪声，并达到预定义的工程意义。
4. 置信区间或独立重复运行支持同方向；样本不足标为 inconclusive，增加独立运行或转人工。

同机配对时，让候选与基线在同一参考设备上运行；温度或后台活动漂移时按 A/B/B/A 或随机顺序交错。多台设备分别计算再按设备层汇总。p-value 不能替代效应量。

基线合同至少绑定四组记录：

1. **应用产物：**git commit、APK/AAB 摘要及构建工具版本。
2. **工具链：**Benchmark、AGP、Kotlin、R8 与 ProfileInstaller 版本。
3. **设备与环境：**设备序列、build fingerprint、ART Mainline、温度和排除规则。
4. **运行与产物：**workload、测试数据、编译/启动模式、迭代协议、原始 JSON、每轮 trace 和统计脚本版本。

只保存 median 无法区分应用回归与环境、工具或脚本变化。

**Q33: [learning] 竞品性能对比要满足哪些“同”才可复现？为什么 FrameMetrics 和 Macrobenchmark 往往用不到竞品身上？**

一轮可复现的竞品测试至少留四份记录：问题定义（比较哪个场景、指标、时间窗口）、实验清单（设备、构建、版本、账号、数据集、网络、温度、编译状态）、原始结果（每次执行的顺序、成败、指标值与 trace）、结论边界（观察到什么、尚未证明什么）。控制变量遵守四同：

1. **同设备**：A/B 在同一台物理设备、同一用户和同一显示模式下成对执行。同型号不等于同设备，多台设备各自成组，不混成一个分布。
2. **同场景**：用用户可感知的起点、终点和成功条件。两个产品信息架构不同不必强求页面同名，但工作量和用户目标要相近。
3. **同网络**：记录带宽、往返延迟、丢包、DNS 与代理。TLS 会话复用、CDN 命中和服务端负载无法靠“连同一个 Wi-Fi”消除，真实网络下应交替执行 A/B 并把网络时间单列。
4. **同热状态**：不要连续测完 A 再测 B，用 A1、B1、B2、A2 这类交错顺序减少时间漂移。设备一旦进入新的 thermal status 该轮作废并等待恢复。不要锁 CPU 频率，固定频率会改变商用设备的调度与温控行为。

可观测性有硬边界：`FrameMetrics` 只能接入自己控制的应用，无法注入未授权的竞品进程。Macrobenchmark 要求目标包 profileable，竞品通常不满足——竞品侧用 UI Automator/adb 驱动加 shell Perfetto 采集，接受可观测性更低的限制。没有竞品源码时，trace 能证明某线程或阶段占用时间，却不能证明其设计动机。结论应分成“观测、候选原因、验证办法”三列。

**Q34: [learning] adb shell am start -W 的输出应该怎么读？“冷启动”与“清缓存”为什么不是一回事？**

`-S` 会在启动前 force-stop Intent 匹配的包，`-W` 等待启动结果。Android 13 与 17 的输出都是 Status、LaunchState、TotalTime、WaitTime 四项（AAOS13 的 `ActivityManagerShellCommand` 与 `WaitResult` 源码核对。旧版本的 ThisTime 已不再打印）。读法：

1. **LaunchState**：`WaitResult` 区分 COLD、WARM、HOT、RELAUNCH，按进程是否运行、Activity 是否新建分类。报告必须保存该字段，预期 COLD 却得到其他状态时该轮无效。一个竞品可能通过透明 Activity、deep link 或多进程策略进入首页，TotalTime 覆盖到哪个 Activity 取决于本次实际启动链。
2. **TotalTime**：Activity 启动跟踪开始到目标窗口完成首轮绘制的延迟，接近 TTID 的系统口径，不表示网络数据齐备或页面可完成业务操作。
3. **WaitTime**：shell 在调用前后用 uptime 计算的阻塞时间，包含命令发起与返回路径，适合诊断命令等待，不替代 TotalTime。

“冷”只描述进程在启动前不存在：`-S` 不清空文件页缓存、应用数据或服务端缓存。要求首次安装、首次登录或冷数据的场景必须另设，不能借 `-S` 推断。编译状态也要选一种协议并声明：保留各 App 经商店分发和正常使用形成的编译状态（回答用户现实体验），或对所有包执行同一安装与编译流程（如 `cmd package compile -m speed -f`，回答固定 ART 条件下的差异，但不能称为商店体验）。`speed-profile` 在未核验各 App profile 是否存在且质量相近时，会引入新的不一致。Android 12 起的系统 SplashScreen 早于应用首帧出现，但 TotalTime 的完成点仍是目标 Activity 窗口绘制，不表示数据可用。

**Q35: [learning] 竞品包体积对比前为什么要先选定“包”的对象？哪些数字不能混用？**

“包有多大”至少对应四种对象：

1. AAB（上传商店的发布包）；
2. 单个 APK；
3. 某设备实际收到的 base + split APK 集合；
4. 压缩下载估算。

只拿竞品的 `base.apk` 与自家 universal APK 比较没有意义。横向对比要先收集目标设备完整 split 集合，按相同 ABI、density、language 和动态模块计算，取不到完整集合时写明缺少的 split。

两种大小口径不能混用：raw file size（文件本身大小）与 download size（传输压缩后的估算），`apkanalyzer apk file-size` 与 `apk download-size` 分别读取，后者是估算值、不等于 Play Console 的交付统计。分类检查按维度进行：DEX 大小受功能量、依赖、编译器与 R8 配置影响，不能从 DEX 较大断言代码质量差。native 库按 ABI 和压缩状态拆分（AAB 通常只向设备发送匹配 ABI，universal APK 可能含多套 .so），并检查符号剥离、页对齐与重复库。资源与 `resources.arsc` 分开看密度和语言变体。assets 里的离线数据、模型和预置媒体，若走 Play Asset Delivery 或运行时下载，要另记“首装下载”与“首次使用追加下载”。位图改矢量图可能减小文件也可能增加运行时栅格化成本，要结合场景判断。

**Q36: [learning] 阅读 AOSP 源码前要固定哪三个坐标？为什么“我看了 AOSP 最新代码”无法复核？**

三个坐标是：

1. **平台版本：**发布 tag，如材料使用的 `android-17.0.0_r1`。
2. **Git 项目：**如 `platform/frameworks/base` 或 `platform/frameworks/native`。
3. **项目内路径：**如 `core/java/android/view/Choreographer.java`。

AOSP checkout 由 Repo 管理的多个 Git 项目组成，manifest 记录项目、路径和 revision。`main`、`android-latest-release`、发布 tag 和厂商分支早已分叉，只写“最新代码”无法定位到任何一份可复核的源码。

阅读时还要保留一份运行现场：build fingerprint、`ro.build.version.sdk` 与厂商版本、进程线程与时间窗、logcat 或 Perfetto trace、复现条件、源码坐标。这份记录防止两类误判：用新版源码解释旧设备行为，或用 AOSP 行为代替厂商实现——厂商可以修改 Framework、SurfaceFlinger、HAL 和内核，AOSP 只是参照系，设备证据决定现场是否走了同一条路径。源码阅读由具体运行证据驱动（日志、trace 或 Binder 调用先于代码搜索），一次分析的完成条件是证据能解释当前行为并明确仍未确认的边界，不要求预先通读整个 AOSP。

**Q37: [learning] Android Code Search 怎么用才有效？为什么“搜索命中”不等于“执行证据”？**

先固定版本再打开文件：引用时复制“当前 revision + 行号”的链接（行号会随提交漂移，tag 和路径才是长期复核凭证），`HEAD` 链接只用于跟进开发中代码。搜索用官方过滤器：

- `file:` 限定文件；
- `content:` 搜内容（日志反查入口，双引号表示逐字匹配）；
- `function:` 与 `symbol:` 缩小到函数与符号；
- `usage:` 排除注释与字符串；
- `lang:` 限定语言。

快捷键 `x` 打开交叉引用查看定义与引用。

交叉引用只提供候选调用关系，需要人工判断：虚方法调用指向静态类型，运行时对象可能是子类。反射、JNI、Binder、消息分发和回调会中断普通调用层次。宏展开、生成代码、条件编译和产品变体可能改变入口。引用列表表达语法关系，不代表设备运行时一定执行。文件存在也不等于编进产品：`Android.bp`（Soong 模块配置）根据模块、依赖、产品变量和 feature flag 选择源文件，要确认模块编进哪个分区、APK、JAR 或 APEX，以及 AIDL、proto、aconfig、sysprop 等生成代码的来源。遇到公开 API 只剩转发逻辑时，按 import 与 Binder 接口 → 模块的 `Android.bp` → `packages/modules/`（Mainline 模块源码）→ APEX 或 APK 的进程归属继续追。分工是：Code Search 与文本搜索定位候选，构建图确认“编了谁”，trace 与调试确认“跑了谁”。

**Q38: [learning] 只有一条 Logcat 日志（例如 Choreographer 的跳帧警告）时，怎样反查到源码并追到引入版本？**

按六步从设备现场追到源码再返回现场：

1. 保留原始时间戳、priority、tag、pid、tid 和进程名。
2. 去掉数字、地址和对象 ID 等动态字段；例如 `Skipped 42 frames` 中的 42 不是稳定搜索词。
3. 用有区分度的稳定文本执行 `content:"..."` 搜索。
4. 固定源码 tag，查看日志调用周围的条件分支与参数来源。
5. 追调用者，记录 debug flag、系统属性或 build type 是否控制该日志。
6. 返回设备证据，核对进程、线程、版本与触发条件。

以跳帧日志为例：AAOS13 源码核对，`"The application may be doing too much work on its main thread"` 位于 `frameworks/base/core/java/android/view/Choreographer.java`，触发条件是 `skippedFrames >= SKIPPED_FRAME_WARNING_LIMIT`，默认阈值 30、来自系统属性 `debug.choreographer.skipwarning`。这条日志只说明主线程进入 `doFrame()` 时已比预期回调时间晚了若干帧间隔，不能证明耗时发生在某个业务方法，也不能逐条对应 42 个已提交的坏帧。字面量搜不到时依次尝试：tag 常量与日志方法、字符串稳定片段、error code 或 stats atom、调用栈里的类与方法、生成源的输入文件、厂商仓库。

追版本历史用 Git 与 Gerrit：`git log -S'字符串'` 找该字符串出现次数变化的提交，`-G'正则'` 找 diff 行匹配的提交。`git blame` 只显示最近一次逐行修改，格式化或重构会覆盖归属，需要配合 `-S`/`-G` 与父提交阅读。提交消息里的 `Change-Id: I...` 是 Gerrit 评审的稳定标识，可到 AOSP Gerrit 查 patch set、评审意见与关联 bug。

**Q39: [learning] 从 Perfetto slice 反查源码时，为什么要先确认 track 和进程？native 层的 trace 宏名怎么对应 slice 名？**

同名 slice 可以由多个模块产生：`measure` 出现在应用 UI 线程时通常指向 View traversal，出现在工具进程或厂商线程时含义完全不同。搜索源码前先记录 track 名称、进程/线程与 pid/tid、slice 完整名称、起止时间与相邻 slice、启用的数据源与 atrace 类别，以及 args、cookie、flow 等关联信息。

Java 层的对应关系：Framework 内部用 `Trace.traceBegin(tag, name)`，slice 名来自参数字符串。AAOS13 源码核对到 `Choreographer#doFrame " + vsyncId` 的拼接，trace 中应搜前缀 `Choreographer#doFrame`，不要把动态 VSync ID 写进查询。`ViewRootImpl` 的 performMeasure/performLayout/performDraw 生成 `measure`、`layout` 等子 slice。异步事件要用名称与 cookie 配对，不能按相邻关系配对。

native 层按宏的命名规则反查：`ATRACE_CALL()`/`ATRACE_NAME(x)` 的 slice 名分别取当前函数名和参数。版本差异要注意——AAOS13（Android 13）的 SurfaceFlinger 使用通用 `ATRACE_*` 宏（本地核对不存在 `common/include/common/trace.h`），Android 17 材料口径则是 `SFTRACE_*` 宏族，且 `composite()` 的 slice 名形如 `composite <vsyncId>`、`SFTRACE_NAME` 不会自动加类前缀。旧资料里的 `onMessageRefresh()`、`composeSurfaces()` 等函数名对应旧版本，不能直接套到新 trace。另外 atrace 类别只控制 atrace 事件，ftrace 调度事件、heapprofd 等数据源不受类别开关控制，预期 slice 缺失时先识别数据源类型再查配置。

**Q40: [learning] 为什么“Android 17 上发生”不足以描述性能问题？版本、交付路径与 target SDK 要怎样拆开记录？**

同一个 APK 在两个系统版本上可能走进不同的调度、进程管理与运行时路径，性能记录至少要包含四条独立变量：

1. 设备系统版本；
2. `targetSdkVersion`；
3. Mainline 模块版本；
4. 内核版本。

target SDK 决定一部分兼容开关的启用。运行在新系统上的所有应用变更则与 target 无关，例如 Android 14 的缓存进程资源管理与 Android 17 部分设备的应用内存限制（命中时 ApplicationExitInfo 的 description 含 MemoryLimiter:AnonSwap）。

大版本也不是唯一版本轴，交付路径决定覆盖与回滚：framework 走整机 OTA。Mainline 模块走 APEX 独立更新，ART 可单独升级，同大版本不保证 ART 构建相同。内核走 boot/vendor_boot，Android 17 官方兼容矩阵包含多条 GKI 分支（材料注明 android13-5.10 从 QPR1 起不再受支持），看到 Android 17 不能反推内核是 6.18。vendor HAL 走厂商分区。应用编译输入走 APK/DM/Profile。

做法：跨版本实验把 OS、target、Mainline、kernel 与产品配置作为独立变量记录，固定 APK、数据集、温度与电源条件。能用 compat change 或 feature flag 在同一设备做 A/B 时优先成对比较。报告 P50/P95 与样本数，不把官方公开的内部百分比（如 DeliQueue 的 missed frames 下降 4%）当作业务目标。

**Q41: [learning] 同一个 APK 在两台设备上启动、掉帧或后台行为不同，怎样把差异归因到具体层而不是笼统的“ROM 优化”？**

OEM 性能分析要回答三个可验证的问题：当前设备相对 AOSP 基线改了什么、改动通过哪一层影响了目标进程、改善了哪个指标又把成本转移到哪里。启动、流畅性、内存、功耗、温控五类目标共享 CPU、GPU、内存带宽、存储带宽、电池功率与散热能力，局部收益经常伴随另一项成本，比如预热换启动快、峰值提频牺牲稳态帧率。

改动位置按层排查：内核层包括调度、schedutil、cgroup、PSI 与设备驱动，OEM 经内核补丁、Kconfig、设备树或 sysfs 运行参数改变行为，看到非标准节点要先确认它属于 GKI、vendor module 还是产品私有实现。native 层包括 SurfaceFlinger、HWC、AudioFlinger 等，显示路径差异常来自 HWC 能力与合成策略，Binder 延迟要分解为客户端等待、事务排队与服务端执行。framework 层包括 AMS/WMS、CachedAppOptimizer、JobScheduler 与产品 overlay、DeviceConfig、task profile。应用层则是 SDK 行为与厂商公开 SDK。

归因流程：固定实验条件（机型、内存档位、build fingerprint、温度、刷新率）→ 建立 AOSP 基线并记录默认值与可覆盖项 → 读取目标设备配置（Settings、DeviceConfig、overlay、task profiles，user build 读不到的记为未知）→ 用 trace 确认执行动作（启动链、掉帧链、后台链、功耗链）→ 逐项做单变量消融 → 给结论标注证据等级：L1 源码与运行证据一致、L2 官方文档或产品配置加运行证据、L3 仅有现象。品牌和地区不能替代设备证据，“某厂商一定杀后台”会把排障变成长期维护的例外集合。

**Q42: [learning] 用 PerfDog 做功耗回归时为什么必须在 Wi-Fi 模式下拔掉 USB 采集 Battery Power？发现帧率退化后怎样组合证据判断是热降频？**

USB 连接线会给设备充电，USB 下采集的电流或功耗曲线混入充电电流，不能与断线后的结果放进同一组基线。PerfDog 官方把 Android Battery Power 明确定义在 Wi-Fi 模式下采集——先用 USB 建立 Wi-Fi 连接，成功后拔线再开始测试，报告必须写明连接方式。

Battery Power 是整机口径，屏幕、基带、Wi-Fi、后台进程和系统服务都包含在内，不是目标 App 的独占功耗，对比时要固定亮度、音量、网络和后台状态。`FPower` 的口径是 Power 除以 FPS、单位仍是 mW，只用于相近场景和帧率下的归一化比较。FPS 接近 0、场景静止或两组帧率差距很大时失去解释力，它也不是物理意义上的单帧能量。

热降频的判断需要同一时间轴上的证据组合：

1. 温度持续上升；
2. CPU/GPU 频率出现台阶式下降；
3. FTime P95/P99、1% Low 或 Stutter 同步恶化；
4. 相同操作标签处没有能单独解释退化的负载新峰值。

单个温度值不等于系统已经限频，报告应写起止温度与系统 thermal status 而不是只贴峰值。平台侧可用 `adb shell dumpsys thermalservice` 与 `dumpsys powerstats` 交叉核验“系统侧是否有数据、变化时间是否一致”（服务名 `thermalservice` 按 AAOS13 源码核对）。注意版本差异——AAOS13 的 `ThermalManagerService` 位于 `services/core/java/com/android/server/power/` 下并按 HIDL 2.0、1.1、1.0 顺序连接 HAL，材料按 Android 17 核对的实现移到 `power/thermal/` 子包且优先尝试 AIDL。精确能耗实验应使用外置功耗仪，PerfDog 更适合相对变化监控。

**Q43: [learning] Geekbench、3DMark、安兔兔、PCMark 的分数在什么条件下才能放进同一组比较？“多核分数高所以多开线程一定快”错在哪里？**

分数只有在 Benchmark 应用版本、应用内测试项目版本、被测系统与驱动版本三个版本号一致，且温度、刷新率、电量等运行条件相同时才可比。大版本变更都改动了测试项或规则：Geekbench 6 到 7、安兔兔 V10 到 V11、PCMark Work 2.0 到 3.0 均不可比，旧序列应冻结在独立字段，厂商没有发布换算公式时不做经验换算。

多核分数的误读来自把代理变量当直接测量：Geekbench 分数由固定子测试按参考系统归一化后加权而来，表示“这套工作负载中的性能”，不表示任意 App 代码同比例变快。多核分数采用 shared-task 模型并只纳入现实任务适合并行的套件，回答不了“开八个线程快多少”——要先检查任务依赖、可并行比例、线程池拥塞与内存带宽，主线程上的串行关键路径不会因多核总分高而自动缩短。同理，Geekbench Compute 跑 Vulkan/OpenCL 计算负载，不覆盖着色、光栅化、合成与显示刷新，分析渲染问题要看 3DMark 图形测试、FrameTimeline 和 App 自身帧数据。3DMark Stress 的 stability（最低轮与最高轮平均帧率之比）必须连同每轮曲线解释：98% 可能来自“每轮都不快”，很高的首轮帧率配 65% 说明峰值强但持续性能弱。

做法上把工具语义写进字段名（`gb7_vulkan_compute`、`3dmark_wild_life_stability` 而不是笼统的 `gpu_score`），结论只引用相关分项加 App 证据。综合分可留在摘要，但“安兔兔总分高所以 SQLite 快”这类推论越过了证据边界。

**Q44: [learning] 做机型分层时为什么应保存分项能力向量而不是一个综合档位？Media Performance Class 能当作档位标准吗？**

综合分把不同资源结构压成一个数，会隐藏 App 依赖的那一维：总分相近的两台设备可能一台 CPU 强存储弱、另一台相反，图片解码重的 App 与冷启动读小文件多的 App 在两台设备上排序相反。分层应保存相互独立的能力向量——CPU 单核与多核、GPU graphics 与 compute、存储、RAM、热稳定性——各维度从对应的 Benchmark 子项或线上采集取值。四档（入门、中端、高端、旗舰）的边界从团队已冻结版本的设备样本分布中计算（例如按关键维度的 P20、P50、P80 切分），换 Benchmark 大版本或设备覆盖集后要重新计算。

Media Performance Class 不能当档位标准：它用 CDD 规定媒体、相机、内存、显示等能力下限并由 CTS 验证，只表达“能力下限标签”，表达不了 CPU 排名、启动 P95 或列表慢帧率。AAOS13 源码核对 `Build.VERSION.MEDIA_PERFORMANCE_CLASS` 从设备属性读取声明值、未声明返回 0。官方公开定义的等级是 30、31、33、34、35（没有 32），设备升级系统后仍报告原先满足的等级，不要自行创造"MPC 37"，也不要把 Android 版本号当作设备必然报告的等级。

工程连接方式是版本化设备字典：客户端只上报设备与系统标识，分析平台在服务端关联能力字段，事件里保留 `capability_schema` 一类的规则版本让历史数据可还原。分档用于灰度覆盖、性能预算和告警分组，Benchmark 版本变化只触发字典重算，不直接触发线上性能告警。
