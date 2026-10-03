# 平台性能优化与前沿评估

> 学习资料（文章模式沉淀）。边界：本文回答"平台级性能优化的证据要求、启动指标口径、AOSP 与论文方案（AppFlow、AOHP）、Game Mode 与设备能力分级的取舍"；构建命令归 13-build-system，机制描述归各机制册。论文/原型结论均标注证据等级，不得当平台承诺。Q 序列即结构，供 atlas 同源直读。

**Q1: 一轮 AOSP 平台性能优化开始前，怎样判断问题属于平台而不是单个应用？结论需要哪三类证据共同约束？**

只有证据指向公共路径、或应用无法在公开接口与文档约定内避开问题时，才应优先修改平台：同机多个应用出现相同的合成、调度或驱动等待，比单应用的 View 遍历、图片解码更接近平台问题。判断不能只靠一次 trace，应找同版本同设备的对照应用，再比较不同版本、设备与兼容性开关（compat change），现象只有随平台变量稳定移动，才有依据继续检查 framework、ART、native 服务或内核。

结论由三类证据共同约束，且不能互相替代：

1. **公开约定**：release notes、behavior changes 与 SDK 文档决定兼容与迁移边界，但不说明目标设备实际启用了什么；
2. **固定 tag 源码**：回答实现、开关条件与默认值，源码中存在某函数或配置默认为 y，不能单独证明设备已启用；
3. **设备运行证据**：build fingerprint、ART/APEX 版本、内核版本加 trace 与实验，说明本次负载实际走到哪条路径。

方法上先用多轮样本确认 P50/P90 等分位数与离散程度，再用 Perfetto、simpleperf 或 dumpsys 解释慢样本；一次只改一个主要变量，无法隔离时降低结论强度。平台改动还要检查稳定性、资源、正确性、兼容性与安全五类副作用，并把回滚路径作为实现的一部分——可配置优化要定义默认值、分批发布的设备分组与回滚后的清理动作。

**Q2: 系统启动优化怎么定义"启动完成"？为什么 first boot 与 OTA 后首启的样本要单独分组？**

启动完成有多个合法终点：kernel entry（bootloader 交权）、second-stage init、Zygote start、sys.boot_completed=1、Launcher shown、first interactive，指标名必须写明起点与终点（如 kernel_to_boot_completed_ms、boot_completed_to_first_interaction_ms）。sys.boot_completed 由 ActivityManagerService.finishBooting() 在 boot animation 完成后的收尾流程中设置，它不等于桌面已显示，也不等于触摸已有响应；property 置 1 后用户回调、广播与应用进程仍可能占用 CPU 与 I/O。init 会用 ro.boottime.<service> 记录服务首次启动的 CLOCK_BOOTTIME 时间，Zygote 对应 ro.boottime.zygote。

样本分类是因为特殊启动会插入普通启动没有的成本：factory reset 后首启有包扫描与向导，OTA 后首启有 checkpoint、APEX/分区切换与 dexopt，Boot Classpath APEX 变化后首启会触发 ART boot dexopt，userspace reboot 与加密状态变化也各有额外路径。材料的源码口径指出：DexOptHelper.performPackageDexOptUpgradeIfNeeded() 只在 first boot、device upgrade 或 Boot Classpath APEX 变化时调用 ArtManagerLocal.onBoot()，该调用会阻塞且耗时可能超过 30 秒，普通启动直接返回——版本看板不区分启动类型时，P90 很容易被少量升级样本主导。

**Q3: init 的 Action 队列是串行的，为什么启动期间还能多进程并发？把慢的驱动 probe 或 module 移出关键路径要防什么回退？**

