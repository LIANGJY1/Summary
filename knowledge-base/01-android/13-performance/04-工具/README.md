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

- [Perfetto 采集与 Trace Processor 分析](./01-perfetto/01-Trace采集与分析.md)（66 题）
- [Agp Instrumentation](./02-agp-instrumentation/01-字节码插桩.md)（4 题）
- [Android Studio Profiler](./03-android-studio-profiler/01-CPU与内存剖析.md)（5 题）
- [Simpleperf 与 CPU 微架构分析](./04-simpleperf/01-CPU采样与分析.md)（6 题）
- [heapprofd Native Heap Profile](./05-heapprofd/01-原生堆剖析.md)（4 题）
- [ART HPROF 与 Java Heap Dump](./06-android-hprof/01-Java堆转储.md)（3 题）
- [Debug Memory Info](./07-debug-memory-info/01-进程内存概要.md)（1 题）
- [Native 内存诊断工具](./08-memory-sanitizers/01-原生内存诊断.md)（1 题）
- [Profilo](./09-profilo/01-ATrace提供者.md)（1 题）
- [AndroidX Tracing](./10-androidx-tracing/01-进程内埋点.md)（3 题）
- [Xtrace](./11-xtrace/01-动态方法跟踪.md)（1 题）
- [Baseline Profile 与 Startup Profile 工具验证](./12-baseline-profile/01-基线Profile验证.md)（4 题）

### 基准测试与回归

- [Benchmark、Macrobenchmark 与性能 CI](./13-benchmark/01-基准测试与CI.md)（17 题）
- [bundletool 设备交付体积测量](./14-bundletool/01-交付体积测量.md)（1 题）
- [PerfDog 实验室测量](./15-perfdog/01-实验室测量.md)（3 题）

### 功耗工具与系统数据

- [BatteryStats 读数与测量](./16-battery-stats/01-电量统计读数.md)（3 题）
- [Battery Historian 与功耗报告](./17-battery-historian/01-功耗报告.md)（6 题）
- [Android Studio Power Profiler](./18-power-profiler/01-功耗剖析器.md)（选型题见 [Battery Historian 采集对比主条目](./17-battery-historian/01-功耗报告.md)）
- [Macrobenchmark PowerMetric](./19-macrobenchmark-powermetric/01-功耗指标.md)（选型题见 [Battery Historian 采集对比主条目](./17-battery-historian/01-功耗报告.md)）
- [PowerMonitor API](./20-power-monitor/01-功耗监测API.md)（1 题）
- [PowerStats HAL 数据读取边界](./21-powerstats-hal/01-功耗HAL数据.md)（2 题）

### 命令行与系统诊断

- [ADB、am 与 dumpsys 诊断命令](./22-adb-dumpsys/01-诊断命令.md)（9 题）
- [Android Code Search](./23-android-code-search/01-源码检索.md)（3 题）
- [Logcat 诊断](./24-logcat/01-日志诊断.md)（1 题）
- [ProfilingManager 与 ProfilingTrigger](./25-profiling-manager/01-受管剖析.md)（4 题）
- [statsd 配置与验证](./26-statsd/01-配置与验证.md)（3 题）
- [StrictMode](./27-strictmode/01-严格模式策略.md)（5 题）
- [ApplicationExitInfo](./28-application-exit-info/01-进程退出记录.md)（6 题）
- [ApplicationStartInfo](./29-application-start-info/01-启动历史.md)（3 题）
- [Android Vitals](./30-android-vitals/01-Vitals报表.md)（8 题）
- [Native Symbolization](./31-native-symbolization/01-符号与映射.md)（3 题）

### APM 产品与采集器

- [APM 工具能力与选型](./32-apm-platforms/00-工具选型.md)（1 题）
- [Firebase Performance](./33-firebase-performance/01-Trace与报表.md)（5 题）
- [商业 APM 选型](./34-commercial-apm/01-商业APM选型.md)（1 题）
- [Matrix](./35-matrix/01-插件与运行时.md)（1 题）
- [Trace Canary](./36-trace-canary/01-方法调用树.md)（1 题）
- [btrace](./37-btrace/01-方法栈采集.md)（2 题）
- [LeakCanary](./38-leakcanary/01-泄漏分析.md)（4 题）
- [KOOM](./39-koom/01-堆与线程泄漏.md)（2 题）
- [DoKit](./40-dokit/01-浮层与网络模拟.md)（3 题）
- [Measure APM](./41-measure-apm/01-会话与堆采集.md)（1 题）
- [BlockCanary](./42-blockcanary/01-主线程卡顿检测.md)（1 题）
- [Collie](./43-collie/01-监控边界.md)（1 题）
- [AndroidGodEye 与 Rabbit](./44-androidgodeye-rabbit/01-历史项目评估.md)（1 题）
- [APM 端侧采集器实现与 API](./45-apm-collector/01-采集器架构与API.md)（34 题）

## 来源与边界

各册题目的行内来源注释指向迁移前的英文分类稿；该分类已并入本目录后删除，注释仅作历史追溯。

[网络请求](../01-应用/04-网络/01-网络请求.md) 与 [平台差异与诊断](../02-系统/05-平台差异与诊断.md) 没有独立工具教程或工具结果解释题；其工具仅作为根因分析的证据入口。其他跨层题按问题目标处理：采集、查询和输出解释归本目录，FrameTimeline、调度、Binder、BufferQueue、ART、网络及功耗的根因机制归原主题。已在系统主题保留的 `am_anr`/ANR trace/DropBox 证据（[ANR 与系统故障诊断](../02-系统/04-ANR与故障诊断.md)，Q10）、蓝牙扫描证据字段与 controller activity 的关系（[Battery Historian 与功耗报告](./17-battery-historian/01-功耗报告.md)，Q6）和 FrameTimeline Expected/Actual（[合成与输入](../02-系统/01-合成与输入.md)，Q7），不在工具页重复全文；工具页的其他采集与分析内容仍保留。
