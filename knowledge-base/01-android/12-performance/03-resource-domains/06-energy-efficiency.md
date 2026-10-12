# Android 应用能效

**Q1: [learning] Android 上有哪几条端侧推理路径？为什么混用会误判成本？**

四条路径的接口与责任方不同：

1. **应用内 LiteRT：**应用打包 runtime 与模型，通过 `Interpreter` / delegate 或 `CompiledModel` 执行，CPU / GPU / NPU 由 delegate 决定。
2. **Play services LiteRT：**运行时由 Play services 更新，生命周期与模型交付不完全由应用包控制。
3. **AICore / ML Kit GenAI：**系统服务管理 Gemini Nano 与模型分发；AICore 包名不属于 AOSP。
4. **NNAPI：**由 NDK API、系统运行时与厂商驱动组成。层次混用会误判初始化成本、内存归属与失败处理——例如 `CompiledModel` 仍是应用内执行路径，不是 AICore 客户端。AICore 的可用性受设备、模型下载与配额影响，与 LiteRT delegate 的失败模式完全不同。

版本边界：NNAPI 自 Android 8.1 引入、API 35（Android 15）起官方标记弃用——Android 13 上 NNAPI 未弃用且是主线模块，“弃用”表示新项目不应再以它为长期入口，不表示既有应用失效。Android 17 的 `FEATURE_NEURAL_PROCESSING_UNIT` 与 NpuManager 调度控制面在 AAOS13 中不存在。无论哪条路径，推理都不应在主线程执行，但仍要防止工作线程挤占主线程与 RenderThread。

**Q2: [learning] 为什么“实验室里最快的推理后端”放进真实页面未必改善体验？**

用户感知的是从输入到 UI 更新的端到端延迟，运行时报告的 kernel 时间只覆盖一段。一次推理包含输入采集、解码缩放归一化、模型与运行时初始化、delegate 分区或编译、输入传输、加速器执行、输出读取与后处理、UI 更新。常见成本有首次准备（打开模型、分配张量、编译子图）、稳态执行、并发干扰、内存压力、热衰减与队列延迟。

两个典型反例：GPU 推理与渲染、相机管线共享资源，GPU 耗时下降不代表页面更流畅。推理线程占满高性能核后，单测模型耗时下降，页面帧时间与整机功耗却可能恶化——线程数要实测而非默认拉满。优化目标应写成可测预算（如“相机预览期间 P95 端到端延迟低于一帧、连续运行十分钟后温度等级可接受”），基准测试至少设 CPU 基线、目标 delegate 无缓存首次创建、缓存命中预热后、与真实 UI/相机并发四组对照，并记录 P50/P95/P99 与温度。

**Q3: [learning] 端侧推理的内存由哪些部分组成？“模型文件 100 MB 所以最多占 100 MB”错在哪？**

推理内存由多个所有者和生命周期不同的部分组成：

1. **模型映射：**FlatBuffer 与权重文件页，驻留量会随访问增长。
2. **权重转换：**delegate 打包、重排、量化展开或编译缓存，首次 prepare 后可能持续驻留。
3. **Tensor：**输入、输出、中间激活与 KV cache，随 shape 和 batch 变化。
4. **Workspace：**CPU scratch 或 GPU / NPU 临时缓冲，可能位于 native heap、驱动或 dma-buf。
5. **输入输出管线：**Bitmap、YUV / RGB 转换结果与纹理。
6. **服务进程：**AICore 等跨进程 runtime 的内存不在应用自身堆内。

“文件 100 MB”只描述压缩后的模型文件：映射驻留按页增长、delegate 可能生成编译产物或权重副本、运行时预分配 tensor arena 与工作区、多会话并发叠加，峰值可达数倍文件大小。观测要分阶段打点：模型打开前、runtime 创建后、首次执行后、稳定运行后、释放后与内存压力时。应用侧只记录到调用端分配，系统托管路径（AICore）的模型权重与服务侧工作不计入调用方 PSS——判断整机压力要同时看相关系统进程、dma-buf 与 `MemAvailable`。释放后 RSS 不立即下降，要先区分分配器留存、文件页缓存与仍被引用的 tensor，重复“创建—运行—释放—施压”周期确认是否有无上限增长。