串行的是 init 的 Action 队列：rc 按解析顺序入队，Action 依次执行、Action 内 command 也依次执行，exec/exec_start 还会让队列等待进程结束。但 start/class_start 只是依次 fork/exec，启动后的 service 彼此并发运行，exec_background 启动进程后不阻塞后续 command——正确姿势是把独立工作放进 service 并发执行，再用 property 表达"完成"依赖（如 on property:vendor.prepare_cache.ready=1 再 start 下游），并给失败路径准备超时与降级。另外注意 trigger 语义：on boot && property:x=y 只在 boot 事件发生时检查组合条件，boot 已过去、property 后来才满足的 Action 不会补跑；把 service 移到更早 class 前要核对分区挂载、SELinux domain、设备节点、APEX 激活与 HAL 依赖，class 只提供分组不表达依赖图。

kernel 段同理：选择性异步 probe 可让慢速 I2C/SPI 设备、加载 firmware 的设备并行初始化（模块还可经 <module>.async_probe=1），官方示例收益 100–500 ms；把非必要 module 从 first-stage ramdisk 挪到 second-stage 可省 500–1000 ms——两个数字取决于硬件与驱动，只能说明量级。风险是依赖表达错误：异步 probe 后 consumer 发现 supplier（时钟、电源等资源提供方）未就绪必须正确返回 -EPROBE_DEFER，显示、存储、clock、regulator、thermal 依赖表达错会把成本变成更晚的同步等待或功能故障，defer storm（大量 probe 反复 defer 重试）会吃掉启动期 CPU。移动 module 后要同时验证 normal boot、recovery、fastbootd、OTA 与 crash recovery；CPUfreq/devfreq 提前上线前要确认 supplier 就绪，并接受频率上升带来的功耗温升回归。

**Q4: AppFlow 论文把 GB 级应用冷启动当成什么问题？为什么"只加文件预读"可能反而变慢？**

AppFlow（MobiCom 2026 研究原型，arXiv 2603.17259，非 AOSP 主线）把大型应用冷启动拆成三段相互影响的成本：CPU 与调度（进程创建、类加载、初始化）、文件 I/O（APK/DEX/SO/资源不在 page cache 触发 major fault）、内存分配与回收（分配触发 kswapd、direct reclaim 与 swap）。它同时协调三件事：启动前按频次做预算内预读（multiple-choice knapsack 把每个应用的文件大小分界约束进全局 100 MB 预算）、启动中吞吐优先读大文件、按内存压力与进程上下文选择后台进程终止。

"只加预读"可能更慢的原因是预读与回收互相抵消：预读占用的 page cache 在低内存下会被回收，论文测得预读页被回收后启动 I/O 延迟增至 6.4 倍；其 ablation 也显示只开 Selective File Preloader 时 cold relaunch 数增加 30%。因此它修改了内核回收路径（按窗口进入 file-first、经 /proc 清单把预加载页标为 active 暂不逐出、每 10 秒刷新活跃度），并约束进程终止只能给系统原本允许终止的候选重新排序，导航、通话、音频等可感知进程不得因预测分数低而被杀。证据边界要守住：128KB 文件分界、100 MB 预算、最多 66.5% 冷启动下降等都来自论文设备与应用集合（Pixel 7/8 与 Raspberry Pi 4B 车载试验台、Android 15 基线），论文未公开完整 Framework/kernel patch，也未进入 Android 17 主线——材料核对的 android17-6.18 vmscan.c 没有预加载清单或启动窗口，这些只能当待验证假设。

**Q5: Android 的 lmkd 基线是怎样工作的？AppFlow 式的"预测哪个应用可杀"在现平台上缺什么？**

lmkd 是用户态低内存终止守护进程，按 AAOS13 源码核对（system/memory/lmkd/lmkd.cpp）与材料一致：默认以 PSI 检测压力，常规设备 psi_partial_stall_ms 默认 70 ms（low-RAM 200 ms，指 1000 ms 窗口内 PSI some 的停顿门槛）、psi_complete_stall_ms 700 ms（full 停顿）、thrashing_limit 默认 100%（low-RAM 30%），且每轮因 thrashing 终止进程后按 10%/50% 下调门槛。收到压力事件后还检查 zone watermark、swap 与 direct reclaim；候选选择由 AMS 经 ProcessList 下发的 oom_score_adj 驱动，数值越高越先被杀，kill_heaviest_task=false 时通常取高 adj 分组队尾，搜索进入 PERCEPTIBLE_APP_ADJ 或更重要范围时会强制选 heaviest 以减少终止次数。

