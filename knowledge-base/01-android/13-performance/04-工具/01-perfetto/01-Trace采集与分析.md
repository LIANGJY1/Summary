# Perfetto 采集与 Trace Processor 分析

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q1 -->

**Q1: [learning] Perfetto 由哪些部分组成，为什么跨线程、跨进程的时间关系问题要优先用它而不是日志和单次堆栈？**

Perfetto 是 Google 开源的 tracing 基础设施，包含采集端（data source 与守护进程）、分析引擎 Trace Processor 和可视化 UI 三个模块。它把所选数据源的事件放进同一时间域，日志和单次堆栈只能记录局部状态，很难回答“主线程等待时对端在做什么”这类跨线程、跨进程的时间关系。

三个模块的分工与边界：

1. **采集层**：按 `TraceConfig` 启动各 data source，收集 ftrace、atrace 标记、进程统计、功耗、堆 profiling 或应用自定义事件。data source 是向 trace 写入某类数据的组件或能力。
2. **分析层**：Trace Processor 解析并按时间排序不同格式的事件，写入列式存储，通过 PerfettoSQL 提供查询接口。
3. **可视化层**：Perfetto UI 在浏览器本地解析与展示，默认不上传文件。超大 trace 可让本机原生 Trace Processor 作为解析后端。

采集配置决定分析上限：启用调度事件才能还原线程何时运行，启用 Binder、FrameTimeline 或应用标记后才能回答对应问题。空白轨道只说明这份 trace 没有该证据，不能推断系统没有发生相应行为。采集端、分析端与 UI 可独立升级，排查解析差异时要同时记录设备 build 与工具版本。历史脉络上，Systrace（Android 4.1 引入，ftrace + atrace 生成 HTML 报告）已被取代：Android 9 起 `traced` / `traced_probes` 进入系统镜像，Android 10 完整可用，Android 11 起大多数设备默认启用（官方文档口径）。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q2 -->

**Q2: [learning] tracepoint 和 tracer 有什么区别？“function / function_graph / tracepoint 三种模式”的说法准确吗？**

tracer 是写入 `current_tracer` 的记录算法，tracepoint 是内核源码预先声明的静态事件点，由 `events/<group>/<name>/enable` 独立开关——启用调度事件时 `current_tracer` 通常仍是 `nop`。“三种模式”是便于入门的说法，不代表 tracepoint 是第三个 `current_tracer` 取值。成本上，未启用的 tracepoint 走静态分支快速路径，开销很小但不是绝对零。启用后要计算字段、复制并写入当前 CPU 的环形缓冲区，实际成本由事件频率与读取速度决定。`function`/`function_graph` 记录函数入口与返回关系，过滤范围过宽时事件量与扰动都会失控，适合缩小到明确函数集合后的内核调试。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q3 -->

**Q3: [learning] traced 与 traced_probes 在采集链路中各负责什么？为什么不能说 traced_probes 以 root 运行？**

`traced` 是 Perfetto 的 tracing service，管理采集会话、central buffer（会话级中央缓冲区）与结果交付。`traced_probes` 是系统 probe Producer，代替 service 读取 ftrace、`/proc`、`/sys`、log 与功耗等受权限保护的数据。二者职责不同，权限差异来自进程所属 group、Linux capability 与 SELinux domain 策略，而不是 root 身份。

采集数据按顺序经过以下路径：

1. **Producer 到 service：**Producer 把序列化的 trace packet 写进与 service 共享的 shared memory。
2. **service 到会话：**service 将 packet 提交进会话的 central buffer。
3. **ftrace 输入：**ftrace 事件还经过内核 per-CPU buffer，由 `traced_probes` 周期读取。

本地 AAOS13 树的 `system/sepolicy/private/` 下存在 `traced.te`、`traced_probes.te`、`traced_perf.te`、`perfetto.te`，其中 `traced_probes` 对 `debugfs_tracing` 目录有读写权限——证明这两个进程是独立 SELinux 域而非 root 兜底。

Android 9/10 的非 Pixel 设备常见需要手动设置 `persist.traced.enable=1` 启用服务（该属性在 AAOS13 的 sepolicy property_contexts 中同样存在），Android 11 起大多数设备默认启用（官方文档口径）。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q4 -->

**Q4: [learning] Android 设备上录制 system trace 时，Perfetto UI、record_android_trace 和直接调用 perfetto 怎么选？**

三种入口适合不同采集场景：Perfetto UI 适合临时交互式录制，`record_android_trace` 适合用 ADB 快速录制并自动拉取、打开 trace，直接调用设备端 `perfetto` 适合已有配置和脚本化流程需要精确控制时。它们最终都围绕设备上的 Perfetto 采集能力。选择入口不会替代对数据源、缓冲区和采集时长的设计。

1. **临时交互式排查**：从 Perfetto UI 的 Record new trace 配置并开始录制，或在设备开发者选项中打开 System Tracing。适合快速查看常见系统事件。保存前仍要确认采集的数据源覆盖目标问题。
2. **快速 ADB 采集**：使用官方 `record_android_trace` 包装脚本。它负责连接设备、调用设备端 `/system/bin/perfetto`、拉取结果并打开 UI，减少手动传输步骤。脚本提供的默认参数不能代替针对具体问题选择数据源。
3. **可重复、精确控制的采集**：通过 `adb shell perfetto` 直接启动设备端 CLI，并按 Android 版本选择 simple mode 或 `TraceConfig`。该方式适合自动化和复现固定配置。非交互 ADB 会话中 Ctrl+C 可能不能可靠停止采集，因此应显式设置时长，或使用可控的停止流程。

帧卡顿排查要确保采到 FrameTimeline、应用相关标记和所需调度事件。设备端 CLI 的 simple mode 只覆盖 ftrace/atrace 子集，完整数据源配置需用 TraceConfig。官方 Perfetto 文档将 `record_android_trace` 定义为设备端 Perfetto 的便捷包装，并说明 UI 和 System Tracing 的交互式录制路径。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q5 -->

**Q5: [learning] 从 Android 9 到 Android 12+，向 perfetto CLI 传入采集配置的方式有什么版本差异？**

`perfetto` CLI 的 normal mode 接收 protobuf 编码的 `TraceConfig`（设备端不接受 JSON 配置）。差异集中在文本配置的可用性上：Android 9 只接受预先序列化的 binary protobuf，不支持 `--txt`。Android 10 起 `--txt` 可以读取人类可读的 PBTX 文本。Android 12+ 的 `perfetto.rc` 创建 `/data/misc/perfetto-configs/` 专用目录，shell 可推送配置后直接引用。

典型输入方式按版本选择：

1. **Android 9**：binary config 经 stdin 传入（`cat config.bin | adb shell perfetto -c -`），或使用 simple mode 短参数。从 `/data/misc/perfetto-traces/` 取结果可能受 `adb pull` 权限限制，可用 `adb shell cat` 重定向导出。
2. **Android 10/11 非 root 设备**：宜经 stdin 传入 PBTX（`cat config.pbtx | adb shell perfetto --txt -c -`），避开 SELinux 对普通临时目录读取的限制。
3. **Android 12+**：`adb push config.pbtx /data/misc/perfetto-configs/` 后 `--txt -c` 直接引用。

simple mode 用短参数（如 `-t 10s sched freq idle am wm gfx view`）在 CLI 内部生成受限配置，只覆盖 ftrace/atrace 子集，仍依赖 `traced` / `traced_probes`，并没有绕过 tracing service。非交互 ADB 会话里 Ctrl+C 不一定能可靠停止采集，可复现场景应设置 `-t` 时长。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q6 -->

**Q6: [learning] TraceConfig 的 fill_policy 与 long trace 写盘字段分别决定什么？**

`fill_policy` 决定 central buffer 满时保留哪一端，先按目标时间窗选择：

1. **`RING_BUFFER`：**默认用新数据覆盖旧数据，适合保留停止采集前的时间窗。
2. **`DISCARD`：**写满后拒收新数据，保留会话开始后的早期证据；会话本身不会因写满而停止。

long trace 通过 `write_into_file: true` 让 service 周期性把 central buffer 写入文件，配套字段：`file_write_period_ms` 控制写文件周期（有效最小值 100 ms），`max_file_size_bytes` 达到上限时会停止 tracing（即使 `duration_ms` 未到）。这套字段生成一个持续增长的文件，不提供自动轮转。需要多个独立文件时改用一组有时长上限的连续会话或触发式采集。另外 `flush_period_ms` 管的是 producer 提交 shared memory 数据的节奏，与写文件周期是两件事。

`duration_ms` 默认不计入设备休眠时段，跨休眠的长 trace 实际墙上时间会更长（Android 17 提供 `prefer_suspend_clock_for_duration` 把休眠计入，AAOS13 无需关注此差异时按默认语义即可）。buffer 容量应按峰值事件速率估算，先抓短 trace 查 `stats` 再调整，而不是背固定数值。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q7 -->

**Q7: [learning] 常见 data source 各有哪些版本与权限门槛？user build 上为什么有些能力抓不到？**

门槛分两层：平台版本决定 data source 是否存在，目标进程资格决定 profiling 类数据能否采集。常规系统追踪（ftrace/atrace 的调度、gfx、view、input 等）不要求 App 声明任何属性，profiling 类数据则通常要求目标 App 为 `profileable` 或 `debuggable`。

常见能力的前提（材料按 Android 17 生态核对，AAOS13 树可佐证其中平台侧进程与数据源的存在）：

1. **ftrace + atrace**：Android 9+ 平台具备，常规采集无 App gate。
2. **FrameTimeline**：Android 12+。AAOS13 的 `FrameTimeline.h` 中核对到数据源名 `android.surfaceflinger.frametimeline`。
3. **Native heap sampling（heapprofd）**：Android 10+，目标 App 需 `profileable`/`debuggable`。
4. **ART 对象分配采样**：Android 12+，在 heapprofd 配置中加 `heaps: "com.android.art"`，门槛同上。
5. **Java heap dump（retained graph）**：Android 11+，门槛同上。
6. `linux.perf` 调用栈采样：Android 12+ 平台具备（AAOS13 sepolicy 已有 `traced_perf` 域），门槛同上。
7. **功耗 rail**：Android 10+ 且设备实现了对应 HAL，机型能力决定有无数据。
8. **logcat in trace**：`android.log` 从 logd 读取日志，Perfetto UI 可把它们按时间放到同一 trace 的摘要轨道和消息表中，便于对齐事件与系统日志；Trace Processor 也提供 `android_logs` 表。官方标注该 data source 仅支持 userdebug build，普通 user build 不应默认视为可用；过滤器与采集到的 log buffer 还会限制可见日志范围。

atrace category 不是事件的别名，而是一组采集开关：`view`、`wm`、`am` 等主要控制用户空间的 `ATRACE_TAG_*` 位。`sched`、`freq`、`idle`、`binder_driver` 主要启用内核 tracepoint（AAOS13 的 `frameworks/native/cmds/atrace/atrace.cpp` 核对了这张 category 表）。一个版本差异值得记住：AAOS13 的 category 表里还有 `binder_lock`（Binder 全局锁 trace），材料按 Android 17 核对时该全局锁事件已不在追踪头文件中。AOSP category 存在不保证厂商内核提供其依赖的每个事件，以 `adb shell atrace --list_categories` 与设备的 `available_events` 为准。

userdebug/eng 调试镜像可以把 profiling 扩大到更多系统进程。遇到空结果时先按这张清单核对资格与配置，而不是先怀疑查询写错。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q8 -->

**Q8: [learning] Android 13 的 android.os.Trace 公开 API 有哪些？同步区间、异步区间和 Counter 怎样正确使用？**

公开 API 只有 6 个方法，AAOS13 的 `frameworks/base/core/api/current.txt` 核对了这一公开面：

1. **同步区间：**`beginSection(String)` / `endSection()`，API 18 引入。
2. **异步区间：**`beginAsyncSection(String, int)` / `endAsyncSection(String, int)`，API 29 引入。
3. **数值与状态：**`setCounter(String, long)` 和 `isEnabled()`，API 29 引入。

把异步与 Counter 写成 API 18 能力会让低版本应用在验证或运行时失败。

使用约束：同步区间必须在同一线程按栈结构嵌套，`endSection()` 结束当前线程最近未闭合的 section，异常路径要用 `finally` 保证闭合。异步区间可跨线程、不要求嵌套，但 begin/end 必须用相同名称与相同 `int` cookie 配对，时间重叠的同名任务必须用不同 cookie。`setCounter` 每次写入一个绝对值样本，不要用大量零时长区间模拟计数器。名称上限是 127 个 UTF-16 code unit（AAOS13 `Trace.java` 的 `MAX_SECTION_NAME_LEN = 127`，一个补充平面字符占两个单位），`|`、换行与空字符由底层替换为空格。超长名称抛 `IllegalArgumentException`。名称宜用稳定操作名，动态 ID 放进 cookie 或 Counter，避免产生大量难以聚合的 slice 名称。抓取配置还要把包名放进 `atrace_apps`，否则 App 的 `ATRACE_TAG_APP`（值为 `1 << 12`，AAOS13 `cutils/trace.h` 核对）事件不会进入这次 system trace。`isEnabled()` 只适合控制观测开销（如懒构造格式化字符串），不能参与鉴权或业务分支。

带 `traceTag` 参数的 `traceBegin`、`asyncTraceForTrackBegin/End`、`instant` 等重载是 `@hide`/`@SystemApi`，不属于普通应用 SDK（AAOS13 源码核对）。NDK 侧 `<android/trace.h>` 的同步 API 自 API 23 起可用，异步与 Counter 自 API 29 起（AAOS13 头文件注释核对）。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q9 -->

**Q9: [learning] 在 Android 13 上，Trace.beginSection() 的事件实际走哪条路径？“每个埋点都双写 ATrace 和 Perfetto”的说法对吗？**

AAOS13 上 Java Trace 走单一 libcutils 路径：JNI（`android_os_Trace.cpp`）把各 API 直接转发给 libcutils 的 `atrace_begin/end/async_begin/async_end/instant` 等函数（本地源码核对），经典 `ATRACE_*` 实现在 `trace-dev.cpp` 中打开 `/sys/kernel/tracing/trace_marker`（失败回退 `/sys/kernel/debug/tracing/`），写文本 marker 进 ftrace 的 print 事件，再由 Perfetto 解析成 slice 或 counter。

