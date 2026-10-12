# Benchmark、Macrobenchmark 与性能 CI

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q31 -->

**Q1: [learning] Macrobenchmark 的 StartupMode 和 CompilationMode 分别控制什么？哪些边界最容易误读？**

`StartupMode` 控制测量前的进程与 Activity 状态：

1. **COLD：**测量前终止目标进程，但不清 app data 或业务缓存，也不模拟全新安装。
2. **WARM：**保留进程，重建或启动 Activity。
3. **HOT：**保留进程和 Activity，再恢复到前台。

`setupBlock` 在每轮测量前运行；COLD 会在 setup 与 measure 之间终止进程，不能把必须留在目标进程内存中的准备工作放在 setup 中。

`CompilationMode`（AndroidX Benchmark 1.4.1 口径）控制测量前的 ART 编译状态：

1. **DEFAULT：**API 24+ 等价于 `Partial(UseIfAvailable)`；Profile 或 ProfileInstaller 缺失时记录而不报错。API 23 使用 Full。
2. **Partial(Require, 0)：**要求 APK 包含 Baseline Profile 与 ProfileInstaller，且由 AGP 7.0+ 正确打包 Profile；条件不满足时报错，适合 Profile 回归门禁。无参 `Partial()` 的默认参数是 Require 与 0。
3. **Partial(Disable, N)：**先运行 N 次工作负载收集运行时 profile，再进行部分编译；N 必须大于 0。
4. **None()：**清除 Profile 和预编译状态，测无预编译路径；不清应用数据。
5. **Full()：**全量 AOT，代码体积变大时可能慢于 Partial，不代表性能上限。
6. **Ignore()：**不重置或改变编译状态，只用于外部已精确控制 ART 状态的实验。

“预热”还对应三种不同操作：

1. **Microbenchmark warmup：**让 JIT 与缓存达到稳定判定，不纳入正式统计。
2. **Partial.warmupIterations：**运行工作负载收集 ART profile 后编译，会改变目标应用的测试条件。
3. **持续负载预热：**让温度与频率进入稳态，数据应单独保存。

编译模式的默认值及失败条件按 AndroidX Benchmark 官方 `CompilationMode`、`CompilationMode.Partial`、`BaselineProfileMode` API 参考核对；固定库版本后再复现实验。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q23 -->

**Q2: [learning] Macrobenchmark 与 Microbenchmark 的进程边界和编译控制有什么不同？“Microbenchmark 数字更稳定”能替代 Macrobenchmark 吗？**

不能互相替代，差别在进程边界与测量对象：Macrobenchmark 的测试 APK 运行在目标应用进程之外，驱动启动、滚动、动画等完整用户场景，要求独立的 `com.android.test` 模块、目标 APK 使用接近 release 的非 debuggable 构建并在 manifest 声明 `<profileable>`。Microbenchmark 在基准测试进程内循环调用被测代码，循环会形成预热缓存与稳定代码路径，结果接近热点代码的最佳情况。分工是：启动、进程创建、系统服务与完整渲染必须由 Macrobenchmark 加系统 trace 判断。

JSON 解析、布局 inflate、列表 item 绑定等可隔离热点用 Microbenchmark，并用 `BlackHole.consume()` 防止编译器把没有外部用途的计算整段删除（材料口径：Benchmark 1.3.0-beta01+ 配合 AGP 8.4.0+ 默认对 Microbenchmark APK 做全量 AOT）。典型证据路径是：Macrobenchmark 发现回归并保留 trace，Perfetto 定位到可隔离函数，Microbenchmark 比较候选实现，再回到 Macrobenchmark 确认端到端改善。

迭代次数没有统一的"10"：先在固定设备上观察分布与热状态，再选择能稳定区分目标回归幅度的数值。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q24 -->

**Q3: [learning] Microbenchmark 的测量协议怎样避免循环、准备工作和编译状态污染结果？**

Microbenchmark 应由 Benchmark 库控制重复执行，循环体只包含被测工作及防止结果消除所需的消费操作。

