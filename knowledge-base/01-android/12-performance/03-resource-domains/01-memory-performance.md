# Android 内存性能

**Q1: [learning] Debug.MemoryInfo 的 App Summary 口径是什么？为什么 getProcessMemoryInfo() 不能高频调用？**

App Summary 是 dumpsys meminfo 的应用摘要口径。常用值回答不同问题：

1. `getMemoryStats()`：返回 `summary.java-heap`、`summary.native-heap`、`summary.graphics` 等域摘要键（AAOS13 `Debug.java` 核对）。
2. `getTotalPss()`：按比例分摊共享页，并包含已换出页的 SwapPss。
3. `getTotalUss()`：各域 Private Clean 与 Private Dirty 的总和。
4. `getMemoryClass()`：返回 MB 为单位的 ART 托管堆近似预算；它与 PSS 不是同一口径，不能相除得到使用率。

getProcessMemoryInfo() 只面向调试或进程管理 UI：官方文档明确 Android Q 起普通应用只返回调用 UID 进程的信息（其余为 0），且采样被显著限频——调用快于限频会返回上一次的数据。AAOS13 AMS 实现为按 MEMORY_INFO_THROTTLE_TIME 节流加缓存。因此它适合放在实验步骤边界或低频诊断任务中，不能放进每帧回调、紧循环或高频定时器。更短的采样周期未必产生新数据。

**Q2: [learning] heap dump 的 Shallow Size、Retained Size、GC Root path 各回答什么？怎样做一次可复现的泄漏检查？**

三个概念分工明确：

1. **Shallow Size：**对象自身在托管堆占用的字节，受对象头、对齐和压缩引用影响，不能按字段数硬算。
2. **Retained Size：**该对象不可达后预计可一并回收的受支配对象总量。
3. **GC Root path：**对象通向 GC Root（活跃线程、JNI global reference、类对象等）的引用链。

泄漏归因主要看 Retained Size 和 GC Root path；单看 Shallow Size 会低估像素位于 native 堆的图片对象。

可复现检查按以下顺序执行：

1. 固定初始页面与进程状态，并执行同一生命周期动作多轮。
2. 等待异步任务与动画结束，再采集 heap dump。
3. 查找应已销毁的 Activity、Fragment View、listener 或缓存条目。
4. 沿 GC Root path 找生命周期更长的持有者。
5. 修复后重复同一脚本，比较实例数与保留图。

单个实例存活不一定是泄漏，系统、输入法或动画可能短期持有。证据要同时满足“越过预期生命周期”与“稳定引用链”。缓存、积压与泄漏要用释放动作后的回落区分；关闭页面或裁剪缓存后仍不降，才支持持有问题。

**Q3: [learning] heapprofd 能回答什么、不能回答什么？**

能回答 native 分配的来源。Android 10+ 起 heapprofd 以抽样方式跟踪 malloc/free、new/delete，按采集窗口提供两组视图：

1. **Unreleased malloc size/count：**窗口内已分配且尚未释放的字节与次数，适合定位持续存活分配。
2. **Total：**窗口内全部分配的字节与次数，包含已经释放的分配，适合观察 churn。

user build 只能分析 manifest 声明为 profileable 或 debuggable 的应用（Perfetto 官方文档口径。本地 AAOS13 树未含 Perfetto 源码，此条按官方文档转写）。

它不能回答的问题也要分开看：

1. 无法回溯采集开始前的历史分配。
2. 默认看不到绕过默认分配器的直接 mmap、graphics buffer 与 GPU private allocation。
3. 采样间隔、缓冲溢出与符号化质量都会影响结果。
4. 以进程名启动时，已运行与之后启动的匹配进程都可能进入采样；需要启动期证据时，应先启动 profiler 再启动应用。

**Q4: [learning] “Native Heap RSS 减 heapprofd unreleased 等于碎片”为什么不成立？**

因为三个数字的范围逐步扩大，不是同一口径的差值关系：

