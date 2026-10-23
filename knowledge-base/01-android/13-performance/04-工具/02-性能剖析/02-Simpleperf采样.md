# Simpleperf 与 CPU 微架构分析

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q5 -->

**Q1: [learning] simpleperf 的 stat、record、report 与 Overhead 分别表示什么？**

simpleperf 的各阶段回答不同问题；Overhead 只是在选定事件上的权重占比，不能一律当墙钟时间：

1. **stat：**汇总事件计数，回答目标累计消耗多少。
2. **record：**按事件触发采样，把程序计数器、线程、映射与可选调用栈写入 `perf.data`，回答消耗发生在哪。
3. **report：**读取 `perf.data`，默认按进程/线程、动态库与符号聚合样本，回答样本归属。

解释 Overhead 前先看采样事件：`task-clock` 表示线程在 CPU 上运行的时间，`cpu-cycles` 还受频率、微架构和迁核影响，`instructions` 是已退休指令数。离散样本占比相近也不证明调用次数接近。采样参数按需求选择：`-f 1000` 表示运行态每秒约 1000 次，线程只运行 200 ms 时约有 200 个样本；`-c 100000` 表示每累积十万个所选事件采样一次；事件的 `:u` 后缀仅统计用户态。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q6 -->

**Q2: [learning] 非 root 设备采集 simpleperf 时怎样判断权限路径？**

先看目标进程是否可采样，再看所用二进制与平台版本；“设备支持事件”不能代替采样授权。常见路径：

1. **profileable 应用：**manifest 声明 `<profileable android:shell="true">` 后，shell 可通过预装工具采样；它不等于 debuggable，也不授予任意进程内存读取权限。
2. **debuggable 应用：**可走 run-as 流程，但构建行为可能偏离发布包。材料按 Android 17 核对的 `app_profiler.py` 默认拒绝此类构建，接受偏差时需显式传入 `--unrepresentative_profile_debug_app`。
3. **root 或 userdebug/eng：**可分析普通发布应用、系统 native 进程或全系统目标。

Android 16 起，材料所引 `environment.cpp` 注释说明侧载 simpleperf 不再拥有所需内核采样权限，优先经 `simpleperf_app_runner` 启动设备内置版本；Android 13 等更早版本优先走 run-as。simpleperf 源码不在本地 AAOS13 树，这条版本差异按材料转写。采集前用 `run_simpleperf_on_device.py list --show-features` 和 `list` 探测能力；`permission denied`、`not supported`、`event not found` 分别指向权限、支持情况和事件名称。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q7 -->

**Q3: [learning] simpleperf 的调用栈模式和丢样该怎样判断？**

默认 `-g` 用 DWARF：内核保存样本寄存器与用户栈，simpleperf 再用 `libunwindstack` 离线展开。它依赖 ELF 的 `.eh_frame`、`.debug_frame`、`.ARM.exidx` 或 `.gnu_debugdata`；材料按 Android 17 核对的单样本栈上限为 65,528 字节，深栈仍可能截断。`--call-graph fp` 则沿帧指针取栈，在保留帧指针的 ARM64 native 代码上开销较低，但 ART Java 栈和 32 位 ARM/Thumb 混合代码可能断栈。模式优劣要比较 `[unknown]` 比例、栈深、热点归属和业务扰动。

出现丢样时依次检查：

1. 降低 `-f`，确认损失是否随采样率下降。
2. 仅在内核丢样时逐步增大 `-m`，即每 CPU 的内核缓冲页数，值须为 2 的幂。
3. 用户缓冲不足或栈被截断时增大 `--user-buffer-size`。
4. 再缩短采集时间、减少目标线程或改用帧指针模式。

每轮只改一个变量，记录 `Samples recorded`、`Samples lost` 与 `truncated stacks`。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q8 -->

**Q4: [learning] simpleperf 的 off-CPU 采样能补齐普通 CPU 采样的什么盲区？**

