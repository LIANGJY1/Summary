# Android Vitals

本组收录该工具或 API 的采集、使用与结果解释题。

<!-- 来源：04-reliability/02-stability-and-crash.md · Q5 -->

**Q1: [learning] Google Play 的 user-perceived crash rate 与 user-perceived ANR rate 口径是什么？阈值应该怎么理解？**

Google Play Console Help 的《Technical quality requirements》将两者列为影响商店可见性的 core vitals。口径是当天至少遇到一次用户可感知崩溃或 ANR 的日活用户比例。该官方页面当前公布的阈值为：user-perceived crash rate 全设备平均 1.09%、单手机型号 8%、手表 4%；user-perceived ANR rate 分别为 0.47%、8%、5%。

口径要点：

1. **DAU 分母**：daily active user 按“单个设备上的单日活跃用户”计，同一人在两台设备使用贡献两个日活。同一日活可包含多个会话，官方 FAQ 示例：一天玩三次崩一次，vitals 显示 100%，按会话计的 SDK 显示 33%。
2. **active use**：对手机和平板指应用正在显示 Activity 或执行前台服务。后台组件崩溃仍进总体 crash rate，只是不一定进 user-perceived 分子。
3. **ANR 范围**：当前只有 input dispatching timed out 计入 user-perceived ANR，Service、Broadcast、Provider 等 ANR 要进内部分析但不能加进该分子。
4. **阈值性质**：达到或超过阈值 Play 可能降低商店可见性、可能展示警告，是商店质量边界而不是固定审核拒绝线，也不是团队 SLO 推荐值。Play 每天用最近 28 天平均评估。

Android vitals 与自建 SDK 数字不同是常态，无须强行对齐：vitals 来自系统（能覆盖 SDK 初始化前的崩溃）、只统计认证设备与 Play 安装、只使用同意共享数据的用户、受隐私最小样本与展示延迟影响。团队要明确每张看板回答的问题，而不是追求两个系统数值接近。

<!-- 来源：05-observability/02-online-diagnostics.md · Q1 -->

**Q2: [learning] Android Vitals 与内部 APM 是两套什么口径的数据？为什么内部 crash-free 率和 Play 的 user-perceived crash rate 不能放在同一张图上直接比较？**

Vitals 是 Google Play 对线上技术质量的外部观测：只统计从 Play 安装、设备通过认证、允许共享使用情况与诊断数据且达到隐私展示门槛的用户。内部 APM 统计所有接入并成功上报的渠道与包。Vitals 的分母是 28 天窗口内用户加权的 DAU（一天一台设备上的一名用户）或按日聚合的 user session（太平洋时间午夜起 24 小时内的全部使用活动），内部 APM 的分母是团队自定义的 user、device、session、launch 等口径。两者没有事件级关联键，重复崩溃、后台事件和渠道范围的处理也不同。

直接相减或比较大小会产生三类误判：低流量版本或小设备分组可能没有可见数据，“没有数据”不是“零问题”。Vitals 排除未认证设备和非 Play 渠道（国内渠道、企业分发、预装、旁加载必须由其他观测系统覆盖）。分母不同（DAU 与会话）让两个百分比没有共同的比较基准。正确做法是分别判断各自趋势，再按版本、时间桶、设备维度和错误聚类验证两边是否指向同一变更。时效也不同：Vitals 每日更新、受聚合与隐私门槛影响，内部 APM 可做到分钟级但受采样与上传成功率影响。

<!-- 来源：05-observability/02-online-diagnostics.md · Q2 -->

**Q3: [learning] 2026 年 Play 的 core vitals 有哪些指标、阈值是多少？整体阈值与机型阈值分别约束什么范围？**

core vitals 指影响 Play 可见性的指标，Play 每天按最近 28 天的用户加权结果检查，突增时可能提前采取措施。2026 年口径如下：

- **user-perceived crash rate**：整体阈值 ≥ 1.09%（文档用 "at least"，达到即算 bad behavior）。机型阈值手机 ≥ 8%、手表 ≥ 4%。
- **user-perceived ANR rate**：整体阈值 ≥ 0.47%。机型阈值手机 ≥ 8%、手表 ≥ 5%。
- **excessive partial wake locks**：超过 5%（文档用 "more than"），无机型阈值。规则是应用在后台或运行前台服务期间持有的 partial wake lock 在 24 小时内累计达到 2 小时即判 excessive（音频、定位、用户发起的 JobScheduler 任务等有豁免），28 天内 excessive 会话占比超 5% 可能影响可见性——该政策自 2026 年 3 月 1 日起进入可见性约束阶段，旧资料的“3 小时”“仍处 beta”已过时。
- **excessive battery usage**：目前只对有足够数据的表盘应用提供（电量消耗超过 4.44%/hour 计入），不扩展到所有 Wear OS 应用。

