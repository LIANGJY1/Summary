# Android 性能工具

本目录按工具能力分组，每个具体工具仍保留独立文章。先按采集目标进入能力组，再用工具名定位条目；性能根因与优化判断见[基础](../00-基础/README.md)、[应用](../01-应用/README.md)、[系统](../02-系统/README.md)和[底层](../03-底层/README.md)。知识内容遵守 [WRITING-GUIDE.md](../../../WRITING-GUIDE.md)。

## 能力分组

1. [采集与追踪](./01-采集与追踪/README.md)（5 个工具，75 题）：Perfetto、埋点和 tracing 提供者，用于记录时间线与构造可分析事件。
2. [性能剖析](./02-性能剖析/README.md)（8 个工具，27 题）：CPU、Java/Native 内存和受管剖析工具，用于定位热点、分配和符号。
3. [基准与交付](./03-基准与交付/README.md)（4 个工具，25 题）：基准测试、性能回归和应用包交付测量工具。
4. [能耗测量](./04-能耗测量/README.md)（6 个工具，12 题）：BatteryStats、功耗报告、Power Profiler 和硬件能耗接口。
5. [平台诊断](./05-平台诊断/README.md)（8 个工具，38 题）：ADB、日志、平台状态与 Android 诊断 API。
6. [线上监控与APM](./06-线上监控与APM/README.md)（14 个工具，58 题）：线上性能监控产品、端侧监控组件和 APM 采集器实现。

## 工具名称索引

1. [Perfetto 采集与 Trace Processor 分析](./01-采集与追踪/01-perfetto/01-Trace采集与分析.md)
2. [Agp Instrumentation](./01-采集与追踪/02-agp-instrumentation/01-字节码插桩.md)
3. [Profilo](./01-采集与追踪/09-profilo/01-ATrace提供者.md)
4. [AndroidX Tracing](./01-采集与追踪/10-androidx-tracing/01-进程内埋点.md)
5. [Xtrace](./01-采集与追踪/11-xtrace/01-动态方法跟踪.md)
6. [Android Studio Profiler](./02-性能剖析/03-android-studio-profiler/01-CPU与内存剖析.md)
7. [Simpleperf 与 CPU 微架构分析](./02-性能剖析/04-simpleperf/01-CPU采样与分析.md)
8. [heapprofd Native Heap Profile](./02-性能剖析/05-heapprofd/01-原生堆剖析.md)
9. [ART HPROF 与 Java Heap Dump](./02-性能剖析/06-android-hprof/01-Java堆转储.md)
10. [Debug Memory Info](./02-性能剖析/07-debug-memory-info/01-进程内存概要.md)
11. [Native 内存诊断工具](./02-性能剖析/08-memory-sanitizers/01-原生内存诊断.md)
12. [ProfilingManager 与 ProfilingTrigger](./02-性能剖析/25-profiling-manager/01-受管剖析.md)
13. [Native Symbolization](./02-性能剖析/31-native-symbolization/01-符号与映射.md)
14. [Baseline Profile 与 Startup Profile 工具验证](./03-基准与交付/12-baseline-profile/01-基线Profile验证.md)
15. [Benchmark、Macrobenchmark 与性能 CI](./03-基准与交付/13-benchmark/01-基准测试与CI.md)
16. [bundletool 设备交付体积测量](./03-基准与交付/14-bundletool/01-交付体积测量.md)
17. [PerfDog 实验室测量](./03-基准与交付/15-perfdog/01-实验室测量.md)
18. [BatteryStats 读数与测量](./04-能耗测量/16-battery-stats/01-电量统计读数.md)
19. [Battery Historian 与功耗报告](./04-能耗测量/17-battery-historian/01-功耗报告.md)
20. [Android Studio Power Profiler](./04-能耗测量/18-power-profiler/01-功耗剖析器.md)
21. [Macrobenchmark PowerMetric](./04-能耗测量/19-macrobenchmark-powermetric/01-功耗指标.md)
22. [PowerMonitor API](./04-能耗测量/20-power-monitor/01-功耗监测API.md)
23. [PowerStats HAL 数据读取边界](./04-能耗测量/21-powerstats-hal/01-功耗HAL数据.md)
24. [ADB、am 与 dumpsys 诊断命令](./05-平台诊断/22-adb-dumpsys/01-诊断命令.md)
25. [Android Code Search](./05-平台诊断/23-android-code-search/01-源码检索.md)
26. [Logcat 诊断](./05-平台诊断/24-logcat/01-日志诊断.md)
27. [statsd 配置与验证](./05-平台诊断/26-statsd/01-配置与验证.md)
28. [StrictMode](./05-平台诊断/27-strictmode/01-严格模式策略.md)
29. [ApplicationExitInfo](./05-平台诊断/28-application-exit-info/01-进程退出记录.md)
30. [ApplicationStartInfo](./05-平台诊断/29-application-start-info/01-启动历史.md)
31. [Android Vitals](./05-平台诊断/30-android-vitals/01-Vitals报表.md)
32. [APM 工具能力与选型](./06-线上监控与APM/32-apm-platforms/00-工具选型.md)
33. [Firebase Performance](./06-线上监控与APM/33-firebase-performance/01-Trace与报表.md)
34. [商业 APM 选型](./06-线上监控与APM/34-commercial-apm/01-商业APM选型.md)
35. [Matrix](./06-线上监控与APM/35-matrix/01-插件与运行时.md)
36. [Trace Canary](./06-线上监控与APM/36-trace-canary/01-方法调用树.md)
37. [btrace](./06-线上监控与APM/37-btrace/01-方法栈采集.md)
38. [LeakCanary](./06-线上监控与APM/38-leakcanary/01-泄漏分析.md)
39. [KOOM](./06-线上监控与APM/39-koom/01-堆与线程泄漏.md)
40. [DoKit](./06-线上监控与APM/40-dokit/01-浮层与网络模拟.md)
41. [Measure APM](./06-线上监控与APM/41-measure-apm/01-会话与堆采集.md)
42. [BlockCanary](./06-线上监控与APM/42-blockcanary/01-主线程卡顿检测.md)
43. [Collie](./06-线上监控与APM/43-collie/01-监控边界.md)
44. [AndroidGodEye 与 Rabbit](./06-线上监控与APM/44-androidgodeye-rabbit/01-历史项目评估.md)
45. [APM 端侧采集器实现与 API](./06-线上监控与APM/45-apm-collector/01-采集器架构与API.md)

共 45 个工具条目、235 题。