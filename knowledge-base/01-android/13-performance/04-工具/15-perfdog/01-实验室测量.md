# PerfDog 实验室测量

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q42 -->

**Q1: [learning] 用 PerfDog 做功耗回归时为什么必须在 Wi-Fi 模式下拔掉 USB 采集 Battery Power？发现帧率退化后怎样组合证据判断是热降频？**

USB 连接线会给设备充电，USB 下采集的电流或功耗曲线混入充电电流，不能与断线后的结果放进同一组基线。PerfDog 官方把 Android Battery Power 明确定义在 Wi-Fi 模式下采集——先用 USB 建立 Wi-Fi 连接，成功后拔线再开始测试，报告必须写明连接方式。

Battery Power 是整机口径，屏幕、基带、Wi-Fi、后台进程和系统服务都包含在内，不是目标 App 的独占功耗，对比时要固定亮度、音量、网络和后台状态。`FPower` 的口径是 Power 除以 FPS、单位仍是 mW，只用于相近场景和帧率下的归一化比较。FPS 接近 0、场景静止或两组帧率差距很大时失去解释力，它也不是物理意义上的单帧能量。

热降频的判断需要同一时间轴上的证据组合：

1. 温度持续上升；
2. CPU/GPU 频率出现台阶式下降；
3. FTime P95/P99、1% Low 或 Stutter 同步恶化；
4. 相同操作标签处没有能单独解释退化的负载新峰值。

单个温度值不等于系统已经限频，报告应写起止温度与系统 thermal status 而不是只贴峰值。平台侧可用 `adb shell dumpsys thermalservice` 与 `dumpsys powerstats` 交叉核验“系统侧是否有数据、变化时间是否一致”（服务名 `thermalservice` 按 AAOS13 源码核对）。注意版本差异——AAOS13 的 `ThermalManagerService` 位于 `services/core/java/com/android/server/power/` 下并按 HIDL 2.0、1.1、1.0 顺序连接 HAL，材料按 Android 17 核对的实现移到 `power/thermal/` 子包且优先尝试 AIDL。精确能耗实验应使用外置功耗仪，PerfDog 更适合相对变化监控。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q40 -->

**Q2: [learning] PerfDog 不要求被测 App 接入 SDK 也不要求设备 root，为什么仍不能用它代替线上 APM 和系统 trace 做根因定位？**

PerfDog 是实验室观测工具：覆盖受控环境中的单台或一组设备，输出帧率、CPU、内存、温度、功耗等外部指标曲线。线上 APM 负责汇总真实用户、真实网络与设备分布下的长期数据。函数、线程或缓存策略级的根因仍要 Perfetto、Android Studio Profiler、simpleperf 或业务埋点提供证据，一条外部曲线证明不了内部实现。

它的适合场景由这个定位推出：QA 在固定设备和固定脚本上做发版回归、快速筛出异常时间段、拿不到源码时观察第三方 App 的外部表现、经 PerfDog Service 接入实验室自动化。字段可用性取决于平台、设备、SoC、驱动与客户端版本，开始前应先查当前设备的可用指标列表，字段为空时不能拿 0 代替“未采集”。GPU 利用率与 Counter 只在部分机型支持，同一设备同一驱动上的版本对比价值较高，跨 SoC 的 GPU 绝对值通常没有可比性。

判断规则：PerfDog 的结论停留在“哪个版本、设备或时间段的外部指标异常”。要回答“哪个函数造成异常”，就把异常区间交给 Perfetto 或源码 trace 继续定位。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q41 -->

**Q3: [learning] PerfDog 的 Jank 用固定的 24 FPS 门槛判定，为什么在 120 Hz 屏幕上不能把这套 Jank 计数直接当系统卡顿分类使用？**

PerfDog 的 SmallJank、Jank、BigJank 用“当前帧时长大于前三帧平均值 2 倍，且大于约 41.67、83.33 或 125 ms”的固定门槛，前两档来自 24 FPS 电影帧时长，不随设备 60、90 或 120 Hz 刷新率变化。Android Vitals、JankStats 与 Perfetto FrameTimeline 的 jank 分类与此不同，口径不能互相替换。

问题出在预算错位：120 Hz 每帧预算约 8.33 ms，一帧 30 ms 已经错过多个刷新周期，却没有达到 SmallJank 的 41.67 ms 门槛。反过来 60 Hz 下一帧 40 ms 只错过约 1.5 帧也可能落进同一档。因此高刷新率测试要同时保留 FTime 分布、P95/P99、1% Low 和连续低帧区间，而不是只比 Jank 次数。Smooth（稳帧指数）数值越低越稳定，官方经验值“游戏或视频小于 8、滑动类 App 小于 20”是产品建议区间而非平台标准，门禁值应先用自身机型与场景建立基线。

帧率数字还依赖窗口选择：一个包名可能同时存在 Activity 主窗口、SurfaceView、TextureView 和独立游戏渲染层，选错窗口测到的是系统 UI 或静止层。测试前确认包名、前台 Activity 与 PerfDog 选中的窗口或 Surface 名称。可用 PerfDog Service 的 `getAppWindowsMap` 或 `dumpsys SurfaceFlinger --list` 的人工核对兜底，layer 名称相似不能当归属证据。
