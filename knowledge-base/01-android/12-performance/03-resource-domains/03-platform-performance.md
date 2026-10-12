# Android 平台性能优化

**Q1: [learning] 一轮 AOSP 平台性能优化开始前，怎样判断问题属于平台而不是单个应用？结论需要哪三类证据共同约束？**

只有证据指向公共路径、或应用无法在公开接口与文档约定内避开问题时，才应优先修改平台：同机多个应用出现相同的合成、调度或驱动等待，比单应用的 View 遍历、图片解码更接近平台问题。判断不能只靠一次 trace，应找同版本同设备的对照应用，再比较不同版本、设备与兼容性开关（compat change），现象只有随平台变量稳定移动，才有依据继续检查 framework、ART、native 服务或内核。

结论由三类证据共同约束，且不能互相替代：

1. **公开约定**：release notes、behavior changes 与 SDK 文档决定兼容与迁移边界，但不说明目标设备实际启用了什么。
2. **固定 tag 源码**：回答实现、开关条件与默认值，源码中存在某函数或配置默认为 y，不能单独证明设备已启用。
3. **设备运行证据**：build fingerprint、ART/APEX 版本、内核版本加 trace 与实验，说明本次负载实际走到哪条路径。

证据到位后按以下顺序推进：

1. **确认分布：**用多轮样本比较 P50/P90 等分位数与离散程度，避免只凭单次 trace 定结论。
2. **解释慢样本：**用 Perfetto、simpleperf 或 dumpsys 追到实际执行路径。
3. **控制变量：**一次只改一个主要变量；无法隔离时降低结论强度。
4. **评估副作用与回退：**检查稳定性、资源、正确性、兼容性和安全；可配置优化要定义默认值、分批发布设备组，以及回滚后的清理动作。

**Q2: [learning] 系统启动优化怎么定义“启动完成”？为什么 first boot 与 OTA 后首启的样本要单独分组？**

启动完成有多个合法终点：kernel entry（bootloader 交权）、second-stage init、Zygote start、sys.boot_completed=1、Launcher shown、first interactive，指标名必须写明起点与终点（如 kernel_to_boot_completed_ms、boot_completed_to_first_interaction_ms）。sys.boot_completed 由 ActivityManagerService.finishBooting() 在 boot animation 完成后的收尾流程中设置，它不等于桌面已显示，也不等于触摸已有响应。property 置 1 后用户回调、广播与应用进程仍可能占用 CPU 与 I/O。init 会用 ro.boottime.<service> 记录服务首次启动的 CLOCK_BOOTTIME 时间，Zygote 对应 ro.boottime.zygote。

样本分类是因为特殊启动会插入普通启动没有的成本：factory reset 后首启有包扫描与向导，OTA 后首启有 checkpoint、APEX/分区切换与 dexopt，Boot Classpath APEX 变化后首启会触发 ART boot dexopt，userspace reboot 与加密状态变化也各有额外路径。材料的源码口径指出：DexOptHelper.performPackageDexOptUpgradeIfNeeded() 只在 first boot、device upgrade 或 Boot Classpath APEX 变化时调用 ArtManagerLocal.onBoot()，该调用会阻塞且耗时可能超过 30 秒，普通启动直接返回——版本看板不区分启动类型时，P90 很容易被少量升级样本主导。

**Q3: [learning] init 的 Action 队列是串行的，为什么启动期间还能多进程并发？把慢的驱动 probe 或 module 移出关键路径要防什么回退？**

init 队列串行与 service 并发是两个不同层级：

