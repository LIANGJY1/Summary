# Android Performance Analyzer 系统追踪

> 本册覆盖 APA 的交互录制、Perfetto 数据分析和 Vulkan 录制选项及扰动。

**Q1: [learning] Android Performance Analyzer（APA）在工具链里处于什么位置？它和 Perfetto CLI、AGI 的分工边界是什么？**

APA 是 Google 提供的独立桌面性能分析应用（材料口径：2026 年 5 月以 open beta 发布 System Profiler，8 月起下载页不再标注 Beta），以 Perfetto 为系统追踪基础，覆盖 CPU、GPU、内存与功耗，支持 Project 管理、交互录制、Trace View 与 PerfettoSQL 查询。它要求受支持的 Android 12+ 设备，发布周期独立于 Android 平台，不是“某版本 Android 新增的 framework API”。

分工按证据需求选：需要脚本化、固定配置、批量采集与 SQL 回归时用 Perfetto CLI 与 Trace Processor。需要 Vulkan 单帧的命令、pipeline、shader、纹理级证据时用 AGI Frame Profiler。需要人机交互的录制管理、轨道整理、GPU counter 浏览和 A/B 对照时用 APA——AGI 文档也把 APA 列为 system profiling 的推荐工具。三者都受设备 GPU producer 与驱动数据限制，空轨道先查设备支持与录制配置。APA 不提供线上采集 API，真实用户设备的脱敏 profile 采集走 `ProfilingManager` 一类系统接口。 APA 展示的 `sched_switch`、FrameTimeline 等数据语义由设备侧决定，时间上相邻的两个事件只是相关线索，写成因果还要线程状态、fence、调用栈或可重复实验支撑。

**Q2: [learning] 用 APA 录一份可信的系统 trace，录制配置和构建类型要注意什么？它注入的 Vulkan layer 会不会扰动结果？**

录制前固定设备、build fingerprint、GPU driver、刷新率、温度与 App 构建配置。launch mode 与 trigger 按问题选（启动问题用 On Startup 覆盖进程创建和首帧，稳态场景用 Manual 进目标窗口）。构建类型上，Java/Kotlin 性能测量建议 release-like 且 `debuggable=false`，Vulkan 应用要采 Vulkan 专属数据时才建议 `debuggable=true`——一个混合 App 很难单次录制兼顾两种目标，应保留两份实验，且两次 trace 的绝对时间不能互换结论。

APA 可在录制时注入三类 Vulkan layer（材料口径）：CPU Timing 把 Vulkan API 调用耗时显示为线程上的 slice，`vkCmdDraw` 一类高频函数被有意排除以免开销扭曲结果，它代表 CPU 提交侧耗时、不代表 GPU 执行时间。Render Pass Debug Names 把代码设置的调试标注带进 Trace View。Screenshots 依赖标准 `VK_KHR_swapchain`，用其他 present 机制时拿不到截图，且截图不能证明画面已交给显示端。启用 layer 本身改变运行环境，对外报告的性能数字必须另录一份未启用 layer 的基线。两个容易踩的采集坑：USB 连接会让 Battery 轨道持续充电，能量结论要转功耗测试或仪器。自定义 `TraceConfig` 增删数据源前先确认其在目标设备上注册、buffer 策略能覆盖录制时长，不要为画面丰富而全开数据源。APA 的 PerfettoSQL 与人工视觉对齐适合复盘，定量 A/B 比较仍用相同 SQL 或 benchmark 指标。
