# 内核与 GKI

> 学习资料（文章模式沉淀）。边界：本文回答 Android 公共内核（ACK）、GKI 与 KMI、内核版本兼容、厂商模块、vendor hooks 和 Android 内核运行时特征；通用 Linux 进程与启动、initramfs/QEMU 控制台及归档操作归 `02-os/`；Binder 驱动和共享内存分别见 `03-binder-driver.md`、`04-shared-memory.md`，内核构建归 `10-build-system/`。逐题标注证据核对状态。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Android 公共内核是什么？量产设备都会使用吗？厂商会修改什么、为什么？**

公共内核指 Android Common Kernel（ACK）——Google 基于上游 Linux 内核（通常选 LTS 分支）维护、包含 Android 所需驱动与特性（Binder 驱动、PSI 等）的公共内核分支；GKI（Generic Kernel Image，通用内核镜像）项目进一步把它变成"Google 统一构建的核心内核镜像 + 厂商可加载模块"的形态。量产设备不是原样照搬：核心镜像来自 ACK/GKI，厂商在之上叠加自己的部分。

1. **谁在用**：Android 12 起新发布的设备按 GKI 2.0 形态出货（核心内核 5.10 起）；存量升级设备可能仍运行厂商旧内核，所以"量产设备都会使用"只对新发布设备成立；
2. **厂商改什么**：SoC/板级硬件驱动以厂商模块形式加载（装在 vendor_boot/vendor_dlkm 等分区）、设备树与产品配置、电源/温控/调度策略调优（经 ACK 预留的 vendor hooks 挂回调）；核心内核镜像本身不打厂商补丁；
3. **为什么**：内核碎片化曾让同一版本 Android 背着几十种内核 fork，安全补丁与上游更新无法统一下发；GKI 把硬件代码移出核心镜像、用稳定的内核模块接口（KMI）解耦，使核心内核可以独立更新而厂商模块不动。

排查边界：公共内核源码标签（如 ACK `android17-6.18-2026-06_r6`）只能核对平台通用机制；具体设备的驱动、配置与调度策略要看设备自己的内核提交版本与 fragment，不能拿公共内核源码当设备内核源码用。

**Q2: [learning] Android 17 的内核一定是 6.18 吗？KMI 稳定到底稳定了什么？**

不一定。Android 17 对应的官方 GKI 发布分支是 android17-6.18（已与 source.android.com 的 GKI release builds 列表核对），但 GKI 的设计目标就是内核与平台 release 解绑，设备可以运行较早 KMI 分支的认证内核。

机制上，`android17-6.18` 中的 android17 是 KMI 代、6.18 是 LTS 内核版本；KMI 稳定保证分支内 vendor 模块与内核的符号与布局兼容，LTS 小版本升级不破坏模块加载。结果：分析内核行为前先用 `uname -r` 与 KMI 字符串确认实际分支，不能写死"Android 17 = 6.18"。另外解耦不止内核一层：Android 16 起推动 GBL（通用 bootloader），bootloader 也在标准化。

**Q3: [learning] "系统是 Android 17"能推出内核一定是 6.18 吗？内核版本边界怎么确认？**

不能。Android 17 的新 ACK 是 `android17-6.18`，但兼容表同时列出多条可用于 Android 17 的较早 GKI 内核：`android16-6.12`、`android15-6.6`、`android14-6.1`、`android14-5.15`、`android13-5.15` 等（`android12-5.10`、`android13-5.10` 自 Android 17 QPR1 起不再支持）。反方向同样要谨慎：在 `android17-6.18-2026-06_r6` 中确认存在的 Arena 或 `sched_ext`，不能写成所有 Android 17 设备都有。

排查第一步是记录运行内核：

```bash
adb shell uname -r
```

这只给出版本字符串；确认它对应哪个受支持的 GKI 构建还要结合 KMI generation、安全补丁级别和厂商模块。另一个常见错误是把 Linux 6.10 当成 Android 17 的内核分支——它只是上游演进的一个版本阶段，Android 17 的 6.18 ACK 已包含 Arena、`sched_ext`、BPF iterators、BPF LSM 和 `struct_ops`。判断某项特性是否可用应直接检查目标 ACK，不凭上游版本推测回移。

**Q4: [learning] 怎么确认一台设备的内核版本、KMI 和功能开关？**