现平台缺的是 AppFlow 需要的三样：跨应用文件访问记录与启动预测（平台只有 UsageStats 等授权接口）、内核 vmscan 里的启动窗口与预读页保护（vendor module 不能替换 core mm 的回收语义）、基于 relaunch 基线（ΔM）与返回概率的净释放排序——oom_score_adj 表达组件此刻的重要性，不是预测。协议能力也有版本差异：AAOS13 的 ProcessList 用 LMK_PROCPRIO 逐进程下发 oom_adj，Android 17 才有 LMK_PROCS_PRIO 批量协议（材料口径每包至多 3 条）；无论哪版都没有"保护将启动应用"的策略接口。全局属性（ro.lmk.lowmem_min_oom、kill_heaviest_task、thrashing 门槛）影响整台设备：调高最低可终止 adj 可能在严重压力下找不到候选，增加 direct reclaim 与 kernel OOM 风险，调低则扩大候选集合。

**Q6: 想借鉴 AppFlow，普通应用、特权 framework 原型、产品级 OS 三种权限下分别能做什么、不能做什么？**

普通应用：优化自身初始化、生成 Baseline Profile、记录 TTID/TTFD、采样并预读自己的文件、控制资源读取；在 TRIM_MEMORY_UI_HIDDEN 回调释放可重建的界面缓存；用 ApplicationExitInfo 回看退出原因。不能访问 lmkd 控制 socket、改其它进程的 oom_score_adj、修改 vmscan 或保护 page cache 中的指定文件；跨包查询使用历史需 PACKAGE_USAGE_STATS 并由用户在设置中授予 usage access。

特权 framework 原型：可在 platform service 中采集授权的应用切换序列、维护带版本号的文件访问记录、在 launch observer 收到事件前后调度预读，并验证预测准确率、I/O 干扰与预算是否适合目标设备；但 kernel 未改时预读页随时可能按常规策略被回收，报告必须区分"读取内容最终被使用"与"页面留到使用时刻"，也不应随意降低 adj 模拟后台存活收益。

产品级 OS：要同时改 framework 与 kernel——建立启动会话 ID、传递文件或 inode/offset 标识、在 classic LRU 与 MGLRU 双路径定义短时保护、处理文件截断/更新/卸载与 memcg 迁移，并把进程排序限制在 AMS/lmkd 已允许终止的候选内；GKI 下 vendor module 不能替换 core mm 的回收语义，改 core mm 意味着长期维护内核分叉与安全更新合并。三类实现都要有退出条件：kernel OOM、SystemUI/Launcher 被杀、导航或音频中断、P99 变差或读放大失控时自动停用并恢复原生行为。实验上固定 cold/hot/warm 口径与分层负载，按组件做 ablation 分离"读取提前""页面保留"与"进程选择"的贡献。

**Q7: AOHP 想把 Android 改造成什么样？评估它时为什么要区分论文设计、原型实现与标准平台三类证据？**

AOHP（Android Open Harness Project）是面向 AI agent 的 OS 级 harness 研究原型：agent 一次任务可能跨多个应用、CLI、文件与服务，需要结构化可验证的观察、事件驱动的生命周期与"数据可流向哪里"的控制面；而标准 Android 的授权对象是应用 UID 与组件，运行时权限与 URI grant 不会理解 LLM prompt 或跨工具派生数据。AOHP 通过修改 frameworks/base、system/core、SELinux policy 等把能力发现、策略检查与审计放进统一系统服务，论文方案分为个性化服务组合、高效 agent 接口与安全信息流三部分。

