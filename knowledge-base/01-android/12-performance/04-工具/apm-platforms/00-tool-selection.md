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
