# Perfetto 进阶：Profile 火焰图、CPU 频率、BufferQueue、Agent 协议、SDK 与 FrameTimeline

> 学习资料（文章模式沉淀）。主线：把 Perfetto 的进阶分析能力——CPU profile 导入与火焰图、CPU 频率与调度关联、BufferQueue 阻塞识别、Agent 调查协议、应用内 SDK 埋点、FrameTracer 与 FrameTimeline 帧分析——沉淀为可复述的 Q&A。源文档：android-internals-wiki §14.8《Perfetto Profile 导入与 Flamegraph 分析》、§14.9《Perfetto CPU 频率与 DVFS 关联分析》、§14.10《BufferQueue 阻塞的 Perfetto 识别》、§14.11《Agent 辅助 Perfetto 分析协议》、§14.12《Perfetto SDK 与应用内 Trace 数据源》、§14.13《FrameTracer 与 FrameTimeline 分析》。材料按 Android 17 / Perfetto v57.2 撰写；系统侧机制按本地 AAOS13 源码（Android 13）核对并标注版本差异（BufferQueue 等待机制、FrameTracer 发射集合、jank 枚举、app.te 的 perfetto_producer 门控、API 33 FrameData）；Perfetto 工具链与 SDK、simpleperf、内核源码不在本地树，按材料口径转写；FrameTimeline 的 SurfaceView 支持边界、Expected/Actual 语义与 Android 12 下限已与 perfetto.dev 官方文档核对。CPU 调度与功耗的机制层见 [../cpu-power/01-调度与功耗框架.md](../08-cpu-power/01-调度与功耗框架.md)；BufferQueue、fence 与合成的机制层见 [../rendering/02-GPU合成与显示管线.md](../02-rendering/02-GPU合成与显示管线.md)；Perfetto 采集、UI 与 SQL 基础见 [./01-Perfetto-采集与SQL分析.md](./01-Perfetto-采集与SQL分析.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 用 Perfetto 分析卡顿时，原生 system trace、pprof、Simpleperf protobuf 与 linux.perf 四种输入在 Trace Processor 中各落在哪些表，能各自证明什么？**

四种入口写入不同的表，SQL 不能混用同一套表名；只有同一次联合录制，调用栈采样才能与帧、调度、Binder 共用一条时间轴。按材料核对的 Android 17 Trace Processor 口径（工具链不在本地 AAOS13 树）：

- **原生 system trace**：写 `slice`、`sched`、`thread_state`、FrameTimeline 与计数器，有事件时间、时长和线程状态；未启用调用栈采样时给不出函数热点。
- **pprof**：写聚合表（`__intrinsic_aggregate_profile` 等，`__intrinsic_` 前缀是内部表名，不当跨版本稳定接口），只有调用树与每条栈的聚合值，没有逐样本时间轴，不能把一条调用栈放回某个具体帧。
- **Simpleperf protobuf**：写 `cpu_profile_stack_sample`，样本带时间戳、线程与调用栈；导入器忽略上下文切换记录，文件里没有 Running、Runnable 或休眠证据。
- **linux.perf**：写 `perf_sample`，可与 ftrace、FrameTimeline 联合录制在同一 trace 时钟下；采样命中数不能换算成单次函数耗时。

诊断分工：FrameTimeline、`slice` 与 `thread_state` 界定异常区间，区间内的 `perf_sample` 回答正在消耗 CPU 的调用路径；聚合 profile 适合快速找热点，但仍需调度数据确认目标线程得到多少 CPU、是否被抢占。全局火焰图不能代替帧级因果链。

**Q2: 用 Simpleperf 生成 Perfetto 可导入的 protobuf 时，为什么漏掉 --show-callchain 之后火焰图只剩叶节点？**

因为 `report-sample` 在未指定 `--show-callchain` 时会把调用链地址数组裁成一个元素，导入后每个样本只剩一帧栈，火焰图宽度全部堆在叶节点上（按材料核对的 Android 17 `cmd_report_sample.cpp` 口径转写，simpleperf 源码不在本地树）。完整数据流三步：`app_profiler.py` 在设备上录制二进制采样文件 `perf.data`，`report-sample --protobuf` 把它转成 Perfetto 可识别的 protobuf，Trace Processor 导入为 `cpu_profile_stack_sample`。

关键参数：`-g`（录制选项内）要求采集调用链；`--show-callchain` 要求导出完整链；`--symdir` 指向符号目录并递归查找带符号文件；R8 混淆应用还要传与该 APK 同构建的 `--proguard-mapping-file`。导入检查可以先按进程与线程统计样本覆盖：

```sql
SELECT thread.name AS thread_name, COUNT(*) AS sample_count,
       MIN(sample.ts) AS first_ts, MAX(sample.ts) AS last_ts
FROM cpu_profile_stack_sample AS sample
JOIN thread USING (utid)
GROUP BY thread.utid ORDER BY sample_count DESC;
```

结果能暴露包名选错、目标线程没被采到或录制区间过短；样本数是采样命中计数，不能解释成函数执行毫秒数。

**Q3: 为什么"trace 文件能否被新版 Perfetto 打开"和"设备能否录到 linux.perf"是两条独立版本线？Android 13 设备怎么录 CPU 采样？**

主机分析端与设备采集端的版本各自演进：Trace Processor 的格式支持（pprof 导入、TrackEvent 附栈等 v53/v54 能力）随新版升级，新 UI 能解析旧 trace；而 `linux.perf` 采集依赖设备侧 traced 与内核 perf 事件支持，按材料口径，Perfetto 官方采集文档把 Android 设备下限标为 Android 15，量产 `user` 构建还要求目标应用声明 `profileable` 或 `debuggable`。Android 13 设备的 traced 没有该数据源，CPU 采样走 Simpleperf protobuf 导入路径（Android 10–17 适用），只在主机端用新版 Trace Processor 打开。

`linux.perf` 配置要点：`perf_event_config` 的 timebase 指定计数器、频率与时钟（如 `SW_CPU_CLOCK`、`frequency: 100`、`PERF_CLOCK_MONOTONIC`），`callstack_sampling` 用 `target_cmdline` 限定目标进程并可开 `kernel_frames`；采集端为样本请求 TID、时间与计数值，用户态展开需再请求用户寄存器与栈内存，对应 `PERF_SAMPLE_TID`、`PERF_SAMPLE_TIME`、`PERF_SAMPLE_CALLCHAIN`、`PERF_SAMPLE_REGS_USER`、`PERF_SAMPLE_STACK_USER` 等 ABI 标志（按材料核对的 Android 17 event_config 与内核 6.18 头文件口径；内核不在本地树）。录制开销也是证据的一部分：采样频率、核数、Java/JIT 代码展开与内核帧都会增加采集端负担，结论应记录这些条件。导入后用 `perf_sample` 覆盖查询（样本数、首末时间、`unwind_error` 计数）加 `stats` 表 `name GLOB 'perf_*'` 的非零项检查展开与丢样问题。

**Q4: 火焰图里一个调度函数 cumulative 很宽而 self 很窄，说明什么？火焰图宽度能当执行时长读吗？**

不能当执行时长。火焰图横向宽度是当前所选指标的聚合值：周期性 `linux.perf` 样本常见是样本数，pprof 可能是 CPU 纳秒或分配字节，Collapsed Stack 是行尾计数；单次持续时间需要 `slice.dur`、方法跟踪或其他区间证据。两个值的含义：

- **self**：该函数位于采样调用栈叶节点的数量或指标值，表示采样经常中断在它内部；
- **cumulative**：该函数出现在调用路径任意层级时的累计值。

调度函数 cumulative 宽而 self 窄，说明大量热点经过它进入不同子路径；只有叶函数 self 宽才表示执行集中在该函数本身。排名受采样频率、采样时刻是否与周期性工作对齐、线程运行份额和展开质量影响，是统计证据而非精确计时。

v53 起 TrackEvent 的 slice 或 instant event 可以附带调用栈：点选单个事件时详情面板展示该事件的栈，区域选择把区间内事件栈聚合成火焰图；默认每个栈计 1，事件带 `callstack_weight` 时按权重聚合，且只统计实际带值的事件。TrackEvent 栈表达"事件记录时附带的调用关系"，`perf_sample` 表达"采样中断时 CPU 上的调用关系"，可以交叉验证但不是同一种采样来源。选区分析要同时限定时间（异常帧、启动阶段、Binder 往返）、线程（主线程、RenderThread、Binder 线程）、场景（冷热启动、首帧与滚动不混聚）与指标（样本数、CPU 时间、分配字节要写明）。

**Q5: 录好的 trace 里函数名是地址或混淆名，怎么补 native 符号与 R8 映射？**

可读函数名依赖采集产物与构建产物匹配：native 栈需要正确的 ELF、Build ID 和展开信息；Java/Kotlin 混淆栈需要与被测 APK 同一次构建生成的 `mapping.txt`。路径里有同名 `.so` 但 Build ID 不匹配时，不能用它证明线上地址对应某个函数。内联函数会让一个机器码地址对应多层源码调用关系，v53 起 UI 能标出 inline frame，分析应保留这些层级；缺少 DWARF 内联记录时，"火焰图里没有某函数名"推不出"该函数未执行"。

对已录好的原生 trace，按材料核对的 Android 17 流程有两条：

```bash
PERFETTO_BINARY_PATH="$ANDROID_PRODUCT_OUT/symbols" \
traceconv symbolize raw-trace.perfetto-trace > symbols.pb
PERFETTO_PROGUARD_MAP="com.android.settings=$R8_MAPPING_FILE" \
traceconv deobfuscate raw-trace.perfetto-trace > deobfuscation.pb
cat raw-trace.perfetto-trace symbols.pb deobfuscation.pb > enriched-trace.perfetto-trace
```

`symbols.pb` 与 `deobfuscation.pb` 是额外的 TracePacket 流，拼接后才得到可直接打开的 trace；`PERFETTO_PROGUARD_MAP` 格式固定为 `包名=映射文件`，多个包用冒号分隔，`R8_MAPPING_FILE` 必须来自被测 APK 的同一次构建。另一种交付方式 `traceconv bundle` 输出 TAR 归档（内含 `trace.perfetto`）：Android 17 固定源码中的 bundle 选项没有 `--proguard-map`，映射仍走环境变量；当前 v57.2 已支持可重复传入的 `--proguard-map` 并成为官方推荐，手工拼接方式主要用于兼容旧流水线（版本口径按材料转写）。

**Q6: Perfetto 里 CPU 频率数据从哪三条采集路径来？频率轨道开头留白说明什么？**

三条路径互补（配置项按材料核对的 Android 17 Perfetto 文档口径）：

- **事件驱动**：`linux.ftrace` 抓 `power/cpu_frequency`（频率切换时刻）、`power/cpu_frequency_limits`（policy 最小最大频率约束更新）与 `power/cpu_idle`；
- **轮询快照**：`linux.sys_stats` 用 `cpufreq_period_ms` 周期读取 sysfs 频率值；
- **一次性系统信息**：`linux.system_info` 在采集开始时读取可用频点。

三条路径都可能因驱动、内核配置、权限或 sysfs 导出差异而缺失，采集后先查数据完整性。轨道开头留白只说明该 CPU 在 trace 开始后没有发生频率切换、事件驱动没有记录，不能解释为 CPU 停止运行；轮询能补快照，却会漏掉采样点之间的瞬时切换，面向启动和掉帧的分析要保留事件驱动采集。

`power/cpu_frequency_limits` 值得与频率事件一起抓：它记录 `min_freq`、`max_freq` 与 policy 代表 CPU（Android 17 内核行为，材料口径；内核源码不在本地树），能区分"governor 没有请求更高频率"和"当前上限不允许再升频"两类线索，限制来源还要结合 thermal、Power HAL、节电模式与厂商代码确认。USB 边界：许多 Android 设备接入 USB 时 USB 驱动栈持有 wakelock，部分 idle state 不会出现，USB 采集的 idle 分布未必代表脱线使用（材料转写的官方文档口径）。

**Q7: Perfetto 的 CPU 频率轨能当硬件实测时钟读吗？文档和实现有什么出入？**

不能。`power/cpu_frequency` 记录的是 cpufreq 路径报告的一次频率切换：按材料核对的 Android 17 内核，普通切换完成后写入 `freqs->new`，快速切换路径写入驱动返回的 `freq`，这是软件可见的请求或报告值，不保证硬件在整个区间都精确运行于该频率。轮询路径的口径更有出入：Android 17 的 Perfetto 文档把轮询描述为读取 `cpuinfo_cur_freq`，但同版本 `CpuFreqInfo::ReadCpuCurrFreq()` 实现读取的是 `scaling_cur_freq`；内核文档指出 `scaling_cur_freq` 多数情况下表示 scaling driver 最近请求的 P-state，未必等于硬件瞬时频率——核对某一版本时应以该版本实现为准，并保留文档与实现的差异。

要讨论有效执行周期或能耗，需要 PMU、AMU 计数器或硬件功耗计等补充证据；`cpuinfo_cur_freq`、`cpuinfo_avg_freq` 也只有在驱动具备反馈能力且 sysfs 允许读取的设备上可用。policy、governor 与 schedutil 的机制层不在本题展开，这里只强调 trace 口径：频率轨是状态信号，不是指令执行量、瞬时硬件频率或功耗。

**Q8: 怎样用 SQL 把 CPU 频率还原成时间区间并与目标线程的 Running 区间求交？为什么分区键必须用 ucpu？**

原始事件是 counter 行：`counter` 连接 `cpu_counter_track`，轨道名 `cpufreq` 的值单位是 kHz，`cpuidle` 的原始值 `4294967295` 表示退出 idle。标准库把原始值转成带 `dur` 的区间：`linux.cpu.frequency` 生成 `cpu_frequency_counters`（同时提供 `ucpu` 与逻辑 `cpu`），`linux.cpu.idle` 生成 `cpu_idle_counters`（`idle = -1` 表示 active）；`dur = -1` 表示区间延续到 trace 末尾，聚合与求交时要按查询目标处理开放区间。之后用 `SPAN_JOIN` 求调度区间与频率区间的交集：

```sql
INCLUDE PERFETTO MODULE linux.cpu.frequency;

CREATE PERFETTO VIEW _running_spans AS
SELECT ts, dur, ucpu, utid FROM sched WHERE dur > 0;

CREATE PERFETTO VIEW _freq_spans AS
SELECT ts, dur, ucpu, freq FROM cpu_frequency_counters
WHERE dur > 0 AND freq IS NOT NULL;

CREATE VIRTUAL TABLE _running_freq USING SPAN_JOIN(
  _running_spans PARTITIONED ucpu, _freq_spans PARTITIONED ucpu);
```

分区键必须用 `ucpu`：`cpu` 是设备内的逻辑 CPU 编号，`ucpu` 是 Trace Processor 的可连接 CPU 标识（多机或虚拟化 trace 下仍唯一）；把 `sched.ucpu` 与 `cpu_counter_track.cpu` 直接比较会混用两个标识域。`cluster_id` 与 `capacity` 来自已采集的处理器信息，可与频点集合互相校验。目标主线程先用 `tid = pid` 定位 `utid`，之后全程用 `utid`，避开长 trace 中 tid 复用。统计结果要同时报告频率覆盖率：内连接舍弃了未知频率区间，"已知频率覆盖的 Running 时间中落在某频点的占比"不能解释成指令占比、周期占比或能耗占比。数据存在性以 SQL 为准：按材料转写的官方文档记录过缺少 idle 事件时 UI 不渲染频率轨但数据存在的显示问题。

**Q9: 冷启动时关键线程长时间低频 Running，能直接得出"governor 调频响应慢"吗？**

不能，低频 Running 只支持"调频响应晚于该段工作"这一假设，归因前要逐项排除替代解释：

- 当前 CPU 属于低容量 policy，线程随后发生迁核；
- policy 的 `scaling_max_freq` 已被 thermal、节电模式或系统策略限制；
- `power/cpu_frequency` 记录的是请求值，硬件有效频率另有偏差；
- 代码受缓存或内存停顿限制，提高频率对 wall time 的收益有限；
- UI 线程 Running 之外还有较长的 Runnable、Binder 或 I/O 等待。

结论顺序是先减工作量再谈调频：删减首帧前任务、合并可延后的工作、降低同步 I/O 与跨进程往返，这些改动直接缩短关键路径且没有副作用。系统侧调优（PerformanceHint/ADPF、uclamp、Power HL 与能效曲线验证）属于对照组实验，不能从一条低频轨直接推出"锁最高频"。因果结论需要对照实验：固定 workload、控制温度与系统状态、只改变一个调频相关变量，并确认帧时序随之稳定变化；"目标线程在已知频率覆盖的 Running 时间中有多少比例落在某频点"是可复核观测，"governor 导致掉帧"是需要实验支持的因果判断。

**Q10: 连续渲染负载与后台批处理，频率分析的关注点有什么不同？**

渲染负载按帧切窗、盯相位关系：滑动、动画和游戏的帧循环有重复节奏，应分别观察 UI 线程、RenderThread、GPU 提交线程与 SurfaceFlinger；频率升降总是落后于负载相位可能带来周期性波动，但线程迁往不同 capacity CPU、Runnable 排队或 GPU fence 等待也会产生相似现象。频率高不保证帧按期完成，频率低也不必然异常——帧内工作量少时低频正是能效目标；判断依据是相同设备、相同温度、相同 workload 下的帧时序与频率变化对照。

后台批处理对单帧时序不敏感，评估的是完成时间与能耗的权衡：低频可能延长 CPU active 时间，高频可能缩短完成时间并提高瞬时功耗，哪个能耗更低取决于电压频率曲线、内存行为、policy 能效、idle state、唤醒次数和 thermal 状态；评估要同时比较完成时间、active/idle 分布、唤醒次数、温度与硬件功耗数据。

两条共同边界：频率轨不含电压，不等价于整机功耗；"高频等同高吞吐"是常见误判——缓存未命中、内存带宽、分支预测和共享资源会让高频 Running 仍然很慢，频率分析适合定位相关性，根因判断还要调用栈、硬件计数器与对照实验。

**Q11: Android 13 上 App 窗口的 dequeueBuffer 什么时候会等？一条长 dequeue slice 应该怎么读？**

按 AAOS13 源码核对（`BufferQueueProducer.cpp`），等待集中在 `waitForFreeSlotThenRelock()`，有两类等待条件：一是有界队列里没有可复用的 free buffer（优先复用 `mFreeBuffers` 中已分配的 buffer，耗尽才在 `mFreeSlots` 上新建）；二是快速断开重连等场景造成 `mQueue.size() > getMaxBufferCountLocked()` 的防爆等待。两个边界分支：非阻塞或 async 模式挤不出槽立即返回 `WOULD_BLOCK`；已经 dequeue 到 `mMaxDequeuedBufferCount` 上限（且已有 buffer 入队过）返回 `INVALID_OPERATION`——这是调用状态不合法，不是在等释放。其余情况生产者线程睡在条件变量 `mCore->mDequeueCondition` 上（`wait` 或带超时的 `wait_for`），Consumer 释放时 `notify` 唤醒。

版本边界：Android 13 没有 Android 17 的 `waitForBufferRelease()` 虚函数与 `BufferReleaseChannel`（本地树无该文件），`BBQBufferQueueProducer` 只覆盖 `connect` 与 `query`，BLAST 窗口与普通队列共用同一条条件变量路径；release 通道是 Android 16 引入分支、Android 17 无条件化（材料核对 A15–A17 tag）。trace 判读：`dequeueBuffer` slice（源码带 `ATRACE_CALL`）变长时，看同线程 `thread_state`——条件变量等待通常落在 futex 路径、表现为 `S`；再结合 FrameTimeline 的 Buffer Stuffing 与 BLAST 计数器判断是否下游积压传导，不能把一切长 dequeue 都读成背压。

**Q12: 怎样从 FrameTimeline 选出 Buffer Stuffing 候选帧？为什么 on_time_finish=1 也可能出现 Buffer Stuffing？**

`actual_frame_timeline_slice` 是 Trace Processor 的内置表（无需 INCLUDE 标准库，按材料核对的 v57.2 口径），按 `jank_type` 筛选并保留身份字段：

```sql
SELECT afts.ts, afts.dur, afts.upid,
       afts.surface_frame_token, afts.display_frame_token,
       afts.jank_type, afts.on_time_finish, afts.layer_name,
       process.name AS process_name
FROM actual_frame_timeline_slice AS afts
LEFT JOIN process USING (upid)
WHERE afts.surface_frame_token != 0
  AND LOWER(afts.jank_type) GLOB '*buffer*stuff*'
ORDER BY afts.ts;
```

`on_time_finish = 1` 仍可出现 Buffer Stuffing：App 的生产工作按时结束，但前一帧尚未 present 时它继续提交新帧，积压使后续帧带着额外延迟 present、输入延迟增加——Stuffing 描述排队状态，不是绘制超时。四组身份字段各有用途：`surface_frame_token` 关联 App 的 `doFrame` 与 RenderThread slice，`display_frame_token` 关联 SF 帧与 flow，`layer_name` 区分主窗口、视频、Camera 与 SurfaceView，`upid` 是 trace 内唯一进程 ID。同名 layer 可能来自不同实例或生命周期，能取得 layer id、track id、BufferQueue 名或 frame number 时一并记录；`jank_type` 原样保存以便核对分析器版本（UI 颜色随版本与主题变化）。SurfaceView 边界：官方文档声明其尚未完整支持，缺 App Actual Timeline slice 不等于该 Surface 没有更新（已与官方文档核对）。

**Q13: QueuedBuffer、BufferTX 与 BufferQueueCore::mQueue.size() 三种"积压"信号有什么区别？**

三者分属三层，不能当同一个队列长度读：

- **`QueuedBuffer - <name>BLAST#<id>`**：App 进程内 BLAST 的计数器（按 AAOS13 源码核对，`BLASTBufferQueue.cpp`），写入值为 `mNumFrameAvailable + mNumAcquired - mPendingRelease.size()`，是 BLAST 内部可用帧、已取得 buffer 与暂存 release 回调的组合变化，不等同于任何 BufferQueue 的 queued slot 数；
- **`BufferTX - <layerName>`**：SurfaceFlinger 侧计数器（Android 13 定义在 `BufferStateLayer.h`），buffer transaction 到达服务端时增加、latch 或 drop 时减少，描述尚未被消费或丢弃的服务端提交；
- **`BufferQueueCore::mQueue.size()`**：某条 BufferQueue 真正的 queued `BufferItem` 数，但常规 Perfetto trace 不直接给出这个成员。

结合源码公式判读 `QueuedBuffer`：计数器上升说明可用帧或 acquired buffer 增加、释放进度没有同步抵消；长期高位说明 BLAST 处理、SF 消费或 release 回调可能落后（等待一组更新同时提交的同步 transaction 也会暂时阻止消费）；上升后 `dequeueBuffer` 变长说明积压已向 Producer 传导；计数器已回落而渲染仍停顿，要查 release fence、GPU/driver 等待、线程调度或应用锁。多个 Surface 同时更新时，把计数器名、`layer_name`、layer id、BufferQueue 名与 frame token 配对，主窗口的 Stuffing 不能自动归因给同进程的视频 layer。

**Q14: 判断 BufferQueue 背压为什么必须成组取证？哪四类等待容易被混为一谈？**

背压是"下游消费不及使 Producer 拿不到 slot"的因果结论，单一信号只证明积压存在、不证明因果方向；需要同一 layer 上的成组证据在同一时间线吻合——FrameTimeline 的 Buffer Stuffing 分类、BLAST 计数器高位、Producer 等待 slice，以及可解释的 acquire、latch、present、release 时序。四类容易混淆的等待：

- **App 执行超时**：`doFrame`、`performTraversals` 或 bind 超预算，dequeue 窗口没有明显等待；
- **GPU 或 fence 等待**：GPU 写入、SF/HWC 读取或 release fence 未完成，证据落在 fence、GPU timeline 与 driver wait；
- **SF/HWC 合成慢**：SF Actual Timeline 变长，jank_type 指向 SF CPU/GPU deadline 或 DisplayHAL，Producer 等待可能是下游变慢的反馈；
- **应用同步或 Binder 等待**：渲染线程卡在应用锁、Binder 或资源加载，时间窗与 buffer release 对不上。

两类问题可以同时出现：下游合成变慢把压力传回 Producer，Producer 等待又扩大 App 帧时长。CPU 采样主要覆盖线程正在运行的时段，通常无法给睡眠区间提供完整用户态调用栈，适合观察进入等待前的执行路径；等待点本身要用明确的 slice、`thread_state` 或内核阻塞原因证明。缺少队列身份时只能写"同进程等待与该帧重叠"，不能写成目标 Surface 已确认阻塞。

**Q15: 多 Surface 页面分析 BufferQueue 阻塞时，为什么必须先理清队列拓扑？thread_state 怎样裁切才可信？**

因为不同出图路径的 Producer、Consumer 与队列互不相同，跨队列拼接事件会把"同时发生"写成"因果"。按路径区分：标准 HWUI 窗口的 RenderThread 把宿主 App Window buffer 交给窗口 BLAST 队列；SurfaceView 有独立 Surface、独立 BufferQueue 与 SF child layer，宿主交互与视频内容分开归因；TextureView 先由外部 Producer 写 SurfaceTexture、再被宿主 HWUI 采样，至少区分输入队列与宿主窗口队列；Camera、视频与游戏的 Producer 可能跨进程且排队策略不同；多窗口或 Dialog 的每个 ViewRoot 有自己的 BLAST，同进程同 RenderThread 不代表共用 BufferQueue。

SQL 侧的核对方法：把 Producer 等待 slice（`dequeueBuffer`、`waitForBufferRelease`、`waiting for free buffer` 等名称模式；Android 13 实际只有 `dequeueBuffer` 一层）限制在同一 `upid`，并要求与 Stuffing 窗口精确重叠——`producer_wait.ts < stuffing.ts + stuffing.dur` 且 `stuffing.ts < producer_wait.ts + producer_wait.dur`。只比较起点会漏掉从窗口前开始、持续到窗口内的等待；不约束 `upid` 会误关联其他进程的同名 slice。`thread_state` 用区间相交裁切，不用固定线程名或人为时间窗：`Running` 表示正在 CPU 上执行，`R` 与 `R+` 表示可运行（后者被抢占），`S` 表示可中断睡眠（条件变量与 `epoll_wait` 常见），`D` 表示不可中断睡眠（多指向 I/O 或驱动）。`blocked_function` 来自内核 `sched_blocked_reason` 事件，对 `D` 状态更有帮助；字段为 `NULL` 可能是线程处于可中断睡眠、事件未采集或内核符号不可见，不能反向证明没有等待。

**Q16: Agent 辅助分析 trace 时，为什么 scratchpad 必须把"已验证事实"和"假设"分开存放？**

因为可复查性要求每条结论都能由保存的命令重新执行，而口头判断（"主线程卡在 Binder""GPU 阻塞"）无法重放。scratchpad 是调查过程中的工作记录，文件名为 trace 文件名追加 `_analysis.md`；trace 目录只读或由外部系统管理时放入获准的工作目录，并记录 trace 散列值与权限受控存储中的文件标识。三张表各有硬约束：

- **输入表**：trace 散列值、平台构建、目标包、问题、复现条件、采集配置、Trace Processor 与 skill、SQL 包版本；任何字段未知都显式写"未提供"，不能自行补全；
- **已验证事实表**：证据编号、时间窗、`upid/utid`、slice/计数器/帧 token、完整 SQL、结果文件或摘要；每条记录都能由保存的命令重新执行，不写"可能是""看起来像"；
- **排除项表**：假设、执行过的查询、结果与排除范围；空结果只能排除"当前 trace 当前表结构下可见的证据"，不能写成机制绝对不存在。

假设放在调查计划而不放进事实表：查询证实后转成已验证事实，证伪后转成排除项，证据不足保留为待验证项并注明缺少的数据源。共享报告时保存散列值与受控文件标识即可，不把本机绝对路径、原始日志或未脱敏 SQL 结果复制到公开问题单——trace 可能含进程名、URL、日志与业务标记。

**Q17: Agent 写 Perfetto SQL 之前必须固定什么？哪些口径错误会让语法正确的查询给出误导结果？**

先固定执行环境再写查询：保存 `trace_processor --version` 输出与二进制来源，对实际二进制检查表和列，不凭记忆编造字段。三类入口要分清：`slice`、`thread_state`、`thread`、`process` 等基础表由 Trace Processor 预置；`android.startup.startups`、`android.frames.timeline`、`android.binder`、`slices.time_in_state` 等标准库模块要用 `INCLUDE PERFETTO MODULE` 加载；同名 metric 表不能因为加载了标准库就假定存在（模块清单按材料核对的 Android 17 标签与 v57.2 转写，Perfetto 源码不在本地树）。

常见口径错误与对策：

- **身份错配**：join 一律用 trace 内唯一的 `utid/upid`，不用会复用的 `tid/pid`；
- **未闭合区间**：`dur = -1` 统计时用 `trace_end() - ts` 替代，否则总时长与 overlap 计算出错；
- **窗口边界**：时间窗查询用区间相交条件，只比较起点会漏掉跨边界长 slice；
- **名称匹配**：已知全名用 `=`；通配用 `GLOB` 并注意 `*` 与 `?` 语义，不要把 `LIKE` 的 `_` 当普通字符；
- **区间连接**：优先用公开模块；直接 `SPAN_JOIN` 时输入区间在每个分区内不能互相重叠，分区键要匹配 CPU、线程或进程语义；
- **私有对象**：不调用下划线开头的表、视图或宏（如标准库内部的 `_interval_intersect!`），上游内部实现变化会让模板失效；
- **静默排除**：Android 17 的 `thread_slice_time_in_state` 只纳入 `dur > 0` 的记录，分析未闭合区间要显式处理并标注边界。

兼容性分三层评估：基础表结构层（表存在不代表 trace 有数据）、Android 标准库层（受采集字段与版本限制，FrameTimeline 从 Android 12 起可用）、厂商扩展层（显示、thermal、功耗自定义轨道按设备建词典）。

**Q18: 长 slice 的 dur 等于 CPU 执行时间吗？怎样用 thread_state 把可疑长 slice 的调查方向定下来？**

不等。`dur` 是 wall time（经过时间），可能覆盖 CPU 执行，也可能覆盖调度等待、Binder、锁、futex 或 I/O。判断顺序五步：

1. 定位目标 slice 的 `ts`、`dur`、线程与进程；
2. 求同一时间窗内该线程与 `thread_state` 的区间相交；
3. 按状态语义解释 `Running`、`R/R+`、`S`、`D` 并计算各状态占比；
4. 按占比最大的状态选下一组证据：CPU 查子 slice、采样栈、cpufreq 与拓扑；Runnable 查同 CPU 竞争、IRQ、实时线程与 idle；Sleeping 查事件、锁、Binder 回复与 futex；D 状态查 `io_wait`、`blocked_function`、内核线程与块设备事件；
5. 找到阻塞方后回到全局视角，检查同一用户可感知窗口中的竞争事件。

边界：函数名出现在 slice 上只说明线程处于该标记范围，不能单独证明 CPU 正在执行该函数；报告要分别给出经过时间与状态分布，状态时长之和还要与 slice 的有效经过时间核对，缺少 `sched` 数据时状态分布可能覆盖不全、应记录证据缺口。`io_wait` 与 `blocked_function` 依赖内核 `sched/sched_blocked_reason` 事件（材料按 Android 17 内核口径核对，内核不在本地树），`blocked_function` 还受 userdebug 构建与符号可用性限制。

**Q19: Agent 的开放性 trace 调查按哪六类方向组织？什么时候应该停止归因？**

六类方向按触发条件选入，避免无边界的全量扫描（每类都是"先查什么、再查什么、常见误判是什么"）：

- **CPU**：主线程或 RenderThread 长耗时、Running/Runnable 占比高——先查 `thread_state`、`sched`、cpufreq，再查调度延迟、同 CPU 竞争与 IRQ；勿把 Runnable 当成 App 正在计算；
- **Graphics**：掉帧、首帧慢——先查 FrameTimeline 与帧 token，匹配 App 与 SF 后再查 BufferQueue、fence、HWC；勿只看 App 帧不查 SF 与显示提交；
- **I/O**：D 状态、缺页密集——先查 `blocked_function` 与缺页事件，再查页缓存、块设备与内核线程；勿仅凭 D 状态断定存储慢；
- **IPC**：客户端等待、Binder 密集——先查 `android.binder` 与 flow，再追服务端线程状态；勿停在"客户端等 Binder"；
- **Memory**：LMK、swap 上升、kswapd 活跃——先查内存计数器、PSI、dmabuf，再查 RSS 与持有路径；勿只看 Java heap；
- **Power**：屏灭耗电、无法 suspend——先查电源轨、suspend 与唤醒源，再查 UID 流量与厂商轨道；勿把缺失电源轨解释成零功耗。

某轨道未被采集时，结论降级为"该证据不可见"，不能写成"该异常不存在"。全局复核四步：长 slice 排序并保留 `depth` 与 `parent_id`（嵌套父子覆盖同一段时间，不能相加成总耗时）；统计窗口内最长 D 状态；确认异常帧与慢 Binder 是否与用户可感知窗口重合；核对 cpufreq、内存、电源轨等计数器在同一窗口的变化与单位。停止条件：问题已被改写为可验证命题并由带时间关系的证据回答；候选阻塞已追到服务端、锁持有者、I/O、调度、GPU/显示提交，或明确记录 trace 在哪一跳缺数据；竞争假设已查询、无法验证的留在待验证项；全局复核没有发现更能解释同一窗口的可见证据。缺关键数据源、trace 截断或时间戳来源无法对齐时，输出补采方案而不是继续生成看似完整的根因。

**Q20: android.os.Trace/ATrace_\*、Perfetto SDK 的 track_event 与 custom data source 三层埋点怎么选？**

选择看两个维度：数据表达能力与谁控制采集，编程语言只是条件（SDK 细节按材料转写，Perfetto 源码不在本地树）：

- **atrace 层**（`android.os.Trace`、`androidx.tracing`、NDK `ATrace_*`）：写同步 section、可跨线程的异步 section 与 counter，由系统 trace consumer 控制；适合 Java/Kotlin 页面、启动步骤与跨 Java/Native 的普通区间。官方建议平台接口已能表达所需信息时优先沿用，不需要为了工具统一而迁移现有埋点。
- **track_event**：写 category、slice、custom track、flow、counter 与 debug annotation，in-process session 或外部 consumer 都可控制；SDK 已处理线程安全、增量状态、string interning 与 flush，Trace Processor 直接生成 `slice`、`counter`、flow 等标准表，是 SDK 的默认选择。
- **custom data source**：仅当四个条件同时成立才用——slice、counter 与调试参数无法自然表达数据；高频记录对每个 TracePacket 大小敏感、需要稳定的 protobuf schema；团队愿意同步维护 Trace Processor importer、内部存储表与查询；schema 具备字段编号、单位、枚举演进与兼容策略。

custom data source 写出的私有 protobuf 不会自动变成 PerfettoSQL 表：没有对应 schema 与 importer 的官方 Trace Processor 会跳过未知字段，原始字节还在 trace 里但 SQL 查询不到业务数据。

**Q21: 应用用 Perfetto SDK 起一个 in-process session，有哪些容易踩错的关键点？**

in-process backend 把 tracing service、consumer 与 producer 都放在当前进程，不连接 `/dev/socket/traced_producer`；产物只含注册到该进程内 backend 的数据源，没有 ftrace、Binder、SurfaceFlinger 或其他进程事件（SDK 细节按材料转写）。关键点按次序：

1. **初始化先于事件**：`Tracing::Initialize(args)`、`TrackEvent::Register()` 与 category 静态存储都要在第一个事件写入前完成；
2. **配置生效时点**：`StartBlocking()` 返回后配置才处于活动状态，此前执行的 `TRACE_EVENT` 不属于这次 session；
3. **输出二选一**：`Setup(config, output_fd)` 直接写入应用以私有权限打开的文件（调用方保持 fd 有效并在停止后关闭），或不传 fd 用 `ReadTraceBlocking()` 读取 consumer buffer；同一个 session 只选一种方式；
4. **收尾**：先 `TrackEvent::Flush()` 把 writer 未提交数据推给 service，再 `StopBlocking()` 结束 session。

`buffer_size_kb` 按目标设备上的事件率、最长采集时间与丢包统计确定，没有通用常量：ring buffer 写满后覆盖旧 packet，`DISCARD` 策略写满后舍弃新 packet，两者丢失的时间段不同。

**Q22: 应用以 system backend 充当 Perfetto producer 时，权限边界在哪里？Android 13 与 Android 17 有什么差别？**

producer 只能向设备上的 `traced` 服务提交数据；采集配置、buffer、输出文件与读取权限都归有 consumer 权限的一方管理，能连接 producer socket 不代表能启动或读取整机 trace。版本差异是关键（本地核对）：Android 13 的 `system/sepolicy/private/app.te` 用 `userdebug_or_eng(\`perfetto_producer({ appdomain })\`)` 门控——`user` 构建上普通应用进程没有 producer 权限；材料核对的 Android 17 已改为无条件 `perfetto_producer(appdomain)`。这直接影响线上方案：Android 13 user 机的 SDK system backend 埋点拿不到数据，应退回 `android.os.Trace`/`ATrace_*` 由具备权限的 consumer 采集。

应用只贡献 TrackEvent 时，初始化可设 `enable_system_consumer = false` 并在代码中不显式调用 `NewTrace(kSystemBackend)`，让 linker 从最终二进制移除未使用的 system consumer IPC 代码——该字段只帮助构建工具裁剪，不负责授权，应用代码仍不得创建 system consumer，也不调用 `ReadTraceBlocking()`。adb 或受控环境采集时，外部 TraceConfig 要显式请求 `track_event` 并在 `TrackEventConfig` 中启用目标 category；抓到 ftrace 却没有应用 slice 时，依次检查 producer 是否连接、track_event descriptor 是否上报、category 是否匹配、事件是否发生在采集窗口内。manifest 的 `profileable`/`debuggable` 影响部分平台 profiler 与 shell profiling 能力，但不会让应用获得 system trace consumer 权限。

**Q23: custom data source 的生命周期回调各有什么约束？停止协议为什么要异步确认？**

custom data source 继承 `perfetto::DataSource<T>`，每个活动 trace session 创建独立实例（SDK 细节按材料转写）。四个入口的约束：

- **`OnSetup`**：解析本实例配置、准备有界状态；`SetupArgs::config` 只在回调期间有效，不能保存指针；
- **`OnStart`**：启动子系统采样；回调可能来自 Perfetto 内部线程；
- **`Trace(lambda)`**：按活动实例写 packet；没有活动实例时 lambda 不执行（计算成本高的参数应放 lambda 内），并发 session 会让同一 lambda 执行多次；
- **`OnStop`**：停止采样并写收尾 packet；不应长时间阻塞 Perfetto 回调线程。

访问实例状态时用 `GetDataSourceLocked()` 取得带锁句柄，避免 session 停止与业务线程写入同时发生时访问已销毁的实例。`OnStop` 中存在异步清理时，调用 `StopArgs::HandleStopAsynchronously()` 取得 acknowledgement closure：清理线程写完末尾 packet 后，要在最后一次 `Trace()` lambda 中显式调用 `TraceContext::Flush()`，再执行该回调；整个过程必须在 consumer 配置的 stop timeout 内完成，超时后服务强制停止，之后写出的末尾数据不会进入 trace。

强类型 packet 需要扩展 TracePacket schema，完整实现至少四处同步修改：定义稳定的 protobuf 字段与编号、生成 pbzero 写入接口、在 Trace Processor importer 中解析并写入存储、为 schema 兼容与损坏 packet 补测试。团队无法长期维护 Trace Processor fork 时，退回 TrackEvent 的 category、slice、counter、flow 与长度受限的 debug annotation；数据源名称用团队控制的反向域名，减少与其他 producer 的命名冲突。

**Q24: Perfetto SDK 的 startup tracing 与 trigger 分别解决什么问题？为什么故障后才启动 trace 看不到故障前？**

普通 session 只能记录它启动之后的事件。startup tracing 解决"启动最早期也要采到"：先让 data source 写入临时目标缓冲区，等待后续 system session 用匹配的配置接管这些数据（按材料转写的 Android 17 SDK 口径）。边界：默认超时 10 秒，超时、配置不匹配或 service 不接受 producer 提供的共享内存时 startup session 终止；只支持 system backend，in-process 场景应在目标初始化工作前创建普通 session 并等待 `StartBlocking()` 完成；只调用 `SetupStartupTracingBlocking()` 不会生成可读取的系统 trace 文件，还需要外部 consumer 在超时内提交可匹配配置；`on_setup`、`on_adopted`、`on_aborted` 回调用于记录临时数据是否被接管或等待中止。

trigger 解决"条件满足时给已配置 session 发信号"：`ActivateTriggers(triggers, ttl_ms)` 只向当前已连接或在 TTL 内完成连接的 backend 发送信号，本身不创建 session，也不能让应用读取 consumer buffer；trace 的 ring buffer、停止策略与输出文件仍由 consumer 配置，`ttl_ms` 由调用方按 producer 连接策略确定。故障前数据的正确姿势是提前运行 session 并使用 ring buffer：故障后才启动 trace 只能看到故障后的活动；进程被 LMK 或 native crash 直接终止时，尚未提交的 producer chunk 和应用私有文件都可能丢失，线上方案要单独验证异常退出路径。

**Q25: 应用集成了 Perfetto SDK 后，它的 TrackEvent 会自动出现在 ProfilingManager 的 system trace 结果里吗？**

不会。按材料核对的 Android 17 `Configs.java`：`ProfilingManager` 的 system trace 固定配置包含 `linux.process_stats`、`android.packages_list`、目标 App 的 atrace、`linux.ftrace` 与 `android.surfaceflinger.frametimeline`，不请求 `track_event`，也不请求应用自定义 data source——SDK system backend 的 TrackEvent 不会自动进入其结果。APM 需要 SDK 专属事件时，只能由应用管理短时 in-process session 并上传自己的文件；需要系统调度证据时走公开 profiling API 或受控 consumer，两类文件的访问权限、脱敏状态与查询 schema 分别记录。

版本边界：`ProfilingManager.requestProfiling()` 从 API 35（Android 15）可用，触发式注册 `ProfilingTrigger` 从 API 36 可用；Android 13（API 33）没有这套 API，线上采集依赖 atrace 加具备权限的 consumer。请求受系统 rate limiter 限制且不保证执行，回调成功后用 `ProfilingResult.getResultFilePath()` 取交付文件；结果经过 redaction（按规则删除或模糊敏感字段），不能把未脱敏 trace 中可见的字段当成公开 API 保证。埋点设计按"这份文件可能被上传用于诊断"评审：category 只描述技术模块，事件名固定，动态值写入有类型、有上限的参数；用户标识、订单号、完整 URL、token、地理位置与原始堆栈不得写入。

**Q26: FrameTimeline 的 Expected slice 宽度等于刷新周期吗？Android 13 是怎么计算唤醒时间的？**

不等。Expected slice 是系统为该帧安排的调度预算（官方文档：表示分配给应用渲染该帧的时间；已与官方文档核对），材料示例中应用 Expected 约 20.5 ms、SF Expected 约 10.5 ms，都不能用"90 Hz 就应固定 11.1 ms"来校验——动态刷新率、工作预算与 VSync 重同步都会改变预算。按 AAOS13 源码核对（`VSyncDispatchTimerQueue.cpp`），唤醒时间由两段预算倒推：

```text
nextReadyTime  = nextVsyncTime - readyDuration   // 应当就绪的时刻
nextWakeupTime = nextReadyTime - workDuration    // 唤醒时刻
```

即先从预测的目标 VSYNC 减去 `readyDuration` 得到应当就绪的时间，再向前减去 `workDuration` 得到唤醒时间；固定的"VSYNC 提前固定毫秒数"模型无法描述动态刷新率、不同预算与重同步。Actual slice 的起点是 `Choreographer#doFrame` 或 `AChoreographer_vsyncCallback` 开始运行的时刻，终点取 GPU 完成与提交 SF 的较晚者（官方文档核对）；Android 13 的 trace 名是 `Choreographer#doFrame <vsyncId>`（本地核对 `Choreographer.java` 拼接 `preferredFrameTimeline().vsyncId`）。`doFrame` 较短不能证明帧在 ready deadline 前就绪——Actual 还覆盖异步 GPU 工作与提交时刻。

**Q27: FrameTracer 的 trace 里缺少某个帧事件（如 AcquireFenceSignaled 或 RELEASE_FENCE），能说明什么、不能说明什么？**

缺事件有三种彼此独立的原因，都不能直接推断"该动作没有发生"：

- **发射集合随版本变化**：AOSP 只在少数调用点 emit。材料核对的 Android 17 只有 DEQUEUE、QUEUE、ACQUIRE_FENCE、LATCH、FALLBACK_COMPOSITION、PRESENT_FENCE 六类，`HWC_COMPOSITION_QUEUED` 与 `RELEASE_FENCE` 没有 emit 调用；而按 AAOS13 源码核对，`BufferQueueLayer::onLayerDisplayed` 会发射 `RELEASE_FENCE`，BufferQueueLayer 还发射 `DETACH` 与 `CANCEL`——读旧 trace 不能按 Android 17 的发射集合假设。proto 枚举存在只表示协议允许写入，不代表会发射；
- **pending fence 延迟补写**（按 AAOS13 源码核对 `FrameTracer.cpp`）：`traceFence` 遇到尚未 signal 的 fence 先存入 `pendingFences`，同一 buffer 以后再次触发 FrameTracer 调用时才补写；补写事件的 timestamp 仍用原 fence signal time，Trace Processor 按时间排序后出现在原位置。trace 结束前没有后续同 buffer 调用时，尾部 pending fence 没有机会补写；signal time 距检查时刻超过 60 秒（`kFenceSignallingDeadline`）的 pending fence 会被丢弃，防止上一次 trace 遗留的 fence 写进新采集；
- **importer 分支**：acquire fence 在 `QUEUE` packet 被 importer 处理前已 signal 时，v57.2 importer 不创建 `GPU_` phase，避免生成起止顺序相反的区间；某帧缺 `GPU_` slice 不是数据损坏（importer 行为按材料核对的 v57.2 转写）。

另外 `QUEUE` 只表示 buffer transaction 携带的提交时间，不能当成 GPU 已完成，也不表示 SF 已采纳该 buffer。缺某一帧的 `AcquireFenceSignaled` 或 `PresentFenceSignaled` 要结合 trace 尾部、buffer 是否继续复用与采集区间判断，不能写成"fence 从未 signal"。

**Q28: FrameTracer 数据经 importer 生成的 APP\_、GPU\_、SF\_、Display\_ 四类 phase 各量什么？哪些误读最常见？**

查询入口是 `gpu_track` 与 `frame_slice`，筛选 `gt.scope = 'graphics_frame_event'`；`Buffer: <bufferId> <layer>` 轨道放原始事件，四类 phase 轨道由 importer 配对起止事件计算 `dur`（表关系按材料核对的 v57.2 importer 转写，Perfetto 源码不在本地树）：

```sql
SELECT fs.ts, fs.dur, gt.name AS track_name,
       fs.name, fs.frame_number, fs.layer_name
FROM gpu_track AS gt
JOIN frame_slice AS fs ON fs.track_id = gt.id
WHERE gt.scope = 'graphics_frame_event'
  AND fs.layer_name GLOB '*com.example.app*'
ORDER BY fs.ts;
```

四类 phase 的含义与误读边界：

- **APP\_（DEQUEUE → QUEUE）**：Producer 持有 buffer 的时间，可能包含 CPU 准备、GPU 提交、主动 pacing、锁等待，不能统一命名"App 绘制耗时"，也不等于完整 `doFrame`（主线程工作可能发生在 dequeue 前）；
- **GPU\_（QUEUE → ACQUIRE\_FENCE signal）**：对 HWUI 常反映等待 GPU 完成的时间；Camera、Video 与硬件 blitter 要按对应设备的完成信号解释，不能统一归为 GPU；
- **SF\_（LATCH → PRESENT\_FENCE signal）**：覆盖 SF 调度、CLIENT/DEVICE 合成与显示后段的综合区间，量不出 HWC 或 RenderEngine 单独耗时；
- **Display\_（相邻 present 间隔）**：反映帧节奏，30 fps 内容在 60 Hz 屏出现约 33.3 ms 间隔可能完全正常，且不等于 buffer 从 present 到可复用的 release latency。

常见误读还有：固定 16.5 ms 阈值在 90/120 Hz、VRR 与非整数帧节奏下误报，应与当前 expected timeline 和目标帧率比较；`dur = -1` 的未闭合 slice（如末尾的 `Display_`）不能参与均值、P95 或总时长统计；汇总查询里的 `NULL` 表示该 phase 没有闭合或未生成，不能用 `COALESCE(..., 0)` 把缺失证据改写成零耗时；同名 layer 在窗口销毁重建后 frame number 可能重新计数，长 trace 自动化要限制时间窗并保留原始 track name。

**Q29: API 33 的 Choreographer.postVsyncCallback 与 FrameData 怎么用？Android 13 上要注意什么？**

API 33 即 Android 13（按 AAOS13 源码核对），提供 `postVsyncCallback(VsyncCallback)` 一次性回调：下一帧执行后即移除，连续观测要再次注册并控制日志与分配成本；回调在 `Choreographer` 绑定的 Looper 线程执行。公开方法：`FrameData.getFrameTimeNanos()`、`getFrameTimelines()`（按时间排序的候选数组）与 `getPreferredFrameTimeline()`（平台选中项）；`FrameTimeline.getVsyncId()`（与 HWUI、SF trace 关联的 VSYNC id）、`getExpectedPresentationTimeNanos()` 与 `getDeadlineNanos()`，后两者使用 `System.nanoTime()` 时基（本地核对 javadoc 与实现）。

使用边界：

- **回调外访问无效**：Android 17 源码在回调外访问 `FrameData` 会抛 `IllegalStateException`（材料口径）；Android 13 没有这个异常机制，但 `FrameData` 对象会被复用，`updateFrameData` 会把过期候选的 `vsyncId` 重置为 INVALID（本地核对）——无论哪版，回调外持有都是错的，需要异步记录时只复制 `long` 等标量值；
- **候选数组长度以事件为准**：Android 17 内部为候选数组预留 7 个槽位但按事件携带的长度重建（材料口径）；Android 13 同样按 `vsyncEventData.frameTimelines.length` 逐项构造，公共 API 不承诺固定数量，业务代码应读实际长度并使用平台标出的 preferred 项；
- **不能替代 trace**：这组 API 提供本次回调可选的调度计划，应用进程拿不到最终 present、SF 分类、其他 layer 或 Display HAL 结果。

**Q30: 统计 FrameTimeline 的 jank 时，为什么应该用 jank_tag 而不是自己解析 jank_type 字符串？Android 13 与 17 的分类有什么差别？**

`jank_type` 是 bitmask 转换出的字符串，一帧可以同时带多个原因，用字符串包含关系自行分组容易把多原因帧漏算或重算；v57.2 提供 `jank_tag` 预计算分类（`Self Jank`、`Other Jank`、`Buffer Stuffing`、`SurfaceFlinger Stuffing`、`Dropped Frame`、`Non-perceivable Jank` 等），统计优先用它，并保存原始类型与工具版本，避免把不同 Perfetto 版本生成的字段混进同一基线（表与字段按材料核对的 v57.2 转写）。分类要与 `present_type`、`on_time_finish` 联合判读：`on_time_finish = 0` 只表示工作超出 ready deadline，不能当全部 jank 的过滤条件；Buffer Stuffing 常见 `on_time_finish = 1` 与 `Late Present` 并存。Android 17 的非卡顿集合（材料口径）：Buffer Stuffing、SurfaceFlinger Stuffing、Non Animating 与三个 display 状态位都位于 non-jank bitmask，不增加 severity score，却描述高延迟或显示状态变化，报告应单独统计，不能全部并入 `No Jank`，也不能全部算进应用 jank rate。

版本差异（本地核对 Android 13 的 `JankInfo.h`）：A13 的 `JankType` 枚举到 `SurfaceFlingerStuffing`（0x100）为止，没有 Android 17 新增的 `JANK_NON_ANIMATING`、`JANK_APP_RESYNCED_JITTER`、`JANK_DISPLAY_NOT_ON`、`JANK_DISPLAY_MODE_CHANGE_IN_PROGRESS`、`JANK_DISPLAY_POWER_MODE_CHANGE_IN_PROGRESS`（2048 到 32768 五个位）；A13 还把 `PredictionState::Expired` 直接分类为 `AppDeadlineMissed`（`FrameTimeline.cpp` 本地核对），读旧 trace 的分类统计要按版本分别解释。

身份对齐规则：`expected_frame_timeline_slice` 与 `actual_frame_timeline_slice` 是内置表，无需 INCLUDE 标准库；DisplayFrame 行的 `surface_frame_token` 为 `NULL`，不要按数值 0 判断；连接同一应用的 Expected 与 Actual 用同一 `upid` 下的 `surface_frame_token`，连接应用帧与 SF 显示帧用 `display_frame_token`——一个 DisplayFrame 组合多条 SurfaceFrame，结果里重复的 display token 是正常的多对一关系，不能去重隐藏。
