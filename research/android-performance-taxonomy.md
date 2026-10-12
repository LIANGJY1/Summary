# Android 性能知识分类调研

调研日期：2026-10-11  
范围：Android 官方开发文档、Android vitals、Perfetto 官方文档。目标是为 `knowledge-base/01-android/12-performance` 设计便于学习、检索和持续扩展的分类目录。

## 结论摘要

Android 官方资料不是按“工具品牌”组织性能知识，而是围绕用户可感知的问题域组织：启动、渲染、稳定性、内存、电量与后台工作、应用体积/构建优化。测量、基准测试、剖析与线上监控是跨领域方法；Perfetto 是诊断与追因手段，适合放在“测量与诊断”入口，并由各问题域文章链接引用，不宜把所有 Perfetto 问题都和性能问题域并列。

推荐目录采用“少量大类目录 + 每篇边界明确的主题文章”。不要把不同根因域仅为减少文件数而拼成超长文章；同一主题下可拆成基础、进阶或机制、实践，但不要重复讲解。

## 一手资料给出的分类依据

1. Android Developers 的 [Improve performance](https://developer.android.com/topic/performance/improving-overview) 把常见优化问题分为应用启动、慢渲染、内存、电池与电量、应用体积；R8、Baseline Profiles、Startup Profiles 和 App Startup 则作为优化能力介绍。它是面向应用开发者的高层主题入口。
2. 更细的 [Address common performance issues](https://developer.android.com/topic/performance/issues) 将问题域展开为稳定性（ANR、崩溃）、渲染与启动、内存管理（LMK、RSS 与 swap、Bitmap）、电量与后台工作（wake lock、唤醒、后台扫描/网络、电量消耗）、权限与构建优化（DEX/R8）。文档说明这些问题域都可以通过 Android Studio Profiler、Perfetto、Logcat、`ApplicationExitInfo` 等工具诊断。
3. [Android vitals](https://developer.android.com/games/optimize/vitals) 从线上质量指标视角补充稳定性、内存、电池、启动、慢渲染、后台网络与唤醒等维度。它是线上观测/质量治理视角，不等同于单机诊断方法；指标统计口径和阈值也应在主题文中单独注明其适用场景。
4. [Perfetto Android trace analysis cookbook](https://perfetto.dev/docs/getting-started/android-trace-analysis) 按 trace 诊断任务举例，覆盖进程/线程、调度阻塞、启动锁竞争、内存等。它说明 Perfetto 横跨多个问题域，最佳组织方式是“工具与分析方法”一处系统讲解，专题文章按需链接具体分析路径。
5. Android 官方的 [performance measurement overview](https://developer.android.com/topic/performance/measuring-performance) 与 [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)、[Microbenchmark](https://developer.android.com/topic/performance/benchmarking/microbenchmark-overview) 区分了测量和优化的尺度：先识别用户旅程/系统级瓶颈，再用宏基准覆盖启动、滚动等端到端路径；局部热代码才适合微基准。基准测试是验证手段，不应被误认为独立性能问题域。

## 建议目录

目录名可按仓库现有编号风格调整，以下主题边界建议保持：

```text
12-performance/
├── README.md                           # 问题域入口、学习顺序、边界和题目索引
├── 00-methods-and-tools/               # 横切方法：发现、测量、定位、验证
│   ├── performance-workflow.md         # 学习路径、实验方法、指标和测量口径
│   └── profiling-tools.md              # Profiler、基准测试、系统诊断工具
├── 01-user-experience/                 # 用户可感知的交互质量
│   ├── rendering-and-responsiveness.md  # 帧流畅度、掉帧与输入响应
│   └── app-startup.md                   # 冷/温/热启动、TTID/TTFD、启动优化
├── 02-tracing/                         # 横跨问题域的时序取证
│   ├── perfetto-fundamentals.md         # 采集、轨道、线程状态和 SQL
│   ├── perfetto-advanced.md             # CPU 频率、BufferQueue、FrameTimeline 与 SDK
│   └── runtime-profiling.md              # 应用内运行时 profile 与插桩
├── 03-resource-domains/                # 独立资源与平台约束
│   ├── memory-performance.md
│   ├── network-performance.md
│   ├── platform-performance.md
│   ├── power-measurement.md
│   ├── cpu-scheduling.md
│   ├── energy-efficiency.md
│   └── app-power-governance.md
├── 04-reliability/                     # 可用性与进程退出
│   ├── anr-diagnosis.md
│   └── stability-and-crash.md
├── 05-observability/                   # 线上反馈和持续治理
│   ├── observability-governance.md
│   ├── online-diagnostics.md
│   ├── apm-platforms.md
│   └── apm-architecture.md
└── 06-app-optimization/                # 应用执行成本和交付产物
    ├── cpu-performance.md
    └── app-size.md
```

目录是主题入口，不要求每个目录只有一篇文章。较大的主题可拆分，但拆分依据应是可独立学习或诊断的边界（例如 Perfetto 的采集与查询、ANR 的机制与排查），不是机械地按题量切片。

## 推荐学习顺序

1. **建立闭环**：性能指标与用户体验 → 建立可重复基线 → 形成假设 → 采集证据 → 验证优化和回归。
2. **先看用户可见路径**：启动 → 渲染/卡顿 → ANR/崩溃。它们有清晰用户症状，也能训练从症状到主线程/调度/锁竞争等根因的分析。
3. **学习资源约束**：内存与进程回收 → CPU/运行时热点 → 后台任务、网络和电量。解释相邻问题之间的因果关系，但各自以独立边界组织。
4. **补齐测量工具深度**：Android Studio Profiler 与宏/微基准的选型 → Perfetto 采集、时间线、调度与 SQL 分析。Perfetto 可在前面专题中按需介绍最小用法，系统技能放在此处。
5. **最后形成生产治理**：Android vitals/APM → 按版本、设备、用户旅程做回归观测；把线上发现映射回对应的启动、渲染、稳定性、内存或电量专题。

学习路径不是严格的先修依赖。Perfetto 等工具可早期使用；此顺序主要避免“先堆工具知识、后找问题”的组织方式。

## 主题归属规则

- 按“要解决的症状/指标”归属主文；通用工具、方法和线上治理作为横切目录。
- 同一知识只设一个主条目。其他文章用链接补充关联，避免把 Perfetto、Android Studio、APM 分别重复写进多个专题。
- **调度**按目标区分：主线程/帧时间问题归渲染或 ANR；CPU 调度与优先级机制归 CPU/运行时；后台执行窗口、JobScheduler/WorkManager 归后台工作。确实跨域的原理在主文解释一次并互链。
- **网络**按场景区分：前台请求的时延/吞吐可作为端到端体验问题；后台流量、Wi-Fi 扫描和无线电唤醒归电量与后台工作。若涉及通用 TCP/DNS/TLS 协议细节，链接到网络知识库，不在此复制。
- **崩溃与 LMK/OOM**有关联但不等价：崩溃文章讲异常/信号与退出证据；内存文章讲分配、驻留、压力和回收机制；稳定性入口负责串联。
- **应用体积与性能**保留同一入口，但区分安装/下载体积、DEX/R8、启动页缺页和运行时执行效率，避免将所有“优化”混成一篇。
- APM/Android vitals 内容聚焦生产指标、采样、聚合、告警及回归闭环；不重复讲每种性能问题的根因。

## 适用范围与局限

上述目录主要参考通用 Android 应用质量文档。若知识库大量面向 AAOS/车机，应把设备/平台特有差异放在相应性能专题的独立小节或子主题中；平台实现仍归 Android 平台知识，不能为了贴合手机文档而删去车机独有的多显示器、长时间运行或系统服务约束。Google Play vitals 的指标只适用于其覆盖范围，不应作为所有车机项目的唯一验收指标。