1. heapprofd 记录采样到的 malloc/new 请求与 free。
2. malloc_info 与 allocator 统计覆盖分配器管理的 arena、cache 与仍持有的页面。
3. Native Heap RSS/PSS 是已驻留页，还受 page size、共享、swap 与采样时刻影响。

差值可能来自多种口径差异，不能直接命名为“碎片”：

1. 未采样到的分配或采集开始前的历史分配。
2. allocator cache、对齐与页内空洞。
3. 直接 mmap、统计分类差异或 ZRAM 换出。
4. 两个工具采样时刻不同。

判断碎片时，先确认 live allocation（未释放分配）确实已下降，再看 allocator 统计与 smaps 匿名驻留页是否同步回落。RSS 滞后回落本身不能单独命名为泄漏或碎片。

**Q5: [learning] Java OOM 的崩溃点栈为什么回答不了“内存被谁占用”？线上 Hprof 方案的关键环节是什么？**

OOM 栈只记录本次分配失败的位置（Bitmap、字符串构造、数组扩容），而此前的堆可能早被长期存活对象占满；失败点和占用者通常不是同一批对象。线上 Hprof 方案按以下链路把分析对象转向持有关系：

1. 在 OOM 或高水位时采集 Hprof，用子进程减轻对交互线程的影响。
2. 裁剪工具在 native 层移除字符串内容与 Bitmap 像素，减少敏感内容及大块像素数据的上传体积。
3. 服务端重建引用图与支配树，按类聚合 Shallow/Retained/GC Root。
4. 混淆类名经 Retrace 还原后分派 owner。

没有可追溯的原始样本与计算口径时，不写压缩前后体积或收益比例；评估时分别记录裁剪规则、样本分布、上传体积和处理成功率。

修复服从引用语义：

1. 页面退出后仍被任务或监听器持有时，取消任务并解除注册。
2. 数量符合业务但 Retained Size 过大时，减少单对象负载或限制集合容量。

不要把批量 Bitmap.recycle() 写成通用修复。API 26 起像素在 native 堆，生命周期正常的 Bitmap 交给 GC 与 NativeAllocationRegistry 管理；提前回收还可能让仍在绘制的调用方访问已释放像素。Android 17 的 ProfilingTrigger.TRIGGER_TYPE_OOM 可在 OOM 时申请 Java heap dump，AAOS13 没有该 API。

**Q6: [learning] 低内存的性能损失为什么没有固定顺序？不同页面的回收成本差在哪？**

分配遇到压力后走哪条路径由页类型与设备配置决定：

1. **clean file page：**直接丢弃，再访问时 refault 需要重新读文件。
2. **dirty file page：**先回写，付出写入延迟与存储竞争。
3. **匿名页：**换入 ZRAM，付出压缩/解压 CPU 与 swap-in 延迟。
4. **高阶连续页：**可能触发 compaction 后重试。
5. **direct reclaim：**分配线程可能同步进入回收，直接损失执行时间。

判断时避免两种误判：

1. direct reclaim 不一定产生存储 I/O；clean 页可丢弃，匿名页在常见配置下进入 ZRAM，因此“回收等于读盘”不成立。
2. 主线程处于 D 状态不等于正在等待回收；要用 direct reclaim 事件或内核调用栈确认等待对象。

把 vmscan、block I/O、refault 与线程状态放在同一段 trace 里核对先后与因果。

**Q7: [learning] LowMemDetector 和 lmkd 都消费 PSI，它们的分工是什么？**

lmkd 与 LowMemDetector 的职责不同：

1. **lmkd：**在内存压力下选择并终止进程；选择策略与 oom_score_adj 见 [../../06-memory-storage/01-memory-management.md](../../06-memory-storage/01-memory-management.md)。
2. **LowMemDetector：**system_server 内的状态管理路径，不负责杀进程。native 层注册 PSI monitor，Java 线程经 epoll 等等级变化，并把压力等级提供给 ActivityManager，作为 memory factor 与进程统计调整的输入。

AAOS13 `LowMemDetector.cpp` 的三个 monitor 阈值如下；这些数值与材料按 Android 17 核对的值一致：