1. **版本与 KMI**：`uname -r` 与 `cat /proc/version`——GKI 设备的 KMI 直接体现在版本串里（形如 `5.15.78-android13-8-g…`，即"内核版本-android 平台发布"）；
2. **功能开关**：启用 CONFIG_IKCONFIG_PROC 的内核把完整 config 挂在 `/proc/config.gz`——`su 0 zcat /proc/config.gz | grep CONFIG_PSI=` 即可验证某功能是否编入；无该节点时到对应 GKI release 页下载 config 比对；
3. **排查顺序**：确认"某机制是否存在"先看 config、再看运行时节点（如 `/dev/binderfs`、`/proc/pressure`）、最后看厂商修改（见 Q1）。

**Q5: [learning] 怎么确认 GKI 内核里的 vendor hooks（厂商钩子）存在？**

vendor hooks 以 android_vh_/android_rvh 前缀的 tracepoint 形式存在，用 ftrace 的可用事件列表验证：`su 0 cat /sys/kernel/tracing/available_events | grep android_vh`，再向 `events/vendor_hooks/<名>/enable` 写 1 即可观测。

1. **版本纪律**：钩子集合随 KMI 版本变化（不同内核分支的 include/trace/hooks/ 内容不同，如 binder 相关钩子只在部分分支存在）——查钩子必须按设备 KMI 对应的内核分支，不能用主线树想当然；
2. **用途**：OEM 的调度/电源策略经这些钩子挂回调；应用与框架工程师可用它们在 ftrace/perfetto 里观测内核侧事件（厂商调优的可见部分，呼应 Q1 的"厂商改什么"）。

**Q6: [learning] PSI 的 /proc/pressure 怎么读？dmesg 过滤有哪些实用姿势？**

每个 `/proc/pressure/{cpu,memory,io}` 文件两行：`some` 与 `full`，各带 avg10/avg60/avg300（窗口内停顿时间占比）与 total（累计微秒）。`some` = 至少部分任务处于停顿的时间占比；`full` = 所有非空闲任务同时停顿的占比（CPU 的 full 在系统级无意义）。

1. **判读**：memory 的 full avg60 持续大于 0 = 全系统级内存停顿明显（内存压力实锤）；some 高而 full 为 0 是局部任务受阻；
2. **dmesg 过滤**：`su 0 dmesg -w | grep -iE 'binder|oom|lowmemorykiller|psi'`；user 版默认限制读 dmesg（dmesg_restrict），要用 userdebug/root；`logcat -b kernel` 依赖 logd 配置、并非所有设备可用；
3. **衔接**：lmkd 消费 PSI 的机制见 [../06-memory-storage/01-memory-management.md](../06-memory-storage/01-memory-management.md)；调度压力与温控见 [../12-performance/20-scheduler-power-framework.md](../12-performance/20-scheduler-power-framework.md)。

**Q7: [learning] ramdisk、initramfs 和 rootfs 是什么关系？内核启动为什么需要它？**

ramdisk 是启动时提供给内核的一份最小文件归档；内核将它展开为初始根文件系统（rootfs），再从中启动 `/init`。它解决了系统分区尚未挂载、但挂载所需程序和配置又必须先运行的依赖问题。

1. **ramdisk**：Android 对启动归档的常用称呼，通常是压缩的 `cpio` 文件，保存 `/init`、`fstab` 和少量早期启动配置；它不是独立的 RAM 磁盘设备，也不是完整系统。
2. **initramfs**：Linux 对启动归档及其解包机制的称呼；内核把归档中的目录树展开到初始根文件系统。
3. **rootfs**：内核启动时使用的初始 `/`，通常由内存型 `ramfs` 或 `tmpfs` 支撑。内核从这里找到 `/init`；它不是 ramdisk 归档本身。
4. **为什么需要**：init 必须先运行才能读取 `fstab`、加载早期存储驱动并挂载 `system`、`vendor` 等分区；但这些分区尚未挂载时，相关程序和配置无法从中读取。启动归档先提供这些必需文件，打破循环依赖。
5. **启动时怎么用**：Bootloader 将内核和归档载入内存；内核展开归档、启动其中的 `/init`（PID 1）；init 再挂载系统分区并继续启动流程。断电后初始根文件系统消失，下次开机再由启动归档重建。

**Q8: [learning] Kernel 的 GKI 边界到底划在哪，"内核可升级"具体指什么？**

GKI 把内核代码分成"稳定 ABI 的通用内核"与"随产品编译的供应商内核"两层：设备可替换的只有供应商模块与内核镜像，而稳定的通用内核片段通过 KMI（Kernel Module Interface）冻结，保证不同厂商的模块能在同一个通用内核上互操作。Android 12 引入的 GKI 2.0 进一步支持把模块按 `vendor_boot` 启动、通用内核与供应商内核解耦部署。

