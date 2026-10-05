# BPF 可观测与可编程边界

> 学习资料（文章模式沉淀）。边界：本文回答设备上的 BPF 功能如何判定可用、Android 平台程序如何加载并附着、BPF Arena 的内存与 map 限制，以及 sched_ext 的运行状态和接管范围。网络 eBPF 统计由 `07-network/07` 负责。资料按 Android 17 与 ACK 6.18 语境核对，设备内核和产品策略仍须现场确认。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 为什么“内核支持 BPF”不能证明设备应用能使用？判定 BPF 可用要检查哪四项条件？**

设备上的某项 BPF 功能只有在实现、配置、运行状态和权限都满足时才可用。verifier 通过仅表示程序通过当前装载检查，单独看到 `CONFIG_BPF_SYSCALL=y`、一个 `.bpf` 文件或 `/sys/fs/bpf` 目录，都不能证明目标能力已经运行并可被目标调用方使用。

1. **内核实现：**检查当前 ACK 分支是否实现目标 map、program type、helper、kfunc 和 attach point。内核版本号相同也不保证厂商分支包含完全相同补丁。
2. **内核配置和 JIT：**确认设备内核启用了目标功能所需配置。若功能依赖特定架构的 JIT 支持，还要确认当前 CPU 架构具备该实现。
3. **装载和附着：**确认 BPF 对象进入系统镜像或模块，再确认 loader 接受了对象、程序加载成功并附着到预期事件。只有文件存在并不表示程序正在采集或执行。
4. **权限与安全策略：**检查文件权限、Linux capability、SELinux 规则、bpffs 节点 owner/group/mode 和 map/program pin 位置。普通应用仍受 Android UID、capability 和 SELinux 边界约束。内核编译了 BPF LSM 不表示 Android 已用它替换 SELinux，是否启用以及参与决策的顺序还取决于 LSM 配置、启动参数和产品策略。

**Q2: [learning] Android 镜像中存在 BPF 对象，为什么不代表设备正在采集？怎样沿 bpfloader 链路逐段确认？**

Android 平台 BPF 对象由 loader 按描述信息与设备条件筛选、加载和附着，并非扫描 `/system/etc/bpf` 后无条件全部启用。排查要逐段证明“对象匹配、程序已加载、attach 已完成、事件触发、map 更新、消费方有权读取”，任何一段缺少证据都会使最终数据为空。

1. **对象和版本匹配：**Android 17 的 bpfloader 读取显式 program/map 描述，并按 build 类型、loader 版本、内核最小/最大版本、CPU 架构与 feature flag 筛选。`min_kver` 和 `max_kver` 定义内核版本适用范围，loader 版本字段限制哪个版本的 loader 可处理对象。范围之外的对象会被跳过。
2. **加载与附着：**`auto_attach=true` 表示成功加载后按描述自动附着并 pin link。`auto_attach=false` 表示 loader 只加载并 pin program，后续由其他组件完成 attach。两种情况下都要验证实际 link 或 attach 状态，不能从 program 已 pin 推断事件已接管。
3. **失败是否继续：**标记为 optional 的 program 加载失败后，loader 可记录错误并继续处理后续 section。非 optional program 失败会中止当前 `.o` 的后续处理。若 `.o` 标记为 `CRITICAL`，对应版本范围内的必需程序未成功加载会使 loader 整体失败，并可能导致 `bpf.progs_loaded` 不会置位。
4. **用户版本过滤：**对象或 map 的 `ignore_on_user` 字段为 `true` 时，user build 会跳过该对象或 map。其他忽略字段分别控制 eng 和 userdebug build，不能把测试对象在 userdebug 上可用当成量产 user build 也可用。
5. **功能专项条件：**wakelock 时长、DMA-BUF iterator、锁竞争等程序还分别受 feature flag 限制。锁竞争程序按此资料要求内核至少为 6.1。`cyclePerUid` 只适用于 x86_64，且需要专门 flag，不能当成 arm64 手机的通用能力。
6. **平台启动时序：**init 挂载 bpffs，按平台启动服务顺序运行 `load-bpf-programs`。Android 17 在 Zygote 启动前加载相应平台对象，并设置 bpffs 节点的 owner、group、mode 和 SELinux 标签。启动完成日志之外仍需检查 attach 与实际 map 更新。
7. **网络统计例外：**网络流量统计与 Tethering 的 BPF 由 `netbpfload` 等网络组件另行管理，不属于上述平台观测对象的同一加载链路。网络数据为空时应检查相应网络 loader，而不是只查平台 `bpfloader`。