三种写法测的都不是目标代码：手工循环把被测循环与 `BenchmarkRule` 自己的 warmup 和测量循环叠在一起，样本混合了多套循环节奏。循环内打日志引入 I/O、锁与 Logcat 成本。返回值无人消费时，编译器或 R8 可能把计算连同结果一起删除——需要用 `BlackHole.consume()` 防止消除（stable 1.4.1 中它仍标 experimental 需 opt-in，1.5.0-rc01 才稳定化）。Microbenchmark 的协议要点由此确定：`measureRepeated {}` 的 block 表示一次操作，循环由库管理。固定 seed 的 fixture 在 `@Before` 准备、循环内只放被测工作与消费操作。可变状态在 `runWithMeasurementDisabled()`（旧名 `runWithTimingDisabled` 已弃用，暂停的是全部测量指标不只计时）内恢复且恢复代码保持轻量。UI 场景用 `measureRepeatedOnMainThread()` 避免长测量触发 ANR。

编译口径决定结果可解释性：使用 AGP 8.4.0+ 并应用 `androidx.benchmark` Gradle 插件时，Microbenchmark APK 默认 full AOT 编译，目标是压掉 JIT 稳定期的波动。`androidx.benchmark.forceaotcompilation=false` 退出该默认、结果更接近 warmup 后的 JIT 状态。full AOT 更稳定但不代表用户设备的常态编译状态——依赖 JIT 行为的局部优化要补一组关闭强制 AOT 的实验。两组结果只在编译配置一致时才可比较，报告必须记录 Benchmark、AGP、Kotlin、R8 与该开关。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q25 -->

**Q4: [learning] Macrobenchmark 模块怎样配置目标构建与每轮测量流程？**

Macrobenchmark 由独立 test APK 在目标应用进程外驱动；目标 APK 保持接近 release 的优化设置，测试 APK 与目标 APK 的 debuggable 状态分别判断。

Macrobenchmark 由单独安装的 test APK 从外部进程驱动目标应用（UiAutomator 负责启动、手势与等待），所以目标应用要保持 non-debuggable 并声明 `<profileable android:shell="true"/>`——允许 adb shell 启动的受控工具读取详细 trace，又不会把应用变成 debuggable。工程结构随之固定：`:app` 的 `benchmark` build type 用 `initWith(release)` 继承 R8 与资源压缩、只换成 debug 签名，`matchingFallbacks` 指向 release 让依赖模块正确匹配。`:macrobenchmark` 模块用 `com.android.test` 插件并声明 `targetProjectPath`，test APK 的 benchmark variant 可以 debuggable（与目标应用的 non-debuggable 不要混淆）。`:app` 还要依赖 ProfileInstaller（1.3+），它负责把 Baseline Profile 交给系统，也提供清 shader cache、重置 profile 的命令通道。

每轮的控制流是：先按 `CompilationMode` 重置或编译目标应用（编译状态重置在平台侧依赖 `pm compile`——AAOS13 的 `PackageManagerShellCommand` 提供 compile 子命令并支持 `speed-profile` 与 `--check-prof`），然后执行 `setupBlock` 与 `measureBlock`。`setupBlock` 不计入指标但决定每轮起点是否一致（按 home 键、deep link 载入固定数据、等待就绪标记）。`measureBlock` 执行被测交互，其中的任何等待都会进入 trace 的测量窗口，某项指标是否计入这段等待由该指标的定义决定。每个 measured iteration 生成一份 Perfetto trace，是解释数字变化的直接证据。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q26 -->

**Q5: [learning] 为什么 Espresso 不能用来驱动 Macrobenchmark 的测量区间？UI Automator、Monkey 与 Android CLI 在性能链路里各承担什么？**

Macrobenchmark 的测试 APK 运行在目标应用进程之外，而 Espresso 是白盒工具——它面向目标应用的 instrumentation 环境，`onView()` 前会等待消息队列与 `IdlingResource` 空闲，把它的 matcher 和同步放进 `measureBlock` 会把框架同步、断言与测试代码的开销计入测量。完整性能场景的驱动是 UI Automator：它在测试进程中通过系统级 UiAutomation 连接读取 accessibility node、注入手势，不要求目标应用链接测试代码，因此能驱动保留 release 优化的 APK 并处理权限弹窗。

页面准备放进 `setupBlock`，`measureBlock` 只留固定输入。Espresso 保留在提交验证阶段做功能断言。Monkey 是另一类：生成伪随机事件做稳定性压测（`-p` 限包、`-s` 固定 seed 可重放事件序列、`--throttle` 限速、`--monitor-native-crashes` 监视 native 崩溃），它不控制编译和设备状态，不能用于启动耗时或帧时间回归判定。