整体越线可能影响所有设备上的可见性，机型越线主要影响对应设备并可能在详情页显示警告。两点边界：官方文档对可见性影响使用 "may"/"likely"，不能改写成每次越线立即降权的确定承诺。内部暂停阈值可以比官方更严格，但字段名必须标明属于团队规则，不能写成 Play 官方阈值。崩溃/ANR 持续超 7 天的新出现问题会被标记为 emerging issues 并给最长 21 天处理——这不改变 28 天统计定义，也不是继续放量的宽限承诺。

<!-- 来源：05-observability/02-online-diagnostics.md · Q3 -->

**Q4: [learning] Vitals 的 user-perceived crash/ANR 与自建 Crash SDK 的口径差在哪？用 Play 聚合数据定位一个崩溃时，三层证据怎么配合？**

user-perceived crash 只统计用户主动使用应用（显示 Activity 或执行前台服务）时发生至少一次崩溃的受影响用户。user-perceived ANR 当前只计 `Input dispatching timed out` 类型。自建 SDK 通过 watchdog、主线程消息耗时或系统 trace 能识别更多卡死，是否标为前台取决于 SDK 自己的进程状态实现。“至少一次”的分子不随多次崩溃增加，观察循环失败要看 multiple crash/ANR rate（一天内至少两次的受影响用户）。Wear OS 有一条例外：官方文档把手表的前台和后台崩溃都纳入 user-perceived 口径，手机结论不能直接复用。

三层证据各管一段：Play 层确定影响面（`versionCode`、`deviceModel`、`apiLevel`、地区、错误聚类），系统层用 `ApplicationExitInfo`（Android 11+）确认退出原因与可用 trace，应用层补用户路径、页面、实验桶、feature flag 和最近变更。三层通常没有可跨系统使用的事件 ID，只能按版本、时间桶、设备维度、进程名和 top frame 建立弱关联——弱关联只筛出候选事件，还要打开少量原始证据验证。两个聚合数字相近不能证明来自同一批事件，Play 的聚类 ID 也不应写进内部事件主键。ANR 不能只看一张堆栈：输入超时只说明输入没有按时处理，根因可能发生在主线程同步 Binder、磁盘 I/O、锁竞争、类初始化或 CPU 密集计算中，要回到 ANR trace、Perfetto 和相邻线程状态判断。

<!-- 来源：05-observability/02-online-diagnostics.md · Q4 -->

**Q5: [learning] Play 的 slow startup、frozen frame、user-perceived LMK 各自的统计边界是什么？为什么“这一帧超过 700 ms”推不出 ANR，“LMK 高于 1%”不能写进发版门禁？**

slow startup 按 TTID（首帧显示耗时）统计，cold ≥ 5 s、warm ≥ 2 s、hot ≥ 1.5 s 计为 excessive——固定秒数识别不了从 1.8 s 退化到 2.4 s 这类未过线的回归，灰度暂停规则仍要比较同启动类型、同设备档的分布，`reportFullyDrawn()` 也不能代替 TTID。slow/frozen rendering 只覆盖 View/UI Toolkit 路径（frozen frame 阈值 700 ms），直接使用 Vulkan/OpenGL/游戏引擎渲染时间的应用可能不在该报表中。user-perceived LMK 统计 28 天内至少经历一次可感知低内存终止（如 Activity 正在显示或前台服务运行时被杀）的 DAU，`ApplicationExitInfo.REASON_LOW_MEMORY` 只是诊断起点，设备整体压力、swap 和应用内存峰值共同参与决策，不能把每次都归咎单一对象泄漏。

两个推论都不能成立的原因在于判定条件不同：frozen frame 超过 700 ms 只说明产生了明显停顿，是否形成 ANR 取决于系统超时条件（输入/广播/服务等各有超时契约），渲染归因应以 FrameTimeline 和线程调度证据为准。LMK 页面把高于 1% 称为需要立即处理的警戒信号，但 Android Vitals 总览没有把 user-perceived LMK 列为 core vital、也没有为它声明商店可见性阈值，所以发版规则里它只能标成内部警戒线。游戏另有 Slow Sessions（仅游戏、运行满一分钟后统计，超过 25% 帧的相邻呈现间隔不低于 50 ms 即 20 FPS slow session），反映用户看到的出帧节奏，不等同于 CPU 单阶段耗时。

<!-- 来源：05-observability/02-online-diagnostics.md · Q5 -->

**Q6: [learning] 用 Play Developer Reporting API 把 Vitals 数据入仓时，哪些口径错误会让曲线“看似完整”却不可信？**

