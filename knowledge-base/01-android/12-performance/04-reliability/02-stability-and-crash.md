# Android 应用稳定性与崩溃排查

## 稳定性治理：度量、崩溃与 ANR

**Q1: [learning] 用户报障“闪退、卡死、白屏”时，为什么必须先区分故障事件与进程结局，怎样据此选择第一手证据？**

用户现象不指向唯一根因，排查要先问“进程发生了什么”：故障事件（一次崩溃、一次 ANR）与进程结局（是否退出、谁终止的）不是同一层概念。分流只负责选择证据，不直接判定根因。

按进程结局分流：

1. 应用进程退出，有未捕获 `Throwable`：Java Crash，主证据是 Java 堆栈、异常原因链与当前版本的混淆映射。
2. **应用进程退出，有致命信号**：Native Crash，主证据是 tombstone、原始符号与 Build ID。
3. **应用进程退出，应用没有临终回调**：LMKD 终止、系统终止或用户操作，主证据是 `ApplicationExitInfo`、Android vitals 与系统内存状态。
4. **进程仍在，系统给出超时报告**：ANR，主证据是 ANR trace、原因文本与 Perfetto。用户选择等待后进程可以继续运行，ANR 不等于退出。
5. **宿主进程仍在但网页白屏**：WebView renderer 退出，主证据是 `onRenderProcessGone()` 回调，宿主进程可以存活。

反例提醒：不能把所有 `SIGKILL` 都算作 LMK（信号退出还需结合 status 与上下文），也不能拿 Java Crash 的处理方式分析 ANR。`OutOfMemoryError` 描述进程内分配失败，与系统低内存终止是两条记录，不能互相替代。

**Q2: [learning] API 30 的 ApplicationExitInfo 能回答什么、不能回答什么？读取时有哪些边界？**

`ApplicationExitInfo` 是进程退出后留在系统里的历史记录，能补上异常处理器来不及写完的证据，解释 LMK、信号退出、用户强停等非 Crash 退出。它不是实时崩溃回调，只能在后续进程读取，记录与 trace 都可能缺失。

使用边界：

1. **历史性与容量**：通过 `ActivityManager.getHistoricalProcessExitReasons()` 查询近期记录。AAOS13 的 `AppExitInfoTracker` 以 30 分钟间隔把内存状态落盘，`config_app_exit_info_history_list_size` 默认每包 16 条——两者是系统实现细节而非公开契约，写盘前重启可能丢记录，应用仍要保留自己的启动标记与最小崩溃记录。
2. **trace 可能为空**：`getTraceInputStream()` 的数据在全局环形缓冲中，可能被其他应用的新记录覆盖而返回 `null`。Android 12（API 31）起 `REASON_CRASH_NATIVE` 可能返回 tombstone protobuf。进程 ANR 后恢复、后来因别的原因退出时，该记录仍可能附带早先的 ANR trace，原因码与 trace 内容要分别判断。
3. **采样值**：`getPss()` 是按比例分摊共享内存后的驻留量、`getRss()` 是全部驻留物理内存，都是系统最近一次采样，可能为 0，不是死亡瞬间快照。

版本边界：REASON 常量随版本变化，AAOS13（API 33）定义 15 个 `REASON_*`（0–14），Android 14 起新增 `REASON_PACKAGE_UPDATED` 与 `REASON_PACKAGE_STATE_CHANGE`，材料按 Android 17 核对为 17 个（0–16），持久化协议不要写死常量数值。重启后消费记录要按 `processName`、`timestamp`、`reason`、`status` 去重，避免每次启动重复上报同一条历史记录。

**Q3: [learning] 团队内部崩溃指标为什么必须写清分子、分母、时间窗与去重键？“UV 崩溃率”这类缩写为什么不够？**

“崩溃率”至少对应五种不同分母的分数，指标文档、上报协议和 SQL 必须写全分子、分母、时间窗、范围和去重键，否则同名指标不可比较、也不可复算。“UV/PV 崩溃率”没有定义统计实体，不能作为发布判定依据。

按分母区分的常见统计对象：

1. **活跃安装实例日**：当天至少发生一次故障的安装实例日 ÷ 当天活跃安装实例日，描述日活影响面。
2. **观察期去重实例**：观察期内至少发生一次 fatal 的去重实例 ÷ 有活动的去重实例，即 Crash-Free Users 类指标。
3. **会话**：发生 fatal 的去重会话 ÷ 有效会话，描述一次使用能否完成。
4. **启动尝试**：以 fatal 或异常退出结束的启动 ÷ 有效启动，描述用户能否进入可用界面。
5. **原始事件**：故障事件数 ÷ 会话数或运行时长，描述频率与诊断负荷。

指标落地还要满足两项数据约束：

1. **保留原始故障事件：**端侧事件至少带 `event_id`、`installation_id`、`process_start_id`、`session_id`、`launch_id`、`cluster_id` 等关联键。“同一安装实例五分钟只算一次”只能用于从原始事件派生的指标表，不能覆盖原始事件，否则重复崩溃和 crash loop 会被清洗隐藏。
2. **检查分母完整性：**监控 SDK 版本覆盖率、会话上报完整率、崩溃补传成功率与符号匹配率；采集缺失会让指标看似改善。接入发布规则时，错误预算单位与 SLI 保持一致：SLO 按会话统计，预算就按受影响会话计算，不能改成事件数或跨 30 天去重的用户数。

**Q4: [learning] Crashlytics 的 Crash-Free Users 与 Crash-Free Sessions 怎么定义？解读时有哪些陷阱？**

Crashlytics 官方《Understand crash-free metrics》把 user 定义为设备上的单个应用安装，把 session 定义为连续使用时段；两个指标只纳入 fatal 事件。Crash-Free Users 为观察期内未发生 fatal 的安装实例比例，Crash-Free Sessions 为未以 fatal 结束的会话比例。它们分别回答“用户观察期内是否遇到崩溃”与“一次会话能否完成”。

解读陷阱：

