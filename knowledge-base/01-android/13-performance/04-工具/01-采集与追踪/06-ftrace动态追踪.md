# ftrace 原生动态追踪

> 系统级追踪工具学习资料。边界：本文回答“原生 tracefs 的使用模型、kprobe/uprobe 动态探针、trace_marker 打标与量产设备可用性”；经 Perfetto 采集与分析 ftrace 数据的链路见 [01-Perfetto采集与分析.md](01-Perfetto采集与分析.md)，本文只写不依赖 Perfetto 的原生 tracefs 视角。内核侧事实按本地 Linux 内核树（AAOS13_kernel/common）kernel/trace/ 核对。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 原生 ftrace 的 tracefs 使用模型是什么？最常用的几个节点各控制什么？**

ftrace 是内核内置的追踪框架，用户态通过 tracefs（常见挂载在 /sys/kernel/tracing）的文件节点控制它：往哪些节点写、读哪个节点取结果。不依赖任何外部工具，是量产机最少依赖的取证入口。

核心节点的职责：

1. `available_tracers`：当前内核编译支持的 tracer 类型，如 function、function_graph、irqsoff 等。它决定 `current_tracer` 能写什么，是判断该内核 ftrace 能力的第一入口。
2. `current_tracer`：选择当前 tracer。设为 function 抓函数调用，function_graph 抓调用图并带耗时。写错会被拒绝，先查 available_tracers。
3. `set_event` / `events/`：按事件启用追踪。`echo 1 > events/sched/sched_switch/enable` 只开调度切换事件，粒度远小于全局 tracer，是最常用的低开销方式。
4. `tracing_on`：追踪缓冲开关。采集脚本的标准节奏是“配置好事件后置 1，复现后置 0”，避免无关数据灌满环形缓冲。
5. `trace` 与 `trace_pipe`：读结果。`trace` 是静态快照，读走不清空；`trace_pipe` 是流式接口，读走即消费，适合边跑边收集。
6. `buffer_size_kb`：每 CPU 环形缓冲大小，默认较小，抓长现场前先扩容；过大会增加内存占用。
7. `tracing_cpumask`：限制追踪哪些 CPU。车机核多时先缩小范围，可显著降低数据量与开销。

依据：本地内核树 kernel/trace/trace.c 的 tracefs 节点注册与 include/linux/ftrace.h。

**Q2: [learning] kprobe 动态探针怎么往内核函数上插入观测点？返回探针差在哪？**

kprobe 允许在不重编内核的情况下，往几乎任意内核函数的入口、偏移或返回处插入探针：向 `kprobe_events` 写一行声明，内核创建一个新 trace event，之后按普通事件读取。实现位于本地内核树 kernel/trace/trace_kprobe.c，事件控制文件即其中的 kprobe_events_ops。

使用与语法要点：

1. **插入入口探针**：`echo 'p:myprobe do_sys_openat2 dfd=%x0 filename=%x1' > kprobe_events`。`p:` 表示入口探针，`myprobe` 是自定义事件名，随后是函数名与可选参数。寄存器参数按目标架构调用约定写（arm64 的 `%x0`、`%x1`），字符串可用 `+offset(%reg):string` 解引用读取。
2. **插入返回探针**：`r:` 前缀声明 retprobe，如 `echo 'r:myret do_sys_openat2 $retval' > kprobe_events`。它在函数返回时触发，`$retval` 取返回值，适合测量“谁失败了”而不是“谁被调用了”。
3. **启用与读取**：写入 kprobe_events 后事件处于未启用状态，需 `echo 1 > events/kprobes/myprobe/enable`；结果出现在 `trace`（或 trace_pipe），字段与声明中的参数对应。
4. **清理**：`echo '-:myprobe' > kprobe_events` 删除探针。调试会话结束应删除，残留探针会持续产生开销。
5. **能力边界**：函数可以被优化或内联，符号名以 `available_filter_functions` 与 `/proc/kallsyms` 实际内容为准。探针参数格式错误会在写入 kprobe_events 时报错，不会静默失败。中缀语法随内核版本演进，复杂取值（数组、位域）以目标版本 Documentation/trace/kprobetrace.rst 为准。