三类证据不能互替：论文设计（arXiv 2606.23449）说明架构与实验；AOHP 实现（framework fork 与 container daemon 的固定 commit）说明当前原型写出了什么；Android 17 对照（android-17.0.0_r1 与 API 37 文档）说明标准平台提供什么。例如论文的 OS 管理跨服务记忆、全链路信息流控制是设计目标，而实现中 AohpVaultService 是内存 token 表（ConcurrentHashMap 直接保存明文映射，无持久加密、过期与 secure erase），AohpTaintTrackerService 只在选定 UI/tool/file 边界记录元数据——不是 TaintDroid 式的全系统动态污点传播。仓库以 Android 16 QPR2 为构建基线且未进入 Android 17，fork 源码不能当标准平台源码读；项目自身也标注为早期研究、不适合生产环境。

**Q8: AOHP 用特权虚拟显示、结构化 UI 和事件流替代 GUI 往返；标准平台的 VirtualDisplay 与 AppFunctions 分别给到哪一步？**

AOHP 的虚拟显示用 signature|privileged 权限 MANAGE_AOHP_VIRTUAL_DISPLAY 与 framework 内部接口创建 PUBLIC+TRUSTED+OWN_FOCUS 的 display，把应用放上去后台交互，再挂 ImageReader（maxImages 为 3）持续取帧；成本仍然存在——WindowManager、SurfaceFlinger、图形 buffer、GPU 合成与输入分发都要工作，论文未报告其开销。标准平台上，公共 DisplayManager.createVirtualDisplay() 创建的 display 默认 private、non-secure，创建 VirtualDisplay 不会自动获得后台启动其它应用 Activity、读取 FLAG_SECURE 窗口、注入输入或豁免冻结等能力，这些受系统权限限制。AOHP 的 Structured UI 从 accessibility tree 导出节点类型/文本/层级/bounds，省去像素细节，但 Canvas、游戏与 WebView 的语义质量依赖应用，节点树与像素可能不同帧；事件流由 system_server 内 hook 提供 Toast/通知的 session buffer（默认上限 200 条、TTL 10 分钟），普通应用用 NotificationListenerService 无法等价复制，因为它拿不到其他应用的 Toast。

标准平台最接近的结构化入口是 AppFunctions（API 36 进入平台、Android 17 扩展，官方仍标 experimental preview）：目标应用在 XML asset 声明 function，caller 搜索需 DISCOVER_APP_FUNCTIONS、跨包执行需 EXECUTE_APP_FUNCTIONS 或 EXECUTE_APP_FUNCTIONS_SYSTEM，并满足包可见性；runtime registration 随注册 context 生命周期，注册进程被冻结时系统不会进入该实现。它不提供任意后台 GUI workspace，与 AOHP 的虚拟显示 session 不能互换；system_server 侧服务由 AppFunctionManagerConfiguration.isSupported() 的 flag gate 决定是否启动。两者组合前要先统一身份、consent、审计与撤销模型，避免两个控制面重复授权。

**Q9: AOHP 的安全信息流设计了六步流程，原型现状与论文的差距在哪？五个 security case 通过能说明什么、不能说明什么？**

论文流程六步：source 被识别为敏感，明文进 agent context 前替换为 typed placeholder，vault 保存明文与 token 映射，agent 只携带 token 提交意图，trusted executor 检查 source/purpose/destination/action/consent，sink 前放行、确认或拒绝并写审计。原型现状的差距要逐个 sink 标注：UI tree 敏感字段替换、token 输入校验、敏感 tap/输入的 consent、file share 检查已实现或部分实现；文件读写的策略检查是 stub（源码返回 DENY 加 file_read_policy_not_implemented），好在选择 fail closed——缺策略时默认拒绝。vault 是进程内存 token 表，重启后映射消失，无硬件密钥绑定、用户隔离与可验证擦除；sanitizer 依赖应用声明加手机号、银行卡号等启发式，字段识别不保证完整。