1. **LOW：**PSI_SOME，1 秒窗口内 15 ms。
2. **MEDIUM：**PSI_FULL，30 ms。
3. **HIGH：**PSI_FULL，50 ms。

两个消费者各持一个监听 fd 不表示 PSI 被重复计算。这些阈值是 framework 常量，OEM 分支可能不同，不能直接当作所有设备的性能阈值。

**Q8: [learning] lmkd kill 之后的性能损失怎么量化？**

cached process 被终止后内存立即可用，但用户再次进入该 App 时，warm/hot resume 变成 cold start：创建进程、加载代码与资源、初始化 Application 与 ContentProvider、恢复业务状态。代价取决于包体、I/O、编译状态和当时的系统压力，没有固定毫秒数可以概括。

评估时关联五项证据：

1. lmkd kill 的时间与原因。
2. 用户返回该 App 的时间间隔。
3. 温启动变冷启动的比例。
4. 冷启动的 CPU、I/O、首帧与状态恢复耗时。
5. 同一进程是否在短时间内反复启动退出；反复终止与重启说明回收没有净收益，反而延长压力窗口。

App 侧可用 ApplicationExitInfo 识别低内存退出（REASON_LOW_MEMORY）；部分设备不支持精确报告，先查 `isLowMemoryKillReportSupported()`。不能只用末条内存曲线反推死因。

**Q9: [learning] 全局内存压力会直接改变前台应用的 GC 行为吗？**

公开机制上不会：ART GC 的频率由应用自身的分配速率、live set、堆目标、收集器与进程状态决定，没有“PSI 升高后前台切换到更重 GC”的规则。可能同时出现的信号按以下分支归因：

1. **GC slice 变密，PSI、vmscan、kswapd 平稳：**更可能是应用 churn。
2. **PSI 与 direct reclaim 上升，GC 不变：**瓶颈更可能在系统回收或 I/O。
3. **两组信号重叠：**继续检查 CPU 竞争与分配线程调用栈。

两个相邻概念也要分开：kswapd、ZRAM、compaction 消耗 CPU 会挤压 GC 并发线程的调度时间。cached process 被 framework compact（压减驻留）或 freeze（暂停调度）属于进程驻留优化，不等同于 ART 的 Java heap GC。GC 与掉帧的重叠判定（暂停、等待、CPU 竞争四类时间）见 [../../06-memory-storage/01-memory-management.md](../../06-memory-storage/01-memory-management.md)。

**Q10: [learning] 低内存拖慢冷启动的证据链怎么建？**

先分线程状态再谈代码：如果低内存样本与对照样本的主线程 Running 时间接近，而低内存样本的 D 状态明显更长，应调查存储等待与回收事件，不能仅凭总启动耗时把差异归因到主线程代码。这里描述的是判读模式，不代表某台设备或产品的实测数值。

完整证据链按序建立：

1. 在 Perfetto 圈出启动区间，比较主线程 Running、Runnable、Sleeping 与 D 状态占比。
2. 展开 D 状态对应的内核调用栈，确认等待文件页、块设备还是文件系统锁。
3. 同一时间窗核对 kswapd0、回收 tracepoint、meminfo/vmstat、swap/ZRAM 与 PSI。
4. 核对 lmkd 与 ActivityManager 事件，确认进程是否反复被终止又重启。

只有同一时间窗内出现低内存水位、文件页回收活跃、启动缺页等待存储等证据，才支持该因果链；单次 trace 的同核现象不足以证明调度缺陷。原文调高 extra_free_kbytes 的历史建议不能直接迁移到新产品。修复验收以同场景 A/B 的 PSI、direct reclaim、kswapd CPU 与启动耗时为准，没有量化结果的案例不要补写修复收益。

**Q11: [learning] ZRAM、MGLRU 和回收事件观测有哪些设备与版本边界？**

ZRAM 评估应成对比较以下指标：