**Q3: [learning] BPF Arena 与普通键值 map 有什么不同？`BPF_MAP_TYPE_ARENA` 的地址和映射限制是什么？**

BPF Arena 是 BPF 程序与用户进程共享的稀疏内存区域，不是按 key 查询和更新 value 的普通 map。它适合在两侧共享含指针的数据结构，但创建 Arena 需要当前架构的 BPF JIT 支持，并遵守映射 flag、虚拟范围大小和地址边界限制。

1. **map 类型：**`BPF_MAP_TYPE_ARENA` 声明 Arena map 类型。它为 BPF 与用户空间提供共享地址区域，不提供常规键值语义，因此不能把 `lookup`、`update` 或 `delete` 当成哈希表操作使用。
2. **mmap 标志：**`BPF_F_MMAPABLE` 必须设置，表示该区域可通过 `mmap()` 映射到用户空间。缺少该标志时，Arena map 创建会失败。
3. **最大条目：**`max_entries` 表示 Arena 虚拟范围包含的页数。`max_entries × PAGE_SIZE` 最大为 4 GiB。该上限描述可寻址虚拟范围，不代表所有页面都会立即分配物理内存。
4. **地址边界：**若通过 `map_extra` 指定用户空间 VMA 起始地址，起始地址必须页对齐，整个 VMA 不能跨越一个 32 位地址边界。未固定地址时也要由内核选择满足边界条件的区域。
5. **架构支持：**当前 BPF JIT 必须支持 Arena。即使内核源码包含 Arena 实现，架构 JIT 不支持时 map 创建仍会返回不支持错误。
6. **运行时取舍：**Arena 让 BPF 与用户态可使用共享指针组织数据，但页面按访问或 BPF 分配路径建立，需由程序管理并发、对象生命周期和空间回收。它不会自动让应用的其他分配变少，也不会自动附着到任何事件。

**Q4: [learning] 怎样确认 Android 设备上的 sched_ext 调度器当前正在运行？full 与 partial 模式分别接管哪些任务，错误时怎样回退？**

`CONFIG_SCHED_CLASS_EXT=y` 只表示内核编译了 sched_ext 框架，不能证明某个 BPF 调度器已加载或正在调度任务。确认运行状态要查看当前状态、活动 ops 名称和启用历史，再结合调度器的 full/partial 配置判断目标线程是否由它接管。

```bash
adb shell 'cat /sys/kernel/sched_ext/state 2>/dev/null'
adb shell 'cat /sys/kernel/sched_ext/root/ops 2>/dev/null'
adb shell 'cat /sys/kernel/sched_ext/enable_seq 2>/dev/null'
```