marker 编码与刷新机制（AAOS13 源码核对）：

1. 同步区间 `B|<pid>|<name>` / `E|<pid>`。异步区间 `S|...|<cookie>` / `F|...|<cookie>`。Counter `C|...|<value>`。即时事件 `I|...`。带 track 的私有 API 用 `G`/`H`/`N` 前缀。
2. 单条消息缓冲上限 1024 字节（`ATRACE_MESSAGE_LENGTH`）。
3. tag 启用状态来自 `debug.atrace.tags.enableflags` 属性，代码通过属性 serial 变化检测并刷新。应用事件由 `debug.atrace.app_number` / `debug.atrace.app_N` 选择。

“双写”属于后续版本行为：Android 15 起 Java Trace 才接入 `tracing_perfetto` 按 category 状态选择 backend，Android 17 再补 `debug.atrace.prefer_sdk` 的并发会话仲裁（材料口径）。AAOS13 的 `frameworks/native/libs/` 下没有 `tracing_perfetto` 库，`Trace.java` 的 `setAppTracingAllowed()` 注释也写明自 Android S 起是 no-op——在 A13 语境下讨论双写会错误估计开销并预期不存在的重复事件。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q10 -->

**Q10: [learning] 调大 TraceConfig.buffers.size_kb 为什么不一定解决丢包？stats 表应该怎么查、哪些项容易漏？**

因为丢包可能发生在三层 buffer 的任何一层，`buffers.size_kb` 只作用于第三层：内核 per-CPU ftrace buffer（由 `ftrace_config.buffer_size_kb` 控制且单位是每 CPU）、Producer 与 service 之间的 shared memory、以及 `traced` 的 central buffer。只调大 central buffer 不解决 ftrace overrun，也不解决 producer 突发写满 shared memory。

诊断入口是 `stats` 表，基础查询列出非零的数据丢失与错误：

```sql
SELECT name, idx, severity, source, value
FROM stats
WHERE severity IN ('data_loss', 'error') AND value != 0
ORDER BY severity, name, idx;
```

`idx` 是 buffer 或 CPU 下标，`source` 区分问题来自 trace 自带统计（`trace`）还是导入分析阶段（`analysis`）。还要检查两个容易漏掉的现象：

1. **统计级别不一致：**`traced_buf_chunks_overwritten`、`traced_buf_chunks_discarded` 与 `ftrace_cpu_overrun_delta` 在 v54 时代标为 `info`，只筛 `data_loss` 会漏掉 central buffer 覆盖与 ftrace overrun，应按名称单查。
2. **解释数据丢失：**环形覆盖可能让 interned string 或进程映射丢失，后续 sequence 因缺少解释条件而无法进入分析表，对应 `traced_buf_incremental_sequences_dropped`。缓解方式是对声明支持的数据源配置 `incremental_state_config.clear_period_ms`，或把低频解释性数据源放进独立 `target_buffer`。

有丢失后能否继续分析取决于损失所在的 CPU、buffer、时段是否在结论路径上。重采成本低时先修配置重采。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q11 -->

**Q11: [learning] 大 trace 怎样可靠解析并保持查询结果可复现？**

先在采集端控制数据量（缩短窗口、减少无关数据源），再用本机原生 Trace Processor 承载解析：Perfetto UI 默认在浏览器内用 WebAssembly 引擎解析，官方文档给出的典型站点内存上限约 2 GB（指运行时内存，不是文件大小）。执行 `./trace_processor server http trace.pftrace`（默认监听 `127.0.0.1:9001`）后 UI 可探测并改用本机后端——它解除浏览器内存限制，但不降低 Trace Processor 自身的完整解析内存需求。

其他手段按需求选择：

1. **不需要通用 ftrace 原始表：**用 `--no-ftrace-raw` 降低内存。
2. **需要其他输出格式：**`traceconv` 可转文本或 JSON，但结果通常更大，也不是无损往返。
3. **需要批量查询：**`BatchTraceProcessor` 为每份 trace 启动独立实例；官方粗算内存约 `2 × 平均文件大小 × Trace 数量`，批大小按主机内存实测。版本固定是复现的硬前提：Trace Processor 的表、标准库模块和查询行为随版本演进，`pip install perfetto` 的包会绑定对应二进制版本，生产流水线应在依赖锁文件固定包版本或用 `bin_path` 指向受审核的二进制并记录校验和。`fetch_latest_trace_processor` 会让同一脚本在不同日期下载不同二进制，产生版本漂移。trace 含进程名、线程名与业务标识，`server http` 只应监听回环地址，公开 URL/外发前先脱敏。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q12 -->

**Q12: [learning] 某条轨道没有数据时，按什么顺序排查最快？**

按数据实际经过的路径把问题分成四类，逐层确认：

1. **事件不存在**：在目标设备的 `available_events` 中确认事件已注册（GKI、厂商配置与驱动实现都会改变可用集合）。
2. **配置未启用**：核对配置使用正确的 `group/event`、atrace category 与 `atrace_apps` 包名，再查 trace 的 `unknown_ftrace_events`、`failed_ftrace_events` 与 atrace 错误（事件名未知/启用失败）。
3. **采集途中丢失**：用 `ftrace_event` 原始表确认事件是否进入 trace，再按 `stats` 的三层统计定位丢失层（ftrace per-CPU、shared memory、central buffer）。
4. **解析未派生**：有些事件只有原始表没有领域表，确认是否缺少专用解析器或应改查 `ftrace_event` + `args`。

两个版本相关点：AAOS13 的 Java Trace 经 libcutils 写入 trace_marker，Android 15+ 才有 TrackEvent 路径可选。用户空间埋点不可见时先查包名是否进了 `atrace_apps`、事件是否落在采集窗口内。定位后一次只改一个采集变量，并用同一复现验证。文件成功生成只证明会话结束并拿到输出，不证明数据完整。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q13 -->

**Q13: [learning] slice.dur、thread_dur 与 Self Duration 三种时间分别是什么？为什么 NULL 不能按 0 处理？**

三种时长量的是不同对象：

1. **`slice.dur`：**slice 起点到终点的墙上时长，线程在这段时间可能运行、等待 CPU、休眠或阻塞。
2. **`thread_dur`：**slice 消耗的线程时间。只有 Track Event 开启线程时间采集时才填充，Android ATrace 产生的普通 slice 往往没有这个字段。
3. **Self Duration：**`slices.self_dur` 标准库计算父 slice 墙上区间扣除同栈子 slice 覆盖后的自身墙上时长，不等于函数自身的 CPU 时间，也不扣除另一条线程上的异步工作。

需要某个 slice 内的 on-CPU 时间时，把它的半开区间 `[ts, ts + dur)` 与 `thread_state.state = 'Running'` 求交。NULL 与 0 含义不同：缺失值表示没有这份数据，零表示测量结果为零——把 `thread_dur` 的 NULL 当 0 会把“无线程数据”误读成“没有消耗 CPU”。长 slice 只证明命名区间长，判定 CPU 重的依据是状态分解或线程时间，不是 slice 宽度。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q16 -->

**Q14: [learning] state track 是什么？在 Android 13 平台上能用吗，等价替代怎么做？**

state track 是 Perfetto v57.1 引入的单值状态轨道：一条轨道任一时刻至多一个当前值（无值即 idle），适合表达播放器 `Buffering → Playing → Paused` 这类单值状态机。二进制格式上体现为 `TrackEvent.Type` 增加 `TYPE_STATE = 5` 与 `StateDescriptor`，查询侧形成 `state` 表（状态列名为 `value`，持续时长 `dur` 已由分析器维护，`dur = -1` 的开放行要裁到 `trace_bounds.end_ts`，不应再用 `LEAD()` 重算）。

能否使用取决于两端版本：producer 与 Trace Processor 都具备 v57 能力才会产生和识别原生 state track。Android 17 平台内置的 Perfetto 是 v54 时代快照、枚举止于 `TYPE_COUNTER = 4`，AAOS13 平台更不可能提供（工具侧结论按材料口径，本地树无 `external/perfetto`）。等价替代按数据含义选择：

1. **有明确起止的状态：**用 slice。
2. **某时刻的状态值：**用 counter。
3. **状态转换提示：**用 instant，生产端自行保证状态合法。这些替代缺少单值约束与专用展示，SQL 与指标名应标明所用口径。若指标需要 idle 时间，注意 state 表只为有值的区间生成行，idle 要么并入观测窗口分母、要么按相邻状态行构造，报告中说明口径。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q21 -->

**Q15: [learning] Perfetto 的 Flow 能证明什么？读一个同步 Binder 调用要检查哪些环节？**

Flow 是 trace 显式记录的跨 track 关联（UI 中的箭头），只表达“数据声明两者相关”，不能自动证明同步阻塞或单一因果。Binder 分析依赖驱动 tracepoint 与 Trace Processor 解析：同步调用可关联客户端 transaction、服务端 transaction 与 reply。oneway 是单向异步调用，没有同步 reply，客户端不等待服务端完成。

读一个同步 Binder 调用按序检查：

1. 客户端 transaction slice 的墙上时长与线程状态。
2. 服务端 Binder 线程何时开始处理，transaction 到开始执行之间是否有排队时间。
3. 服务端 slice 内部是 Running、锁等待、D 状态还是再次发起 Binder。
4. reply 何时返回，客户端从唤醒到 Running 又等了多久。

嵌套 Binder、回调和线程池排队会让路径分叉，第一条箭头不能代表完整调用树。批量统计应使用 `android.binder` 标准库（工具侧能力，材料口径）而不是按名称通配 slice。FrameTimeline 也用 flow 把应用帧与 SurfaceFlinger display frame 通过 surface frame token 关联，比按时间重叠匹配可靠。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q23 -->

**Q16: [learning] Perfetto trace 里怎么找 lmkd 杀进程的证据？有哪些版本差异？**

没有名为 `android_lmk_proc_state` 或 `linux.lowmemorykiller` 的独立 data source。应组合以下证据：

1. trace 中的 lmkd 标记。
2. event log 的 kill 记录与 statsd 统计。
3. PSI 描述的内存压力环境。

AAOS13 的 `system/memory/lmkd/lmkd.cpp` 在杀进程路径上使用 `ATRACE_INT("kill_one_process", pid)` 计数器加 `ATRACE_BEGIN(desc)` / `ATRACE_END()` 区间（本地源码核对）——采集窗口覆盖 kill 且 atrace 配置放行时，trace 里可见该 counter 与 slice。

材料按 Android 17 核对的实现改为 `ATRACE_INSTANT_FOR_TRACK` instant 事件，属版本差异，读旧 trace 时按 A13 形态找 counter/切片，读新 trace 找 instant。配套证据：`LMK_KILL_OCCURRED` 统计包在 A13 源码中同样存在（材料口径：交给 statsd 与 AMS）。event log/logcat 的 kill 描述用于核对 pid、`oom_adj`、RSS 与 kill reason。

`/proc/pressure/memory` 的 `some` / `full`（some 表示至少有任务因内存压力停顿，full 表示所有非 idle 任务同时停顿）还原 kill 前的压力环境。`sched` 与进程生命周期事件确认 lmkd 何时运行、目标进程何时退出。`onTrimMemory()` 回调不能证明 lmkd 随后执行了 kill。分析低内存与卡顿的因果时先确认压力、回收、GC、调度的先后顺序。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q24 -->

**Q17: [learning] SPAN_JOIN 的输入要满足什么条件？违反约束会怎样？各变体怎么选？**

`SPAN_JOIN` 计算两组时间区间的交集，输入要求：两侧都有 `ts` 且至少一侧有 `dur`（做区间分析应两侧都显式提供）。区间为半开形式 `[ts, ts + dur)`。`dur > 0`，`dur = -1` 的开放区间先裁到查询窗口或 `trace_end()`。分区列必须是整数且两侧表达同一实体（`utid` 对 `utid`、`ucpu` 对 `ucpu`），`PARTITIONED` 两侧列名相同，也可以只给一侧分区。

最关键的约束：同一输入表在同一分区内区间互不重叠——算子按分区与 `ts` 推进游标，不做重叠检查，违反时静默产生错误行而不是报错。普通业务 slice 带父子嵌套，直接按 `utid` 送入会重复计算父子层。输入前用“前序最大结束时间”的窗口函数检查重叠，或用 `intervals.overlap` 模块的 `interval_merge_overlapping_partitioned!` 宏把相交区间合并成不重叠覆盖集（合并后丢失原始 `slice.id`，只适合统计覆盖时长）。counter 表是离散采样点不是区间，先按 `track_id` 分区用 `LEAD()` 补出前向区间（标准库 `linux.cpu.frequency` 已把 `cpufreq` 封装成 `cpu_frequency_counters`，优先用它）。

按输入区间与输出要求选算子：

1. **两侧同分区内互不重叠，只需交集：**用 `SPAN_JOIN`。
2. **需保留未匹配时间：**用 `SPAN_LEFT_JOIN` 保留一侧，或用 `SPAN_OUTER_JOIN` 保留两侧；它们用影子时间段补空档。
3. **输入有父子嵌套或必须保留事件身份：**改用普通区间条件或 `intervals.intersect`，不要让互相重叠的原始 slice 直接进入 `SPAN_JOIN`。

空分区表参与 `SPAN_OUTER_JOIN` 或作为 `SPAN_LEFT_JOIN` 右表时，即使另一侧非空也可能不输出行，自动化查询要覆盖空表测试。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q25 -->

**Q18: [learning] 自定义指标应该选标准库、Trace Summarization 还是 v1 指标？**

按依赖与复用成本选择：

1. **已有标准库模块：**先用 `INCLUDE PERFETTO MODULE`，确认模块语义与版本边界。
2. **新的长期指标：**用 Trace Summarization，在规格里声明维度、数值列、单位与极性，输出统一的 `TraceSummary` protobuf；标准库缺口可通过自定义 SQL 包和 `--add-sql-package` 注册。
3. **存量 v1 指标：**只有读取服务仍依赖 `TraceMetrics` 时继续维护；Android 17 标签与 v57.2 帮助已将其标为软弃用，接口继续工作但不新增功能。

