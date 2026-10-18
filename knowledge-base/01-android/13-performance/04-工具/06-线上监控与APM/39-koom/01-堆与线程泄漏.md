# KOOM

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q16 -->

**Q1: [learning] KOOM 的 java/native/thread leak 三个模块各判定什么？公共版本的 API 与 ABI 门槛怎么限制接入？**

三个模块的判定口径不同：

1. **Java leak：**轮询主进程前台状态，命中 heap 使用率、线程数或 FD 数阈值后用 `fork()` 派生子进程生成 Hprof，再由 Shark 生成引用链 JSON。
2. **Native leak：**用 PLT hook 记录目标 `.so` 的活跃分配，与 `libmemunreachable` 的不可达内存结果求交集，产出候选。
3. **Thread leak：**只识别 joinable 线程已退出、但没有被 `pthread_detach` 或 `pthread_join` 回收的情况。运行中的匿名线程、无界线程池 worker 和长期 WAITING 的业务线程不属于它的判定对象，需要另一套观测。

门槛直接写在源码里（KOOM 为第三方 SDK，未本地核对。涉及的平台符号按 AAOS13 核对）：`DefaultInitTask` 与 `ForkJvmHeapDumper` 限 API 21–36，Android 17 / API 37 会被拒绝。native leak 限 API 24+ 且仅 arm64。thread leak 限 API 28–34 且仅 arm64。Maven Central 最新正式版 `2.2.2`（2024-04）不含上游后来合入的 Android 15 fast dump 与 16 KB 修改。因此 Android 17 项目不能把 `2.2.2` 当生产依赖，需要维护 fork 并逐符号验证。

fork 依赖的私有符号在平台侧确实存在：KOOM fast dump 解析 `libart.so` 的 `art::hprof::DumpHeap`、`art::ScopedSuspendAll`、`art::gc::ScopedGCCriticalSection` 等——AAOS13 的 `art/runtime/hprof/hprof.h`、`art/runtime/thread_list.h`、`art/runtime/gc/scoped_gc_critical_section.h` 中都能找到对应声明，native leak 依赖的 `libmemunreachable` 及其 `GetUnreachableMemoryString(bool, size_t)` 接口也在 `system/memory/libmemunreachable/` 中存在。但“源码里有”不构成应用可依赖的 ABI 承诺，平台版本升级需逐符号重新验证。


<!-- 来源：05-observability/03-apm-platforms.md · Q17 -->

**Q2: [learning] KOOM 用 fork 子进程写 Hprof 来降低主进程停顿——为什么诊断动作本身仍可能加速进程退出？自动路径产出的 Hprof 会脱敏吗？**

fork dump 的顺序是暂停 ART、调用 `fork()`、恢复父进程、由子进程写 Hprof。copy-on-write 让子进程创建时不复制整块堆，主进程停顿因此缩短。但父子进程随后修改的页面仍会各自增加物理内存，子进程还要消耗 CPU、文件 I/O 与磁盘——在可用内存已经很低的场景（正是触发 dump 的场景），诊断动作本身可能失败或加快进程退出。这是“降低停顿”与“消除资源风险”的区别，接入方要为 dump 失败、子进程被杀、磁盘不足设计降级。

触发与额度边界：`OOMMonitor` 只在主进程前台轮询（默认 15 秒），heap 使用率连续 3 次超过阈值（大堆 80%、中堆 85%、小堆 90%）或线程数超 750、FD 数超 1000 会触发 dump，heap 超 90% 或单间隔增长超 350000 KB 立即触发。每个进程生命周期最多自动 dump 一次，非 debug 构建还有“每版本 5 次、首个 15 天内”的额度。这些默认值是上游策略而非通用安全值，业务还要叠加远程开关、采样与冷却时间。

Hprof 的答案是否定的：自动路径调用 `ForkJvmHeapDumper`，产出原始 Hprof——源码虽有 `ForkStripHeapDumper`，但自动监控不使用它。文件可能包含字符串、账号数据、图片字节与业务对象，生产环境应在设备内完成分析、只上传引用链 JSON 摘要。确需保留 Hprof 要单独授权并用私有目录、短保留期与加密传输。另一个坑是 `isSpaceEnough()` 只检查约 1.2 MB 可用空间，远低于真实 Hprof 需求，磁盘配额要自己算。KOOM 为第三方 SDK，未本地核对。
