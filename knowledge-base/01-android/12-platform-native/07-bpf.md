# BPF 可观测与可编程边界

> 学习资料（文章模式沉淀）。边界：本文回答"BPF 能力的四道门槛、装载与附着条件、Arena/sched_ext 的启用语义"；网络 eBPF 统计归 08-network/07。源文档：android-internals-wiki §1.28（Android 17/ACK 6.18 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: "内核支持 BPF"为什么推不出"这台设备上应用能用"？要依次通过哪四道检查？**

因为 BPF 可用能力是四项条件的交集：内核实现 ∩ 内核配置 ∩ 已加载并附着的程序 ∩ 调用方权限。verifier 通过只是第一道安全检查，仅看到 `CONFIG_BPF_SYSCALL=y`、某个 `.bpf` 文件或 `/sys/fs/bpf` 目录，都不足以得出"可用"的结论。

1. **内核实现**：当前 ACK 源码是否包含对应的 map、program type、helper、kfunc 或 attach 点；
2. **内核配置与 JIT**：设备内核是否编译该能力，当前 CPU 架构的 JIT 是否支持；
3. **装载与附着**：BPF 对象是否随系统镜像安装，loader 是否按内核版本与 feature flag 加载，程序是否已附着到目标事件；
4. **调用方权限**：文件权限、Linux capability 与 SELinux 权限。

补充边界：BPF LSM 被编译进内核也不代表 Android 用 BPF LSM 替换了 SELinux——是否启用、以何种顺序参与安全决策还取决于启动参数、LSM 列表和产品策略；普通应用始终受 Android UID、capability、SELinux 和 bpffs 节点 owner/group/mode 约束。

**Q2: 平台 BPF 程序如何装载？"镜像里有 .bpf 文件"等于"设备正在采集"吗？**

不等于。Android 17 用 Rust 实现的 bpfloader，读取显式描述表而不是"扫描 `/system/etc/bpf` 全部加载"，并按 build 类型、feature flag、CPU 架构和 min/max kernel version 筛选；init 挂载 bpffs（`nodev noexec nosuid`）并在 Zygote 启动前触发 `load-bpf-programs`，加载后设置节点 owner/group/mode。

"镜像里有文件"与"正在采集"之间的差距来自装载条件：

1. `auto_attach=true` 的程序加载即附着并 pin link；`auto_attach=false` 只加载并 pin program，附着由后续组件完成；
2. 非 critical 对象加载失败只记错误并继续启动；`skip_on_user` 的测试对象在量产 user build 中不加载；
3. 部分对象受 feature flag 或内核版本限制：wakelock 时长、DMA-BUF iterator、锁竞争程序各需对应 flag，锁竞争还要求内核至少 6.1；`cyclePerUid` 仅 x86_64 且需要专门 flag，不能写成 arm64 手机的通用能力。

另外，网络流量统计与 Tethering 的 BPF 由 `netbpfload` 等组件另行管理，不在上述平台观测对象之列。排查时按"内核支持 → 对象安装 → loader 加载 → attach → 事件触发 → map 更新 → 消费方有权限读取"逐段验证，前一段没有证据时，空数据不能归因给业务或消费方。

**Q3: ACK 6.18 编译了 BPF Arena 和 sched_ext，它们会自动改变设备行为吗？**

都不会。`CONFIG_SCHED_CLASS_EXT=y` 与 Arena 的实现进入内核只说明"框架存在"，两者都需要用户空间主动参与才会生效。

- **Arena**（`BPF_MAP_TYPE_ARENA`）：BPF 程序与用户进程之间的稀疏共享内存，必须设置 `BPF_F_MMAPABLE`、当前架构 JIT 支持，`max_entries × PAGE_SIZE` 最大 4 GiB，用户空间 VMA 不能跨越 32 位地址边界；它不是键值 map——lookup、update、delete 等 map 操作返回不支持，也不会自动附着到任何事件或自动减少内存占用。
- **sched_ext**：初始状态 `disabled`，只有用户空间成功加载并附着一份 `sched_ext_ops` 才进入 `ENABLED` 并切换符合条件的任务（未设 `SCX_OPS_SWITCH_PARTIAL` 时是全部符合条件）；BPF 调度器报错、退出或 watchdog 超时后，内核停用它并把任务交回内置公平调度类（Android 17 为 EEVDF）。

确认 sched_ext 状态可读 `/sys/kernel/sched_ext/state` 等只读节点；`enable_seq` 只增不减，非 0 只证明曾经启用过，不证明当前仍在运行。Android 17 AOSP 没有"ML Scheduler"或 `bpf_runqueue_hook` 这类系统级通用接口——"关键任务响应时间改善 35%"这类说法若缺少可复现实验，不能作为平台结论。

**Q4: sched_ext 是什么？确认一台设备"厂商 BPF 调度器在运行"要看哪三层？full 与 partial 模式差在哪？**

sched_ext 是 Linux 的可扩展调度类，允许 eBPF 程序经 struct_ops 提供普通任务的调度策略；sched_ext 从 Linux 6.12 进入主线，材料按 Android 17 arm64 GKI 核对 CONFIG_SCHED_CLASS_EXT=y（本地 AAOS13 树无内核源码，内核结论按材料口径转写）。三层确认缺一不可：编译能力（内核配置含 CONFIG_SCHED_CLASS_EXT）；运行状态（/sys/kernel/sched_ext/state 为 enabled、root/ops 显示当前调度器名，enable_seq 大于 0 只证明本次开机曾成功启用过）；任务范围（full 还是 partial，目标线程是否在接管范围）。配置只说明编译了框架，BPF 调度器未加载时任务仍走 fair 类。

full 模式（未设 SCX_OPS_SWITCH_PARTIAL）接管 SCHED_NORMAL/BATCH/IDLE 与 SCHED_EXT 任务，此时普通应用线程的调度策略字段仍可能显示 SCHED_NORMAL，所以在 /proc/<pid>/sched 里搜 ext 判断归属不可靠；partial 模式只接管显式设为 SCHED_EXT 的任务。BPF 回调多有默认行为：select_cpu() 只是优化提示且可省略、enqueue() 默认进 global DSQ、dispatch() 仅在 local 与 global DSQ 为空时调用；CPU 只执行自己 local DSQ 中的任务，自定义 DSQ 可按 FIFO 或虚拟时间排序。安全边界是自动回退：BPF 错误、runnable task stall 或 SysRq-S 会终止调度器并把任务交回 fair 类。Android 17 的 schedutil 已接入 SCX 性能目标值，full 模式下 BPF 策略要同步提供合理的性能目标，否则频率响应与调度意图脱节；第三方公开的 hmbird_sched proc 节点只能当线索，不能替代厂商源码与运行时证据。

```bash
adb shell 'cat /sys/kernel/sched_ext/state 2>/dev/null'
adb shell 'cat /sys/kernel/sched_ext/root/ops 2>/dev/null'
adb shell 'cat /sys/kernel/sched_ext/enable_seq 2>/dev/null'
```