v1 指标的约定（维护存量时需要）：同目录同名的一对 `.sql` 与 `.proto` 文件，proto 用 `extend TraceMetrics` 挂自定义消息，字段号使用本地保留的 450–500 区间且团队内登记。输出视图必须命名为 `{扩展字段名}_output`。计数器还原出的区间数据求平均时用 `DURATION_WEIGHTED_MEAN`（按样本覆盖时长加权），普通算术平均会让只持续 1 秒的样本与持续 9 秒的样本同权重。另注意两套“宏”不是一回事：SQL 侧的 `CREATE PERFETTO MACRO` 在查询执行前展开表/表达式片段（调用以 `!` 结尾），UI 侧的命令宏是 `Settings > Macros` 里的 JSON 命令序列。指标规格应同时记录依赖的数据源、适用版本、单位、方向与空值含义。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q26 -->

**Q19: [learning] Perfetto 指标进入 CI 前，怎样区分空值、零值与不可比样本？**

空表不等于性能为零。空结果应先查数据链上的缺口：

1. 采集配置未启用指标依赖的数据源，或目标进程未进入 `atrace_apps`。
2. trace 在目标事件发生前结束，或 `stats` 已记录丢包、截断。
3. 工具版本缺少规格引用的标准库模块。
4. 进程名、包名与查询条件不一致。CI 不能把空结果自动转成数值零：空值表示没有足够证据，零表示证据完整且测得为零——缺失值、零值与查询错误要分开存储，异常时保留原始 trace 与配置。

进 CI 后增加两层约束：

1. **可比性：**固定平台版本、设备与刷新率、场景脚本、采集配置和 Trace Processor 版本，再把 P50/P95/P99 与样本量一起解读；最大值对单次抖动敏感，不宜单独做门禁。
2. **阈值：**用固定设备、固定构建下未改码重复运行的噪声分布定门禁；“变慢 10%”或固定毫秒数没有跨场景通用性。凡是缺少 FrameTimeline、Binder、sched 等关键数据的结论都应降级为“不完整”并给出补采配置，而不是挑一个能算的数替代。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q27 -->

**Q20: [learning] 把 Perfetto 查询放进 CI 门禁时，怎么避免诊断配置污染测量？埋点开销怎么治理？**

把“测量”与“诊断”分成两层：门禁数值以 Macrobenchmark 输出的 Benchmark JSON 为准，Perfetto SQL 或 Trace Summarization 只生成诊断指标并保留异常样本的原始 trace。超阈值后用同一提交、更详细的诊断配置复测，但诊断结果不替换门禁样本。每次采集配置变更都重建基线，避免把工具开销变化记成产品性能变化。诊断配置会增加 ftrace 事件、堆采样与埋点频率，观测本身改变被测系统（probe effect），对小于几毫秒的阶段，插桩与调度抖动可能接近差异量级，需要 A/B 对照（不采集/最小配置/候选配置三组）与足够样本。

埋点治理按记录内容和开销分开：

1. **名称：**用稳定、低基数的操作名，动态标识放 cookie、counter 或受控字段。
2. **敏感数据：**账号、URL、文件路径、token 不进 trace。
3. **事件量：**高频循环先估算事件速率，再决定采样或缩小范围。开销没有跨设备固定值：官方经验值约为每个区间 5 μs（启用态 ATrace 单事件量级 1–10 μs，材料口径），trace 关闭时走快速检查但不是零开销，应目标设备实测。发布包不要用 `-assumenosideeffects class android.os.Trace` 全局清除——该 R8 规则假定方法无副作用，会删除应用与依赖库的所有调用点并让诊断能力随构建配置漂移。更可控的做法是包装自己的可选埋点，用构建期常量（如 `BuildConfig.ENABLE_APP_TRACE`）让 R8 只移除不可达分支。


<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q34 -->

**Q21: [learning] 为什么启动时间不要用 “bindApplication 到第一个 doFrame” 手工拼 slice？标准库提供了什么？**

手工拼接会漏掉 `system_server` 发起阶段、RenderThread 渲染、可见帧判定与温/热启动分支，而且起止 slice 名称随版本漂移。标准库提供两层可复核结果：

1. **启动实例：**`android.startup.startups` 输出 `android_startups` 与 `android_startup_processes`，保留平台识别的 `cold`/`warm`/`hot` 分类；分类取决于进程与 Activity 状态，不能按耗时长短命名。
2. **显示阶段：**`android.startup.time_to_display` 关联首帧与 `reportFullyDrawn()`，给出 TTID（首次显示耗时）与 TTFD（完全显示耗时）。TTFD 依赖应用调用 `reportFullyDrawn()`，空值不能当零毫秒。

两个使用边界：进程在启动期间死亡重建时，一个 `startup_id` 可能关联多个 `upid`，TTID/TTFD 仍是该启动实例的一组时间。`android.startup.startup_breakdowns` 会把主线程 slice 与 `thread_state` 裁成互斥区间并生成 `reason`，但 reason 是标准库的派生分类、不是设备直接记录的原始事件，适合筛选候选原因，仍要用原始时间线与源码确认。模块源码建议采集 Binder、ART、`am`、`view` 事件，缺少时分类变粗。统计启动区间内的 Binder 事务时，要同时满足 `upid` 匹配与时间相交，且各事务可能互相重叠——`SUM(client_dur)` 是时长之和，不是启动区间中的独占时间。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q1 -->

**Q22: [learning] 用 Perfetto 分析卡顿时，原生 system trace、pprof、Simpleperf protobuf 与 linux.perf 四种输入在 Trace Processor 中各落在哪些表，能各自证明什么？**

四种入口写入不同的表，SQL 不能混用同一套表名。只有同一次联合录制，调用栈采样才能与帧、调度、Binder 共用一条时间轴。按材料核对的 Android 17 Trace Processor 口径（工具链不在本地 AAOS13 树）：

1. **原生 system trace**：写 `slice`、`sched`、`thread_state`、FrameTimeline 与计数器，有事件时间、时长和线程状态。未启用调用栈采样时给不出函数热点。
2. **pprof**：写聚合表（`__intrinsic_aggregate_profile` 等，`__intrinsic_` 前缀是内部表名，不当跨版本稳定接口），只有调用树与每条栈的聚合值，没有逐样本时间轴，不能把一条调用栈放回某个具体帧。
3. **Simpleperf protobuf**：写 `cpu_profile_stack_sample`，样本带时间戳、线程与调用栈。导入器忽略上下文切换记录，文件里没有 Running、Runnable 或休眠证据。
4. **linux.perf**：写 `perf_sample`，可与 ftrace、FrameTimeline 联合录制在同一 trace 时钟下。采样命中数不能换算成单次函数耗时。

诊断分工：FrameTimeline、`slice` 与 `thread_state` 界定异常区间，区间内的 `perf_sample` 回答正在消耗 CPU 的调用路径。聚合 profile 适合快速找热点，但仍需调度数据确认目标线程得到多少 CPU、是否被抢占。全局火焰图不能代替帧级因果链。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q2 -->

**Q23: [learning] 用 Simpleperf 生成 Perfetto 可导入的 protobuf 时，为什么漏掉 --show-callchain 之后火焰图只剩叶节点？**

因为 `report-sample` 在未指定 `--show-callchain` 时会把调用链地址数组裁成一个元素，导入后每个样本只剩一帧栈，火焰图宽度全部堆在叶节点上（按材料核对的 Android 17 `cmd_report_sample.cpp` 口径转写，simpleperf 源码不在本地树）。完整数据流三步：`app_profiler.py` 在设备上录制二进制采样文件 `perf.data`，`report-sample --protobuf` 把它转成 Perfetto 可识别的 protobuf，Trace Processor 导入为 `cpu_profile_stack_sample`。

导出时要让录制、调用链转换和符号还原使用匹配的参数：

1. `-g`：录制时采集调用链；省略后导出阶段没有完整链可供恢复。
2. `--show-callchain`：导出完整调用链；省略时 `report-sample` 会裁成一个元素，火焰图只剩叶节点。
3. `--symdir`：指定递归搜索带符号文件的目录；目录缺少匹配文件时不能完成对应符号化。
4. `--proguard-mapping-file`：R8 混淆应用需提供该 APK 同一次构建生成的映射文件；映射不匹配会导致反混淆结果不可信。

导入检查可以先按进程与线程统计样本覆盖：

```sql
SELECT thread.name AS thread_name, COUNT(*) AS sample_count,
       MIN(sample.ts) AS first_ts, MAX(sample.ts) AS last_ts
FROM cpu_profile_stack_sample AS sample
JOIN thread USING (utid)
GROUP BY thread.utid ORDER BY sample_count DESC;
```

结果能暴露包名选错、目标线程没被采到或录制区间过短。样本数是采样命中计数，不能解释成函数执行毫秒数。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q3 -->

**Q24: [learning] 为什么“trace 文件能否被新版 Perfetto 打开”和“设备能否录到 linux.perf”是两条独立版本线？Android 13 设备怎么录 CPU 采样？**

主机分析端与设备采集端的版本各自演进：Trace Processor 的格式支持（pprof 导入、TrackEvent 附栈等 v53/v54 能力）随新版升级，新 UI 能解析旧 trace。而 `linux.perf` 采集依赖设备侧 traced 与内核 perf 事件支持，按材料口径，Perfetto 官方采集文档把 Android 设备下限标为 Android 15，量产 `user` 构建还要求目标应用声明 `profileable` 或 `debuggable`。Android 13 设备的 traced 没有该数据源，CPU 采样走 Simpleperf protobuf 导入路径（Android 10–17 适用），只在主机端用新版 Trace Processor 打开。