五个 security case（敏感显示替换、普通动作放行、敏感动作确认、未支持访问拒绝、敏感事件脱敏）在该原型与该测试应用上通过，能证明这五个用例按预期运行；不能证明任意第三方应用的字段识别完整、隐式流/native code/侧信道被跟踪、prompt injection 无法诱导已授权动作、vault 在重启/多用户/设备失窃场景安全，或 framework 服务无提权与拒绝服务问题。实验数字同理：checkpoint 加权完成率从 54.44% 提升到 75.56%、共同成功任务的 token 降低 51.55%，是该任务集上该 agent 的结果，论文没有消融实验，不能归因给 SUI 单一机制，更不能推广到所有应用、设备与模型。

**Q10: AOSP 的 Game Mode 给了系统什么信号、没给什么？"游戏事件在 InputDispatcher 有专属优先级"为什么不成立？**

Game Mode 提供的是模式与场景信号，不是输入优先级。用户可选 Standard/Performance/Battery（Android 14 加 Custom），游戏在每次 onResume 重新读取 getGameMode()（可能返回 GAME_MODE_UNSUPPORTED）自行调整画质帧率；厂商干预（backbuffer resize、FPS throttling、ANGLE）面向未适配游戏，不定义输入事件顺序，游戏声明 supportsBatteryGameMode/supportsPerformanceGameMode 后平台会清除此前对该模式施加的干预。GAME_LOADING（Android 13 引入）与 GAME（Android 14 引入）是发给 Power HAL 的电源模式：按 AAOS13 源码核对，GameManagerService 只在 GAME_MODE_PERFORMANCE 时把 isLoading 经 Mode.GAME_LOADING 传给 PowerManager 并带超时限制；Mode.GAME 为 Android 14 引入、本地树未见，A17 口径由"TOP 进程全部是游戏"触发。Power HAL 收到信号后可以提高 CPU 频率或游戏线程调度优先级，但这是性能温控策略，不改窗口路由。

"游戏事件有专属优先级"不成立的直接证据：按 AAOS13 源码核对，本地 frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp 对 GameManager/GAME_MODE 的引用为 0。触摸目标由逻辑显示屏、坐标命中测试、窗口可接收性与触摸序列状态决定，从窗口栈前端向后遍历选首个能接收触点且非 spy window 的窗口，游戏模式不参与该函数；InputTarget.Flags.FOREGROUND 只表示该目标是本次事件的主要前台目标，不表示性能优先级；策略回调 interceptKey/MotionBeforeQueueing 也不接收游戏模式参数。厂商"触控增强"可能改触控采样、驱动、调度、刷新率或游戏工具任意一层，判断某设备是否在 inputflinger 重排事件需要厂商源码差异或跟踪数据。

**Q11: Media Performance Class 是什么？Android 17 新增的等级为什么打破了"非零值等于 API 级别"的旧假设？**

MPC（Media Performance Class）是兼容性规范（CDD）定义的设备能力下限集合，用一个整数关联编解码器、相机、音频、显示、内存、存储与图形要求，应用运行时读取它选择初始体验档位；它不是通用跑分，也不能替代单项能力查询——声明了高等级的设备仍可能因温度、后台负载或厂商策略波动。旧等级值曾与 API 级别对齐（30/31/33/34/35 对应 Android 11–15），Android 17 CDD 2.2.7 新增 1、10、20 三个低等级与最高等级 37（32、36 未定义），非零值不再都等于某个 VERSION_CODES。