1. **实体定义**：一个人在两台设备安装应用计为两个 user。按业务账号去重的自建系统与 Crashlytics 的“用户”不是同一实体，不能互相比对。
2. **观察期**：观察期越长，同一实例遇到至少一次崩溃的机会越大，1 天与 28 天的数值不能直接比较。
3. **事件范围**：non-fatal（已捕获并主动上报的错误）与 ANR 过滤条件不进入 Crash-Free 图表，只看这张图会低估整体故障。
4. **会话边界**：Crashlytics 当前把冷启动视为新会话、应用进入后台至少 30 分钟后回前台也开始新会话。这是产品规则，自建指标可以采用别的边界，但要在名字中标明内部口径。
5. **公式不能改写**：Crash-Free Sessions 不能写成 `1 - 崩溃事件数 ÷ 会话数`——同一受影响会话内多条 fatal 记录只计一次。
6. **不能由 Play 阈值反推**：Play 全机型 user-perceived crash rate 阈值为 1.09%，但两边故障范围、用户定义与聚合方式均不同，`100% − 1.09%` 推不出 Crashlytics 的发布线。

**Q5: [learning] Google Play 的 user-perceived crash rate 与 user-perceived ANR rate 口径是什么？阈值应该怎么理解？**

Google Play Console Help 的《Technical quality requirements》将两者列为影响商店可见性的 core vitals。口径是当天至少遇到一次用户可感知崩溃或 ANR 的日活用户比例。该官方页面当前公布的阈值为：user-perceived crash rate 全设备平均 1.09%、单手机型号 8%、手表 4%；user-perceived ANR rate 分别为 0.47%、8%、5%。

口径要点：

1. **DAU 分母**：daily active user 按“单个设备上的单日活跃用户”计，同一人在两台设备使用贡献两个日活。同一日活可包含多个会话，官方 FAQ 示例：一天玩三次崩一次，vitals 显示 100%，按会话计的 SDK 显示 33%。
2. **active use**：对手机和平板指应用正在显示 Activity 或执行前台服务。后台组件崩溃仍进总体 crash rate，只是不一定进 user-perceived 分子。
3. **ANR 范围**：当前只有 input dispatching timed out 计入 user-perceived ANR，Service、Broadcast、Provider 等 ANR 要进内部分析但不能加进该分子。
4. **阈值性质**：达到或超过阈值 Play 可能降低商店可见性、可能展示警告，是商店质量边界而不是固定审核拒绝线，也不是团队 SLO 推荐值。Play 每天用最近 28 天平均评估。

Android vitals 与自建 SDK 数字不同是常态，无须强行对齐：vitals 来自系统（能覆盖 SDK 初始化前的崩溃）、只统计认证设备与 Play 安装、只使用同意共享数据的用户、受隐私最小样本与展示延迟影响。团队要明确每张看板回答的问题，而不是追求两个系统数值接近。

**Q6: [learning] 崩溃聚类为什么要同时生成 exact 与 family 两层指纹？哪些字段不应直接进入指纹？**

两层指纹解决两个相反的问题：exact fingerprint 用于同一构建产物内，保留 retrace 后的源位置或 Native 相对位置，区分同一方法里的多个 throw site。family fingerprint 用于跨构建召回候选，弱化行号与偏移、保留稳定的异常类型与帧序列，避免插入一行日志就把同一问题拆开。指纹规则要有 `schema_version`，规则变化时用新版本重算并保留旧 issue 到新 issue 的映射。

不应直接进指纹的字段及替代做法：

1. **整段 exception message**：文件路径、URL、时间戳、对象地址与服务端文案高熵且可能带隐私。优先使用稳定 error code，需要模板时按异常类型维护受监控的解析规则。
2. **原始 fault address**：ASLR 与堆布局使它每次不同。改用地址类别（near-null、tag mismatch、不可访问映射）结合 signal 与 `si_code`。
3. **全部线程栈**：无关线程的调度会让同一 Crash 每次得到不同 ID。只把 crash thread 放进强签名，其他线程作为相似度与人工分析证据。
4. **一律删除系统帧**：`Looper.loop()` 等公共尾帧低权重即可，`SQLiteConnection`、Binder proxy 等特定帧可能正是失败语义，应留在辅助特征。

Java 侧的稳定特征还包括外层异常类型、root cause 类型与 cause 链类型序列、每层第一个业务帧、suppressed 异常摘要。Native 侧是 signal、`si_code`、模块 Build ID、相对 PC 与归一化的 abort message。符号化是聚合前置条件：R8 mapping 绑定构建产物，Native 符号按 Build ID 匹配，未符号化事件进入带原因的后备分组单独展示。

**Q7: [learning] 故障归因为什么比较发生率而不是原始计数？first observed 与 introduced 为什么必须区分？**

归因回答的是“问题在哪些接触到该版本或功能的条件下更常发生”，所以任何归因表都需要分子和分母：比较同时间窗内的 rate ratio（该组发生率 ÷ 基线发生率）或 rate difference，并展示分母与置信区间。“某机型有 50 次 Crash”在它贡献大部分活跃量时没有任何信息。

first observed（首次观测到问题的构建）不等于 introduced（首次引入问题的构建），旧问题看似新出现的常见原因：

1. 旧版本没有采集，或 mapping/Native 符号缺失。
2. 分组算法刚升级，issue 边界变化。
3. 新版本让更多用户进入某项功能，暴露量变化。
4. 旧版本样本量太小。
5. 同一根因在新构建中换了失败表现。

判断 introduced 需要结合各版本实际使用量、旧构建故障率上界、代码差异与复现证据。`git bisect` 只适用于有稳定自动复现、明确 good/bad 边界且每个中间构建可运行的场景。`git blame` 只能说明某行最近被谁修改，工单应派给团队队列并附证据，不能凭相关性把故障认定到个人或某个团队。

**Q8: [learning] Android 应用未捕获异常到达线程顶层后经过怎样的默认处理链？自定义 UncaughtExceptionHandler 为什么必须委托？**

AAOS13 的处理链是：`Thread.dispatchUncaughtException()` 先调用平台 pre-handler，再调用线程显式处理器或其 `ThreadGroup`，最终交给默认处理器。`RuntimeInit.commonInit()` 在应用代码运行前安装两个处理器：`LoggingHandler` 经 `RuntimeHooks.setUncaughtExceptionPreHandler()` 注册为前置处理器，写入 `FATAL EXCEPTION` 日志，应用无法通过公开 `Thread` API 替换它。`KillApplicationHandler` 注册为默认处理器，向 ActivityManager 上报 Crash，并在 `finally` 中执行 `Process.killProcess(Process.myPid())` 与 `System.exit(10)`（AAOS13 `RuntimeInit.java` 核对）。

