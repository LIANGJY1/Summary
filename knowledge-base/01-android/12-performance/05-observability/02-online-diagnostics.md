# Android 性能线上诊断

**Q1: [learning] Android Vitals 与内部 APM 是两套什么口径的数据？为什么内部 crash-free 率和 Play 的 user-perceived crash rate 不能放在同一张图上直接比较？**

Vitals 是 Google Play 对线上技术质量的外部观测：只统计从 Play 安装、设备通过认证、允许共享使用情况与诊断数据且达到隐私展示门槛的用户。内部 APM 统计所有接入并成功上报的渠道与包。Vitals 的分母是 28 天窗口内用户加权的 DAU（一天一台设备上的一名用户）或按日聚合的 user session（太平洋时间午夜起 24 小时内的全部使用活动），内部 APM 的分母是团队自定义的 user、device、session、launch 等口径。两者没有事件级关联键，重复崩溃、后台事件和渠道范围的处理也不同。

直接相减或比较大小会产生三类误判：低流量版本或小设备分组可能没有可见数据，“没有数据”不是“零问题”。Vitals 排除未认证设备和非 Play 渠道（国内渠道、企业分发、预装、旁加载必须由其他观测系统覆盖）。分母不同（DAU 与会话）让两个百分比没有共同的比较基准。正确做法是分别判断各自趋势，再按版本、时间桶、设备维度和错误聚类验证两边是否指向同一变更。时效也不同：Vitals 每日更新、受聚合与隐私门槛影响，内部 APM 可做到分钟级但受采样与上传成功率影响。

**Q2: [learning] 2026 年 Play 的 core vitals 有哪些指标、阈值是多少？整体阈值与机型阈值分别约束什么范围？**

core vitals 指影响 Play 可见性的指标，Play 每天按最近 28 天的用户加权结果检查，突增时可能提前采取措施。2026 年口径如下：

- **user-perceived crash rate**：整体阈值 ≥ 1.09%（文档用 "at least"，达到即算 bad behavior）。机型阈值手机 ≥ 8%、手表 ≥ 4%。
- **user-perceived ANR rate**：整体阈值 ≥ 0.47%。机型阈值手机 ≥ 8%、手表 ≥ 5%。
- **excessive partial wake locks**：超过 5%（文档用 "more than"），无机型阈值。规则是应用在后台或运行前台服务期间持有的 partial wake lock 在 24 小时内累计达到 2 小时即判 excessive（音频、定位、用户发起的 JobScheduler 任务等有豁免），28 天内 excessive 会话占比超 5% 可能影响可见性——该政策自 2026 年 3 月 1 日起进入可见性约束阶段，旧资料的“3 小时”“仍处 beta”已过时。
- **excessive battery usage**：目前只对有足够数据的表盘应用提供（电量消耗超过 4.44%/hour 计入），不扩展到所有 Wear OS 应用。

整体越线可能影响所有设备上的可见性，机型越线主要影响对应设备并可能在详情页显示警告。两点边界：官方文档对可见性影响使用 "may"/"likely"，不能改写成每次越线立即降权的确定承诺。内部暂停阈值可以比官方更严格，但字段名必须标明属于团队规则，不能写成 Play 官方阈值。崩溃/ANR 持续超 7 天的新出现问题会被标记为 emerging issues 并给最长 21 天处理——这不改变 28 天统计定义，也不是继续放量的宽限承诺。

**Q3: [learning] Vitals 的 user-perceived crash/ANR 与自建 Crash SDK 的口径差在哪？用 Play 聚合数据定位一个崩溃时，三层证据怎么配合？**

user-perceived crash 只统计用户主动使用应用（显示 Activity 或执行前台服务）时发生至少一次崩溃的受影响用户。user-perceived ANR 当前只计 `Input dispatching timed out` 类型。自建 SDK 通过 watchdog、主线程消息耗时或系统 trace 能识别更多卡死，是否标为前台取决于 SDK 自己的进程状态实现。“至少一次”的分子不随多次崩溃增加，观察循环失败要看 multiple crash/ANR rate（一天内至少两次的受影响用户）。Wear OS 有一条例外：官方文档把手表的前台和后台崩溃都纳入 user-perceived 口径，手机结论不能直接复用。

三层证据各管一段：Play 层确定影响面（`versionCode`、`deviceModel`、`apiLevel`、地区、错误聚类），系统层用 `ApplicationExitInfo`（Android 11+）确认退出原因与可用 trace，应用层补用户路径、页面、实验桶、feature flag 和最近变更。三层通常没有可跨系统使用的事件 ID，只能按版本、时间桶、设备维度、进程名和 top frame 建立弱关联——弱关联只筛出候选事件，还要打开少量原始证据验证。两个聚合数字相近不能证明来自同一批事件，Play 的聚类 ID 也不应写进内部事件主键。ANR 不能只看一张堆栈：输入超时只说明输入没有按时处理，根因可能发生在主线程同步 Binder、磁盘 I/O、锁竞争、类初始化或 CPU 密集计算中，要回到 ANR trace、Perfetto 和相邻线程状态判断。

**Q4: [learning] Play 的 slow startup、frozen frame、user-perceived LMK 各自的统计边界是什么？为什么“这一帧超过 700 ms”推不出 ANR，“LMK 高于 1%”不能写进发版门禁？**

