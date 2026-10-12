# heapprofd Native Heap Profile

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q5 -->

**Q1: [learning] 在量产 user build 上，谁有权对一个应用做 native heap 采样？普通 App 想在线上采自己的堆 profile 应走哪条路？**

权限判断同时看会话发起者与目标应用属性（材料按 Android 17 的 `CanProfileAndroid()` 核对。heapprofd 自 Android 10 引入，本地 AAOS13 树无 `external/perfetto`，bionic 侧已核对）：

1. **Shell 或未指定发起者：**目标应用须声明 `profileable_from_shell`（`<profileable android:shell="true" />`）或 `debuggable`。
2. **Trusted-system 发起者：**由 `traced` 赋予受信系统会话身份，普通 App 写入自己的配置也不会获得；目标只需 `profileable` 或 `debuggable`。
3. **平台 UID：**user build 上小于 `AID_APP_START` 的 UID 只允许 trusted-system 发起；isolated process 的判定更严格。
4. **Installer 过滤：**`target_installed_by` 只会缩小目标集合，不会授予权限。AAOS13 已核对 bionic 的 `malloc_heapprofd.cpp`：安装是多步流程，且已有不兼容 malloc hook（如 GWP-ASan/malloc debug 占用 dispatch）时 heapprofd 会拒绝安装。

普通 App 的线上路径是 Android 15+ 的 `ProfilingManager` 请求自己的 heap profile：系统核对 Binder 调用 UID 与包名、配置目标固定为调用方包名、受单应用与全系统限流，请求被接受只表示开始尝试、不保证生成结果文件（`ERROR_FAILED_RATE_LIMIT_PROCESS`/`_SYSTEM`/`_INVALID_REQUEST` 等错误码要分别记录）。`:remote` 等次要进程不会自动加入。AAOS13（Android 13）没有 `ProfilingManager`。QA 复现走另一条路：release APK 声明 `android:shell="true"` 后在 user build 上由 shell 侧 Perfetto/`tools/heap_profile` 采集——它依赖本地调试通道，不代表 App 能在用户手机上自行启动系统采集。


<!-- 来源：02-tracing/03-runtime-profiling.md · Q6 -->

**Q2: [learning] 用 heapprofd 判断 native 泄漏，为什么第一个 dump 不能当基线？哪些“看起来在涨”其实不是 malloc 泄漏？**

heapprofd 只记录采集窗口内经过受支持分配器（默认 heap 为 `libc.malloc`，覆盖 Bionic 的 malloc/free 与 C++ new/delete 路径）抽样到的分配与释放，不扫描完整堆快照——attach 之前已存在的分配没有历史事件，第一个 dump 无法代表完整 native heap。判断泄漏至少需要：两个以上连续 dump（`continuous_dump_config` 周期输出）、可重复的操作序列，以及场景结束后观察是否回落的稳定窗口。用 `heap_profile_allocation` 表按 callsite 汇总正样本（分配）与负样本（释放）得到净存活字节，相同调用栈跨 dump 增长、场景退出后不回落且多轮复现一致，才构成较强线索。

三类常见误判：一是分配器缓存仍保留页面——应用已 free 但分配器保留 arena/page，存活分配估算已回落而 RSS 不变。二是增长来自 mmap 或图形内存——heapprofd 默认不看 `mmap`/`munmap` 直接建立的映射，DMA-BUF 与 GPU 内存也没有分配器调用栈语义，PSS 上升而 heapprofd 平稳时应转查匿名映射、memtrack、`dmabuf_dump`。三是 Java 引用保住 native 对象——heap profile 不提供引用关系，需要 Java heap dump 补充。参数边界：`sampling_interval_bytes` 是平均每 N 字节取一个样本（1 表示逐次），增大降低开销但漏掉小分配。`block_client` 在 buffer 满时让目标进程等待，线上测量通常关闭以免阻塞高频分配线程。daemon 的 memory/CPU guardrail 每 30 秒检查一次、命中后直接关闭对应 data source，且 `max_heapprofd_memory_kb` 限制的是 heapprofd 自身而非目标 App。


<!-- 来源：03-resource-domains/01-memory-performance.md · Q3 -->

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

<!-- 来源：03-resource-domains/01-memory-performance.md · Q4 -->

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
