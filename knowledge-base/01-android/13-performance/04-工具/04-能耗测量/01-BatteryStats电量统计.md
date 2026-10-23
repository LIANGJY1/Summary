# BatteryStats 读数与测量

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

BatteryStats 采集流程和与 Battery Historian、Power Profiler、Macrobenchmark PowerMetric 的分工见 [Battery Historian 主条目](02-BatteryHistorian功耗报告.md)。

<!-- 来源：03-resource-domains/04-power-measurement.md · Q1 -->

**Q1: [learning] BatteryStats 的活动时长、PowerStats HAL 的能量读数（µWs）与设置页的应用耗电百分比，为什么不能直接换算和横向比较？**

三者是不同层次的数据：BatteryStats 记录 UID 与组件的活动时间、计数与状态（`BatteryStatsImpl` 维护，`BatteryStatsService` 运行在 system_server）。PowerStats HAL/ODPM 返回某条 rail 或 energy consumer 自开机累计的能量，单位 µWs。设置页百分比是 Framework 归因计算后按查询窗口生成的结果，单位是 mAh 或百分比。能量（µWs）与电荷量（µAh/mAh）之间的换算还必须引入电压，忽略单位直接比较会得出错误结论。

数据从硬件进入 Framework 后不保证每层完整：设备没有对应 energy consumer 时，Framework 回退到 controller activity 或 `power_profile.xml` 估算。共享 rail 无法区分 UID 时，还要按活动时间二次分配。因此 rail 与电池端读数是测量（带设备实现差异），UID 归因是“测量加模型加分配”的混合结果，误差来源不同。

做法：报告耗电结论前先声明口径（活动时长、charge、energy、mAh、百分比），比较只发生在同一口径内。`BATTERY_PROPERTY_CHARGE_COUNTER`（µAh）与 `BATTERY_PROPERTY_CURRENT_NOW`（µA）等属性的精度和符号方向由设备实现决定，不给库仑计设通用误差范围。

<!-- 来源：03-resource-domains/04-power-measurement.md · Q11 -->

**Q2: [learning] 设计一次可信的用户设置类功耗实验（对比刷新率、深色模式等），变量应该怎么安排？统计与失败样本要注意什么？**

实验按三个阶段进行：

1. **固定基线：**固定设备、亮度、刷新率范围、主题、网络、内容、温度区间与采样窗口，用于比较代码版本。
2. **单变量扫描：**每次只改一项设置，并验证其他生效值没有联动变化；例如省电模式可能同时改变刷新率、后台任务与处理器策略。
3. **用户场景复测：**恢复自动亮度、ARR、动态内容与常用网络，检查结论方向是否仍存在，以评估实际体验。

每个变量都记录请求/配置值与生效值及证据：亮度记滑块、自动亮度与 nit/HBM；刷新率记 VSync 间隔与显示模式；网络记信号、重试与缓存；热状态记起始温度与 severity。

统计边界：功耗数据常呈偏态分布且有长尾，报告要给出重复次数、中位数、离散程度与预先定义的异常值规则。`p < 0.05`（如 Mann-Whitney U 检验）只说明当前样本存在统计证据，不等于差异有产品价值。删除异常样本必须有预先定义的规则并保留失败原因——脚本超时、广告出现、网络失败、温度越界是场景质量问题，不能只因数值偏大就移除。

采集与脱敏：`dumpsys display`、`dumpsys SurfaceFlinger`、`dumpsys batterystats --charged` 与 bugreport 适合保存配置快照和诊断上下文，但不能替代连续时间线。Perfetto 可同时观察 FrameTimeline、VSync、调度、频率与设备开放的 power rail。命令输出包含应用、账号、设备标识与网络信息，分享前要脱敏。

<!-- 来源：05-observability/02-online-diagnostics.md · Q18 -->

**Q3: [learning] 验证一个“后台同步更省电”的改动，为什么“功率降低了”或“时间线更安静了”都不算结论？网络功耗的固定排名（Wi-Fi 低、5G 高）为什么不可用？**

功耗优化必须同时观察“完成了多少工作”和“付出了多少能量”：只降低功率可能让任务执行更久、总能量反而增加，只缩短耗时也可能抬高峰值功率和温度。每次实验至少保留五组数据：场景产出（完成的同步数、请求量）、性能（耗时、CPU 时间、字节）、能量（整机或所选 `PowerMonitor` 的增量）、环境（温度、亮度、网络、充电状态、版本）和正确性（失败率、数据时效）。A/B 在等价条件下交替运行基准与候选实现（同设备、同场景、相近初始状态，并保留不执行待测场景的空闲基线），结论只填测量得到的分布、样本条件和不确定性，不预写固定百分比或“续航增加几小时”。某次 Historian 时间线更安静，也需要多轮实验与能量增量支持。

网络功耗的固定排序不成立，因为信号质量、频段、基带状态机、扫描、连接建立、传输时长、数据量、硬件代际和共享芯片都会改变结果：5G 不统一解释为更耗电，弱信号或频繁扫描的 Wi-Fi 同样可能很贵。`TrafficStats` 或 HealthStats 的字节数只表示活动量，能量还取决于传输时间和无线状态，“每 MB 固定耗电”不能跨网络和设备复用。优化假设要逐项验证：合并可延期请求可减少 radio 活跃次数但增加等待与单次失败影响。连接复用减少握手成本而频繁主动断开可能增加 radio 状态切换。预取可能下载用户永远不用的数据。WorkManager 的网络约束只适合可延期任务，交互请求仍按用户时效执行。
