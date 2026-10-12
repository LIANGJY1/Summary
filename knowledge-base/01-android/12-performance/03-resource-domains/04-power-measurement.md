# Android 功耗测量与归因

**Q1: [learning] BatteryStats 的活动时长、PowerStats HAL 的能量读数（µWs）与设置页的应用耗电百分比，为什么不能直接换算和横向比较？**

三者是不同层次的数据：BatteryStats 记录 UID 与组件的活动时间、计数与状态（`BatteryStatsImpl` 维护，`BatteryStatsService` 运行在 system_server）。PowerStats HAL/ODPM 返回某条 rail 或 energy consumer 自开机累计的能量，单位 µWs。设置页百分比是 Framework 归因计算后按查询窗口生成的结果，单位是 mAh 或百分比。能量（µWs）与电荷量（µAh/mAh）之间的换算还必须引入电压，忽略单位直接比较会得出错误结论。

数据从硬件进入 Framework 后不保证每层完整：设备没有对应 energy consumer 时，Framework 回退到 controller activity 或 `power_profile.xml` 估算。共享 rail 无法区分 UID 时，还要按活动时间二次分配。因此 rail 与电池端读数是测量（带设备实现差异），UID 归因是“测量加模型加分配”的混合结果，误差来源不同。

做法：报告耗电结论前先声明口径（活动时长、charge、energy、mAh、百分比），比较只发生在同一口径内。`BATTERY_PROPERTY_CHARGE_COUNTER`（µAh）与 `BATTERY_PROPERTY_CURRENT_NOW`（µA）等属性的精度和符号方向由设备实现决定，不给库仑计设通用误差范围。

**Q2: [learning] power_profile.xml 在功耗估算里承担什么角色？为什么用 AOSP 默认值算真机耗电没有意义，PMIC rail 的电流能直接填进去吗？**

`power_profile.xml` 是软件估算用的设备参数表：记录组件在不同状态下折算到电池侧的平均电流（单位 mA、按标称电压测量），Framework 用 `charge(mAh) = 电流(mA) × 时长(ms) / 3,600,000` 把活动时长换算成电荷量。AAOS13 源码核对（`frameworks/base/core/res/res/xml/power_profile.xml`）：文件注释明确写着 "The default values are deliberately incorrect values"，OEM 出货前必须实测覆盖——默认 `screen.on.display0` 是 0.1，任何用默认值算出的真机报告都没有设备测量意义。

不能把 rail 电流直接填进 profile：rail 位于不同电压域，相同电流在不同电压下对应的功率不同，profile 要的是折算到电池侧的电流。CPU 参数按 cluster 组织（`cpu.active`、`cpu.clusters.cores`、频点数组），modem、Wi-Fi 与 Bluetooth 还保留 controller 项——AAOS13 的 `PowerProfile.java` 已含 `bluetooth.controller.idle/rx/tx/voltage` 这些键。使用哪组数据取决于芯片、HAL 与 Framework 采集器能力，不能简化成“开启时间乘一个固定 mA”。

**Q3: [learning] Android 13 与 Android 17 把组件耗电归因到 UID 的管线有什么不同？设备提供 screen energy consumer 时，屏幕耗电怎么分给应用？**

Android 13 按本地源码核对：`BatteryUsageStatsProvider`（`frameworks/base/core/java/com/android/internal/os/`）实例化一组 `*PowerCalculator`（`CpuPowerCalculator`、`ScreenPowerCalculator`、`WifiPowerCalculator` 等都在 `com.android.internal.os` 下），把 `BatteryStatsImpl` 的活动统计与 profile 参数计算成 `BatteryUsageStats`。材料按 Android 17 核对的路径是 `PowerAttributor → MultiStatePowerAttributor → processor/*PowerStatsProcessor`，且 Android 16 起 provider 移除了顶层 calculator 回退——这条管线在 AAOS13 树中不存在，读 Android 13 源码应从 calculator 进入，不要把 Android 17 的 processor 结构当成当前源码。

屏幕 UID 分配：处理器先算设备级屏幕耗电（有 screen energy consumer 用累计能量，否则用 screen-on、doze、亮度档位时长与 profile），再按各 UID 的 top activity 时长占比分配。硬件 energy consumer 只提升屏幕“总量”的测量质量，不能自动得到每个应用的屏幕电量——UID 结果依旧来自前台活动时长比例，不是硬件直接识别每个应用。