**Q4: [learning] 端侧 LLM 推理的 TTFT 和 TPOT 应该怎么定义与测量，为什么平均 TPOT 不足以描述生成体验？**

TTFT 等于首个 token 到达时间减请求进入时间，覆盖分词、排队和冷启动，对应用户实际等待。TPOT 等于最后一个 token 与首个 token 的时间差除以输出 token 数减 1，只在输出多于 1 个 token 时有定义。TPOT 是整段 decode 的平均值，会掩盖个别 token 的停顿，工程报告还应给出相邻 token 延迟的 P50、P95、最大值与随时间变化的曲线。

测量口径的关键边界：要分析执行后端时应单独报告 prefill 区间，并把模型文件读取、权重映射、后端初始化、计算图编译与首轮 kernel 预热分开统计，不能把预热后的时间称为冷启动 TTFT。采用推测解码或一次提交多 token 的运行时，输出回调与硬件 kernel 不再一一对应，此时 token 到达间隔仍可描述用户体验，但不能据此推断每个 token 的硬件执行周期。能耗同理要定分母：`prefill_energy/input_token` 反映长输入处理成本，`decode_energy/output_token` 适合比较连续生成，`request_energy/output_token` 包含加载与应用开销，`joules/successful_request` 还能反映取消与失败造成的浪费。不同口径可以同时报告，不能把其中一个简称“模型能效”后横向比较。

**Q5: [learning] LLM decode 阶段 CPU 与 GPU 利用率都不高，TPOT 却很差，可能有哪些原因？**

decode 呈“CPU 短暂工作—提交—等待加速器—读取结果—采样”的交替过程，任一环节的等待都会拉长 token 间隔，而两侧的平均利用率都不高，所以单张利用率截图无法证明升频策略有问题。常见原因：

1. **串行依赖**：CPU 必须等加速器结果才能采样并发起下一轮，任一侧空闲可能表示在等待前一阶段。
2. **短时突发**：短 kernel 的利用率在采样窗口内被平均，频率尚未稳定升高工作已结束。
3. **跨设备交接**：CPU 提交慢让 GPU 暂时没工作，GPU 执行慢又让 CPU 休眠。
4. **带宽瓶颈**：计算单元升频后权重或 KV cache 受带宽限制，延迟改善很小。
5. **热与功率上限**：观测到的低频来自 thermal clamp 或功率预算，不是 governor 低估。
6. **后台竞争**：相机、显示、网络与系统服务共同消耗 CPU、带宽与热余量。

诊断应从 token 时间线开始，再关联 CPU 放置、频率、调度、内存与温度的同一时间轴。

**Q6: [learning] 普通应用在端侧 LLM 能效上能做什么、不能做什么？**

普通应用能改变工作负载并向系统描述时间目标：选择模型、量化格式、上下文长度、采样参数，选择运行时公开的 CPU/GPU/NPU 后端，调整线程数与请求队列，并在 API 支持时创建 ADPF hint session、查询 thermal status。不能指定硬件频率、修改 governor 或 thermal 配置，也不应直接调用 AIDL Power HAL——`IPower` 是系统与厂商实现之间的接口，Framework API 负责身份、权限与版本兼容。

这条边界决定了优化的优先级：频率策略只能在既定工作量上取舍，减少每个请求必须完成的计算与数据传输通常更稳定。量化收益要确认后端是否原生支持该位宽、不支持的算子是否回退 CPU。KV cache 容量随上下文增长，应记录运行时真实分配而非只按模型配置推算。内存允许时应复用模型映射、已编译图与长期 session，避免每次对话重复加载编译。root 实验机上的固频实验（如 FUSE 论文在 root Pixel 7 上把最低/最高频率设为同一值）会绕过平台功耗与温控约束，其结论不能写成 SDK 能力。

**Q7: [learning] 应用如何用 PerformanceHintManager 为 LLM decode 建立性能提示，Android 13 与 Android 17 的 API 差异是什么？**

通过 `getSystemService(PerformanceHintManager.class)` 为一组长期 worker 线程创建 hint session，表达“这些线程周期性完成一轮工作并希望在目标时间内结束”：每个周期调用 `reportActualWorkDuration()` 报告实际时长，目标变化时用 `updateTargetWorkDuration()` 更新。`createHintSession()` 可能返回 `null`，应用必须保留没有 session 时仍可正确执行的路径。