slow startup 按 TTID（首帧显示耗时）统计，cold ≥ 5 s、warm ≥ 2 s、hot ≥ 1.5 s 计为 excessive——固定秒数识别不了从 1.8 s 退化到 2.4 s 这类未过线的回归，灰度暂停规则仍要比较同启动类型、同设备档的分布，`reportFullyDrawn()` 也不能代替 TTID。slow/frozen rendering 只覆盖 View/UI Toolkit 路径（frozen frame 阈值 700 ms），直接使用 Vulkan/OpenGL/游戏引擎渲染时间的应用可能不在该报表中。user-perceived LMK 统计 28 天内至少经历一次可感知低内存终止（如 Activity 正在显示或前台服务运行时被杀）的 DAU，`ApplicationExitInfo.REASON_LOW_MEMORY` 只是诊断起点，设备整体压力、swap 和应用内存峰值共同参与决策，不能把每次都归咎单一对象泄漏。

两个推论都不能成立的原因在于判定条件不同：frozen frame 超过 700 ms 只说明产生了明显停顿，是否形成 ANR 取决于系统超时条件（输入/广播/服务等各有超时契约），渲染归因应以 FrameTimeline 和线程调度证据为准。LMK 页面把高于 1% 称为需要立即处理的警戒信号，但 Android Vitals 总览没有把 user-perceived LMK 列为 core vital、也没有为它声明商店可见性阈值，所以发版规则里它只能标成内部警戒线。游戏另有 Slow Sessions（仅游戏、运行满一分钟后统计，超过 25% 帧的相邻呈现间隔不低于 50 ms 即 20 FPS slow session），反映用户看到的出帧节奏，不等同于 CPU 单阶段耗时。

**Q5: [learning] 用 Play Developer Reporting API 把 Vitals 数据入仓时，哪些口径错误会让曲线“看似完整”却不可信？**

五类高频错误：不读 freshness 就拉数——每个 metric set 提供各粒度最新可用数据点，定时任务应先读 freshness、只在数据推进后拉新增区间，并记录服务端 freshness 与抓取时间。时区混用——`timelineSpec` 的 DAILY 只支持 `America/Los_Angeles`、HOURLY 只支持 UTC，与 Console 窗口和内部看板时区都可能不同。对聚合值二次汇总——`distinctUsers` 是按数量级取整的近似值，官方明确警告不要跨维度行相加，需要整体值时应发起不带 breakdown 的独立查询。7 天/28 天字段是按每日 `distinctUsers` 加权的 rolling rate，服务端已让大流量日期占更高权重，再对日值做简单平均是错的。rate 与 `errorReportCount` 是两种量纲（受影响用户比例与错误报告条数），不能用同一条公式合并。

分页时除 `pageToken` 外必须保持原查询参数不变，否则可能拼出看似连续的假曲线。2026 年稳定性与性能 rate 的主要入口是 `v1beta1`（`vitals.crashrate`、`vitals.anrrate`、`vitals.lmkrate`、`vitals.slowstartrate`、`vitals.slowrenderingrate`、`vitals.excessivewakeuprate`、`vitals.stuckbackgroundwakelockrate`），错误报告计数仍是 `v1alpha1`。metric set 名称与字段应按官方 schema 保存。注意 `stuckBgWakelockRate`（后台持有超 1 小时的用户率）与 core vital 的 excessive partial wake locks（24 小时累计 2 小时、28 天影响 5% 会话）定义不同，仓库可以同时保存两者，但告警字段必须带上具体定义。发版决策上：Play 未越线而内部指标恶化时按内部灰度规则处理，Play 升高而内部无变化时先查分母、渠道、同意范围和上报成功率，不能按“无复现”关闭问题。

**Q6: [learning] 一次“打开页面卡住”要建哪些存储观测事件？为什么 wall time 与 CPU time 必须分开记录、事件必须带 scope？**

按失败层次分类建事件，单一的 `storage_slow` 无法判断该检查哪一层：文件调用事件（操作类别、路径类别、线程、wall time、CPU time、字节数、调用点、场景）区分调用方耗时与疑似 I/O 等待。不良形态事件捕获主线程访问、细碎调用、重复读取和大目录遍历。SQLite 执行慢事件（scope、SQL 指纹、事务状态、driver）区分查询本身、排队与连接等待。再加 SQLite 错误、空间快照、文件校验失败和资源未关闭信号。

wall time 是自然经过时间，CPU time 是线程实际占用处理器的时间：前者长而后者短指向调度、锁或 I/O 等待，两者都高更像应用代码计算——这只用于选择下一步证据，不能凭两个时长直接认定内核 I/O。scope 决定同一字段能解释什么：围绕 DAO 方法计时得到调用端到端时长（含协程调度与连接等待），driver 执行边界更接近 SQLite 操作，native `read()`/`write()` Hook 看到的是系统调用（覆盖不了页缓存回写）。字段里只写 `duration` 会让后台把不同 scope 的数据错误合并。路径、SQL 和文件内容可能含用户数据，默认只采集稳定类别、受控指纹和应用调用点，原始证据走受限诊断流程。

**Q7: [learning] 线上 I/O 采集为什么优先公开 API 回调而不是 Native Hook？直接 Hook libc 的 read/write 要逐项验证什么？**

采集路径同时决定覆盖面和升级风险，优先级从低风险到高风险：应用封装或库提供的回调（生产长期主路径，看不到绕过封装的三方调用）、编译期插桩（自有字节码的文件入口与 DAO 调用，看不到 native、反射和系统内部 I/O）、StrictMode/BlockGuard（libcore OS 层的线程磁盘读写与泄漏信号，不测设备耗时，三方 native 不覆盖）、Native Hook（覆盖面最广、风险最高，只对少量用户专项灰度启用）。adb 加 Perfetto 属实验室手段，能看到调度、频率、database atrace 与设备允许的 ftrace 事件。