版本边界：AAOS13 源码核对，`ScreenPowerCalculator` 仍有 10 分钟 smear 门槛（`MIN_ACTIVE_TIME_FOR_SMEARING = 10 分钟`，总 top activity 时长不足时把屏幕耗电按时长摊分）。材料按 Android 17 核对，processor 在总 top activity duration 为 0 时直接跳过 UID 分配、移除了该 smear 门槛。引用屏幕归因行为时必须注明年份对应的实现。

**Q4: [learning] 设置页里“某应用耗电占比 20%”能当作应用的精确耗电量吗？它与库仑计、燃料计的读数是什么关系，统计什么时候会自动重置？**

不能当作精确测量。设置页使用 Framework 归因结果，受查询窗口与统计 reset 边界、measured energy 是否可用、profile 与 controller 参数质量、UID 前后台状态、共享资源分配以及 Settings 自身分组展示策略影响。所有 UID 百分比相加也不一定覆盖电池端观测到的全部消耗。它适合排序和发现异常组件，不具备实验室仪表的绝对精度。

库仑计与燃料计面向整块电池：Coulomb Counter 对充放电电流做时间积分得到电荷变化，Fuel Gauge 结合库仑计量、电压、温度与电池模型估计 SOC——两者都不能识别某次电流属于哪个 UID。Framework 不会把电池总差值按应用比例切分，因为总差值还包含系统、屏幕、基带与供电转换损耗。两类结果可以校对总趋势，不能互相替代：回答“某 UID 被归因多少 mAh”用 BatteryUsageStats，回答“测试窗口电池少了多少 charge”用燃料计或外接功耗仪。

重置行为：统计会持久化到 `/data/system/batterystats.bin`（实现细节、非公开接口），且会自动 reset——AAOS13 源码核对，满电触发 `resetAllStatsLocked(RESET_REASON_FULL_CHARGE)`。实验前 `dumpsys batterystats --reset` 是人为建窗口（`RESET_REASON_ADB_COMMAND`），重置影响整机统计，只在专用测试设备执行。材料按 Android 17 核对的拔充电器条件 reset（`shouldResetOnUnplugLocked()`，高电量阈值默认 90、可由 `BatteryStatsConfig` 配置）在 A13 树中不存在，属版本差异。

**Q5: [learning] WakeLock 的 tag 为什么必须稳定且不能包含用户信息？用 WorkSource 把锁记到别的 UID 名下可行吗？**

tag 是 BatteryStats 与 Android vitals 聚合的键：稳定 tag（硬编码的包名、类名或操作名）让同一持锁点可聚合、可定位。把请求 ID、时间戳、递增序号或用户 ID 放进 tag 会造成高基数——同一持锁点分散成大量统计项，线上数据无法聚合。系统发现 tag 可能包含个人信息时，诊断工具只显示 `_UNKNOWN`。业务请求 ID 应留在应用日志中，通过时间戳与锁统计关联。

WorkSource 表示“这份工作替哪个 UID 执行”，常见于系统服务或中间层。材料按 Android 17 核对，`PowerManagerService.BinderService.acquireWakeLock()` 会在非空 WorkSource 上校验 `UPDATE_DEVICE_STATS` 权限——普通应用不能借此改写自身归因，也不应把成本转给其他 UID。归因还要注意：某个系统组件持有锁不代表成本一定计在它名下，排查时要结合 WorkSource、UID 与 tag 三条线索。

**Q6: [learning] 客户端 isHeld() 返回 true 的 WakeLock，为什么在系统侧可能根本不生效？WakeLockStateListener 观察到的又是什么？**

`isHeld()` 只说明客户端尚未 release。服务端是否“尊重”这把锁由 PowerManagerService 决定。AAOS13 源码核对（`setWakeLockDisabledStateLocked()`），对应用 UID 的 PARTIAL_WAKE_LOCK 满足任一条件即被禁用：owner UID 处于 cached 状态（`NO_CACHED_WAKE_LOCKS` 开启时按进程状态判断）、设备处于 Doze 且 UID 不在静态或临时豁免名单、Low Power Standby 生效且 UID 不在 LPS allowlist、force-suspend 激活。材料按 Android 17 核对的版本还检查 owner 进程 frozen 状态与 power group 强制禁用——A13 该方法没有 frozen 检查，属版本差异。

