# Battery Historian 与功耗报告

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q20 -->

**Q1: [learning] “WLAN rail 在 20 秒窗口增加 4.2 J”与“目标 UID 被 BatteryStats 归因 0.3 mAh”为什么不能放进同一列比较？**

因为它们是两种证据：前者是 ODPM（设备端电源轨监控）或 Power Stats HAL 的系统级硬件计量，rail 是给某类硬件供电的电源轨，能量单位 `uWs` 数值上等于微焦耳，测量的是整个设备子系统。后者是 BatteryStats 用功耗模型按 UID、组件和进程状态归因的估算结论，精度依赖 `power_profile.xml` 与活动时长统计。两类数据的单位、时间范围和系统消耗的分配规则都不同，报告必须写明用的是哪种。ODPM 是设备级测量，前台 App、后台进程、系统服务和硬件自身活动都可能贡献读数，不能自动归因给目标应用。Android Studio Hedgehog 起的 Power Profiler 展示 ODPM 数据，官方高精度 rail 范围是 Pixel 6 及后续 Pixel 设备（材料口径），没有 ODPM 的设备可能只有电池容量、电荷或电流读数。整机供电端的高精度结论则要靠外部电源分析仪，它同样不提供线程级归因。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q21 -->

**Q2: [learning] Battery Historian 时间线里的色块、top_app 和电池百分比分别代表什么？哪些解读是错的？**

Battery Historian 把 bugreport 中的 BatteryStats 历史渲染成时间线，色块只表示事件或状态正在活动（CPU running、userspace wakelock、JobScheduler、Mobile radio/Wi-Fi、GPS/sensor 等），无法读出该组件消耗了多少电量。常见误读有三类：把某一行持续变色当成耗电来源——能量结论还要结合归因统计、rail、线程活动或外部测量。把 `top_app` 当成“耗电最多的应用”——它只表示当时处于前台，提供场景上下文。

在短窗口读电池百分比——百分比来自 fuel gauge（电量计量芯片及估算算法）并取整，短测试里可能长时间不变或一次跳变，短时优化应比较 rail 能量或可重复的系统级指标，长时待机测试再把百分比斜率作辅助证据。时间重叠只能建立相关性：WLAN 活动与一次图片下载重叠只说明该操作值得进一步测量，不能排除同时刻其他进程也在使用 WLAN。WakeLock 行的判断边界是：bugreport 生成时仍被持有的锁值得检查，但录音、导航等任务可能合法持锁，要按持锁原因、生命周期和时长是否与用户可见任务一致判断。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q22 -->

**Q3: [learning] 采集一份可比较的 bugreport 给 Battery Historian，命令顺序和状态开关怎么安排？Historian 现在还能用吗？**

顺序是有状态的，按此执行（AAOS13 `BatteryStatsService` 核对选项存在）：`dumpsys batterystats --reset` 清空当前统计与 history（确认不需要保留现场后执行）。`--enable full-wake-history` 临时打开完整 userspace wakelock 时间线（占用历史缓冲区，只覆盖目标窗口）。断开 USB 让 BatteryStats 进入 on-battery 统计口径，执行固定测试场景。重新连接后 `adb bugreport bugreport.zip` 生成报告。

最后 `--disable full-wake-history` 恢复。直接读文本时，`--charged`、`--history`、`--usage` 与 `-c`/`--checkin` 输出粒度不同，且 `--checkin` 在材料按 Android 17 核对时还带“输出并清除旧统计”的语义，自动化脚本不能把 `-c` 与 `--checkin` 当作等价。Android 7.0 及以上使用 zip 格式 bugreport，包含账号、网络、日志等敏感信息，不应上传到不受信任的在线 Historian 服务。

维护状态：Google 的 battery-historian 仓库已于 2022 年 12 月归档转为只读（材料口径），官方文档仍给出 `stable:3.1` 容器命令但旧镜像可能拉取失败。打不开时直接用 `dumpsys batterystats` 文本、Perfetto `android.power` 或 Power Profiler。Historian 适合回看较长窗口的 wakelock、job、alarm、定位与网络活动，不适合短时函数级测量，也不能从时间线算出某段代码的毫瓦数。


<!-- 来源：05-observability/02-online-diagnostics.md · Q15 -->

**Q4: [learning] 功耗分析常把哪四类不同性质的数据混为一谈？Battery Historian 能回答其中哪个、不能回答哪个？**

四类数据各有不同的观察对象与推论边界：