Android CLI（材料口径：2026 年 5 月发布 1.0）是运行在开发机/CI 上的主机编排工具：`android run` 只安装并启动 APK、不执行构建，`layout`/`screen` 返回 UI 树与截图，`studio` 子命令做符号与静态检查查询——它不生成帧级测量数据，性能结论仍来自 Macrobenchmark、Perfetto 等采集端。Journey 用自然语言执行路径、存在执行差异，不适合控制计时区间。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q27 -->

**Q6: [learning] 性能 CI 的五个阶段各固定什么？为什么模拟器跑出的基准数字不能进物理设备基线？**

性能 CI 按产物流向固定五个阶段：

1. **构建：**生成接近 release 的 `benchmark` 变体，关闭 debuggable，保留 R8 和资源压缩，只更换 CI 签名。
2. **设备准备：**固定型号、build fingerprint、电量、充电方式、亮度、刷新率与账号后台。
3. **执行：**PR 冒烟以 `dryRunMode` 或少量场景验证脚本；夜间趋势在固定物理设备跑完整迭代，发布候选增加机型。
4. **产物收集：**保存指标 JSON、每轮系统 trace，并在同一次记录中绑定 Git commit、APK 哈希和设备指纹。
5. **回归判定：**只比较同名测试和指标、同一启动与编译模式、同类设备；阈值来自历史方差和业务预算。

AndroidX Benchmark 会把模拟器判为配置错误。即使通过 `suppressErrors` 继续运行，结果也只能用于诊断，不能进入物理设备基线。同一 invocation 的迭代还共享温度、缓存和后台状态，并非独立样本。设备刷机、Benchmark 升级或 Baseline Profile 更新时应建立新基线。

Benchmark 的输出是连续数值，受设备、温度、编译状态与系统负载影响，单次运行越界可能只是噪声——门禁必须先量化噪声再定阈值，判定规则是“差值同时超过业务绝对预算与历史噪声上界才判回归”。

落地步骤按依赖顺序：

1. 固定一小组物理设备，为每台设备建立历史序列。
2. 对 main 分支定时运行，估计每个 metric 的自然波动带（MAD、置信区间或团队已有统计模型），不要在没有历史数据时随意写“慢 8% 就失败”。
3. PR 在同型号、同系统 fingerprint 的设备上运行 baseline 与 candidate。
4. 判定用 `candidateMedian - baselineMedian > max(业务预算, 历史噪声上界)`。
5. 首次越界在同一设备自动重跑，两轮方向一致才阻断。
6. 归档 raw JSON 与每轮 Perfetto trace，供人工复核起点、样本数与异常负载。

样本数决定统计口径：启动跑 10 次适合看 median 与逐次 trace，P95 接近极值——CI 要用 P95 必须增加样本并从 JSON 的 `runs` 计算。帧指标一轮就有大量样本，可看 p90/p95/p99 但要同时看 frame count。两个边界：`androidx.benchmark.suppressErrors` 会把 debuggable、模拟器、低电量等配置错误降级为警告，只可用于验证脚本能否跑通的 smoke job，绝不能出现在性能门禁。模拟器可跑 smoke test，性能门禁使用物理设备。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q39 -->

**Q7: [learning] FrameTimingMetric 的三个输出怎么读？TraceSectionMetric 默认 Mode.Sum 的陷阱是什么？**

`FrameTimingMetric` 输出三类样本：

1. **`frameDurationCpuMs`：**UI thread 与 RenderThread 产出一帧的 CPU duration（API 31 前无法计入 `Choreographer#doFrame` 开始前的时间）。
2. **`frameOverrunMs`：**API 31+ 提供，AAOS13 的 `FrameMetrics.DEADLINE` 已支持。它表示相对帧 deadline 的超期或余量——正值超期、负值仍有余量；变刷新率设备上应优先用它判断 deadline 表现。
3. **`frameCount`：**测量窗口内产出的帧数，用来解释“删掉无效帧后分位数反而上升”这类样本变化。

它仍要与 FrameTimeline、主线程、RenderThread 与 GPU 轨道联读，不能凭一个分位数定位根因。