Hook 属于未获平台兼容性保证的手段，即使某一版能拦截 `open`/`read`/`write`/`close`，也要逐项验证：已加载与延迟加载的共享库是否都进入 Hook。32/64 位 ABI、Android 版本与厂商链接器行为是否一致。`pread`/`pwrite`、`readv`/`writev`、`mmap`、`sendfile` 和直接 syscall 是否在覆盖清单。Hook 内部是否有递归保护（采集与上报自身的 I/O 不得再次触发）。路径与 fd 生命周期采集是否线程安全、失败能否回退原函数。远程关闭是否在 Hook 初始化前可用。事件必须带 `collector` 和 `coverage_version`，否则无法区分业务回归与采集器升级造成的数量变化。

**Q8: [learning] StrictMode 能证明“主线程发生了磁盘操作”吗？能测出这次 I/O 花了多久吗？为什么不能反射替换 CloseGuard 的 reporter 做长期监控？**

StrictMode 能证明触发、不能测时长。AAOS13 源码核对：`BlockGuardOs` 在 `open`、`read`、`write`、`fsync`、`stat`、`rename` 等 libcore OS 操作前调用线程策略的 `onReadFromDisk()`/`onWriteToDisk()`，所以 StrictMode 回答的是“这个受监控线程是否触发了磁盘操作”，它不测量存储设备完成一次请求的时间。调用发生时数据可能已在页缓存，耗时也可能短于一次帧预算，主线程告警要关联 FrameTimeline 和用户操作后再定优先级。两个使用边界：`setThreadPolicy()` 只作用于调用它的线程，只在主线程安装不能声称覆盖全部工作线程。`detectUnbufferedIo()` 从 API 26 提供、`detectLeakedSqlLiteObjects()` 从 API 9 提供（AAOS13 均已核对存在），适合 debug、自动化测试与内测包，生产开启要先评估成本。

`CloseGuard.setReporter()` 在 AAOS13 仍标记为 `@hide`（已核对），普通应用没有稳定的公开调用契约。反射替换 reporter 会同时引入 non-SDK 限制和版本兼容风险。长期监控应使用公开的 StrictMode VM policy（`detectLeakedSqlLiteObjects()`、`detectLeakedClosableObjects()` 等）并限制在测试与受控诊断场景。

**Q9: [learning] 一个 DAO 方法耗时 300 ms，怎么分层定位慢在 SQL 本身、连接等待还是结果映射？验证查询计划时要注意什么？**

按计时 scope 分层，一层排除一类原因：DAO 端到端时长回答“用户路径中等待多久”，包含排队、协程调度和结果映射。transaction scope（`begin` 到 commit/rollback 返回）暴露锁持有窗口与批量写范围。driver statement scope（prepare/step 到完成）最接近 SQL 执行与取数成本。exception/recovery scope 记录错误类别与恢复结果。若 DAO 层高而 driver 层低，慢在调度或结果映射，不在 SQL 计划。

`RoomDatabase.QueryCallback#onQuery()` 只在查询执行时提供 SQL 与绑定参数，没有开始和结束时间——在回调的 Executor 里计时只会得到处理回调本身的时间。官方提示它有额外成本，应按需启用并采样，参数只记类型、SQL 先归一化删除敏感字面量再算指纹。确认 SQL 本身慢后，用 `EXPLAIN QUERY PLAN` 在同一 SQLite 实现上验证计划：`SCAN` 表示扫描，`SEARCH ... USING INDEX` 表示用了索引，`USE TEMP B-TREE` 说明排序或去重可能要临时结构。计划节点不是自动修复指令：小表扫描可能合理，索引增加写入与空间成本，复合索引列顺序要结合过滤、排序和选择性判断。官方建议用目标设备的 `adb shell sqlite3`（不同版本 SQLite revision 不同）。使用 `BundledSQLiteDriver` 时应用携带自己的 SQLite 实现，设备自带 sqlite3 与它可能不同，应用同一 driver 和 schema 的测试工具执行。

**Q10: [learning] Android 框架内部的 SQLite 诊断信息（OperationLog、语句缓存统计、慢查询开关）能搬进普通 App 吗？AAOS13 与 Android 17 有什么差异？**

不能——这些诊断都是框架私有或 `@hide`，普通 App 没有对应采集接口。AAOS13 源码核对：`SQLiteConnection.OperationLog` 保留 `MAX_RECENT_OPERATIONS = 20` 条最近操作，只服务于 framework 调试与 dump。材料核对的“严格超过 2 秒的操作记入长操作”（`LONG_OPERATION_THRESHOLD_MS = 2_000`）是 Android 17 的实现，AAOS13 树中不存在该常量，2 秒也不应复制成业务慢查询门槛。`SQLiteConnectionPool#getStatementCacheMissRate()` 在 Android 17 带 `@hide`（AAOS13 树中未见该方法）。

公开侧的边界：每连接 prepared statement cache 默认 25、`setMaxSqlCacheSize()` 公开上限 100（AAOS13 已核对 `SQLiteDatabaseConfiguration` 默认 25 与 `MAX_SQL_CACHE_SIZE = 100`），没有内存与命中率证据时不应把缓存调到上限。`setIdleConnectionTimeout()` 已废弃，文档警告重建连接会清除 per-connection PRAGMA 且系统没有恢复回调。`setLookasideConfig(0, 0)` 只是请求禁用 lookaside，系统可能按设备选择不同值。开发设备可用 `adb shell dumpsys meminfo <package>` 查看缓存命中统计，普通应用没有免权限的生产 API。`SQLiteDebug.shouldLogSlowQuery()` 读取带 UID 后缀的系统属性、受日志标签控制，是隐藏调试开关，不能当动态下发阈值方案。结论：线上用自己 driver 层或回调采集，framework 细节只用于理解平台行为。