普通 CPU 采样只在线程运行时发生。线程因锁、I/O 或其他原因离开 CPU 后，等待时间不会出现在这些样本中。`--trace-offcpu` 额外记录 `sched:sched_switch` 与上下文切换时间戳，估算离开 CPU 时停在哪条调用路径；报告可用 `report_html.py --trace-offcpu on-off-cpu` 分开展示两种权重。

使用边界分三项：

1. 整次记录只能选择 `cpu-clock` 或 `task-clock` 中一个事件。
2. off-CPU 权重来自调度时间戳，切换记录或样本丢失会降低精度；先用 `list --show-features` 探测内核支持。
3. 需要唤醒者、Binder 对端、频率或可运行队列的完整时间上下文时，用 Perfetto System Trace 补证，不能从 off-CPU 样本独自推断等待原因。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q9 -->

**Q5: [learning] Arm Topdown 与 SPE 各能定位什么 CPU 微架构问题？**

Topdown（自顶向下微架构分析）把处理器的执行机会按 slot 分成四类，用于判断瓶颈方向：

1. **Retiring：**公式认定已退休的 slot。
2. **Bad Speculation：**错误推测与恢复开销。
3. **Frontend Bound：**前端未及时供给后端的损失。
4. **Backend Bound：**后端资源约束导致的损失。

四类比例不是优化处方；函数热点、调用栈和数据地址仍需采样工具定位。

公式属于具体 CPU 而不属于指令集：slot 宽度（如 Neoverse V1 定义中的 8 slots/cycle）、事件和系数都由该 CPU 的 telemetry specification 定义，不能从 Armv8、Armv9 或 Android API 级别推导。把 Intel TMAM 公式换成 Arm 事件名没有物理含义。材料按 Android 17 核对：simpleperf 可以采集 `raw-stall-slot-backend` 这类原始事件（`simpleperf list raw` 查看），但不计算四类指标。

异构 SoC 上大/小核公式不同、计数不要跨簇相加，事件数超过 PMU 计数器时的分时复用会让比例不可信。SPE（Statistical Profiling Extension，Android 17 simpleperf 新增采集与解码）能按采样指令地址、数据地址和事件位（L1、TLB、分支、LLC 等）定位热点指令，但该版本解码器不输出操作类型、`PERF_SAMPLE_DATA_SRC`、延迟权重、物理地址和可用于逐样本对齐的时间戳，`--spe-cache-miss` 不是 Android 17 的有效命令。


<!-- 来源：06-app-optimization/01-cpu-performance.md · Q13 -->

**Q6: [learning] 怎么用 Simpleperf 判断一段代码是不是 memory-bound（主要受内存访问限制）？**

先确认设备支持哪些 PMU 事件，再做组计数，最后才做热点采样：`simpleperf list`/`list raw`/`stat --print-hw-counter` 列出内核封装事件、raw 事件与硬件 counter 数量。`stat` 时把 `cpu-cycles,instructions` 与 `cache-references,cache-misses` 分组（同组事件尽量同时调度，可计算 IPC 与 miss ratio，发生 multiplexing 时输出中的 enabled/running 时间和警告必须保留）。确认 cache 事件与慢样本相关后，再用 `record -e cache-misses:u` 配合 `report --sort dso,symbol` 定位符号。

低 IPC 不能单独证明 memory-bound：branch miss、指令 cache/TLB 压力、长依赖链、锁等待附近的短运行片段、前端/后端 stall、不同核心宽度与频率、PMU multiplexing 都会拉低 IPC。判断 memory-bound 至少要观察 cache/TLB refill、backend stall、内存带宽或访问延迟采样中的一部分，并通过改变数据布局或工作集做可控实验，确认延迟或吞吐随之改善。边界：不存在“cache miss 超过 10% 就该优化”的通用阈值。不同 cluster 可能使用不同 PMU，线程迁移会影响结果解释。普通 cache-miss 采样只能提示热点指令附近，不能单独证实 false sharing。