`TraceSectionMetric` 的陷阱在默认聚合方式：stable 1.4.1 中它标注为 `ExperimentalMetricApi`，默认 `Mode.Sum` 会把全部匹配 section 的时长求和并输出次数——遇到递归或重入 section 时，重叠时间会被重复相加。只取第一条要显式选 `Mode.First`，另有 `Min`、`Max`、`Count`、`Average`。它默认只匹配目标包，且忽略未闭合（`dur = -1`）的 slice。用于基准指标的 trace 名称必须稳定，并打开每轮 Perfetto trace 复核选中的区间是否符合预期。

其他指标的门槛：`PowerMetric`（API 29+）的高精度读数依赖设备 power rail，使用前检查 `deviceSupportsHighPrecisionTracking()`。`MemoryUsageMetric` 适合固定操作窗口，不能替代线上 OOM 与 LMKD 证据。


<!-- 来源：01-user-experience/02-app-startup.md · Q7 -->

**Q8: [learning] Macrobenchmark 的 StartupTimingMetric 与 CompilationMode 如何影响启动结果？**

StartupTimingMetric 记录启动请求到首帧或完整可用帧的时间，CompilationMode 控制目标 APK 的编译状态；两者都必须固定才能比较版本：

1. **TTID：**`timeToInitialDisplayMs` 从系统收到启动 Intent 计到目标 Activity 首帧。
2. **TTFD：**`timeToFullDisplayMs` 计到应用调用 `reportFullyDrawn()` 之后的首个完整帧；未调用就没有可解释值，API 29 及以下也可能缺失该字段（材料口径）。
3. **启动模式：**`StartupMode.COLD` 会在 setup 与 measure 之间终止进程，冷、温、热模式不能混成一个趋势。

编译模式按研究目标选：

1. **DEFAULT：**API 24+ 相当于 `Partial(BaselineProfileMode.UseIfAvailable)`；有可用 Profile 和 ProfileInstaller 时才用于部分 AOT，缺失时记录而不报错。API 23 使用 Full。
2. **None()：**清除编译代码和 profile，观察无预编译状态。
3. **Partial()：**无参构造默认 `BaselineProfileMode.Require`、`warmupIterations = 0`。`Require` 要求目标 APK 包含 Baseline Profile 与 ProfileInstaller，且用 AGP 7.0+ 构建以将 Profile 打包进 APK；条件不满足会在编译准备阶段失败。要观察可选安装状态，显式用 `UseIfAvailable`；要测试 Profile 收益，显式用 `Require` 暴露配置错误。
4. **Full()：**目标 APK 的方法全量 AOT，可减少 JIT 噪声；在 API 24+ 不代表真实用户的默认编译状态或性能上限，代码体积与指令缓存压力还可能使它慢于 Partial。
5. **Ignore()：**不重置当前编译状态，是适合外部脚本控制 ART 编译的实验 API。

上述默认值、AGP/安装条件和行为按 AndroidX Benchmark 官方 `CompilationMode`、`CompilationMode.Partial`、`BaselineProfileMode` API 参考及 Android Developers《Benchmark Baseline Profiles with Macrobenchmark library》核对；具体实验还应固定库版本。Android 14 起重置编译状态可保留应用数据，低版本常需重装 APK。报告以 median 为主，仍保留 min、max 和单次 trace。Baseline Profile 收益要在同 APK、同设备、同启动模式下比较 `None()` 与显式 `Partial(baselineProfileMode = Require)`，不能直接套跨项目的经验百分比。


<!-- 来源：05-observability/01-observability-governance.md · Q14 -->

**Q9: [learning] 把 Macrobenchmark 接进 CI 门禁，指标可用性与基线选择有哪些硬边界？**

环境边界：物理设备而非模拟器（模拟器受宿主机与虚拟化影响）。目标包接近生产——non-debuggable、profileable、与生产一致的 R8、资源压缩、签名后处理与 Baseline Profile，测试 APK 与目标 APK 分开构建。固定设备不等于环境恒定，要记录电量、温控、build fingerprint、`CompilationMode` 与数据准备方式。指标边界：`StartupTimingMetric.timeToInitialDisplayMs` 测首帧（TTID），`timeToFullDisplayMs` 依赖 `reportFullyDrawn()`（TTFD）且在 Android 10 / API 29 及更早可能不可用，没有完整绘制点的场景不能用 TTFD 卡门禁。`FrameTimingMetric.frameDurationCpuMs` 与 `frameOverrunMs`（API 31+ 才可用）语义不同、不能共享阈值。`TraceSectionMetric` 与 `PowerMetric` 仍是实验性 API（前者默认 `targetPackageOnly = true` 且 `Mode.Sum` 累计所有同名 section）。