```java
PerformanceHintManager manager =
        context.getSystemService(PerformanceHintManager.class);
PerformanceHintManager.Session session =
        manager.createHintSession(workerTids, targetNanos);
if (session != null) {
    session.reportActualWorkDuration(actualDecodeNanos);
}
```

版本差异已按本地 AAOS13 源码（`frameworks/base/core/java/android/os/PerformanceHintManager.java`）核对：Android 13 提供 `createHintSession(tids, initialTargetWorkDurationNanos)`、`updateTargetWorkDuration(long)` 与 `reportActualWorkDuration(long)`，没有 `setThreads()` 与 `setPreferPowerEfficiency()` 等更新 API（Android 17 语境下可用于更新线程集合、表达能效偏好与按 CPU/GPU 分项上报）。target 线程 ID 必须来自真实执行线程，不能用 UI 线程代替。用法条件：session 覆盖长期 worker，不能每生成一个 token 就创建销毁。target 取产品可接受的 token 周期，不能长期填达不到的极小值。actual 的统计边界要固定，不能这轮只量 GPU、下一轮把采样与 UI 回调计入。prefill 若没有稳定分块周期，可为边界明确的 chunk 用独立 session，或只为 decode 建 session 并用 trace 单独评估 TTFT。

**Q8: [learning] 测 LLM 推理能耗时，整机电池电流与 power rails（部件能量轨道）各能说明什么？**

电池电流与电压代表整机功耗，屏幕、调制解调器与后台任务都计入结果，USB 充电会改变计数器含义，边充电的数据不能与放电测试比较。power rails 是按部件计量的能量轨道，依赖设备 `IPowerStats` 实现，很多量产设备不提供可用 rail，此时应报告“不可用”，不能用估算值填补。

按 `E_request = ∫ V(t) × I(t) dt` 积分前必须核对电流正负方向的定义。若要扣除空闲基线，应在相近时间用相同的屏幕、网络、温度与采样配置测量，并同时保留未扣除的整机能量——基线扣除会放大短请求的误差。每组配置需要多次重复并随机安排测试顺序，避免“高性能配置”都在设备未升温时运行而“节能配置”都在升温后运行。使用外置功耗仪时还要记录供电路径、采样频率、电池是否被旁路，拆机旁路电池的结论只适用于该实验条件。race to idle（尽快完成后空闲）只有在更高瞬时功率被更短执行时间抵消、且没有提前触发温控或占用其他部件预算时才有能效优势，判断依据是请求总能量、持续 TPOT、热状态与恢复时间，不能只看峰值频率。

**Q9: [learning] 传感器批处理（batching）到底省了哪部分电，为什么它不能减少采样？**

batching 省的是“缓冲与搬运 + AP 唤醒”两段成本：事件暂存在 sensor hub 的硬件 FIFO 中合并交付，减少应用处理器（AP）的唤醒次数。它不改变采样频率，传感器本体（MEMS、ADC、融合算法）的功耗仍在，所以“采样频率越低越省电”只覆盖传感器本体一部分成本。

传感器耗电来自三个位置：**采样与计算**（频率、传感器类型、融合复杂度，发生在 MEMS/hub/融合算法）。**缓冲与搬运**（FIFO 深度、事件大小、共享方式，发生在 hub SRAM、硬件 FIFO、HAL FMQ 与 SensorService 队列）。**AP 唤醒与处理**（wake-up 属性、批量窗口、回调工作量，发生在 SoC resume、SensorService 与应用线程）。关键边界：`batch()` 只是提交配置，真正的省电来自 hub/FIFO 在 AP 之外暂存事件。没有硬件 FIFO 或低功耗 hub 时，即使 `batch()` 返回成功，也可能无法减少 AP 唤醒。

**Q10: [learning] registerListener 的 samplingPeriodUs 与 maxReportLatencyUs 分别控制什么，请求 50 Hz 加 5 秒延迟会不会变成 0.2 Hz？**

