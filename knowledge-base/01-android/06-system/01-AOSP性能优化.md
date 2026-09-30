# AOSP 性能优化

> 学习资料（文章模式沉淀）。主线：平台侧性能优化怎么定位、验证与交付——从证据分层与版本轴，到构建调试、内核 6.18 与 ARM64 安全开销、AutoFDO、安装编译链路、启动耗时测量、Rust 边界，以及 AppFlow 与 AOHP 两个研究原型。源文档：android-internals-wiki 第 18 章《AOSP 性能优化》§18.1–§18.9；可本地核对的机制按 AAOS13 源码（Android 13）核对并标注版本差异（lunch 两段式、DM manifest 校验、freezer debounce 默认值、lmkd LMK_PROCPRIO 协议、MessageQueue 旧实现等），研究原型（AppFlow、AOHP）与内核 6.18 专属内容按材料口径转写、不确定处已弱化；SDM（Android 16+）、DeliQueue 与分代 CMC（Android 17）等版本敏感结论按材料已核对官方文档的口径标注。启动全链路见 [../architecture/02-Android系统启动流程.md](../01-architecture/02-Android系统启动流程.md)；ART 编译机制层见 [../architecture/12-类加载ART编译与JNI链接.md](../01-architecture/12-类加载ART编译与JNI链接.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 一轮 AOSP 平台性能优化开始前，怎样判断问题属于平台而不是单个应用？结论需要哪三类证据共同约束？**

只有证据指向公共路径、或应用无法在公开接口与文档约定内避开问题时，才应优先修改平台：同机多个应用出现相同的合成、调度或驱动等待，比单应用的 View 遍历、图片解码更接近平台问题。判断不能只靠一次 trace，应找同版本同设备的对照应用，再比较不同版本、设备与兼容性开关（compat change），现象只有随平台变量稳定移动，才有依据继续检查 framework、ART、native 服务或内核。

结论由三类证据共同约束，且不能互相替代：

1. **公开约定**：release notes、behavior changes 与 SDK 文档决定兼容与迁移边界，但不说明目标设备实际启用了什么；
2. **固定 tag 源码**：回答实现、开关条件与默认值，源码中存在某函数或配置默认为 y，不能单独证明设备已启用；
3. **设备运行证据**：build fingerprint、ART/APEX 版本、内核版本加 trace 与实验，说明本次负载实际走到哪条路径。

方法上先用多轮样本确认 P50/P90 等分位数与离散程度，再用 Perfetto、simpleperf 或 dumpsys 解释慢样本；一次只改一个主要变量，无法隔离时降低结论强度。平台改动还要检查稳定性、资源、正确性、兼容性与安全五类副作用，并把回滚路径作为实现的一部分——可配置优化要定义默认值、分批发布的设备分组与回滚后的清理动作。

**Q2: Android 17 的 DeliQueue（无锁 MessageQueue）解决什么问题？Android 13 上的消息队列是什么实现，迁移测试要覆盖哪些风险？**

DeliQueue 针对消息入队争用：旧 MessageQueue 用同一把对象 monitor 保护按 when 排序的单链表，后台线程 post 与 Looper 的 next() 竞争同一把锁，高并发投递时可能出现优先级反转，让主线程卡在队列锁上。Android 17 对 target 37 应用启用 lock-free 新实现：producer 用 VarHandle CAS 把消息压入 Treiber stack，Looper 调用 nextMessage() 时批量 drain 到普通与异步两个有序集合再挑选交付；为兼容保留的 mMessages 字段在新实现下恒为 null，不能反映队列是否为空。按 AAOS13 源码核对，本地树 frameworks/base/core/java/android/os/ 下只有旧版 MessageQueue.java，没有 CombinedMessageQueue 目录也没有 USE_NEW_MESSAGEQUEUE（A17 中为兼容变更 421623328L）——新实现是 Android 17 行为。

迁移风险集中在私有实现依赖：反射读取 mMessages 或遍历旧链表、自制空闲检测、经 JNI 或 hidden API 操作队列内部状态，以及依赖反射写 static final 的测试工具。官方测试基线是 Espresso 3.7.0 及以上、Robolectric 4.17 及以上并迁移到 @LooperMode(PAUSED)。验证时可在 debuggable 构建上用 am compat enable/disable USE_NEW_MESSAGEQUEUE 做同包 A/B，同时观察 monitor_contention、消息排队时间与帧、启动端到端指标；若锁等待下降而排队时间上升，说明瓶颈已转移到 producer 数量或 Looper 端 drain 压力——lock-free 降低的是入队争用，不保证队列不积压。

**Q3: Android 17 ART 的分代 Concurrent Mark-Compact 与 userfaultfd 是什么关系？启用条件是什么，为什么不能承诺所有应用都降低暂停时间？**

两者处在不同维度：userfaultfd 是让用户态参与处理缺页事件的 Linux 接口，是 CMC 的实现路径之一；分代是按对象代际选择回收范围的策略。Android 10 起的并发复制（CC）回收器已有分代，Android 17 新增的是 Concurrent Mark-Compact（CMC）的分代能力——"Android 17 才有分代 GC"混淆了回收器与策略。

模型上（材料按 android-17.0.0_r1 的 mark_compact.cc 核对）：上次 GC 后的新分配为 young，存活一次进入 mid，下一次 young GC 同时标记 young 与 mid、经 card table 处理 old 到年轻区域的引用，存活的 mid 压缩后晋升 old；full GC 覆盖全堆并重置分代边界——young GC 并非完全忽略 old，跨代引用仍靠 card table 保证可达性。启用是三项 AND：兼容的 read barrier 或 userfaultfd 路径、运行时 GC 选项 generational_gc、以及 ShouldUseGenerationalGC()；device-config 属性默认为 true，所以 device_config get 返回空值不能判定功能关闭。这项改进还能经 Google Play 系统更新下发到 Android 12 及以上设备，"系统不是 Android 17"也不能证明它不存在。

收益边界：官方只承诺更频繁、更低成本的年轻代回收可减轻 GC 干扰并改善最大 RSS；对象存活率高、跨代引用多或堆压力大时收益会变化。验证以目标进程的 GC 事件为准，对比 young/full collection 次数、暂停分布、GC CPU 时间与峰值 RSS，而不是只查一个属性或数总 GC 次数。

**Q4: 为什么"Android 17 上发生"不足以描述性能问题？版本、交付路径与 target SDK 要怎样拆开记录？**

同一个 APK 在两个系统版本上可能走进不同的调度、进程管理与运行时路径，性能记录至少要包含设备系统版本、targetSdkVersion、Mainline 模块版本与内核版本四条独立变量。target SDK 决定一部分兼容开关的启用；运行在新系统上的所有应用变更则与 target 无关，例如 Android 14 的缓存进程资源管理与 Android 17 部分设备的应用内存限制（命中时 ApplicationExitInfo 的 description 含 MemoryLimiter:AnonSwap）。

大版本也不是唯一版本轴，交付路径决定覆盖与回滚：framework 走整机 OTA；Mainline 模块走 APEX 独立更新，ART 可单独升级，同大版本不保证 ART 构建相同；内核走 boot/vendor_boot，Android 17 官方兼容矩阵包含多条 GKI 分支（材料注明 android13-5.10 从 QPR1 起不再受支持），看到 Android 17 不能反推内核是 6.18；vendor HAL 走厂商分区；应用编译输入走 APK/DM/Profile。

做法：跨版本实验把 OS、target、Mainline、kernel 与产品配置作为独立变量记录，固定 APK、数据集、温度与电源条件；能用 compat change 或 feature flag 在同一设备做 A/B 时优先成对比较；报告 P50/P95 与样本数，不把官方公开的内部百分比（如 DeliQueue 的 missed frames 下降 4%）当作业务目标。

**Q5: Android 17 的 lunch target 为什么是三段式？Android 13 本地树怎么选 target，三种 build variant 有什么边界？**

Android 17 的 lunch target 是 product_name-release_config-build_variant 三段，例如 aosp_cf_x86_64_only_phone-aosp_current-userdebug，release config 选择 feature launch flag 的发布配置。按 AAOS13 源码核对，本地 build/make/envsetup.sh 的 lunch 仍是两段式 product-variant（默认示例即 aosp_arm-eng），没有 release config 段——旧文章的两段式 target 不能直接套到 Android 17，反之也不能把三段式搬回旧 tag；拿不准时运行无参数 lunch 查看当前 tag 可用的组合，并检查输出中的 TARGET_PRODUCT、TARGET_BUILD_VARIANT 与 OUT_DIR。

source build/envsetup.sh 把 lunch、m 等命令加载进当前 shell，每个新 shell 都要执行一次；m 不指定 -j 时自行选择并发度，先采用默认值、经主机测量后再调。三种 build variant 的边界：user 是生产配置，最接近量产安全基线但缺少 root 调试；userdebug 保留 adb root、remount 等能力，framework 修改通常用它定位与功能验证；eng 调试检查更多，不能作为发布性能基线。需要报告绝对性能数字时，用匹配量产的 user 构建复测，并把 userdebug 与 user 的差异写入报告，不能用"足够接近"省略。

**Q6: AOSP 构建里 Soong、Kati、Ninja 各管什么？改了 framework 代码后怎样编译、同步到设备并让它生效？**

Soong 读取 Android.bp，Kati 处理遗留 Android.mk，二者生成构建图，Ninja 执行具体命令；日常入口是 m，通常不直接调 Ninja。三层的故障表现不同：模块名、属性或可见性错误发生在 Soong 解析阶段，Android.mk 转换问题出现在 Kati 阶段，编译器与链接错误由 Ninja 报出具体 action。改完构建描述可先跑 m nothing 做结构校验——它解析并验证构建结构、不生成产物，但类型检查与链接错误仍要编译受影响模块才会出现。

同步流程按产物所在分区走：

```bash
m services
adb root && adb remount
adb sync 06-system
adb shell stop && adb shell start
```

adb sync 接受 system、system_ext、product、vendor 等分区名，不接受 framework 这类模块名；改动位于 APEX、boot image 或 early-boot 代码时完整 adb reboot 更稳妥。stop/start 只重启 Zygote 与依赖它的 Java 进程，不会重新执行 bootloader、init 与 early-boot 路径，研究启动时延必须完整重启；首次 remount 可能要先 adb disable-verity 并重启，关闭 verity 只用于隔离的开发设备。模块名来自 Android.bp 的 name，文件路径不等于模块名；单文件 push 风险高于分区同步，因为类路径里可能还有 dexpreopt 产物、架构变体或关联 APEX，同步后行为未变时应核对设备端文件哈希与 build fingerprint。

**Q7: 用 Cuttlefish 验证 framework 改动的边界在哪？为什么 Android 内核必须单独构建？**

Cuttlefish 适合验证纯 AOSP framework 行为、系统服务与 CTS；它与真机的差异集中在 HAL 及依赖具体硬件的部分，GPU 合成、热控制、SoC 调度、相机和功耗结论仍需真机。使用 CI 产物时 cvd-host_package.tar.gz 与设备 image 必须来自同一次构建，主机包与镜像混搭不能靠"能启动"证明组合受支持；运行前确认 /dev/kvm 存在且当前用户有权限。把自编译 AOSP 刷入 Pixel 前还要核对四件事：当前 tag 存在目标产品与 lunch 配置、driver binaries 与设备和平台 build 匹配、bootloader/radio 固件满足镜像要求、允许 OEM unlocking 且已备份数据——fastboot flashing unlock 与 flashall -w 都涉及数据清除。

内核方面，AOSP 平台树只带预编译 kernel binary，完整内核源码与构建规则在独立 checkout：Android 13 起的现代 Android Common Kernel 用 Bazel/Kleaf 构建（如 tools/bazel run //common:kernel_aarch64_dist），build.sh 在 Android 14 及以上不受支持。单个 GKI 镜像不包含 vendor modules、DTBO、vendor_boot 与签名处理，单独刷入任意 Pixel 不构成完整方案；平台 tag 与内核 tag 是两个锚点，不能互换或互相反推。

**Q8: 内核里某个机制"存在"就能说明设备启用了吗？评估 Android 17 GKI 6.18 的调度与内存改动要满足哪三个条件？**

不能。Kconfig 的 default y 只说明依赖满足且无其他配置覆盖时取 y；设备结论要同时满足三个条件：构建条件（最终 .config 含所需选项、编译器支持相应插桩）、硬件与固件条件（CPU 实现架构特性或固件提供所需调用）、运行时条件（启动参数未关闭，用户态机制还需进程显式启用）。源码 tag 中存在某个功能，无法单独证明设备已经启用它。

放到 Android 17 GKI（材料按 android17-6.18-2026-06_r6 核对，内核不在本地 AAOS13 树）逐项看：EEVDF 从 Linux 6.6 起进入公平调度，不能把 6.12 写成分界点，且它的 lag/virtual deadline 模型不识别"UI 线程"语义，不保证 UI 任务总能抢先；CONFIG_SCHED_CLASS_EXT=y 只说明编入了 sched_ext，BPF 调度器加载并运行后才会接管任务，/sys/kernel/sched_ext/state 为 enabled 且 root/ops 有名字才是运行证据；F2FS 的 checkpoint_merge 要看设备挂载参数与文件系统状态；io_uring 的内核实现不等于 Android 应用契约，NDK 稳定 API 未列出 io_uring，普通应用还受 seccomp 与 SELinux 约束；dm-verity multi-buffer hashing 补丁并未合入 r6，其约 35% 的 cold-cache 吞吐数据不能记为 Android 17 收益。

工程做法是建核查表：机制、启用条件、原始测试口径三列对齐；设备测试记录至少包含 platform build、kernel release、GKI tag、挂载参数、CPU 拓扑与样本统计。性能对照保持内核配置与缓解状态一致，只改一个变量，"没有检出差异"和"证明零开销"要分开表述。

**Q9: MGLRU 开启后 lmkd 杀进程次数可能下降，这条因果链完整吗？**

不完整——lmkd 不读取 MGLRU 的 generation 来挑选进程，MGLRU 只能通过改变内核页面回收的成本与效果，间接影响 lmkd 所见的压力信号。lmkd 按 PSI 停顿、内存水位、thrashing 与进程 oom_score_adj 决策；MGLRU 改变回收的页面选择与开销，可能改变 PSI stall、refault、swap 与可用内存，这些变化才传导到 kill 条件。

MGLRU（Multi-Gen LRU）按访问时间窗口为 memcg 与 NUMA 节点维护多代页面：aging 扫描页表访问位，把近期访问的页放入较新 generation，reclaim 从较老 generation 选候选。generation 表达的是 recency（最近是否访问），不能简化为每秒访问次数。材料口径的 GKI r6 gki_defconfig 已设 CONFIG_LRU_GEN=y 与 CONFIG_LRU_GEN_ENABLED=y，设备仍可通过产品配置或运行时开关形成差异，运行时可用 /sys/kernel/mm/lru_gen/enabled 位掩码读主开关。

因此"MGLRU 开启后 kill 必然下降"没有源码保证：某些负载减少 refault 与 kswapd CPU，另一些可能因 swap 或 reclaim 参数呈现不同结果。对照实验应同时记录 /proc/pressure/memory 的 some/full stall、mm_vmscan_* 事件与 kswapd/direct reclaim、major/minor fault、refault、swap in/out 与 lmkd kill reason 及被杀进程的 oom_score_adj，而不是只数 kill 次数。

**Q10: ARM64 的 KASLR、KPTI 与 Spectre 缓解各自保护什么？为什么 KPTI 是否生效不能靠 CPU 型号推断？**

三者保护对象不同。KASLR 在启动阶段随机化内核与模块基地址，依赖 CONFIG_RANDOMIZE_BASE、bootloader 经设备树 /chosen/kaslr-seed 提供的熵，且未传 nokaslr；它不会给每次间接调用附加固定成本。KPTI 隔离 EL0 与内核页表，热点在系统调用、缺页与中断等用户态/内核态往返路径，纯用户态计算的结果代表不了 Binder 或存储负载。Spectre v1 用 array_index_nospec() 加架构屏障做局部边界修复；Spectre v2/BHB 由内核按 CPU capability、MIDR 匹配、架构特性与勘误信息，在固件调用、CPU 专用回调或内核指令序列（如分支序列覆盖分支历史）中选择缓解，实现不固定为"入口插一条 SB"。

KPTI 不能按 CPU 产品名或上市年份编"必定启用/跳过"表：6.18 对 kpti= 的定义是默认只在需要缓解的核心上启用，kpti=0/1 才是强制开关，同一 SoC 还可能包含多种核心。同一条逻辑贯穿整个控制流防线：PAC（返回地址/指针认证）、BTI（限制间接分支落点，与 Spectre v2 缓解不互替）、SCS（内核影子调用栈）、KCFI（间接调用类型检查）、MTE、GCS 在 arch/arm64/Kconfig 多为 default y，是否生效仍受构建、硬件固件与运行时三条件约束——例如 GCS 配置只让内核在硬件存在时提供用户态 ABI，进程还要经 prctl(PR_SET_SHADOW_STACK_STATUS) 启用，exec() 会清除该状态。

性能归因上，关闭缓解的对照只适用于隔离实验室的可丢弃工程镜像，测试设备不得承载账号密钥；量产结论以 /sys/devices/system/cpu/vulnerabilities/* 状态节点、/proc/cmdline 与最终 .config 建立证据链，量产设备常用 kptr_restrict 把 kallsyms 地址显示为全零，不能据此判断 KASLR 失效。

**Q11: MTE 用 4 位标签管理 16 字节粒度的内存，为什么不能据此换算出固定 PSS 增量？评估 MTE 成本要看哪些指标？**

4 位标签只是架构的标签存储格式，PSS 变化取决于标签存储、分配器元数据、页提交、工作集与故障模式的叠加，方向和幅度都随负载变化，官方与材料都不给出固定百分比。MTE（Memory Tagging Extension）把内存划成 16 字节 granule，每粒 4 位 allocation tag，指针高位携带 logical tag，访问时比较两者；tag mismatch 可同步报告到出错指令、异步延迟到内核入口，或用 asymmetric 模式（读同步、写异步）。

生效是三件独立的事叠加：内核 CONFIG_ARM64_MTE 与硬件支持（HWCAP2_MTE 告知用户态）；映射带 PROT_MTE（匿名映射或 tmpfs/memfd 等 RAM-backed 文件映射）；线程经 PR_SET_TAGGED_ADDR_CTRL 设置 tagged-address ABI 与 fault mode。Android 应用通常由 android:memtagMode、runtime 与 Scudo 分配器完成进程级原生堆配置，不要求业务代码逐个调用 mmap。

评估时至少分别记录：进程 PSS/RSS、匿名页与 swap；分配与释放速率及 Scudo 路径；同步 fault 的定位收益与用户可见延迟；异步 fault 的发现延迟与崩溃归因；同一业务脚本下的 CPU time、帧时间与功耗。同步模式适合需要精确故障地址的验证阶段，生产策略要结合崩溃治理、性能与设备覆盖率，不能为了安全标签无差别开启同步模式。

**Q12: AutoFDO 与插桩 PGO、Baseline Profile 分别差在哪？它会在用户手机运行时"自动优化"吗？**

不会。AutoFDO（Automatic Feedback-Directed Optimization）把真实工作负载的执行样本转换成 LLVM 采样 Profile，再用同源代码与相近工具链重新编译，让编译器调整热点内联、基本块布局与分支权重——采集、转换、重编与验证都发生在研发构建流程，用户设备运行的是已用 Profile 编译好的产物。与插桩 PGO 相比，它不插入计数器、不改源码，适合接近生产的负载，代价是样本可能丢失偏斜、地址还原与负载代表性更难保证；与 Baseline Profile 相比，它作用于 C/C++ 原生二进制与内核（Clang/LLVM、按地址与分支样本、系统构建期生效），Baseline Profile 则指导 ART/dex2oat 提前编译应用 DEX 的热点方法，两者可以同时改善启动但不能互换。

Profile 只改变机器码、不改变程序语义，但 Profile 偏差可能造成代码体积膨胀或性能回退，编译器链接器本身也可能有缺陷，所以发布仍要比较代码段大小、基准性能与稳定性。它也不会在 Perfetto 里生成名为 AutoFDO 的 slice——收益要从 A/B 与系统 trace 的执行成本变化里读出来，而不是找一条专用轨道。

**Q13: Soong 模块怎样接入 AutoFDO？模块声明 afdo: true 能证明本地二进制已经优化了吗？**

不能。afdo: true 只是打开模块的构建接入，是否真正使用还取决于 Profile 配置、目标架构、构建变体与产物日志；验证要看详细构建日志里的 -fprofile-sample-use= 指向的 Profile。按 AAOS13 源码核对：build/soong/cc/afdo.go 已存在，编译参数模板为 -funique-internal-linkage-names -fprofile-sample-accurate -fprofile-sample-use=%s，且 frameworks/base/libs/hwui/Android.bp（libhwui）、art/runtime/Android.bp（libart）、art/libartbase/Android.bp 均已声明 afdo: true——用户空间原生 AFDO 在 Android 13 已进入这些代表性平台模块，Android 17 延续该接入。

OEM 与平台团队还要做的：确认实际构建引用了有效 Profile；针对自研内核差异与产品 CUJ 采集有代表性的 Profile；随代码变化定期刷新，避免长期复用旧 Profile。Profile 与二进制版本必须接近——源码、内联结构与地址布局变化后旧样本可用性下降，应记录代码提交、Clang 版本、build ID、生成时间与完整转换命令；vmlinux、GKI 模块、厂商模块与用户空间库要分别保留未剥离 ELF 并分别生成 Profile，同一份 kernel.afdo 不能优化所有模块，--allow-mismatched-build-id 只是工具容错开关，不能用来忽略二进制来源。

**Q14: Android 17 GKI 的 kernel.afdo 是怎么从设备采样生成的？README 的 Pixel 8 收益数字为什么不能写进产品承诺？**

流程是"采样 → 转换 → 重编 → A/B"。测试机（userdebug/eng、root、CoreSight 能力）上用 simpleperf record -e cs-etm:k 录制分支轨迹——cs-etm 是软件接口名，底层可能是 ETM、ARMv9 的 ETE 加 TRBE 缓冲；simpleperf inject 把原始数据转成分支列表（高负载下轨迹可能溢出丢失，建议多次录制）；主机端与未剥离 vmlinux 聚合生成文本 Profile，最后经 create_llvm_prof 转成 kernel.afdo。三个参数不能省：--binary 必须指向匹配的未剥离 vmlinux，否则 Profile 映射到错误源码位置；--use_fs_discriminator 保留编译器路径区分信息；--prof_sym_list=false 避免把未采样的内核函数都当冷代码降级优化——内核 Profile 不可能覆盖错误处理、中断与低频管理路径。

工作负载比采样时长更重要：Profile 只描述采集期间执行的代码，只跑开机或只启动一个应用都会偏斜；GKI README 的代表性流程包括 App Crawler、单应用 crawler 与冷启动组合。A/B 要保持源码标签、Clang/Kleaf 版本、defconfig 与 LTO、设备固件、温度条件一致，唯一变量是是否应用 Profile，并先用构建日志与反汇编确认两组确实分别未用、已用 Profile。

收益边界：README 的 Pixel 8 数据（boot 1.1%、cold launch 6.6%、Binder 系列 15%–23%）标注为 preliminary，Binder 项还是多轮最佳值，且当时 Pixel 对 6.18 的功耗与调频尚未完全调优；这些数字只说明该 Profile 在该实验中的正向变化，OEM 必须在自己的 SoC、调度配置、vendor modules 与关键用户旅程上重做只改 Profile 的受控 A/B。采样本身也有开销：ETM 数据占用硬件缓冲、带宽与后处理时间。

**Q15: Profile、DM、SDM、SDC 四类文件分别解决什么问题？各自的版本边界是什么？**

四类文件分工：Profile（Baseline/Startup/Cloud）是 ART 或构建工具选择热点的输入；.dm（Dex Metadata，ZIP 格式，与 APK 同基名如 base.apk 对应 base.dm）携带 primary.prof（供 speed-profile 的 AOT 输入）与可选 primary.vdex（验证数据）；.sdm（Secure Dex Metadata）携带面向特定 ISA 的云端 AOT 产物（至少含 primary.odex），文件名带 ISA 段（如 base.arm64.sdm），必须用与 APK 相同的 signer 做 v3 签名；.sdc 由设备端 artd 生成，记录 SDM 时间戳与设备 ART APEX 版本，解决文件代际与设备环境匹配——它不是签名文件，签名验证在安装阶段完成。

版本边界：SDM/SDC 是 Android 16 引入（ArtManagedInstallFileHelper 源码注释标记）、Android 17 延续；按 AAOS13 源码核对，本地树没有 ArtManagedInstallFileHelper，SDM 在 Android 13 不存在。DM 则早已有之但行为有版本差异：AAOS13 的 frameworks/base/core/java/android/content/pm/dex/DexMetadataHelper.java 会用 ZIP 内 manifest.json 校验包名与版本号，并提供 pm.dexopt.dm.require_manifest 属性；Android 17 的实现已移除 manifest 读取与相关属性——不能用 A17 口径判断 A13 的 DM 状态，反之亦然。

范围边界：SDM 只覆盖随 APK 打包的 primary dex，运行时动态生成或自定义 ClassLoader 加载的 secondary dex 不在支持范围；同一 APK 覆盖两种 ISA 需要两个 SDM，arm64 产物不能供 arm 进程使用。三类 Profile 也职责不同：Baseline Profile 面向 Day-0、随 APK 发布；Startup Profile 只影响构建期 DEX 布局（安装后的文件系统里没有 startup.prof 可查）；Cloud Profile 由分发侧生成，设备仍可能要跑 dex2oat。

**Q16: 安装一个带 SDM 的应用时，系统怎样决定跳过本机 dex2oat？SDM 无效或缺失会怎样？**

决策链是：Package Manager 完成安装事务并发起安装 dexopt，ART Service 选择 primary dex、ABI、compiler filter、Profile 与优先级，artd 作为特权辅助进程校验路径、检查现有产物并启动编译器。artd.getDexoptNeeded() 结合 APK、boot classpath、现有本地产物与 SDM 组合判断目标是否已满足：现有产物（含 SDM 产物）满足目标则无需本机 dexopt，缺失或过期才启动 dex2oat。SDM 只是 OatFileAssistant 可选择的产物位置之一，没有取代 dexopt 调度器。

安装会话先用文件名映射（去掉 .arm64.sdm 补回 .apk）与 APK v3 签名校验 SDM：文件名无受支持 ISA 后缀、找不到同基名 APK、任一侧签名无法验证或 signer 集合不同都判为无效。A17 的新验证分支将其标为删除无效文件、记 warning 后继续安装，旧分支可能直接拒绝安装——分析日志要先确认设备走哪条分支。SDM 缺失是常态：dexopt 开始前 PrimaryDexopter 会按每个 primary dex 与 ABI 调用 artd.maybeCreateSdc()，SDM 不存在时 artd 返回成功，不构成安装错误；本机编译执行后，PrimaryDexopter 会尽早删除对应 SDM 与 SDC 释放空间——这正说明 SDM 是可被本机编译结果替代的产物，即使未删，ART 的产物垃圾回收也会清理。

版本口径：材料把"应用 dexopt 调度入口迁到 ART Service"记为 Android 14 起；按 AAOS13 源码核对，本地树已有 art/libartservice（ArtManagerLocal 在 SystemServer 经 LocalManagerRegistry 注册）与 art/artd，同时 frameworks 的 DexOptHelper.java 仍在 PMS 侧——Android 13 处于过渡期，分析安装编译链路要按具体版本定位入口。

**Q17: 运行时怎么使用 SDM 里的云端 AOT 代码？为什么"SDM 文件存在"不能证明应用在执行其中的机器码？**

运行时不会在安装阶段把 SDM 解包到应用的 oat 目录：OatFileBase::OpenOatFileFromSdm() 把 ZIP 内部条目拼成 <sdm>!/primary.odex 直接加载——ODEX 来自 SDM，VDEX 来自同 APK 配套 DM 的 primary.vdex，缺少可用 DM/VDEX 时仅有 SDM 无法组成这条加载路径。抓启动 trace 时，Open sdm file <path> 这个 slice 比"oat 目录里有没有 .odex"更能说明 SDM 被运行时打开。

云端产物没有绕过兼容性检查：OatFileAssistant 仍核对 APK dex checksum、boot classpath 与 class loader context、compiler filter 是否满足请求、OAT/VDEX 状态、SDM 与 SDC 的时间戳关系（st_mtim 不一致说明 SDM 被替换过）、以及 SDC 记录的 ART APEX 版本上下文——samegrade placebo（版本号不变换入另一版 ART APEX）就靠它识别。任一条件不满足时，系统可选择较低编译级别的产物、解释执行、JIT 或安排本机 dexopt。

因此文件存在只证明安装器接收过它。确证要组合三类证据：pm art dump <package> 的 filter/reason/location（location 指向 SDM 内 primary.odex 时证据强于 reason 字符串，但它是调试字符串、格式不保证稳定）；trace 中有无 dex2oat 与 Open sdm file；安装日志的 validation warning 与 artd 错误。带 -dm 后缀的 reason 也不够——源码注释明确它只表示本次调用传入过 DM，空 DM 也可能出现。

**Q18: SDM 能省掉安装期的哪些工作？为什么不能照搬"安装时间减少 40%–60%"这类百分比？**

能省的是：当 SDM/DM/SDC 与当前 APK、ISA、boot classpath 和编译目标兼容且满足安装目标时，设备无需为同一目标再跑 dex2oat，从而减少编译器 wall time、dex2oat 的 CPU 时间与临时内存峰值、生成 ODEX/VDEX 的写放大，以及安装期编译带来的热量与功耗。不能省的是：三个文件的下载与会话写盘、APK 与 SDM 的 v3 签名解析与 signer 比较、包扫描与权限更新、原生库提取或映射、fsync 与 SELinux 操作、SDC 创建与兼容性检查，以及未被 AOT 覆盖方法的解释/JIT 和应用自身初始化——"携带 SDM 后安装近似零成本"不成立；安装总耗时若主要花在下载或包扫描，dex2oat 的减少对总值影响有限。

百分比不可照搬：AOSP 源码没有定义能推出这些数字的基准；收益随 dex 规模、目标 filter、CPU、存储、温控与产品安装策略变化，安装策略原本用 verify 的设备能省的本机编译本来就少。冷启动还可能出现方向不同的变化：SDM 的映射、ZIP 访问与页缺失特征可能与本地产物不同，应把安装 wall time、首次启动与后续启动分开测。

可信验证是 A/B/C 三组实验：同一 APK 分别以 APK、APK+DM、APK+DM+对应 ISA 的 SDM 冷安装，固定设备构建、ART APEX 版本、温度与存储余量，每组多轮并报告 P50/P90 与失败回退次数；用 pm art dump、ART_DEX2OAT_REPORTED 统计与 trace 把"SDM 被接收"和"SDM 被运行时采用"当成两个检查点分别确认。

**Q19: 系统启动优化怎么定义"启动完成"？为什么 first boot 与 OTA 后首启的样本要单独分组？**

启动完成有多个合法终点：kernel entry（bootloader 交权）、second-stage init、Zygote start、sys.boot_completed=1、Launcher shown、first interactive，指标名必须写明起点与终点（如 kernel_to_boot_completed_ms、boot_completed_to_first_interaction_ms）。sys.boot_completed 由 ActivityManagerService.finishBooting() 在 boot animation 完成后的收尾流程中设置，它不等于桌面已显示，也不等于触摸已有响应；property 置 1 后用户回调、广播与应用进程仍可能占用 CPU 与 I/O。init 会用 ro.boottime.<service> 记录服务首次启动的 CLOCK_BOOTTIME 时间，Zygote 对应 ro.boottime.zygote。

样本分类是因为特殊启动会插入普通启动没有的成本：factory reset 后首启有包扫描与向导，OTA 后首启有 checkpoint、APEX/分区切换与 dexopt，Boot Classpath APEX 变化后首启会触发 ART boot dexopt，userspace reboot 与加密状态变化也各有额外路径。材料的源码口径指出：DexOptHelper.performPackageDexOptUpgradeIfNeeded() 只在 first boot、device upgrade 或 Boot Classpath APEX 变化时调用 ArtManagerLocal.onBoot()，该调用会阻塞且耗时可能超过 30 秒，普通启动直接返回——版本看板不区分启动类型时，P90 很容易被少量升级样本主导。

**Q20: bootanalyze、bootio 与 bootstat 各自回答什么问题？使用时有哪些容易踩的坑？**

三者分工：bootanalyze 把 logcat/dmesg 中的稳定事件转成阶段时间；bootio 用内核 taskstats 回答启动窗口里哪个进程 I/O 最多；bootstat 把启动事件与相对时间持久化，适合版本看板与长期回归。证据层级应逐层缩小：先用分位数确认回归是否稳定，再用阶段时间定位慢段，最后用 Perfetto/ftrace 把慢段细分到调度、Binder、锁、缺页与块 I/O——单份 trace 解释一次慢启动，统计才能证明版本回归。

bootanalyze 的坑（工具位于 system/extras/boottime_tools/bootanalyze/，本地 AAOS13 树未包含 system/extras，用法按材料核对）：events 记录事件第一次出现的时间点，timings 要用带 name 与 time 命名捕获组的正则从日志提取子阶段耗时，正则要先在目标版本的原始 logcat 上回放；README 与脚本有漂移（README 写 Python 2.7 而脚本 shebang 已是 python3），脚本默认等 BootComplete 与 LauncherStart 两个事件，产品定制 config.yaml 要保留脚本使用的事件键，Launcher 日志格式变化会让采集一直等到超时；wrapper 环境变量是源码固化的 CONFIG_YMAL 拼写，且会无条件执行 touch /data/bootchart/enabled，做低扰动基线要评估 bootchart 的影响。时间上，logcat 的 wall clock 在启动早期可能被校时（默认配置按 Updating system time 修正），dmesg 用内核起算时间，两类时钟不能直接相减。bootio 依赖 CONFIG_TASKSTATS 等 4 项内核配置，start 控制文件 /data/misc/bootio/start 不会自动删除。bootstat 的 ro.boottime.event.* 字段只有在 init event timestamp flag 开启时才有值，bootloader 没提供 ro.boot.boottime 时它补不出上电到 kernel 的时间。Perfetto 要在重启前安装 trace 配置并确认 session 已开始，否则会漏掉 kernel、first-stage init 与 Zygote 前半段；system_server 的 Perfetto producer buffer（材料口径 4 MiB）只服务于该 producer，不是整份 trace 的全局 buffer。

**Q21: init 的 Action 队列是串行的，为什么启动期间还能多进程并发？把慢的驱动 probe 或 module 移出关键路径要防什么回退？**

串行的是 init 的 Action 队列：rc 按解析顺序入队，Action 依次执行、Action 内 command 也依次执行，exec/exec_start 还会让队列等待进程结束。但 start/class_start 只是依次 fork/exec，启动后的 service 彼此并发运行，exec_background 启动进程后不阻塞后续 command——正确姿势是把独立工作放进 service 并发执行，再用 property 表达"完成"依赖（如 on property:vendor.prepare_cache.ready=1 再 start 下游），并给失败路径准备超时与降级。另外注意 trigger 语义：on boot && property:x=y 只在 boot 事件发生时检查组合条件，boot 已过去、property 后来才满足的 Action 不会补跑；把 service 移到更早 class 前要核对分区挂载、SELinux domain、设备节点、APEX 激活与 HAL 依赖，class 只提供分组不表达依赖图。

kernel 段同理：选择性异步 probe 可让慢速 I2C/SPI 设备、加载 firmware 的设备并行初始化（模块还可经 <module>.async_probe=1），官方示例收益 100–500 ms；把非必要 module 从 first-stage ramdisk 挪到 second-stage 可省 500–1000 ms——两个数字取决于硬件与驱动，只能说明量级。风险是依赖表达错误：异步 probe 后 consumer 发现 supplier（时钟、电源等资源提供方）未就绪必须正确返回 -EPROBE_DEFER，显示、存储、clock、regulator、thermal 依赖表达错会把成本变成更晚的同步等待或功能故障，defer storm（大量 probe 反复 defer 重试）会吃掉启动期 CPU。移动 module 后要同时验证 normal boot、recovery、fastbootd、OTA 与 crash recovery；CPUfreq/devfreq 提前上线前要确认 supplier 就绪，并接受频率上升带来的功耗温升回归。

**Q22: Zygote preload 用开机成本换取什么？删减预加载清单或使用 lazy preload 分别要注意什么？**

preload 是把成本从每次应用启动转移到系统启动：主 Zygote 在 fork system_server 前完整预加载 Framework 类（按 AAOS13 源码核对，frameworks/base/config/preloaded-classes 约 1.6 万行）、资源、app-process HAL 与图形驱动、共享库与字体缓存等，fork 出的进程靠写时复制共享这些页。扩清单可能减少应用启动期类加载，却增加 Zygote 启动工作、常驻共享页与脏页风险；删类或扩清单都要在干净开机与多应用场景同时衡量 Zygote 预加载时长、system_server ready 时间、Zygote PSS、多个代表应用的 TTID/TTFD 与低内存设备上的重启和 swap。Boot image profile 文档把 boot classpath Profile、system_server Profile 与 preload 类清单放在同一套设备调优流程，数据应来自真实 CUJ 并随系统镜像发布。

lazy preload 不是新能力也不是增量拆分：--enable-lazy-preload 只跳过启动期 preload，收到首次 preload 请求时 ZygoteInit.lazyPreload() 仍执行同一套完整 preload——它改变的只是支付时间。材料口径与 AAOS13 一致：主 64 位 Zygote 不传该参数，32 位 secondary Zygote 传。回归时要分别记录 primary 的 ZygotePreload、secondary 的 ZygoteInitTiming_lazy 与首个 32 位进程请求前后的延迟。另外业务应用自己的类通常不在系统 Zygote 的通用预加载集合里，不要用扩预加载解决单应用的启动问题。

**Q23: Android 平台为什么引入 Rust？Keystore2 这类 Rust Binder 服务与"Rust 经 JNI 跳 C++ AIDL"的说法差在哪？**

Rust 的主要目标是减少新增 native 代码中的内存安全缺陷（Google 2021 年发布支持时引用内存不安全约占当时 Android 高严重性漏洞 70% 的历史数据，不能当现状统计），迁移策略侧重新增代码与可独立替换的组件，长期保留混合语言结构。组件形态要按源码说（材料按 android-17.0.0_r1 核对）：Keystore2 与 VirtualizationService 是 Rust Binder 服务，直接用 Rust AIDL backend 与 libbinder_rs，服务启动后 add_service 注册——Java 客户端经一笔 Binder transaction 到达 Rust stub，没有固定的 C++ AIDL 中转层，"每次调用经 JNI 再进 C++ AIDL 再到 Rust"是误判。DnsResolver 仍是 C++ 与 Rust 混合（libresolvrs_ffi 经 CXX bridge 参与 DoH/HTTP3 部分），UWB 与 Bluetooth 的 Rust 库是渐进替换组件——把整个 resolver 或蓝牙栈标成 Rust 服务超出源码证据。

性能上语言迁移没有固定方向：跨进程 Binder 的成本主要在 Parcel 编解码、内核事务、线程调度与服务端工作；同进程 C ABI 边界可能只是一次普通函数调用，但两侧编译单元通常无法内联，字符串、容器与所有权转换会引入分配复制，回调频率高会放大固定成本。Keystore 操作常见的耗时来源是 Binder 排队、数据库事务、SELinux 检查、KeyMint HAL 与 TEE——只测一个空 FFI 函数的纳秒值解释不了端到端时延。

**Q24: Android 设备端的 Rust 编译配置与分配器路径是什么？"panic 可以在 FFI 入口用 catch_unwind 恢复"错在哪？**

按 AAOS13 源码核对（build/soong/rust/config/global.go），设备端 Rust 全局启用 -C opt-level=3、-C overflow-checks=on、-C force-unwind-tables=yes 与 -C panic=abort，且 build/soong/rust/builder.go 默认追加 -C lto=thin（ThinLTO 默认开启）——这套配置与 Android 17 材料一致，比通用 Rust 项目的经验数字更适合解释 Android。panic=abort 意味着 panic 直接终止进程、不沿栈展开，catch_unwind 不能为设备构建提供进程内恢复；FFI API 应把可预期失败编码为 Result、错误码或 AIDL Status，panic 只留给内部不变量被破坏。force-unwind-tables 同时保留了栈回溯与诊断所需的 unwind table，不能根据 panic 策略推算二进制缩小比例。

分配路径：未设 #[global_allocator] 的 Rust 标准库代码经 libc malloc/free 进入设备 native allocator——常规设备是 Scudo（硬化分配器，含 chunk 元数据校验、隔离与 quarantine），低内存产品可能是 jemalloc；改用 Rust 不会消除分配器的安全成本，Scudo 也不是逐对象 guard page、不是完整 ASan，不能用"每块分配前后都有保护页"当成本模型。整数溢出检查在运行期进行，"安全检查全部在编译期完成"的说法与设备构建配置不符；LLVM 能在循环边界清晰时消除部分检查，但结果依赖代码形态，评估要用 benchmark 与反汇编确认热点是否真有检查残留。

**Q25: 评估一个 Rust 系统服务的性能时，应把哪些边界成本拆开测量？**

四类边界分开。跨进程 Binder：成本在 Parcel 编码与 fd 处理、用户/内核切换、目标线程唤醒排队、大参数复制、服务端锁与硬件调用、回包与重新调度；Perfetto 里建议记五段——client 发起、driver 排队、server 进入 runnable、server 执行、reply 返回，只在服务函数入口计时会漏掉调用方阻塞与调度延迟。同进程 FFI：extern "C" 的标量与 ABI 兼容结构传递可很轻，但跨语言内联通常不存在；参数形态决定复制——借用 slice（&[u8]/rust::Slice）以指针加长度传递可零复制，owned String/Vec、CString 或 C++ 容器则按构造方式分配复制，opaque object 还要约定析构方与线程安全，创建对象的一侧导出 destroy 是常见做法。JNI：拆成 managed/native 切换、局部/全局引用管理、字符串数组转换、线程 attach/detach 与异常查询；大 payload 可评估 direct ByteBuffer 或共享内存。

运行时成本另算：Arc<Mutex<T>> 的未竞争、跨核竞争、优先级反转与持锁 I/O 是不同问题；泛型单态化与 async state machine 扩大 .text，动态链接共享代码页而静态 rlib 让 ThinLTO 删未用代码——Keystore2 选 prefer_rlib 的源码理由是 /system 上动态 Rust 进程数不足，属组件级取舍，不能推广成所有服务都应静态链接。工具上用 simpleperf 统计 cycles/instructions/branch-misses、heapprofd 看 malloc 热点、llvm-size/readelf 与 showmap 区分磁盘体积与运行时 PSS；Soong 用 Rust v0 symbol mangling，采样报告先确认符号与 build ID 已加载，不要对着 unknown 符号比较语言。优化顺序先架构级成本（减少 Binder 往返、缩短持锁区、避免重复编码复制），profiler 显示 bridge 占比高时才调 bridge 表示或 linkage。

**Q26: AppFlow 论文把 GB 级应用冷启动当成什么问题？为什么"只加文件预读"可能反而变慢？**

AppFlow（MobiCom 2026 研究原型，arXiv 2603.17259，非 AOSP 主线）把大型应用冷启动拆成三段相互影响的成本：CPU 与调度（进程创建、类加载、初始化）、文件 I/O（APK/DEX/SO/资源不在 page cache 触发 major fault）、内存分配与回收（分配触发 kswapd、direct reclaim 与 swap）。它同时协调三件事：启动前按频次做预算内预读（multiple-choice knapsack 把每个应用的文件大小分界约束进全局 100 MB 预算）、启动中吞吐优先读大文件、按内存压力与进程上下文选择后台进程终止。

"只加预读"可能更慢的原因是预读与回收互相抵消：预读占用的 page cache 在低内存下会被回收，论文测得预读页被回收后启动 I/O 延迟增至 6.4 倍；其 ablation 也显示只开 Selective File Preloader 时 cold relaunch 数增加 30%。因此它修改了内核回收路径（按窗口进入 file-first、经 /proc 清单把预加载页标为 active 暂不逐出、每 10 秒刷新活跃度），并约束进程终止只能给系统原本允许终止的候选重新排序，导航、通话、音频等可感知进程不得因预测分数低而被杀。证据边界要守住：128KB 文件分界、100 MB 预算、最多 66.5% 冷启动下降等都来自论文设备与应用集合（Pixel 7/8 与 Raspberry Pi 4B 车载试验台、Android 15 基线），论文未公开完整 Framework/kernel patch，也未进入 Android 17 主线——材料核对的 android17-6.18 vmscan.c 没有预加载清单或启动窗口，这些只能当待验证假设。

**Q27: Android 的 lmkd 基线是怎样工作的？AppFlow 式的"预测哪个应用可杀"在现平台上缺什么？**

lmkd 是用户态低内存终止守护进程，按 AAOS13 源码核对（system/memory/lmkd/lmkd.cpp）与材料一致：默认以 PSI 检测压力，常规设备 psi_partial_stall_ms 默认 70 ms（low-RAM 200 ms，指 1000 ms 窗口内 PSI some 的停顿门槛）、psi_complete_stall_ms 700 ms（full 停顿）、thrashing_limit 默认 100%（low-RAM 30%），且每轮因 thrashing 终止进程后按 10%/50% 下调门槛。收到压力事件后还检查 zone watermark、swap 与 direct reclaim；候选选择由 AMS 经 ProcessList 下发的 oom_score_adj 驱动，数值越高越先被杀，kill_heaviest_task=false 时通常取高 adj 分组队尾，搜索进入 PERCEPTIBLE_APP_ADJ 或更重要范围时会强制选 heaviest 以减少终止次数。

现平台缺的是 AppFlow 需要的三样：跨应用文件访问记录与启动预测（平台只有 UsageStats 等授权接口）、内核 vmscan 里的启动窗口与预读页保护（vendor module 不能替换 core mm 的回收语义）、基于 relaunch 基线（ΔM）与返回概率的净释放排序——oom_score_adj 表达组件此刻的重要性，不是预测。协议能力也有版本差异：AAOS13 的 ProcessList 用 LMK_PROCPRIO 逐进程下发 oom_adj，Android 17 才有 LMK_PROCS_PRIO 批量协议（材料口径每包至多 3 条）；无论哪版都没有"保护将启动应用"的策略接口。全局属性（ro.lmk.lowmem_min_oom、kill_heaviest_task、thrashing 门槛）影响整台设备：调高最低可终止 adj 可能在严重压力下找不到候选，增加 direct reclaim 与 kernel OOM 风险，调低则扩大候选集合。

**Q28: 想借鉴 AppFlow，普通应用、特权 framework 原型、产品级 OS 三种权限下分别能做什么、不能做什么？**

普通应用：优化自身初始化、生成 Baseline Profile、记录 TTID/TTFD、采样并预读自己的文件、控制资源读取；在 TRIM_MEMORY_UI_HIDDEN 回调释放可重建的界面缓存；用 ApplicationExitInfo 回看退出原因。不能访问 lmkd 控制 socket、改其它进程的 oom_score_adj、修改 vmscan 或保护 page cache 中的指定文件；跨包查询使用历史需 PACKAGE_USAGE_STATS 并由用户在设置中授予 usage access。

特权 framework 原型：可在 platform service 中采集授权的应用切换序列、维护带版本号的文件访问记录、在 launch observer 收到事件前后调度预读，并验证预测准确率、I/O 干扰与预算是否适合目标设备；但 kernel 未改时预读页随时可能按常规策略被回收，报告必须区分"读取内容最终被使用"与"页面留到使用时刻"，也不应随意降低 adj 模拟后台存活收益。

产品级 OS：要同时改 framework 与 kernel——建立启动会话 ID、传递文件或 inode/offset 标识、在 classic LRU 与 MGLRU 双路径定义短时保护、处理文件截断/更新/卸载与 memcg 迁移，并把进程排序限制在 AMS/lmkd 已允许终止的候选内；GKI 下 vendor module 不能替换 core mm 的回收语义，改 core mm 意味着长期维护内核分叉与安全更新合并。三类实现都要有退出条件：kernel OOM、SystemUI/Launcher 被杀、导航或音频中断、P99 变差或读放大失控时自动停用并恢复原生行为。实验上固定 cold/hot/warm 口径与分层负载，按组件做 ablation 分离"读取提前""页面保留"与"进程选择"的贡献。

**Q29: AOHP 想把 Android 改造成什么样？评估它时为什么要区分论文设计、原型实现与标准平台三类证据？**

AOHP（Android Open Harness Project）是面向 AI agent 的 OS 级 harness 研究原型：agent 一次任务可能跨多个应用、CLI、文件与服务，需要结构化可验证的观察、事件驱动的生命周期与"数据可流向哪里"的控制面；而标准 Android 的授权对象是应用 UID 与组件，运行时权限与 URI grant 不会理解 LLM prompt 或跨工具派生数据。AOHP 通过修改 frameworks/base、system/core、SELinux policy 等把能力发现、策略检查与审计放进统一系统服务，论文方案分为个性化服务组合、高效 agent 接口与安全信息流三部分。

三类证据不能互替：论文设计（arXiv 2606.23449）说明架构与实验；AOHP 实现（framework fork 与 container daemon 的固定 commit）说明当前原型写出了什么；Android 17 对照（android-17.0.0_r1 与 API 37 文档）说明标准平台提供什么。例如论文的 OS 管理跨服务记忆、全链路信息流控制是设计目标，而实现中 AohpVaultService 是内存 token 表（ConcurrentHashMap 直接保存明文映射，无持久加密、过期与 secure erase），AohpTaintTrackerService 只在选定 UI/tool/file 边界记录元数据——不是 TaintDroid 式的全系统动态污点传播。仓库以 Android 16 QPR2 为构建基线且未进入 Android 17，fork 源码不能当标准平台源码读；项目自身也标注为早期研究、不适合生产环境。

**Q30: AOHP 用特权虚拟显示、结构化 UI 和事件流替代 GUI 往返；标准平台的 VirtualDisplay 与 AppFunctions 分别给到哪一步？**

AOHP 的虚拟显示用 signature|privileged 权限 MANAGE_AOHP_VIRTUAL_DISPLAY 与 framework 内部接口创建 PUBLIC+TRUSTED+OWN_FOCUS 的 display，把应用放上去后台交互，再挂 ImageReader（maxImages 为 3）持续取帧；成本仍然存在——WindowManager、SurfaceFlinger、图形 buffer、GPU 合成与输入分发都要工作，论文未报告其开销。标准平台上，公共 DisplayManager.createVirtualDisplay() 创建的 display 默认 private、non-secure，创建 VirtualDisplay 不会自动获得后台启动其它应用 Activity、读取 FLAG_SECURE 窗口、注入输入或豁免冻结等能力，这些受系统权限限制。AOHP 的 Structured UI 从 accessibility tree 导出节点类型/文本/层级/bounds，省去像素细节，但 Canvas、游戏与 WebView 的语义质量依赖应用，节点树与像素可能不同帧；事件流由 system_server 内 hook 提供 Toast/通知的 session buffer（默认上限 200 条、TTL 10 分钟），普通应用用 NotificationListenerService 无法等价复制，因为它拿不到其他应用的 Toast。

标准平台最接近的结构化入口是 AppFunctions（API 36 进入平台、Android 17 扩展，官方仍标 experimental preview）：目标应用在 XML asset 声明 function，caller 搜索需 DISCOVER_APP_FUNCTIONS、跨包执行需 EXECUTE_APP_FUNCTIONS 或 EXECUTE_APP_FUNCTIONS_SYSTEM，并满足包可见性；runtime registration 随注册 context 生命周期，注册进程被冻结时系统不会进入该实现。它不提供任意后台 GUI workspace，与 AOHP 的虚拟显示 session 不能互换；system_server 侧服务由 AppFunctionManagerConfiguration.isSupported() 的 flag gate 决定是否启动。两者组合前要先统一身份、consent、审计与撤销模型，避免两个控制面重复授权。

**Q31: AOHP 的安全信息流设计了六步流程，原型现状与论文的差距在哪？五个 security case 通过能说明什么、不能说明什么？**

论文流程六步：source 被识别为敏感，明文进 agent context 前替换为 typed placeholder，vault 保存明文与 token 映射，agent 只携带 token 提交意图，trusted executor 检查 source/purpose/destination/action/consent，sink 前放行、确认或拒绝并写审计。原型现状的差距要逐个 sink 标注：UI tree 敏感字段替换、token 输入校验、敏感 tap/输入的 consent、file share 检查已实现或部分实现；文件读写的策略检查是 stub（源码返回 DENY 加 file_read_policy_not_implemented），好在选择 fail closed——缺策略时默认拒绝。vault 是进程内存 token 表，重启后映射消失，无硬件密钥绑定、用户隔离与可验证擦除；sanitizer 依赖应用声明加手机号、银行卡号等启发式，字段识别不保证完整。

五个 security case（敏感显示替换、普通动作放行、敏感动作确认、未支持访问拒绝、敏感事件脱敏）在该原型与该测试应用上通过，能证明这五个用例按预期运行；不能证明任意第三方应用的字段识别完整、隐式流/native code/侧信道被跟踪、prompt injection 无法诱导已授权动作、vault 在重启/多用户/设备失窃场景安全，或 framework 服务无提权与拒绝服务问题。实验数字同理：checkpoint 加权完成率从 54.44% 提升到 75.56%、共同成功任务的 token 降低 51.55%，是该任务集上该 agent 的结果，论文没有消融实验，不能归因给 SUI 单一机制，更不能推广到所有应用、设备与模型。
