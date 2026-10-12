# Android 性能工具

本目录按工具或紧密相关 API 分组。工具的采集与使用方法、输出口径和采集器实现归此；被观测对象的根因机制仍留在应用、系统或底层主题。来源答案按原文保留，编号在各组内连续，注释记录原文件和 Q 号。

共 45 个工具入口、235 道题；BatteryStats 采集对比只保留在 Battery Historian 主条目，BatteryStats 读数页保留其余独有问题。

## 推荐学习顺序

1. **建立证据链：**ADB/dumpsys、Logcat、Android Code Search 与源码坐标。
2. **采集和分析：**Perfetto → Android Studio Profiler、Simpleperf、heapprofd、HPROF。
3. **埋点与 Profile：**AGP 插桩、AndroidX Tracing、Baseline Profile，再看 Profilo/XTrace 的实现边界。
4. **验证回归：**Macrobenchmark/Microbenchmark、CI、PerfDog 与 bundletool。
5. **平台数据入口：**BatteryStats、Battery Historian、ProfilingManager、statsd、StrictMode、Vitals 与进程历史 API。
6. **线上监控：**先看 APM 选型和各产品组，再读端侧采集器架构。

## 工具索引

### Trace、插桩与 profiling

- [Perfetto 采集与 Trace Processor 分析](./perfetto/01-trace-capture-and-analysis.md)（66 题）
- [Agp Instrumentation](./agp-instrumentation/01-bytecode-instrumentation.md)（4 题）
- [Android Studio Profiler](./android-studio-profiler/01-cpu-and-memory-profiler.md)（5 题）
- [Simpleperf 与 CPU 微架构分析](./simpleperf/01-cpu-sampling-and-analysis.md)（6 题）
- [heapprofd Native Heap Profile](./heapprofd/01-native-heap-profiling.md)（4 题）
- [ART HPROF 与 Java Heap Dump](./android-hprof/01-java-heap-dump.md)（3 题）
- [Debug Memory Info](./debug-memory-info/01-process-memory-summary.md)（1 题）
- [Native 内存诊断工具](./memory-sanitizers/01-native-memory-diagnostics.md)（1 题）
- [Profilo](./profilo/01-atrace-provider.md)（1 题）
- [AndroidX Tracing](./androidx-tracing/01-in-process-tracing.md)（3 题）
- [Xtrace](./xtrace/01-dynamic-method-tracing.md)（1 题）
- [Baseline Profile 与 Startup Profile 工具验证](./baseline-profile/01-profile-collection-and-validation.md)（4 题）

### 基准测试与回归

- [Benchmark、Macrobenchmark 与性能 CI](./benchmark/01-macro-micro-and-ci.md)（17 题）
- [bundletool 设备交付体积测量](./bundletool/01-device-delivery-size.md)（1 题）
- [PerfDog 实验室测量](./perfdog/01-lab-measurements.md)（3 题）

### 功耗工具与系统数据

- [BatteryStats 读数与测量](./battery-stats/01-reading-batterystats.md)（3 题）
- [Battery Historian 与功耗报告](./battery-historian/01-battery-historian-and-bugreport.md)（6 题）
- [Android Studio Power Profiler](./power-profiler/01-power-profiler.md)（选型题见 [Battery Historian 采集对比主条目](./battery-historian/01-battery-historian-and-bugreport.md)）
- [Macrobenchmark PowerMetric](./macrobenchmark-powermetric/01-power-metric.md)（选型题见 [Battery Historian 采集对比主条目](./battery-historian/01-battery-historian-and-bugreport.md)）
- [PowerMonitor API](./power-monitor/01-power-monitor-api.md)（1 题）
- [PowerStats HAL 数据读取边界](./powerstats-hal/01-powerstats-data.md)（2 题）

### 命令行与系统诊断

- [ADB、am 与 dumpsys 诊断命令](./adb-dumpsys/01-process-and-system-diagnostics.md)（9 题）
- [Android Code Search](./android-code-search/01-source-search.md)（3 题）
- [Logcat 诊断](./logcat/01-logcat-diagnostics.md)（1 题）
- [ProfilingManager 与 ProfilingTrigger](./profiling-manager/01-managed-profiling.md)（4 题）
- [statsd 配置与验证](./statsd/01-configuration-and-validation.md)（3 题）
- [StrictMode](./strictmode/01-thread-and-vm-policy.md)（5 题）
- [ApplicationExitInfo](./application-exit-info/01-exit-history-and-traces.md)（6 题）
- [ApplicationStartInfo](./application-start-info/01-startup-history.md)（3 题）
- [Android Vitals](./android-vitals/01-play-vitals-and-reporting.md)（8 题）
- [Native Symbolization](./native-symbolization/01-symbols-and-mappings.md)（3 题）

### APM 产品与采集器

- [APM 工具能力与选型](./apm-platforms/00-tool-selection.md)（1 题）
- [Firebase Performance](./firebase-performance/01-traces-and-reporting.md)（5 题）
- [商业 APM 选型](./commercial-apm/01-evaluation.md)（1 题）
- [Matrix](./matrix/01-gradle-plugin-and-runtime.md)（1 题）
- [Trace Canary](./trace-canary/01-method-call-tree.md)（1 题）
- [btrace](./btrace/01-method-stack-capture.md)（2 题）
- [LeakCanary](./leakcanary/01-leak-analysis.md)（4 题）
- [KOOM](./koom/01-heap-and-thread-leak.md)（2 题）
- [DoKit](./dokit/01-performance-overlay-and-network-mocking.md)（3 题）
- [Measure APM](./measure-apm/01-session-and-heap-capture.md)（1 题）
- [BlockCanary](./blockcanary/01-main-thread-stall-detection.md)（1 题）
- [Collie](./collie/01-monitoring-boundaries.md)（1 题）
- [AndroidGodEye 与 Rabbit](./androidgodeye-rabbit/01-historical-project-evaluation.md)（1 题）
- [APM 端侧采集器实现与 API](./apm-collector/01-collector-architecture-and-apis.md)（34 题）

## 来源与边界

逐题检查了 22 篇文章，工具题来自其中 20 篇：通用方法与工具、用户体验、Trace、内存/功耗、ANR/崩溃、可观测性、APM、CPU 与包体优化。来源标记为相对 `12-performance/` 的原文件路径和 Q 号。

`03-resource-domains/02-network-performance.md` 与 `03-resource-domains/03-platform-performance.md` 没有独立工具教程或工具结果解释题；其工具仅作为根因分析的证据入口。其他跨层题按问题目标处理：采集、查询和输出解释归本目录，FrameTimeline、调度、Binder、BufferQueue、ART、网络及功耗的根因机制归原主题。 已在系统主题保留的 `am_anr`/ANR trace/DropBox 证据（[ANR 与崩溃](../02-系统/04-anr-crash.md)，Q11）、蓝牙扫描证据字段与 controller activity 的关系（[Battery Historian 与功耗报告](./battery-historian/01-battery-historian-and-bugreport.md)，Q6）和 FrameTimeline Expected/Actual（[渲染与输入](../02-系统/01-rendering-input.md)，Q6），不在工具页重复全文；工具页的其他采集与分析内容仍保留。