典型用途：定位“某个内核函数被谁以什么参数调用、返回了什么”，例如排查 VHAL 相关驱动接口的调用频率与错误码，不需要重编内核或重启设备。

依据：本地内核树 kernel/trace/trace_kprobe.c（kprobe_events 控制与 enable_boot_kprobe_events）、Documentation/trace/kprobetrace 语义。

**Q3: [learning] uprobe 与 trace_marker 分别把观测点放在哪一层？**

uprobe 把探针放在用户态二进制的指定偏移上，trace_marker 则由应用或脚本主动向 trace 缓冲写一行文本。两者都进入同一个 trace 缓冲，与内核事件在时间线上可对齐。

1. **uprobe**：向 `uprobe_events` 写入声明（如 `p:mylib /system/lib64/libfoo.so:0x1234 arg1=%x0`），内核在该文件的虚拟地址处放置断点，进程执行到时触发事件。实现位于本地内核树 kernel/trace/trace_uprobe.c（uprobe_events 控制文件）。适合观测无源码符号或未插桩的闭源库；偏移要对应目标设备的实际二进制，ASLR 不影响（基于文件偏移）。符号解析失败、偏移越界都会在写入时报错。
2. **trace_marker**：`echo "begin:xxx" > /sys/kernel/tracing/trace_marker` 会在 trace 中生成一行打印事件。它不需要任何探针，是最轻量的打标手段；atrace 在应用层的 beginSection/endSection 底层走的也是向 tracefs 写入的路径。适合标记自定义业务阶段，把系统事件与业务时间点对齐。
3. **选择**：观测“某个函数被执行到了什么参数、返回了什么”用 uprobe；把“我这段业务逻辑开始/结束了”写进时间线用 trace_marker。两者常配合：uprobe 抓系统库行为，trace_marker 标业务输入，在 trace 里对齐因果。

依据：本地内核树 kernel/trace/trace_uprobe.c 与 fs/tracefs 的 marker 节点。

**Q4: [learning] 量产设备上 ftrace 与 kprobe 可用性怎么确认？有哪些常见限制？**

tracefs 与动态探针都受内核配置和构建裁剪影响，上车前按“节点存在性 → 权限 → 配置开关”三步确认，不能假设所有设备一致。

确认顺序：

1. **节点存在性**：`ls /sys/kernel/tracing/`。目录不存在说明内核未编入 ftrace 或未挂载 tracefs；缺 `kprobe_events` 说明 CONFIG_KPROBE_EVENTS 未开或 kprobe 未注册事件接口。本地 GKI 配置片段中显式可见的相关项只有 CONFIG_KPROBES=y，其余 ftrace/事件配置以目标设备实际内核为准，动手前逐一核对。
2. **权限**：tracefs 多数节点要求 root 或特定 SELinux 域。量产 user build 通常限制 shell 域写入；untrusted_app 域不可直接访问。车机排查一般走 adb root（userdebug）或厂商调试入口。
3. **开销与安全**：kprobe 在被探函数入口做代码改写，对热路径函数加探针会带来可测量的开销；uprobe 用断点指令，命中成本高于 kprobe。追踪期间不要对安全敏感路径（如认证、密钥处理函数）插入带参数打印的探针，参数内容可能含凭据。

常见坑：

1. 缓冲被无关事件灌满：先 `events/enable` 全关，只开目标事件，再置 `tracing_on`。
2. 多 CPU 数据错序：各 CPU 独立缓冲，`trace` 快照按缓冲序拼接，严格时序分析应使用带时间戳合并的工具（如 trace_processor）。
3. 只看 `trace` 不置 `tracing_on 0`：持续写入会覆盖早期现场，复现完成立即停采并导出。

依据：本地内核树 kernel/trace/ 目录结构与 arch/arm64/configs/gki_defconfig（CONFIG_KPROBES=y 显式可见）；设备实际配置与 SELinux 策略需按目标镜像核对，本文不假设量产设备默认可用。