1. **state：**`adb shell` 在设备上执行命令，`cat` 读取 `/sys/kernel/sched_ext/state`。当前值为 `enabled` 才表示 sched_ext 正在启用。`2>/dev/null` 将错误输出丢弃，方便兼容节点不存在的设备，但也会隐藏权限或节点错误，排障时应去掉后查看错误。
2. **root/ops：**`/sys/kernel/sched_ext/root/ops` 显示当前 root scheduler 的 ops 名称。它可确认哪份调度器处于活动状态，不证明它对所有任务生效。
3. **enable_seq：**这是本次开机以来单调递增的启用次数。非零只证明曾有 sched_ext 成功启用，不证明当前仍处于 enabled 状态。
4. **full 模式：**未设置 `SCX_OPS_SWITCH_PARTIAL` 时，sched_ext 接管 `SCHED_NORMAL`、`SCHED_BATCH`、`SCHED_IDLE` 和 `SCHED_EXT` 任务。因此普通应用线程的 policy 字段仍可能显示 `SCHED_NORMAL`，不能只在 `/proc/<pid>/sched` 中搜索 `SCHED_EXT` 来判断是否由 sched_ext 调度。
5. **partial 模式：**设置 `SCX_OPS_SWITCH_PARTIAL` 后，只有显式设为 `SCHED_EXT` 的任务进入 sched_ext。`SCHED_NORMAL`、`SCHED_BATCH` 和 `SCHED_IDLE` 仍由优先级更高的 fair-class 调度器处理。
6. **回退路径：**调度器主动退出、BPF 内部错误、runnable task 长时间停滞或触发 SysRq-S 时，内核会停用 BPF 调度器并将受管任务交回 fair-class。若调度器退出、接口报错或 watchdog 告警，应同时检查 sched_ext sysfs 状态和内核日志。
**Q5: [learning] sched_ext 的 `select_cpu()`、`enqueue()` 和 `dispatch()` 怎样分工？任务最终从哪个 DSQ 被 CPU 执行？**

sched_ext 的调度回调把 CPU 选择、入队和分发拆成不同阶段。`select_cpu()` 提供唤醒时的 CPU 选择提示，`enqueue()` 决定任务排入哪条队列，`dispatch()` 在本地和全局队列都没有可运行任务时补充任务。

1. `select_cpu()`：在唤醒阶段给出 CPU 选择提示，可省略并使用默认实现。返回值不是绑定关系，核心调度器仍可按任务亲和性等条件修正选择。
2. `enqueue()`：决定唤醒任务进入 global DSQ、某 CPU 的 local DSQ，或由调度器暂存在自定义队列。默认实现可把任务放入 global DSQ。
3. `dispatch()`：当当前 CPU 的 local DSQ 和 global DSQ 都没有可运行任务时调用，用于从 BPF 自定义队列取任务并移入可执行队列。只使用内建 DSQ 的调度器通常不需要实现它。
4. **DSQ 执行：**每个 CPU 只从自己的 local DSQ 选择当前任务。调度器可以建立自定义 DSQ，并按 FIFO 或虚拟时间顺序管理其中的任务，再把它们分发到 local DSQ。

**Q6: [learning] sched_ext 怎样通过 CPU performance target 协同 schedutil？这个目标值能否直接证明关键任务性能提升？**

sched_ext 可为 CPU 设置相对性能目标，Android 17 的 schedutil 路径可据此选择目标频率。目标值只是调频输入，不等于硬件已达到对应频率，也不能单独证明端到端时延改善。实际效果取决于调度策略、cpufreq driver、硬件和负载。

1. **目标值含义：**BPF 调度器可按目标 CPU 调用 `scx_bpf_cpuperf_set(cpu, perf)` 设置相对性能目标。`cpu` 标识目标处理器，`perf` 在 0 到 `SCX_CPUPERF_ONE` 的范围内表示相对最大性能的比例。
2. **硬件响应：**schedutil 将目标值映射为频率请求，但实际频点还受 CPU capacity、频率表、功耗限制和硬件驱动影响。应观察目标、实际频率与任务调度时间线，不能只看 BPF 写入成功。
3. **性能结论：**验证“关键任务改善”需要定义 workload、基线、目标指标、采样窗口和重复实验，并区分调度等待、CPU 频率响应与应用工作时间。没有可复现实验的数据不能作为平台结论。
4. **接口边界：**Android 17 AOSP 没有通用的“ML Scheduler”或 `bpf_runqueue_hook` 系统接口。厂商产品若声称存在专有调度接口，应以其源码和设备运行状态为证据。