1. **Action 执行：**rc 按解析顺序入队，Action 依次执行，Action 内的 command 也依次执行。
2. **阻塞命令：**`exec`/`exec_start` 会让队列等待目标进程结束；`start`/`class_start` 依次发起服务，但服务启动后的工作彼此并发；`exec_background` 启动后不阻塞后续 command。
3. **表达依赖：**把独立工作放进 service 并发执行，再用 property 表达完成依赖（如 `on property:vendor.prepare_cache.ready=1` 后启动下游），并为失败路径准备超时与降级。
4. **核对触发时机：**`on boot && property:x=y` 只在 boot 事件发生时检查组合条件；boot 已过去、property 后来才满足时，Action 不会补跑。
5. **核对 service 依赖：**把 service 移到更早 class 前，检查分区挂载、SELinux domain、设备节点、APEX 激活与 HAL 依赖。class 只提供分组，不表达依赖图。

kernel 侧把慢 probe 或 module 移出关键路径也有条件：

1. 选择性异步 probe 可让慢速 I2C/SPI 设备及加载 firmware 的设备并行初始化；模块还可通过 `<module>.async_probe=1` 请求异步 probe。官方示例收益 100–500 ms，取决于硬件与驱动，只表示量级。
2. 把非必要 module 从 first-stage ramdisk 移到 second-stage，官方示例可省 500–1000 ms；同样不能当作设备保证值。
3. 异步 probe 后，consumer 发现 supplier（时钟、电源等资源提供方）未就绪时，必须正确返回 `-EPROBE_DEFER`。显示、存储、clock、regulator、thermal 等依赖若表达错误，可能变成更晚的同步等待或功能故障；defer storm 会消耗启动期 CPU。
4. 移动 module 后验证 normal boot、recovery、fastbootd、OTA 与 crash recovery。CPUfreq/devfreq 提前上线前确认 supplier 就绪，并测量频率上升带来的功耗与温升回归。

kernel 段同理：选择性异步 probe 可让慢速 I2C/SPI 设备、加载 firmware 的设备并行初始化（模块还可经 <module>.async_probe=1），官方示例收益 100–500 ms。把非必要 module 从 first-stage ramdisk 挪到 second-stage 可省 500–1000 ms——两个数字取决于硬件与驱动，只能说明量级。风险是依赖表达错误：异步 probe 后 consumer 发现 supplier（时钟、电源等资源提供方）未就绪必须正确返回 -EPROBE_DEFER，显示、存储、clock、regulator、thermal 依赖表达错会把成本变成更晚的同步等待或功能故障，defer storm（大量 probe 反复 defer 重试）会吃掉启动期 CPU。移动 module 后要同时验证 normal boot、recovery、fastbootd、OTA 与 crash recovery。CPUfreq/devfreq 提前上线前要确认 supplier 就绪，并接受频率上升带来的功耗温升回归。

**Q4: [learning] AppFlow 论文把 GB 级应用冷启动当成什么问题？为什么“只加文件预读”可能反而变慢？**

AppFlow（《AppFlow: Memory Scheduling for Cold Launch of Large Apps on Mobile and Vehicle Systems》，arXiv:2603.17259 预印本，已被 MobiCom 2026 接收；非 AOSP 主线）把大型应用冷启动拆成三类相互影响的成本：CPU 与调度、文件 I/O、内存分配与回收。论文提出三项协调机制：

1. **启动前预读：**按文件访问频次，在预算内选择预读集合；论文将每个应用的文件大小分界建模为 multiple-choice knapsack，并使用全局 100 MB 预算。
2. **启动中读取：**对大文件采用吞吐优先的读取策略。
3. **内存压力下的进程选择：**结合内存压力与进程上下文选择可终止的后台进程。

这些方案分别应对进程创建、类加载与初始化，APK/DEX/SO/资源不在 page cache 导致的 major fault，以及分配触发 kswapd、direct reclaim 与 swap 等成本；论文评测结果不等于 Android 平台承诺。