自定义处理器必须保存并调用安装前的处理器，否则会截断这条链：进程留在部分线程已终止、共享状态未定义的局面，`KillApplicationHandler` 的上报与终止也不会执行。材料按 Android 17 核对，OOM profiling 触发器等依赖默认链的系统能力同样失效。排查 SDK 冲突时还要检查线程级注册：`Thread.setUncaughtExceptionHandler()` 的优先级高于 `ThreadGroup` 与默认处理器，某个线程的处理器不再委托时也会截断进程级采集链。任意线程都可能触发致命路径——主线程、线程池与第三方 SDK 线程都要覆盖。

**Q9: [learning] 自定义致命异常处理器的最小正确结构是什么？致命路径上哪些工作不能做？**

最小正确结构是三件事：一次性进入保护、只写最小记录、`finally` 中无条件委托安装前的处理器。

```kotlin
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

fun interface CrashSpool {
    fun tryAppendMinimal(thread: Thread, error: Throwable)
}

class DelegatingFatalHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val crashSpool: CrashSpool,
) : Thread.UncaughtExceptionHandler {
    private val entered = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (entered.compareAndSet(false, true)) {
                crashSpool.tryAppendMinimal(thread, error)
            }
        } catch (_: Throwable) {
            // 采集失败不能截断平台退出链
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
```

以上是可编译的 Kotlin 结构示例，`CrashSpool` 的持久化实现留给接入方；它只展示处理器边界，不代表任意文件写入都能在致命路径安全完成。

`AtomicBoolean` 保证多线程接近同时崩溃时只有一个线程进入写入。`finally` 保证自有采集失败后仍委托旧处理器。致命路径上不能做：发送同步网络请求、生成完整堆转储、等待其他线程释放普通业务锁、初始化数据库或大型序列化框架——进程可能正处于锁异常、内存紧张或 Binder 不可用状态，上传只能尽力而为。正确分工是把工作移到正常运行期：维护固定容量的 breadcrumb 环形缓冲、版本与会话快照、已打开并可独占写入的应用私有暂存区。处理器内只写时间戳、进程与线程标识、异常链摘要和完整性校验字段，重启后再补传。两个边界：这套结构不承诺记录必达（磁盘满、OOM、进程被外部终止都可能打断）。普通第三方应用不应把系统 `DropBoxManager` 当自有崩溃暂存区，它是容量受限的系统诊断设施，条目可能被丢弃。

**Q10: [learning] Thread.getAllStackTraces() 为什么不是原子快照？跨线程取栈的真实成本是什么？**

AAOS13 的 `getAllStackTraces()` 先枚举活动线程数组，再逐线程调用 `getStackTrace()`，各条栈的采样时刻不同，不是同一指令时刻的原子快照。线程并发创建或退出时，枚举结果也不承诺覆盖每条活动线程。公开 API 文档同样把每条栈定义为可能在不同时间取得的快照。

跨线程取栈的路径有真实成本：AAOS13 的 `VMStack.getThreadStackTrace()` 对目标线程区分两种情况——目标就是调用线程时直接 `CreateInternalStackTrace()`。目标是其他线程时，调用线程先离开 Runnable 状态，经 `ThreadList::SuspendThreadByPeer()` 挂起目标线程，取完再恢复（AAOS13 `dalvik_system_VMStack.cc` 核对）。这条路径要求 ART 的线程协调与对象分配仍能工作，目标线程迟迟到不了 suspend point 时延迟增加，也不满足 async-signal-safe 要求。因此它适合正常运行期诊断、受控 watchdog 或测试工具，不能单独证明一组严格同时发生的死锁关系。附带两个边界：`Throwable` 默认构造会执行 `fillInStackTrace()`，ART 的 `kMaxSavedFrames = 256`（AAOS13 `thread.cc`）只是减少重复回溯的优化阈值，不是最大 Java 栈深。handler 里再调用 `thread.getStackTrace()` 得到的是更晚的采样，分析时要保留传入的原始 `Throwable` 作为主栈。

**Q11: [learning] Java 未捕获异常、系统 ANR、Native 致命信号、进程已退出四类现场，为什么不能共用一套采集代码？**

四类现场的运行时状态、权限和安全边界不同，统一塞进一个处理器会把可诊断的故障演变成死锁、二次崩溃或残缺报告。选路依据是现场边界，而不是某个函数平时能否调用。

各现场的能力与限制：

1. **Java 未捕获异常**：ART 通常还能执行 Java 代码，但可能正处于 OOM、栈溢出或锁异常。优先保存传入的 `Throwable` 与预存 breadcrumb，资源允许时才补少量目标线程，不要无限制遍历全部线程或同步上传。
2. **系统 ANR 与调试 SIGQUIT**：进程仍存在，由 ART 的 SignalCatcher 在普通线程上下文执行诊断。应用侧用系统 ANR trace 与事前采样的主线程栈互补，不应把自定义 `sigaction(SIGQUIT)` 当稳定公开接口。
3. **Native 致命信号**：Java 堆、线程栈与运行时锁都可能不一致。处理器内不能调 JNI/Java、不能遍历 ART 私有结构、不能用 `malloc/new`、STL 容器、普通 mutex 和数据库网络，把 Java 版“临时文件 + rename”搬进信号处理器是典型错误。
4. **进程已退出**：进程内 Java 状态已不存在，只能合并 `ApplicationExitInfo`、tombstone/ANR trace 与退出前写好的预存记录，并区分“系统证据”“端侧预判”“服务端推断”三种来源。

**Q12: [learning] ART 收到 SIGQUIT 后怎样生成全线程 dump？为什么普通应用拿不到等价的 monitor 持有关系？**