1. pswpin/pswpout、ZRAM 占用与压缩效率。
2. memory PSI、direct reclaim 与 kswapd 时间。
3. swap-in 后的启动与切换延迟。
4. 压缩线程 CPU 与功耗。
5. lmkd kill 与缓存进程存活。

只提高压缩比可能增加 CPU，只加容量可能挤占未压缩内存。page-cluster 控制一次换入预读的相邻页数；16 KB 页设备上同一值覆盖更大字节范围。配置节点是否可用取决于内核，评估指标（mm_stat 口径）见 [../../06-memory-storage/01-memory-management.md](../../06-memory-storage/01-memory-management.md)。

MGLRU 用 generation 记录页面热度。是否启用要读 `/sys/kernel/mm/lru_gen/enabled` 并核对设备配置；评估关注 refault、scan/steal、direct reclaim、PSI 与冷启动回访。generation 的 bitmask 随内核版本变化，不当作常量。

回收事件观测有版本边界：材料按 Android 17 核对，lmkd 优先用 BPF 库 libmemevents 订阅回收事件，失败时回退 vmstat，并已删除旧版 Perfetto 的 mm_events 常驻 probe。AAOS13 树的 system/memory/libmeminfo 中没有 libmemevents，lmkd 通过轮询 vmstat 获取回收状态。具体设备以镜像为准，不把某一版的观测设施写成平台通用结论。

**Q12: [learning] 内存抖动、泄漏、缓存增长在曲线上怎么区分？**

在同一脚本下观察曲线形态与释放动作后的回落：

1. **抖动：**分配速率高、GC 后大幅回落且基线稳定；查分配调用栈与 GC/慢帧重叠。
2. **泄漏：**多轮 GC 后存活基线持续上升；查 heap dump、Retained Size 与 GC Root。
3. **缓存或对象池：**曲线上升到容量上限后封顶；核对容量、命中率与逐出记录。
4. **大对象突发：**单次或少数分配拉高曲线；查分配大小、调用栈与业务事件。

判断时遵守两条边界：

1. 锯齿形只是线索；若每次 GC 后基线也在上升，要同时排查存活对象。
2. 分配很高但帧时间、CPU 和 GC 等待没有退化时，优化优先级可以后移。

一次快照不能证明泄漏，一次 GC 也不能证明资源已释放。验证优化要用同一脚本、同设备、同构建复测分配、GC、帧时间与正确性。

**Q13: [learning] TLAB 让分配很快，为什么高频分配仍然会拖慢帧？**

TLAB（线程本地分配缓冲区）只优化分配动作本身：线程在私有缓冲区内推进指针、避免堆全局锁。之后仍有多种成本：

1. 缓冲区用尽时需要补充空间。
2. 每次分配都会增加标记、扫描与回收工作量；活对象越多，收集器要处理的引用越多。
3. 大对象或突发分配容易离开最轻的分配路径。
4. 回收线程消耗 CPU 与内存带宽，会与 UI 线程、RenderThread 竞争。
5. 可分配空间不足时，发起分配的线程要等待 GC 完成；这个等待往往比单次 GC 暂停更长。

从帧预算看：120 Hz 一帧约 8.33 ms，GC 暂停、分配等待或 CPU 竞争只要与已接近预算的帧重叠，就可能产生 missed frame。没有“某类 GC 固定耗时几毫秒”的跨设备常量，判定靠 trace 中分配、GC slice 与 FrameTimeline 的对齐，而不是暂停时长直方图。AAOS13 的收集器是并发复制 CC（支持分代），材料按 Android 17 核对的 CMC 及分代 CMC 在 AAOS13 源码中不存在，但这不改变“高频分配仍有后续成本”的结论。

**Q14: [learning] 常见的 Java 分配热点有哪些？各自怎么证实和修？**

证实热点一律使用 allocation profile 调用栈，不按代码外观猜。常见类别与处置方式如下：