**Q11: [learning] SQLite 报错按 message 搜 "corrupt" 为什么不可靠？发现损坏后的恢复策略要区分什么？远程清理要满足什么条件？**

错误分类要看主错误码与扩展错误码，异常 message 只是文本：`busy`/`locked` 说明并发或锁状态，`full` 说明写入空间不足，`IOERR` 指向 I/O 路径，`CORRUPT`/`NOTADB` 才接近数据库内容或格式问题——按 message 搜 "corrupt" 会把不同性质的错误合并到同一处理路径。事件至少记录数据库别名、driver 与库版本、异常类、主/扩展错误码（driver 提供时）、事务状态、WAL/SHM 文件是否存在及大小、剩余空间、恢复动作与结果。message 可能带 SQL 或路径，进日志前要脱敏。

`PRAGMA integrity_check` 会读取大量页面，不适合在前台或每次启动执行，应放在维护窗口、数据库副本或用户明确发起的诊断流程中并记录检查覆盖。恢复策略按数据性质分三类处理：可再生缓存直接删除重建。可从服务器重建的数据走重新同步。唯一用户数据必须先尝试导出保存——默认删除数据库会让“恢复成功”掩盖数据丢失。应用私有可替换文件用 `AtomicFile` 或“先写临时文件、成功后 rename 替换”的协议。数据库文件交给 SQLite 的事务与 journal/WAL 机制，不自行拼装或远程删除 `.db`、`-wal`、`-shm` 文件。远程清理是破坏性能力，需要固定 allowlist、规则版本、dry-run 先行、幂等执行和远程关闭开关。未知文件、唯一用户数据、打开中的数据库文件都不能按“疑似缓存”处理。

**Q12: [learning] 网络质量监控为什么要同时保留客户端、接入层、系统网络状态三层观察？接入层没有这条请求记录时能丢弃客户端样本吗？**

三层各回答一个不同的问题：客户端样本回答用户当时的经历（阶段耗时、失败异常、路由尝试、页面场景），盲区是上报通道自身受故障影响、采样和延迟上传会改变可见分布。接入层日志回答请求是否到达入口、入口流量与状态码分布，看不到 DNS 失败、建连失败和被代理/VPN 拦截的请求。系统网络状态（`NetworkCapabilities` 等）解释默认网络、VPN、计费与门户验证，拿不到驱动、基站和运营商内部路由。

对账时按 `trace_id`（业务请求路径）、`request_id`（网络库一次逻辑调用）、`attempt_id`（一次路由或连接尝试）分成三个集合：两侧都有记录的可比较端到端耗时与服务端耗时。只有客户端记录的用于分析入口前失败（DNS、运营商、CDN、证书、网络切换）。只有接入层记录的提示客户端采样、ID 注入或上报丢失。DNS、connect、TLS 阶段失败的 attempt 不会到达服务器，服务端自然没有同名 ID——这些记录必须保留，并保存候选地址数量、脱敏后的 peer 分组、库级错误枚举、TLS 错误类别、代理/VPN 标记等受控证据。原始 DNS 列表、完整 IP、完整 URL 和查询参数不应作为通用遥测字段。重定向和重试还要按 `exchange_id` 区分，避免把多次响应的字节数和耗时相加后当成一次请求。

**Q13: [learning] OkHttp EventListener 和 Cronet Metrics 的阶段事件为什么不能假设序列固定？连接复用时“阶段为空”为什么不能记成 0 ms？**

事件序列由库的重试、回退和复用策略决定：OkHttp 的 Fast Fallback 会交错尝试多个地址，同一个 `Call` 中 connect 系列事件和 `connectionAcquired` 都可能出现多次。重定向与认证质询产生新的 exchange。连接复用时 DNS、connect、TLS 事件完全缺席。Cronet 的 `RequestFinishedInfo.Metrics` 对没有发生或无法取得的阶段返回 `null`，复用 socket 时 DNS/connect/SSL 均为空。反过来“存在 DNS 事件”也不能等价于“访问了远端 DNS”——DNS 命中本地缓存但未复用 socket 时 Cronet 仍可给出 DNS 时间戳。因此每个阶段要同时记录 observed、not_observed 或 unsupported：复用是“阶段未发生”，记成 0 ms 会把缓存连接伪装成瞬时建连。

版本边界：OkHttp 与 Cronet 都独立于 Android 平台版本发布，不能从平台 API level 推导事件语义。材料口径（2026-08）OkHttp release 为 5.4.0，`cronet-api:500.0.1` 虽被标为 release 但其 POM 说明这是指向 `org.chromium.net:cronet` 的废弃过渡空工件——事件必须记录网络库、库版本、provider、引擎版本和 `negotiated_protocol`。QUIC/HTTP/3 下 `connect_ms` 不能固定解释为 TCP 三次握手，`secure_handshake_ms` 也不一定是 TLS。字节字段同样要分清来源：`Metrics#getReceivedByteCount()` 是当前请求的传输层接收字节（不含之前的重定向），`UrlResponseInfo#getReceivedByteCount()` 从请求开始累计并包含重定向，两者不能混入同一指标序列。

**Q14: [learning] TrafficStats 的字节数为什么必须按相邻差值使用？registerDefaultNetworkCallback() 之后应该在哪一步读网络属性？**

`TrafficStats` 提供的是自开机以来所有网络接口上网络层流量的累计计数器（含 TCP 与 UDP），设备重启后归零。`getUidRxBytes()`/`getUidTxBytes()` 聚合同一 UID 下的所有进程，Android 7.0 起查询其他 UID 返回 `UNSUPPORTED`，设备不支持某项统计时也返回 `UNSUPPORTED`（数值为 -1，不是有效字节数），`getTotal*` 系列是整机口径。看板必须保存 `boot_id`（或等价的开机标记）、采集时间和前后差值。发现计数变小、启动标记变化或任一端为 `UNSUPPORTED` 时丢弃该区间差值。`tagSocket()` 只能给 socket 计账加标签，不产生 DNS、握手和首包时间，不能替代网络库的逐请求事件。

