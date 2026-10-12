# PowerStats HAL 数据读取边界

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/02-online-diagnostics.md · Q19 -->

**Q1: [learning] PowerStats HAL 的数据类型、设备能力与公开权限分别意味着什么？**

PowerStats HAL 可报告三类数据，但设备实现覆盖度决定它能否支持组件或 UID 能耗诊断；仅能列出对象并不代表有真实读数。

1. **状态驻留：**PowerEntity / StateResidencyResult 提供各状态的累计驻留时长、进入次数和最近进入时间。
2. **组件能量：**EnergyConsumer / EnergyConsumerResult 提供累计能量；设备可选提供 EnergyConsumerAttribution 做 UID 分摊，分摊总和不能超过组件能量。
3. **计量通道：**Channel / EnergyMeasurement 通常对应电源轨，能量使用 µW·s，时间戳使用 CLOCK_BOOTTIME。

PowerStats AIDL v2（按 Android 17 冻结接口核对）与 HIDL 1.0 的能力不同：framework 连接 AIDL v2 失败时可回退 HIDL 1.0，后者没有 EnergyConsumer。AOSP 默认实现可能只注册用于联调的虚构 rail / consumer；设备只实现少数 rail 时，`dumpsys powerstats` 仍会列出对象，但覆盖范围有限。未提供 attribution 的组件不能据此得到 UID 分摊。

Power Monitor 公共 API 从 API 35 起只公开 Channel / EnergyConsumer 累计值，不公开状态驻留与 UID attribution。没有 `ACCESS_FINE_POWER_MONITORS` 时，缓存最长 20 秒、granularity 为 UNSPECIFIED；有权限时为 250 ms、FINE。该权限为 signature / privileged / development 级。读数还按调用 UID 混入随机扰动，monitor index 在重启后不保证稳定。

比较设备能力可用 L0–L4 描述：L0 无可用数据；L1 只有计量通道；L2 有组件能量；L3 有 UID 分摊；L4 测量链经外部仪器验证。比较前保存对象清单、对累计值做差分、统一时间轴，并区分负差值与计数回绕。测量同一任务的能效优先用任务总能量，同时记录耗时与不确定性；功率 W、能量 J / Wh 和电池电流 mA 是不同物理量。

<!-- 来源：03-resource-domains/06-energy-efficiency.md · Q8 -->

**Q2: [learning] 测 LLM 推理能耗时，整机电池电流与 power rails（部件能量轨道）各能说明什么？**

整机电池电流与电压反映测试窗口内整机从电池侧取用或回充的功率；屏幕、调制解调器、SoC 和后台任务等都会计入，不能直接归因到 LLM。USB 充电可能改变计数器的方向或含义，因此边充电数据不能与放电测试直接比较。计算窗口能量时，先核对设备电流正负号定义，再按 `E = ∫ V(t) × I(t) dt` 积分。

Power rails 是设备实现提供的部件计量通道，能说明对应 rail 在窗口内的能量变化；它只覆盖该设备实际实现并可读取的部件，不保证覆盖完整 SoC，也不天然提供进程或模型级归因。能力取决于设备的 `IPowerStats` / PowerStats HAL 实现。许多量产设备没有可用 rail；应如实报告不可用，不能用 `power_profile.xml` 或其他估算值冒充 rail 实测。Battery current 属于整机口径，rail 属于设备实现覆盖范围内的组件口径，两者不能互相替代。

比较推理配置时，按相同采样方式取空闲基线：保持屏幕、网络、温度、模型与采集配置相近，在邻近时间段测量，并同时报告原始整机能量与扣除基线后的增量。基线扣除可能放大短请求的误差，所以要多次重复、随机安排配置顺序，并记录失败样本与设备是否处于充电状态。power rail 也要按同一时间窗口对累计读数做差分。

能耗分母要与工作量一起声明：至少报告**任务总能量**和**每次请求能量**；生成任务还应报告**每个输出 token 的能量**，明确 token 计数口径。配套记录请求耗时、持续 TPOT（每输出 Token 时间）、热状态和恢复时间。只有更高瞬时功率被更短执行时间抵消，且没有更早触发温控或挤占其他部件预算时，“race to idle”才可能降低任务能量；不能只看峰值频率、平均电流或吞吐量。

使用外置功耗仪时，另记供电路径、采样频率和电池是否被旁路。拆机旁路电池的结果只适用于该供电与测量条件；它不能直接代表电池放电场景。