ART 让相关线程屏蔽 `SIGQUIT`，由专属的 SignalCatcher 线程通过 `sigwait()` 同步等待信号，在普通线程控制流里调用 `Runtime::DumpForSigQuit()`，经 `ThreadList::Dump()` 创建 DumpCheckpoint 让各线程在安全位置协作输出（AAOS13 `signal_catcher.cc` 与 `thread_list.cc` 核对）。这条系统路径可以附加 Java monitor 关系——`waiting to lock ... held by thread N`、`locked ...`、`waiting on ...`——但它不是原子快照，checkpoint、栈遍历与输出都可能耗时，故障中也可能缺帧。

普通应用拿不到等价能力的边界：公开的 `Thread.getStackTrace()` 只返回 `StackTraceElement[]`，不含阻塞对象与持锁对象。ART 的 `CreateAnnotatedStackTrace()` 能构造带 `blockedOn` 与 `heldLocks` 的结果，但它是经 `VMStack.getAnnotatedThreadStackTrace()` 暴露的隐藏 API，AAOS13 中只有 `system_server` 侧的 `WatchdogDiagnostics` 这类平台代码使用。Java SE 的 `ThreadMXBean.findDeadlockedThreads()` 不在 Android 公共 API 清单中。应用侧等价做法是在正常 Java 环境里对目标线程采样 `getStackTrace()`，用 `BLOCKED` 状态与连续样本做疑点标记，锁归属的确认留给系统 trace 或 Perfetto。

**Q13: [learning] Kotlin 协程异常在什么条件下会变成 Java Crash？**

取决于异常能否传播出结构化并发边界：根协程 `launch` 的未处理异常会被视为未捕获异常，最终交给当前线程的未捕获异常处理器进入 `RuntimeInit` 链。`async` 把异常保存在 `Deferred` 中由 `await()` 重抛。在业务边界被捕获并转换为失败状态的异常只是一条 non-fatal 记录，不进入 Crash 指标。

`CoroutineExceptionHandler` 的位置与限制：它在协程已经以异常完成后被调用，只能报告或执行善后，不能恢复协程。给普通子 `launch` 单独安装处理器通常不生效，因为结构化并发会把子任务失败先传播给父协程。只有无父 `Job` 的根 `launch`，或 `SupervisorJob` 下没有其他传播路径的 `launch`，才会落到处理器。材料核对的 kotlinx.coroutines 1.11.0 文档说明，JVM 兜底流程会调用经 `ServiceLoader` 发现的处理器以及当前线程的 `uncaughtExceptionHandler`，旧版 `kotlinx-coroutines-android` 的反射实现细节不能当作平台固定机制。

```kotlin
viewModelScope.launch {
    try {
        uiState.value = UiState.Content(repository.load())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        nonFatalReporter.record(error)
        uiState.value = UiState.Error(retryable = true)
    }
}
```

这个例子里 `IOException` 转成页面状态、不计 Crash。`CancellationException` 表示协作取消，捕获 `Throwable` 时必须重抛以保留取消语义，不能把取消当业务故障上报。

**Q14: [learning] 应用 SafeMode 怎样识别 crash loop 并决定降级？为什么启动标记缺失不能直接计为启动失败？**

SafeMode 是应用自己的熔断器：把本地最小崩溃记录与 `ApplicationExitInfo` 关联到“同一次未完成的启动”，确认同版本、同进程重复命中同一问题组后，下一次启动先关闭可疑模块。它与 Android 系统安全模式无关，也不能挽救已经 fatal 的当前进程。

关联必须是同一次启动尝试：`processName` 与本地启动标记一致、退出时间戳位于启动开始之后且在窗口内、本地版本与 `process_start_id` 与 `getProcessStateSummary()` 匹配、同一条退出记录只匹配一次。残留的启动标记只能说明上次没走到成功点——用户强停、LMK、设备重启、覆盖安装都会留下相同状态，所以标记缺失不计失败，只有“未完成标记 + 相关系统记录”或“未完成标记 + 本地 fatal 记录”才构成启动失败证据。退出原因也要分类：`REASON_CRASH`、`REASON_CRASH_NATIVE`、启动窗口内的 `REASON_ANR` 与 `REASON_INITIALIZATION_FAILURE` 参与 crash loop 判断。用户强停、包更新、权限变化、`REASON_FREEZER` 默认不计。`REASON_LOW_MEMORY` 单独统计，`SIGKILL` 不能自动解释为 LMK。

状态机至少区分正常、疑似、受限与试运行四态，避免一次偶发故障关闭大量功能，也避免兜底页能启动就被当作原问题已修复。动作按证据分级——关闭可疑模块、页面降级、只保留诊断与升级入口，且关闭要有依赖图（广告 SDK 关闭后它的 ContentProvider 初始化与后台任务也要停）。阈值可由远程配置调整，但崩溃循环中的设备往往拉不到网络，本地保守默认值必须独立可用。“清理数据”不能作为默认恢复动作，除非某缓存已被校验或复现证实损坏。

**Q15: [learning] AAOS13 中 Native Crash 从致命信号到 tombstone 经过哪些系统组件？为什么不经过 Java UncaughtExceptionHandler？**

AAOS13 的 Native Crash 路径按以下顺序经过系统组件：

1. bionic/debuggerd 在进程内安装 `debuggerd_signal_handler`；收到信号后保存 `siginfo_t` 与 `ucontext_t`，派生 `crash_dump32` 或 `crash_dump64`。
2. `crash_dump` 通过 `ptrace` 暂停并读取目标线程的寄存器、maps 与线程信息，再用 `libunwindstack` 生成线程 backtrace。
3. `crash_dump` 连接 `tombstoned` 取得输出 fd，生成文本与 protobuf tombstone，并经 `/data/system/ndebugsocket` 通知 ActivityManager。

以上链路按 AAOS13 的 `debuggerd_handler.cpp`、`crash_dump.cpp` 与 `tombstoned.cpp` 核对。它不经过 Java `UncaughtExceptionHandler`，因此 Java 致命异常处理器覆盖不了 Native Crash。

应用可见性的边界：普通应用不能直接遍历 `/data/tombstones`——AAOS13 的 tombstoned 默认保留 32 组产物（属性 `tombstoned.max_tombstone_count`），槽位会被轮转。可用入口是 bugreport、平台崩溃报告、API 30 起的 `getHistoricalProcessExitReasons()`，以及 Android 12（API 31）起对 `REASON_CRASH_NATIVE` 调用 `getTraceInputStream()` 读取 protobuf tombstone。protobuf 必须按 schema 解析并限制输入大小，返回值可能因环形缓冲轮转为 `null`。