网络状态要按样本发生时记录：Android 8.0 及以上在 `onAvailable()` 之后才会回调 `onCapabilitiesChanged()` 和 `onLinkPropertiesChanged()`，在 `onAvailable()` 里同步查询新网络属性会遇到时序竞争。回调运行在 `ConnectivityManager` 专用线程，里面只应更新创建后不再修改的内存快照，耗时计算与上传放到工作线程，用完注销。语义边界：`NET_CAPABILITY_INTERNET` 表示被配置为可访问互联网、不代表已连通。`NET_CAPABILITY_VALIDATED` 表示系统探测到公共互联网，仍不保证业务域名可达。`CAPTIVE_PORTAL=true` 且非 `VALIDATED` 是门户登录的重要旁证。transport 是集合：VPN 可以同时带 VPN、Wi-Fi 与蜂窝承载，数据模型要保存集合而不是单值枚举。`onLost()` 只说明这张网络不再是默认网络，未必已断开。显式绑定其他 `Network` 的请求要用该请求选中的快照。`Network` 对象仅在连接存续期内有效，重连后是新对象，只能端侧短期关联。

NetworkCallback 生命周期的功耗取证：

泄漏只是嫌疑，归因需要四步证据链：heap dump 显示已销毁组件仍被 listener、线程或队列引用。trace 或日志显示该对象仍在收到回调。回调带来可量化的 CPU、Binder、网络或存储工作。修复后 retained object 数量、回调量与对应资源使用同时下降。修复模式是对称释放——注册与注销放进同一生命周期区间（`onStart` 注册对应 `onStop` 注销），并用 registered 标志防重复。协程、Rx 流、sensor listener、location listener 与 Handler 回调都要明确由谁注册、由谁取消。

RSS 下降不能直接证明续航提升：堆占用高本身不耗电，是“被保留的对象继续执行工作”才耗电。GC、内存回收、内存压缩的具体路径取决于内核与内存配置，应用侧不能把 RSS 变化换算成能耗。内存压力与功耗限制也是两类现象：低内存回收用 `ApplicationExitInfo` 与 lmkd 日志取证，OEM 冻结需要 vendor 日志或 freezer 状态支撑，不能把“线程没有 runnable slice”直接当成冻结。

**Q15: [learning] 功耗分析常把哪四类不同性质的数据混为一谈？Battery Historian 能回答其中哪个、不能回答哪个？**

四类数据各有不同的观察对象与推论边界：

1. **状态与事件时间线：**Batterystats 历史、system trace 与应用 Trace 回答某段时间发生了什么、哪些行为重叠；不能单独量化事件能量。
2. **本 UID 资源计数：**`SystemHealthManager.takeMyUidSnapshot()` 的 HealthStats 与应用埋点回答 CPU 时间、网络字节、WakeLock 与 Job 时长怎样变化；计数本身不是焦耳或 mAh。
3. **系统归因估算：**`BatteryUsageStats`、设置页与 bugreport 回答系统怎样把组件能耗估算到 UID；数值受设备功耗模型限制。AAOS13 走 `*PowerCalculator`，Android 17 才是 `PowerAttributor` / processor 管线。
4. **设备级能量：**ODPM 电源轨与外置功耗仪回答实验窗口内整机或子系统的能量差；共享轨不自动提供 UID 归因。

Battery Historian 只是把 bugreport 中的 Batterystats 等记录转成可交互时间线的离线工具（官方已标注不再积极维护），它属于第一类：能帮助回答“电量下降时屏幕、CPU、Job、网络、定位和 WakeLock 处于什么状态”，不能直接证明某个事件消耗了多少能量。能量与功率也要分开：能量是窗口内累计消耗，平均功率等于能量增量除以时间增量。电池百分比经过量化与系统估算，短窗口内没有下降不能推导为“零消耗”。

**Q16: [learning] API 35 的 PowerMonitor 读数为什么“单次累计值没有场景意义”？AAOS13 上能用吗？**

AAOS13 不可用：`SystemHealthManager` 的 `getSupportedPowerMonitors()` 与 `getPowerMonitorReadings()` 从 API 35 起公开（AAOS13 源码核对不存在这两个方法），Android 13 及更早版本没有这条公开路径。可用平台上也要按正确语义使用：`PowerMonitorReadings` 返回自开机以来的累计能量（单位微瓦秒，包含电池供电与插电阶段），重启后不连续——单次读数没有场景意义，正确用法是同一组 `PowerMonitor` 在场景前后各读一次，按各自 `timestamp`（来自 `SystemClock.elapsedRealtime()`）计算非负增量。跨重启、计数回退或 `ENERGY_UNAVAILABLE` 的样本应丢弃。

三条使用边界：支持列表可能为空，电源监视器的名称与含义依设备而变，名称相同也不代表不同 OEM 的计量范围等价。普通 App 读数可能被缓存并加入与调用 UID 相关的随机噪声，高频读取得不到仪器精度，也不应尝试反推去噪。电源轨是设备级数据，App 只能在受控实验中用 Trace 时间窗做相关分析，不能把电源轨增量直接记为当前 App 的能耗。硬件通道名称可能暴露机型与硬件组合，生产上报要先评估设备指纹风险，并对采样频率和保留期设限。

**Q17: [learning] 普通 App 在 Android 13/17 上还能从系统读到哪些功耗相关数据？哪些路径走不通？**

