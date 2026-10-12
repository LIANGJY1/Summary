# AndroidX Tracing

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q7 -->

**Q1: [learning] androidx.tracing 2.0 的进程内 Tracer 与经典 trace {} 为什么是两条路径而不是替代关系？**

经典 `Trace.beginSection()` / `trace {}` 把同步 slice、异步 slice 和 counter 写入系统 trace 缓冲区，随系统 trace 会话进入 Perfetto。2.0 新增的进程内路径把带字段的事件写入应用进程控制的缓冲区，由 `TraceDriver` 持有 `Tracer` 管理一次 tracing 生命周期、`TraceSink` 决定事件如何序列化输出，`tracing-wire:2.0.0` 提供 Perfetto 格式的 `TraceSink` 实现。两者用途不同，官方也没有弃用经典 API——低频、希望始终进入系统 trace 缓冲区的事件仍推荐它。

进程内路径的采集边界：Android Studio 的 System Trace 目前不会自动采集这部分数据。Benchmark 1.5.0-rc01（预发布线）才能在测试结束后采集并合并目标包的进程内 trace。`tracing-perfetto:1.0.1` 又是另一条独立接入路径（自带 native binary 与启用握手），与经典 API、`tracing-wire` 互不相同。新 API 还支持 category 筛选、metadata、Perfetto flow、counter 与 `traceCoroutine()`，但接入时要自己设计采集启动方式、文件生命周期、丢事件策略和体积控制。

版本坐标：稳定线 `androidx.tracing:tracing:2.0.0` 的 Android 变体 minSdk 23。仍需覆盖 API 21–22 的应用用 1.3.0 兼容线（自 1.3.0 起 `tracing-ktx` 已并入主 artifact）。平台侧的 `android.os.Trace` 机制（同步/异步/counter 语义、127 字符限制、AAOS13 的 libcutils 单路径）已在 01-Perfetto 文档核对，此处不重复。


<!-- 来源：05-observability/03-apm-platforms.md · Q8 -->

**Q2: [learning] 非 debuggable 进程里 Trace.isEnabled() 返回 true 需要什么条件？各 API 段允许应用写 trace 的前提有什么差别？**

`isEnabled()` 为 true 需要同时满足两件事：当前存在能接收应用事件的 trace 会话，且该进程被允许写 app trace。Android 12 以后“默认允许应用 tracing”不表示系统一直在后台记录——没有采集会话时返回 false，昂贵的名称构造应借此跳过或改用 lazy label。

非 debuggable 进程的允许条件按 API 段递进（AndroidX Tracing 源码口径，属第三方库实现，未本地核对。平台行为按版本转写）：

1. **API 21–28**：需在应用启动早期调用 `Trace.forceEnableAppTracing()`——这是 AndroidX 提供的兼容入口（AAOS13 平台源码没有同名公开 API），内部反射平台隐藏方法。
2. **API 29–30**：Manifest 设置 `<profileable android:shell="true"/>`，或仍用 `forceEnableAppTracing()`。
3. **API 31+**：默认允许。显式设置 `<profileable android:enabled="false"/>` 或 `shell="false"` 会限制该能力，`forceEnableAppTracing()` 在这一段是无操作。

采集侧还要包含目标应用：Macrobenchmark 会自动采集目标应用的自定义 trace point。自定义 Perfetto 配置要把目标包放进 atrace 的应用配置。正式性能结论应来自 non-debuggable、profileable 且接近发布配置的构建——debuggable 包的调试设施与运行时行为会改变时间分布。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q12 -->

**Q3: [learning] 新项目要做线上应用内 trace 或系统级追踪，公共选项有哪些？Profilo 的架构还有什么参考价值？**

公共追踪入口按目标选择：

1. **添加业务标记：**Java / Kotlin 使用 `android.os.Trace` 或 AndroidX Tracing（材料口径中的稳定版 1.3.0）；Native 使用 NDK ATrace。它们只发标记，采集端启用对应类别后才出现在 Perfetto system trace 中。
2. **线上应用内采集：**评估 AndroidX Tracing `2.0.0-beta01`（预发布，可插拔 backend / sink，可写 Perfetto trace packet）或 Perfetto Tracing SDK 的 in-process backend（C++17，不连接系统 `traced`，只含应用注册的数据源）。
3. **系统级因果分析：**使用 Android 10+ Perfetto system trace，同轴组合应用标记、sched、Binder、频率、内存与 I/O。
4. **平台托管 profile：**Android 15+ 可用 `ProfilingManager` 请求；它受限流，不保证执行，结果会脱敏。

Profilo 仍有架构参考价值：provider 按需启停的组织方式、trigger 决定是否采集的控制流、固定容量 mmap 环形缓冲区（写满循环复用旧槽位，读线程落后时会覆盖未读数据）与待处理文件管理，都是线上短窗口 trace 的成熟设计。但直接依赖其上游 AAR 快速上线没有维护方保证：仓库已归档、trace schema 无版本承诺、实现依赖私有符号与 PLT relocation，Java unwinder 依赖旧 ART 布局。两种 in-process 方案都只含应用自己的数据源，不会自动加入调度器或系统进程事件。选择应用内采集后，抽样、大小与频率限制、脱敏、加密、上传与保留策略仍要自己实现——Perfetto 格式只解决数据模型与工具兼容，不替应用决定合规边界。