基线边界：`baseline` 不能总指向上一轮——上一轮本身可能已退化，逐次比较放过累积变化。固定已知良好版本适合判断累计漂移，滚动稳定基线适合识别设备环境变化，更新固定基线是发布决策、不能由测试任务在结果变差后自动完成。CI 分三档：PR 快检只验证编译与可执行。主干定时任务在固定物理设备上多轮测量建趋势。发布候选在代表性设备档位跑核心路径。JSON 与每次迭代的 Perfetto trace 都要归档，聚合异常后能回到单次 trace 查原因。测试环境异常标“无效运行”，不伪装成业务豁免。

<!-- 来源：05-observability/01-observability-governance.md · Q11 -->

**Q10: [learning] 性能 A/B 实验的随机化单元和暴露口径怎么定？为什么“只分析成功激活的用户”会引入偏差？**

随机化单元要选与产品语义一致的稳定单元（账号、安装或设备），分析时保留分桶版本：按请求随机会让同一用户反复切换方案，更换哈希函数或盐值会重新分桶。随机化单元与统计单元必须一致——按用户分桶时，同一用户的多次启动、帧与会话是相关观测，应先在用户级聚合再比较，或使用按单元聚类的标准误/聚类 bootstrap。否则活跃用户贡献更多事件，置信区间会过窄。

暴露要拆成漏斗分别记录：分配（assignment）→ 配置拉取（fetch）→ 激活（activate）→ 实际使用 → 上报成功。主分析按预注册的分配口径。只分析激活者属于处理后选择偏差：变体自身导致启动失败或崩溃时，“成功用户”样本恰好抹掉了损伤。Firebase A/B 的官方边界同理：未触发激活事件的目标用户不进实验结果但仍可能收到实验值，且实验开始后不能修改变体权重。

<!-- 来源：05-observability/01-observability-governance.md · Q12 -->

**Q11: [learning] 用 P90/P99 做实验主指标时，判定协议与均值有什么不同？分群分位值能加权相加吗？**

不能。主效应应直接定义为两组分位差 `Qτ(variant) − Qτ(baseline)`，并直接为差值构造区间——分别看两组区间是否重叠不能替代差值区间。分位数是非线性秩统计量，不适用均值公式：按随机化单元做聚类 bootstrap（被抽中单元携带其全部合格事件，直接按帧或请求重采样会破坏单元内相关性），或把 SLO 转成用户/会话级违约率 `P(X > L)`。两种结果回答的问题不同，尾部报告除 P99 外还要预注册体验阈值 `L` 的违约率与超阈值时长。

总体分布是各群混合累积分布 `F(x) = Σ wg·Fg(x)`，总体分位值是混合 CDF 的逆函数，不等于各群分位值的加权和。人群构成变化与群内分布变化要分开归因，标准化时把两组重加权到同一参考构成再计算经验分布。区间同时包含可接受改善与不可接受退化时，结论就是证据不足，不能用单点方向替代。重复查看普通 p 值并随时停止会抬高假阳性率，要用固定终点、预分配错误预算的序贯方法或 anytime-valid 推断。低端机、热限频造成的真实慢样本属于用户体验，不能当异常值删除。

<!-- 来源：05-observability/01-observability-governance.md · Q13 -->

**Q12: [learning] SRM（样本比例失衡）检查为什么必须先于性能指标判断？常见原因有哪些？**

实际分配数与预设权重的差异超出随机波动可解释范围时，实验数据已不可用于决策——继续比较收益是在损坏的数据管道上读数。检查用卡方检验（大样本）或精确方法（小样本、稀疏分群），覆盖全量与预注册关键分群，阈值与查看频率预注册。并沿“分配 → 拉取 → 激活 → 指标产生 → 上传成功”逐层定位：分配已均衡而激活后失衡，说明问题发生在处理之后——某变体在激活或上报前崩溃，此时只过滤“成功用户”会隐藏 Crash/ANR 造成的损伤。

常见原因包括分桶哈希函数或盐值变更、安装 ID 重置、目标条件实现不一致、埋点丢失与变体提前崩溃。A/A 实验用于长期校准分桶与统计任务的假阳性率、置信区间覆盖率，但它不能证明平台永远无偏。实验平台自身也是事故源：一个错误配置可能让启动多拉接口、打开高频日志或调整线程池参数，因此平台要支持配置审计、灰度放量、紧急关闭和不可变的配置版本历史（紧急关闭生成新配置版本并保留旧快照，不覆盖历史值）。