1. **状态与事件时间线：**Batterystats 历史、system trace 与应用 Trace 回答某段时间发生了什么、哪些行为重叠；不能单独量化事件能量。
2. **本 UID 资源计数：**`SystemHealthManager.takeMyUidSnapshot()` 的 HealthStats 与应用埋点回答 CPU 时间、网络字节、WakeLock 与 Job 时长怎样变化；计数本身不是焦耳或 mAh。
3. **系统归因估算：**`BatteryUsageStats`、设置页与 bugreport 回答系统怎样把组件能耗估算到 UID；数值受设备功耗模型限制。AAOS13 走 `*PowerCalculator`，Android 17 才是 `PowerAttributor` / processor 管线。
4. **设备级能量：**ODPM 电源轨与外置功耗仪回答实验窗口内整机或子系统的能量差；共享轨不自动提供 UID 归因。

Battery Historian 只是把 bugreport 中的 Batterystats 等记录转成可交互时间线的离线工具（官方已标注不再积极维护），它属于第一类：能帮助回答“电量下降时屏幕、CPU、Job、网络、定位和 WakeLock 处于什么状态”，不能直接证明某个事件消耗了多少能量。能量与功率也要分开：能量是窗口内累计消耗，平均功率等于能量增量除以时间增量。电池百分比经过量化与系统估算，短窗口内没有下降不能推导为“零消耗”。

<!-- 来源：03-resource-domains/07-app-power-governance.md · Q2 -->

**Q5: [learning] 一轮可复现的 BatteryStats 采集怎么搭？Battery Historian、Power Profiler 与 Macrobenchmark PowerMetric 各适合什么问题？**

采集流程：记录构建指纹与电池状态 → `dumpsys batterystats --reset` 建立窗口起点（会清除统计，只在专用测试设备执行）→ 断开 USB 执行预定义场景（充电改变电池电流方向，部分设备 USB 链路还会阻止完整休眠）→ `adb bugreport` 与 `--charged`/`--checkin` 导出。需要逐锁明细时临时开 `--enable full-wake-history`，采集完关闭。工具分工：Battery Historian 适合回看长区间系统事件与 UID 统计，官方已声明不再活跃维护，图上的活动条不代表该组件的能量消耗。`dumpsys batterystats` 的文本与 checkin 数据适合按 UID 比较 CPU、网络、WakeLock 与传感器统计，读数前先确认统计区间与包名对应的 UID（共享 UID、多用户会让“一个包一个 UID”失效）。Power Profiler（Android Studio，Android 10+ 的 Pixel 6 及后续设备）把 ODPM 电源轨与系统追踪放同一条时间轴，是设备级数据、不按应用分摊。Macrobenchmark `PowerMetric` 适合把固定脚本纳入回归，接口仍是实验性、高精度追踪仅限部分 Pixel，用前先查 `deviceSupportsHighPrecisionTracking()`。跨设备只比趋势，不比绝对电流或绝对耗电。

<!-- 来源：03-resource-domains/06-energy-efficiency.md · Q17 -->

**Q6: [learning] 排查蓝牙功耗异常时，应用日志、`dumpsys bluetooth_manager`、BatteryStats 与 controller activity 各能回答什么？“BLE scan 归因时间很短”能证明 controller 没在工作吗？**

不能。BatteryStats 的 start/stop 区间覆盖整个逻辑扫描请求，而逻辑扫描可能因熄屏、位置关闭或系统 suspend 被暂停——材料按 Android 17 源码核对：`ScanController` 在 `ScanManager.startScan()` 之前就调用 `AppScanStats.recordScanStart()`，Bluetooth dump 能记录 suspended duration。所以“归因时间”不等于“controller 连续接收时间”。材料还指出 batch scan 的成本可能归到 Bluetooth 应用而非发起 UID：UID 时间偏短时要继续查 Bluetooth 组件与 controller activity，不能下“该应用没怎么扫”的结论。

三类证据分工：应用日志与 trace 回答业务何时 start/stop、用了什么 filter/mode、重试原因。`dumpsys bluetooth_manager`（`Ongoing scans`、`Last scans`、scanner UID、suspended、timeout、callback 或 PendingIntent 等字段）回答系统如何看待这个 scanner。BatteryStats 与 Battery Historian 回答 UID 归因、controller 时间与 wake lock/job/alarm 的时间线关系。整机电量变化要用 ODPM rail 或外接仪表。HCI snoop 提供 scan/连接参数与协议时序但包含敏感数据，离开受控环境前要脱敏。

估算边界：BatteryStats 的 Bluetooth mAh 依赖设备 `power_profile` 的 `bluetooth.controller.idle/rx/tx/voltage` 等值（AAOS13 `PowerProfile.java` 已核对存在这些键），属于估算而非实测。字段名随版本与 OEM 变化，缺少某个字段不代表活动不存在。
