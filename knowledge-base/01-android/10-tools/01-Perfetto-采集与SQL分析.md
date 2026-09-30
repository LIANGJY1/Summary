# Perfetto 采集与 SQL 分析

> 学习资料（文章模式沉淀）。主线：把采集配置、轨道语义与 SQL 关联当作一条证据链——先定义问题与数据源，再读 track、slice 与线程状态，最后用可复查的查询量化结论。源文档：android-internals-wiki §14.1《Perfetto 入门、Trace 抓取与可靠性》、§14.2《Perfetto UI、状态轨道与版本边界》、§14.3《线程 CPU 状态分析》、§14.4《Perfetto 指标自动化与分析平台》、§14.5《Perfetto 输入延迟 SQL 深度分析》、§14.6《Android Tracing 基础设施与自定义 Trace》、§14.7《Perfetto SQL、SPAN_JOIN 与 Jank CUJ》；系统侧机制按本地 AAOS13 源码（Android 13）核对并标注版本差异，Android 15 起的 `tracing_perfetto` 路径、Android 17 的 `prefer_sdk` 仲裁与 lmkd instant 轨道等为后续版本能力；Perfetto 工具侧行为（Trace Processor、标准库、UI、state track）不在本地树，按材料（Android 17 平台 Perfetto v54、主机 v57.2）口径转写并弱化不确定处；`android.os.Trace` 公开 API 的 API 18/29 边界、NDK `ATrace_*` 的 API 23/29 边界、marker 编码、atrace category、InputDispatcher 队列计数器、FrameTimeline 数据源名、lmkd 与 CUJ 标记已按本地源码核对。ANR 超时契约与报告链路见 [../performance/03-ANR.md](../07-performance/03-ANR.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Perfetto 由哪些部分组成，为什么跨线程、跨进程的时间关系问题要优先用它而不是日志和单次堆栈？**

Perfetto 是 Google 开源的 tracing 基础设施，包含采集端（data source 与守护进程）、分析引擎 Trace Processor 和可视化 UI 三个模块；它把所选数据源的事件放进同一时间域，日志和单次堆栈只能记录局部状态，很难回答"主线程等待时对端在做什么"这类跨线程、跨进程的时间关系。

三个模块的分工与边界：

1. **采集层**：按 `TraceConfig` 启动各 data source，收集 ftrace、atrace 标记、进程统计、功耗、堆 profiling 或应用自定义事件；data source 是向 trace 写入某类数据的组件或能力；
2. **分析层**：Trace Processor 解析并按时间排序不同格式的事件，写入列式存储，通过 PerfettoSQL 提供查询接口；
3. **可视化层**：Perfetto UI 在浏览器本地解析与展示，默认不上传文件；超大 trace 可让本机原生 Trace Processor 作为解析后端。

采集配置决定分析上限：启用调度事件才能还原线程何时运行，启用 Binder、FrameTimeline 或应用标记后才能回答对应问题；空白轨道只说明这份 trace 没有该证据，不能推断系统没有发生相应行为。采集端、分析端与 UI 可独立升级，排查解析差异时要同时记录设备 build 与工具版本。历史脉络上，Systrace（Android 4.1 引入，ftrace + atrace 生成 HTML 报告）已被取代：Android 9 起 `traced` / `traced_probes` 进入系统镜像，Android 10 完整可用，Android 11 起大多数设备默认启用（官方文档口径）。

**Q2: `traced` 与 `traced_probes` 在采集链路中各负责什么？为什么不能说 `traced_probes` 以 root 运行？**

`traced` 是 Perfetto 的 tracing service，管理采集会话、central buffer（会话级中央缓冲区）与结果交付；`traced_probes` 是系统 probe Producer，代替 service 读取 ftrace、`/proc`、`/sys`、log 与功耗等受权限保护的数据。二者职责不同，权限差异来自进程所属 group、Linux capability 与 SELinux domain 策略，而不是 root 身份。

数据流向是：Producer 先把序列化的 trace packet 写进与 service 共享的 shared memory，service 再提交进会话的 central buffer；ftrace 事件还多一层内核 per-CPU buffer，由 `traced_probes` 周期读取。本地 AAOS13 树的 `system/sepolicy/private/` 下存在 `traced.te`、`traced_probes.te`、`traced_perf.te`、`perfetto.te`，其中 `traced_probes` 对 `debugfs_tracing` 目录有读写权限——证明这两个进程是独立 SELinux 域而非 root 兜底。Android 9/10 的非 Pixel 设备常见需要手动设置 `persist.traced.enable=1` 启用服务（该属性在 AAOS13 的 sepolicy property_contexts 中同样存在），Android 11 起大多数设备默认启用（官方文档口径）。

**Q3: 从 Android 9 到 Android 12+，向 `perfetto` CLI 传入采集配置的方式有什么版本差异？**

`perfetto` CLI 的 normal mode 接收 protobuf 编码的 `TraceConfig`（设备端不接受 JSON 配置）；差异集中在文本配置的可用性上：Android 9 只接受预先序列化的 binary protobuf，不支持 `--txt`；Android 10 起 `--txt` 可以读取人类可读的 PBTX 文本；Android 12+ 的 `perfetto.rc` 创建 `/data/misc/perfetto-configs/` 专用目录，shell 可推送配置后直接引用。

典型输入方式按版本选择：

1. **Android 9**：binary config 经 stdin 传入（`cat config.bin | adb shell perfetto -c -`），或使用 simple mode 短参数；从 `/data/misc/perfetto-traces/` 取结果可能受 `adb pull` 权限限制，可用 `adb shell cat` 重定向导出；
2. **Android 10/11 非 root 设备**：宜经 stdin 传入 PBTX（`cat config.pbtx | adb shell perfetto --txt -c -`），避开 SELinux 对普通临时目录读取的限制；
3. **Android 12+**：`adb push config.pbtx /data/misc/perfetto-configs/` 后 `--txt -c` 直接引用。

simple mode 用短参数（如 `-t 10s sched freq idle am wm gfx view`）在 CLI 内部生成受限配置，只覆盖 ftrace/atrace 子集，仍依赖 `traced` / `traced_probes`，并没有绕过 tracing service。非交互 ADB 会话里 Ctrl+C 不一定能可靠停止采集，可复现场景应设置 `-t` 时长。

**Q4: `TraceConfig` 的 `fill_policy` 与 long trace 写盘字段分别决定什么？**

`fill_policy` 决定 central buffer 满时保留哪一端：默认的 `RING_BUFFER` 用新数据覆盖旧数据，适合保留停止采集前的时间窗；`DISCARD` 在写满后拒收新数据，保留会话开始后的早期证据——会话本身不停止，不能把 DISCARD 理解为"满后立刻结束 trace"。选择前先确定要保留问题发生前还是会话开始后的证据。

long trace 通过 `write_into_file: true` 让 service 周期性把 central buffer 写入文件，配套字段：`file_write_period_ms` 控制写文件周期（有效最小值 100 ms），`max_file_size_bytes` 达到上限时会停止 tracing（即使 `duration_ms` 未到）。这套字段生成一个持续增长的文件，不提供自动轮转；需要多个独立文件时改用一组有时长上限的连续会话或触发式采集。另外 `flush_period_ms` 管的是 producer 提交 shared memory 数据的节奏，与写文件周期是两件事；`duration_ms` 默认不计入设备休眠时段，跨休眠的长 trace 实际墙上时间会更长（Android 17 提供 `prefer_suspend_clock_for_duration` 把休眠计入，AAOS13 无需关注此差异时按默认语义即可）。buffer 容量应按峰值事件速率估算，先抓短 trace 查 `stats` 再调整，而不是背固定数值。

**Q5: 常见 data source 各有哪些版本与权限门槛？user build 上为什么有些能力抓不到？**

门槛分两层：平台版本决定 data source 是否存在，目标进程资格决定 profiling 类数据能否采集；常规系统追踪（ftrace/atrace 的调度、gfx、view、input 等）不要求 App 声明任何属性，profiling 类数据则通常要求目标 App 为 `profileable` 或 `debuggable`。

常见能力的前提（材料按 Android 17 生态核对，AAOS13 树可佐证其中平台侧进程与数据源的存在）：

1. **ftrace + atrace**：Android 9+ 平台具备，常规采集无 App gate；
2. **FrameTimeline**：Android 12+；AAOS13 的 `FrameTimeline.h` 中核对到数据源名 `android.surfaceflinger.frametimeline`；
3. **Native heap sampling（heapprofd）**：Android 10+，目标 App 需 `profileable`/`debuggable`；
4. **ART 对象分配采样**：Android 12+，在 heapprofd 配置中加 `heaps: "com.android.art"`，门槛同上；
5. **Java heap dump（retained graph）**：Android 11+，门槛同上；
6. **`linux.perf` 调用栈采样**：Android 12+ 平台具备（AAOS13 sepolicy 已有 `traced_perf` 域），门槛同上；
7. **功耗 rail**：Android 10+ 且设备实现了对应 HAL，机型能力决定有无数据；
8. **logcat in trace**：官方 `android.log` 标注 userdebug builds 支持，普通 user build 不应默认视为可用。

userdebug/eng 调试镜像可以把 profiling 扩大到更多系统进程。遇到空结果时先按这张清单核对资格与配置，而不是先怀疑查询写错。

**Q6: `android.os.Trace` 的公开 API 有哪几个？同步区间、异步区间和 Counter 分别怎么正确使用？**

公开 API 只有 6 个方法：`beginSection(String)` / `endSection()`（API 18 引入），`beginAsyncSection(String, int)` / `endAsyncSection(String, int)`、`setCounter(String, long)`、`isEnabled()`（均为 API 29 引入）——AAOS13 的 `frameworks/base/core/api/current.txt` 核对了这一公开面。把异步与 Counter 写成 API 18 能力会让低版本应用在验证或运行时失败。

使用约束：同步区间必须在同一线程按栈结构嵌套，`endSection()` 结束当前线程最近未闭合的 section，异常路径要用 `finally` 保证闭合；异步区间可跨线程、不要求嵌套，但 begin/end 必须用相同名称与相同 `int` cookie 配对，时间重叠的同名任务必须用不同 cookie；`setCounter` 每次写入一个绝对值样本，不要用大量零时长区间模拟计数器。名称上限是 127 个 Unicode code unit（AAOS13 `Trace.java` 的 `MAX_SECTION_NAME_LEN = 127`；UTF-16 计数，一个补充平面字符占两个单位），`|`、换行与空字符由底层替换为空格；超长名称抛 `IllegalArgumentException`。名称宜用稳定操作名，动态 ID 放进 cookie 或 Counter，避免产生大量难以聚合的 slice 名称。抓取配置还要把包名放进 `atrace_apps`，否则 App 的 `ATRACE_TAG_APP`（值为 `1 << 12`，AAOS13 `cutils/trace.h` 核对）事件不会进入这次 system trace。`isEnabled()` 只适合控制观测开销（如懒构造格式化字符串），不能参与鉴权或业务分支；带 `traceTag` 参数的 `traceBegin`、`asyncTraceForTrackBegin/End`、`instant` 等重载是 `@hide`/`@SystemApi`，不属于普通应用 SDK（AAOS13 源码核对）。NDK 侧 `<android/trace.h>` 的同步 API 自 API 23 起可用，异步与 Counter 自 API 29 起（AAOS13 头文件注释核对）。

**Q7: 在 Android 13 上，`Trace.beginSection()` 的事件实际走哪条路径？"每个埋点都双写 ATrace 和 Perfetto"的说法对吗？**

AAOS13 上 Java Trace 走单一 libcutils 路径：JNI（`android_os_Trace.cpp`）把各 API 直接转发给 libcutils 的 `atrace_begin/end/async_begin/async_end/instant` 等函数（本地源码核对），经典 `ATRACE_*` 实现在 `trace-dev.cpp` 中打开 `/sys/kernel/tracing/trace_marker`（失败回退 `/sys/kernel/debug/tracing/`），写文本 marker 进 ftrace 的 print 事件，再由 Perfetto 解析成 slice 或 counter。

marker 编码与刷新机制（AAOS13 源码核对）：

1. 同步区间 `B|<pid>|<name>` / `E|<pid>`；异步区间 `S|...|<cookie>` / `F|...|<cookie>`；Counter `C|...|<value>`；即时事件 `I|...`；带 track 的私有 API 用 `G`/`H`/`N` 前缀；
2. 单条消息缓冲上限 1024 字节（`ATRACE_MESSAGE_LENGTH`）；
3. tag 启用状态来自 `debug.atrace.tags.enableflags` 属性，代码通过属性 serial 变化检测并刷新；应用事件由 `debug.atrace.app_number` / `debug.atrace.app_N` 选择。

"双写"属于后续版本行为：Android 15 起 Java Trace 才接入 `tracing_perfetto` 按 category 状态选择 backend，Android 17 再补 `debug.atrace.prefer_sdk` 的并发会话仲裁（材料口径）。AAOS13 的 `frameworks/native/libs/` 下没有 `tracing_perfetto` 库，`Trace.java` 的 `setAppTracingAllowed()` 注释也写明自 Android S 起是 no-op——在 A13 语境下讨论双写会错误估计开销并预期不存在的重复事件。

**Q8: Perfetto trace 里怎么找 lmkd 杀进程的证据？有哪些版本差异？**

没有名为 `android_lmk_proc_state` 或 `linux.lowmemorykiller` 的独立 data source，要组合多类证据：trace 中的 lmkd 标记、event log 的 kill 记录、statsd 统计与 PSI 压力环境。

AAOS13 的 `system/memory/lmkd/lmkd.cpp` 在杀进程路径上使用 `ATRACE_INT("kill_one_process", pid)` 计数器加 `ATRACE_BEGIN(desc)` / `ATRACE_END()` 区间（本地源码核对）——采集窗口覆盖 kill 且 atrace 配置放行时，trace 里可见该 counter 与 slice；材料按 Android 17 核对的实现改为 `ATRACE_INSTANT_FOR_TRACK` instant 事件，属版本差异，读旧 trace 时按 A13 形态找 counter/切片，读新 trace 找 instant。配套证据：`LMK_KILL_OCCURRED` 统计包在 A13 源码中同样存在（材料口径：交给 statsd 与 AMS）；event log/logcat 的 kill 描述用于核对 pid、`oom_adj`、RSS 与 kill reason；`/proc/pressure/memory` 的 `some` / `full`（some 表示至少有任务因内存压力停顿，full 表示所有非 idle 任务同时停顿）还原 kill 前的压力环境；`sched` 与进程生命周期事件确认 lmkd 何时运行、目标进程何时退出。`onTrimMemory()` 回调不能证明 lmkd 随后执行了 kill；分析低内存与卡顿的因果时先确认压力、回收、GC、调度的先后顺序。

**Q9: trace 太大打不开时怎么处理？为什么流水线必须固定主机工具版本？**

先在采集端控制数据量（缩短窗口、减少无关数据源），再用本机原生 Trace Processor 承载解析：Perfetto UI 默认在浏览器内用 WebAssembly 引擎解析，官方文档给出的典型站点内存上限约 2 GB（指运行时内存，不是文件大小）；执行 `./trace_processor server http trace.pftrace`（默认监听 `127.0.0.1:9001`）后 UI 可探测并改用本机后端——它解除浏览器内存限制，但不降低 Trace Processor 自身的完整解析内存需求。

其他手段按需选择：不需要通用 ftrace 原始表时用 `--no-ftrace-raw` 降低内存；`traceconv` 转文本/JSON 通常更大且转换只保留目标格式能表达的数据，不是无损往返；`BatchTraceProcessor` 为每份 trace 启动独立实例，官方粗算内存约 `2 × 平均文件大小 × Trace 数量`，批大小按主机内存实测。版本固定是复现的硬前提：Trace Processor 的表、标准库模块和查询行为随版本演进，`pip install perfetto` 的包会绑定对应二进制版本，生产流水线应在依赖锁文件固定包版本或用 `bin_path` 指向受审核的二进制并记录校验和；`fetch_latest_trace_processor` 会让同一脚本在不同日期下载不同二进制，产生版本漂移。trace 含进程名、线程名与业务标识，`server http` 只应监听回环地址，公开 URL/外发前先脱敏。

**Q10: `slice.dur`、`thread_dur` 与 Self Duration 三种时间分别是什么？为什么 NULL 不能按 0 处理？**

`slice.dur` 是 slice 起点到终点的墙上时长（时间轴上的宽度），线程在这段时间可能运行、等待 CPU、休眠或阻塞；`thread_dur` 是 slice 消耗的线程时间，只有 Track Event 开启线程时间采集时才填充，Android ATrace 产生的普通 slice 往往没有这个字段；Self Duration 是嵌套概念，用 `slices.self_dur` 标准库计算父 slice 墙上区间扣除同栈子 slice 覆盖后的自身墙上时长，不等于函数自身的 CPU 时间，也不扣除另一条线程上的异步工作。

需要某个 slice 内的 on-CPU 时间时，把它的半开区间 `[ts, ts + dur)` 与 `thread_state.state = 'Running'` 求交。NULL 与 0 含义不同：缺失值表示没有这份数据，零表示测量结果为零——把 `thread_dur` 的 NULL 当 0 会把"无线程数据"误读成"没有消耗 CPU"。长 slice 只证明命名区间长，判定 CPU 重的依据是状态分解或线程时间，不是 slice 宽度。

**Q11: Perfetto 的线程状态（Running/R/R+/S/D 等）从哪里来？各状态能确认什么？**

Linux 的 `TASK_RUNNING` 定义为 0，同时覆盖"正在 CPU 上执行"与"具备运行条件"两种情况，只看内核状态值区分不了 Running 与 Runnable；Perfetto 用调度事件重建时间线：`sched_switch` 把下一个线程记为 `Running`、按 `prev_state` 为被切出的线程打开后续状态区间，`sched_waking` 关闭原阻塞区间、打开 `R` 并尽量记录 `waker_utid` 与 `irq_context`；`R+` 表示这次切出带有抢占标志（内核 tracepoint 在抢占时置位并在文本输出追加 `+`），但不含抢占者身份。

状态与能直接确认的事实（材料按内核 6.18 与 Trace Processor 解码口径转写；本地学习树不含内核源码，具体状态位以设备内核为准）：

1. **Running**：该区间线程在某颗 CPU 上获得执行；
2. **R / R+**：具备运行条件未获得 CPU；`R+` 额外说明上一次切出是抢占式；
3. **S**：可中断睡眠，等待显式唤醒；
4. **D**：不可中断睡眠；
5. **T/t/X/Z 及 I/P/W/K/N 等**：停止、被跟踪、退出与特殊调度状态，遇到复合状态保留原始 `end_state` 再对照内核定义。

数据落点是两张表：`sched_slice` 从 CPU 视角记录"哪个 `utid` 在哪个 CPU 上运行多久"，`thread_state` 从线程视角记录连续状态并附带 CPU、`io_wait`、`blocked_function`、waker 等可选字段；`dur = -1` 表示区间未闭合（trace 结束或数据丢失），聚合时应排除。`utid`/`upid` 是 Trace Processor 在一份 trace 内分配的线程/进程唯一标识，用于区分被操作系统复用后的 PID/TID。UI 颜色会随主题与版本变化，结论应写状态名或 SQL 值。

**Q12: 线程处于 D 状态能直接判为磁盘 I/O 慢吗？`io_wait` 和 `blocked_function` 什么时候才有值？**

不能。`TASK_UNINTERRUPTIBLE` 只说明等待条件不会被普通信号打断，来源覆盖块 I/O、swap、内存回收、页迁移、驱动等待和内核同步路径；磁盘 I/O 只是常见来源之一。没有进一步证据时应写"Uninterruptible Sleep，原因未由本次 trace 识别"。

两个字段的取得条件：采集 `sched/sched_blocked_reason` tracepoint 后，`thread_state` 才会有 `io_wait`（来自任务 `in_iowait` 记账标志，值为 1 只提高 I/O 等待的可能性，不含文件名、设备或调用方）与 `blocked_function`（来自 `__get_wchan()` 观察到的内核睡眠位置，需要内核符号解析成功，例如配置 `symbolize_ksyms: true` 且权限允许；符号受限时可能为空）。归因按证据走：`io_wait = 1` 且与块设备/文件系统事件重叠，查设备与调用栈；与回收、压缩事件重叠，查 PSI 与内存压力；`blocked_function` 指向驱动或同步路径，查对应子系统源码。非 Running 状态没有"正在执行的 CPU"，`blocked_function` 为空也不能反推"没有内核阻塞"。

**Q13: Perfetto 的 Flow 能证明什么？读一个同步 Binder 调用要检查哪些环节？**

Flow 是 trace 显式记录的跨 track 关联（UI 中的箭头），只表达"数据声明两者相关"，不能自动证明同步阻塞或单一因果。Binder 分析依赖驱动 tracepoint 与 Trace Processor 解析：同步调用可关联客户端 transaction、服务端 transaction 与 reply；oneway 是单向异步调用，没有同步 reply，客户端不等待服务端完成。

读一个同步 Binder 调用按序检查：

1. 客户端 transaction slice 的墙上时长与线程状态；
2. 服务端 Binder 线程何时开始处理，transaction 到开始执行之间是否有排队时间；
3. 服务端 slice 内部是 Running、锁等待、D 状态还是再次发起 Binder；
4. reply 何时返回，客户端从唤醒到 Running 又等了多久。

嵌套 Binder、回调和线程池排队会让路径分叉，第一条箭头不能代表完整调用树；批量统计应使用 `android.binder` 标准库（工具侧能力，材料口径）而不是按名称通配 slice。FrameTimeline 也用 flow 把应用帧与 SurfaceFlinger display frame 通过 surface frame token 关联，比按时间重叠匹配可靠。

**Q14: FrameTimeline 的 Expected/Actual 与颜色编码怎么读？有哪些容易踩的边界？**

FrameTimeline 自 Android 12 起提供每帧的预计与实际时间线（AAOS13 树核对到数据源 `android.surfaceflinger.frametimeline`，即 Android 13 已具备）。Expected Timeline 是调度器为该帧安排的预计时间窗；App Actual Timeline 从 `Choreographer#doFrame`（或 NDK Choreographer 回调）开始，结束点取 GPU 完成时间与帧提交给 SurfaceFlinger 的 post time 两者中较晚者；SurfaceFlinger Actual Timeline 覆盖合成到屏幕更新。

选中 Actual slice 后按字段读，而不是按颜色下结论：`Present Type`（Early/On-time/Late）、`On time finish`（是否在 deadline 前完成）、`Jank Type`（App、SurfaceFlinger、Display HAL、预测、调度等平台分类）、`Prediction Type`、`GPU Composition`、`Layer Name` 与 `Is Buffer`。颜色是独立于线程状态的另一套编码（绿=无 jank、浅绿=晚呈现但可能平稳、红=本进程被归因为 jank 来源、黄=App 帧 jank 但归因 SF、蓝=Dropped frame，材料按官方文档口径），只用于找候选帧。三个边界：`BufferStuffing`（旧 buffer 未呈现就持续提交新 buffer）与 "App Deadline Missed" 是不同分类，不能把所有非绿帧合并报告；SurfaceView、Camera、视频等独立 Surface 路径不在 FrameTimeline 完整支持范围内；Actual slice 的 `dur` 是 FrameTimeline 定义的墙上区间，不代表主线程 CPU 时间。

**Q15: state track 是什么？在 Android 13 平台上能用吗，等价替代怎么做？**

state track 是 Perfetto v57.1 引入的单值状态轨道：一条轨道任一时刻至多一个当前值（无值即 idle），适合表达播放器 `Buffering → Playing → Paused` 这类单值状态机；二进制格式上体现为 `TrackEvent.Type` 增加 `TYPE_STATE = 5` 与 `StateDescriptor`，查询侧形成 `state` 表（状态列名为 `value`，持续时长 `dur` 已由分析器维护，`dur = -1` 的开放行要裁到 `trace_bounds.end_ts`，不应再用 `LEAD()` 重算）。

能否使用取决于两端版本：producer 与 Trace Processor 都具备 v57 能力才会产生和识别原生 state track；Android 17 平台内置的 Perfetto 是 v54 时代快照、枚举止于 `TYPE_COUNTER = 4`，AAOS13 平台更不可能提供（工具侧结论按材料口径，本地树无 `external/perfetto`）。等价替代按数据含义选择：有明确起止用 slice，时刻值用 counter，状态转换提示用 instant 并由生产端自行保证状态合法；这些替代缺少单值约束与专用展示，SQL 与指标名应标明所用口径。若指标需要 idle 时间，注意 state 表只为有值的区间生成行，idle 要么并入观测窗口分母、要么按相邻状态行构造，报告中说明口径。

**Q16: 线程 Running 区间很长说明什么？中断处理的时间会算到谁的头上？**

Running 只表示线程占据了某个 CPU 的任务时间线，可能在用户态执行，也可能在系统调用或异常处理的内核态执行；HardIRQ 会暂停当前任务但不一定发生 `sched_switch`，中断返回路径上执行的 SoftIRQ 同样不切换任务——这些时间都计入外层线程的 Running 区间。把整段 Running 都算成应用函数时间会系统性高估应用开销。

分层验证的顺序：应用或框架 slice 能否解释这段工作；`linux.perf` 或 simpleperf 样本的 `cpu_mode` 与调用栈指向用户态还是内核态；同一 CPU 的 IRQ/SoftIRQ 轨道（`cpu_irq`、`cpu_softirq` 类型 track）覆盖了多少；`ksoftirqd/<cpu>` 是独立可调度线程，统计时与中断路径执行分开以免重复计算；CPU 编号、`capacity`（调度器眼中的相对计算能力，不是实时利用率）与频率解释执行环境。帧分析不要套固定阈值：60 Hz 的 16.67 ms、120 Hz 的 8.33 ms 是名义周期，单个 `doFrame` 的 Running 与它们不可直接比——帧还包含 Runnable、RenderThread、GPU 与 SurfaceFlinger 阶段，调度偏移与刷新率切换会改变 deadline；Android 12+ 应先用 FrameTimeline 的 overrun 选中错过 deadline 的帧，再回看线程状态。

**Q17: Runnable 区间变长就是调度器选错了吗？怎么量化"被唤醒到开始运行"的延迟？**

不是。Runnable（`R`/`R+`）只证明线程具备运行条件却未上 CPU，候选原因包括：全机负载与高优先级/实时任务占用、调度类与 nice、affinity/cpuset/uclamp 与厂商策略、CPU 离线与热限制、唤醒放置（调度器为缓存亲和暂缓迁移，普通 Linux 调度不保证严格 work-conserving）以及 cgroup CPU 带宽配额。看到空闲 CPU 也不能判错——目标线程未必允许迁移到那颗核。

量化入口：由 `sched_waking` 打开的 `R` 区间时长近似"被唤醒到被调度"的延迟，`R+` 从抢占切出持续到下一次运行；工具侧 `sched.latency` 标准库的 `sched_latency_for_running_interval` 把每次 Running 关联到它之前的 Runnable 区间并给出 `waker_utid`、CPU 与优先级（材料口径，本地树无标准库源码）。`waker_process` 为空可能来自 `R+`、trace 开头、数据丢失或缺少 `sched_waking`；`irq_context = 1` 表示唤醒发生在中断环境，被中断线程不是业务唤醒源。`R+` 占比高不等于优先级设置错误——要看切出后同 CPU 上运行了什么、其调度类与优先级，以及这段等待是否越过业务 deadline。

**Q18: 主线程关键区间内长时间 Sleeping 怎么追？"Woken by" 是业务源头吗？**

`S` 是可中断睡眠，常见来源包括 Looper 的 `epoll_wait()`、futex/条件变量、同步 Binder 等回复和定时器；空闲线程长期 Sleeping 是健康的，需要追查的是业务 slice 或帧窗口内的长 `S`。追查线索：slice 名称或采样栈说明在哪个等待 API 进入睡眠；`waker_utid`/`waker_id` 指向谁；Binder 事务、monitor 锁竞争、Flow 或定时器事件能否解释唤醒关系；唤醒后的 `R` 是否又贡献了调度延迟。

"Woken by" 记录的是执行唤醒动作的线程，不一定是业务源头：解锁路径中它可能是释放锁的线程；Binder 回复中它可能落在驱动或服务端执行路径；`irq_context` 标记中断环境时，被中断线程不应被当作设备事件的业务来源。Trace Processor 对已在 Running/Runnable 的线程收到重复唤醒时记为 `spurious_sched_wakeup`，不改写当前状态（材料口径）。一次同步等待常表现为 `S → R → Running` 的连续段，只统计 `S` 会漏掉唤醒后的排队；UI 的 Critical Path 视图按唤醒关系推导跨线程依赖，受 trace 完整性限制，结论仍要回到原始状态与 slice 确认。

**Q19: 为什么要把线程状态裁进业务区间统计？ANR 判定和 D 状态是什么关系？**

整条线程生命周期的状态占比会被 Looper 的正常睡眠主导，对一次掉帧或一次启动没有解释力；可靠的统计是把状态裁进边界明确的业务区间——由应用写入的自定义 trace 标记（如 `cold_start`）或 FrameTimeline 帧窗口。

做法是求半开区间交集：对每条 `thread_state` 行计算 `MIN(state_end, window_end) - MAX(state_ts, window_start)`，只累计真正重叠的片段，`dur = -1` 的未闭合区间先裁到 `trace_end()`；工具侧 `sched.time_in_state` 标准库提供 `sched_time_in_state_for_thread_in_interval(开始时间, 时长, utid)` 区间函数可直接使用（材料口径）。输出按 state、`io_wait`、`blocked_function` 分组，Running 为主查调用栈与 IRQ，`R` 为主查唤醒与调度约束，`S` 为主查 Binder/锁/waker，`D` 为主查 I/O 与内核路径。结论至少包含业务区间、`utid`/`upid`、状态时长与至少一种独立旁证。另注意：D 状态本身不触发 ANR——ANR 由输入分发、广播、Service 等框架监控条件判定，D 只有在阻止受监控工作按时完成时才进入因果链，不存在"D 状态超过 N 秒就 ANR"的通用规则。

**Q20: 调大 `TraceConfig.buffers.size_kb` 为什么不一定解决丢包？`stats` 表应该怎么查、哪些项容易漏？**

因为丢包可能发生在三层 buffer 的任何一层，`buffers.size_kb` 只作用于第三层：内核 per-CPU ftrace buffer（由 `ftrace_config.buffer_size_kb` 控制且单位是每 CPU）、Producer 与 service 之间的 shared memory、以及 `traced` 的 central buffer。只调大 central buffer 不解决 ftrace overrun，也不解决 producer 突发写满 shared memory。

诊断入口是 `stats` 表，基础查询列出非零的数据丢失与错误：

```sql
SELECT name, idx, severity, source, value
FROM stats
WHERE severity IN ('data_loss', 'error') AND value != 0
ORDER BY severity, name, idx;
```

`idx` 是 buffer 或 CPU 下标，`source` 区分问题来自 trace 自带统计（`trace`）还是导入分析阶段（`analysis`）。两个容易漏的点：其一，`traced_buf_chunks_overwritten`、`traced_buf_chunks_discarded` 与 `ftrace_cpu_overrun_delta` 在 v54 时代标为 `info`，只筛 `data_loss` 会漏掉 central buffer 覆盖与 ftrace overrun，应按名称单查；其二，环形覆盖的代价不止丢旧事件——被覆盖的 interned string 或进程映射会让后续 sequence 失去解释条件，整段数据无法进入分析表（对应 `traced_buf_incremental_sequences_dropped`），缓解手段是 `incremental_state_config.clear_period_ms`（只对声明支持的数据源生效）和把低频解释性数据源放进独立 `target_buffer`。有丢失后能否继续分析取决于损失所在的 CPU、buffer、时段是否在结论路径上；重采成本低时先修配置重采。

**Q21: 自定义指标应该选标准库、Trace Summarization 还是 v1 指标？**

按复用成本递增的顺序选：先在标准库（`INCLUDE PERFETTO MODULE`）里找现成模块并确认其语义与版本边界；新指标用 Trace Summarization——它在规格里声明维度、数值列、单位与极性，输出统一的 `TraceSummary` protobuf，天然适合批量处理与长期回归；标准库有缺口时在仓库内组织自定义 SQL 包（`--add-sql-package` 注册，包名即命名空间）；只有存量读取服务依赖 `TraceMetrics` 时才继续维护 v1 指标——它在 Android 17 标签与 v57.2 帮助中已标为软弃用（不新增功能，接口继续工作）。

v1 指标的约定（维护存量时需要）：同目录同名的一对 `.sql` 与 `.proto` 文件，proto 用 `extend TraceMetrics` 挂自定义消息，字段号使用本地保留的 450–500 区间且团队内登记；输出视图必须命名为 `{扩展字段名}_output`。计数器还原出的区间数据求平均时用 `DURATION_WEIGHTED_MEAN`（按样本覆盖时长加权），普通算术平均会让只持续 1 秒的样本与持续 9 秒的样本同权重。另注意两套"宏"不是一回事：SQL 侧的 `CREATE PERFETTO MACRO` 在查询执行前展开表/表达式片段（调用以 `!` 结尾），UI 侧的命令宏是 `Settings > Macros` 里的 JSON 命令序列。指标规格应同时记录依赖的数据源、适用版本、单位、方向与空值含义。

**Q22: 指标查询返回空表或需要进 CI 时，哪些结果不能直接当数值用？**

空表不等于性能为零。空结果的常见原因：采集配置没启用指标依赖的数据源；trace 在目标事件发生前结束；目标进程没进 `atrace_apps`；工具版本缺少规格引用的标准库模块；`stats` 已记录丢包或截断；进程名、包名与查询条件不一致。CI 不能把空结果自动转成数值零：空值表示没有足够证据，零表示证据完整且测得为零——缺失值、零值与查询错误要分开存储，异常时保留原始 trace 与配置。

进 CI 后再加两层约束：其一，固定实验条件（平台版本、设备与刷新率、场景脚本、采集配置、Trace Processor 版本）后，分位数 P50/P95/P99 才可与基线比较，且必须与样本量一起解读，最大值对单次抖动敏感不适合单独做门禁；其二，门禁阈值来自固定设备、固定构建下未改码重复运行测出的噪声分布，"变慢 10%"或固定毫秒数没有跨场景通用性。凡是缺少 FrameTimeline、Binder、sched 等关键数据的结论都应降级为"不完整"并给出补采配置，而不是挑一个能算的数替代。

**Q23: 把 Perfetto 查询放进 CI 门禁时，怎么避免诊断配置污染测量？埋点开销怎么治理？**

把"测量"与"诊断"分成两层：门禁数值以 Macrobenchmark 输出的 Benchmark JSON 为准，Perfetto SQL 或 Trace Summarization 只生成诊断指标并保留异常样本的原始 trace；超阈值后用同一提交、更详细的诊断配置复测，但诊断结果不替换门禁样本；每次采集配置变更都重建基线，避免把工具开销变化记成产品性能变化。诊断配置会增加 ftrace 事件、堆采样与埋点频率，观测本身改变被测系统（probe effect），对小于几毫秒的阶段，插桩与调度抖动可能接近差异量级，需要 A/B 对照（不采集/最小配置/候选配置三组）与足够样本。

埋点治理要点：名称用稳定、低基数的操作名，动态标识放 cookie、counter 或受控字段；账号、URL、文件路径、token 不进 trace；高频循环先估算事件速率再决定采样或缩小范围。开销没有跨设备固定值：官方经验值约为每个区间 5 μs（启用态 ATrace 单事件量级 1–10 μs，材料口径），trace 关闭时走快速检查但不是零开销，应目标设备实测。发布包不要用 `-assumenosideeffects class android.os.Trace` 全局清除——该 R8 规则假定方法无副作用，会删除应用与依赖库的所有调用点并让诊断能力随构建配置漂移；更可控的做法是包装自己的可选埋点，用构建期常量（如 `BuildConfig.ENABLE_APP_TRACE`）让 R8 只移除不可达分支。

**Q24: `android_input_events` 与 `android.input.inputevent` 的三张原始视图是什么关系？为什么不能桥接？**

两条独立链路。`android_input_events`（标准库 `android.input` 派生，工具侧行为按材料口径）由 ATrace 与 FrameTimeline 派生：`sendMessage`/`receiveMessage` 建立四段消息节点，`UnwantedInteractionBlocker::notifyMotion` 提供事件 ID 与读取时间，`deliverInputEvent` 与 `Choreographer#doFrame` 建立帧关联——常规 user 量产构建即可采集，前提是配置包含 `input`/`view`/`gfx` 类别、目标应用与 FrameTimeline 数据源。`android_motion_events`、`android_key_events`、`android_input_event_dispatch` 来自 `android.input.inputevent` 调试数据源，配置协议限定只能在 `userdebug`/`eng` 构建启用，记录 InputDispatcher 处理的原始事件字段与窗口分发决策。

两者不能当作同一张表拆分后的结果：原始视图的 `event_id` 是数值型，生命周期表的 `input_event_id` 来自 ATrace 名称（通常是 `0x` 前缀的十六进制文本），标准库没有公开稳定的桥接视图，`CAST` 后等值连接可能把格式差异或 ID 碰撞误当成同一事件。原始视图查询时还要注意：同一事件会投递给前台窗口、监视窗口等多个目标，`android_input_event_dispatch` 出现多行不是重复数据；坐标、按键码等敏感字段受脱敏等级影响可能为 NULL，查询必须允许缺失。

**Q25: `android_input_events` 的四段延迟和 `end_to_end_latency_dur` 分别怎么定义？怎么解读分段异常？**

四段往返延迟基于同一事件的四个消息节点（工具侧标准库定义，材料口径）：`dispatch_latency_dur` = 应用收到 − Dispatcher 发出；`handling_latency_dur` = 应用发 `FINISHED` − 应用收到；`ack_latency_dur` = 系统收到确认 − 应用发出；`total_latency_dur` = 发出 − 收到确认。`end_to_end_latency_dur` 定义为 `present_time - read_time`（InputReader 读取到关联帧呈现），无帧关联时为 NULL。

解读边界：输入通道基于 socket，`ack_latency_dur` 不是 Binder 往返耗时，归因 Binder 拥塞会带偏方向；`handling_latency_dur` 覆盖输入批处理与框架分发开销，不等同于某个业务回调的执行时间。分析前先做覆盖率检查（`COUNT(*)`、各字段 `IS NOT NULL` 的数量、`is_speculative_frame = 1` 的数量），`with_frame` 明显少于总行数时要核对 FrameTimeline 与场景完整性——覆盖率不同的两组分位数不可比。找慢样本按 `total_latency_dur` 排序后分段定位：`dispatch_ms` 高查 Dispatcher 与目标线程的调度状态，`handling_ms` 高展开应用接收线程，`ack_ms` 高查确认回传路径；单段偏高只是排查入口，不独立证明根因。

**Q26: 标准库把输入事件关联到帧的规则是什么？哪些情况会给出"不可信"的帧关联？**

关联规则（工具侧 `android.input` 标准库，材料按 Android 17 核对）：在同一应用线程上查找与 `deliverInputEvent` 区间相交的 `Choreographer#doFrame`，相交即精确关联；没有交集时选择该线程上紧随其后的帧并标记 `is_speculative_frame = 1`；映射到 SurfaceFlinger 时选择不早于关联应用帧的首个未丢弃帧。

由此产生的限制：推测关联只说明时间最接近，不能证明该输入触发了该帧；被丢弃的应用帧会让 `frame_id` 指向后续未丢弃帧；一帧可以合并多个 MOVE 事件，输入行与帧不是一一关系。字段边界：标准库内部对象 `_input_read_time` 只匹配 motion 事件的 `notifyMotion` slice，按键事件可以有完整往返延迟却没有 `read_time`、`event_time` 与呈现延迟；`event_time` 是 InputReader 的 ATrace 输出携带的事件时间，比 `dispatch_ts` 更靠近设备事件，但不能描述成触摸控制器中断时间。把输入结果连接 `android_frames` 时应同时用 `frame_id` 与 `upid` 条件，避免不同进程复用 Vsync ID 造成误连；帧持续时间长不等于输入处理慢，还要展开 `doFrame`、RenderThread 与调度上下文。

**Q27: InputDispatcher 的 `iq`、`oq`、`wq` 队列计数器在 trace 里长什么样？怎么把采样点变成区间？**

AAOS13 的 `InputDispatcher.cpp` 用 ATrace 计数器记录三类队列（本地源码逐字核对）：`iq` 是尚未处理的全局入站队列；`oq:<窗口名>` 是该连接上等待写入应用输入通道的出站队列；`wq:<窗口名>` 是已写给应用、仍等待 `FINISHED` 确认的等待队列。名称用固定 40 字节栈缓冲拼接（`char counterName[40]`），过长的通道名会被截断；旧文档中 "InputDispatcher inbound queue" 之类的名称不是源码写法，查询用 `GLOB 'oq:*'`、`GLOB 'wq:*'` 前缀匹配并 JOIN `process_counter_track` 限定 `system_server`。

counter 只在值变化时记录采样点、没有 `dur`，一个值持续到同轨道的下一次采样；要按"非零持续区间"分析时用窗口函数补时长：

```sql
SELECT track_id, ts, value,
  COALESCE(LEAD(ts) OVER (PARTITION BY track_id ORDER BY ts), trace_end()) - ts AS dur_ns
FROM counter ...;
```

`LEAD()` 按同一 `track_id` 取下一采样时刻，末条用 `trace_end()` 兜底。解读：瞬间非零是正常流转；持续的 `iq` 表示入站未消费完，持续 `oq` 表示连接上有待写入事件，持续 `wq` 表示还有事件在等确认——队列堆积能缩小范围，但不能单独证明是主线程、socket 写入还是 `system_server` 调度的根因。

**Q28: 分析输入 ANR 时，为什么 `android_input_events` 里可能没有卡住的那个事件？正确的证据组合是什么？**

`android_input_events` 的一行要求四个消息节点（发出、接收、FINISHED、确认）都匹配成功；触发 ANR 的事件往往停在等待队列里没有 `finish_ack`，因此不会出现在结果里——"表里没有"不等于"输入没发生"。

正确的证据组合有三份：其一，以 `android.anrs` 标准库的 `android_anrs` 表为时间锚点（解析 `system_server` 的 ErrorId、subject、计时器与 `anr_type`，工具侧材料口径），不要在 slice 表里按名称搜 "ANR"（会命中日志与应用自定义 slice）；`anr_dur_ms` 优先来自平台计时器，`default_anr_dur_ms` 只是 AOSP/Pixel 的参考默认值，OEM 可以改写，超时窗内查询时用 `COALESCE(anr_dur_ms, default_anr_dur_ms)`。其二，窗口内 `wq:`/`oq:`/`iq` 的非零持续区间，确认堆积发生在哪类队列、是否持续到 ANR 附近。其三，目标主线程在超时窗内的运行、可运行、睡眠状态与长 slice。已完成的往返延迟只用于观察超时前是否已经退化，不能替代未完成投递的现场（ANR 检测与超时契约本身见引言链接的 ANR 专题文档）。

**Q29: tracepoint 和 tracer 有什么区别？"function / function_graph / tracepoint 三种模式"的说法准确吗？**

tracer 是写入 `current_tracer` 的记录算法，tracepoint 是内核源码预先声明的静态事件点，由 `events/<group>/<name>/enable` 独立开关——启用调度事件时 `current_tracer` 通常仍是 `nop`。"三种模式"是便于入门的说法，不代表 tracepoint 是第三个 `current_tracer` 取值。成本上，未启用的 tracepoint 走静态分支快速路径，开销很小但不是绝对零；启用后要计算字段、复制并写入当前 CPU 的环形缓冲区，实际成本由事件频率与读取速度决定。`function`/`function_graph` 记录函数入口与返回关系，过滤范围过宽时事件量与扰动都会失控，适合缩小到明确函数集合后的内核调试。

atrace category 不是事件的别名，而是一组采集开关：`view`、`wm`、`am` 等主要控制用户空间的 `ATRACE_TAG_*` 位；`sched`、`freq`、`idle`、`binder_driver` 主要启用内核 tracepoint（AAOS13 的 `frameworks/native/cmds/atrace/atrace.cpp` 核对了这张 category 表）。一个版本差异值得记住：AAOS13 的 category 表里还有 `binder_lock`（Binder 全局锁 trace），材料按 Android 17 核对时该全局锁事件已不在追踪头文件中。AOSP category 存在不保证厂商内核提供其依赖的每个事件，以 `adb shell atrace --list_categories` 与设备的 `available_events` 为准。

**Q30: 某条轨道没有数据时，按什么顺序排查最快？**

按数据实际经过的路径把问题分成四类，逐层确认：

1. **事件不存在**：在目标设备的 `available_events` 中确认事件已注册（GKI、厂商配置与驱动实现都会改变可用集合）；
2. **配置未启用**：核对配置使用正确的 `group/event`、atrace category 与 `atrace_apps` 包名，再查 trace 的 `unknown_ftrace_events`、`failed_ftrace_events` 与 atrace 错误（事件名未知/启用失败）；
3. **采集途中丢失**：用 `ftrace_event` 原始表确认事件是否进入 trace，再按 `stats` 的三层统计定位丢失层（ftrace per-CPU、shared memory、central buffer）；
4. **解析未派生**：有些事件只有原始表没有领域表，确认是否缺少专用解析器或应改查 `ftrace_event` + `args`。

两个版本相关点：AAOS13 的 Java Trace 走 trace_marker 单路径（见前文），Android 15+ 才有 TrackEvent 路径可选；用户空间埋点不可见时先查包名是否进了 `atrace_apps`、事件是否落在采集窗口内。定位后一次只改一个采集变量，并用同一复现验证；文件成功生成只证明会话结束并拿到输出，不证明数据完整。

**Q31: `SPAN_JOIN` 的输入要满足什么条件？违反约束会怎样？各变体怎么选？**

`SPAN_JOIN` 计算两组时间区间的交集，输入要求：两侧都有 `ts` 且至少一侧有 `dur`（做区间分析应两侧都显式提供）；区间为半开形式 `[ts, ts + dur)`；`dur > 0`，`dur = -1` 的开放区间先裁到查询窗口或 `trace_end()`；分区列必须是整数且两侧表达同一实体（`utid` 对 `utid`、`ucpu` 对 `ucpu`），`PARTITIONED` 两侧列名相同，也可以只给一侧分区。

最关键的约束：同一输入表在同一分区内区间互不重叠——算子按分区与 `ts` 推进游标，不做重叠检查，违反时静默产生错误行而不是报错。普通业务 slice 带父子嵌套，直接按 `utid` 送入会重复计算父子层；输入前用"前序最大结束时间"的窗口函数检查重叠，或用 `intervals.overlap` 模块的 `interval_merge_overlapping_partitioned!` 宏把相交区间合并成不重叠覆盖集（合并后丢失原始 `slice.id`，只适合统计覆盖时长）。counter 表是离散采样点不是区间，先按 `track_id` 分区用 `LEAD()` 补出前向区间（标准库 `linux.cpu.frequency` 已把 `cpufreq` 封装成 `cpu_frequency_counters`，优先用它）。

变体按输入形状选择：两侧互斥、只要交集用 `SPAN_JOIN`；要保留一侧未匹配时间用 `SPAN_LEFT_JOIN`（用影子时间段补空档）或 `SPAN_OUTER_JOIN`；输入含嵌套或要保留事件身份用普通区间条件或 `intervals.intersect`。一个已记录的陷阱：空分区表参与 `SPAN_OUTER_JOIN` 或作为 `SPAN_LEFT_JOIN` 的右表时，即使另一侧非空也不输出行，自动化查询要加空表测试。

**Q32: Binder 客户端调用耗时长，怎么区分是服务端执行慢还是客户端在等调度？**

用 `android.binder` 标准库的两张表拆解（工具侧材料口径）。`android_binder_txns` 配对客户端事务与服务端 reply；同步与 oneway 必须按 `is_sync` 分组——异步事务没有同步等待关系，`client_dur` 语义不同，两组数不能合成一个延迟分布。`android_sync_binder_thread_state_by_txn` 已对同步事务两端区间分别做 `SPAN_JOIN`，按 `thread_state_type` 区分：`binder_txn` 是客户端区间，`binder_reply` 是服务端区间。

解读规则：客户端处于 `S` 符合等待同步回复的预期；服务端出现较长 `R`/`R+` 才是"服务端线程排队等 CPU"的直接证据；服务端 `Running` 长则回到该事务的 slice 子树确认具体工作。`interface`、`method_name` 依赖 AIDL/HIDL 追踪 slice，为空时事务配对仍有效——报表应保留 `<unresolved>` 行而不是过滤掉，否则最难解释的调用会从结果里消失。所谓"Binder 线程池利用率"必须同时给出容量（最大线程数）与统计窗口：只累加 Binder 线程 CPU 时间得到的是运行时间，线程池可能睡眠等待工作，也可能在处理非 Binder slice；判断是否饱和要结合服务端并发事务数、可用线程数与事务排队。

**Q33: 为什么启动时间不要用 "bindApplication 到第一个 doFrame" 手工拼 slice？标准库提供了什么？**

手工拼接会漏掉 `system_server` 发起阶段、RenderThread 渲染、可见帧判定与温/热启动分支，而且起止 slice 名称随版本漂移。`android.startup.startups` 标准库按平台事件输出 `android_startups` 与 `android_startup_processes`，保留平台识别的 `cold`/`warm`/`hot` 分类（对应进程与 Activity 是否已存在的不同路径，不能按耗时长短命名）；`android.startup.time_to_display` 继续关联首帧与 `reportFullyDrawn()`，给出 TTID（首次显示耗时）与 TTFD（完全显示耗时）——TTFD 依赖应用调用 `reportFullyDrawn()`，空值不能当零毫秒。

两个使用边界：进程在启动期间死亡重建时，一个 `startup_id` 可能关联多个 `upid`，TTID/TTFD 仍是该启动实例的一组时间；`android.startup.startup_breakdowns` 会把主线程 slice 与 `thread_state` 裁成互斥区间并生成 `reason`，但 reason 是标准库的派生分类、不是设备直接记录的原始事件，适合筛选候选原因，仍要用原始时间线与源码确认；模块源码建议采集 Binder、ART、`am`、`view` 事件，缺少时分类变粗。统计启动区间内的 Binder 事务时，要同时满足 `upid` 匹配与时间相交，且各事务可能互相重叠——`SUM(client_dur)` 是时长之和，不是启动区间中的独占时间。

**Q34: `android_jank_cuj` 为什么看不到第三方 App 的 CUJ？系统 CUJ 指标的输入和第三方路径分别是什么？**

因为标准库对输入有明确过滤：CUJ 必须是 `process_track` 上持续时间大于零、名称匹配 `J<*>` 的 slice，且进程名匹配 `com.android.*` 或 `com.google.android*`（工具侧 `android.cujs.base` 的源码行为）——第三方 App 写出同名标记也不会进表。本地 AAOS13 源码可佐证系统侧的产生机制：`InteractionJankMonitor` 用 `J<%s>` 格式写 CUJ 标记，`FrameTracker` 结束时经 `Trace.traceCounter` 写出 `J<CUJ>#totalFrames`、`#missedFrames` 等计数器。

系统 CUJ 指标汇合三组独立数据：`J<CUJ>`、`FT#begin/endVsync` 等标记定义名称与 vsync 边界；Java `FrameTracker` 的聚合计数器（`totalFrames`、`missedAppFrames`、`missedSfFrames`、`weightedAppJank`、`weightedSfJank` 等）提供事后汇总；FrameTimeline 的 expected/actual 给逐帧时序与 App/SF 分类。使用时注意：`weighted_missed_app_frames` 等是速率（jank/s），乘 `anim_duration_ms / 1000` 才是窗口内加权总量；expected timeline 缺失时指标用 16.6 ms 回退值，不能反推设备运行在 60 Hz；HWUI 的 C++ `JankTracker`（`kMissedDeadline`、`kSlowUI` 等，经 FrameMetrics 上报）是另一套实现，数据来源不能与 Java FrameTracker 混写。第三方 App 的可执行路径是组合：AndroidX JankStats 收集应用内帧指标、用应用命名空间（如 `myapp.cuj.feed_scroll`）的异步 section 标记交互、再在 Perfetto 中用 FrameTimeline 与 `thread_state` 做逐帧分析——采集配置需加入 `atrace_apps` 与 FrameTimeline 数据源。

**Q35: 分析一帧"慢"，怎么把 doFrame、FrameTimeline、锁与调度证据拼起来？哪些时间不能相加？**

先定位对象再用区间求交。用 `android.frames.timeline` 的 `android_frames` 取得帧窗口、`doFrame` slice 与 UI/RenderThread 的 `utid`，`android_frames_overrun` 给出 deadline 超额（正值为超期，负值为余量）；`actual_frame_timeline_count`、`draw_frame_count` 等完整性列异常说明时间线关联失败，自动化统计要保留这些列。然后把 `doFrame` 与该帧 UI 线程（`ui_thread_utid`）的 `thread_state` 求交集，得到 Running、Runnable、睡眠与 I/O 等待的分解；再把 `doFrame` 与 `android_monitor_contention` 求交集得到锁等待覆盖——条件是 `blocked_utid` 等于 UI 线程且两区间相交（只统计"完全包含"会漏掉两端跨界的锁等待）；只对 Running 片段关联 `sched` 与 CPU 频率，解释线程获得 CPU 后的运行环境。

三个"不能相加"的边界：锁等待期间线程没有运行，同一 `utid` 上锁等待区间与 Running 区间按定义互斥，`SPAN_JOIN` 得到空集是正确结果；Binder、锁与 GC 活动可能彼此重叠，三类覆盖时间相加不是"总阻塞时间"；锁时间本来就是睡眠时间的一个具体原因，不应再与各状态相加。`android_monitor_contention` 的 `waiter_count` 从 0 起编号（0 表示自己是第一个等待者），不能当作等待线程总数。结论写成"FrameTimeline 将某帧标为 App Deadline Missed，UI 线程 6.2 ms 处于 R 状态，同窗口未发现 monitor 锁竞争"这样的具体命题——数字来自具体 trace，时间相关性不写成因果关系。