公开可用的有两层。本 UID 层：API 24 起 `SystemHealthManager.takeMyUidSnapshot()` 返回本 UID 的 `HealthStats`（用户态/内核态 CPU 时间、Wi-Fi 与移动网络字节、WakeLock、Job、Sync、传感器、前后台状态等键），它是资源代理指标而不是能量测量。读取前必须用对应的 `has…()` 检查键是否存在，共享 UID 会把多个包的统计记到同一身份，系统代持的 WakeLock 和库内部调度也会影响解释。电池状态层：`BatteryManager` 公开电量、充电状态、瞬时/平均电流、charge counter（微安时）和 energy counter（纳瓦时）等属性，描述整块电池或燃料计状态、不提供 App 归因。设备可以不支持某个属性——`getIntProperty()` 对 targetSdkVersion 为 Android 9 及以上的应用返回 `Integer.MIN_VALUE`（更旧目标返回 0），`getLongProperty()` 返回 `Long.MIN_VALUE`，API 35 新增的 `getStringProperty()` 返回 `null`。

走不通的路径：`BatteryStatsManager#getBatteryUsageStats()`、`BatteryUsageStatsQuery` 和按 UID 获取 `BatteryConsumer` 的路径都带 `@hide`，且受 `BATTERY_STATS` 权限保护（Android 17 清单声明为 `signature|privileged|development`，AAOS13 同样不是普通应用可得）。`SystemHealthManager` 也没有 `getBatteryUsageStats()`、`registerBatteryStateCallback()` 或 `getCurrentPowerStats()`。普通 App 不能复制系统设置或 system_server 的调用代码获取全设备 `BatteryUsageStats`——这类信息应通过 adb、bugreport、平台测试组件或 OEM 授权的系统应用获取。

**Q18: [learning] 验证一个“后台同步更省电”的改动，为什么“功率降低了”或“时间线更安静了”都不算结论？网络功耗的固定排名（Wi-Fi 低、5G 高）为什么不可用？**

功耗优化必须同时观察“完成了多少工作”和“付出了多少能量”：只降低功率可能让任务执行更久、总能量反而增加，只缩短耗时也可能抬高峰值功率和温度。每次实验至少保留五组数据：场景产出（完成的同步数、请求量）、性能（耗时、CPU 时间、字节）、能量（整机或所选 `PowerMonitor` 的增量）、环境（温度、亮度、网络、充电状态、版本）和正确性（失败率、数据时效）。A/B 在等价条件下交替运行基准与候选实现（同设备、同场景、相近初始状态，并保留不执行待测场景的空闲基线），结论只填测量得到的分布、样本条件和不确定性，不预写固定百分比或“续航增加几小时”。某次 Historian 时间线更安静，也需要多轮实验与能量增量支持。

网络功耗的固定排序不成立，因为信号质量、频段、基带状态机、扫描、连接建立、传输时长、数据量、硬件代际和共享芯片都会改变结果：5G 不统一解释为更耗电，弱信号或频繁扫描的 Wi-Fi 同样可能很贵。`TrafficStats` 或 HealthStats 的字节数只表示活动量，能量还取决于传输时间和无线状态，“每 MB 固定耗电”不能跨网络和设备复用。优化假设要逐项验证：合并可延期请求可减少 radio 活跃次数但增加等待与单次失败影响。连接复用减少握手成本而频繁主动断开可能增加 radio 状态切换。预取可能下载用户永远不用的数据。WorkManager 的网络约束只适合可延期任务，交互请求仍按用户时效执行。

**Q19: [learning] PowerStats HAL 的数据类型、设备能力与公开权限分别意味着什么？**

PowerStats HAL 可报告三类数据，但设备实现覆盖度决定它能否支持组件或 UID 能耗诊断；仅能列出对象并不代表有真实读数。

1. **状态驻留：**PowerEntity / StateResidencyResult 提供各状态的累计驻留时长、进入次数和最近进入时间。
2. **组件能量：**EnergyConsumer / EnergyConsumerResult 提供累计能量；设备可选提供 EnergyConsumerAttribution 做 UID 分摊，分摊总和不能超过组件能量。
3. **计量通道：**Channel / EnergyMeasurement 通常对应电源轨，能量使用 µW·s，时间戳使用 CLOCK_BOOTTIME。

PowerStats AIDL v2（按 Android 17 冻结接口核对）与 HIDL 1.0 的能力不同：framework 连接 AIDL v2 失败时可回退 HIDL 1.0，后者没有 EnergyConsumer。AOSP 默认实现可能只注册用于联调的虚构 rail / consumer；设备只实现少数 rail 时，`dumpsys powerstats` 仍会列出对象，但覆盖范围有限。未提供 attribution 的组件不能据此得到 UID 分摊。

Power Monitor 公共 API 从 API 35 起只公开 Channel / EnergyConsumer 累计值，不公开状态驻留与 UID attribution。没有 `ACCESS_FINE_POWER_MONITORS` 时，缓存最长 20 秒、granularity 为 UNSPECIFIED；有权限时为 250 ms、FINE。该权限为 signature / privileged / development 级。读数还按调用 UID 混入随机扰动，monitor index 在重启后不保证稳定。

比较设备能力可用 L0–L4 描述：L0 无可用数据；L1 只有计量通道；L2 有组件能量；L3 有 UID 分摊；L4 测量链经外部仪器验证。比较前保存对象清单、对累计值做差分、统一时间轴，并区分负差值与计数回绕。测量同一任务的能效优先用任务总能量，同时记录耗时与不确定性；功率 W、能量 J / Wh 和电池电流 mA 是不同物理量。

**Q20: [learning] getHistoricalProcessExitReasons() 的三个参数怎么用？历史容量有多大？**