对本仓库的实际影响是**可写范围与崩溃归因**：GKI 之外的内核代码（vendor 模块）改动不会随系统升级自动重新编译，接口不匹配会在运行期而非编译期暴露；因此"升级系统后某厂商模块行为异常"是一类需要单独归因的故障。判断规则：看到与升级相关的内核行为变化，先确认变更落在通用内核还是 vendor 模块——落在通用内核则所有设备同步生效，落在 vendor 模块则只有该产品线受影响。

**Q9: [learning] 内核里某个机制"存在"就能说明设备启用了吗？评估 Android 17 GKI 6.18 的调度与内存改动要满足哪三个条件？**

不能。Kconfig 的 default y 只说明依赖满足且无其他配置覆盖时取 y；设备结论要同时满足三个条件：构建条件（最终 .config 含所需选项、编译器支持相应插桩）、硬件与固件条件（CPU 实现架构特性或固件提供所需调用）、运行时条件（启动参数未关闭，用户态机制还需进程显式启用）。源码 tag 中存在某个功能，无法单独证明设备已经启用它。

放到 Android 17 GKI（材料按 android17-6.18-2026-06_r6 核对，内核不在本地 AAOS13 树）逐项看：EEVDF 从 Linux 6.6 起进入公平调度，不能把 6.12 写成分界点，且它的 lag/virtual deadline 模型不识别"UI 线程"语义，不保证 UI 任务总能抢先；CONFIG_SCHED_CLASS_EXT=y 只说明编入了 sched_ext，BPF 调度器加载并运行后才会接管任务，/sys/kernel/sched_ext/state 为 enabled 且 root/ops 有名字才是运行证据；F2FS 的 checkpoint_merge 要看设备挂载参数与文件系统状态；io_uring 的内核实现不等于 Android 应用契约，NDK 稳定 API 未列出 io_uring，普通应用还受 seccomp 与 SELinux 约束；dm-verity multi-buffer hashing 补丁并未合入 r6，其约 35% 的 cold-cache 吞吐数据不能记为 Android 17 收益。

工程做法是建核查表：机制、启用条件、原始测试口径三列对齐；设备测试记录至少包含 platform build、kernel release、GKI tag、挂载参数、CPU 拓扑与样本统计。性能对照保持内核配置与缓解状态一致，只改一个变量，"没有检出差异"和"证明零开销"要分开表述。

**Q10: [learning] ARM64 的 KASLR、KPTI 与 Spectre 缓解各自保护什么？为什么 KPTI 是否生效不能靠 CPU 型号推断？**

三者保护对象不同。KASLR 在启动阶段随机化内核与模块基地址，依赖 CONFIG_RANDOMIZE_BASE、bootloader 经设备树 /chosen/kaslr-seed 提供的熵，且未传 nokaslr；它不会给每次间接调用附加固定成本。KPTI 隔离 EL0 与内核页表，热点在系统调用、缺页与中断等用户态/内核态往返路径，纯用户态计算的结果代表不了 Binder 或存储负载。Spectre v1 用 array_index_nospec() 加架构屏障做局部边界修复；Spectre v2/BHB 由内核按 CPU capability、MIDR 匹配、架构特性与勘误信息，在固件调用、CPU 专用回调或内核指令序列（如分支序列覆盖分支历史）中选择缓解，实现不固定为"入口插一条 SB"。

KPTI 不能按 CPU 产品名或上市年份编"必定启用/跳过"表：6.18 对 kpti= 的定义是默认只在需要缓解的核心上启用，kpti=0/1 才是强制开关，同一 SoC 还可能包含多种核心。同一条逻辑贯穿整个控制流防线：PAC（返回地址/指针认证）、BTI（限制间接分支落点，与 Spectre v2 缓解不互替）、SCS（内核影子调用栈）、KCFI（间接调用类型检查）、MTE、GCS 在 arch/arm64/Kconfig 多为 default y，是否生效仍受构建、硬件固件与运行时三条件约束——例如 GCS 配置只让内核在硬件存在时提供用户态 ABI，进程还要经 prctl(PR_SET_SHADOW_STACK_STATUS) 启用，exec() 会清除该状态。

性能归因上，关闭缓解的对照只适用于隔离实验室的可丢弃工程镜像，测试设备不得承载账号密钥；量产结论以 /sys/devices/system/cpu/vulnerabilities/* 状态节点、/proc/cmdline 与最终 .config 建立证据链，量产设备常用 kptr_restrict 把 kallsyms 地址显示为全零，不能据此判断 KASLR 失效。