0 的语义是"当前读取路径没有可用声明"：可能是设备未声明、旧系统没有公开字段、Jetpack 或 Play services 的补充读取未返回，不能证明设备低端或某项能力缺失，业务应为 0 选择保守默认值再用运行时 API 逐项开启。MPC 与平台版本分离：OTA 后设备可保留原 MPC，SDK_INT 回答 API 是否存在、MPC 回答能力下限声明、运行时能力查询回答当前设备是否支持某功能、实测数据回答当前负载能否达标——四类信号不能互换。读取字段 Build.VERSION.MEDIA_PERFORMANCE_CLASS 自 Android 12 公开（AAOS13 树的 Build.java 已存在该字段），同一次开机内稳定、OTA 后可能提高。版本边界：Android 17 的新等级按材料与官方 CDD 口径转写；AAOS13（CDD 13）语境下合法值仍是 30/31/33。

**Q12: 用 Jetpack DevicePerformance 读 MPC 有什么兼容性坑？业务分级为什么不能用 mpc >= 34 这类比较？**

两个坑。其一，PlayServicesDevicePerformance 先从 DataStore 读取 Play services 上次结果与平台默认读取值取较大者，而 mediaPerformanceClass 是 lazy 初始化——第一次访问发生在异步更新完成之前就会一直使用旧本地结果，官方建议在 Application.onCreate() 中只创建一次对象，新结果通常供后续进程使用。其二，材料核对时点的 androidx-main 中，DefaultDevicePerformance.isPerformanceClassValid() 仍要求值至少为 Build.VERSION_CODES.R（30），会把 CDD 17 的 1/10/20 视为无效并退回 0——只依赖平台默认读取路径的代码会丢失新低等级；Play services 已把低等级写入 DataStore 时取 max 仍能保留。接入前要对项目锁定的 Jetpack 版本做单元测试，并同时上报平台原始值 raw_build_mpc 与库最终值 resolved_mpc。

业务分级不能用 >= 比较，因为等级集合和条款不是单调的：有些等级提高测试负载后仍使用相同计数阈值，测试口径（分辨率、帧率、并发）也会改变。更稳的做法是为每项功能维护 CDD 明确列出的等级集合——如 HDR 显示候选 {34, 35, 37}、后置 RAW 候选 {31, 33, 34, 35, 37}（31 才要求 RAW）、JPEG_R 候选 {35, 37}——未识别值进入 Unknown 不自动套用相邻等级，且每项仍要保留运行时能力查询作为必要条件。产品侧的 MediaTier 分组不能反向解释为 CDD 正式名称。

**Q13: MPC 与运行时能力查询怎样组合决策？值为 0 或低等级的设备怎么设计体验？**

决策顺序固定五步：用 SDK_INT 判断 API 是否存在；用经过显式识别的 MPC 选择保守、标准或高规格候选；用运行时能力查询剔除设备不支持的项（多路视频看 getMaxSupportedInstances 与性能点，HDR 看编码器与屏幕能力，相机看 CameraCharacteristics 与输出流组合，低延迟音频看 AAudio 与实测往返延迟）；用实测耗时、温度、内存压力与失败率调整默认值；用可远程关闭的功能开关保留快速回退。这个顺序避免两类故障：高 MPC 设备因单项能力不满足而配置失败，以及 MPC 为 0 的设备被无条件关闭原本可用的功能。

低等级不是失败状态，而是规范定义的下限信息：MPC 1 面向轻量媒体与低内存低 I/O 环境，应严格限制缓冲、位图与并发；MPC 10 可把 720p30 与较低并发作为初始候选再查编解码器与相机能力；MPC 20 有更高内存与 1080p 屏幕下限，可采用中等并发。值为 0 或未识别时的保护策略：视频默认单路 720p/1080p30 起步，相机预览与拍照分别选尺寸优先首帧与成功率，滤镜限制中间缓冲并提供关闭入口，转码上传限制并发，缓存预算结合 isLowRamDevice() 与进程内存回收通知，再按线上耗时与失败率逐步开放——避免维护庞大的机型白名单。发布决策按 MPC 与运行时能力交叉观察，不直接上报机型等高基数字段。