五类高频错误：不读 freshness 就拉数——每个 metric set 提供各粒度最新可用数据点，定时任务应先读 freshness、只在数据推进后拉新增区间，并记录服务端 freshness 与抓取时间。时区混用——`timelineSpec` 的 DAILY 只支持 `America/Los_Angeles`、HOURLY 只支持 UTC，与 Console 窗口和内部看板时区都可能不同。对聚合值二次汇总——`distinctUsers` 是按数量级取整的近似值，官方明确警告不要跨维度行相加，需要整体值时应发起不带 breakdown 的独立查询。7 天/28 天字段是按每日 `distinctUsers` 加权的 rolling rate，服务端已让大流量日期占更高权重，再对日值做简单平均是错的。rate 与 `errorReportCount` 是两种量纲（受影响用户比例与错误报告条数），不能用同一条公式合并。

分页时除 `pageToken` 外必须保持原查询参数不变，否则可能拼出看似连续的假曲线。2026 年稳定性与性能 rate 的主要入口是 `v1beta1`（`vitals.crashrate`、`vitals.anrrate`、`vitals.lmkrate`、`vitals.slowstartrate`、`vitals.slowrenderingrate`、`vitals.excessivewakeuprate`、`vitals.stuckbackgroundwakelockrate`），错误报告计数仍是 `v1alpha1`。metric set 名称与字段应按官方 schema 保存。注意 `stuckBgWakelockRate`（后台持有超 1 小时的用户率）与 core vital 的 excessive partial wake locks（24 小时累计 2 小时、28 天影响 5% 会话）定义不同，仓库可以同时保存两者，但告警字段必须带上具体定义。发版决策上：Play 未越线而内部指标恶化时按内部灰度规则处理，Play 升高而内部无变化时先查分母、渠道、同意范围和上报成功率，不能按“无复现”关闭问题。

<!-- 来源：05-observability/01-observability-governance.md · Q16 -->

**Q7: [learning] staged rollout 的 userFraction、暂停（halt）与“回滚”各是什么语义？灰度期间为什么不能只看崩溃率？**

`userFraction` 是有资格接收该分阶段版本的用户比例，不是安装完成率或在线占比。它只对 `inProgress`/`halted` 状态有效且严格在 0 与 1 之间。暂停把状态置为 `halted`：阻止更多用户取得该版本，已安装用户停留在该版本——Play 不能远程降级 APK，“回滚”实际是三种操作：暂停发布、回退服务端配置、发布更高 `versionCode` 的修复包。已全量版本也可暂停（前一个版本会重新提供给未安装用户，内部测试轨道除外），配置错误走配置回退，已安装用户靠服务端兼容与功能开关兜底。staged rollout 不满足严格 A/B 条件（无稳定旧版对照组，覆盖受国家、资格与自动更新影响），版本间差异可以触发处置但不能证明代码因果。

灰度决策要分快慢信号：自建 APM、崩溃/ANR 事件流与启动帧指标是快信号（前提是数据延迟与覆盖达标）。Android vitals 每天更新最近 28 天均值，适合长期质量判断，不能作为刚起步小流量的扩量依据。客服与评论到达慢且有选择偏差，“暂时没有投诉”不能抵消已观测的崩溃或数据管道异常。上报成功率、事件丢弃、配置命中率或上报延迟异常时，当前结论标“不可判定”，维持或暂停当前比例。

<!-- 来源：05-observability/01-observability-governance.md · Q17 -->

**Q8: [learning] App Performance Score 的静态分与动态分分别评估什么？分数怎么转换成工程任务？**

这是 Google 仍处 Preview 的评估框架（2026 年口径），0–100 分表示评估表中还有多少改进空间。静态分不运行应用、检查源码配置：最新 AGP、R8 full mode 且 keep 例外受控、Baseline Profile 覆盖至少一条用户路径、Startup Profile 优化 DEX 布局、最新稳定版 Compose、内容可用时调用 `FullyDrawnReporter`/`reportFullyDrawn()`。这些项成本低、信号清楚、适合接 CI。注意配置纪律：生成 profile 的 variant 应关闭 R8 混淆以保持方法签名可匹配，最终 release 才启用。只检查仓库里有 `baseline-prof.txt` 证明不了 release 产物包含并使用它。

动态分在物理设备上测启动到可交互（口径指向 TTFD）与滚动/动画中的 slow/frozen frames 占比，分数属于“该设备、该构建、该次观察条件”，不能跨应用排名，低端设备会放大问题。分数不能给根因、也不能替代线上 KPI——分数高但支付页等待久、搜索首屏空，用户仍觉得差。转换方式是四个队列：配置项（AGP/R8 → Profile → Compose/TTFD）、测试项（冷启动、通知启动、滚动、核心交易路径的 Macrobenchmark 用例）、trace 项（固定场景采 trace，归因线程/I/O/Binder/GPU）、平台项（采集 → 聚合 → 告警）。静态与动态都低时先修静态项（配置修正也可能改善动态表现），但阻断核心业务的动态问题不能等静态项完成。规则会随 Preview 迭代，报告保存评分日期与规则快照，不同规则生成的分数不画同一条趋势线。