`samplingPeriodUs` 控制采样频率（单位是微秒，20,000 µs 约为 50 Hz，只有增大它才降低频率）。`maxReportLatencyUs` 控制事件允许在 FIFO 暂存的最长交付延迟，正数表示允许批量交付，0 表示尽快上报。所以四参数重载请求"20,000 µs + 5,000,000 µs"仍以约 50 Hz 采样，只是允许每批最多等 5 秒交付，不会变成 0.2 Hz。应用回调可能一次收到多个事件，其 timestamp 早于回调时刻。

两个参数都有边界。采样周期是提示值：硬件只有离散的输出数据率（ODR）档位，HAL 会就近映射，应用不能假定回调严格等间隔。而且不同 reporting mode 下含义不同——continuous 是期望采样周期，on-change 是最快事件间隔，one-shot 直接忽略（应使用 `requestTriggerSensor()` 而非 `registerListener()`）。`maxReportLatencyUs` 不降低采样频率，也不保证事件等到窗口结束：FIFO 已满、其他 sensor 到期、应用调用 `flush()` 或 AP 因其他原因醒来都会提前交付。理论批量时长上限可用“可用 FIFO 事件数除以采样频率”估算，`getFifoMaxEventCount() == 0` 表示不支持硬件 batching，共享 FIFO 的实际容量介于 `getFifoReservedEventCount()` 与 max 之间——50 Hz sensor 若只有 100 个槽位，请求 5 秒延迟也可能在约 2 秒后提前上报。

**Q11: [learning] 同一个传感器被多个应用以不同参数请求时，硬件最终采用谁的配置？**

SensorService 的 `SensorDevice` 为每个连接保存 `BatchParams`，由 `Info::selectBatchParams()` 聚合出硬件参数：忽略已标记 disabled 的连接，采样周期取所有有效连接的最小值（满足最快请求），每个连接的有效批量周期不短于其自身采样周期，聚合批量周期取这些值的最小值。若聚合批量周期不大于聚合采样周期则置 0，明确要求实时流式交付。本地 AAOS13 源码（`frameworks/native/services/sensorservice/SensorDevice.cpp`）核对，该聚合与置 0 逻辑在 Android 13 已存在。

推论：一个请求 200 Hz 且 `maxReportLatencyUs = 0` 的客户端，会使同一 handle 的硬件采用高频实时交付配置，另一个 10 Hz、10 秒 batching 的应用无法用自己的参数覆盖它。排查时用 `adb shell dumpsys sensorservice` 看 selected 值——它才是聚合后实际传给硬件的参数：selected batching period 为 0 时先检查其他客户端的请求聚合。selected 较大但事件仍提前到达，再查 FIFO 容量、共享 FIFO、flush 与 AP resume。

**Q12: [learning] 灭屏后 non-wake-up 传感器事件缺失，是 HAL 丢包吗？wake-up 传感器又是怎么工作的？**

不一定是丢包。non-wake-up sensor 不能唤醒 AP：AP suspend 期间事件继续写入 non-wake-up FIFO，FIFO 满后按环形缓冲覆盖较老的 continuous 事件，没有 FIFO 则事件直接丢失，`maxReportLatency` 不会为它唤醒 AP，AP 因其他原因醒来后才交付仍保留的事件。灭屏测试出现事件缺口可能符合 suspend 规则，不能直接判定 HAL 丢数据包。

wake-up sensor 允许 AP suspend，但必须在事件达到最大上报延迟、wake-up FIFO 即将满或 one-shot wake-up 触发时唤醒 AP，正数 `maxReportLatencyUs` 可让多个事件共用一次 AP resume。设备从 suspend 恢复时会尽量交付 FIFO 中全部内容，减少刚回 suspend 又被唤醒的概率。两个关键边界：相同传感器类型可同时存在 wake-up 与 non-wake-up 两个独立实例，必须用 `Sensor.isWakeUpSensor()` 判断当前实例，不能按 `TYPE_ACCELEROMETER` 等类型名推断。on-change sensor（如 step counter）有“最新事件保存在共享 FIFO 之外、不被 continuous 事件覆盖”的特殊保证，累计值语义依赖它。业务若要求灭屏期间每个事件都不丢，只能持 partial wake lock 保活或改用 wake-up sensor，两者都会显著改变功耗模型，通常应先确认业务是否只需要最新状态或累计结果。