签名的三个参数分别控制筛选范围：

1. **`packageName`：**传 null 匹配调用者 UID 下的包；查其他 UID 需要系统级 `DUMP` 权限。传包名时，通过 `BIND_EXTERNAL_SERVICE` 绑定的外部服务进程记录也可能纳入，因此主进程读到推送进程的 `REASON_LOW_MEMORY` 不能算主进程启动失败。
2. **`pid`：**非 0 时按进程过滤；PID 会被系统复用，不能单独做事件 ID。
3. **`maxNum`：**传 0 表示返回系统当前仍保留的全部匹配记录。

结果按时间从新到旧排列。

容量是有界环形缓冲：AOSP 默认资源 `config_app_exit_info_history_list_size` 为每包 16 条（按 AAOS13 源码核对），设备厂商可覆盖，不属于 SDK 合约。公开 API 只承诺“近期记录”，同一 PID 可以保留多次退出记录。没有历史可能由缓冲淘汰、系统尚未记录、数据被清除或厂商实现差异造成，不能据此断定“进程未异常退出”。查询经 Binder 批量返回，放在应用可交互后的后台 executor 执行，不进首帧关键路径。

**Q21: [learning] ApplicationExitInfo 的 reason 与 status 怎么解释？哪些 reason 容易误判？**

`reason` 是系统分类主字段，`status` 的含义取决于 `reason`：进程自行 `exit()` 时保存退出码，因操作系统信号结束（`REASON_SIGNALED`）时保存信号编号。易误判点：`REASON_USER_REQUESTED` 覆盖“设置中强行停止”与“从最近任务移除”等多种入口，且 Android 14 / API 34 之前包更新也用它（API 34 才加入 `REASON_PACKAGE_UPDATED` 与 `REASON_PACKAGE_STATE_CHANGE`，跨版本报表必须按 API 级别解释）。`REASON_USER_STOPPED` 指多用户设备上的系统用户或工作资料被停止，与 Force stop 无关。低内存终止要先查 `isLowMemoryKillReportSupported()`，不支持的设备上表现为 `REASON_SIGNALED` + SIGKILL，即使支持，`REASON_LOW_MEMORY` 也只说明系统当时有内存压力、不能证明应用泄漏。`REASON_FREEZER` 自 API 33 公开（AAOS13 已具备），指 freezer 相关退出，不能用来命名某个厂商的后台管理功能。`REASON_UNKNOWN` 与一般 `REASON_SIGNALED` 信息不足，不能自动标“被安全中心杀死”。

细分原因 subreason 在平台源码中属隐藏信息，公开 API 没有读取入口。`description` 只是人工阅读文本且格式不保证稳定，服务端不能靠解析字符串恢复内部细分。AAOS13 源码中的公开常量集到 `REASON_FREEZER` 为止，`REASON_MEMORY_LIMITER` 与 `REASON_ANOMALY` 只以 API 10000 预览形式出现在在线参考中，覆盖 API 37 的数据协议不应提前引用。`pss`/`rss`（单位 kB）是系统最近一次采样，进程在采样前死亡时可能为 0——0 要标“不可用”，不插值，也不等于死亡时刻的精确内存。

**Q22: [learning] ApplicationExitInfo 的 trace 附件有哪些读取边界？**

四个边界决定消费方式。其一，trace 在独立的系统级环形缓冲区里，可能被包括其他应用在内的新记录覆盖，`getTraceInputStream()` 返回 `null` 属于允许结果。其二，常见内容是 ANR 线程转储文本，而 API 31 起 `REASON_CRASH_NATIVE` 可返回 protobuf 编码的 tombstone 流——两者格式不同，必须结合 `reason` 判断类型，不能把所有非空流当文本或都标成 ANR。其三，进程从 ANR 恢复后因其他原因退出时，先前的 ANR trace 会附在后一条记录上，实际 `reason` 与附件类型分别保存。其四，读取是 I/O 操作，应在后台按字节上限复制到私有临时文件、校验成功后再发布为待上传附件。超配额、解析失败与缺失都作为明确状态上报。

系统还可能稍后为已有记录补上 ANR trace：本地按来源指纹 upsert（存在则更新、不存在则插入），而不是“看过时间戳就永久跳过”，否则会漏掉后来出现的附件。隐私优先于字段完整度：ANR trace、tombstone 可能包含线程名、文件路径与内存片段，上传前限制字节数、验证格式、删除不需要的数据，无法可靠脱敏的附件不上传。

**Q23: [learning] ProfilingManager 与 ProfilingTrigger 的能力从哪个版本开始？AAOS13 上能用吗？结果为什么可能没有？**

`ProfilingManager` 自 Android 15 / API 35 提供四类请求（system trace、Java heap dump、heap profile、stack sampling），AAOS13（API 33）上不存在，低版本继续用自有指标、Perfetto、bugreport 等实验室路径。请求受系统级与进程级限流，且可能因已有 profiling 进行中、磁盘不足、参数无效或执行失败而没有结果——“请求已发出”与“结果已生成”是两个状态。必须成对提供 executor 与 listener（或提前注册全局监听器），两处都没有时请求被丢弃且无回调。只有 `error_code == ERROR_NONE` 才读 `getResultFilePath()`，路径来自回调、不能拼接固定目录。结果脱敏且只含请求进程的信息，其他进程 CPU 活动合并显示在 `OtherProcesses`。App 在结果投递前退出时，系统会在下次启动并注册全局监听器后尝试补投。