1. **逐帧回调：**onDraw、动画或触摸回调创建 Paint、Path、Rect、临时集合时，把可复用状态移到 View 成员并在构造期创建，帧回调只更新字段。
2. **循环内临时对象：**循环创建集合、正则、格式化器或中间 DTO 时，把容器移出循环，用 clear() 复用并按已知规模设置初始容量。复用前确认引用不会被异步任务或返回值保存，否则会引入并发错误。
3. **字符串与日志：**把 `result += item` 改为 buildString；在调用点判断日志级别，或使用接收 lambda 的延迟日志 API，确保禁用时不构造文本。
4. **键装箱与容器选择：**只有热点经 profile 证实后，才评估将 `HashMap<Int, T>` 换成 `SparseArray`。`SparseArray` 以排序数组省去 key 装箱，但查询与插入特征不同，应由 benchmark 决定，不能按元素数量设统一切换点。
5. **密集数值容器：**密集数值计算若经 profile 证实对象分配或装箱是热点，可评估改用 `IntArray` 或 `LongArray`；比较时同时确认索引访问、容量和实际工作负载相符。
6. **value class 装箱边界：**Kotlin 的 `@JvmInline value class` 只在直接参数与局部变量路径避免包装；泛型、接口、可空场景仍会装箱，优化前需按实际调用路径检查分配 profile。
7. **大 buffer：**使用固定数量轮转或分块处理，限制同时存活的 buffer 数量，并按实际最大负载验证容量。
8. **图像对象：**按目标尺寸解码并遵守图像库的 buffer pool 所有权。API 26 起 Bitmap 像素在 native 堆；库管理的对象应归还库的池，不要在未知调用方仍可能使用时提前 `recycle()`。

**Q15: [learning] Java/Kotlin 分配和 GC 在开发期与线上怎么观测？能复制 BinderInternal.GcWatcher 吗？**

开发期有两条工具链：

1. **Android Studio Java/Kotlin allocations：**提供 sampled 模式以降低高分配应用的观测影响。分析 churn 按窗口内累计次数与字节排序；分析泄漏按结束时仍存活的数量与引用排序，两者使用不同口径。
2. **Perfetto heapprofd：**Android 12 起可指定 `--heaps com.android.art` 记录 Java 分配调用栈。

ART allocation profile 只记对象创建位置，不提供删除时间或引用图；存活关系仍需 heap dump。Total 包含已回收对象，不能当作存活堆大小。

线上只保留经过开销评估的场景指标。不要复制 BinderInternal.GcWatcher（framework 隐藏 API，AAOS13 中仍靠 WeakReference 与 finalize 感知 GC）：finalization 的执行时间没有确定性，不能当每次 GC 的精确通知，更不能当线上 GC 计数器。System.gc() 只发出 GC 请求，不保证立即执行也不消除分配源，测试工具可用，产品代码靠修正分配速率与生命周期。

**Q16: [learning] Compose 的内存抖动怎么治理？remember、strong skipping、derivedStateOf 各解决什么？**

Compose 结构本身占内存，但更常见的 churn 来自重组期重复执行的构造代码与不稳定的参数身份。不同机制解决不同问题：

1. **remember(keys)：**跨重组保存对象。key 要准确描述失效时机；key 变化过频会让缓存反复失效，缺 key 会让旧对象与新输入不匹配。remember 会延长对象在组合中的存活，不适合无限增长的数据。
2. **Strong skipping：**Kotlin 2.0.20 起默认启用；参数未变的 restartable composable 可跳过，编译器会记忆捕获不稳定对象的 lambda。不要为跳过重组随意加 @Stable；它是行为契约，标注错误会让 UI 漏掉更新，应先用编译器报告核对稳定性推断。
3. **derivedStateOf：**适合输入变化频率高、输出变化频率低的场景，例如滚动位置持续变化而按钮只关心是否在顶部。若输入每次变化都必须更新结果，直接计算更简单。
4. **原始类型状态 API：**计数器等状态可用 mutableIntStateOf、mutableLongStateOf 等 API 减少 JVM 路径上的装箱。

Compose 经 AndroidX 发布，不与 platform API 绑定；库版本边界不能推断为 Android 17 设备的专属运行时行为。