<!-- 来源：05-observability/01-observability-governance.md · Q15 -->

**Q13: [learning] 发版门禁应检查哪些域？产物身份为什么要先冻结？**

发版门禁至少覆盖以下域：

1. **启动：**按启动类型、入口与设备群比较 TTID；仅对正确调用 `reportFullyDrawn()` 的场景使用 TTFD。
2. **渲染：**按刷新率与页面分组检查 FrameTiming 分布、生产慢帧与冻帧。
3. **内存：**检查 Java / 原生堆、PSS / RSS、OOM 与低内存退出。
4. **稳定性：**检查崩溃、ANR、原生崩溃与 `ApplicationExitInfo` 原因，分别呈现受影响用户数与事件数。
5. **功耗：**检查固定场景能耗与异常唤醒。
6. **数据健康度：**检查实验分组、采样配置、上报与查询延迟。
7. **包体与配置：**检查 AAB / 动态特性大小、资源与远程参数快照。

任一域失败都执行预设动作并记录审批人。

产物身份先冻结：`versionCode`、Git 提交、AAB/APK 文件摘要、签名证书摘要、`compileSdk`/`targetSdk`、R8 mapping、原生符号、Baseline Profile 版本与远程配置快照绑定同一构建 ID——实验室和生产引用同一份证据，数字变化才能转换成发布决定。阈值绑定指标版本、设备群、场景与发布阶段，来自本项目历史噪声、SLO 与最小可检测效应，不复制其他项目的毫秒数或百分比。升级目标版本时按“保留原 targetSdk 验证运行兼容”与“targetSdk 升级验证目标兼容”两轮推进（材料以 Android 17 为例），兼容开关只用于逐项定位，正式结论来自接近生产的候选包在完整行为组合下的验证。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q19 -->

**Q14: [learning] 系统升级后应用出现性能回退，怎样用 A/B 对照方法把“版本回归”从噪声里分离出来？**

A/B 指只改变待验证因素（系统版本）的对照实验。先固定其余变量：同一 App 构建、账号数据、操作脚本、设备型号、刷新率、编译状态和 thermal 区间。同时记录 `ro.build.fingerprint` 与安全补丁级别、ART/Profiling 等 Mainline 模块版本、kernel release 与厂商补丁标识、GPU 驱动、Perfetto 版本与采集配置。同一台设备无法在同一时刻运行两个系统版本：用两台同型号设备要先量化个体差异。刷机往返则要控制数据恢复、dexopt、缓存和后台初始化。

比较阶段而不只比较总时间：把 CUJ 分成 App Running、Runnable、Binder、I/O、RenderThread/GPU、SurfaceFlinger 等阶段，总时长回退可能来自不同阶段，只有阶段差异稳定出现，才继续映射到源码或配置变更。源码差异用来验证假设：怀疑调度策略就比较 kernel/厂商 config、cpuset、uclamp 与 power hint。怀疑 Framework 服务就比较对应 AOSP tag 与 DeviceConfig。怀疑显示链路就比较 SurfaceFlinger、HWC、GPU 驱动与 Layer 输入。版本号上的时间相关性只能提出假设，不能代替源码或实验证据。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q32 -->

**Q15: [learning] 性能回归判定为什么要“排除规则预先定义 + 噪声下限 + 效应量”三件套？基线需要保存哪些内容？**

排除规则须在运行前写入协议，至少覆盖：

1. **环境失效：**热状态门控失败、设备更新或重启、已知设备池故障。
2. **场景失效：**目标页面断言失败、脚本未完成目标动作。
3. **证据失效：**预先要求的 trace 缺失或损坏。

看到慢值后不能以“可能遇到 GC”为由事后剔除有效样本；应保留它并用 trace 判断是否属于用户路径。每次排除都保存原因、原始记录和排除前后样本量。

回归判定不能套跨 workload 的固定百分比，要依次确认：

1. 数据质量与预定义排除规则通过。
2. 候选版本超过产品或平台 SLO。
3. 相对基线的效应量超过该测试的历史噪声，并达到预定义的工程意义。
4. 置信区间或独立重复运行支持同方向；样本不足标为 inconclusive，增加独立运行或转人工。

