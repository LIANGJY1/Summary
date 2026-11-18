# APM 工具能力与选型

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q1 -->

**Q1: [learning] 应用上线后的性能问题由哪四类工具能力分层处理？为什么客户端 APM、官方信号、线下诊断和 Benchmark 不能互相替代？**

四类能力按采集位置、运行阶段和证据深度互补：客户端 APM 覆盖大量真实设备但受权限与采样预算限制，官方信号口径稳定但服从版本与数据可见性限制，线下诊断证据深但只覆盖可复现场景，Benchmark 擅长改动前后对比但不能代表线上分布。选型的分类依据是这四项差异，不能按“都能看性能”合并。

1. **客户端 APM**：Matrix、KOOM、Measure、自研 SDK 等，运行在 Release 与灰度设备上，输出聚合指标、异常样本、堆栈与 trace。代价是包体、CPU/内存、hook 与插桩的兼容风险和上传平台建设。
2. **官方信号与 SDK**：Android vitals、JankStats、FrameMetrics、`ApplicationExitInfo`、`ProfilingManager`、Tracing，来自系统、Play 或应用进程，指标定义由平台固定。代价是最低 API 版本与回调开销限制。
3. **线下诊断**：Perfetto、Android Studio Profiler、simpleperf、LeakCanary 等，运行在 Debug/QA/实验室，输出完整 trace、调用栈与 heap。代价是需要设备、复现和人工分析。
4. **Benchmark/CI**：Macrobenchmark、Microbenchmark 等，输出可比较的耗时、帧与吞吐。代价是环境控制（编译模式、设备、温度）。

典型协作链路是：线上指标发现“哪个版本、页面、设备群在变差”并筛出样本，线下工具把一个样本展开到线程、调用栈与系统时间线定位原因，Benchmark 用同一实验验证修复，最后回到线上指标确认真实用户分布改善。两个常被忽略的边界：Android vitals 只覆盖 Play 认证设备上从 Play 安装且同意共享数据的用户。设备上的 Android 平台版本与应用内 APM SDK 版本要分别记录，升级 SDK 不会改变 framework 与内核实现，反之亦然。

**Q2: [learning] Matrix、KOOM、Booster、LeakCanary 这类三方库应该按什么维度选型？“README 写支持某 API 级别”能证明什么？**

选型维度是采集位置：它在哪个层面拿到信号，决定了能看到什么、风险在哪里。“支持 API 37”这类声明只证明框架维护者覆盖了其公开测试范围，不能推导出上层工具的某个具体能力在目标 ROM 上可用。

按采集位置分四类：

- **进程内探针**：Matrix、KOOM、LeakCanary、btrace 在 App 进程内拿方法、Looper、堆、线程、I/O 线索，看不到系统全局因果。
- **构建期改写**：Booster 与 Matrix 的 Gradle 插件在编译期检查或改写字节码，回答“代码里存在什么调用”，回答不了“线上执行了多少次、多慢”。
- **研发侧工具箱**：DoKit、BlockCanary 一类适合开发测试现场，不做生产采样与聚合。
- **可观测性平台**：Firebase Performance、Measure 负责上传、聚合与会话关联，不替代本地源码级定位。

版本声明要拆开读。Matrix 的 Gradle 插件 README 仍声明支持 AGP 3.5/4.0/4.1，而旧 Transform API 自 AGP 8.0 起已移除（材料口径），用 AGP 8/9 的项目要选已迁移的 fork 或自行迁移到 Instrumentation/Scoped Artifacts API。KOOM 的 Java 模块声明 API 21+，Native/Thread 模块限 API 24+ 与 arm64-v8a，其 fork dump（暂停 VM、fork 子进程 dump 后恢复父进程）把冻结缩到 20 ms 量级是项目测量结果而非设备保证。KOOM Native 用“分配元数据 + 保守扫描”找不可达分配块，漏报由形似指针的值造成，它不能替代检测越界和 use-after-free 的 ASan/HWASan。组合使用还要防四件事：开销叠加（探针、Hook、unwind、dump 各自耗资源）、Hook 链冲突（两个库改写同一符号时链顺序与递归保护互相破坏）、数据缺共同主键（session 与时间源不统一就无法关联）、监控不可关闭。接入前把远程开关、采样率和失败降级写进清单，用目标低端机实测开销，不引用上游百分比。