“只加预读”可能更慢的原因是预读与回收互相抵消：预读占用的 page cache 在低内存下会被回收，论文测得预读页被回收后启动 I/O 延迟增至 6.4 倍。其 ablation 也显示只开 Selective File Preloader 时 cold relaunch 数增加 30%。因此它修改了内核回收路径（按窗口进入 file-first、经 /proc 清单把预加载页标为 active 暂不逐出、每 10 秒刷新活跃度），并约束进程终止只能给系统原本允许终止的候选重新排序，导航、通话、音频等可感知进程不得因预测分数低而被杀。证据边界要守住：128KB 文件分界、100 MB 预算、最多 66.5% 冷启动下降等都来自论文设备与应用集合（Pixel 7/8 与 Raspberry Pi 4B 车载试验台、Android 15 基线），论文未公开完整 Framework/kernel patch，也未进入 Android 17 主线——材料核对的 android17-6.18 vmscan.c 没有预加载清单或启动窗口，这些只能当待验证假设。

**Q5: [learning] Android 的 lmkd 基线是怎样工作的？AppFlow 式的“预测哪个应用可杀”在现平台上缺什么？**

lmkd 是用户态低内存终止守护进程，按 AAOS13 源码核对（system/memory/lmkd/lmkd.cpp）与材料一致：默认以 PSI 检测压力，常规设备 psi_partial_stall_ms 默认 70 ms（low-RAM 200 ms，指 1000 ms 窗口内 PSI some 的停顿门槛）、psi_complete_stall_ms 700 ms（full 停顿）、thrashing_limit 默认 100%（low-RAM 30%），且每轮因 thrashing 终止进程后按 10%/50% 下调门槛。收到压力事件后还检查 zone watermark、swap 与 direct reclaim。候选选择由 AMS 经 ProcessList 下发的 oom_score_adj 驱动，数值越高越先被杀，kill_heaviest_task=false 时通常取高 adj 分组队尾，搜索进入 PERCEPTIBLE_APP_ADJ 或更重要范围时会强制选 heaviest 以减少终止次数。

现平台缺的是 AppFlow 需要的三样：跨应用文件访问记录与启动预测（平台只有 UsageStats 等授权接口）、内核 vmscan 里的启动窗口与预读页保护（vendor module 不能替换 core mm 的回收语义）、基于 relaunch 基线（ΔM）与返回概率的净释放排序——oom_score_adj 表达组件此刻的重要性，不是预测。协议能力也有版本差异：AAOS13 的 ProcessList 用 LMK_PROCPRIO 逐进程下发 oom_adj，Android 17 才有 LMK_PROCS_PRIO 批量协议（材料口径每包至多 3 条）。无论哪版都没有“保护将启动应用”的策略接口。全局属性（ro.lmk.lowmem_min_oom、kill_heaviest_task、thrashing 门槛）影响整台设备：调高最低可终止 adj 可能在严重压力下找不到候选，增加 direct reclaim 与 kernel OOM 风险，调低则扩大候选集合。

**Q6: [learning] 想借鉴 AppFlow，普通应用、特权 framework 原型、产品级 OS 三种权限下分别能做什么、不能做什么？**

借鉴 AppFlow 的能力取决于权限层级：

1. **普通应用：**优化自身初始化、生成 Baseline Profile、记录 TTID/TTFD、预读自身文件并控制资源读取；可在 `TRIM_MEMORY_UI_HIDDEN` 释放可重建界面缓存，用 `ApplicationExitInfo` 回看退出原因。不能访问 lmkd 控制 socket、修改其他进程的 `oom_score_adj` 或 vmscan，也不能保护 page cache 中的指定文件。跨包使用历史需要 `PACKAGE_USAGE_STATS`，并由用户在设置中授予 usage access。
2. **特权 framework 原型：**可在 platform service 中采集获授权的应用切换序列、维护带版本的文件访问记录，并在 launch observer 前后调度预读；评估预测准确率、I/O 干扰与预算。kernel 未改时预读页仍可能按常规策略被回收，因此要区分“读取内容最终被使用”和“页面留存到使用时刻”，也不要用随意降低 adj 来模拟后台存活收益。
3. **产品级 OS：**需同时改 framework 与 kernel，建立启动会话 ID，传递文件或 inode/offset 标识，在 classic LRU 与 MGLRU 路径定义短时保护，并处理文件截断、更新、卸载与 memcg 迁移；进程排序仍限制在 AMS/lmkd 已允许终止的候选内。GKI 下 vendor module 不能替换 core mm 的回收语义；修改 core mm 会带来长期维护内核分叉与合并安全更新的成本。