触发器按版本递增：Android 16 / API 36 加 `APP_FULLY_DRAWN`、`ANR`（结果只投递给 `registerForAllProfilingResults()` 的全局监听器，且依赖系统当时正在后台 trace）。SDK extension 36.1 加 `requestRunningSystemTrace()` 与用户终止类触发器（要用 `SDK_INT_FULL` 对比 `BAKLAVA_1` 判断，`SDK_INT == 36` 区分不了 36.0 和 36.1）。Android 17 / API 37 加 `OOM`（要求自定义 UncaughtExceptionHandler 继续调用默认处理器，否则系统无法使用）、`COLD_START`（新启动 trace + stack sampling，未调 `reportFullyDrawn()` 时默认 5 秒停止、用 discard buffer 保住启动早期内容）、`ANOMALY` 与过量 CPU 终止等。Android 17 部分设备的 MemoryLimiter 终止表现为 `REASON_OTHER` 且 `description` 含精确字符串 `"MemoryLimiter:AnonSwap"`——只有这个文档保证的完整标记可机器判断，不能匹配宽泛的 `"MemoryLimiter"` 或把所有 `REASON_OTHER` 归入内存限制。所有触发式采集都是尽力而为，“触发已登记”“系统产生结果”“结果已上传”要分开统计。

**Q24: [learning] ApplicationStartInfo 给启动样本补了什么？它和 TTID/TTFD、业务埋点是什么关系？**

它是 Android 15 / API 35 的系统侧启动记录（AAOS13 上不存在），提供进程身份（PID 与 package/real/defining 三类 UID）、启动 reason、冷/温/热类型、启动状态与单调时钟时间戳。价值是为每条启动样本补系统归因：冷启动从创建进程开始，温启动复用部分已保存状态，热启动把仍在运行的应用带回前台——同样首帧耗时，桌面图标冷启动、最近任务恢复与广播拉起的优化方向完全不同。

它不能定位慢代码，也不能替代平台 TTID/TTFD 或业务埋点：TTID 是系统收到启动 Intent 到首帧显示，TTFD 到应用调用 `reportFullyDrawn()`。业务首页可用、路由与异步数据完成仍要自己记录。公开 start reason 覆盖 launcher、broadcast、push、job 等。API 36 加 `getStartComponent()` 区分 Activity/Service/Broadcast/Provider，API 35 只能按 reason 粗分并接受较低分类精度。`getIntent()` 已移除 extras 且不应直接上传，上报只走白名单映射的 route 与 entry scene。

**Q25: [learning] 读 ApplicationStartInfo 时为什么先看 startupState 再看 startType？时间戳相减要满足什么条件？**

历史查询可能包含尚未完成的启动记录：`STARTUP_STATE_STARTED` 只保证身份/原因字段与 `START_TIMESTAMP_LAUNCH`。`STARTUP_STATE_FIRST_FRAME_DRAWN` 才额外保证 `BIND_APPLICATION`、`APPLICATION_ONCREATE` 与 `FIRST_FRAME`。`getStartType()` 只在首帧完成状态有保证，未完成记录可能返回 `START_TYPE_UNSET`，线上协议用 nullable 或 `unavailable` 表示，不能把未设置值当成热启动。`addApplicationStartInfoCompletionListener()` 在当前启动首帧完成时投递一次后自动移除，不等待 `reportFullyDrawn()`。需要 `FULLY_DRAWN` 时间戳要在调用 `reportFullyDrawn()` 后重新查询或重新注册监听器。

时间戳是纳秒单调时钟（同一次开机周期内可比较），只在同一条记录、两个 key 均存在时相减：`FIRST_FRAME − LAUNCH` 在 launch 可信时可保存为系统首帧区间。`APPLICATION_ONCREATE − BIND_APPLICATION` 包含框架在两点之间执行的工作，不能当 `onCreate()` 自身耗时。不能与 `System.currentTimeMillis()` 直接相减，要同刻保存两种时钟快照再在同一 boot session 内近似换算。开发者自定义 key 限定在 21–30 区间、重复写覆盖旧值、`reportFullyDrawn()` 之后写入被丢弃。版本注意：Service 启动的 `START_TIMESTAMP_LAUNCH` 在 Android 15/16 可能不正确，不要给 Service 启动设耗时门禁。

**Q26: [learning] 线上冷启动 SLA 应该统计哪些启动？ApplicationStartInfo 与 ApplicationExitInfo 怎么关联？**

SLA 只纳入完成记录中的 `START_TYPE_COLD` 且组件为 Activity 的用户显式启动（reason 为 `LAUNCHER` 或 `LAUNCHER_RECENTS`）。warm/hot 分桶统计、不与 cold 聚合，后台 Service/Broadcast/Provider 拉起属于后台拉起健康度，不进首页冷启动指标。`START_ACTIVITY` 的外部跳转由业务入口场景细分。

与前一次退出的关联要保留两份原始记录、再生成带置信度的关联边：exit 的 timestamp 是 epoch 毫秒、start 的时间戳是单调纳秒，两次的 PID 通常不同，改时间、重启与包更新都会影响匹配。候选匹配用包 UID、进程名、先后顺序、boot session 与业务 session，时间窗口由线上数据校准、不是平台保证。`wasForceStopped()` 是本次启动记录上的直接信号，优先于从模糊 exit reason 推断，用于决定是否重新注册被清理的 alarm/job。`REASON_CRASH` 后短时间 cold start 指向崩溃循环，`REASON_PACKAGE_UPDATED` 对应新版本首启，用户请求终止不计为 crash 恢复。CI 的 Macrobenchmark `StartupMode.COLD/WARM/HOT` 与系统的 `START_TYPE_*` 是两套枚举，不要按整数值对应——CI 管可重复回归，线上分位数管用户分布，Android 17 的 `COLD_START` 触发式 trace 只做低频抽样诊断。