**Q16: [learning] 为什么只凭 SIGSEGV、SIGABRT 的信号编号不能完成 Native 崩溃归因？**

信号只给出故障入口：CPU、内核、运行库和应用代码都可能触发同一信号，归因要同时读 `si_code`（内核细分原因）、fault address、abort message、寄存器与分配器报告。`SIGABRT` 尤其常见误判——它常常是检测器发现错误后的结果，根因在 abort message 与上游栈，而不是 abort 本身。

常见误判点：

1. **SIGSEGV**：非零大地址不能单独证明 use-after-free，也可能是越界、损坏指针或错误映射。fault address 为 0 或较小偏移常见于空基址加字段偏移，仍要用崩溃指令确认读了哪个寄存器。
2. **SIGABRT**：来源包括显式 `abort()`、未捕获 C++ 异常、`CHECK`/断言、fdsan、FORTIFY 或 allocator 主动终止。`std::terminate` 处理未捕获异常也与编译选项相关（CMake 默认启用异常，`ndk-build` 默认关闭，不能笼统写“NDK 默认禁用异常”）。
3. **SIGBUS**：优先检查 `mmap` 文件被截短的生命周期，不能把所有 ARM64 SIGBUS 归为未对齐访问。
4. **SIGFPE**：IEEE 浮点除零通常产生 Inf/NaN，不一定发出信号。
5. **栈顶在系统库**：`pc` 落在 `libart.so`/`libc.so` 不能推断系统缺陷，应用传入无效对象或破坏内存后，故障指令完全可能位于系统库。

`SEGV_MAPERR` 表示地址没有有效映射，`SEGV_ACCERR` 表示映射存在但权限不允许。MTE、GWP-ASan 与 Scudo 会在 Cause 或 abort message 中补充更强证据，优先采用。

**Q17: [learning] tombstone 里的 pc 是绝对地址吗？解读一份 tombstone 应按什么顺序？**

backtrace 中的 `pc` 列通常是模块相对地址（rel_pc）：系统回溯器先找到地址所属内存映射，再换算成 ELF 内的相对地址，非栈顶帧还做过返回地址修正。因此拿到 tombstone 后不要再减一次 ASLR 基址，`base.apk!libfoo.so (offset 0x2a4000)` 中的 offset 描述 ELF 在容器文件内的位置，也不能从 pc 再减一次。把进程绝对 pc 原样交给 `addr2line` 经常得到错误结果。

解读顺序：先核对 ABI、时间、进程与线程（决定用哪套构建产物，`pid == tid` 通常表示主线程，cmdline 与包名相似但不能一律称为包名）。再读 signal、`si_code`、`Cause` 与 abort message。寄存器部分要守住 ABI 边界——ARM64 的 `x0-x7` 是入口参数寄存器，但崩溃可能发生在函数中部、寄存器早已复用，`x0 == 0` 不能证明第一个源码参数为空，`x30` 在优化或叶函数中也不一定等于可靠调用者。最后用 backtrace 与 maps 做符号化，优先把整份 tombstone 交给 `ndk-stack`，或使用报告中明确标出的 relative pc。

**Q18: [learning] 栈回溯（unwind）与符号化（symbolication）为什么是两个独立环节？错误符号为什么也会“看起来对”？**

unwind 用崩溃现场（寄存器、栈内存、映射）按回溯规则恢复调用帧，symbolication 用匹配构建的符号文件把地址还原为函数与行号。两者依赖的证据不同，任何一环版本不匹配都会产出可读但错误的堆栈。AAOS13 的 `crash_dump` 使用 `libunwindstack`，它能读取 DWARF CFI、32 位 ARM 的 `.ARM.exidx`、frame pointer，并识别 ART 的 JIT 与 Dex 虚拟帧。

回溯信息来源的取舍：DWARF CFI（`.eh_frame`/`.debug_frame`）在优化构建下也能描述寄存器恢复规则，但元数据可能被裁剪。frame pointer 读取简单、适合高频采样，但任何一层省略 FP、尾调用或栈破坏都会截断（arm64 默认保留 FP、arm32 默认不保留）。启发式栈扫描只能提供候选地址，误报高。错误符号“看起来对”的原因是：错误 ELF 也可能在相同偏移附近存在函数并输出语法完整的函数名，所以服务端校验不能只看“是否输出函数名”，而要核对 Build ID 完全匹配、地址落在可执行 `PT_LOAD` 范围、函数范围覆盖输入地址、同一事件同模块帧使用同一 Build ID，不匹配时明确标记缺失或错误，不能回退到同名最新版符号。

**Q19: [learning] Native 符号产物应该怎样归档与验证？AGP 的 debugSymbolLevel 两档有什么区别？**

符号库以 ELF Build ID 为主键，因为版本号、ABI 和 `.so` 文件名不能唯一定位二进制。每次发布至少归档：

1. 最终 APK/AAB、版本、渠道与 ABI 清单。
2. 每个发布 `.so` 的 Build ID。
3. 保留符号的 ELF 或拆分出的 debug 文件。
4. 编译器与裁剪配置。
5. 第三方 Native SDK 版本及其符号获取方式。

归档后验证 stripped 与 unstripped 文件的 Build ID 完全一致，并用该构建制造已知崩溃确认符号化命中。

AGP 4.1+ 可为 AAB 生成 native debug symbols：`SYMBOL_TABLE` 只能恢复函数名，`FULL` 还提供文件和行号（官方文档口径）。产物随 AAB 交给 Play Console 并按团队保留周期归档。第三方依赖若在进入构建前已被剥离调试信息，AGP 无法重新生成——构建日志出现相关告警时应阻止发布或留下明确的风险豁免记录。使用 Breakpad 时，`dump_syms` 输出的 `MODULE` 行 identifier 直接作为符号目录键（`symbols/<模块名>/<identifier>/<模块名>.sym`），不要自行猜测或截断，模块同名但构建不同必须落在不同标识目录。