三类实现都需要退出条件：出现 kernel OOM、SystemUI/Launcher 被杀、导航或音频中断、P99 变差或读放大失控时，停用优化并恢复原生行为。实验固定 cold/hot/warm 口径与分层负载，再用 ablation 分离读取提前、页面保留与进程选择的贡献。

**Q7: [learning] AOSP 的 Game Mode 给了系统什么信号、没给什么？“游戏事件在 InputDispatcher 有专属优先级”为什么不成立？**

Game Mode 提供的是模式与场景信号，不是输入优先级。用户可选 Standard/Performance/Battery（Android 14 加 Custom），游戏在每次 onResume 重新读取 getGameMode()（可能返回 GAME_MODE_UNSUPPORTED）自行调整画质帧率。厂商干预（backbuffer resize、FPS throttling、ANGLE）面向未适配游戏，不定义输入事件顺序，游戏声明 supportsBatteryGameMode/supportsPerformanceGameMode 后平台会清除此前对该模式施加的干预。GAME_LOADING（Android 13 引入）与 GAME（Android 14 引入）是发给 Power HAL 的电源模式：按 AAOS13 源码核对，GameManagerService 只在 GAME_MODE_PERFORMANCE 时把 isLoading 经 Mode.GAME_LOADING 传给 PowerManager 并带超时限制。Mode.GAME 为 Android 14 引入、本地树未见，A17 口径由“TOP 进程全部是游戏”触发。Power HAL 收到信号后可以提高 CPU 频率或游戏线程调度优先级，但这是性能温控策略，不改窗口路由。

“游戏事件有专属优先级”不成立的直接证据：按 AAOS13 源码核对，本地 frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp 对 GameManager/GAME_MODE 的引用为 0。触摸目标由逻辑显示屏、坐标命中测试、窗口可接收性与触摸序列状态决定，从窗口栈前端向后遍历选首个能接收触点且非 spy window 的窗口，游戏模式不参与该函数。InputTarget.Flags.FOREGROUND 只表示该目标是本次事件的主要前台目标，不表示性能优先级。策略回调 interceptKey/MotionBeforeQueueing 也不接收游戏模式参数。厂商“触控增强”可能改触控采样、驱动、调度、刷新率或游戏工具任意一层，判断某设备是否在 inputflinger 重排事件需要厂商源码差异或跟踪数据。

**Q8: [learning] Media Performance Class 是什么？Android 17 新增的等级为什么打破了“非零值等于 API 级别”的旧假设？**

MPC（Media Performance Class）是兼容性规范（CDD）定义的设备能力下限集合，用一个整数关联编解码器、相机、音频、显示、内存、存储与图形要求，应用运行时读取它选择初始体验档位。它不是通用跑分，也不能替代单项能力查询——声明了高等级的设备仍可能因温度、后台负载或厂商策略波动。旧等级值曾与 API 级别对齐（30/31/33/34/35 对应 Android 11–15），Android 17 CDD 2.2.7 新增 1、10、20 三个低等级与最高等级 37（32、36 未定义），非零值不再都等于某个 VERSION_CODES。

0 的语义是“当前读取路径没有可用声明”：可能是设备未声明、旧系统没有公开字段、Jetpack 或 Play services 的补充读取未返回，不能证明设备低端或某项能力缺失，业务应为 0 选择保守默认值再用运行时 API 逐项开启。MPC 与平台版本分离：OTA 后设备可保留原 MPC，SDK_INT 回答 API 是否存在、MPC 回答能力下限声明、运行时能力查询回答当前设备是否支持某功能、实测数据回答当前负载能否达标——四类信号不能互换。读取字段 Build.VERSION.MEDIA_PERFORMANCE_CLASS 自 Android 12 公开（AAOS13 树的 Build.java 已存在该字段），同一次开机内稳定、OTA 后可能提高。版本边界：Android 17 新等级依据 Android 17 Compatibility Definition 第 2.2.7 节及 MPC supplemental information；AAOS13（CDD 13）语境下合法值仍是 30/31/33。

