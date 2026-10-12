# Android Studio Profiler

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q2 -->

**Q1: [learning] Android Studio 的三种 CPU 采集任务分别适合什么问题？**

按需要的证据选择，三种任务的时间读数不能互换：

1. **System Trace：**记录带时间戳的系统事件、线程状态、调度、渲染与部分系统进程信息。Android 10 及以上采用 Perfetto 格式。先用它区分执行、等待、调度和渲染并圈定窗口；没有显式 tracepoint 的普通方法不会自动展开。
2. **Callstack Sample：**周期性采样运行中的调用栈，给出 CPU 时间分布。已确认时间主要花在 Running 且缺方法标记时，用它找高占比路径；它不记录线程睡眠期间的墙钟等待。
3. **Record Java/Kotlin Methods：**ART 在方法进入和退出时插桩记录调用层级。只有调用顺序仍不清楚时再缩小场景录制；插桩会改变时序，官方建议控制在约 5 秒内，并用无插桩 trace 或基准测试复核绝对耗时。

Android 7.1 及以下的采集量受 File size limit 约束，Android 8.0（API 26）及以上忽略该上限；这不意味着可以无成本长时间录制。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q3 -->

**Q2: [learning] Callstack Sample 的函数占比为什么不能当成单次耗时？**

某函数命中 30% 的样本，表示它在所选采样窗口和事件口径下约占相应 CPU 权重；不说明单次调用持续时间或调用次数。Android Studio 的 Callstack Sample 要求 Android 8.0（API 26）及以上。

解释结果时分开三类限制：

1. **采样缺失：**短于间隔的调用可能没有样本，同一方法的多次调用已被聚合。
2. **墙钟等待：**主线程等待 Binder、锁或 I/O 时不在 CPU 上运行，样本很少也可能是关键路径。用 System Trace 的 Running、Runnable、Sleeping 等状态还原等待。
3. **栈展示：**JNI、ART、`/apex/`、`/system/` 与内核帧可能出现在栈中；隐藏这些帧只改变展示，不改变原始样本。应用 native 采样可由 Simpleperf 后端支持。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q4 -->

**Q3: [learning] 接近发布的性能问题该选 profileable 还是 debuggable 构建？**

优先用接近发布配置的 profileable 变体定位，再在确需 Java 堆细节时缩小问题并制作 debuggable 变体。两者开放的采集能力不同：

1. **profileable：**Android 10（API 29）起，manifest 的 `<profileable>` 允许受控、相对低扰动的 System Trace、Callstack Sample 和 Native allocations。Studio 的低开销 Profile 入口走这一路径。
2. **debuggable：**开放 Java/Kotlin allocations、heap dump 和 Interaction timeline 等完整数据。debug 构建会改变 Java、JNI 与编译优化行为，测到的绝对时间未必代表发布包。
3. **量产现场：**Android 15（API 35）起 ProfilingManager 提供受系统限流和脱敏约束的生产采集入口，适用于开发机不在场的样本。

材料口径的推荐环境为 API 29+、带 Google Play 的测试设备与 AGP 7.3+；采集能力仍须以目标 Studio、设备和构建版本验证。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q10 -->

**Q4: [learning] Android Studio Memory Profiler 顶部的内存分类是什么口径？看到曲线上升就宣布泄漏为什么不可靠？**

分类统计来自系统报告的 App 私有已提交内存页，不包含与系统或其他 App 共享的页面，因此不等于 RSS 或 PSS。Graphics 包含图形缓冲区队列、GL surface 和 GL texture 等 CPU/GPU 共享内存，不是独立的 GPU 显存。曲线上升只是候选时段：缓存扩容、延迟 GC、原生分配器保留空闲页、Bitmap 或图形缓冲区的生命周期，以及测试路径没有回到同一状态，都能产生相似曲线。进程 RSS 没有立即下降也不等价于 Java 对象仍存活。泄漏结论需要可重复路径、明确的生命周期预期、多轮 GC 后对象数仍单调增长，以及从 GC Root 追到的引用链。

配套任务上，Java/Kotlin allocation 默认 Full 记录全部分配、可切 Sampled（结果为估计值），Android 7.1 及以下最多保存 65,535 条分配记录而 Android 8.0 及以上没有这项限制（材料口径）。Native allocation 任务按累计分配字节数触发采样（材料标注当前默认 2,048 字节），其 Native Remaining Size 只描述窗口内分配减释放的净变化，不能直接证明窗口结束后仍有泄漏。

<!-- 来源：03-resource-domains/01-memory-performance.md · Q15 -->

**Q5: [learning] Java/Kotlin 分配和 GC 在开发期与线上怎么观测？能复制 BinderInternal.GcWatcher 吗？**

开发期有两条工具链：

1. **Android Studio Java/Kotlin allocations：**提供 sampled 模式以降低高分配应用的观测影响。分析 churn 按窗口内累计次数与字节排序；分析泄漏按结束时仍存活的数量与引用排序，两者使用不同口径。
2. **Perfetto heapprofd：**Android 12 起可指定 `--heaps com.android.art` 记录 Java 分配调用栈。

ART allocation profile 只记对象创建位置，不提供删除时间或引用图；存活关系仍需 heap dump。Total 包含已回收对象，不能当作存活堆大小。

线上只保留经过开销评估的场景指标。不要复制 BinderInternal.GcWatcher（framework 隐藏 API，AAOS13 中仍靠 WeakReference 与 finalize 感知 GC）：finalization 的执行时间没有确定性，不能当每次 GC 的精确通知，更不能当线上 GC 计数器。System.gc() 只发出 GC 请求，不保证立即执行也不消除分配源，测试工具可用，产品代码靠修正分配速率与生命周期。