**Q13: [learning] Android 12 起的高采样率限制是什么，为什么声明了 HIGH_SAMPLING_RATE_SENSORS 还可能被限？**

目标版本为 Android 12（API 31）及以上的应用访问六类运动/姿态传感器（加速度计、未校准加速度计、陀螺仪、未校准陀螺仪、磁场、未校准磁场）时，普通 listener 默认最多约 200 Hz，Sensor Direct Channel 默认最高 `RATE_NORMAL`（通常约 50 Hz）。需要更高频率要在 manifest 声明 `android.permission.HIGH_SAMPLING_RATE_SENSORS`。声明了权限也可能仍被限制：用户关闭麦克风访问权限时，这六类传感器即使已有高采样权限也会受限，因为高频运动/姿态数据可能泄露音频相关信息。

```xml
<uses-permission
    android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
```

本地 AAOS13 源码核对：`SensorService.h` 将普通 listener 的受限周期设为 5 ms（`SENSOR_SERVICE_CAPPED_SAMPLING_PERIOD_NS`，对应 200 Hz），`SensorService.cpp` 检查 `HIGH_SAMPLING_RATE_SENSORS` 权限，`SensorDevice.h` 中存在麦克风开关变化时更新连接 BatchParams 的路径——该限制与麦克风隐私联动在 Android 13 已落地，不是 Android 17 新增。公开 SDK 文档提示高频请求可能抛 `SecurityException`，而源码还有兼容模式与不抛异常、直接限制采样率的分支，应用不应依赖某一种失败表现，应声明权限、检查注册结果并用实际事件时间戳验证最终采样率。

**Q14: [learning] BLE 扫描的能耗由哪些参数决定？ScanFilter 与 setReportDelay() 分别省的是什么，SCAN_MODE_LOW_LATENCY 能长期开着吗？**

一次扫描的能耗分三段：

1. **Controller：**扫描窗口占用接收机；active scan 还要发送 scan request 并等待 response。
2. **Bluetooth 进程：**执行过滤、组装结果与统计。
3. **应用进程：**处理 Binder 回调与业务逻辑。scan mode 控制接收占空比——材料按 Android 17 源码转写的 AOSP 默认参数：`LOW_POWER` 约 10%、`BALANCED` 约 25%、`LOW_LATENCY` 100%（100 ms 窗口/100 ms 间隔）。这些毫秒数不是 SDK 公开保证，`Settings.Global`、DeviceConfig 与 OEM 都可能改变运行参数，应用只应依赖 mode 的语义。`LOW_LATENCY` 的 100% 占空比只适合用户正在等待配对的短窗口，长期开启等于让接收机持续工作。

`ScanFilter` 的收益主要在结果侧：匹配在 controller 支持硬件 offload 时下沉，减少无关结果上送、Binder 回调与应用进程工作。它不会自动缩短 controller 的扫描窗口——想降低射频接收时间要缩短时长或换更低 duty cycle 的 mode。`setReportDelay()` 批量上送主要减少 host 与应用唤醒，硬件批量存储能力取决于设备。Android 14 新增 `CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH`（熄屏批量投递、亮屏恢复逐条，最小 10 分钟），适合容忍延迟的观察任务。蓝牙模块源码（le_scan 包）不在本地 AAOS13 树，以上参数按材料 Android 17 语境转写。

**Q15: [learning] 进程可能被回收时仍要发现 BLE 设备，应该用什么方案？周期用 Job/Alarm 启动扫描、连续 start/stop 为什么都被系统限制？**

后台 presence 的正确入口是 `PendingIntent` 扫描（系统代应用投递结果。Intent 用 mutable flag 供系统填充 extras、用显式 Receiver 限制投递目标）或 Companion Device Manager 的 presence API。两者都不给应用无限后台执行权，Android 12+ 的后台 FGS 启动限制仍然生效。用 Job/Alarm 周期启动扫描模拟“设备出现”是反模式：外围设备不在附近时每个周期仍唤醒进程，还会撞上注册频率限制——材料按 Android 17 核对，AOSP 默认保留最近 5 次已结束扫描，最早一次仍在 30 秒窗口内时，后续非特权注册收到 `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`（默认值可由 DeviceConfig 调整，非公开保证）。注册失败后应停止循环，等待业务事件或退避计时器。