**Q9: [learning] 用 Jetpack DevicePerformance 读 MPC 有什么兼容性坑？业务分级为什么不能用 mpc >= 34 这类比较？**

兼容风险有两项，受 Jetpack 版本与读取时序影响：

1. **首次读取时序：**`PlayServicesDevicePerformance` 会读取 DataStore 中此前保存的 Play services 结果，并与平台值取较大者；`mediaPerformanceClass` 为 lazy 值。如果首次访问发生在异步更新完成前，本次进程可能继续使用旧值。官方建议在 `Application.onCreate()` 创建一次对象，新值通常供后续进程使用。
2. **低等级识别：**本文核对的 `androidx-main` 实现中，`DefaultDevicePerformance.isPerformanceClassValid()` 仍要求值至少为 `Build.VERSION_CODES.R`（30），可能把 CDD 17 的 1、10、20 视为无效并返回 0；若 Play services 已把低等级写入 DataStore，取较大值的路径又可能保留该等级。该实现随库版本变化，不能直接套到项目依赖。

接入前对项目锁定的 Jetpack 版本做测试，并同时记录平台原始值 `raw_build_mpc` 与库最终值 `resolved_mpc`。本文未将 androidx-main 的行为外推为所有已发布版本。

业务分级不能只用 `>=`，因为等级集合和条款不构成单一、稳定的性能排序；功能要求应按 Android 17 CDD 对应条款显式识别：

1. **HDR 显示：**候选等级为 34、35、37。
2. **后置 RAW：**候选等级为 31、33、34、35、37；31 是首次要求 RAW 的等级。
3. **JPEG_R：**候选等级为 35、37。

未识别值进入 Unknown，不自动套用相邻等级；每项仍要保留运行时能力查询作为必要条件。产品侧的 MediaTier 分组不能反向解释为 CDD 正式名称。

**Q10: [learning] MPC 与运行时能力查询怎样组合决策？值为 0 或低等级的设备怎么设计体验？**

决策按以下五步执行：

1. 用 `SDK_INT` 判断所需 API 是否存在。
2. 用明确识别的 MPC 选择保守、标准或高规格候选。
3. 用运行时能力查询剔除不支持的项：多路视频检查 `getMaxSupportedInstances()` 与性能点，HDR 检查编码器与屏幕能力，相机检查 `CameraCharacteristics` 与输出流组合，低延迟音频检查 AAudio 能力与实测往返延迟。
4. 根据实测耗时、温度、内存压力与失败率调整默认值。
5. 用可远程关闭的功能开关保留快速回退。

这个顺序避免两类配置错误：高 MPC 设备因单项能力不足而失败，以及 MPC 为 0 的设备被无条件关闭原本可用的功能。

低等级不是失败状态，而是规范定义的下限信息：MPC 1 面向轻量媒体与低内存低 I/O 环境，应严格限制缓冲、位图与并发。MPC 10 可把 720p30 与较低并发作为初始候选再查编解码器与相机能力。MPC 20 有更高内存与 1080p 屏幕下限，可采用中等并发。值为 0 或未识别时的保护策略：视频默认单路 720p/1080p30 起步，相机预览与拍照分别选尺寸优先首帧与成功率，滤镜限制中间缓冲并提供关闭入口，转码上传限制并发，缓存预算结合 isLowRamDevice() 与进程内存回收通知，再按线上耗时与失败率逐步开放——避免维护庞大的机型白名单。发布决策按 MPC 与运行时能力交叉观察，不直接上报机型等高基数字段。