`WakeLockStateListener`（API 33 引入，AAOS13 `PowerManager.java` 已含 `setStateListener()`）回调的 `enabled=false` 表示 Framework 服务因上述电源策略暂时忽略这把锁。策略重新允许后，仍处于 held 状态的锁可能再次生效。因此监听状态不能替代 release：客户端仍要保证成功、失败、取消、超时和组件销毁都进入同一条释放路径，引用计数锁还要由单一 owner 管理 acquire/release 配对。

**Q7: [learning] BatteryStats 里能看到几十个应用 wakelock tag，内核 /sys/kernel/debug/wakeup_sources 里却对不上号——应用 tag、suspend blocker 与内核 wakeup_source 三层为什么不一一对应？**

因为每层记录的对象不同。应用每个 tag 的锁保留在 Framework 的 UID/tag 统计（BatteryStats）里。PowerManagerService 只判断是否存在需要 CPU 的有效锁（AAOS13 源码核对：`updateSuspendBlockerLocked()` 检查 `mWakeLockSummary & WAKE_LOCK_CPU`），有需要才持有名为 `PowerManagerService.WakeLocks` 的一个 suspend blocker（blocker 名在 A13 源码 `PowerManagerService.java` 中核对）——多个应用的锁在这一段已经汇总。内核侧还有 alarmtimer、输入、USB、蓝牙、modem 等驱动自己注册的 wakeup source，与应用 tag 没有命名对应关系。

SystemSuspend 的协调流程（Android 10 起取代 libsuspend。源码不在 AAOS13 树，按材料转写）：suspend 线程读取 `/sys/power/wakeup_count`，等待用户态 suspend counter 归零，把先前读到的值写回并写 `mem` 到 `/sys/power/state`。写回失败说明读取后出现了新 wakeup 事件，本轮放弃。

诊断分工：`dumpsys suspend_control_internal --wakelocks/--wakeups` 回答“用户态 blocker 与唤醒统计”。debugfs `wakeup_sources` 的 `active_count`、`total_time`、`max_time`、`prevent_suspend_time` 等字段回答“哪个内核 source 活跃”——量产 user build 通常不允许 `adb root` 也读不到 debugfs，且快照之间要比增量而不是绝对累计值。看到某个 kernel source 活跃，不能按名字去找同名 App tag。

**Q8: [learning] 应用没有调用过 newWakeLock()，Android vitals 里为什么仍会出现归因到它的锁？excessive partial wake lock 指标怎么算、超了会怎样？**

JobScheduler、WorkManager、AlarmManager、FCM、Location 与音频栈都在各自的执行窗口内代持 WakeLock 并归因到发起应用：Job 执行期由调度框架代持、Alarm 投递到 `onReceive()` 完成期间持锁、位置栈在采集与投递期持锁、FCM 在消息投递期间短时持有。所以排查陌生锁名时不能只搜项目里的 `newWakeLock()`，要按锁名与时间回查是哪条 API 链路。WorkManager 管理锁的生命周期也不代表 Worker 可以无限执行——Job 的 stop reason 仍要记录。

excessive partial wake lock 口径（按材料与官方文档转写）：所有非豁免 partial wake lock 在应用后台或运行 FGS 期间的持有时间合计，24 小时内达到或超过 2 小时的应用会话被计入。audio、location 与 JobScheduler user-initiated API 创建的锁豁免。若 28 天内超过 5% 的会话命中，可能影响 Google Play 的可见性（材料记录 2026 年 3 月起逐步执行）。

边界：2 小时与 5% 是质量指标口径，不是系统 API 配额，也不是“每用户可持锁 5% 的时间”。修复要回到 Play Console 的锁名、affected sessions 与 P90/P99 时长定位具体业务，再看持锁区间内的 CPU、网络、定位活动与 suspend 成功次数，不能只看持锁总时长。

**Q9: [learning] “亮度 50%”为什么不是跨设备可比的物理量？比较屏幕耗电时有哪几种口径，Android 13 与 17 的显示归因有什么差别？**

应用层亮度是归一化值：系统经 `BrightnessMappingStrategy`（AAOS13 源码核对存在）用亮度—nit 曲线、环境照度曲线与校准数据换算成面板亮度，同一滑块 50% 在两台设备可能对应不同 nit 或不同非线性映射。HBM、HDR、相机或阳光可读模式还可能被触发。跨设备实验要同时记录滑块值、自动亮度状态、环境照度、可读的 nit 与面板类型，只写“亮度 50%”不可比。