系统还有两层被动保护（按材料 Android 17 核对）：熄屏且无过滤、或位置关闭且未声明不用于位置的扫描会被暂停（`requiresScreenOn()`/`requiresLocationOn()`，位置检查与 `BLUETOOTH_SCAN` 运行时授权是两层独立检查），亮屏或位置恢复后继续。长时扫描默认 10 分钟降级（无 filter 转 opportunistic、有 filter 压到不高于 `LOW_POWER`），Android 17 另有按 UID 的扫描额度限速（默认每 1 小时补充 6 分钟额度）。这些保护不能反向利用——业务离开扫描场景仍要显式 stop。权限侧注意：`BLUETOOTH_SCAN` 的 `neverForLocation` 是数据用途声明，不改变扫描占空比，不是省电参数。

**Q16: [learning] GATT 连接已经建立，为什么蓝牙功耗仍然很高？连接优先级、MTU、notification 与轮询应该怎么组合？**

“已连接”不等于低功耗：连接间隔很短、notification 很密、链路质量差导致重传、应用高频轮询 characteristic 或持续发小包，都会让 controller 频繁进入 active。控制要点按业务阶段切换：批量传输窗口用 `requestConnectionPriority(HIGH)`，传输完成恢复 `BALANCED`——返回 true 只表示请求已提交，最终参数由 controller 与外围设备决定。周期状态更新启用 characteristic notification，由外围设备在数据变化时推送，优于主机固定周期 `readCharacteristic()`。MTU 减少分片（材料按 Android 14 核对，首个 MTU 请求固定请求 517。API 37 的 `setAutomaticMtuEnabled(true)` 自动协商），最终结果仍要在 `onMtuChanged()` 里确认并按协商值分片。通知频率由配件固件控制——配件每几十毫秒发送一次无变化数据时，手机侧无法单独消除这部分成本。

生命周期与重连：不再使用的 `BluetoothGatt` 要 `disconnect()` 加 `close()`。重连要有带 jitter 的指数退避、重试上限与取消条件（蓝牙关闭、权限撤销、用户解绑、业务完成），避免多台设备同时重连形成风暴。`autoConnect=true` 适合已知设备的长期在场检测，但不保证进程存活。API 37 新增 `BluetoothGattConnectionSettings` 统一 transport、autoConnect、opportunistic 等参数（AAOS13 无此 API，按材料版本标注），其中 opportunistic client 不维持底层连接、只适合复用现有连接的观察者。

**Q17: [learning] 排查蓝牙功耗异常时，应用日志、dumpsys bluetooth_manager、BatteryStats 与 controller activity 各能回答什么？“BLE scan 归因时间很短”能证明 controller 没在工作吗？**

不能。BatteryStats 的 start/stop 区间覆盖整个逻辑扫描请求，而逻辑扫描可能因熄屏、位置关闭或系统 suspend 被暂停——材料按 Android 17 源码核对：`ScanController` 在 `ScanManager.startScan()` 之前就调用 `AppScanStats.recordScanStart()`，Bluetooth dump 能记录 suspended duration。所以“归因时间”不等于“controller 连续接收时间”。材料还指出 batch scan 的成本可能归到 Bluetooth 应用而非发起 UID：UID 时间偏短时要继续查 Bluetooth 组件与 controller activity，不能下“该应用没怎么扫”的结论。

三类证据分工：应用日志与 trace 回答业务何时 start/stop、用了什么 filter/mode、重试原因。`dumpsys bluetooth_manager`（`Ongoing scans`、`Last scans`、scanner UID、suspended、timeout、callback 或 PendingIntent 等字段）回答系统如何看待这个 scanner。BatteryStats 与 Battery Historian 回答 UID 归因、controller 时间与 wake lock/job/alarm 的时间线关系。整机电量变化要用 ODPM rail 或外接仪表。HCI snoop 提供 scan/连接参数与协议时序但包含敏感数据，离开受控环境前要脱敏。

估算边界：BatteryStats 的 Bluetooth mAh 依赖设备 `power_profile` 的 `bluetooth.controller.idle/rx/tx/voltage` 等值（AAOS13 `PowerProfile.java` 已核对存在这些键），属于估算而非实测。字段名随版本与 OEM 变化，缺少某个字段不代表活动不存在。