**Q20: [learning] 应用自研 Native 信号处理器有哪些安全边界？接入多个崩溃采集器为什么会互相破坏？**

信号处理上下文只能执行 async-signal-safe 操作：不调用 `malloc/new`、`free/delete`、STL 容器、iostream 与普通日志格式化。不进入 JNI、ART、数据库、网络栈。不使用可能已被当前线程持有的 mutex。只用预分配内存、固定大小结构、原子状态与已经打开的 fd。工程上还要处理重入、嵌套信号、多线程同时故障、备用信号栈（`sigaltstack()`）与部分写入，并保存、恢复或转发旧 action 与信号屏蔽集，最终把信号交还 debuggerd 以保留系统 tombstone。

多采集器互相破坏的机制是：内核对每个信号只维护一份 disposition，后一次 `sigaction()` 会替换前一次。AAOS13 的 ART SignalChain 包装了 `sigaction` 以保护平台特殊处理器先行处理（`sigchain.cc` 中每个信号一个 `SignalChain`、平台槽位数组加单个用户 action），应用通过 `dlopen("libc.so") + dlsym("sigaction")` 绕过包装层，会直接竞争内核 disposition，导致系统 tombstone 缺失或处理器不被调用。保存旧处理器也不会自动形成安全链——`SIG_DFL`、`SA_SIGINFO`、重复进入与重新投递都要保留原语义。稳妥策略是让一个组件负责致命 Native 信号、其他 SDK 关闭 Native 捕获只保留 Java 与上传能力。接入第二个采集器后缺堆栈时，先逐段确认现场生成、文件完整、符号匹配与上传，再检查 signal disposition，并用故障注入验证 tombstone 仍能生成。

**Q21: [learning] 广播 ANR 的时间窗口怎么算？goAsync() 会延长它吗？**

广播 ANR 从系统分发广播开始计时，到 receiver 完成（同步 receiver 以 `onReceive()` 返回为完成点，`goAsync()` 后以 `PendingResult.finish()` 为完成点）为止。计时窗口还包含进程冷启动与线程池排队消耗的时间。AAOS13 的广播基线 `TIMEOUT` 为 10 秒 × `HW_TIMEOUT_MULTIPLIER`（`BroadcastConstants` 核对），官方 ANR 文档口径为带 `FLAG_RECEIVER_FOREGROUND` 的前台广播 10 秒、普通广播 60 秒。材料按 Android 17 核对，Android 14 起进程因调度得不到足够 CPU 时窗口可放宽到约 10–20 秒与 60–120 秒，AAOS13 没有这一扩展。

`goAsync()` 不增加时间：它只是把完成点从 `onReceive()` 返回延后到 `PendingResult.finish()`，异步工作沿用原有截止时间。因此示例中内部预算必须短于对应广播窗口，处理器要能响应取消——进入不可取消的阻塞调用时，`withTimeout` 无法保证按时执行 `finish()`。超过窗口或需要进程重建后继续的任务，应在 `onReceive()` 中只入队持久任务（WorkManager/JobScheduler）。另一个排查边界：receiver 可经 `registerReceiver(..., scheduler)` 指定 `Handler` 线程执行，主线程栈显示空闲不能排除广播 ANR，要按 ANR 类型检查实际执行线程。

**Q22: [learning] ANR 治理中主线程预算怎么建立？“已经异步”为什么不能证明预算达标？**

主线程预算要按场景建立而不是全局常数：对冷启动、页面切换、广播、Service 和 provider 分别采样，记录主线程消息耗时、排队时长、同步 Binder 时间、锁等待与设备状态，比较 P50/P95/P99 并把告警线设在对应系统窗口之前、留出安全余量。固定 500 毫秒之类的全局阈值会把启动、绘制与后台回调混成一类，失去定位能力。

“已经异步”不够的原因是线程切换只改变执行位置：无界队列仍会积压、不可取消的 I/O 仍会占住 worker、错误切回主线程仍会阻塞界面。启动阶段的典型假象：AndroidX App Startup 的 `Initializer.create()` 仍在启动主线程同步执行，合并 provider 只整理依赖不改变耗时。StrictMode 的 `detectDiskReads()` 等只负责检测，是否抛出由 penalty 策略决定。`SharedPreferences.apply()` 虽然立即返回，未完成的写盘可能在组件收尾点被同步等待，批量合并写入比机械替换成 `commit()` 更可靠。命名上也要守住边界：超过内部告警线只能叫“主线程 stall”或“ANR 风险事件”，系统尚未判定时不能记为 ANR。

**Q23: [learning] 同步 Binder 调用为什么没有通用超时？调用方和服务端各应怎样控制等待？**

Binder 驱动没有给普通同步事务提供应用可配置的通用超时，调用线程要一直等到对端处理完成。对端 CPU 饥饿、Binder 线程池占满、锁等待或对端再调用其他进程都会把等待拉长。治理靠控制等待位置与协议设计，而不是寻找超时参数。

调用方做法：缓存变化不频繁的系统信息并明确失效条件，避免在绘制或输入路径重复查询。合并小事务减少往返，单次数据量仍受 Binder 事务大小限制。外部服务调用放受控后台线程并给业务结果设计超时、默认值或重试。自有 AIDL 设计异步回调，用 request ID 丢弃迟到结果。只有语义允许单向投递时才用 `oneway`——它省去调用方等待，但远端处理不过来时事务会在队列积压（背压），不是同步调用的通用修复。`Future.get(timeout)` 与协程 `withTimeout` 都只让调用方停止等待，执行阻塞事务的 worker 仍被占用，远端正在执行的 `onTransact()` 不会被终止。

服务端同样会制造调用方 ANR：`onTransact()` 同步切主线程再等待返回、Binder 线程持锁执行磁盘或网络、所有 Binder 线程被长事务占住、持锁回调客户端形成跨进程环路等待、大对象频繁序列化。自有服务应记录接口名、请求 ID、调用方、排队与执行时间，观测代码优先放在自有 proxy/stub 与调用点，而不是 Hook 隐藏的 `BinderProxy.transactNative()`。

**Q24: [learning] 应用侧 ANR Watchdog 怎样设计才能避免探针互相确认或误报？它的能力边界在哪里？**

