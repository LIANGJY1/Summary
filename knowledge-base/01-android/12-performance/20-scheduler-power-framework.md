# 调度与功耗框架

> 学习资料（文章模式沉淀）。主线：异构 CPU 的 capacity 感知调度与 EAS、DVFS 与 Thermal 的性能约束、后台执行与任务调度、ADPF 反馈框架、端侧 AI Runtime 与 NPU 的能力边界。源文档：android-internals-wiki §5.1《Linux 调度、EAS 与大小核架构》、§5.2《DVFS、Thermal 与 Android 功耗管理》、§5.3《后台执行、任务调度与 App Hibernation》、§5.4《ADPF 自适应性能框架》、§5.5《Android 端侧 AI Runtime 与 NPU 性能边界》；机制按本地 AAOS13 源码（Android 13）核对，与材料 Android 17 语境的差异（内核源码不在本地树、pthread_setaffinity_np、HintSession 新增 API、ThermalManagerService 位置、NpuManager 与 NNAPI 弃用）已标注；JobScheduler pending reason 的 API 级别按材料引用的官方 API 参考转写。车载电源状态机与 CPMS 见 [../framework/Android电源/电源.md](../../../docs/others/framework/Android电源/电源.md)；lmkd 与冻结器对内存压力的治理见 [../06-memory-storage/01-memory-management.md](../06-memory-storage/01-memory-management.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 分析 Perfetto 的 CPU 轨道前，为什么要先区分微架构、调度器 capacity、cpufreq policy 与能量模型性能域？**

这四个概念回答不同问题，且经常重合却不保证一一对应：微架构描述同频下每周期大约完成多少工作（SoC/Arm 资料）；capacity 是内核认为该 CPU 的最大相对算力与当前可用能力（`arch_scale_cpu_capacity()`、调度拓扑、温控或中断压力都会修正它）；cpufreq policy 描述哪些 CPU 共享一套频率控制与可选频点（sysfs 的 `cpufreq/policy*`）；能量模型性能域描述哪组 CPU 共享一张活跃态功耗成本表。两个 CPU 可以共享 policy 但 capacity 不同，宣传中的"中核"也不一定对应独立性能域。

CPU 编号更没有跨设备语义：CPU 7 可能是最高 capacity 的核心，也可能只是某个同构簇的成员；最高频率高也不代表 capacity 高，因为每赫兹工作量可能不同。可靠的识别路径是先读设备拓扑（各 CPU 的 `cpu_capacity`、`cpuinfo_max_freq`、policy 的 `related_cpus`），再与 Perfetto 的频率与调度轨道对照；量产设备可能隐藏这些节点，读不到时应转向设备内核配置或厂商源码，不能用最高频率替代 capacity。

内核调度机制（capacity 感知调度、EAS、EEVDF、schedutil）不在本地 AAOS13 树中（树内无 kernel 源码），本篇相关结论以材料按 Android 17 内核 android17-6.18 核对的官方调度文档为准。

**Q2: [learning] 从 CFS 到 EEVDF，公平调度"记账"和"选人"分别怎么工作？nice 值改变的是什么？**

记账与选择是两层。记账仍是 vruntime：公平调度实体每运行一段实际时间，按 `delta_vruntime ≈ delta_exec × NICE_0_LOAD / weight` 换算为虚拟运行时间增量，nice 越小权重越大、相同运行时间产生的 vruntime 增量越少，长期竞争时获得更高 CPU 份额。选择则从旧 CFS 的"取最小 vruntime"演进为 EEVDF（Linux 6.6 起迁移，材料按 android17-6.18 核对）：先看运行资格（lag ≥ 0，表示按公平份额系统仍欠它 CPU 时间），再在合格实体中选虚拟截止时间最早者，虚拟截止时间由本次请求长度和权重共同决定。

nice 改变的是竞争结果，不是执行速度：它不会让同频同核上的指令变快。两个始终可运行、同层竞争的线程，CPU 份额大致与权重成比例，但实际结果还受 cgroup 层级、负载均衡、UClamp、温控限制与睡眠模式影响。在 Perfetto 的 `sched_slice.priority` 里，fair 类线程常见 100–139（约 `120 + nice`），数值越小权重越高；不要把这套内核编号方向与 RT 用户态 `sched_priority`（1–99，数值越大越高）混在一起。

如果线程大部分时间在等待 Binder、锁、I/O 或 GPU，修改 nice 通常没有收益；提高权重也需相应权限，不等于强制选到高 capacity CPU。

**Q3: [learning] EAS 的选核在什么条件下生效？它管的是不是所有任务迁移？**

EAS（能量感知调度，Linux 5.0 主线）只在满足前提时生效：调度域具备 `SD_ASYM_CPUCAPACITY_FULL` 的异构拓扑、root domain 关联了已注册的能量模型、平台实现了 PELT 频率不变性与 CPU 不变性回调、且调频策略假设 OPP 跟随利用率（官方只推荐 schedutil）。它接管的是 fair 类任务的部分唤醒放置：`select_task_rq_fair()` 在普通唤醒路径且 root domain 未进入 overutilized 时才调用 `find_energy_efficient_cpu()`。

决策不是"遍历所有 CPU 选最省电的"：它从每个性能域筛出有剩余 capacity 的代表候选，与任务上次运行的 `prev_cpu` 比较适配程度和能量增量（放置前后系统活跃态能耗之差），选择能量增量更低者；同步唤醒满足条件时可走快速路径直接复用当前 CPU。root domain 一旦 overutilized（官方描述为利用率超过 capacity 约 80% 的临界点），这次唤醒跳过 EAS，回到基于负载与空闲的选择，此时不能用"EM 选了这个核"解释 trace。

EAS 不负责其余迁移：周期负载均衡、newidle balance、主动均衡与 misfit migration 依据调度域、负载和 capacity 判断，不会为每次迁移做能量估算。

**Q4: [learning] `fits_capacity` 预留了多少余量？温控为什么会让"冷机能装下的任务"变成 misfit？**

材料按 Linux 6.18 核对，`fits_capacity(util, capacity)` 预留约 20% 余量，判断形如 `util × 1280 < capacity × 1024`；所以 util=800 不能算"刚好装进 capacity=800 的 CPU"。这段余量避免任务在临界位置反复迁移，也给突发负载留出空间。UClamp 会进一步参与 `util_fits_cpu()`：`uclamp.min` 表达最低性能点，`uclamp.max` 可让被限幅的任务在较低 capacity CPU 上仍被视为适配。

温控或调频限制使 CPU 达不到原有最高性能时，内核会以 thermal pressure 扣减可用 capacity。于是同一任务冷机时适配、进入热稳态后可能变成 misfit，被负载均衡迁往其他 CPU。这就是持续性能测试必须进入热稳态的原因：只比较冷机前几秒的核心分布，说明不了设备在长期功耗预算下的行为，优化判断要同时覆盖冷机与热稳态。

**Q5: [learning] schedutil 把哪些信号合成为目标频率？"CPU 百分比 × 最高频率"错在哪？**

schedutil 用调度器利用率信号决定 cpufreq policy 的性能需求，输入远不止 CFS 利用率：PELT 的 `util_avg` 与短期估计 `util_est`、运行队列的 UClamp 上下界、I/O 等待增强、RT/DL/IRQ 对可用 capacity 的占用、thermal pressure 修正，以及 sched_ext 启用时的性能目标。合成后的需求再加约 1.25 倍 DVFS 余量并按 UClamp 限幅，最后由驱动解析到 policy 允许的支持频点。"百分比 × 最高频率"漏掉了上述全部输入，也把"估算需求"误当"物理性能曲线"。

两个实用推论：其一，一个 policy 覆盖多个 CPU 时取其中最高需求选频，所以看到某 CPU 负载不高也要查同 policy 的其他成员；其二，更新速率限制 `rate_limit_us` 初值来自驱动的策略转换延迟，不是 Android 统一的固定毫秒值，I/O wait boost 也只针对带 `SCHED_CPUFREQ_IOWAIT` 的唤醒、超过一个调度周期会重置或衰减。Perfetto 的频率事件通常反映内核请求状态而非硬件实测时钟，需要硬件反馈时找 `cpuinfo_cur_freq`、`cpuinfo_avg_freq` 或厂商计数器并说明口径。

**Q6: [learning] 一个线程实际能运行在哪些 CPU 上？为什么"把 RenderThread 绑到大核"不是默认优化？**

线程最终可用的 CPU 是三个集合的交集：在线 CPU ∩ 调度器亲和性掩码（`sched_setaffinity()` 设置）∩ cpuset/cgroup 允许范围。亲和性只能缩小候选范围，无法绕过 cpuset；系统改变 task profile、CPU 下线或温控策略后，即使负载不变，线程也可能被迫迁移。查询用 `/proc/<pid>/task/<tid>/status` 的 `Cpus_allowed_list` 与该文件的 cgroup 归属。接口边界：Android 13 的 bionic 提供 `sched_setaffinity()`，`pthread_setaffinity_np()`/`pthread_getaffinity_np()` 自 API 36 才公开。

绑核把调度器的选择空间收窄到指定 CPU，会让线程无法避开该 CPU 的竞争、降频或温控限制，还可能造成排队与能耗回归；用"CPU 编号大于等于 4 就是大核"判断类型也不可靠，因为簇布局随 SoC 不同。只有当证据证明选核或迁移是瓶颈时，才把它作为有对照组、可回滚的实验手段，验证延迟分位数、相邻线程是否退化与热稳态表现。

**Q7: [learning] Android 用户空间怎么把进程状态翻译成调度约束？task profile、nice 与 UClamp 能互相替代吗？**

不能互替：nice 改变 fair 类的竞争权重，UClamp 约束利用率提示，cpuset/task profile 约束可用 CPU 集合与 cgroup 归属，修改对象与副作用都不同。按 AAOS13 源码核对（`system/core/libprocessgroup/profiles/task_profiles.json`）：`HighEnergySaving` 加入 CPU 控制器 background 组、`HighPerformance` 加入 foreground、`MaxPerformance` 加入 top-app；`ProcessCapacityLow/High/Max` 加入对应 cpuset；聚合配置 `CPUSET_SP_BACKGROUND` 同时组合 HighEnergySaving、ProcessCapacityLow、低 I/O 优先级与高定时器松弛——所以"前台/后台调度组"包含多种资源策略，不能只按目录名推断。

框架侧调用路径按 AAOS13 核对：`OomAdjuster` 决定进程分组后，`android.os.Process` 经 JNI 调用 `SetTaskProfiles()`（线程）或 `SetProcessProfilesCached()`（进程），冻结等路径直接调 `SetProcessProfiles()`。常用优先级常量（`Process.java`）：`THREAD_PRIORITY_BACKGROUND=10`、`FOREGROUND=-2`、`DISPLAY=-4`、`URGENT_DISPLAY=-8`、`AUDIO=-16`、`URGENT_AUDIO=-19`，它们描述请求值，能否设置还受内核权限、服务端检查与 SELinux 限制。UClamp 与 EEVDF 处于不同层面：提高 `uclamp.min` 不保证线程立刻获得 CPU，也不越过实时线程；`oom_score_adj` 服务于低内存终止选择，与 CPU 调度是两套状态。

**Q8: [learning] DVFS 的节能依据是什么？为什么"降频一定省电"不成立？**

DVFS 的物理基础是 CMOS 动态功耗近似式 `P ≈ α × C × V² × f`：电压呈平方关系、频率呈一次关系，而更高频率通常需要更高电压维持时序裕量，所以降频常常伴随降压，节能幅度可能大于单独降频。SoC 以 OPP（工作性能点）提供经过表征的离散频率—电压档位，调频策略计算需求，驱动解析到允许档位，固件与温控还可能进一步约束。

但对短任务，"race to idle"可能更省能：先用高性能档快速完成、更早进入更深空闲态，整段能量反而低于长时间低速运行。讨论续航要用能量 `E = ∫P(t)dt` 而非瞬时功率，结论以同一工作量下的能量与完成时间为准。另外通用 Android 接口不承诺支持欠压；高频端能效变差，但拐点与幅度依赖具体芯片、温度与封装，不能用固定 GHz 数字概括。

**Q9: [learning] Linux CPUFreq 分几层？`scaling_cur_freq` 是硬件实频吗？**

CPUFreq 分三层：核心层维护 policy、频率上下限与公共接口；governor 根据负载或用户策略计算性能需求；驱动把需求提交给硬件寄存器、固件或性能状态接口。policy 不等于单个 CPU——`/sys/devices/system/cpu/cpufreq/policyN/` 可以包含多个共享频率的 CPU，排查时先读 `related_cpus`、`scaling_driver`、`scaling_governor` 与频率上下限。

`scaling_cur_freq` 通常是最近请求的 P-state 对应频率，不保证是物理时钟读数；`cpuinfo_cur_freq`、`cpuinfo_avg_freq` 只有驱动与硬件支持时才提供。在使用 SCMI、ACPI CPPC 或厂商固件的设备上，内核可能提交抽象性能等级，映射到时钟与电压的方式由平台定义，读一次等级不等于独立测量了物理时钟。写入频率上下限会改变系统行为并可能破坏温控策略，排障先做只读采集，限频 A/B 对照限定在可恢复的工程设备并记录温度、电量与负载。

**Q10: [learning] 从应用持有 WakeLock 到系统挂起，中间经过哪些层？"CPU idle 比例接近 100%"能证明系统已挂起吗？**

不能。CPU idle 是单个 CPU 暂无可运行任务、进入 cpuidle 状态，其他 CPU 与用户空间仍可工作；system suspend 是全系统低功耗状态，用户空间被冻结、设备被挂起。证明挂起要用 `power/suspend_resume` 跟踪点或 `dumpsys power`，不能用空闲比例或 trace 空白推断。

功耗主路径按 AAOS13 源码核对（`PowerManagerService.java`）：应用 `acquire()` 经 Binder 到 PMS，PMS 把满足条件的 Framework WakeLock 汇总到 `mWakeLockSuspendBlocker`（另有显示 blocker 与启动期 blocker），通过 `nativeAcquireSuspendBlocker()`/`nativeReleaseSuspendBlocker()` 持有原生 blocker，`nativeSetAutoSuspend()` 决定是否允许自动挂起；JNI 连接 `ISystemSuspend`，没有有效 blocker 时内核才尝试进入 system suspend。应用 WakeLock 与内核 wakeup source 关联但不是同一对象——硬件驱动可独立注册 wakeup source，系统服务也可能代表应用持锁，归因要结合 WorkSource、UID 与时间线。

**Q11: [learning] 使用 PARTIAL_WAKE_LOCK 的正确姿势是什么？哪些表现说明锁被滥用了？**

普通应用最常用 `PARTIAL_WAKE_LOCK`（保持 CPU 运行、屏幕可关闭）。安全写法是短作用域加双重保护：`acquire(timeout)` 限制最长持锁时间，`finally` 中判断 `isHeld` 后 `release()`，覆盖正常与异常路径；需要 `android.permission.WAKE_LOCK`。引用计数默认按 acquire/release 配对，`setReferenceCounted(false)` 后一次 release 就结束多次 acquire，混用两种规则容易提前释放或泄漏。

滥用模式有四种：忘记释放或异常路径泄漏（业务结束后锁长期活跃）；粒度过大（把网络请求、重试等待、解析全包进同一把锁，把不可控等待纳入持锁区间）；高频短锁（频繁 Alarm、轮询、推送重试反复唤醒 SoC 与无线电，要按 acquire 次数与间隔统计而不只看总时长）；隐式锁（音频、位置、JobScheduler 等系统 API 可能代表应用持锁，看到陌生标签先查 WorkSource 与发起 API）。WakeLock 只约束系统挂起，不能解释 CPU 为什么繁忙；工作能交给 WorkManager、JobScheduler 或播放框架时应让对应 API 管理锁与约束。

**Q12: [learning] Android 的温控为什么分"控制平面"和"报告平面"？ThermalManagerService 与 Thermal status 是什么关系？**

因为"及时降温"和"告知系统与应用"是两类职责。控制平面可以位于硬件、固件、Linux thermal core 或厂商进程：传感器 → 热区/trip 点 → cooling device（限频、热插拔、功率预算、限充等），不必等 Framework 执行；报告平面把设备状态转换成 Android 定义的类型与严重程度：Thermal HAL → ThermalManagerService → 系统监听者与 PowerManager API。一次 severity 变化不对应某个固定的频率上限。

按 AAOS13 源码核对，`ThermalManagerService` 位于 `frameworks/base/services/core/java/com/android/server/power/ThermalManagerService.java`（材料按 Android 17 核对的路径是 `server/power/thermal/`，A17 才迁移）；它缓存 HAL 上报的温度、维护面向公开 API 的整体 thermal status、为 headroom 收集 SKIN 温度，并对 SHUTDOWN 状态发起关机。整体 status 由 SKIN 类型传感器的最高 severity 计算：`PowerManager.getCurrentThermalStatus()` 返回的 NONE 到 SHUTDOWN 描述的是用户体验热状态，不代表"所有传感器最高温度"或"CPU 被限到几 GHz"——芯片或充电域可能已受限而整体 status 仍为 NONE。

**Q13: [learning] `getThermalHeadroom()` 返回的数值怎么解读？应用侧降载策略要满足什么条件？**

它是"距离 SEVERE 热限制的相对余量"，不是剩余 CPU 百分比：1.0 表示当前或预测达到 `THERMAL_STATUS_SEVERE`，数值可以大于 1.0 但没有固定 status 映射，0.0 不对应固定温度或 NONE，设备不支持或调用过密时可能返回 NaN。参数是 0–60 秒的预测范围，主要跟踪 SKIN 一类慢变化传感器，采样高于约每秒一次没有收益；AAOS13 已含该 API（`PowerManager.getThermalHeadroom()`，本地源码核对），API 35 的 `getThermalHeadroomThresholds()` 与 API 36 的 CPU/GPU headroom 在 Android 13 上不存在。

应用降载策略要满足三点：可逆（温度回落能逐步恢复）、有迟滞（进入与退出用不同阈值，避免画质在临界值附近反复切换）、按瓶颈选择（GPU 受限减像素与特效，CPU 受限减模拟、脚本或编码复杂度）。典型分档是 LIGHT 停非关键后台任务、MODERATE 降渲染分辨率与编码档位、SEVERE 及以上降帧率目标并优先保障交互与数据安全——分档动作是产品策略示例，要经设备实测标定。配合 `OnThermalStatusChangedListener` 响应已发生的状态变化，用低频轮询 headroom 提前准备降载。

**Q14: [learning] Power HAL 的 setMode/setBoost 和性能提示会话能保证频率吗？GameManager 的 GAME_LOADING 有什么限制？**

不能保证。Power HAL 是场景提示与厂商策略的接口边界：`setMode()`（如 `INTERACTIVE`、`GAME`、`GAME_LOADING`）与 `setBoost()`（如 `INTERACTION`）进入厂商实现后，可以被映射为 UClamp、cpuset、devfreq、固件投票或私有策略，AOSP 不规定它们必须写入某个 schedutil 参数；`isModeSupported()`/`isBoostSupported()` 查询支持情况，厂商也可以忽略某个提示。它不会向应用承诺具体频率，也不会把 cpuidle 或 schedutil 变成被调用的下游——一次唤醒中退出 idle、升频与交互提示时间相邻，不代表存在固定调用链。

游戏管理路径按 AAOS13 源码核对：`GameManagerService` 在游戏前台状态变化时触发 `Mode.GAME`，`setGameState()` 可触发 `Mode.GAME_LOADING`，源码用 `LOADING_BOOST_MAX_DURATION = 5 * 1000` 限制加载模式最长 5 秒。`setGameMode()` 改变的是用户选择与配置，不等于一次固定时长的升频；WakeLock 的 acquire 没有固定触发 `Boost.INTERACTION` 的通用链路，两者不要混写。

**Q15: [learning] Doze、App Standby Bucket 与用户"后台受限"分别约束什么？待机桶有哪些档位？**

三者作用对象不同：Doze 看设备整体状态（灭屏、静止、未充电），把网络、Job、Sync 与普通 Alarm 推迟到维护窗口，并忽略普通应用的 WakeLock；App Standby Bucket 看单个应用的活跃度，决定 job、alarm 与网络预算；设置里的"后台受限"是用户明确禁止后台活动，与预测得到的桶是不同状态。三者可以叠加，一次 Job 延迟可能同时受 Doze、桶位、配额与网络约束。

按 AAOS13 源码核对（`UsageStatsManager.java`），桶常量为 `STANDBY_BUCKET_ACTIVE = 10`、`WORKING_SET = 20`、`FREQUENT = 30`、`RARE = 40`、`RESTRICTED = 45`、内部桶 `NEVER = 50`（安装后未启动）。桶是资源控制器的共同输入而非执行器：JobScheduler 的 QuotaController、AlarmManager、NetworkPolicyManagerService 各自消费这份状态。官方 power-details 页给的基线是电池供电、无豁免时成立——例如 Rare 桶 job 每 24 小时最多 10 分钟、Restricted 桶 job 每天一次最多 10 分钟，充电时基本不按桶限制；分桶阈值属系统实现且厂商可调，不能把某个天数当稳定规则。观测入口：`adb shell am get-standby-bucket <package>`、`dumpsys deviceidle`、`dumpsys jobscheduler <package>`（注意本条命令在无 jobscheduler 源码的 AAOS13 树上依然可用，只是源码不在本地树）。

**Q16: [learning] JobScheduler 从 schedule() 到 onStartJob() 经过什么？JobService 回调有哪些必须遵守的生命周期约定？**

JobScheduler 面向带条件的可延期工作：应用声明"做什么、需要哪些条件、最晚可以延后多久"，系统结合设备状态与所有应用的请求选择执行窗口，`RESULT_SUCCESS` 只表示系统接受任务，不表示已启动。执行链（材料按 Android 17 的 APEX 源码核对，AAOS13 树未检出 jobscheduler 模块源码，机制自 Android 5.0 起稳定）：`JobSchedulerService.scheduleAsPackage()` → 各 StateController 跟踪约束 → 约束、配额与优先级筛出就绪任务 → `JobConcurrencyManager` 分配执行上下文 → `JobServiceContext` 绑定应用服务 → `onStartJob()` 回调。

生命周期约定：`onStartJob()` 与 `onStopJob()` 都在主线程回调，不能在里面做耗时工作；`onStartJob()` 返回 true 表示工作会继续，完成时必须调用 `jobFinished()`（收到 `onStopJob()` 后不再调用）；返回 true 且不调用 `jobFinished()` 会让系统持续认为任务在运行。`JobServiceContext` 会代为持有 `PARTIAL_WAKE_LOCK` 并在清理时释放，所以 JobService 通常无须自持锁，但这不豁免业务里的长循环与重复网络请求。持久化任务写 `/data/system/job/jobs.xml`，重启后恢复；系统代持的锁也意味着任务时长要自控，否则计入执行窗口与功耗。

**Q17: [learning] WorkManager、JobScheduler、UIDT、expedited、FGS 与 exact alarm 怎么选？各有什么 API 版本边界？**

选型先问三个问题：任务是否要跨进程生命周期保留、是否由用户刚刚明确发起、允许延后多久。按序判断：页面存活期的短异步用协程/执行器；可延期、需重试、跨重启用 WorkManager（API 23+ 走 JobScheduler 承载，约束、桶位与配额仍生效）；用户刚触发的重要短任务评估 expedited（API 31 引入，专用配额、允许约束更少、配额不足需定义降级或丢弃策略）；用户发起的长网络传输用 UIDT（API 34 引入 `setUserInitiated(true)`，需权限、网络约束与进度通知）；持续可感知工作用符合类型要求的 FGS；闹钟提醒等精确时刻用 exact alarm（API 31 起需 special access 授权，未授权抛 SecurityException）。

版本边界：pending reason 查询分三档——API 34 单原因、API 36 多原因与 history、API 37 累计统计，Android 13 上没有公开的等待原因查询，只能用 `dumpsys jobscheduler` 与桶位推断；TARE（资源经济实验）存在于 Android 13–14 的 AOSP 源码但为隐藏、默认关闭，Android 15 起删除，Android 13 设备上不应把它当可依赖机制。

**Q18: [learning] App Hibernation 与待机桶、应用归档有什么区别？应用进入休眠后发生了什么、怎么恢复？**

三者相邻但不相同：待机桶按活跃度控制后台资源配额；App Hibernation 面向长期未使用应用，把包置于类似 Force stop 的状态并回收存储；应用归档（API 35 起）移除 APK 保留数据。按 AAOS13 源码核对，休眠状态由 `AppHibernationService`（`system_server`）维护，分为用户级（`setHibernatingForUser`：Force stop + 删除该用户缓存）与全局级（所有用户都超阈值时设置，资源配置开启时删除 dexopt/OAT 产物）两层；"多久未使用、谁该豁免"的策略在可独立更新的 PermissionController 模块（AAOS13 树未检出其源码，材料按 Android 17 核对默认未使用阈值 90 天、检查周期 15 天，可被 DeviceConfig 覆盖）。调试用 `adb shell cmd app_hibernation get-state/set-state`（该命令在 A13 只实现这两个子命令）。

休眠后：后台入口失效（Job、Alarm、推送包括高优先级 FCM 都无法唤醒进程）；权限自动重置是独立步骤——PermissionController 按权限组筛选已授予的用户敏感运行时权限并撤销，退出休眠不会自动恢复，用户要在功能入口重新授权；cache 与可选的 dexopt 产物被删，filesDir/数据库/凭据保留。恢复靠直接或间接用户操作解除 stopped 状态，`PackageManagerService` 随之清除休眠标记并定向发送 `LOCKED_BOOT_COMPLETED`/`BOOT_COMPLETED`（需 `RECEIVE_BOOT_COMPLETED` 声明）；应用应在启动与该广播中共用幂等重建入口，恢复首启可能同时承担冷启动、缓存重建与代码重新优化的成本，不能给出固定延迟。

**Q19: [learning] ADPF 的 HintSession 是反馈回路还是升频开关？Android 13 上能用到哪些 API？**

反馈回路，不是升频开关。应用创建 session 时给出一组线程 TID 与目标工作时长（target work duration），每个周期上报实际时长（actual duration），系统比较两者后尝试调整这组线程的核放置或频率，使后续周期接近目标；公开契约只承诺"尝试调整"，可能维持频率、改 CPU 分配或受热与功耗约束失败。频率没有上升不能证明 session 失效——资源可能已经足够，或瓶颈在 GPU、内存带宽与温控。

按 AAOS13 源码核对，Android 13 的可用面是 `PerformanceHintManager`（API 31 引入）：`createHintSession(tids, targetDurationNanos)`（返回 null 表示设备不支持或 TID 不属于本应用）、`updateTargetWorkDuration()`、`reportActualWorkDuration(long)` 与 `close()`；服务端 `HintManagerService` 位于 `services/core/java/com/android/server/power/hint/`。API 34 的 `setThreads()`、API 35 的 `WorkDuration`（CPU/GPU 分项上报）与 `setPreferPowerEfficiency()`、API 36 的 NDK workload hint 在 Android 13 上不存在——协程漂移导致线程集合失真时，A13 的做法是关闭旧 session 再按当前 TID 重建。

**Q20: [learning] target duration 应该填整帧周期吗？事后上报 actual 能修复已经掉的那一帧吗？**

都不。target 只覆盖 session 纳入的线程组：60 Hz 显示周期约 16.67 ms，但这组线程通常只负责其中一段（例如 6 ms 的 CPU 渲染工作），把 16.67 ms 填进去会让系统高估时间余量；帧率、渲染比例或流水线分工变化后应重新测量并调用 `updateTargetWorkDuration()`，不能只用 `1000 / fps` 生成固定值。

actual duration 是事后反馈，影响的是后续周期：上报发生在工作完成之后，对已经超时的那一帧无能为力。可预测的负载突增应提前调整自身策略（降低画质、延后非关键工作）；session 绑定 Linux TID——Java `Thread.getId()`、协程 ID 都不能替代，TID 要在真正执行工作的线程上取得。上报频率按"每帧或每个任务周期一次"，不为小任务反复创建 session。判断接入效果看 actual 相对 target 的 P50/P90/P95 分布、掉帧区间与同机 A/B 对照，而不是瞬时最高频率。

**Q21: [learning] Performance Hint、CPU/GPU Headroom 与 Thermal 状态三类信号怎么分工？**

三个信号回答不同问题，不能互换：Performance Hint 表达"这组线程希望在多长时间内完成周期性工作"；CPU/GPU Headroom（API 36 的 `SystemHealthManager.getCpuHeadroom()/getGpuHeadroom()`，0–100，Android 13 无）回答"当前计算资源还有多少可授予容量"；Thermal status/headroom 回答"设备已进入什么热限制、接近严重限制的趋势如何"。设备可能热余量尚可但 GPU 已满，也可能 CPU 有余量却因皮肤温度接近阈值需要降级。

使用纪律：headroom 查询是同步 Binder 调用、官方提示可能超过 1 ms，要放在工作线程并遵守最小调用间隔，适合在场景切换或低频调档时读取；HintSession 的 target/actual 每周期上报。CPU 频率未上升时先在同一时间窗对照工作时长、调度、频率与 thermal 数据，再判断是提示未生效还是资源已足够/另有瓶颈；降级决策用三类信号组合触发，并保持滞回与最短保持时间。

**Q22: [learning] Android 上有哪几条端侧推理路径？为什么混用会误判成本？**

四条路径的接口与责任方不同：LiteRT 自带运行时（应用打包 runtime 与模型，`Interpreter`/delegate 或新 `CompiledModel`，CPU/GPU/NPU 看 delegate）；Play services 提供的 LiteRT 接口（运行时随 Play 更新）；AICore/ML Kit GenAI（系统服务管理 Gemini Nano 与模型分发，AICore 包名不属于 AOSP）；NNAPI（NDK API + 系统运行时与厂商驱动）。层次混用会误判初始化成本、内存归属与失败处理——例如 `CompiledModel` 仍是应用内执行路径，不是 AICore 客户端；AICore 的可用性受设备、模型下载与配额影响，与 LiteRT delegate 的失败模式完全不同。

版本边界：NNAPI 自 Android 8.1 引入、API 35（Android 15）起官方标记弃用——Android 13 上 NNAPI 未弃用且是主线模块，"弃用"表示新项目不应再以它为长期入口，不表示既有应用失效；Android 17 的 `FEATURE_NEURAL_PROCESSING_UNIT` 与 NpuManager 调度控制面在 AAOS13 中不存在。无论哪条路径，推理都不应在主线程执行，但仍要防止工作线程挤占主线程与 RenderThread。

**Q23: [learning] 为什么"实验室里最快的推理后端"放进真实页面未必改善体验？**

用户感知的是从输入到 UI 更新的端到端延迟，运行时报告的 kernel 时间只覆盖一段。一次推理包含输入采集、解码缩放归一化、模型与运行时初始化、delegate 分区或编译、输入传输、加速器执行、输出读取与后处理、UI 更新；常见成本有首次准备（打开模型、分配张量、编译子图）、稳态执行、并发干扰、内存压力、热衰减与队列延迟。

两个典型反例：GPU 推理与渲染、相机管线共享资源，GPU 耗时下降不代表页面更流畅；推理线程占满高性能核后，单测模型耗时下降，页面帧时间与整机功耗却可能恶化——线程数要实测而非默认拉满。优化目标应写成可测预算（如"相机预览期间 P95 端到端延迟低于一帧、连续运行十分钟后温度等级可接受"），基准测试至少设 CPU 基线、目标 delegate 无缓存首次创建、缓存命中预热后、与真实 UI/相机并发四组对照，并记录 P50/P95/P99 与温度。

**Q24: [learning] NPU 加速的收益取决于什么？Android 17 的 NPU 声明与调度控制面是做什么的？**

收益取决于模型覆盖与运行时配合：Android 设备的 NPU 没有供普通应用通用的模型图执行 ABI，LiteRT NPU delegate 与厂商 SDK 需适配具体 SoC；要回答哪些子图能进 NPU、首次编译多久、输入输出是否多一次复制、连续运行的温度功耗变化、失败后的回退顺序。AOT（目标 SoC 已知时预生成产物）与 JIT（设备侧编译）按厂商支持程度不同；部分委托把不支持的子图留给 CPU/GPU，子图过多时跨分区同步与张量转换可能抵消加速收益。

Android 17 增加了控制面（按材料核对，AAOS13 中不存在）：`PackageManager.FEATURE_NEURAL_PROCESSING_UNIT`（字符串 `android.hardware.npu`）标识设备声明 NPU 能力，目标 API 37 且直接访问 NPU 的应用需在 manifest 声明（提供 CPU/GPU 回退时用 `required="false"` 兼顾安装范围）；AOSP 新增 NpuManager 模块与 `hardware/interfaces/npu/aidl/`，传递 UID、优先级（数值小者优先）、直接访问资格与工作生命周期——它管理资格与调度信息，不定义模型计算，`@SystemApi` 不面向普通应用。`hasSystemFeature()` 只是前置条件，不能检测模型兼容性；确认真实使用加速器要有 delegate 分区记录、厂商 profiler 或编译日志等直接证据。

**Q25: [learning] 端侧推理的内存由哪些部分组成？"模型文件 100 MB 所以最多占 100 MB"错在哪？**

推理内存至少包括：模型映射（FlatBuffer 与权重文件页）、权重转换（delegate 打包、重排、量化展开或编译缓存，首次 prepare 后可能持续驻留）、tensor（输入输出、中间激活、KV cache，随 shape 与 batch 变化）、workspace（CPU scratch 或 GPU/NPU 临时缓冲，可能在 native heap、驱动或 dma-buf）、输入输出管线（Bitmap、YUV/RGB 转换、纹理）以及服务进程（AICore 等跨进程 runtime 的内存不在应用自身堆内）。

"文件 100 MB"只描述压缩后的模型文件：映射驻留按页增长、delegate 可能生成编译产物或权重副本、运行时预分配 tensor arena 与工作区、多会话并发叠加，峰值可达数倍文件大小。观测要分阶段打点：模型打开前、runtime 创建后、首次执行后、稳定运行后、释放后与内存压力时；应用侧只记录到调用端分配，系统托管路径（AICore）的模型权重与服务侧工作不计入调用方 PSS——判断整机压力要同时看相关系统进程、dma-buf 与 `MemAvailable`。释放后 RSS 不立即下降，要先区分分配器留存、文件页缓存与仍被引用的 tensor，重复"创建—运行—释放—施压"周期确认是否有无上限增长。

**Q26: [learning] Android 17 的 cgroup 是纯 v2 统一层级吗？一个进程的 CPU 组和冻结组分别写在哪里？**

不是。AOSP `android-17.0.0_r1` 的默认拓扑仍是 v1/v2 混合：CPU 调度组在 v1 `/dev/cpuctl`（background、foreground、top-app、rt 等）、CPU 集合在 v1 `/dev/cpuset`（noprefix，文件名是 `cpus`/`mems`）、块 I/O 在 v1 `/dev/blkio`；cgroup v2 的 `/sys/fs/cgroup` 提供 freezer 与可选 memory，并按 `/sys/fs/cgroup/{apps|system}/uid_<uid>/pid_<pid>` 建立 per-process 目录（以 `AID_APP_START` 分界选择 apps 或 system）。同一进程可以同时属于多棵树。

- `cgroups.json` 定义控制器版本与挂载点；v2 memory 标记 `NeedsActivation`、`MaxActivationDepth` 3、`Optional`，由 init 写 `+memory` 到 `cgroup.subtree_control` 激活。
- `cgroup.freeze` 是 v2 核心接口，不通过 `+freezer` 写入 `subtree_control`；AOSP 把它命名为 freezer controller 是为了让 task profile 经统一抽象找到文件。
- 看到 `/proc/<pid>/cgroup` 中的 `0::/apps/...`，不能推断 CPU 与 cpuset 也已迁入 v2，必须结合 mountinfo 或设备上的 `cgroups.json` 解释；厂商可覆盖控制器版本与挂载点，结论以设备实际配置为准。

**Q27: [learning] libprocessgroup 和 task profile 把什么抽象掉了？`SetClamps` 为什么不能用？**

libprocessgroup 用两份 JSON（`cgroups.json` 定义控制器与挂载点；`task_profiles.json` 定义 attribute、action 与 aggregate profile）把"后台/前台/top-app/冻结"等意图翻译成具体 cgroup 文件操作，framework 与 native 服务只表达意图、不接触路径。Android 17 支持的 action 包括 `JoinCgroup`、`SetAttribute`、`WriteFile`、`SetTimerSlack`、`SetSchedulerPolicy`、`Compact` 与按序执行的 aggregate profile；`SetClamps` 不在支持列表中——UClamp 通过 `UClampMin`/`UClampMax` 等 attribute 表达，而不是独立 action。

- 加载顺序：系统默认 → 产品首发 API 级别文件 → vendor → task profile 另读 system_ext；同名定义由后加载者覆盖。分析 OEM 设备必须看设备上的实际 JSON 与 cgroupfs，不能从 AOSP profile 名推断厂商参数。
- 典型 profile 映射：`HighEnergySaving` → `/dev/cpuctl/background`；`MaxPerformance` → `/dev/cpuctl/top-app`；`ProcessCapacityMax` → `/dev/cpuset/top-app`；`Frozen`/`Unfrozen` → per-process `cgroup.freeze` 写 1/0。
- `SCHED_SP_TOP_APP` 与 `CPUSET_SP_TOP_APP` 的差别说明"调度组"与"调度组+cpuset"是两个入口：只设 scheduling group 的入口不会自动修改 cpuset。
- init service 的 `task_profiles` 指令（Android 12 起替代 `writepid` 旧写法）在子进程 exec 前应用 profile。

**Q28: [learning] framework 的 schedGroup 如何落到 cgroup？为什么短时间内可能看到新 schedGroup 与旧 cgroup 路径并存？**

`OomAdjuster.applyResultsLSP()` 把调度组映射为 `THREAD_GROUP_*`（BACKGROUND/RESTRICTED/DEFAULT/TOP_APP/FOREGROUND_WINDOW），再经 `mProcessGroupHandler` 异步发送组变更，由回调线程调用 libprocessgroup 应用 profile——profile 文件写入不在 OomAdjuster 的计算循环内执行。状态字段先更新、cgroup 迁移随后完成，因此短时间观察可能同时看到新 schedGroup 与旧 cgroup 路径，且没有固定的迁移窗口长度。

- schedGroup 不代表固定 CPU 份额：具体用哪些控制器、uclamp 与 cpuset 由设备 task profile 和 libprocessgroup 配置决定；不能假定某个 `/dev/cpuctl` 路径与固定百分比对所有设备成立。
- UClamp 限定调度器利用率的上下界，影响选核与调频决策输入，不等于锁频也不保证迁移到大核；线程实际可用 CPU 是 thread affinity 与有效 cpuset 的交集，排查时要同时看 `Cpus_allowed_list`、cpuset 层级、温控与调度 trace。
- `cpu.weight`/`cpu.max` 是 cgroup v2 CPU 接口；Android 17 AOSP 默认 CPU 控制器在 v1 `/dev/cpuctl`，这两个文件不能用来解释平台基线。

**Q29: [learning] SoC 型号能预测流畅度吗？跨 SoC 分析要把哪三层证据分开，CPU 分析的四个概念怎样区分？**

不能——SoC 型号只给出硬件拓扑起点，峰值算力不会自动转成帧稳定性，散热、内存配置、内核与厂商策略都会改变结果。三层证据要分开：IP 与 SoC 规格（厂商公开的 CPU/GPU/NPU/ISP 能力，回答硬件提供了什么）、整机实现（OEM 选的内存颗粒、频率表、散热方案、内核与驱动，决定能力如何工作）、当前负载（温度、电量模式、刷新率与并发，决定一次 trace 捕获到什么）。三者不能互相替代：产品页证明不了持续频率，单次 Perfetto 也证明不了架构上限。

CPU 的四个概念：指令集描述软件可用指令（如 Armv9.x）；微架构描述核心如何取指、乱序执行与访问缓存（如 Oryon、C1-Pro）；拓扑描述核心数量、共享缓存与 cpufreq policy 的组织；策略描述调度器、调频器、Power HAL 与厂商服务如何使用硬件。两个核心同为 Armv9 不代表 IPC 与能耗相同；两个核心在同一 policy 也不代表共享缓存。实用上先读设备拓扑（/sys/devices/system/cpu/possible、各 policy 的 related_cpus/scaling_driver/scaling_governor、GPU 驱动与内核版本），部分 user build 会隐藏节点，缺项记为"设备未公开"，不要凭 SoC 名称补值。

**Q30: [learning] 厂商内核 hook 与 EAS 是什么关系？一段"多核同时升频"的 trace 怎样查证来源？**

材料按 Android 17 内核（android17-6.18）核对：公平调度类用 EEVDF，EAS 在 root domain 未进入 overutilized 时经 find_energy_efficient_cpu() 选核，而 select_task_rq_fair() 会先执行 Android vendor hook（android_rvh_select_task_rq_fair），hook 给出非负 CPU 时直接采用——厂商模块可以在选核路径注入产品策略，但 hook 本身不给出策略内容，确认注册了什么实现需要厂商模块源码、符号、trace 或设备实验。schedutil 依据利用率请求频率，输入包括 PELT 利用率、uclamp、iowait boost、thermal pressure，以及 sched_ext 启用时的性能目标； Energy Model 不记录任务迁移造成的私有缓存局部性损失，"EAS 选了这个核"不能解读为内核已精确计算缓存迁移成本。

一段升频 trace 的查证顺序：用 sched 与 thread_state 判断线程是运行、可运行还是阻塞；对齐 CPU 频率、空闲状态、温控状态与电源模式；检查关键线程所属 cgroup、task profile 与 uclamp；在 userdebug 或厂商调试环境追踪 Power HAL 性能提示与厂商事件；用固定脚本重复冷机、热机与不同电源模式。RPMh、DCVS、Perflock 这类品牌词不能互换使用，无厂商标记时结论停在"观测到频率与负载不成比例"，不给私有机制命名——线程并发、共享 policy、温控恢复与 HAL 提示都可能形成相似曲线。

**Q31: [learning] MUSCHED 的 VIP 是什么机制？它证明了什么，又有哪些不能外推成 Android 17 默认行为？**

MUSCHED 是荣耀面向移动交互负载的语义感知调度框架（2024 年 1 月量产、2026 年 OSDI 论文），核心是给交互关键任务打临时 VIP 标签：用户空间按启动、滑动、动画等场景与线程角色更新 BPF Map 中的 VIP 候选与生存时间，内核侧维护每 CPU 的 FIFO VIP 队列——单次时间片 3 ms，Audio/Video/WebView/Display 的累计预算分别为 20/10/120/20 ms，预算耗尽撤销 VIP，有效顺序为 RT > VIP > CFS。它还处理依赖传播：VIP 等待者被普通线程持锁阻塞时，把 VIP 标签临时传给锁持有者，锁释放或超过生存时间后撤销；Binder 同步事务上传播 VIP，并周期性把等待超过 4 ms 的 VIP 推向空闲 CPU。

它证明了语义标注、有界预算与依赖传播可在量产组合：论文实验室数据（Magic7、Android 15、Linux 6.6）报告 10 个应用各 100 次冷启动平均降 14.8%、VIP 任务睡眠时间降 71.8%；自 2024 年起的量产统计报告动画、滑动与启动异常次数下降。但不能外推为 Android 17 默认能力：VIP 不是上游新增的 sched_class，Android 17 没有 SCHED_VIP；论文未公开 full/partial 接管模式、私有 kfunc、引用计数与环检测细节；Binder 默认路径不会自动复制 VIP 标签，oneway、嵌套调用与线程池复用要单独验证；3 ms 时间片与 4 ms 阈值是该样机与 120 Hz 场景的调优点。对应用团队的可行路径是公开机制：减少关键窗口的 runnable 竞争与持锁、用 ADPF 与 Game State 表达负载，而不是寻找不存在的 VIP SDK。

**Q32: [learning] Power HAL 的 Mode/Boost 与 Hint Session 是两条什么路径？应用能直接调用 setMode 吗？**

Power HAL 是 framework 与厂商电源实现之间的 AIDL 接口（材料按 Android 17 冻结的 power AIDL v7 核对；本地 AAOS13 树的 hardware/interfaces 只包含 automotive，power AIDL 未包含于本地树，接口版本按材料口径转写）。两条入口职责不同：Mode/Boost 由系统场景或特权调用触发，PowerManagerService 把交互、亮灭屏、省电、游戏等状态经 setMode/setBoost 交给厂商实现（A17 口径 Mode 19 个、Boost 6 个），setMode 是 oneway 调用、调用方不等待结果，Binder 对外方法要求 DEVICE_POWER 权限，省电模式还会过滤 LAUNCH 启动加速；Hint Session 由应用的 PerformanceHintManager 请求进入 HintManagerService，校验 UID/TGID/TID 与进程状态后交给 IPowerHintSession，线程组持续报告目标时长与实际时长，v5 起可用可选 FMQ 通道减少高频报告的 Binder 往返。

应用不能直接调 setMode/setBoost——那是 framework 到厂商 HAL 的接口，不是 SDK；setPowerSaveModeEnabled() 是需要 DEVICE_POWER 或 POWER_SAVER 权限的隐藏 @SystemApi。厂商收到提示后可以调 CPU/GPU/内存总线或空闲策略，也可以按本机策略忽略：接口枚举存在不代表设备实现了对应动作，要用 isModeSupported()/isBoostSupported() 查询；"LAUNCH 一定触发某厂商硬件请求通道"这类固定映射需要目标设备 HAL 源码、厂商 trace 标记或寄存器级证据才能成立。AOSP 也没有定义 Power HAL 直接调用内核调度器辅助函数的通用路径。

**Q33: [learning] schedutil 怎么决定 CPU 频率？"利用率 × 最高频率"错在哪？频率上不去时应检查什么？**

schedutil 用调度器利用率信号合成频率需求，输入远不止 fair 类利用率：PELT 的 util_avg 与短期估计 util_est、uclamp 上下界、iowait boost、RT/DL/IRQ 对可用 capacity 的占用、thermal pressure，以及 sched_ext 启用时的性能目标——full 接管时只按 SCX 目标起算、partial 时 SCX 目标加 fair 利用率；合成需求加约 1.25 倍余量并按 uclamp 限幅，再由驱动解析到 policy 支持的频点。"百分比 × 最高频率"漏掉了全部输入，还把估算需求误当成物理性能曲线。

细节边界：一个 policy 覆盖多个 CPU 时取处理后的最大利用率，给某 CPU 写 target 不代表硬件只改变这个 CPU；频率更新受 freq_update_delay_ns 速率限制（初值来自 cpufreq_policy_transition_delay_us），没有跨设备统一的固定毫秒值，支持 fast switch 时直接更新否则由工作线程延后。频率上不去的排查顺序：确认 Mode/Boost/Hint 的发起方、权限与支持结果；对齐 runnable 任务、SCX 状态与 switch_all；检查更新是否被速率限制或目标未变拦截；查 policy 频率上下限、温控约束与驱动频率表；最后把驱动请求、实际频率、任务完成时间、帧性能与温度放到同一时间轴。机制细节见调度与功耗框架篇，本篇强调 OEM 视角：schedutil 是否为量产配置、policy 划分、OPP 表与热限频都因设备而异，通用内核源码不足以还原产品配置。

**Q34: [learning] PerformanceHintManager 的 Hint Session 怎么用？thermal headroom 的数值怎么读才不会误判？**

Hint Session 表达周期性工作目标：用属于本进程的长期存活线程组创建 session，给出目标时长（从该线程组负责的一轮工作预算出发，不要机械照搬整帧 16.67 ms），每周期调用 reportActualWorkDuration()，目标变化时 updateTargetWorkDuration()，结束调用 close()；createHintSession() 可能返回 null（设备不支持或线程不属于当前进程），API 34 起线程集合变化可用 setThreads()。它传递的是工作目标，不承诺绑核、固定频率或避免温控降频——资源是否响应要靠设备数据确认；计时用与 uptimeNanos 一致的时钟基准，GPU 完成时间不能混进 CPU 工作时长。评估做只改会话开关的 A/B，观察线程 running/runnable/sleeping、频率、FrameTimeline 与功耗。

thermal headroom 是归一化热压力指标：PowerManager.getThermalHeadroom(forecastSeconds)（API 30 起，参数 0–60 秒）返回值越高表示越接近严重温控阈值，1.0 对应 THERMAL_STATUS_SEVERE、可以大于 1.0，不是摄氏度；设备不支持或调用过快返回 NaN，慢变化传感器约一秒更新一次，更高频轮询没有收益。API 35 的 getThermalHeadroomThresholds() 读取设备为各温控状态定义的阈值映射，API 36 起可能运行时变化并可用 addThermalHeadroomListener() 监听；跨机比较原始 headroom 数字要保留传感器与阈值模型差异。CPU/GPU headroom（SystemHealthManager，API 36 起）是另一套：结果范围 0–100、至少一次同步 Binder 事务可能超过 1 ms，不要在渲染或音频线程等待，最小查询间隔与计算窗口由设备报告。应用纪律：信号只用于渐进调整负载并加滞回与最短保持时间，不写 sysfs、不绑核、不依赖厂商私有性能服务。

**Q35: [learning] PowerStats HAL 报告哪三组数据？为什么"dumpsys powerstats 能列出对象"不等于设备能做应用耗电归因？**

PowerStats AIDL（材料按 Android 17 冻结 v2 核对）的三组数据：状态驻留（PowerEntity/StateResidencyResult，各状态自开机的累计时长、进入次数与最近进入时间）、组件能量（EnergyConsumer/Result，累计能量，可选 EnergyConsumerAttribution[] 做 UID 分摊，分摊总和不得超过组件累计能量）、计量通道（Channel/EnergyMeasurement，常对应一条电源 rail，能量单位 µW·s、时间戳用 CLOCK_BOOTTIME）。接口只约定格式，覆盖范围由设备决定：AOSP 默认实现注册的是虚构 rail 与 consumer、仅供联调；厂商只接少量 rail 时 dumpsys 仍会列出对象但覆盖有限；没提供 attribution 数组时该组件就没有 UID 分摊；framework 连接 AIDL v2 失败会回退 HIDL 1.0，后者不提供 EnergyConsumer。

公开 Power Monitor API（API 35 起）更受限：只公开 Channel/EnergyConsumer 累计值，不公开状态驻留与 UID 分摊；无 ACCESS_FINE_POWER_MONITORS（signature|privileged|development 级）时缓存最长 20 秒且 granularity 为 UNSPECIFIED，有权限时 250 毫秒、FINE；公开读数还按调用 UID 混入随机扰动，monitor index 重启后不保证稳定。评估设备能力可按 L0–L4 分级（无可用数据/仅计量通道/组件能量/UID 分摊/测量链经外部仪器验证），跨设备比较先保存对象清单、用累计值差分、统一时间轴并把负差值与回绕区分开——把 L1 与 L3 设备混进同一张应用耗电榜，会把"没有 UID 数据"误读成"应用耗电较低"。测量前还要分清功率（W）、能量（J/Wh）与电池电流（mA）三个物理量，比较"完成同一任务谁省电"优先用任务总能量并给出耗时与置信区间。

```bash
adb shell dumpsys powerstats
adb exec-out dumpsys powerstats --proto meter > powerstats-meter.pb
```