口径有四种，回答的问题不同：外接电源分析仪测整机输入侧能量。设备 power rail/ODPM 测某条供电链路的能量。燃料计或电池电量变化看长窗口整机趋势。BatteryStats 归因回答“谁在何时使用了哪些组件”。归因的版本差异：AAOS13 核对，`ScreenPowerCalculator` 用 profile 参数估算（`power_profile.xml` 已按显示区分 `screen.on.display0`、`screen.full.display0` 等键）。材料按 Android 17 核对，`ScreenPowerStatsCollector` 优先收集 DISPLAY energy consumer 的累计能量，没有时才回退 `PowerProfile`。UID 部分两种实现都来自 top activity 时长占比，不能证明某个 View、某帧 GPU 工作或某个颜色像素消耗了对应能量。

论文数字的边界：材料引用的实验（亮度 0%→100% 汇总 +86.5% 等）来自单台 Galaxy S23 Ultra 的短场景与 BatteryStats 口径，只能说明方向与实验设计方法，不能外推为收益预期。面板类型决定内容与耗电的关系：LCD 背光是主成本、页面颜色影响有限。OLED/AMOLED 像素自发光，亮度与内容共同决定成本，短测跨过热阈值还会把帧率变化混进显示功耗。

**Q10: [learning] 为什么“深色模式省电 X%”与“省电模式提升续航 Y%”不能直接写进产品文案？这两类结论各依赖什么条件？**

因为两者的收益都由设备与内容条件决定。深色模式：收益只对像素自发光的 OLED/AMOLED 且界面颜色占比大的页面明显——图片、视频、地图等媒体像素不随主题变化。LCD 的背光持续工作，页面配色难以形成同等级收益。用户在深色界面上提高亮度还会抵消像素收益。材料引用的单机实验（同一 AMOLED 设备上 Instagram -2.4%、全部场景汇总 -1.4%）只说明该设备该脚本下的方向。文案应写成“在部分 OLED 设备和深色内容页面上可能降低显示功耗”，并把视觉舒适与低光使用作为独立收益。

省电模式：它是策略输入而非固定参数包——AAOS13 源码核对，`PowerManagerService` 发布 low-power 状态，`BatterySaverController` 与 `BatterySaverPolicy` 计算各服务策略。它会限制后台、位置、网络、动画、刷新率或处理器策略，但具体 CPU 上限与刷新率上限不属于统一的应用层保证，OEM 还能叠加策略。材料引用的实验（该设备汇总 -9.1%、YouTube -14%、WhatsApp 无同方向）同样是设备与脚本共同的结果。应用应读 `PowerManager.isPowerSaveMode()`、监听 `ACTION_POWER_SAVE_MODE_CHANGED` 并降低自身工作量。测试报告记录它带来的可观察变化（刷新率范围、任务推迟、网络与频率变化），不要假设固定幅度，也不要直接读写 `Settings.Global.LOW_POWER_MODE` 当控制方案。

**Q11: [learning] 设计一次可信的用户设置类功耗实验（对比刷新率、深色模式等），变量应该怎么安排？统计与失败样本要注意什么？**

实验按三个阶段进行：

1. **固定基线：**固定设备、亮度、刷新率范围、主题、网络、内容、温度区间与采样窗口，用于比较代码版本。
2. **单变量扫描：**每次只改一项设置，并验证其他生效值没有联动变化；例如省电模式可能同时改变刷新率、后台任务与处理器策略。
3. **用户场景复测：**恢复自动亮度、ARR、动态内容与常用网络，检查结论方向是否仍存在，以评估实际体验。

每个变量都记录请求/配置值与生效值及证据：亮度记滑块、自动亮度与 nit/HBM；刷新率记 VSync 间隔与显示模式；网络记信号、重试与缓存；热状态记起始温度与 severity。

统计边界：功耗数据常呈偏态分布且有长尾，报告要给出重复次数、中位数、离散程度与预先定义的异常值规则。`p < 0.05`（如 Mann-Whitney U 检验）只说明当前样本存在统计证据，不等于差异有产品价值。删除异常样本必须有预先定义的规则并保留失败原因——脚本超时、广告出现、网络失败、温度越界是场景质量问题，不能只因数值偏大就移除。

采集与脱敏：`dumpsys display`、`dumpsys SurfaceFlinger`、`dumpsys batterystats --charged` 与 bugreport 适合保存配置快照和诊断上下文，但不能替代连续时间线。Perfetto 可同时观察 FrameTimeline、VSync、调度、频率与设备开放的 power rail。命令输出包含应用、账号、设备标识与网络信息，分享前要脱敏。