Watchdog 用后台线程周期向主 Looper 投递探针，测量探针从投递到执行的延迟。生产实现要始终只保留一个待确认 token：本轮 `Runnable` 只确认自己的 token，token 未确认前不投递新探针——常见写法每个周期清零共享计数再投递任务，旧探针晚到时会把新周期误标为成功，连续阻塞时探针还会在主队列堆积。

阈值没有通用的 3 秒或 5 秒答案，应从产品基线推导：业务最常见的系统 ANR 类型及时限、设备等级与系统版本、主线程消息耗时的线上分布、同一 stall 的去重与上报间隔限制、调试构建与连接调试器时的排除规则。报告要带 token、投递时间、延迟、主线程栈与若干关键线程栈。`onStall` 回调内不做网络或大文件写入。

能力边界决定了它只能做预警而非判定：探针未按时执行只证明主 Looper 阻塞，不能宣布系统已产生 ANR。receiver 运行在自定义线程或 `goAsync()` worker 卡住时主 Looper 可能完全正常。无焦点窗口等系统侧路径也不一定表现为主线程持续阻塞。系统可能在 Watchdog 上报前、同时或之后判定 ANR，没有“必定提前通知”的保证。因此 Watchdog 事件应与系统 ANR 分开存储，再通过时间、进程与堆栈签名关联。向自身发送 SIGQUIT 取 thread dump 有开销，只适合内部调试或小流量验证。

**Q25: [learning] 疑似系统负载导致的 ANR 怎样识别与过滤，才不会变成“甩锅”？**

系统负载是解释与分组的证据维度，不是删除记录的理由：一条“疑似系统负载”判断需要 ANR 窗口内多信号一致——本进程与系统 CPU 时间、I/O wait 与读写量、内存 PSI 与回收活动、Binder 事务排队、设备温度与降频状态、同型号同时间窗其他进程是否一起变慢。任何单一阈值（“I/O wait 大于某百分比”“RAM 小于 2 GB”）都不足以确定原因。低端设备也要单独分组并保留在整体指标中，从分母排除只会让看板变好、不会改善用户体验。

容易误判的信号：`am_proc_died`/`am_kill` 短时间密集只支持“系统内存压力高”，不能证明本次 ANR 与应用代码无关。cached app freezer 解冻的时间差不能构成跳过状态恢复的理由，应用侧也没有可靠的“刚解冻”通用 API。`getRunningAppProcesses()` 是采样时刻的进程列表，不适合做上报有效性过滤。设备启动后系统服务繁忙，不等于应用冷启动与同步 IPC 没有优化空间。落地做法是给监控平台增加 `system_load_suspected` 字段与证据列，把这类事件降低排序或放入单独队列，原始事件、分母与系统确认状态全部保留。多轮版本对比后确认只出现在某设备系统构建且应用栈分散时，再把证据交给厂商或平台团队联合排查。

## 偶现崩溃与流程卡死：缺陷模式和复现方法

**Q26: [learning] 偶现崩溃为什么应先检查时序和状态，再怀疑稳定的功能逻辑？**

偶现意味着触发结果受事件顺序、生命周期或共享状态影响。稳定的纯输入逻辑通常更容易重复；时序缺陷则可能只在取消、重连、快速切换或进程恢复等边界条件下暴露。

先从崩溃栈与事件记录连到竞态条件，再判断功能逻辑：

1. **定位访问点：**从异常类型和栈顶找到失败操作及其访问的对象；不要把栈顶位置直接当作共享状态的唯一写入方。
2. **查找并发方：**追踪对象的读写入口，列出回调、工作线程、任务入队/执行和页面生命周期事件。
3. **对齐状态变化：**记录关键状态的旧值、新值、写入来源、对象代际和单调时间戳，判断旧事件是否覆盖新状态。
4. **比较失败与正常样本：**核对设备状态、并发任务、重试次数和生命周期路径，找出失败时才成立的事件交叠条件。
5. **验证条件：**将该交叠转成可重复的复现步骤或定向事件注入，再观察异常与状态迁移是否一致。

同一输入在不同顺序下产生不同结果时，优先画事件时序与状态迁移图；只有证据显示输入处理本身不稳定，再检查功能逻辑。

**Q27: [learning] 取消、超时和提前返回分支为什么容易留下偶现崩溃或流程卡死？**

主路径完成时不代表边界分支也完成了清理。取消、失败、超时、重复事件和提前返回如果绕过状态收敛动作，可能留下监听器、弹窗、任务或等待标记，使后续事件操作已失效对象或永远等待终态。

逐条检查每个出口是否完成与入口相匹配的动作：

1. **资源与订阅：**取消任务、注销监听、关闭临时 UI，并清除持有回调的引用。
2. **状态与结果：**将流程推进到明确的成功、失败、取消或可重试状态，不保留含义不明的“处理中”。
3. **重试与兜底：**只有确认进入终态后才撤销超时兜底；重试必须有次数或时间预算，并能被取消。
4. **重复事件：**清理动作和终态转换应具备幂等性，重复回调不会二次释放或重复推进状态。

评审时从所有 `return`、`cancel`、`reset` 和异常出口反向检查：流程是否到达终态，已注册资源是否释放，后续是否仍可能收到旧事件。

**Q28: [learning] 页面销毁后迟到回调怎样导致崩溃？**

异步工作可能晚于页面或视图生命周期完成；回调若继续读写已销毁的 View、binding 或 owner，就会触发空引用、非法状态访问或旧页面被重新显示。

Fragment 的 Fragment 生命周期与其 View 生命周期可能不同步：Fragment 实例仍在 back stack 时，旧 View 已经销毁。处理原则按对象生命周期选择：

1. **绑定到视图的工作：**把协程等工作绑定到 `viewLifecycleOwner.lifecycleScope`，并在 View lifecycle 销毁时取消任务和延迟消息；不要只绑定到 Fragment 的较长生命周期。
2. **无法取消的回调：**执行前验证生命周期仍有效，并确认 callback 仍属于当前对象实例。
3. **结果交付：**使用页面代际令牌或请求 ID 丢弃旧请求结果，避免前一页面覆盖新页面状态。
4. **视图引用：**在 `onDestroyView()` 释放 view binding 等仅属于该 View 的引用，避免 Fragment 保留已销毁 View。
5. **纯数据任务：**将结果交给生命周期感知的状态持有者，由当前订阅者决定是否渲染。