同机配对时，让候选与基线在同一参考设备上运行；温度或后台活动漂移时按 A/B/B/A 或随机顺序交错。多台设备分别计算再按设备层汇总。p-value 不能替代效应量。

基线合同至少绑定四组记录：

1. **应用产物：**git commit、APK/AAB 摘要及构建工具版本。
2. **工具链：**Benchmark、AGP、Kotlin、R8 与 ProfileInstaller 版本。
3. **设备与环境：**设备序列、build fingerprint、ART Mainline、温度和排除规则。
4. **运行与产物：**workload、测试数据、编译/启动模式、迭代协议、原始 JSON、每轮 trace 和统计脚本版本。

只保存 median 无法区分应用回归与环境、工具或脚本变化。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q33 -->

**Q16: [learning] 竞品性能对比要满足哪些“同”才可复现？为什么 FrameMetrics 和 Macrobenchmark 往往用不到竞品身上？**

一轮可复现的竞品测试至少留四份记录：问题定义（比较哪个场景、指标、时间窗口）、实验清单（设备、构建、版本、账号、数据集、网络、温度、编译状态）、原始结果（每次执行的顺序、成败、指标值与 trace）、结论边界（观察到什么、尚未证明什么）。控制变量遵守四同：

1. **同设备**：A/B 在同一台物理设备、同一用户和同一显示模式下成对执行。同型号不等于同设备，多台设备各自成组，不混成一个分布。
2. **同场景**：用用户可感知的起点、终点和成功条件。两个产品信息架构不同不必强求页面同名，但工作量和用户目标要相近。
3. **同网络**：记录带宽、往返延迟、丢包、DNS 与代理。TLS 会话复用、CDN 命中和服务端负载无法靠“连同一个 Wi-Fi”消除，真实网络下应交替执行 A/B 并把网络时间单列。
4. **同热状态**：不要连续测完 A 再测 B，用 A1、B1、B2、A2 这类交错顺序减少时间漂移。设备一旦进入新的 thermal status 该轮作废并等待恢复。不要锁 CPU 频率，固定频率会改变商用设备的调度与温控行为。

可观测性有硬边界：`FrameMetrics` 只能接入自己控制的应用，无法注入未授权的竞品进程。Macrobenchmark 要求目标包 profileable，竞品通常不满足——竞品侧用 UI Automator/adb 驱动加 shell Perfetto 采集，接受可观测性更低的限制。没有竞品源码时，trace 能证明某线程或阶段占用时间，却不能证明其设计动机。结论应分成“观测、候选原因、验证办法”三列。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q43 -->

**Q17: [learning] Geekbench、3DMark、安兔兔、PCMark 的分数在什么条件下才能放进同一组比较？“多核分数高所以多开线程一定快”错在哪里？**

分数只有在 Benchmark 应用版本、应用内测试项目版本、被测系统与驱动版本三个版本号一致，且温度、刷新率、电量等运行条件相同时才可比。大版本变更都改动了测试项或规则：Geekbench 6 到 7、安兔兔 V10 到 V11、PCMark Work 2.0 到 3.0 均不可比，旧序列应冻结在独立字段，厂商没有发布换算公式时不做经验换算。

多核分数的误读来自把代理变量当直接测量：Geekbench 分数由固定子测试按参考系统归一化后加权而来，表示“这套工作负载中的性能”，不表示任意 App 代码同比例变快。多核分数采用 shared-task 模型并只纳入现实任务适合并行的套件，回答不了“开八个线程快多少”——要先检查任务依赖、可并行比例、线程池拥塞与内存带宽，主线程上的串行关键路径不会因多核总分高而自动缩短。同理，Geekbench Compute 跑 Vulkan/OpenCL 计算负载，不覆盖着色、光栅化、合成与显示刷新，分析渲染问题要看 3DMark 图形测试、FrameTimeline 和 App 自身帧数据。3DMark Stress 的 stability（最低轮与最高轮平均帧率之比）必须连同每轮曲线解释：98% 可能来自“每轮都不快”，很高的首轮帧率配 65% 说明峰值强但持续性能弱。

做法上把工具语义写进字段名（`gb7_vulkan_compute`、`3dmark_wild_life_stability` 而不是笼统的 `gpu_score`），结论只引用相关分项加 App 证据。综合分可留在摘要，但“安兔兔总分高所以 SQLite 快”这类推论越过了证据边界。
