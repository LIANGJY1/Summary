# OEM 与设备差异

> 学习资料（文章模式沉淀）。主线：OEM 在调度、内存、功耗、温控与设备能力分级上的实现空间，以及应用如何用公开 API 与可复现证据和厂商策略协作——从 OEM 归因流程、SoC 平台差异、游戏模式与输入优先级，到 Power HAL 与 Power Stats、Media Performance Class、Private Space 边界，再到 Android Auto 与 Android Automotive OS 性能。源文档：android-internals-wiki 第 19 章《OEM 与设备差异》§19.1–§19.7；可本地核对的机制按 AAOS13 源码（Android 13）核对并标注版本差异（freezer debounce 默认值、lmkd oom_adj 协议、GAME_LOADING 传递、CarPropertyManager registerCallback、Private Space 不在本地树等），厂商闭源实现（Power HAL、SoC 私有服务）不做事源码级断言、内核 6.18 专属内容按材料口径转写、不确定处已弱化；Private Space 为 Android 15+、Android 17 CDD 新增 MPC 等级、AAOS App Lock 为车载特权组件等版本敏感结论按材料已核对官方文档的口径标注。调度、DVFS 与温控的机制层见 [../cpu-power/01-调度与功耗框架.md](../08-cpu-power/01-调度与功耗框架.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 同一个 APK 在两台设备上启动、掉帧或后台行为不同，怎样把差异归因到具体层而不是笼统的"ROM 优化"？**

OEM 性能分析要回答三个可验证的问题：当前设备相对 AOSP 基线改了什么、改动通过哪一层影响了目标进程、改善了哪个指标又把成本转移到哪里。启动、流畅性、内存、功耗、温控五类目标共享 CPU、GPU、内存带宽、存储带宽、电池功率与散热能力，局部收益经常伴随另一项成本，比如预热换启动快、峰值提频牺牲稳态帧率。

改动位置按层排查：内核层包括调度、schedutil、cgroup、PSI 与设备驱动，OEM 经内核补丁、Kconfig、设备树或 sysfs 运行参数改变行为，看到非标准节点要先确认它属于 GKI、vendor module 还是产品私有实现；native 层包括 SurfaceFlinger、HWC、AudioFlinger 等，显示路径差异常来自 HWC 能力与合成策略，Binder 延迟要分解为客户端等待、事务排队与服务端执行；framework 层包括 AMS/WMS、CachedAppOptimizer、JobScheduler 与产品 overlay、DeviceConfig、task profile；应用层则是 SDK 行为与厂商公开 SDK。

归因流程：固定实验条件（机型、内存档位、build fingerprint、温度、刷新率）→ 建立 AOSP 基线并记录默认值与可覆盖项 → 读取目标设备配置（Settings、DeviceConfig、overlay、task profiles，user build 读不到的记为未知）→ 用 trace 确认执行动作（启动链、掉帧链、后台链、功耗链）→ 逐项做单变量消融 → 给结论标注证据等级：L1 源码与运行证据一致、L2 官方文档或产品配置加运行证据、L3 仅有现象。品牌和地区不能替代设备证据，"某厂商一定杀后台"会把排障变成长期维护的例外集合。

**Q2: Cached Apps Freezer 在系统里怎么实现？"冻结"与 SIGSTOP 差在哪？怎样确认设备真的发生了冻结？**

AOSP freezer 是 framework 协调的 cgroup v2 冻结。按 AAOS13 源码核对（CachedAppOptimizer.java），进程具备冻结资格后先冻结 Binder（freezeBinder）并确认没有待处理事务，再经 Process.setProcessFrozen(pid, uid, true) 给目标 pid 应用 Frozen task profile（把该 pid cgroup 的 FreezerState 写 1），解冻顺序相反；若冻结期间收到同步事务，系统会终止目标进程避免调用方无限等待——先停 Binder 再停进程的协调顺序是它与 SIGSTOP 的主要工程差别。SIGSTOP 无法被捕获或忽略、会暂停整个线程组，但不通知 Android 组件状态，进程可能停在持锁或 IPC 临界区，依赖方会长时间等待，不适合第三方应用当后台治理接口。

配置上按 AAOS13 核对：DEFAULT_USE_FREEZER 为 true，debounce 走 DeviceConfig 的 activity_manager_native_boot.freezer_debounce_timeout，源码默认 DEFAULT_FREEZER_DEBOUNCE_TIMEOUT 为 600000 ms（10 分钟）；Android 17 材料口径是 config_defaultFreezerDebounceTimeout 为 10000 ms（10 秒）——debounce 默认值是版本敏感项，排查当前设备以 dumpsys 输出为准。确认手段：device_config get activity_manager_native_boot use_freezer（返回 null 不代表关闭，会回退源码默认）、settings get global cached_apps_freezer、dumpsys activity 的 Freezer settings 段（含 Apps frozen 计数）、logcat -b events -s am_freeze am_unfreeze；user build 读不到 PID 级 cgroup 文件只说明权限或路径不匹配，不能判定 freezer 关闭。

边界：冻结只停止执行、不省内存，匿名页是否被压缩换出取决于后续 compaction 与 ZRAM，冻结的进程仍占用页表与内核对象；Android 14 起应用全部进程 frozen 时系统还会终止其活动 TCP socket（材料口径），冻结不能当网络保活手段。

**Q3: USAP 池与"厂商预启动"是什么关系？为什么 trace 里没看到 fork 不能证明系统预启动了应用？**

USAP（Unspecialized App Process）池只负责提前创建进程：池成员是主/次 Zygote 预先 fork、尚未绑定应用身份的进程，启动请求满足条件时经 specializeAppProcess 绑定 UID/GID、SELinux 标签与数据目录。按 AAOS13 源码核对，ZygoteProcess.shouldAttemptUsapLaunch() 要求四项同时成立：mUsapPoolSupported、mUsapPoolEnabled、策略指定 USAP 启动、命令受 USAP 支持；mUsapPoolEnabled 默认为 false，策略只放行延迟敏感、非 system process 的请求，需要 wrapper 进程、child Zygote 或预加载包的命令退回普通 Zygote 路径，child Zygote 不支持 USAP。

没看到 fork 的解释至少有四种：目标进程早已存在（cached）、来自 USAP 专门化、来自 App Zygote，或 trace 窗口漏掉了进程创建。判断要核对 PID 创建时间、父进程、bindApplication 与 USAP 状态；trace 中的 Zygote:FillUsapPool 能证明填池动作，可用 dalvik.vm.usap_pool_enabled 属性与 runtime_native DeviceConfig 区分"源码支持"和"当前已启用"。

"智能预测启动"描述的是决策输入，命中后厂商可能做的动作差异很大：提前 ART 编译或 profile 维护、预取文件页、保留 cached 进程、填 USAP 池、创建私有预热进程或调整短时调度 I/O 优先级。验证要设计命中组与未命中组，固定网络、温度、编译状态与页缓存条件；只有产品文档、系统日志或调用链能说明策略来源，trace 负责证明动作与效果，不能凭"点击后很快"断定系统预创建了进程。

**Q4: 后台任务应该选哪个系统 API？为什么"加白名单"或"全用前台服务"不是万能解？行业案例的百分比能直接复用吗？**

按工作语义选 API：离开页面即可取消的进程内任务用协程或 executor；跨重启可延期的持久任务用 WorkManager（受约束与配额影响，不承诺精确时刻）；平台级调度控制用 JobScheduler（正确声明网络、充电、空闲条件）；用户指定时刻的提醒用 AlarmManager（精确闹钟受权限与政策约束）；用户持续可感知的工作才用前台服务；消息送达用平台推送通道。FGS 自身受版本约束：Android 12 起 target 31 及以上从后台启动 FGS 受限，Android 14 起 target 34 及以上必须声明服务类型并通过权限检查——它不能当"最稳后台"的万能替代。

白名单只改变特定电源限制，修复不了错误的 WorkManager 约束、FGS 类型、权限、服务端推送或网络失败；普通应用也不应依赖进程互相唤醒、静音音频或周期自唤醒绕过策略。案例百分比只能留在原案例的分母里：TikTok 公开报告的启动时间减少 45% 等属于该项目当时的版本、设备分布与指标口径，公开文章没有给出可复算的原始数据；可直接复用的是工程动作——按 Jetpack App Startup 按需初始化、用 Layout Inspector 简化 View 层级并把集中在一帧的工作分散、媒体播放器按 codec 复用与下一条预加载预渲染、持续用 Perfetto 与线上指标防回归。OEM 侧的 SceneSDK 类合作是双向的：应用提供场景与目标帧率、设备反馈温控与频率约束，用帧时间、功耗与热稳态验证；这类私有 SDK 不属于公共 API，不能假设所有机型可调用。

**Q5: SoC 型号能预测流畅度吗？跨 SoC 分析要把哪三层证据分开，CPU 分析的四个概念怎样区分？**

不能——SoC 型号只给出硬件拓扑起点，峰值算力不会自动转成帧稳定性，散热、内存配置、内核与厂商策略都会改变结果。三层证据要分开：IP 与 SoC 规格（厂商公开的 CPU/GPU/NPU/ISP 能力，回答硬件提供了什么）、整机实现（OEM 选的内存颗粒、频率表、散热方案、内核与驱动，决定能力如何工作）、当前负载（温度、电量模式、刷新率与并发，决定一次 trace 捕获到什么）。三者不能互相替代：产品页证明不了持续频率，单次 Perfetto 也证明不了架构上限。

CPU 的四个概念：指令集描述软件可用指令（如 Armv9.x）；微架构描述核心如何取指、乱序执行与访问缓存（如 Oryon、C1-Pro）；拓扑描述核心数量、共享缓存与 cpufreq policy 的组织；策略描述调度器、调频器、Power HAL 与厂商服务如何使用硬件。两个核心同为 Armv9 不代表 IPC 与能耗相同；两个核心在同一 policy 也不代表共享缓存。实用上先读设备拓扑（/sys/devices/system/cpu/possible、各 policy 的 related_cpus/scaling_driver/scaling_governor、GPU 驱动与内核版本），部分 user build 会隐藏节点，缺项记为"设备未公开"，不要凭 SoC 名称补值。

**Q6: 厂商内核 hook 与 EAS 是什么关系？一段"多核同时升频"的 trace 怎样查证来源？**

材料按 Android 17 内核（android17-6.18）核对：公平调度类用 EEVDF，EAS 在 root domain 未进入 overutilized 时经 find_energy_efficient_cpu() 选核，而 select_task_rq_fair() 会先执行 Android vendor hook（android_rvh_select_task_rq_fair），hook 给出非负 CPU 时直接采用——厂商模块可以在选核路径注入产品策略，但 hook 本身不给出策略内容，确认注册了什么实现需要厂商模块源码、符号、trace 或设备实验。schedutil 依据利用率请求频率，输入包括 PELT 利用率、uclamp、iowait boost、thermal pressure，以及 sched_ext 启用时的性能目标； Energy Model 不记录任务迁移造成的私有缓存局部性损失，"EAS 选了这个核"不能解读为内核已精确计算缓存迁移成本。

一段升频 trace 的查证顺序：用 sched 与 thread_state 判断线程是运行、可运行还是阻塞；对齐 CPU 频率、空闲状态、温控状态与电源模式；检查关键线程所属 cgroup、task profile 与 uclamp；在 userdebug 或厂商调试环境追踪 Power HAL 性能提示与厂商事件；用固定脚本重复冷机、热机与不同电源模式。RPMh、DCVS、Perflock 这类品牌词不能互换使用，无厂商标记时结论停在"观测到频率与负载不成比例"，不给私有机制命名——线程并发、共享 policy、温控恢复与 HAL 提示都可能形成相似曲线。

**Q7: Adreno、Mali/Xclipse 的架构名称与厂商 NPU 百分比能说明什么？GPU 或内存数据缺失时怎么避免误判？**

架构名称只给出排查方向：Adreno 的 sliced architecture 与 FlexRender 是 Qualcomm 对自家 GPU 的描述，tile-based rendering 不能概括所有 Adreno 负载；Mali/Immortalis 是可配置 IP，同一代 IP 可以有 10–24 个着色器核心等不同规模，看到 Mali-G1 Ultra 仍要确认具体核心数与驱动；Xclipse 960 的 RDNA 代际官方未在产品页给出，要读实机 Vulkan/OpenGL ES 与驱动信息。API 支持由整机驱动决定：产品页列的是 IP 上限，应用能用哪些 Vulkan/OpenGL ES 扩展取决于量产驱动的 feature level。厂商 NPU 百分比用各自的模型、精度、功耗模式与上一代基线，缺少共同分母不能相加排序；端侧推理要看算子与动态形状是否被加速、计算图切成多少子图、哪些算子回退 CPU/GPU（NNAPI NDK 从 Android 15 起弃用，新方案选受支持的运行时与 delegate）。

数据缺失要防两类误判。GPU：Perfetto 的 gpu.renderstages、gpu.counters 数据依赖厂商 producer 注册，空轨道不能证明 GPU 空闲，采集前应查询设备公布的数据源并精确匹配名称，gpu_mem_total 是内存分配量不是带宽。内存："支持 LPDDR5X"只是控制器能力范围，5300 与 10667 未注明单位时不能直接比较，应用拿到的是受内存控制器、缓存与互连调度的有效带宽；Perfetto 没有所有设备通用的 DRAM bandwidth 轨道，没有计数器时用受控扰动实验（保持 GPU 场景不变、逐档增加 CPU 内存流量并观察帧时间与功耗）才能接近因果判断，时间相关性只能提出假设。

**Q8: sched_ext 是什么？确认一台设备"厂商 BPF 调度器在运行"要看哪三层？full 与 partial 模式差在哪？**

sched_ext 是 Linux 的可扩展调度类，允许 eBPF 程序经 struct_ops 提供普通任务的调度策略；sched_ext 从 Linux 6.12 进入主线，材料按 Android 17 arm64 GKI 核对 CONFIG_SCHED_CLASS_EXT=y（本地 AAOS13 树无内核源码，内核结论按材料口径转写）。三层确认缺一不可：编译能力（内核配置含 CONFIG_SCHED_CLASS_EXT）；运行状态（/sys/kernel/sched_ext/state 为 enabled、root/ops 显示当前调度器名，enable_seq 大于 0 只证明本次开机曾成功启用过）；任务范围（full 还是 partial，目标线程是否在接管范围）。配置只说明编译了框架，BPF 调度器未加载时任务仍走 fair 类。

full 模式（未设 SCX_OPS_SWITCH_PARTIAL）接管 SCHED_NORMAL/BATCH/IDLE 与 SCHED_EXT 任务，此时普通应用线程的调度策略字段仍可能显示 SCHED_NORMAL，所以在 /proc/<pid>/sched 里搜 ext 判断归属不可靠；partial 模式只接管显式设为 SCHED_EXT 的任务。BPF 回调多有默认行为：select_cpu() 只是优化提示且可省略、enqueue() 默认进 global DSQ、dispatch() 仅在 local 与 global DSQ 为空时调用；CPU 只执行自己 local DSQ 中的任务，自定义 DSQ 可按 FIFO 或虚拟时间排序。安全边界是自动回退：BPF 错误、runnable task stall 或 SysRq-S 会终止调度器并把任务交回 fair 类。Android 17 的 schedutil 已接入 SCX 性能目标值，full 模式下 BPF 策略要同步提供合理的性能目标，否则频率响应与调度意图脱节；第三方公开的 hmbird_sched proc 节点只能当线索，不能替代厂商源码与运行时证据。

```bash
adb shell 'cat /sys/kernel/sched_ext/state 2>/dev/null'
adb shell 'cat /sys/kernel/sched_ext/root/ops 2>/dev/null'
adb shell 'cat /sys/kernel/sched_ext/enable_seq 2>/dev/null'
```

**Q9: MUSCHED 的 VIP 是什么机制？它证明了什么，又有哪些不能外推成 Android 17 默认行为？**

MUSCHED 是荣耀面向移动交互负载的语义感知调度框架（2024 年 1 月量产、2026 年 OSDI 论文），核心是给交互关键任务打临时 VIP 标签：用户空间按启动、滑动、动画等场景与线程角色更新 BPF Map 中的 VIP 候选与生存时间，内核侧维护每 CPU 的 FIFO VIP 队列——单次时间片 3 ms，Audio/Video/WebView/Display 的累计预算分别为 20/10/120/20 ms，预算耗尽撤销 VIP，有效顺序为 RT > VIP > CFS。它还处理依赖传播：VIP 等待者被普通线程持锁阻塞时，把 VIP 标签临时传给锁持有者，锁释放或超过生存时间后撤销；Binder 同步事务上传播 VIP，并周期性把等待超过 4 ms 的 VIP 推向空闲 CPU。

它证明了语义标注、有界预算与依赖传播可在量产组合：论文实验室数据（Magic7、Android 15、Linux 6.6）报告 10 个应用各 100 次冷启动平均降 14.8%、VIP 任务睡眠时间降 71.8%；自 2024 年起的量产统计报告动画、滑动与启动异常次数下降。但不能外推为 Android 17 默认能力：VIP 不是上游新增的 sched_class，Android 17 没有 SCHED_VIP；论文未公开 full/partial 接管模式、私有 kfunc、引用计数与环检测细节；Binder 默认路径不会自动复制 VIP 标签，oneway、嵌套调用与线程池复用要单独验证；3 ms 时间片与 4 ms 阈值是该样机与 120 Hz 场景的调优点。对应用团队的可行路径是公开机制：减少关键窗口的 runnable 竞争与持锁、用 ADPF 与 Game State 表达负载，而不是寻找不存在的 VIP SDK。

**Q10: AOSP 的 Game Mode 给了系统什么信号、没给什么？"游戏事件在 InputDispatcher 有专属优先级"为什么不成立？**

Game Mode 提供的是模式与场景信号，不是输入优先级。用户可选 Standard/Performance/Battery（Android 14 加 Custom），游戏在每次 onResume 重新读取 getGameMode()（可能返回 GAME_MODE_UNSUPPORTED）自行调整画质帧率；厂商干预（backbuffer resize、FPS throttling、ANGLE）面向未适配游戏，不定义输入事件顺序，游戏声明 supportsBatteryGameMode/supportsPerformanceGameMode 后平台会清除此前对该模式施加的干预。GAME_LOADING（Android 13 引入）与 GAME（Android 14 引入）是发给 Power HAL 的电源模式：按 AAOS13 源码核对，GameManagerService 只在 GAME_MODE_PERFORMANCE 时把 isLoading 经 Mode.GAME_LOADING 传给 PowerManager 并带超时限制；Mode.GAME 为 Android 14 引入、本地树未见，A17 口径由"TOP 进程全部是游戏"触发。Power HAL 收到信号后可以提高 CPU 频率或游戏线程调度优先级，但这是性能温控策略，不改窗口路由。

"游戏事件有专属优先级"不成立的直接证据：按 AAOS13 源码核对，本地 frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp 对 GameManager/GAME_MODE 的引用为 0。触摸目标由逻辑显示屏、坐标命中测试、窗口可接收性与触摸序列状态决定，从窗口栈前端向后遍历选首个能接收触点且非 spy window 的窗口，游戏模式不参与该函数；InputTarget.Flags.FOREGROUND 只表示该目标是本次事件的主要前台目标，不表示性能优先级；策略回调 interceptKey/MotionBeforeQueueing 也不接收游戏模式参数。厂商"触控增强"可能改触控采样、驱动、调度、刷新率或游戏工具任意一层，判断某设备是否在 inputflinger 重排事件需要厂商源码差异或跟踪数据。

**Q11: 触控采样率 1000 Hz、高刷新率、异步注入，各自能改善"跟手"吗？应该怎么测量？**

逐项看边界。1000 Hz 采样只说明理想采样间隔约 1 ms，驱动批处理、事件预测、游戏逻辑更新、GPU 队列与显示扫描仍会增加总延迟，不能换算成触摸到显示时间。刷新率影响显示侧等待：60 Hz 到 120 Hz 把刷新间隔从约 16.67 ms 降到约 8.33 ms，游戏能按时出帧时输入到可见更新的等待缩短，但应用或 GPU 已积压时提高刷新率无法让 MotionEvent 更早送达；RefreshRatePolicy 计算窗口的帧率选择优先级（获焦点且设 preferredDisplayModeId 的窗口最高），SurfaceFlinger 结合各图层投票与屏幕能力决定最终刷新率，单窗口请求不保证生效；Game Mode 的 FPS throttling 只能限速、不能把 60 FPS 提到 120。异步注入（INJECT_INPUT_EVENT_MODE_ASYNC）只改变调用方等待语义：事件入队后立即返回成功、不等待目标解析与应用处理，仍要经过窗口选择与输入通道，且需要系统级 INJECT_EVENTS 权限，不是"零排队"。View 层 requestDisallowInterceptTouchEvent(true) 只影响应用内部父 ViewGroup 的 onInterceptTouchEvent 拦截（新一轮 ACTION_DOWN 前会被重置），改不了 InputDispatcher 的目标选择。

测量上用 Perfetto 的 android.input 数据源（详细跟踪仅 userdebug/eng）与 Trace Processor 的 android_input_events 表分段：read_time、dispatch_latency、handling_latency、ack_latency、total_latency、end_to_end_latency（输入到关联画面呈现），量产 user 构建退化为 FrameTimeline、电源模式与自定义标记对照。报告中位数与 P90/P95/P99 而非单次点击；自动化注入会改变输入源，要在报告标记。

**Q12: Power HAL 的 Mode/Boost 与 Hint Session 是两条什么路径？应用能直接调用 setMode 吗？**

Power HAL 是 framework 与厂商电源实现之间的 AIDL 接口（材料按 Android 17 冻结的 power AIDL v7 核对；本地 AAOS13 树的 hardware/interfaces 只包含 automotive，power AIDL 未包含于本地树，接口版本按材料口径转写）。两条入口职责不同：Mode/Boost 由系统场景或特权调用触发，PowerManagerService 把交互、亮灭屏、省电、游戏等状态经 setMode/setBoost 交给厂商实现（A17 口径 Mode 19 个、Boost 6 个），setMode 是 oneway 调用、调用方不等待结果，Binder 对外方法要求 DEVICE_POWER 权限，省电模式还会过滤 LAUNCH 启动加速；Hint Session 由应用的 PerformanceHintManager 请求进入 HintManagerService，校验 UID/TGID/TID 与进程状态后交给 IPowerHintSession，线程组持续报告目标时长与实际时长，v5 起可用可选 FMQ 通道减少高频报告的 Binder 往返。

应用不能直接调 setMode/setBoost——那是 framework 到厂商 HAL 的接口，不是 SDK；setPowerSaveModeEnabled() 是需要 DEVICE_POWER 或 POWER_SAVER 权限的隐藏 @SystemApi。厂商收到提示后可以调 CPU/GPU/内存总线或空闲策略，也可以按本机策略忽略：接口枚举存在不代表设备实现了对应动作，要用 isModeSupported()/isBoostSupported() 查询；"LAUNCH 一定触发某厂商硬件请求通道"这类固定映射需要目标设备 HAL 源码、厂商 trace 标记或寄存器级证据才能成立。AOSP 也没有定义 Power HAL 直接调用内核调度器辅助函数的通用路径。

**Q13: schedutil 怎么决定 CPU 频率？"利用率 × 最高频率"错在哪？频率上不去时应检查什么？**

schedutil 用调度器利用率信号合成频率需求，输入远不止 fair 类利用率：PELT 的 util_avg 与短期估计 util_est、uclamp 上下界、iowait boost、RT/DL/IRQ 对可用 capacity 的占用、thermal pressure，以及 sched_ext 启用时的性能目标——full 接管时只按 SCX 目标起算、partial 时 SCX 目标加 fair 利用率；合成需求加约 1.25 倍余量并按 uclamp 限幅，再由驱动解析到 policy 支持的频点。"百分比 × 最高频率"漏掉了全部输入，还把估算需求误当成物理性能曲线。

细节边界：一个 policy 覆盖多个 CPU 时取处理后的最大利用率，给某 CPU 写 target 不代表硬件只改变这个 CPU；频率更新受 freq_update_delay_ns 速率限制（初值来自 cpufreq_policy_transition_delay_us），没有跨设备统一的固定毫秒值，支持 fast switch 时直接更新否则由工作线程延后。频率上不去的排查顺序：确认 Mode/Boost/Hint 的发起方、权限与支持结果；对齐 runnable 任务、SCX 状态与 switch_all；检查更新是否被速率限制或目标未变拦截；查 policy 频率上下限、温控约束与驱动频率表；最后把驱动请求、实际频率、任务完成时间、帧性能与温度放到同一时间轴。机制细节见调度与功耗框架篇，本篇强调 OEM 视角：schedutil 是否为量产配置、policy 划分、OPP 表与热限频都因设备而异，通用内核源码不足以还原产品配置。

**Q14: PerformanceHintManager 的 Hint Session 怎么用？thermal headroom 的数值怎么读才不会误判？**

Hint Session 表达周期性工作目标：用属于本进程的长期存活线程组创建 session，给出目标时长（从该线程组负责的一轮工作预算出发，不要机械照搬整帧 16.67 ms），每周期调用 reportActualWorkDuration()，目标变化时 updateTargetWorkDuration()，结束调用 close()；createHintSession() 可能返回 null（设备不支持或线程不属于当前进程），API 34 起线程集合变化可用 setThreads()。它传递的是工作目标，不承诺绑核、固定频率或避免温控降频——资源是否响应要靠设备数据确认；计时用与 uptimeNanos 一致的时钟基准，GPU 完成时间不能混进 CPU 工作时长。评估做只改会话开关的 A/B，观察线程 running/runnable/sleeping、频率、FrameTimeline 与功耗。

thermal headroom 是归一化热压力指标：PowerManager.getThermalHeadroom(forecastSeconds)（API 30 起，参数 0–60 秒）返回值越高表示越接近严重温控阈值，1.0 对应 THERMAL_STATUS_SEVERE、可以大于 1.0，不是摄氏度；设备不支持或调用过快返回 NaN，慢变化传感器约一秒更新一次，更高频轮询没有收益。API 35 的 getThermalHeadroomThresholds() 读取设备为各温控状态定义的阈值映射，API 36 起可能运行时变化并可用 addThermalHeadroomListener() 监听；跨机比较原始 headroom 数字要保留传感器与阈值模型差异。CPU/GPU headroom（SystemHealthManager，API 36 起）是另一套：结果范围 0–100、至少一次同步 Binder 事务可能超过 1 ms，不要在渲染或音频线程等待，最小查询间隔与计算窗口由设备报告。应用纪律：信号只用于渐进调整负载并加滞回与最短保持时间，不写 sysfs、不绑核、不依赖厂商私有性能服务。

**Q15: PowerStats HAL 报告哪三组数据？为什么"dumpsys powerstats 能列出对象"不等于设备能做应用耗电归因？**

PowerStats AIDL（材料按 Android 17 冻结 v2 核对）的三组数据：状态驻留（PowerEntity/StateResidencyResult，各状态自开机的累计时长、进入次数与最近进入时间）、组件能量（EnergyConsumer/Result，累计能量，可选 EnergyConsumerAttribution[] 做 UID 分摊，分摊总和不得超过组件累计能量）、计量通道（Channel/EnergyMeasurement，常对应一条电源 rail，能量单位 µW·s、时间戳用 CLOCK_BOOTTIME）。接口只约定格式，覆盖范围由设备决定：AOSP 默认实现注册的是虚构 rail 与 consumer、仅供联调；厂商只接少量 rail 时 dumpsys 仍会列出对象但覆盖有限；没提供 attribution 数组时该组件就没有 UID 分摊；framework 连接 AIDL v2 失败会回退 HIDL 1.0，后者不提供 EnergyConsumer。

公开 Power Monitor API（API 35 起）更受限：只公开 Channel/EnergyConsumer 累计值，不公开状态驻留与 UID 分摊；无 ACCESS_FINE_POWER_MONITORS（signature|privileged|development 级）时缓存最长 20 秒且 granularity 为 UNSPECIFIED，有权限时 250 毫秒、FINE；公开读数还按调用 UID 混入随机扰动，monitor index 重启后不保证稳定。评估设备能力可按 L0–L4 分级（无可用数据/仅计量通道/组件能量/UID 分摊/测量链经外部仪器验证），跨设备比较先保存对象清单、用累计值差分、统一时间轴并把负差值与回绕区分开——把 L1 与 L3 设备混进同一张应用耗电榜，会把"没有 UID 数据"误读成"应用耗电较低"。测量前还要分清功率（W）、能量（J/Wh）与电池电流（mA）三个物理量，比较"完成同一任务谁省电"优先用任务总能量并给出耗时与置信区间。

```bash
adb shell dumpsys powerstats
adb exec-out dumpsys powerstats --proto meter > powerstats-meter.pb
```

**Q16: Media Performance Class 是什么？Android 17 新增的等级为什么打破了"非零值等于 API 级别"的旧假设？**

MPC（Media Performance Class）是兼容性规范（CDD）定义的设备能力下限集合，用一个整数关联编解码器、相机、音频、显示、内存、存储与图形要求，应用运行时读取它选择初始体验档位；它不是通用跑分，也不能替代单项能力查询——声明了高等级的设备仍可能因温度、后台负载或厂商策略波动。旧等级值曾与 API 级别对齐（30/31/33/34/35 对应 Android 11–15），Android 17 CDD 2.2.7 新增 1、10、20 三个低等级与最高等级 37（32、36 未定义），非零值不再都等于某个 VERSION_CODES。

0 的语义是"当前读取路径没有可用声明"：可能是设备未声明、旧系统没有公开字段、Jetpack 或 Play services 的补充读取未返回，不能证明设备低端或某项能力缺失，业务应为 0 选择保守默认值再用运行时 API 逐项开启。MPC 与平台版本分离：OTA 后设备可保留原 MPC，SDK_INT 回答 API 是否存在、MPC 回答能力下限声明、运行时能力查询回答当前设备是否支持某功能、实测数据回答当前负载能否达标——四类信号不能互换。读取字段 Build.VERSION.MEDIA_PERFORMANCE_CLASS 自 Android 12 公开（AAOS13 树的 Build.java 已存在该字段），同一次开机内稳定、OTA 后可能提高。版本边界：Android 17 的新等级按材料与官方 CDD 口径转写；AAOS13（CDD 13）语境下合法值仍是 30/31/33。

**Q17: 用 Jetpack DevicePerformance 读 MPC 有什么兼容性坑？业务分级为什么不能用 mpc >= 34 这类比较？**

两个坑。其一，PlayServicesDevicePerformance 先从 DataStore 读取 Play services 上次结果与平台默认读取值取较大者，而 mediaPerformanceClass 是 lazy 初始化——第一次访问发生在异步更新完成之前就会一直使用旧本地结果，官方建议在 Application.onCreate() 中只创建一次对象，新结果通常供后续进程使用。其二，材料核对时点的 androidx-main 中，DefaultDevicePerformance.isPerformanceClassValid() 仍要求值至少为 Build.VERSION_CODES.R（30），会把 CDD 17 的 1/10/20 视为无效并退回 0——只依赖平台默认读取路径的代码会丢失新低等级；Play services 已把低等级写入 DataStore 时取 max 仍能保留。接入前要对项目锁定的 Jetpack 版本做单元测试，并同时上报平台原始值 raw_build_mpc 与库最终值 resolved_mpc。

业务分级不能用 >= 比较，因为等级集合和条款不是单调的：有些等级提高测试负载后仍使用相同计数阈值，测试口径（分辨率、帧率、并发）也会改变。更稳的做法是为每项功能维护 CDD 明确列出的等级集合——如 HDR 显示候选 {34, 35, 37}、后置 RAW 候选 {31, 33, 34, 35, 37}（31 才要求 RAW）、JPEG_R 候选 {35, 37}——未识别值进入 Unknown 不自动套用相邻等级，且每项仍要保留运行时能力查询作为必要条件。产品侧的 MediaTier 分组不能反向解释为 CDD 正式名称。

**Q18: MPC 与运行时能力查询怎样组合决策？值为 0 或低等级的设备怎么设计体验？**

决策顺序固定五步：用 SDK_INT 判断 API 是否存在；用经过显式识别的 MPC 选择保守、标准或高规格候选；用运行时能力查询剔除设备不支持的项（多路视频看 getMaxSupportedInstances 与性能点，HDR 看编码器与屏幕能力，相机看 CameraCharacteristics 与输出流组合，低延迟音频看 AAudio 与实测往返延迟）；用实测耗时、温度、内存压力与失败率调整默认值；用可远程关闭的功能开关保留快速回退。这个顺序避免两类故障：高 MPC 设备因单项能力不满足而配置失败，以及 MPC 为 0 的设备被无条件关闭原本可用的功能。

低等级不是失败状态，而是规范定义的下限信息：MPC 1 面向轻量媒体与低内存低 I/O 环境，应严格限制缓冲、位图与并发；MPC 10 可把 720p30 与较低并发作为初始候选再查编解码器与相机能力；MPC 20 有更高内存与 1080p 屏幕下限，可采用中等并发。值为 0 或未识别时的保护策略：视频默认单路 720p/1080p30 起步，相机预览与拍照分别选尺寸优先首帧与成功率，滤镜限制中间缓冲并提供关闭入口，转码上传限制并发，缓存预算结合 isLowRamDevice() 与进程内存回收通知，再按线上耗时与失败率逐步开放——避免维护庞大的机型白名单。发布决策按 MPC 与运行时能力交叉观察，不直接上报机型等高基数字段。

**Q19: 厂商应用锁、Private Space、AAOS App Lock 与应用内鉴权怎么区分？误认会出什么问题？**

四类机制的保护对象与系统行为不同：AOSP Private Space（Android 15 引入，材料口径；按 AAOS13 源码核对，本地 UserManager 无 USER_TYPE_PROFILE_PRIVATE，该机制不在 Android 13）锁定的是整个私密资料用户——用户停止后其中 Activity/Service/Job 与进程全部结束，应用入口、最近任务与通知被隐藏；OEM 手持设备应用锁由厂商实现，可能只在启动、最近任务或通知前插入认证，未必创建资料用户，没有跨厂商公开 API；AAOS App Lock 是车载特权组件（Android 14 起以非捆绑应用提供、须厂商平台密钥签名放入系统镜像），只服务车载次用户、与资料用户锁定状态相互独立，不能外推到手持设备；应用自有鉴权（如 AndroidX BiometricPrompt）只保护自己的页面与会话，不改变用户或资料用户状态。

误认的典型后果：把"通知不可见"当成 PendingIntent 被系统删除（实际是资料用户停止后的可见性隐藏）、把最近任务消失当成 Activity 主动 finish()（实际是系统隐藏相关任务）、把 Private Space 锁定、厂商认证取消与应用内会话过期归为同一种状态——三者的生命周期、权限与恢复路径都不同。另有两个名称陷阱：Android 17 手持设备的公开接口没有 AppLockManager 一类通用逐应用锁；Advanced Protection Mode 是整机安全总开关，不负责给单个应用加启动口令。

**Q20: 普通 App 能探测 Private Space 吗？默认 Launcher 用哪些 API 接入、要满足什么权限？**

普通 App 不能可靠探测：LauncherApps.getProfiles() 在调用者无权限时只返回当前资料用户，调用者本身位于 managed profile 或 private profile 时也只返回当前用户；UserManager.isQuietModeEnabled(UserHandle) 虽是公开方法，但调用者必须先持有目标 UserHandle，而隐藏私密资料用户的句柄普通 App 拿不到；requestQuietModeEnabled 只允许前台默认 Launcher 或持 MANAGE_USERS/MODIFY_QUIET_MODE 的调用者调用。这种限制是隐私设计的一部分——若任意 App 能判断设备有无 Private Space 及其运行与安装状态，隐藏语义就被削弱。业务上把"设备没有该入口"视为正常配置差异：受管设备、全托管设备或管理员策略都可能关闭该功能，App 应按当前用户中的常规生命周期与失败结果编程。

默认 Launcher 的接入有两条权限路径：声明 normal 级 ACCESS_HIDDEN_PROFILES 并持有 ROLE_HOME 角色；或系统应用持 signature/privileged 级 ACCESS_HIDDEN_PROFILES_FULL（无需 HOME 角色）。只声明 normal 权限不足以列出 Private Space。Launcher 从 LauncherApps.getLauncherUserInfo(user) 拿 LauncherUserInfo 并与 UserManager.USER_TYPE_PROFILE_PRIVATE 比较；用 isQuietModeEnabled 表示"资料用户已锁定"，用 userConfig 的 PRIVATE_SPACE_ENTRYPOINT_HIDDEN（API 36 起）表示"锁定时是否隐藏解锁入口"——两个状态不能合成一个布尔值，否则无法表达"已锁定但入口可见"；监听 ACTION_PROFILE_AVAILABLE/ACTION_PROFILE_UNAVAILABLE 并从 EXTRA_USER 取变化用户，收到广播后重新查询，不要用 managed profile 专属的 ACTION_MANAGED_PROFILE_* 替代。

**Q21: Private Space 锁定后，跨空间的 content:// 读取失败是授权被撤销了吗？涉及私密空间的长任务该怎么处理？**

不一定是授权撤销。锁定会停止私密资料用户，其中的 ContentProvider 不再运行，读取可能抛 FileNotFoundException、SecurityException 或 provider 不可用相关异常；但"调用方是否持有该 URI 的授权"与"提供方用户是否运行"是两个独立条件，空间解锁后若授权仍在且来源对象未被删除，读取可能恢复——单凭异常类型不能判定授权状态。URI 是 content:// 内容标识不是文件路径，读取失败排查时分别记录：授权持有状态、提供方用户与 provider 运行状态、来源对象是否存在。

长任务（上传、转码、OCR、索引）的处理原则：用户选择完成后立即检查 MIME 类型、声明长度与可读性；任务需要脱离来源长期执行时，把内容复制到当前 App 用户域内的受控存储并记录复制是否完成；重新打开 URI 时处理来源被删除、provider 不可用与授权变化；日志不写原始 URI、文件名、媒体标题或私密空间包列表——Private Space 的存在本身带隐私含义，即使对 userId 哈希，结果仍可能成为跨会话不变的标识。Android 16 QPR2 起增加从主空间向 Private Space 移动或复制文件的可选能力（由私密资料用户中的系统前台服务传输），AOSP 发布说明标注由厂商选择集成，不能假定所有设备都有同一入口。测试要覆盖锁定瞬间已选 URI 的同步与延迟读取、六类入口（Launcher、通知、App Link、分享、Photo Picker、DocumentsUI）与厂商应用锁开关组合。

**Q22: Android Auto 和 AAOS 有什么本质区别？性能问题为什么要先确定"谁在哪执行"？**

Android Auto 运行在手机上并把体验投射到兼容车机：应用与 host（手机上的 Android Auto 实现，负责发现应用、管理生命周期、把 Template 数据转换成符合驾驶限制的界面）都在手机，车机是显示、输入与音频端点，业务网络通常走手机；应用不拥有 USB/Wi-Fi/蓝牙投射协议，也不能接管车机接收端的重连算法。AAOS 是直接运行在车载硬件上的 Android：应用进程、存储、网络与 CarService/VHAL/CarWatchdog 都在车端，模板 host 是车机中的系统应用，允许停驻使用的 Activity 走常规 Android 渲染路径。UI 责任分三种：模板模型由客户端构建、布局绘制在 host，地图 Surface 与停驻 Activity 才是应用自己提交图形内容。

版本有四条轴要记录：Android SDK API level、Car App API level（manifest 的 minCarApiLevel 与 host 运行时支持）、androidx.car.app 库版本、host/OEM 软件版本——Android 17 不会自动开启某个模板，Jetpack 升级也不改变旧 host 能力；当前文档还按平台区分发布阶段（如 Car App Library 媒体体验在 Android 17 及以上 AAOS 完整支持、在 Android Auto 受早期访问与测试轨道限制），不能只用 Android API level 推断类别可用性。诊断"点击后卡了"要先拆阶段：Android Auto 是车机输入到手机 host 再到应用再返回模板再生成车机界面的跨设备链路，AAOS 是车机内的单设备链路；没有车端 trace 时用高速摄像与可识别的输入/画面标记测触摸到可见反馈。

**Q23: 模板应用的冷启动与刷新怎么做才不踩 host 的坑？**

onGetTemplate() 是同步取模接口，不能在其中等待网络、数据库迁移、图片解码或路线计算；host 只有拿到首个合法模板才能呈现内容。稳妥结构：进程启动时恢复一份小而完整的本地快照，onGetTemplate() 只读取不可变界面状态并构建模板，网络与磁盘工作放后台，新状态就绪后在主线程调用 Screen.invalidate()，页面销毁或 host 断开时取消无用请求。

刷新与配额是硬边界：invalidate() 只请求 host 再次调用 onGetTemplate()，发出一次刷新请求后、新模板返回前继续调用不产生新请求；host 还限制车屏更新频率，短时间内返回多个模板可能只显示末次结果——不能把 invalidate() 当动画时钟，也不应为每个网络分片刷新界面。host 限制一个任务最多五个模板，配额算 Template 数量而非 Screen 实例数，同类内容刷新、返回上一级与结束任务后重置各有规则，耗尽后继续发送新模板 host 可显示错误并关闭应用。各车允许展示的条目数不同：用 ConstraintManager.getContentLimit() 按运行时上限裁剪数据，客户端不能修改该上限；分页与"更多"操作也要服从对应模板的驾驶限制。指标记 service 绑定到首个模板返回的时间、每次 onGetTemplate() 执行时间分布、invalidate 请求到下一次模板返回的间隔；客户端进程的 FrameTimeline 覆盖不到 host 的完整绘制与车端显示。

**Q24: 地图 Surface 的生命周期怎么管理？帧预算与可见区域有哪些约束？**

导航、POI 与天气类模板要声明相应模板权限及 androidx.car.app.ACCESS_SURFACE，经 AppManager.setSurfaceCallback() 接收 SurfaceContainer。生命周期规则：每次 onSurfaceAvailable() 都以回调给出的宽、高、DPI 与 Surface 为准，尺寸或 DPI 变化时即使底层 Surface 尚未销毁也可能再次回调；每个收到的 Surface 实例都必须调用 release()，onSurfaceDestroyed() 到达后停止提交帧，并释放自己创建的 VirtualDisplay、Presentation、EGL 关联表面、图形缓冲区与地图引擎引用。投射断开、host 重建、昼夜模式或配置变化都可能触发重建。

可见区域分两层：onVisibleAreaChanged() 给出当前保证无遮挡的 visible area，当前必须可见的重要内容放这里；onStableAreaChanged() 给出考虑动态遮挡后长期稳定的最小区域，不希望随 host 控件显隐移动的持续内容放这里——固定安全边距会在超宽屏、远端屏和不同旋转输入布局上出错。帧预算按设备报告与 trace 为准、不能固定 60 Hz：路线计算、瓦片解码与图标生成不进渲染线程，快速拖动时取消已离开视野的请求、合并重复瓦片、限制解码并发；热压力或 GPU 余量不足时减少非导航覆盖物、阴影与预取，但路线、下一转向与安全提示保持可读。分别记录平均帧率、jank、帧呈现时间与功耗。

**Q25: 车载媒体应用围绕什么组件组织？音频与导航语音要注意什么？**

媒体浏览与播放围绕 MediaBrowserService 或 Media3 的 MediaLibraryService 与 MediaSession 组织：browse tree 供 host 分层浏览，MediaSession 提供播放状态、队列、元数据与控制，Car App Library 媒体体验仍需按 host 能力保留这些组件或兼容路径。性能四块：浏览树根节点与常用分类从本地索引快速返回、远端结果分页加载且错误可恢复；封面按 host 所需尺寸解码并设内存磁盘缓存上限，不为每个条目保存原图；播放器状态先写 MediaSession、UI 作为状态消费者，host 重连后立即拿到队列与位置；音频正确处理焦点、duck、蓝牙或车载输出变化与 ACTION_AUDIO_BECOMING_NOISY，不在 UI 线程准备解码器或等待 DRM 与网络。

导航语音要请求 audio focus 并使用 AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE，官方建议的短时焦点类型是 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK；语音生成、媒体降音量与播放完成事件带会话 ID，避免重算路线后播出旧指令。定位请求频率服务于导航精度与路线匹配，不长期用设备最高采样率；记录定位时间戳、精度、路线匹配耗时与渲染使用的位置版本，才能区分定位波动、路线计算慢与画面更新慢。扬声器端到端时延还包含 Android 音频栈、host 或 CarAudioService、DSP 与车辆放大器，应用 trace 只覆盖其中一段。行驶中视频等停驻体验按平台支持与停驻状态处理，屏幕尺寸足够不等于允许播放。

**Q26: CarWatchdog 怎么管第三方应用的磁盘写入？地图与媒体应用最容易超限的模式是什么？**

AAOS CarWatchdog 通过内核 /proc/uid_io/stats 的 per-UID I/O 统计跟踪应用与服务的磁盘写入（AAOS13 树 packages/services/Car 的 watchdog 服务端已实现该链路），写入量从每个 UTC 自然日开始累计、跨同一天内的多次车辆启动保留；第三方应用反复超过配置阈值时可被设为 COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED，即停用到用户再次启动或手动启用。CarWatchdogManager 提供 getResourceOveruseStats()（查当前一天或过去最多 30 天）与 addResourceOveruseListener()（写入达阈值约 80% 或 100% 时收到通知），AAOS13 树的 car-lib 已含这些接口；地图与媒体类别可有更高的独立阈值，具体数值与处理动作由系统和厂商配置共同决定。

超限高发模式：瓦片与路线缓存反复覆盖同一文件、SQLite WAL checkpoint 频繁且日志未收敛、图片转码与临时文件重复生成、离线包重复解压、每次位置更新都触发持久化。优化方向是减少写放大（底层实际写入量大于业务数据量）：合并改写、控制无效淘汰、复用解压结果，通常比单纯扩大缓存有效；同时记录逻辑下载字节、文件系统写入字节、缓存命中率与淘汰量，验证收益而不是只看下载量。

**Q27: AAOS 的车辆电源状态机与 VHAL 属性访问对应用意味着什么？跨版本订阅接口有什么差异？**

电源状态由 CarPowerManagementService（CPMS）与 VHAL 协调，覆盖 On、Shutdown Prepare、Suspend-to-RAM、Suspend-to-Disk 与关机；CarPowerPolicyDaemon 集中管理电源策略，可按车辆状态关闭显示、音频、定位、蓝牙等组件。setListenerWithCompletion() 等 CarPower 接口是受权限保护的 System API（需要 CONTROL_SHUTDOWN_PROCESS），普通第三方应用不能用它延长电源切换——应按常规生命周期持久化最小恢复状态、接受进程随时被终止、在网络或音频能力恢复后重建会话。按 AAOS13 源码核对，CarPowerStateListenerWithCompletion、STATE_SHUTDOWN_PREPARE（值 7）与 CompletablePowerStateChangeFuture 已存在于本地 car-lib：特权服务在 Shutdown Prepare、Suspend Enter、Hibernation Enter 等状态会拿到非空 future，须在 getExpirationTime() 期限内 complete()，到期后系统仍继续转换；挂起或关机准备阶段不应启动大规模同步与缓存整理。

VHAL 使用 AIDL 接口 IVehicle.aidl（AAOS13 树已冻结 v1），把车速、挡位等车辆属性转换成 Android 侧统一接口，应用经 CarPropertyManager 访问并接受权限检查，不能绕过 CarService 直接向车内总线发消息；ADAS 等安全控制不依赖应用时序，信息娱乐显示的车辆数据要带时间戳与过期判定。订阅接口有版本差异：AAOS13 的 CarPropertyManager 用 registerCallback()（本地源码无 subscribePropertyEvents 也无弃用标注），Android 17 材料口径是 registerCallback() 已弃用、推荐 subscribePropertyEvents() 且默认启用可变更新率（数值未变化时省略重复回调）——跨版本代码要保留兼容路径。同步 getProperty() 可能耗时数秒、不能在主线程调用；订阅回调未显式指定 Executor 时落到创建 Car 时的事件线程或主线程，回调里只保存快照，解析、聚合与上传放到有队列上限的线程池，OEM 自定义属性更新过频或单次数据过大都会挤占车辆属性通道。
