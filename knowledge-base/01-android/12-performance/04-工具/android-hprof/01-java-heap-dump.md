# ART HPROF 与 Java Heap Dump

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q13 -->

**Q1: [learning] ART 生成 HPROF 时为什么整个采集期间都处于停顿？"app/zygote/image"堆空间归类对裁剪文件有什么约束？**

`art/runtime/hprof/hprof.cc` 的 `DumpHeap()` 先进入与 GC 互斥的 `ScopedGCCriticalSection`，再创建 `ScopedSuspendAll(..., true /* long suspend */)` 停止其他托管线程，使对象和引用在遍历期间保持一致。`Hprof::Dump()` 在这个暂停范围内执行两遍——第一遍用计数输出器计算整体大小和最大 record 大小，第二遍写正式数据，停顿覆盖计数遍历、正式遍历和文件输出（AAOS13 源码核对）。

所以代价远不止“扫一遍对象”，耗时随对象数、字段与数组数据量、存储速度和 Bitmap 内容变化，没有一组“每 100 MB 几秒”的数字能跨设备成立。文件头 magic 是 `JAVA PROFILE 1.0.3`，对象 ID 宽度为 4 字节（AAOS13 源码核对，注释明确 hprof-conv 按 4 硬编码）。`HEAP_DUMP_INFO` record 把对象归入 `HPROF_HEAP_APP`（含 app image）、`HPROF_HEAP_ZYGOTE`、`HPROF_HEAP_IMAGE` 三个空间。

zygote 和 boot image 对象通常不是应用泄漏的分配主体，但会出现在 GC Root 路径中、其类与字符串 record 也可能被其他对象引用，直接删除这些二进制区段会得到引用断裂、无法解析的文件——KOOM 一类工具用配套的裁剪器和补全器维护引用关系，手工裁剪前必须理解这一点。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q14 -->

**Q2: [learning] Perfetto 的 android.java_hprof 与完整 ART HPROF 差在哪？它的 fork 管线为什么停顿更短但仍不是零成本？**

两者都来自 ART 托管堆，但产物和停顿边界不同：完整 HPROF（`am dumpheap`、`Debug.dumpHprofData()`）保存类、对象、字段、数组、字符串与 GC Root，能回答“某个字段保存了什么”。`android.java_hprof`（Android 11+ 数据源）只记录类型、对象大小、GC Root 与引用关系，直接写成 protobuf `HeapGraph` 包，不保存实例基本类型字段值、字符串内容、基本类型数组字节和 Bitmap 像素，因此文件更小、隐私面也更小，且能与同一 trace 里的 GC、调度事件做时间对齐。

管线（AAOS13 源码核对关键点）：Perfetto 的 `JavaHprofProducer` 找到目标并授权后发送 `__SIGRTMIN + 6` 信号（`art/perfetto_hprof/perfetto_hprof.cc` 中 `kJavaHeapprofdSignal`），ART 插件的信号处理器只向 pipe 写通知，专用监听线程再进入 GC 临界区、执行 fork——父进程随后尽快恢复托管线程，派生出的子进程遍历 fork 时刻的堆副本并输出 `HeapGraph`。fork 采用写时复制：采集仍有 fork、页表、页面复制、子进程内存与序列化开销，不能描述成零成本。

数据里的 `native_size` 只覆盖 `NativeAllocationRegistry` 为部分 Java 对象登记的原生内存，不能替代 heapprofd 的 native 分配采样。能否采集由 Perfetto 侧的 `CanProfile()` 按 build type、UID 与 manifest 属性判定，与 `am dumpheap` 的权限模型不是同一套。

<!-- 来源：03-resource-domains/01-memory-performance.md · Q2 -->

**Q3: [learning] heap dump 的 Shallow Size、Retained Size、GC Root path 各回答什么？怎样做一次可复现的泄漏检查？**

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