下面的最小配置以 `com.example.app` 为目标，采集 10 秒的 CPU 调用栈样本，并同时记录调度事件和进程名。字段口径依据 Perfetto 官方 [TraceConfig proto 文档](https://perfetto.dev/docs/reference/trace-config-proto) 与 [Linux tracing cookbook](https://perfetto.dev/docs/getting-started/linux-cookbook)。它需要支持 `linux.perf` 的设备和相应采集权限，不应假定普通 user build 可原样运行：

1. `kernel_frames: true`：需要 `traced_perf` 以 root 运行，或手动放开 `kptr_restrict`；Android debug build 通常由平台处理这一权限。
2. Android 的 `linux.process_stats` 全进程扫描读取 `/proc` 进程文件：采集进程需具备相应读取权限并通过 `PTRACE_MODE_READ` 检查；procfs 的 `hidepid` 也可能限制访问。官方建议相关采集进程以 root 运行，具体可用方式依设备而定。

```text
duration_ms: 10000
buffers { size_kb: 40960 fill_policy: DISCARD }
data_sources {
  config {
    name: "linux.perf"
    perf_event_config {
      timebase {
        counter: SW_CPU_CLOCK
        frequency: 100
        timestamp_clock: PERF_CLOCK_MONOTONIC
      }
      callstack_sampling {
        scope { target_cmdline: "com.example.app" }
        kernel_frames: true
      }
    }
  }
}
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    process_stats_config { scan_all_processes_on_start: true }
  }
}
```

三个数据源各自提供不同证据：

1. `linux.perf` 提供函数调用栈。
2. `linux.ftrace` 提供调度状态变化。
3. `linux.process_stats` 在 trace 开始时扫描进程并提供进程/线程名称上下文。

三者数据互补，不能从采样栈单独推导 Running、Runnable 或休眠时间。

配置值及省略后果如下：

1. **`duration_ms: 10000`：**将采集限制为 10 秒，便于复现并控制开销；省略时 TraceConfig 不设定时长，由采集入口/调用方决定何时停止。
2. **`size_kb: 40960`：**分配 40 MiB trace buffer。官方 proto 未给 `size_kb` 的省略值作固定容量承诺，配置时应显式设定；不要把缺省容量当成本例大小。
3. **`fill_policy: DISCARD`：**缓冲区写满并遇到未读数据时停止接收新 packet，保留已有数据。省略该字段时默认使用 `RING_BUFFER`；writer 快于 reader（或停止采集后才读取）时，新 packet 会覆盖旧 packet。显式选 `DISCARD` 是为了本例保留采集窗口开头的样本。
4. **`name: "linux.perf"`：**选择 Linux perf 数据源；未配置此数据源就不会有 perf 样本。
5. **`counter: SW_CPU_CLOCK`：**以软件 CPU 时钟为 timebase；Perfetto 文档用它作为周期性采样计时事件。显式选择可让配置意图明确；不设置 counter 的结果未由该文档承诺，需按目标版本 schema/producer 核对。
6. **`frequency: 100`：**请求每 CPU 每秒约 100 次采样，符合官方 cookbook 示例。内核可动态调整频率，也可能限频，因此它不是实际采样率。省略 frequency 后是否由其他 timebase 字段决定，取决于配置内容；单独省略时没有文档承诺的固定采样率。
7. **`timestamp_clock: PERF_CLOCK_MONOTONIC`：**显式选用单调时钟，便于与 trace 的调度时间域关联。省略时官方 proto 指定由实现选择；合并/比较数据源前应核验 trace clock metadata 与转换快照。
8. **`callstack_sampling`：**开启用户态调用栈展开；省略整个消息时只记录事件计数，不产生调用栈样本。若消息存在而 `user_frames` 未设置，官方 proto 说明默认包含用户态展开（DWARF）。
9. **`scope.target_cmdline`：**仅保留 `/proc/<pid>/cmdline` 匹配 `com.example.app` 的进程样本。省略 scope 会保留全部进程，unwinder 负担可能升高并丢样。
10. **`kernel_frames: true`：**将内核栈帧加入样本，以调查 CPU 热点是否进入内核；省略时 proto bool 默认 false，不采内核帧。需要 `traced_perf` 以 root 运行，或手动放开 `kptr_restrict`；Android debug build 通常由平台处理这一权限，实际行为仍需按设备核对。
11. **`sched_switch`、`sched_waking`：**记录线程切换与唤醒事件，用于区分线程何时运行、何时被唤醒；省略 `linux.ftrace` 数据源不会有这些调度事件。
12. **`scan_all_processes_on_start: true`：**在采集开始时扫描所有进程，补充名称和分组上下文；省略/false 时不做启动全量扫描，但 ftrace 看到线程时仍可触发进程信息补采。访问 `/proc` 仍受采集进程权限、`PTRACE_MODE_READ` 和 procfs `hidepid` 限制；没有权限时应关闭启动全量扫描，并接受进程上下文可能不完整。全量扫描会增加开始时的采集工作。

`target_cmdline` 是进程筛选条件，不是 Linux perf 的安全访问授权。采样频率、CPU 数、Java/JIT 展开与内核帧都会影响开销，必须随结果记录。

按配置验证采集结果：

1. 检查 trace metadata 中的采集时长和时钟信息，确认窗口覆盖目标操作，且时间戳可与同 trace 的其他轨道对齐。
2. 查询 `perf_sample`，按线程统计样本数并查看首末时间；目标应用完全没有样本时，检查包名匹配、应用是否运行、设备是否支持该数据源及权限。
3. 查看 `unwind_error` 与 `stats` 中名称匹配 `perf_*` 的非零错误项，区分未展开栈、采集失败与真实的零样本线程。
4. 将样本数理解为采样命中，不换算成函数执行毫秒；需要调度状态时与同 trace 的调度事件交叉验证。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q5 -->

**Q25: [learning] 录好的 trace 里函数名是地址或混淆名，怎么补 native 符号与 R8 映射？**

可读函数名依赖采集产物与构建产物匹配：native 栈需要正确的 ELF、Build ID 和展开信息。Java/Kotlin 混淆栈需要与被测 APK 同一次构建生成的 `mapping.txt`。路径里有同名 `.so` 但 Build ID 不匹配时，不能用它证明线上地址对应某个函数。内联函数会让一个机器码地址对应多层源码调用关系，v53 起 UI 能标出 inline frame，分析应保留这些层级。缺少 DWARF 内联记录时，“火焰图里没有某函数名”推不出“该函数未执行”。

对已录好的原生 trace，按材料核对的 Android 17 流程有两条：`PERFETTO_BINARY_PATH` 指向系统或应用的 ELF 符号目录；未设置或目录不含匹配 Build ID 时不能完成对应符号化。

```bash
PERFETTO_BINARY_PATH="$ANDROID_PRODUCT_OUT/symbols" \
traceconv symbolize raw-trace.perfetto-trace > symbols.pb
PERFETTO_PROGUARD_MAP="com.android.settings=$R8_MAPPING_FILE" \
traceconv deobfuscate raw-trace.perfetto-trace > deobfuscation.pb
cat raw-trace.perfetto-trace symbols.pb deobfuscation.pb > enriched-trace.perfetto-trace
```

`symbols.pb` 与 `deobfuscation.pb` 是额外的 TracePacket 流，拼接后才得到可直接打开的 trace。`PERFETTO_PROGUARD_MAP` 格式固定为 `包名=映射文件`，多个包用冒号分隔，`R8_MAPPING_FILE` 必须来自被测 APK 的同一次构建。另一种交付方式 `traceconv bundle` 输出 TAR 归档（内含 `trace.perfetto`）：Android 17 固定源码中的 bundle 选项没有 `--proguard-map`，映射仍走环境变量。当前 v57.2 已支持可重复传入的 `--proguard-map` 并成为官方推荐，手工拼接方式主要用于兼容旧流水线（版本口径按材料转写）。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q7 -->

**Q26: [learning] Agent 写 Perfetto SQL 之前必须固定什么？哪些口径错误会让语法正确的查询给出误导结果？**

先固定执行环境再写查询：保存 `trace_processor --version` 输出与二进制来源，对实际二进制检查表和列，不凭记忆编造字段。三类入口要分清：`slice`、`thread_state`、`thread`、`process` 等基础表由 Trace Processor 预置。`android.startup.startups`、`android.frames.timeline`、`android.binder`、`slices.time_in_state` 等标准库模块要用 `INCLUDE PERFETTO MODULE` 加载。同名 metric 表不能因为加载了标准库就假定存在（模块清单按材料核对的 Android 17 标签与 v57.2 转写，Perfetto 源码不在本地树）。

常见口径错误与对策：

1. **身份错配**：join 一律用 trace 内唯一的 `utid/upid`，不用会复用的 `tid/pid`。
2. **未闭合区间**：`dur = -1` 统计时用 `trace_end() - ts` 替代，否则总时长与 overlap 计算出错。
3. **窗口边界**：时间窗查询用区间相交条件，只比较起点会漏掉跨边界长 slice。
4. **名称匹配**：已知全名用 `=`。通配用 `GLOB` 并注意 `*` 与 `?` 语义，不要把 `LIKE` 的 `_` 当普通字符。
5. **区间连接**：优先用公开模块。直接 `SPAN_JOIN` 时输入区间在每个分区内不能互相重叠，分区键要匹配 CPU、线程或进程语义。
6. **私有对象**：不调用下划线开头的表、视图或宏（如标准库内部的 `_interval_intersect!`），上游内部实现变化会让模板失效。
7. **静默排除**：Android 17 的 `thread_slice_time_in_state` 只纳入 `dur > 0` 的记录，分析未闭合区间要显式处理并标注边界。

兼容性分三层评估：基础表结构层（表存在不代表 trace 有数据）、Android 标准库层（受采集字段与版本限制，FrameTimeline 从 Android 12 起可用）、厂商扩展层（显示、thermal、功耗自定义轨道按设备建词典）。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q10 -->

**Q27: [learning] Perfetto 里 CPU 频率数据从哪三条采集路径来？频率轨道开头留白说明什么？**

三条路径互补（配置项按材料核对的 Android 17 Perfetto 文档口径）：

1. **事件驱动**：`linux.ftrace` 抓 `power/cpu_frequency`（频率切换时刻）、`power/cpu_frequency_limits`（policy 最小最大频率约束更新）与 `power/cpu_idle`。
2. **轮询快照**：`linux.sys_stats` 用 `cpufreq_period_ms`（正数毫秒周期）轮询 CPU 频率；省略或设为 0 时不会启用该轮询。
3. **一次性系统信息**：`linux.system_info` 在采集开始时读取可用频点。

三条路径都可能因驱动、内核配置、权限或 sysfs 导出差异而缺失，采集后先查数据完整性。轨道开头留白只说明该 CPU 在 trace 开始后没有发生频率切换、事件驱动没有记录，不能解释为 CPU 停止运行。轮询能补快照，却会漏掉采样点之间的瞬时切换，面向启动和掉帧的分析要保留事件驱动采集。

`power/cpu_frequency_limits` 值得与频率事件一起抓：它记录 `min_freq`、`max_freq` 与 policy 代表 CPU（Android 17 内核行为，材料口径。内核源码不在本地树），能区分“governor 没有请求更高频率”和“当前上限不允许再升频”两类线索，限制来源还要结合 thermal、Power HAL、节电模式与厂商代码确认。USB 边界：许多 Android 设备接入 USB 时 USB 驱动栈持有 wakelock，部分 idle state 不会出现，USB 采集的 idle 分布未必代表脱线使用（材料转写的官方文档口径）。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q11 -->

**Q28: [learning] Perfetto 的 CPU 频率轨能当硬件实测时钟读吗？文档和实现有什么出入？**

不能。`power/cpu_frequency` 记录的是 cpufreq 路径报告的一次频率切换：按材料核对的 Android 17 内核，普通切换完成后写入 `freqs->new`，快速切换路径写入驱动返回的 `freq`，这是软件可见的请求或报告值，不保证硬件在整个区间都精确运行于该频率。轮询路径的口径更有出入：Android 17 的 Perfetto 文档把轮询描述为读取 `cpuinfo_cur_freq`，但同版本 `CpuFreqInfo::ReadCpuCurrFreq()` 实现读取的是 `scaling_cur_freq`。内核文档指出 `scaling_cur_freq` 多数情况下表示 scaling driver 最近请求的 P-state，未必等于硬件瞬时频率——核对某一版本时应以该版本实现为准，并保留文档与实现的差异。

要讨论有效执行周期或能耗，需要 PMU、AMU 计数器或硬件功耗计等补充证据。`cpuinfo_cur_freq`、`cpuinfo_avg_freq` 也只有在驱动具备反馈能力且 sysfs 允许读取的设备上可用。policy、governor 与 schedutil 的机制层不在本题展开，这里只强调 trace 口径：频率轨是状态信号，不是指令执行量、瞬时硬件频率或功耗。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q12 -->

**Q29: [learning] 怎样用 SQL 把 CPU 频率还原成时间区间并与目标线程的 Running 区间求交？为什么分区键必须用 ucpu？**

原始事件是 counter 行：`counter` 连接 `cpu_counter_track`，轨道名 `cpufreq` 的值单位是 kHz，`cpuidle` 的原始值 `4294967295` 表示退出 idle。标准库把原始值转成带 `dur` 的区间：`linux.cpu.frequency` 生成 `cpu_frequency_counters`（同时提供 `ucpu` 与逻辑 `cpu`），`linux.cpu.idle` 生成 `cpu_idle_counters`（`idle = -1` 表示 active）。`dur = -1` 表示区间延续到 trace 末尾，聚合与求交时要按查询目标处理开放区间。之后用 `SPAN_JOIN` 求调度区间与频率区间的交集：

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

分区键必须用 `ucpu`：`cpu` 是设备内的逻辑 CPU 编号，`ucpu` 是 Trace Processor 的可连接 CPU 标识（多机或虚拟化 trace 下仍唯一）。把 `sched.ucpu` 与 `cpu_counter_track.cpu` 直接比较会混用两个标识域。`cluster_id` 与 `capacity` 来自已采集的处理器信息，可与频点集合互相校验。目标主线程先用 `tid = pid` 定位 `utid`，之后全程用 `utid`，避开长 trace 中 tid 复用。统计结果要同时报告频率覆盖率：内连接舍弃了未知频率区间，“已知频率覆盖的 Running 时间中落在某频点的占比”不能解释成指令占比、周期占比或能耗占比。数据存在性以 SQL 为准：按材料转写的官方文档记录过缺少 idle 事件时 UI 不渲染频率轨但数据存在的显示问题。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q20 -->

**Q30: [learning] android.os.Trace/ATrace_\*、Perfetto SDK 的 track_event 与 custom data source 三层埋点怎么选？**

选择看两个维度：数据表达能力与谁控制采集，编程语言只是条件（SDK 细节按材料转写，Perfetto 源码不在本地树）：

1. **atrace 层**（`android.os.Trace`、`androidx.tracing`、NDK `ATrace_*`）：写同步 section、可跨线程的异步 section 与 counter，由系统 trace consumer 控制。适合 Java/Kotlin 页面、启动步骤与跨 Java/Native 的普通区间。官方建议平台接口已能表达所需信息时优先沿用，不需要为了工具统一而迁移现有埋点。
2. **track_event**：写 category、slice、custom track、flow、counter 与 debug annotation，in-process session 或外部 consumer 都可控制。SDK 已处理线程安全、增量状态、string interning 与 flush，Trace Processor 直接生成 `slice`、`counter`、flow 等标准表，是 SDK 的默认选择。
3. **custom data source**：仅当以下条件同时成立才用：

    1. slice、counter 与调试参数无法自然表达数据。
    2. 高频记录对每个 TracePacket 大小敏感，需要稳定的 protobuf schema。
    3. 团队愿意同步维护 Trace Processor importer、内部存储表与查询。
    4. schema 已定义字段编号、单位、枚举演进与兼容策略。

custom data source 写出的私有 protobuf 不会自动变成 PerfettoSQL 表：没有对应 schema 与 importer 的官方 Trace Processor 会跳过未知字段，原始字节还在 trace 里但 SQL 查询不到业务数据。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q21 -->

**Q31: [learning] 应用用 Perfetto SDK 起一个 in-process session，有哪些容易踩错的关键点？**

in-process backend 把 tracing service、consumer 与 producer 都放在当前进程，不连接 `/dev/socket/traced_producer`。产物只含注册到该进程内 backend 的数据源，没有 ftrace、Binder、SurfaceFlinger 或其他进程事件（SDK 细节按材料转写）。关键点按次序：

1. **初始化先于事件**：`Tracing::Initialize(args)`、`TrackEvent::Register()` 与 category 静态存储都要在第一个事件写入前完成。
2. **配置生效时点**：`StartBlocking()` 返回后配置才处于活动状态，此前执行的 `TRACE_EVENT` 不属于这次 session。
3. **输出二选一**：`Setup(config, output_fd)` 直接写入应用以私有权限打开的文件（调用方保持 fd 有效并在停止后关闭），或不传 fd 用 `ReadTraceBlocking()` 读取 consumer buffer。同一个 session 只选一种方式。
4. **收尾**：先 `TrackEvent::Flush()` 把 writer 未提交数据推给 service，再 `StopBlocking()` 结束 session。

`buffer_size_kb` 按目标设备上的事件率、最长采集时间与丢包统计确定，没有通用常量：ring buffer 写满后覆盖旧 packet，`DISCARD` 策略写满后舍弃新 packet，两者丢失的时间段不同。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q22 -->

**Q32: [learning] 应用以 system backend 充当 Perfetto producer 时，权限边界在哪里？Android 13 与 Android 17 有什么差别？**

producer 只能向设备上的 `traced` 服务提交数据。采集配置、buffer、输出文件与读取权限都归有 consumer 权限的一方管理，能连接 producer socket 不代表能启动或读取整机 trace。版本差异是关键（本地核对）：Android 13 的 `system/sepolicy/private/app.te` 用 `userdebug_or_eng(\`perfetto_producer({ appdomain })\`)` 门控——`user` 构建上普通应用进程没有 producer 权限。材料核对的 Android 17 已改为无条件 `perfetto_producer(appdomain)`。这直接影响线上方案：Android 13 user 机的 SDK system backend 埋点拿不到数据，应退回 `android.os.Trace`/`ATrace_*` 由具备权限的 consumer 采集。

应用只贡献 TrackEvent 时，初始化可设 `enable_system_consumer = false` 并在代码中不显式调用 `NewTrace(kSystemBackend)`，让 linker 从最终二进制移除未使用的 system consumer IPC 代码——该字段只帮助构建工具裁剪，不负责授权，应用代码仍不得创建 system consumer，也不调用 `ReadTraceBlocking()`。adb 或受控环境采集时，外部 TraceConfig 要显式请求 `track_event` 并在 `TrackEventConfig` 中启用目标 category。抓到 ftrace 却没有应用 slice 时，依次检查 producer 是否连接、track_event descriptor 是否上报、category 是否匹配、事件是否发生在采集窗口内。manifest 的 `profileable`/`debuggable` 影响部分平台 profiler 与 shell profiling 能力，但不会让应用获得 system trace consumer 权限。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q23 -->

**Q33: [learning] custom data source 的生命周期回调各有什么约束？停止协议为什么要异步确认？**

custom data source 继承 `perfetto::DataSource<T>`，每个活动 trace session 创建独立实例（SDK 细节按材料转写）。四个入口的约束：

1. `OnSetup`：解析本实例配置、准备有界状态。`SetupArgs::config` 只在回调期间有效，不能保存指针。
2. `OnStart`：启动子系统采样。回调可能来自 Perfetto 内部线程。
3. `Trace(lambda)`：按活动实例写 packet。没有活动实例时 lambda 不执行（计算成本高的参数应放 lambda 内），并发 session 会让同一 lambda 执行多次。
4. `OnStop`：停止采样并写收尾 packet。不应长时间阻塞 Perfetto 回调线程。

访问实例状态时用 `GetDataSourceLocked()` 取得带锁句柄，避免 session 停止与业务线程写入同时发生时访问已销毁的实例。`OnStop` 中存在异步清理时，调用 `StopArgs::HandleStopAsynchronously()` 取得 acknowledgement closure：清理线程写完末尾 packet 后，要在最后一次 `Trace()` lambda 中显式调用 `TraceContext::Flush()`，再执行该回调。整个过程必须在 consumer 配置的 stop timeout 内完成，超时后服务强制停止，之后写出的末尾数据不会进入 trace。

强类型 packet 需要扩展 TracePacket schema，完整实现至少同步维护以下部分：

1. 定义稳定的 protobuf 字段与编号。
2. 生成 pbzero 写入接口。
3. 在 Trace Processor importer 中解析并写入存储。
4. 为 schema 兼容与损坏 packet 补测试。

团队无法长期维护 Trace Processor fork 时，退回 TrackEvent 的 category、slice、counter、flow 与长度受限的 debug annotation。数据源名称用团队控制的反向域名，减少与其他 producer 的命名冲突。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q24 -->

**Q34: [learning] Perfetto SDK 的 startup tracing 与 trigger 分别解决什么问题？为什么故障后才启动 trace 看不到故障前？**

普通 session 只能记录它启动之后的事件。startup tracing 解决“启动最早期也要采到”：先让 data source 写入临时目标缓冲区，等待后续 system session 用匹配的配置接管这些数据（按材料转写的 Android 17 SDK 口径）。边界：默认超时 10 秒，超时、配置不匹配或 service 不接受 producer 提供的共享内存时 startup session 终止。只支持 system backend，in-process 场景应在目标初始化工作前创建普通 session 并等待 `StartBlocking()` 完成。只调用 `SetupStartupTracingBlocking()` 不会生成可读取的系统 trace 文件，还需要外部 consumer 在超时内提交可匹配配置。`on_setup`、`on_adopted`、`on_aborted` 回调用于记录临时数据是否被接管或等待中止。

trigger 解决“条件满足时给已配置 session 发信号”：`ActivateTriggers(triggers, ttl_ms)` 只向当前已连接或在 TTL 内完成连接的 backend 发送信号，本身不创建 session，也不能让应用读取 consumer buffer。trace 的 ring buffer、停止策略与输出文件仍由 consumer 配置，`ttl_ms` 由调用方按 producer 连接策略确定。故障前数据的正确姿势是提前运行 session 并使用 ring buffer：故障后才启动 trace 只能看到故障后的活动。进程被 LMK 或 native crash 直接终止时，尚未提交的 producer chunk 和应用私有文件都可能丢失，线上方案要单独验证异常退出路径。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q25 -->

**Q35: [learning] 应用集成了 Perfetto SDK 后，它的 TrackEvent 会自动出现在 ProfilingManager 的 system trace 结果里吗？**

不会。按材料核对的 Android 17 `Configs.java`：`ProfilingManager` 的 system trace 固定配置包含 `linux.process_stats`、`android.packages_list`、目标 App 的 atrace、`linux.ftrace` 与 `android.surfaceflinger.frametimeline`，不请求 `track_event`，也不请求应用自定义 data source——SDK system backend 的 TrackEvent 不会自动进入其结果。APM 需要 SDK 专属事件时，只能由应用管理短时 in-process session 并上传自己的文件。需要系统调度证据时走公开 profiling API 或受控 consumer，两类文件的访问权限、脱敏状态与查询 schema 分别记录。

版本边界：`ProfilingManager.requestProfiling()` 从 API 35（Android 15）可用，触发式注册 `ProfilingTrigger` 从 API 36 可用。Android 13（API 33）没有这套 API，线上采集依赖 atrace 加具备权限的 consumer。请求受系统 rate limiter 限制且不保证执行，回调成功后用 `ProfilingResult.getResultFilePath()` 取交付文件。结果经过 redaction（按规则删除或模糊敏感字段），不能把未脱敏 trace 中可见的字段当成公开 API 保证。埋点设计按“这份文件可能被上传用于诊断”评审：category 只描述技术模块，事件名固定，动态值写入有类型、有上限的参数。用户标识、订单号、完整 URL、token、地理位置与原始堆栈不得写入。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q27 -->

**Q36: [learning] FrameTracer 的 trace 里缺少某个帧事件（如 AcquireFenceSignaled 或 RELEASE_FENCE），能说明什么、不能说明什么？**

缺事件有三种彼此独立的原因，都不能直接推断“该动作没有发生”：

1. **发射集合随版本变化**：AOSP 只在少数调用点 emit。材料核对的 Android 17 只有 DEQUEUE、QUEUE、ACQUIRE_FENCE、LATCH、FALLBACK_COMPOSITION、PRESENT_FENCE 六类，`HWC_COMPOSITION_QUEUED` 与 `RELEASE_FENCE` 没有 emit 调用。而按 AAOS13 源码核对，`BufferQueueLayer::onLayerDisplayed` 会发射 `RELEASE_FENCE`，BufferQueueLayer 还发射 `DETACH` 与 `CANCEL`——读旧 trace 不能按 Android 17 的发射集合假设。proto 枚举存在只表示协议允许写入，不代表会发射。
2. **pending fence 延迟补写**（按 AAOS13 源码核对 `FrameTracer.cpp`）：`traceFence` 遇到尚未 signal 的 fence 先存入 `pendingFences`，同一 buffer 以后再次触发 FrameTracer 调用时才补写。补写事件的 timestamp 仍用原 fence signal time，Trace Processor 按时间排序后出现在原位置。trace 结束前没有后续同 buffer 调用时，尾部 pending fence 没有机会补写。signal time 距检查时刻超过 60 秒（`kFenceSignallingDeadline`）的 pending fence 会被丢弃，防止上一次 trace 遗留的 fence 写进新采集。
3. **importer 分支**：acquire fence 在 `QUEUE` packet 被 importer 处理前已 signal 时，v57.2 importer 不创建 `GPU_` phase，避免生成起止顺序相反的区间。某帧缺 `GPU_` slice 不是数据损坏（importer 行为按材料核对的 v57.2 转写）。

另外 `QUEUE` 只表示 buffer transaction 携带的提交时间，不能当成 GPU 已完成，也不表示 SF 已采纳该 buffer。缺某一帧的 `AcquireFenceSignaled` 或 `PresentFenceSignaled` 要结合 trace 尾部、buffer 是否继续复用与采集区间判断，不能写成“fence 从未 signal”。


<!-- 来源：02-tracing/02-perfetto-advanced.md · Q28 -->

**Q37: [learning] FrameTracer 数据经 importer 生成的 APP\_、GPU\_、SF\_、Display\_ 四类 phase 各量什么？哪些误读最常见？**

查询入口是 `gpu_track` 与 `frame_slice`，筛选 `gt.scope = 'graphics_frame_event'`。`Buffer: <bufferId> <layer>` 轨道放原始事件，四类 phase 轨道由 importer 配对起止事件计算 `dur`（表关系按材料核对的 v57.2 importer 转写，Perfetto 源码不在本地树）：

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

1. **APP\_（DEQUEUE → QUEUE）**：Producer 持有 buffer 的时间，可能包含 CPU 准备、GPU 提交、主动 pacing、锁等待，不能统一命名“App 绘制耗时”，也不等于完整 `doFrame`（主线程工作可能发生在 dequeue 前）。
2. **GPU\_（QUEUE → ACQUIRE\_FENCE signal）**：对 HWUI 常反映等待 GPU 完成的时间。Camera、Video 与硬件 blitter 要按对应设备的完成信号解释，不能统一归为 GPU。
3. **SF\_（LATCH → PRESENT\_FENCE signal）**：覆盖 SF 调度、CLIENT/DEVICE 合成与显示后段的综合区间，量不出 HWC 或 RenderEngine 单独耗时。
4. **Display\_（相邻 present 间隔）**：反映帧节奏，30 fps 内容在 60 Hz 屏出现约 33.3 ms 间隔可能完全正常，且不等于 buffer 从 present 到可复用的 release latency。

常见误读还有：固定 16.5 ms 阈值在 90/120 Hz、VRR 与非整数帧节奏下误报，应与当前 expected timeline 和目标帧率比较。`dur = -1` 的未闭合 slice（如末尾的 `Display_`）不能参与均值、P95 或总时长统计。汇总查询里的 `NULL` 表示该 phase 没有闭合或未生成，不能用 `COALESCE(..., 0)` 把缺失证据改写成零耗时。同名 layer 在窗口销毁重建后 frame number 可能重新计数，长 trace 自动化要限制时间窗并保留原始 track name。


<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q39 -->

**Q38: [learning] 从 Perfetto slice 反查源码时，为什么要先确认 track 和进程？native 层的 trace 宏名怎么对应 slice 名？**

同名 slice 可以由多个模块产生：`measure` 出现在应用 UI 线程时通常指向 View traversal，出现在工具进程或厂商线程时含义完全不同。搜索源码前先记录 track 名称、进程/线程与 pid/tid、slice 完整名称、起止时间与相邻 slice、启用的数据源与 atrace 类别，以及 args、cookie、flow 等关联信息。

Java 层的对应关系：Framework 内部用 `Trace.traceBegin(tag, name)`，slice 名来自参数字符串。AAOS13 源码核对到 `Choreographer#doFrame " + vsyncId` 的拼接，trace 中应搜前缀 `Choreographer#doFrame`，不要把动态 VSync ID 写进查询。`ViewRootImpl` 的 performMeasure/performLayout/performDraw 生成 `measure`、`layout` 等子 slice。异步事件要用名称与 cookie 配对，不能按相邻关系配对。

native 层按宏的命名规则反查：`ATRACE_CALL()`/`ATRACE_NAME(x)` 的 slice 名分别取当前函数名和参数。版本差异要注意——AAOS13（Android 13）的 SurfaceFlinger 使用通用 `ATRACE_*` 宏（本地核对不存在 `common/include/common/trace.h`），Android 17 材料口径则是 `SFTRACE_*` 宏族，且 `composite()` 的 slice 名形如 `composite <vsyncId>`、`SFTRACE_NAME` 不会自动加类前缀。旧资料里的 `onMessageRefresh()`、`composeSurfaces()` 等函数名对应旧版本，不能直接套到新 trace。另外 atrace 类别只控制 atrace 事件，ftrace 调度事件、heapprofd 等数据源不受类别开关控制，预期 slice 缺失时先识别数据源类型再查配置。

<!-- 来源：03-resource-domains/05-cpu-scheduling.md · Q1 -->

**Q39: [learning] 分析 Perfetto 的 CPU 轨道前，为什么要先区分微架构、调度器 capacity、cpufreq policy 与能量模型性能域？**

四个概念回答不同问题，且经常重合却不保证一一对应：

1. **微架构：**描述同频下每周期大约完成多少工作，依据 SoC / Arm 资料。
2. **Capacity：**内核认为该 CPU 的最大相对算力与当前可用能力；`arch_scale_cpu_capacity()`、调度拓扑、温控或中断压力都会修正它。
3. **CPUFreq policy：**描述哪些 CPU 共享一套频率控制与可选频点，可从 sysfs 的 `cpufreq/policy*` 查看。
4. **能量模型性能域：**描述哪组 CPU 共享一张活跃态功耗成本表。两个 CPU 可以共享 policy 但 capacity 不同，宣传中的“中核”也不一定对应独立性能域。

CPU 编号更没有跨设备语义：CPU 7 可能是最高 capacity 的核心，也可能只是某个同构簇的成员。最高频率高也不代表 capacity 高，因为每赫兹工作量可能不同。可靠的识别路径是先读设备拓扑（各 CPU 的 `cpu_capacity`、`cpuinfo_max_freq`、policy 的 `related_cpus`），再与 Perfetto 的频率与调度轨道对照。量产设备可能隐藏这些节点，读不到时应转向设备内核配置或厂商源码，不能用最高频率替代 capacity。

内核调度机制（capacity 感知调度、EAS、EEVDF、schedutil）不在本地 AAOS13 树中（树内无 kernel 源码），本篇相关结论以材料按 Android 17 内核 android17-6.18 核对的官方调度文档为准。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q28 -->

**Q40: [learning] android_input_events 与 android.input.inputevent 的三张原始视图是什么关系？为什么不能桥接？**

两条独立链路。

`android_input_events`（标准库 `android.input` 派生，工具侧行为按材料口径）由 ATrace 与 FrameTimeline 派生：`sendMessage`/`receiveMessage` 建立四段消息节点，`UnwantedInteractionBlocker::notifyMotion` 提供事件 ID 与读取时间，`deliverInputEvent` 与 `Choreographer#doFrame` 建立帧关联——常规 user 量产构建即可采集，前提是配置包含 `input`/`view`/`gfx` 类别、目标应用与 FrameTimeline 数据源。

`android_motion_events`、`android_key_events`、`android_input_event_dispatch` 来自 `android.input.inputevent` 调试数据源，配置协议限定只能在 `userdebug`/`eng` 构建启用，记录 InputDispatcher 处理的原始事件字段与窗口分发决策。

两者不能当作同一张表拆分后的结果：原始视图的 `event_id` 是数值型，生命周期表的 `input_event_id` 来自 ATrace 名称（通常是 `0x` 前缀的十六进制文本），标准库没有公开稳定的桥接视图，`CAST` 后等值连接可能把格式差异或 ID 碰撞误当成同一事件。原始视图查询时还要注意：同一事件会投递给前台窗口、监视窗口等多个目标，`android_input_event_dispatch` 出现多行不是重复数据。坐标、按键码等敏感字段受脱敏等级影响可能为 NULL，查询必须允许缺失。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q29 -->

**Q41: [learning] android_input_events 的四段延迟和 end_to_end_latency_dur 分别怎么定义？怎么解读分段异常？**

四段往返延迟基于同一事件的四个消息节点（工具侧标准库定义，材料口径）：

1. **`dispatch_latency_dur`：**应用收到 − Dispatcher 发出。
2. **`handling_latency_dur`：**应用发 `FINISHED` − 应用收到。
3. **`ack_latency_dur`：**系统收到确认 − 应用发出。
4. **`total_latency_dur`：**系统收到确认 − Dispatcher 发出。

`end_to_end_latency_dur` 从 InputReader 读取延伸到关联帧呈现，即 `present_time - read_time`；无帧关联时为 NULL。

解读边界：输入通道基于 socket，`ack_latency_dur` 不是 Binder 往返耗时，归因 Binder 拥塞会带偏方向。`handling_latency_dur` 覆盖输入批处理与框架分发开销，不等同于某个业务回调的执行时间。分析前先做覆盖率检查（`COUNT(*)`、各字段 `IS NOT NULL` 的数量、`is_speculative_frame = 1` 的数量），`with_frame` 明显少于总行数时要核对 FrameTimeline 与场景完整性——覆盖率不同的两组分位数不可比。找慢样本按 `total_latency_dur` 排序后分段定位：`dispatch_ms` 高查 Dispatcher 与目标线程的调度状态，`handling_ms` 高展开应用接收线程，`ack_ms` 高查确认回传路径。单段偏高只是排查入口，不独立证明根因。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q30 -->

**Q42: [learning] 标准库把输入事件关联到帧的规则是什么？哪些情况会给出“不可信”的帧关联？**

关联按应用线程和帧顺序进行（工具侧 `android.input` 标准库，材料按 Android 17 核对）：

1. 在同一应用线程上查找与 `deliverInputEvent` 区间相交的 `Choreographer#doFrame`，相交即精确关联。
2. 没有交集时选择该线程上紧随其后的帧，标记 `is_speculative_frame = 1`。
3. 映射到 SurfaceFlinger 时选择不早于关联应用帧的首个未丢弃帧。

由此产生的限制：推测关联只说明时间最接近，不能证明该输入触发了该帧。被丢弃的应用帧会让 `frame_id` 指向后续未丢弃帧。一帧可以合并多个 MOVE 事件，输入行与帧不是一一关系。字段边界：标准库内部对象 `_input_read_time` 只匹配 motion 事件的 `notifyMotion` slice，按键事件可以有完整往返延迟却没有 `read_time`、`event_time` 与呈现延迟。`event_time` 是 InputReader 的 ATrace 输出携带的事件时间，比 `dispatch_ts` 更靠近设备事件，但不能描述成触摸控制器中断时间。把输入结果连接 `android_frames` 时应同时用 `frame_id` 与 `upid` 条件，避免不同进程复用 Vsync ID 造成误连。帧持续时间长不等于输入处理慢，还要展开 `doFrame`、RenderThread 与调度上下文。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q31 -->

**Q43: [learning] InputDispatcher 的 iq、oq、wq 队列计数器在 trace 里长什么样？怎么把采样点变成区间？**

AAOS13 的 `InputDispatcher.cpp` 用 ATrace 计数器记录三类队列（本地源码逐字核对）：

1. **`iq`：**尚未处理的全局入站队列。
2. **`oq:<窗口名>`：**该连接上等待写入应用输入通道的出站队列。
3. **`wq:<窗口名>`：**已写给应用、仍等待 `FINISHED` 确认的等待队列。
名称用固定 40 字节栈缓冲拼接（`char counterName[40]`），过长的通道名会被截断。旧文档中 "InputDispatcher inbound queue" 之类的名称不是源码写法，查询用 `GLOB 'oq:*'`、`GLOB 'wq:*'` 前缀匹配并 JOIN `process_counter_track` 限定 `system_server`。

counter 只在值变化时记录采样点、没有 `dur`，一个值持续到同轨道的下一次采样。要按“非零持续区间”分析时用窗口函数补时长：

```sql
SELECT track_id, ts, value,
  COALESCE(LEAD(ts) OVER (PARTITION BY track_id ORDER BY ts), trace_end()) - ts AS dur_ns
FROM counter ...;
```

`LEAD()` 按同一 `track_id` 取下一采样时刻，末条用 `trace_end()` 兜底。解读：瞬间非零是正常流转。持续的 `iq` 表示入站未消费完，持续 `oq` 表示连接上有待写入事件，持续 `wq` 表示还有事件在等确认——队列堆积能缩小范围，但不能单独证明是主线程、socket 写入还是 `system_server` 调度的根因。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q32 -->

**Q44: [learning] 分析输入 ANR 时，为什么 android_input_events 里可能没有卡住的那个事件？正确的证据组合是什么？**

`android_input_events` 的一行要求四个消息节点（发出、接收、FINISHED、确认）都匹配成功。触发 ANR 的事件往往停在等待队列里没有 `finish_ack`，因此不会出现在结果里——“表里没有”不等于“输入没发生”。

正确的证据组合有三份：

1. **ANR 时间锚点：**查 `android.anrs` 标准库的 `android_anrs` 表，读取 `system_server` 的 ErrorId、subject、计时器与 `anr_type`（工具侧材料口径），不要在 slice 表里按名称搜 "ANR"。`anr_dur_ms` 优先来自平台计时器；`default_anr_dur_ms` 只是 AOSP/Pixel 的参考默认值，OEM 可以改写，超时窗内用 `COALESCE(anr_dur_ms, default_anr_dur_ms)`。
2. **输入队列：**查同一窗口内 `wq:`/`oq:`/`iq` 的非零持续区间，确认堆积发生在哪类队列、是否持续到 ANR 附近。
3. **目标线程：**查目标主线程在超时窗内的运行、可运行、睡眠状态与长 slice。
已完成的往返延迟只用于观察超时前是否已经退化，不能替代未完成投递的现场（ANR 检测与超时契约本身见引言链接的 ANR 专题文档）。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q35 -->

**Q45: [learning] android_jank_cuj 为什么看不到第三方 App 的 CUJ？系统 CUJ 指标的输入和第三方路径分别是什么？**

因为标准库对输入有明确过滤：CUJ 必须是 `process_track` 上持续时间大于零、名称匹配 `J<*>` 的 slice，且进程名匹配 `com.android.*` 或 `com.google.android*`（工具侧 `android.cujs.base` 的源码行为）——第三方 App 写出同名标记也不会进表。本地 AAOS13 源码可佐证系统侧的产生机制：`InteractionJankMonitor` 用 `J<%s>` 格式写 CUJ 标记，`FrameTracker` 结束时经 `Trace.traceCounter` 写出 `J<CUJ>#totalFrames`、`#missedFrames` 等计数器。

系统 CUJ 指标汇合三组独立数据：

1. **CUJ 标记：**`J<CUJ>`、`FT#begin/endVsync` 等定义名称与 vsync 边界。
2. **聚合计数器：**Java `FrameTracker` 的 `totalFrames`、`missedAppFrames`、`missedSfFrames`、`weightedAppJank`、`weightedSfJank` 等提供事后汇总。
3. **逐帧时间线：**FrameTimeline 的 expected/actual 给逐帧时序与 App/SF 分类。

使用时注意：`weighted_missed_app_frames` 等是速率（jank/s），乘 `anim_duration_ms / 1000` 才是窗口内加权总量。expected timeline 缺失时指标用 16.6 ms 回退值，不能反推设备运行在 60 Hz。HWUI 的 C++ `JankTracker`（`kMissedDeadline`、`kSlowUI` 等，经 FrameMetrics 上报）是另一套实现，数据来源不能与 Java FrameTracker 混写。

第三方 App 的可执行路径是组合：AndroidX JankStats 收集应用内帧指标、用应用命名空间（如 `myapp.cuj.feed_scroll`）的异步 section 标记交互、再在 Perfetto 中用 FrameTimeline 与 `thread_state` 做逐帧分析——采集配置需加入 `atrace_apps` 与 FrameTimeline 数据源。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q30 -->

**Q46: [learning] 统计 FrameTimeline 的 jank 时，为什么应该用 jank_tag 而不是自己解析 jank_type 字符串？Android 13 与 17 的分类有什么差别？**

`jank_type` 是 bitmask 转换出的字符串，一帧可以同时带多个原因，用字符串包含关系自行分组容易把多原因帧漏算或重算。v57.2 提供 `jank_tag` 预计算分类（`Self Jank`、`Other Jank`、`Buffer Stuffing`、`SurfaceFlinger Stuffing`、`Dropped Frame`、`Non-perceivable Jank` 等），统计优先用它，并保存原始类型与工具版本，避免把不同 Perfetto 版本生成的字段混进同一基线（表与字段按材料核对的 v57.2 转写）。分类要与 `present_type`、`on_time_finish` 联合判读：`on_time_finish = 0` 只表示工作超出 ready deadline，不能当全部 jank 的过滤条件。Buffer Stuffing 常见 `on_time_finish = 1` 与 `Late Present` 并存。Android 17 的非卡顿集合（材料口径）：Buffer Stuffing、SurfaceFlinger Stuffing、Non Animating 与三个 display 状态位都位于 non-jank bitmask，不增加 severity score，却描述高延迟或显示状态变化，报告应单独统计，不能全部并入 `No Jank`，也不能全部算进应用 jank rate。

版本差异（本地核对 Android 13 的 `JankInfo.h`）：A13 的 `JankType` 枚举到 `SurfaceFlingerStuffing`（0x100）为止，没有 Android 17 新增的 `JANK_NON_ANIMATING`、`JANK_APP_RESYNCED_JITTER`、`JANK_DISPLAY_NOT_ON`、`JANK_DISPLAY_MODE_CHANGE_IN_PROGRESS`、`JANK_DISPLAY_POWER_MODE_CHANGE_IN_PROGRESS`（2048 到 32768 五个位）。A13 还把 `PredictionState::Expired` 直接分类为 `AppDeadlineMissed`（`FrameTimeline.cpp` 本地核对），读旧 trace 的分类统计要按版本分别解释。

身份对齐规则如下：

1. `expected_frame_timeline_slice` 与 `actual_frame_timeline_slice` 是内置表，无需 INCLUDE 标准库。
2. DisplayFrame 行的 `surface_frame_token` 为 `NULL`，不要按数值 0 判断。
3. 连接同一应用的 Expected 与 Actual 时，使用同一 `upid` 下的 `surface_frame_token`。
4. 连接应用帧与 SF 显示帧时，使用 `display_frame_token`。一个 DisplayFrame 组合多条 SurfaceFrame，因此重复的 display token 是正常的多对一关系，不能去重隐藏。

<!-- 来源：01-user-experience/01-rendering-and-responsiveness.md · Q8 -->

**Q47: [learning] FrameTimeline 单帧归因应按什么顺序取证？**

从用户可见帧向上游追溯，避免把恰好重叠的 Binder、GC 或长 slice 写成根因：

1. 复现单一场景，固定刷新率与显示模式，并辨认 Producer、Surface、layer 和合成方式。
2. 在应用 Actual Timeline 选异常 SurfaceFrame，读取 surface/display token、finish、present 和 jank 字段。
3. 沿 flow 找到对应 DisplayFrame，核对 SurfaceFlinger 侧是否也超期。
4. 按 JankType 展开责任线程与子系统，在目标帧窗口内检查 Binder、调度、锁、I/O、GC、GPU queue 和 fence。
5. 检查相邻帧，判断背压或合成方式变化是否跨帧传播，再在同设备状态和脚本下复测异常类型与帧间隔分布。

时间重叠只说明相关。要归因 Binder 或 GC，还须证明它位于责任线程或依赖路径，且时长足以解释 finish/present 偏差。直接依赖证据可定责，组合证据可写高可信假设，背景负载与温度变化只应列为待验证项。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q14 -->

**Q48: [learning] Perfetto 的线程状态（Running/R/R+/S/D 等）从哪里来？各状态能确认什么？**

Linux 的 `TASK_RUNNING` 定义为 0，同时覆盖“正在 CPU 上执行”与“具备运行条件”两种情况，只看内核状态值区分不了 Running 与 Runnable。Perfetto 用调度事件重建时间线：`sched_switch` 把下一个线程记为 `Running`、按 `prev_state` 为被切出的线程打开后续状态区间，`sched_waking` 关闭原阻塞区间、打开 `R` 并尽量记录 `waker_utid` 与 `irq_context`。`R+` 表示这次切出带有抢占标志（内核 tracepoint 在抢占时置位并在文本输出追加 `+`），但不含抢占者身份。

状态与能直接确认的事实（材料按内核 6.18 与 Trace Processor 解码口径转写。本地学习树不含内核源码，具体状态位以设备内核为准）：

1. **Running**：该区间线程在某颗 CPU 上获得执行。
2. **R / R+**：具备运行条件未获得 CPU。`R+` 额外说明上一次切出是抢占式。
3. **S**：可中断睡眠，等待显式唤醒。
4. **D**：不可中断睡眠。
5. **T/t/X/Z 及 I/P/W/K/N 等**：停止、被跟踪、退出与特殊调度状态，遇到复合状态保留原始 `end_state` 再对照内核定义。

数据落点是两张表：`sched_slice` 从 CPU 视角记录"哪个 `utid` 在哪个 CPU 上运行多久"，`thread_state` 从线程视角记录连续状态并附带 CPU、`io_wait`、`blocked_function`、waker 等可选字段。`dur = -1` 表示区间未闭合（trace 结束或数据丢失），聚合时应排除。`utid`/`upid` 是 Trace Processor 在一份 trace 内分配的线程/进程唯一标识，用于区分被操作系统复用后的 PID/TID。UI 颜色会随主题与版本变化，结论应写状态名或 SQL 值。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q15 -->

**Q49: [learning] 线程处于 D 状态能直接判为磁盘 I/O 慢吗？io_wait 和 blocked_function 什么时候才有值？**

不能。`TASK_UNINTERRUPTIBLE` 只说明等待条件不会被普通信号打断，来源覆盖块 I/O、swap、内存回收、页迁移、驱动等待和内核同步路径。磁盘 I/O 只是常见来源之一。没有进一步证据时应写“Uninterruptible Sleep，原因未由本次 trace 识别”。

两个字段都依赖采集 `sched/sched_blocked_reason` tracepoint，解释时仍要分开：

1. **`io_wait`：**来自任务 `in_iowait` 记账标志。值为 1 只提高 I/O 等待的可能性，不含文件名、设备或调用方。
2. **`blocked_function`：**来自 `__get_wchan()` 观察到的内核睡眠位置，需内核符号解析成功，例如配置 `symbolize_ksyms: true` 且权限允许；符号受限时可能为空。归因按证据走：`io_wait = 1` 且与块设备/文件系统事件重叠，查设备与调用栈。与回收、压缩事件重叠，查 PSI 与内存压力。`blocked_function` 指向驱动或同步路径，查对应子系统源码。非 Running 状态没有“正在执行的 CPU”，`blocked_function` 为空也不能反推“没有内核阻塞”。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q17 -->

**Q50: [learning] 线程 Running 区间很长说明什么？中断处理的时间会算到谁的头上？**

Running 只表示线程占据了某个 CPU 的任务时间线，可能在用户态执行，也可能在系统调用或异常处理的内核态执行。HardIRQ 会暂停当前任务但不一定发生 `sched_switch`，中断返回路径上执行的 SoftIRQ 同样不切换任务——这些时间都计入外层线程的 Running 区间。把整段 Running 都算成应用函数时间会系统性高估应用开销。

分层验证 Running 区间：

1. 先看应用或框架 slice 能否解释这段工作。
2. 再看 `linux.perf` 或 simpleperf 样本的 `cpu_mode` 与调用栈指向用户态还是内核态。
3. 核对同一 CPU 的 IRQ/SoftIRQ 轨道（`cpu_irq`、`cpu_softirq` 类型 track）覆盖了多少。

`ksoftirqd/<cpu>` 是独立可调度线程，统计时与中断路径执行分开以免重复计算。CPU 编号、`capacity`（调度器眼中的相对计算能力，不是实时利用率）与频率解释执行环境。帧分析不要套固定阈值：60 Hz 的 16.67 ms、120 Hz 的 8.33 ms 是名义周期，单个 `doFrame` 的 Running 与它们不可直接比——帧还包含 Runnable、RenderThread、GPU 与 SurfaceFlinger 阶段，调度偏移与刷新率切换会改变 deadline。Android 12+ 应先用 FrameTimeline 的 overrun 选中错过 deadline 的帧，再回看线程状态。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q18 -->

**Q51: [learning] Runnable 区间变长就是调度器选错了吗？怎么量化“被唤醒到开始运行”的延迟？**

不是。Runnable（`R`/`R+`）只证明线程具备运行条件却未上 CPU，候选原因要按约束来源排查：

1. **竞争：**全机负载与高优先级或实时任务占用。
2. **任务属性：**调度类、nice、affinity、cpuset、uclamp 与厂商策略。
3. **可用容量：**CPU 离线、热限制或 cgroup CPU 带宽配额。
4. **唤醒放置：**调度器可能为缓存亲和暂缓迁移；普通 Linux 调度不保证严格 work-conserving。看到空闲 CPU 也不能判错——目标线程未必允许迁移到那颗核。

量化入口：由 `sched_waking` 打开的 `R` 区间时长近似“被唤醒到被调度”的延迟，`R+` 从抢占切出持续到下一次运行。工具侧 `sched.latency` 标准库的 `sched_latency_for_running_interval` 把每次 Running 关联到它之前的 Runnable 区间并给出 `waker_utid`、CPU 与优先级（材料口径，本地树无标准库源码）。`waker_process` 为空可能来自 `R+`、trace 开头、数据丢失或缺少 `sched_waking`。`irq_context = 1` 表示唤醒发生在中断环境，被中断线程不是业务唤醒源。`R+` 占比高不等于优先级设置错误——要看切出后同 CPU 上运行了什么、其调度类与优先级，以及这段等待是否越过业务 deadline。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q19 -->

**Q52: [learning] 主线程关键区间内长时间 Sleeping 怎么追？"Woken by" 是业务源头吗？**

`S` 是可中断睡眠，常见于 Looper 的 `epoll_wait()`、futex/条件变量、同步 Binder 等回复和定时器。空闲线程长期 Sleeping 是健康的；需要分析的是业务 slice 或帧窗口内的长 `S`。按因果路径追查：

1. **等待入口：**用 slice 名或采样栈确认线程在哪个 API 进入睡眠。
2. **唤醒动作：**查 `waker_utid`/`waker_id` 和唤醒时间，再以 Binder 事务、monitor 竞争、Flow 或定时器事件解释关联。
3. **唤醒后排队：**继续统计 `R → Running`，避免只把 `S` 算成等待成本。

“Woken by”只标执行唤醒动作的线程，不一定是业务源头：

1. **锁路径：**可能是释放锁的线程。
2. **Binder 回复：**可能落在驱动或服务端执行路径。
3. **中断路径：**`irq_context` 标记中断环境时，不应把被中断线程当作设备事件源头。

Trace Processor 对已在 Running/Runnable 的线程收到重复唤醒时记为 `spurious_sched_wakeup`，不改写当前状态（材料口径）。Critical path lite 可沿唤醒关系列出相关线程，但调度或唤醒事件缺失会截断链路，结论仍要回到原始状态与 slice。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q20 -->

**Q53: [learning] 为什么要把线程状态裁进业务区间统计？ANR 判定和 D 状态是什么关系？**

整条线程生命周期的状态占比会被 Looper 的正常睡眠主导，对一次掉帧或一次启动没有解释力。可靠的统计是把状态裁进边界明确的业务区间——由应用写入的自定义 trace 标记（如 `cold_start`）或 FrameTimeline 帧窗口。

做法是求半开区间交集：对每条 `thread_state` 行计算 `MIN(state_end, window_end) - MAX(state_ts, window_start)`，只累计真正重叠的片段，`dur = -1` 的未闭合区间先裁到 `trace_end()`。工具侧 `sched.time_in_state` 标准库提供 `sched_time_in_state_for_thread_in_interval(开始时间, 时长, utid)` 区间函数可直接使用（材料口径）。

输出按 state、`io_wait`、`blocked_function` 分组，再依主要状态选择证据：

1. **Running：**查调用栈与 IRQ。
2. **R：**查唤醒与调度约束。
3. **S：**查 Binder、锁与 waker。
4. **D：**查 I/O 与内核路径。结论至少包含业务区间、`utid`/`upid`、状态时长与至少一种独立旁证。另注意：D 状态本身不触发 ANR——ANR 由输入分发、广播、Service 等框架监控条件判定，D 只有在阻止受监控工作按时完成时才进入因果链，不存在“D 状态超过 N 秒就 ANR”的通用规则。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q33 -->

**Q54: [learning] Binder 客户端调用耗时长，怎么区分是服务端执行慢还是客户端在等调度？**

用 `android.binder` 标准库的两张表拆解（工具侧材料口径）。`android_binder_txns` 配对客户端事务与服务端 reply。同步与 oneway 必须按 `is_sync` 分组——异步事务没有同步等待关系，`client_dur` 语义不同，两组数不能合成一个延迟分布。`android_sync_binder_thread_state_by_txn` 已对同步事务两端区间分别做 `SPAN_JOIN`，按 `thread_state_type` 区分：`binder_txn` 是客户端区间，`binder_reply` 是服务端区间。

按事务两端的状态解读：

1. **客户端 S：**符合等待同步回复的预期。
2. **服务端 R/R+：**较长区间直接支持“服务端线程排队等 CPU”。
3. **服务端 Running：**回到该事务的 slice 子树确认具体工作。`interface`、`method_name` 依赖 AIDL/HIDL 追踪 slice，为空时事务配对仍有效——报表应保留 `<unresolved>` 行而不是过滤掉，否则最难解释的调用会从结果里消失。所谓“Binder 线程池利用率”必须同时给出容量（最大线程数）与统计窗口：只累加 Binder 线程 CPU 时间得到的是运行时间，线程池可能睡眠等待工作，也可能在处理非 Binder slice。判断是否饱和要结合服务端并发事务数、可用线程数与事务排队。

<!-- 来源：02-tracing/01-perfetto-fundamentals.md · Q36 -->

**Q55: [learning] 分析一帧“慢”，怎么把 doFrame、FrameTimeline、锁与调度证据拼起来？哪些时间不能相加？**

先定位对象再用区间求交。用 `android.frames.timeline` 的 `android_frames` 取得帧窗口、`doFrame` slice 与 UI/RenderThread 的 `utid`，`android_frames_overrun` 给出 deadline 超额（正值为超期，负值为余量）。`actual_frame_timeline_count`、`draw_frame_count` 等完整性列异常说明时间线关联失败，自动化统计要保留这些列。

然后把 `doFrame` 与该帧 UI 线程（`ui_thread_utid`）的 `thread_state` 求交集，得到 Running、Runnable、睡眠与 I/O 等待的分解。再把 `doFrame` 与 `android_monitor_contention` 求交集得到锁等待覆盖——条件是 `blocked_utid` 等于 UI 线程且两区间相交（只统计“完全包含”会漏掉两端跨界的锁等待）。只对 Running 片段关联 `sched` 与 CPU 频率，解释线程获得 CPU 后的运行环境。

三个“不能相加”的边界要分别检查：

1. **锁等待与 Running：**同一 `utid` 上两者按定义互斥，`SPAN_JOIN` 得到空集是正确结果。
2. **Binder、锁与 GC：**三类活动可能彼此重叠，覆盖时间相加不是“总阻塞时间”。
3. **锁等待与睡眠：**锁时间可能已包含在睡眠状态里，再相加会重复计时。`android_monitor_contention` 的 `waiter_count` 从 0 起编号（0 表示自己是第一个等待者），不能当作等待线程总数。结论写成“FrameTimeline 将某帧标为 App Deadline Missed，UI 线程 6.2 ms 处于 R 状态，同窗口未发现 monitor 锁竞争”这样的具体命题——数字来自具体 trace，时间相关性不写成因果关系。



<!-- 来源：02-tracing/02-perfetto-advanced.md · Q4 -->

**Q56: [learning] 火焰图里一个调度函数 cumulative 很宽而 self 很窄，说明什么？火焰图宽度能当执行时长读吗？**

不能当执行时长。火焰图横向宽度是当前所选指标的聚合值：周期性 `linux.perf` 样本常见是样本数，pprof 可能是 CPU 纳秒或分配字节，Collapsed Stack 是行尾计数。单次持续时间需要 `slice.dur`、方法跟踪或其他区间证据。两个值的含义：

1. **self**：该函数位于采样调用栈叶节点的数量或指标值，表示采样经常中断在它内部。
2. **cumulative**：该函数出现在调用路径任意层级时的累计值。

调度函数 cumulative 宽而 self 窄，说明大量热点经过它进入不同子路径。只有叶函数 self 宽才表示执行集中在该函数本身。排名受采样频率、采样时刻是否与周期性工作对齐、线程运行份额和展开质量影响，是统计证据而非精确计时。

v53 起 TrackEvent 的 slice 或 instant event 可以附带调用栈：点选单个事件时详情面板展示该事件的栈，区域选择把区间内事件栈聚合成火焰图。默认每个栈计 1，事件带 `callstack_weight` 时按权重聚合，且只统计实际带值的事件。TrackEvent 栈表达“事件记录时附带的调用关系”，`perf_sample` 表达“采样中断时 CPU 上的调用关系”，可以交叉验证但不是同一种采样来源。选区分析要同时限定时间（异常帧、启动阶段、Binder 往返）、线程（主线程、RenderThread、Binder 线程）、场景（冷热启动、首帧与滚动不混聚）与指标（样本数、CPU 时间、分配字节要写明）。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q6 -->

**Q57: [learning] Agent 辅助分析 trace 时，为什么 scratchpad 必须把“已验证事实”和“假设”分开存放？**

因为可复查性要求每条结论都能由保存的命令重新执行，而口头判断（“主线程卡在 Binder”“GPU 阻塞”）无法重放。scratchpad 是调查过程中的工作记录，文件名为 trace 文件名追加 `_analysis.md`。trace 目录只读或由外部系统管理时放入获准的工作目录，并记录 trace 散列值与权限受控存储中的文件标识。三张表各有硬约束：

1. **输入表**：trace 散列值、平台构建、目标包、问题、复现条件、采集配置、Trace Processor 与 skill、SQL 包版本。任何字段未知都显式写“未提供”，不能自行补全。
2. **已验证事实表**：证据编号、时间窗、`upid/utid`、slice/计数器/帧 token、完整 SQL、结果文件或摘要。每条记录都能由保存的命令重新执行，不写“可能是”“看起来像”。
3. **排除项表**：假设、执行过的查询、结果与排除范围。空结果只能排除“当前 trace 当前表结构下可见的证据”，不能写成机制绝对不存在。

假设放在调查计划而不放进事实表：查询证实后转成已验证事实，证伪后转成排除项，证据不足保留为待验证项并注明缺少的数据源。共享报告时保存散列值与受控文件标识即可，不把本机绝对路径、原始日志或未脱敏 SQL 结果复制到公开问题单——trace 可能含进程名、URL、日志与业务标记。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q8 -->

**Q58: [learning] 长 slice 的 dur 等于 CPU 执行时间吗？怎样用 thread_state 把可疑长 slice 的调查方向定下来？**

不等。`dur` 是 wall time（经过时间），可能覆盖 CPU 执行，也可能覆盖调度等待、Binder、锁、futex 或 I/O。判断顺序五步：

1. 定位目标 slice 的 `ts`、`dur`、线程与进程。
2. 求同一时间窗内该线程与 `thread_state` 的区间相交。
3. 按状态语义解释 `Running`、`R/R+`、`S`、`D` 并计算各状态占比。
4. 按占比最大的状态选下一组证据：CPU 查子 slice、采样栈、cpufreq 与拓扑。Runnable 查同 CPU 竞争、IRQ、实时线程与 idle。Sleeping 查事件、锁、Binder 回复与 futex。D 状态查 `io_wait`、`blocked_function`、内核线程与块设备事件。
5. 找到阻塞方后回到全局视角，检查同一用户可感知窗口中的竞争事件。

边界：函数名出现在 slice 上只说明线程处于该标记范围，不能单独证明 CPU 正在执行该函数。报告要分别给出经过时间与状态分布，状态时长之和还要与 slice 的有效经过时间核对，缺少 `sched` 数据时状态分布可能覆盖不全、应记录证据缺口。`io_wait` 与 `blocked_function` 依赖内核 `sched/sched_blocked_reason` 事件（材料按 Android 17 内核口径核对，内核不在本地树），`blocked_function` 还受 userdebug 构建与符号可用性限制。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q9 -->

**Q59: [learning] Agent 的开放性 trace 调查按哪六类方向组织？什么时候应该停止归因？**

六类方向按触发条件选入，避免无边界的全量扫描（每类都是“先查什么、再查什么、常见误判是什么”）：

1. **CPU**：主线程或 RenderThread 长耗时、Running/Runnable 占比高——先查 `thread_state`、`sched`、cpufreq，再查调度延迟、同 CPU 竞争与 IRQ。勿把 Runnable 当成 App 正在计算。
2. **Graphics**：掉帧、首帧慢——先查 FrameTimeline 与帧 token，匹配 App 与 SF 后再查 BufferQueue、fence、HWC。勿只看 App 帧不查 SF 与显示提交。
3. **I/O**：D 状态、缺页密集——先查 `blocked_function` 与缺页事件，再查页缓存、块设备与内核线程。勿仅凭 D 状态断定存储慢。
4. **IPC**：客户端等待、Binder 密集——先查 `android.binder` 与 flow，再追服务端线程状态。勿停在“客户端等 Binder”。
5. **Memory**：LMK、swap 上升、kswapd 活跃——先查内存计数器、PSI、dmabuf，再查 RSS 与持有路径。勿只看 Java heap。
6. **Power**：屏灭耗电、无法 suspend——先查电源轨、suspend 与唤醒源，再查 UID 流量与厂商轨道。勿把缺失电源轨解释成零功耗。

某轨道未被采集时，结论降级为“该证据不可见”，不能写成“该异常不存在”。全局复核按以下顺序完成：

1. 按时长排序长 slice，保留 `depth` 与 `parent_id`；嵌套父子覆盖同一段时间，不能相加成总耗时。
2. 统计窗口内最长 D 状态。
3. 确认异常帧与慢 Binder 是否与用户可感知窗口重合。
4. 核对 cpufreq、内存、电源轨等计数器在同一窗口的变化与单位。

满足以下条件时停止归因：

1. 问题已改写为可验证命题，并由带时间关系的证据回答。
2. 候选阻塞已追到服务端、锁持有者、I/O、调度或 GPU/显示提交；若追踪中断，已明确记录 trace 缺数据的位置。
3. 竞争假设已查询；无法验证的假设留在待验证项。
4. 全局复核未发现更能解释同一窗口的可见证据。

缺关键数据源、trace 截断或时间戳来源无法对齐时，输出补采方案，不继续生成看似完整的根因。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q13 -->

**Q60: [learning] 冷启动时关键线程长时间低频 Running，能直接得出“governor 调频响应慢”吗？**

不能，低频 Running 只支持“调频响应晚于该段工作”这一假设，归因前要逐项排除替代解释：

1. 当前 CPU 属于低容量 policy，线程随后发生迁核。
2. policy 的 `scaling_max_freq` 已被 thermal、节电模式或系统策略限制。
3. `power/cpu_frequency` 记录的是请求值，硬件有效频率另有偏差。
4. 代码受缓存或内存停顿限制，提高频率对 wall time 的收益有限。
5. UI 线程 Running 之外还有较长的 Runnable、Binder 或 I/O 等待。

结论顺序是先减工作量再谈调频：删减首帧前任务、合并可延后的工作、降低同步 I/O 与跨进程往返，这些改动直接缩短关键路径且没有副作用。系统侧调优（PerformanceHint/ADPF、uclamp、Power HL 与能效曲线验证）属于对照组实验，不能从一条低频轨直接推出“锁最高频”。因果结论需要对照实验：固定 workload、控制温度与系统状态、只改变一个调频相关变量，并确认帧时序随之稳定变化。“目标线程在已知频率覆盖的 Running 时间中有多少比例落在某频点”是可复核观测，“governor 导致掉帧”是需要实验支持的因果判断。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q14 -->

**Q61: [learning] 连续渲染负载与后台批处理，频率分析的关注点有什么不同？**

渲染负载按帧切窗、盯相位关系：滑动、动画和游戏的帧循环有重复节奏，应分别观察 UI 线程、RenderThread、GPU 提交线程与 SurfaceFlinger。频率升降总是落后于负载相位可能带来周期性波动，但线程迁往不同 capacity CPU、Runnable 排队或 GPU fence 等待也会产生相似现象。频率高不保证帧按期完成，频率低也不必然异常——帧内工作量少时低频正是能效目标。判断依据是相同设备、相同温度、相同 workload 下的帧时序与频率变化对照。

后台批处理对单帧时序不敏感，评估的是完成时间与能耗的权衡：低频可能延长 CPU active 时间，高频可能缩短完成时间并提高瞬时功耗，哪个能耗更低取决于电压频率曲线、内存行为、policy 能效、idle state、唤醒次数和 thermal 状态。评估要同时比较完成时间、active/idle 分布、唤醒次数、温度与硬件功耗数据。

两条共同边界：频率轨不含电压，不等价于整机功耗。“高频等同高吞吐”是常见误判——缓存未命中、内存带宽、分支预测和共享资源会让高频 Running 仍然很慢，频率分析适合定位相关性，根因判断还要调用栈、硬件计数器与对照实验。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q16 -->

**Q62: [learning] 怎样从 FrameTimeline 选出 Buffer Stuffing 候选帧？为什么 on_time_finish=1 也可能出现 Buffer Stuffing？**

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

`on_time_finish = 1` 仍可出现 Buffer Stuffing：App 的生产工作按时结束，但前一帧尚未 present 时它继续提交新帧，积压使后续帧带着额外延迟 present、输入延迟增加。Stuffing 描述排队状态，不是绘制超时。四组身份字段各有用途：

1. `surface_frame_token`：关联 App 的 `doFrame` 与 RenderThread slice。
2. `display_frame_token`：关联 SF 帧与 flow。
3. `layer_name`：区分主窗口、视频、Camera 与 SurfaceView。
4. `upid`：trace 内唯一进程 ID。

同名 layer 可能来自不同实例或生命周期；能取得 layer id、track id、BufferQueue 名或 frame number 时一并记录。`jank_type` 原样保存以便核对分析器版本，UI 颜色随版本与主题变化。SurfaceView 边界：官方文档声明其尚未完整支持，缺 App Actual Timeline slice 不等于该 Surface 没有更新（已与官方文档核对）。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q17 -->

**Q63: [learning] QueuedBuffer、BufferTX 与 BufferQueueCore::mQueue.size() 三种“积压”信号有什么区别？**

三者分属三层，不能当同一个队列长度读：

1. `QueuedBuffer - <name>BLAST#<id>`：App 进程内 BLAST 的计数器（按 AAOS13 源码核对，`BLASTBufferQueue.cpp`），写入值为 `mNumFrameAvailable + mNumAcquired - mPendingRelease.size()`，是 BLAST 内部可用帧、已取得 buffer 与暂存 release 回调的组合变化，不等同于任何 BufferQueue 的 queued slot 数。
2. `BufferTX - <layerName>`：SurfaceFlinger 侧计数器（Android 13 定义在 `BufferStateLayer.h`），buffer transaction 到达服务端时增加、latch 或 drop 时减少，描述尚未被消费或丢弃的服务端提交。
3. `BufferQueueCore::mQueue.size()`：某条 BufferQueue 真正的 queued `BufferItem` 数，但常规 Perfetto trace 不直接给出这个成员。

结合源码公式判读 `QueuedBuffer`：计数器上升说明可用帧或 acquired buffer 增加、释放进度没有同步抵消。长期高位说明 BLAST 处理、SF 消费或 release 回调可能落后（等待一组更新同时提交的同步 transaction 也会暂时阻止消费）。上升后 `dequeueBuffer` 变长说明积压已向 Producer 传导。计数器已回落而渲染仍停顿，要查 release fence、GPU/driver 等待、线程调度或应用锁。多个 Surface 同时更新时，把计数器名、`layer_name`、layer id、BufferQueue 名与 frame token 配对，主窗口的 Stuffing 不能自动归因给同进程的视频 layer。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q18 -->

**Q64: [learning] 判断 BufferQueue 背压为什么必须成组取证？哪四类等待容易被混为一谈？**

背压是“下游消费不及使 Producer 拿不到 slot”的因果结论，单一信号只证明积压存在、不证明因果方向。需要同一 layer 上的成组证据在同一时间线吻合——FrameTimeline 的 Buffer Stuffing 分类、BLAST 计数器高位、Producer 等待 slice，以及可解释的 acquire、latch、present、release 时序。四类容易混淆的等待：

1. **App 执行超时**：`doFrame`、`performTraversals` 或 bind 超预算，dequeue 窗口没有明显等待。
2. **GPU 或 fence 等待**：GPU 写入、SF/HWC 读取或 release fence 未完成，证据落在 fence、GPU timeline 与 driver wait。
3. **SF/HWC 合成慢**：SF Actual Timeline 变长，jank_type 指向 SF CPU/GPU deadline 或 DisplayHAL，Producer 等待可能是下游变慢的反馈。
4. **应用同步或 Binder 等待**：渲染线程卡在应用锁、Binder 或资源加载，时间窗与 buffer release 对不上。

两类问题可以同时出现：下游合成变慢把压力传回 Producer，Producer 等待又扩大 App 帧时长。CPU 采样主要覆盖线程正在运行的时段，通常无法给睡眠区间提供完整用户态调用栈，适合观察进入等待前的执行路径。等待点本身要用明确的 slice、`thread_state` 或内核阻塞原因证明。缺少队列身份时只能写“同进程等待与该帧重叠”，不能写成目标 Surface 已确认阻塞。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q19 -->

**Q65: [learning] 多 Surface 页面分析 BufferQueue 阻塞时，为什么必须先理清队列拓扑？thread_state 怎样裁切才可信？**

因为不同出图路径的 Producer、Consumer 与队列互不相同，跨队列拼接事件会把“同时发生”写成“因果”。按路径区分：

1. **标准 HWUI 窗口：**RenderThread 把宿主 App Window buffer 交给窗口 BLAST 队列。
2. **SurfaceView：**有独立 Surface、独立 BufferQueue 与 SF child layer，宿主交互与视频内容分开归因。
3. **TextureView：**外部 Producer 先写 SurfaceTexture，再由宿主 HWUI 采样；至少区分输入队列与宿主窗口队列。
4. **Camera、视频与游戏：**Producer 可能跨进程，排队策略也不同。
5. **多窗口或 Dialog：**每个 ViewRoot 有自己的 BLAST；同进程、同 RenderThread 不代表共用 BufferQueue。

SQL 侧的核对方法：把 Producer 等待 slice（`dequeueBuffer`、`waitForBufferRelease`、`waiting for free buffer` 等名称模式。Android 13 实际只有 `dequeueBuffer` 一层）限制在同一 `upid`，并要求与 Stuffing 窗口精确重叠——`producer_wait.ts < stuffing.ts + stuffing.dur` 且 `stuffing.ts < producer_wait.ts + producer_wait.dur`。只比较起点会漏掉从窗口前开始、持续到窗口内的等待。不约束 `upid` 会误关联其他进程的同名 slice。`thread_state` 用区间相交裁切，不用固定线程名或人为时间窗：`Running` 表示正在 CPU 上执行，`R` 与 `R+` 表示可运行（后者被抢占），`S` 表示可中断睡眠（条件变量与 `epoll_wait` 常见），`D` 表示不可中断睡眠（多指向 I/O 或驱动）。`blocked_function` 来自内核 `sched_blocked_reason` 事件，对 `D` 状态更有帮助。字段为 `NULL` 可能是线程处于可中断睡眠、事件未采集或内核符号不可见，不能反向证明没有等待。

<!-- 来源：02-tracing/02-perfetto-advanced.md · Q29 -->

**Q66: [learning] API 33 的 Choreographer.postVsyncCallback 与 FrameData 怎么用？Android 13 上要注意什么？**

API 33 即 Android 13（按 AAOS13 源码核对），提供 `postVsyncCallback(VsyncCallback)` 一次性回调：下一帧执行后即移除，连续观测要再次注册并控制日志与分配成本。回调在 `Choreographer` 绑定的 Looper 线程执行。公开方法：`FrameData.getFrameTimeNanos()`、`getFrameTimelines()`（按时间排序的候选数组）与 `getPreferredFrameTimeline()`（平台选中项）。`FrameTimeline.getVsyncId()`（与 HWUI、SF trace 关联的 VSYNC id）、`getExpectedPresentationTimeNanos()` 与 `getDeadlineNanos()`，后两者使用 `System.nanoTime()` 时基（本地核对 javadoc 与实现）。

使用边界：

1. **回调外访问无效**：Android 17 源码在回调外访问 `FrameData` 会抛 `IllegalStateException`（材料口径）。Android 13 没有这个异常机制，但 `FrameData` 对象会被复用，`updateFrameData` 会把过期候选的 `vsyncId` 重置为 INVALID（本地核对）——无论哪版，回调外持有都是错的，需要异步记录时只复制 `long` 等标量值。
2. **候选数组长度以事件为准**：Android 17 内部为候选数组预留 7 个槽位但按事件携带的长度重建（材料口径）。Android 13 同样按 `vsyncEventData.frameTimelines.length` 逐项构造，公共 API 不承诺固定数量，业务代码应读实际长度并使用平台标出的 preferred 项。
3. **不能替代 trace**：这组 API 提供本次回调可选的调度计划，应用进程拿不到最终 present、SF 分类、其他 layer 或 Display HAL 结果。