仅在回调入口检查一次不够：排队后的延迟执行仍有时间窗口，校验必须靠近实际访问视图的位置。重复注册、重复取消和销毁后的回调都应纳入测试。

**Q29: [learning] 长驻进程的回调订阅失效后，怎样避免静默丢更新？**

连接对象仍存在不代表远端订阅仍有效。服务重启、进程休眠、断连竞争或异步初始化过期结果，可能让应用保持“已连接”标记却不再收到回调。

建立可恢复的订阅状态机：

1. **区分状态：**至少区分未连接、连接中、已连接且已注册、重连中，不能用单个布尔值代表全部阶段。
2. **校验代际：**连接建立与异步初始化都携带代际标识；旧连接的迟到结果不得覆盖当前状态。
3. **重连补注册：**连接恢复后根据唯一订阅表重新注册，并处理重复注册和部分成功。
4. **检测静默：**对关键状态使用版本号、心跳或业务状态校验；无更新只能触发诊断或恢复，不能直接证明服务断开。
5. **保留证据：**记录连接代际、注册结果、回调到达时间和恢复动作，区分应用状态错误与服务端未推送。

看门狗只能提供检测与恢复，不能替代回调契约或证明底层根因。对重复恢复设置退避和上限，避免故障时形成重连风暴。

**Q30: [learning] 多个事件源同时更新 UI 时，怎样避免状态横跳和旧值回滚？**

同一 UI 状态应有明确的权威来源；多个回调、缓存查询和延迟任务若都能写状态，就可能相互覆盖。常见风险是回调已携带新值，代码却再读尚未更新的缓存决定分支；或较早发出的延迟结果晚于新事件抵达。

收敛方法：

1. 为每类状态指定权威事件源，回调参数优先于可能滞后的缓存值。
2. 将状态更新串行化，给异步请求附上递增版本或代际令牌。
3. 应用结果前比较版本；旧结果只记录诊断信息，不覆盖新状态。
4. 对多个事件源合成的 UI 状态，明确优先级和冲突处理规则，并将迁移写入状态表。
5. 对非法或历史残留状态定义自愈迁移；新事件到来时不能假设初始状态干净。

防抖不能用固定经验值替代测量。处理重复输入与延迟反馈时：

1. 记录输入事件时间戳与到达值，量化重复噪声间隔，并与用户可能进行的有效连续操作区分。
2. 根据设备与业务观测选择防抖窗口；窗口要覆盖已确认的噪声分布，同时不能吞掉产品需要保留的有效输入。不存在适用于所有设备和交互的统一毫秒数。
3. 确认权威事件源到达后，取消尚未执行的旧反馈任务，避免它随后把 UI 回滚到过时值。
4. 用同一日志和复现脚本重测，确认噪声被抑制、有效操作仍生效、权威回调不会被旧任务覆盖。

修复后按原始事件顺序复现，并额外注入乱序、重复和迟到事件，验证状态最终收敛且不会被旧值回滚。

**Q31: [learning] 应用长期停留在 Loading 或同步中时，应怎样找出缺失的终态？**

Loading 不是网络请求本身的状态；它应由明确的业务状态机控制。请求被防抖丢弃、合法空结果未处理、超时被提前取消、重试无限循环或错误状态位串写，都可能让 UI 永远停在处理中。

沿状态迁移顺序核查：

1. 请求是否真正发出，还是被防抖、去重或权限检查吞掉。
2. 成功、空结果、失败、取消和超时是否各自有终态转换。
3. 超时兜底是否只在终态到达后取消，重试是否有总预算和退避。
4. 每个状态位是否由唯一状态机维护，方法名与返回值语义是否一致。
5. 页面销毁、进程恢复或连接重建后，旧任务是否仍能写入当前页面状态。

为每个请求记录 request ID、开始时间、状态迁移、重试计数与最终结果。日志中只有“请求开始”而没有终态时，应先追踪丢失的迁移，而不是只增加 Loading 超时。

**Q32: [learning] 怎样为偶现问题选择压力复现手段？**

复现手段应放大缺陷依赖的维度：连接类问题增加服务重启和断连竞争；生命周期问题快速销毁重建页面；状态覆盖问题提高事件频率并注入乱序；启动问题循环进程退出、设备上下电或睡眠唤醒。

设计复现矩阵时按步骤执行：

1. 从失败条件提取变量，例如操作间隔、连接状态、页面代际、后台时长和设备电源状态。
2. 每轮只改变一个主要变量，并记录随机种子或事件脚本，保证失败可重复。
3. 提高压力时保留业务有效性；纯随机操作无法覆盖目标状态时，使用定向事件注入。
4. 记录崩溃栈、事件时间线和关键状态迁移，确保压力工具没有覆盖原始证据。
5. 从栈顶访问点追到被访问对象，再找出可能并发读写它的线程、回调或任务；把这些事件与失败时间窗对齐，形成待验证的竞态假设。
6. 修复后运行原复现脚本，并增加边界样本验证没有把正常路径一并屏蔽。

monkey、快速连点、反复重启和生命周期切换是压力手段，不是根因证据；必须将复现时序与实际失败状态关联起来。

**Q33: [learning] 跨应用、框架和设备服务的故障应怎样划分归因边界？**

表面症状不能确定责任层。崩溃、无响应、界面未更新或媒体无声，可能分别发生在应用状态管理、框架回调、服务协议、驱动或硬件资源路径。

按边界逐层取证：

1. **应用层：**确认请求、参数、状态迁移和结果处理是否符合契约。
2. **框架与服务层：**确认调用是否到达、回调是否按契约返回，以及服务是否报告失败或重启。
3. **设备与驱动层：**只有前两层证据指向资源、调度、HAL 或驱动时，再收集对应系统日志与状态。
4. **修复归属：**应用层补偿只能标注为规避或恢复措施；不能把它写成设备根因修复。

跨层结论应分别说明观察到的事实、推断与未验证路径。将应用仓库中的配套修改当成系统根因修复，会掩盖仍需设备侧验证的问题。
