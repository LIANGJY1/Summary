# ANR：超时契约、诊断与预警

> 学习资料（文章模式沉淀）。主线：按各类超时契约与系统检测分层理解 ANR，用 trace 快照与内核证据做联合诊断，覆盖跨边界根因、案例复盘、Notification 与 ContentProvider 专项链路，以及 Android 17 预警能力。源文档：android-internals-wiki 第 9 章《ANR》§9.1–§9.7；机制按本地 AAOS13 源码（Android 13）核对，与材料 Android 17 语境的差异已标注（TimeoutRecord、AnrTimer、ANR 预警、shortService 与分代 CMC 等为 Android 14–17 能力，AAOS13 的报告过期阈值与 Watchdog pre-dump 时机也不同）；user-perceived ANR 的 vitals 定义与 0.47%/8% 门槛已与官方文档核对。AMS 调度、广播队列与 ANR 计时总表见 [../architecture/14-系统服务调度核心.md](../01-architecture/14-系统服务调度核心.md)；lmkd/Freezer 的机制层见 [../memory/01-内存管理与压力治理.md](../05-memory/01-内存管理与压力治理.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: system_server 处理一条应用 ANR 经过哪几层？为什么报告处理要异步排队、可能只转储目标进程自身？**

检测器（InputDispatcher、广播/Service/Provider 模块）建立期限并发现超时，把目标交给编排层 AnrHelper：先去重（拒绝同一 PID 正在处理或已在队列的请求），再把记录排队给独立消费者线程；消费侧 ProcessErrorStateRecord.appNotResponding() 写日志与统计、生成 trace、提交 DropBox，最后按进程是否与用户界面相关处置。异步排队是因为完整报告要抓 system_server、常驻进程、native 进程和 CPU 状态，成本高，不能让检测调用方承担。

排队带来的边界：AAOS13 的 AnrHelper 把排队超过 1 分钟（EXPIRED_REPORT_TIME_MS）的转储请求视为过期，只转储无响应进程自身；材料按 Android 17 核对的阈值改为排队超过 10 秒或开机不足 10 分钟，且增加了先行抓取目标栈的 early dump——AAOS13 没有 early-dump 阶段，仅靠 dumpStackTraces 先转储 firstPids（目标进程在首位）保证目标栈相对更早。两次 ANR 间隔不足 2 分钟（CONSECUTIVE_ANR_TIME_MS）时安排 Binder heavy-hitter 自动采样补充调用热点。Android 17 起检测器还用 TimeoutRecord 对象统一承载超时类型与时刻，AAOS13 以 reason 字符串直接传递，语义等价。

处置分支：与当前用户界面无关且未开启"显示所有 ANR"的进程按后台 silent ANR 直接终止；system_server、SystemUI、有 top UI 或 overlay UI 的进程记录 NOT_RESPONDING 并投递无响应界面；关机、调试器附着、进程已死等状态会跳过或改变处置。因此没有弹窗不能证明没有发生 ANR。

**Q2: system_server 的 Watchdog 与应用 ANR 有什么区别？AAOS13 的 pre-watchdog 抓栈发生在什么时刻？**

两者都处理"长时间没有进展"，但保护对象和恢复方式不同：应用 ANR 保护应用组件、输入窗口与应用进程，由各子系统检测器判定；Watchdog 保护 system_server 的关键 Handler 与 Monitor，由 Watchdog 线程和 HandlerChecker 检查。AAOS13 的默认期限为 60 秒（Watchdog.java 的 DEFAULT_TIMEOUT，可被 Settings 覆盖）：检查未过半继续等待，超过一半进入 WAITED_HALF，写 pre_watchdog DropBox 全量抓栈但不杀；真正到期（OVERDUE）且无调试器、允许重启时调用 Process.killProcess(Process.myPid()) 终止 system_server，由系统重启恢复。

版本差异：材料按 Android 17 核对的 pre-watchdog 在期限的 1/4 处触发（PRE_WATCHDOG_TIMEOUT_RATIO 为 4，默认约 15 秒），AAOS13 是过半（约 30 秒）才首次抓栈。另外，应用主线程同步等待 system_server 时可能先记录应用 ANR，若 system_server 受监控线程也持续无进展，Watchdog 才独立触发——两份报告要按时间线一起分析，不能只看归因进程名。

**Q3: Input connection ANR 从哪个动作开始计时？报告里的"队首事件"能当作触发超时的那条事件吗？**

计时围绕输入连接的确认回执：事件写入应用输入通道后，待确认条目留在连接的 waitQueue 中，应用处理完并通过 finishInputEvent 回执后才移除；InputDispatcher 用 mAnrTracker 维护最近的到期时间，到期并确认仍未响应后生成 reason，AAOS13 InputDispatcher.cpp 的格式是 `<通道名> is not responding. Waited N ms for <事件描述>`。基础超时为 5 秒：IInputConstants 的 UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS 为 5000，乘 HwTimeoutMultiplier，且窗口可以提供自己的 dispatching timeout，所以应以报告中的实际等待值为准。

队首事件只是诊断线索：源码注释说明最老条目最适合排查，但未必是触发计时到期的那一条——等待期间窗口超时配置可能变化，较新事件可能更早到达自己的期限。观测入口有 dumpsys input 中连接的 `WaitQueue: length=` 与条目明细，以及 ATRACE 计数器 iq（入站队列）、每个连接的 oq/wq；队列长度只描述积压数量，单个条目可能是长任务也可能是刚进入等待，不能由长度直接判根因。

**Q4: "Application does not have a focused window" 指向什么问题？为什么不能按"点击事件处理超过 5 秒"来排查？**

这条 reason 来自 InputDispatcher 的另一条路径：系统已知道 focused application（应获得焦点的应用），却等不到可接收输入的 focused window，独立计时器到期后生成 `<应用> does not have a focused window`（AAOS13 源码原样）。它与"事件处理慢"的 connection 超时是两条路径，表示系统还没有合适窗口接收输入。

常见检查方向是启动链而非事件处理：Activity 启动是否卡在 bindApplication 或 Application.onCreate、首个窗口是否迟迟未加入 WMS、窗口切换期间焦点 token 是否对应错误、显示切换或多窗口状态是否异常。责任归属由 WMS 的 AnrController 按输入 token、窗口、Activity 与 PID 判定，被记录的进程不一定是用户当时看到的界面进程。Android 17 为这条路径增加了 deadline 前的 pre-ANR 预警，AAOS13 没有该机制。

**Q5: execute-service ANR 的"前台 20 秒"和前台服务（FGS）是什么关系？**

没有直接关系。AMS 向进程调度服务生命周期事务后，ActiveServices 的 scheduleServiceTimeoutLocked 按 ProcessServiceRecord.shouldExecServicesFg() 在两档预算中选择：AAOS13 源码为 SERVICE_TIMEOUT = 20 秒 × HW_TIMEOUT_MULTIPLIER、SERVICE_BACKGROUND_TIMEOUT 为其 10 倍（200 秒）。这里的"前台"表示本次服务执行采用较高优先级调度，不能从前台服务类型推导，也不能由"它是 foreground service"直接判定预算。

计时覆盖 onCreate()、onBind()、onStartCommand() 等生命周期事务的完成，可能包含服务进程冷启动消耗的时间；reason 形如 `executing service <组件名>, waited N ms`。计时责任归到进程，同一进程可并发执行多个 Service，要结合 executing service 列表、事务记录与主线程栈确认是哪次调用未完成。

**Q6: 前台服务相关的超时有哪几类结果？shortService 和 dataSync 超时算 ANR 吗？**

至少分三类，结果不同。其一，调用 startForegroundService() 后未按期调用 startForeground()：官方文档给应用侧的契约是"几秒内"（ANR 概览写作 5 秒），AAOS13 源码内部默认 30 秒超时（DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS）加 10 秒 ANR 延迟（DEFAULT_SERVICE_START_FOREGROUND_ANR_DELAY_MS）；serviceForegroundTimeout 先停服务再投递 SERVICE_FOREGROUND_TIMEOUT_ANR_MSG，另一条拆除路径经 serviceForegroundCrash 抛 ForegroundServiceDidNotStartInTimeException——日志可能呈现 ANR 也可能呈现该异常，都应回到晋升契约排查。内部 30 秒可被 DeviceConfig 改写，不能写进业务计时。

其二，Android 14 引入的 shortService 约 3 分钟到期，onTimeout() 后源码默认再给约 10 秒，仍未停止则走 ANR。其三，Android 15 起 targetSdk 35+ 的 dataSync/mediaProcessing 后台累计 6 小时到期未停止，结果是 ForegroundServiceDidNotStopInTimeException 崩溃，不调用 appNotResponding，不能计入 ANR 统计。版本边界：AAOS13（API 33）的 ServiceInfo 中没有 FOREGROUND_SERVICE_TYPE_SHORT_SERVICE，也没有限时 FGS 路径，遇到这类报告说明设备在 Android 14+。

**Q7: JobService 有哪几条互相独立的时间线？job 被系统停止本身算 ANR 吗？**

三条时间线要分开读。回调 ANR：onStartJob()/onStopJob() 都在主线程执行，超时未返回即触发；材料按 Android 17 核对 JobServiceContext 的默认回调预算为 8 秒（× HW_TIMEOUT_MULTIPLIER），且对 targetSdk 34+ 才显式上报 ANR（兼容变更 ANR_PRE_UDC_APIS_ON_SLOW_RESPONSES，与官方文档口径一致），服务绑定另有 18 秒预算但走绑定失败与重新调度，不是 ANR 阈值。job 运行超时：onStartJob 返回 true 后，JobScheduler 因配额或执行时限停止 job 并调用 onStopJob——停止本身不是 ANR，但 onStopJob 仍要在回调期限内返回。通知要求：user-initiated job 启动后需在约 10 秒内调用 setNotification()，超时 reason 为 `required notification not provided` 并触发 ANR。

本地 AAOS13 树未包含 jobscheduler 模块源码，以上数值按材料与官方文档转写。工程做法不变：onStartJob 中快速把工作交给有并发上限的执行器并返回 true，任务完成时调用 jobFinished()，收到 onStopJob 后快速取消或标记后台工作；在回调里等待 Future、join 或同步 Binder 会直接耗尽 8 秒窗口。

**Q8: EventLog 的 am_anr、ANR trace、DropBox 三类产物各回答什么？应用侧能回捞到多少？**

三类证据按职能配合。am_anr 是 EventLog tag 30008，字段顺序为 User、PID、进程名、应用 flags、reason（AAOS13 EventLogTags.logtags 核对），回答"何时、谁、因为什么"；events 缓冲区循环覆盖，线上要尽早保存完整 bugreport。ANR trace 存放在 /data/anr/ 目录（AAOS13 的 ANR_TRACE_DIR 常量），新版本文件名为 anr_ 前缀加时间戳（官方文档核对），回答"采样时线程在做什么"。DropBox 由 ProcessErrorStateRecord 调 addErrorToDropBox 提交，tag 与进程类别相关（如 data_app_anr、system_app_anr），汇总更完整的上下文。

关联时必须同时使用时间、PID、进程名和错误 ID：PID 会被复用，一个 bugreport 可能含多次 ANR。应用侧回捞：Android 11（API 30）起可通过 getHistoricalProcessExitReasons() 查询自身历史退出记录，筛选 REASON_ANR 后用 traceInputStream 读取系统保留的 trace；系统只保存目标进程第一段及其前置头部，内容范围小于完整 bugreport，且流允许返回 null（环形缓冲可能已被覆盖），采集逻辑要把 null 当正常分支。

**Q9: "系统发 SIGQUIT、ART 写 traces.txt"的说法现在还准确吗？**

只在兜底路径上成立。AAOS13 的主路径是：ProcessErrorStateRecord.appNotResponding() 组织 firstPids、nativePids 等候选，调用 ActivityManagerService.dumpStackTraces() 创建 trace 文件，再经 Debug.dumpJavaBacktraceToFileTimeout() 请求 tombstoned 写入（AAOS13 AMS 源码核对）；源码注释明确"dumpStackTraces 落 trace 文件，失败则对本进程发 SIGNAL_QUIT 兜底取栈"。traces.txt 是历史文件名，新版本用 anr_ 前缀命名。

解读 trace 文件本身还有两条边界：文件头之后不一定立即出现目标 PID 段；文件结束也不证明所有候选进程都成功完成 dump——转储超时、进程退出或整轮预算耗尽都会留下失败标记或截断结果，确认完整性的判据是目标进程段落成对出现且无错误标记。

**Q10: ANR trace 里出现的进程都是 Binder 对端吗？什么情况下采集范围会缩小？**

不是。firstPids 以 ANR 目标进程开头，还可包含其 parent、system_server、persistent 常驻进程和可能是当前输入法的进程；lastPids 收集其余 Java 进程并由 ProcessCpuTracker 按 CPU 活动挑选；nativePids 来自系统关注的 native 进程清单。某进程出现在文件中只说明它符合本轮采集候选规则，Binder 因果关系必须用事务 flow、双方调用栈或 transaction log 确认。

范围会缩小的场景：报告过期（AAOS13 为排队超 1 分钟，Android 17 为排队超 10 秒或开机 10 分钟内）只转储目标进程自身；后台 silent ANR、系统启动早期也会收缩采集。因此"文件里没有对端栈"推不出"系统没尝试采集对端"，更推不出对端无责任；把结论建立在缺失的段子上属于证据越界。

**Q11: 同一段线程栈里的 ART 状态、Linux 状态和 Java 栈怎么区分解读？**

三者粒度不同，交叉验证才有效。ART 线程状态（Blocked、Waiting、Native、Runnable 等）是 ART 在可安全暂停的 suspend point 观察到的类型：Native 表示线程在原生代码中，不区分是否消耗 CPU——主线程在 epoll 中等待消息时也是 Native；sCount 是当前 suspend 请求计数，不是历史被暂停次数。Linux 状态 R/S/D 是内核在采样点看到的 task state：D 对应不可中断等待，常见于 I/O、direct reclaim、驱动等待，但状态字母本身不含原因；普通 futex 等待通常应按 S 分析，不能仅凭 futex_wait 归因用户态锁；freezer 有独立的 TASK_FROZEN 状态，冻结结论要用 cgroup.events 的 frozen 与 freeze/unfreeze 事件确认。

Java/native 栈提供调用路径与等待机制（Object.wait、futex_wait、nativePollOnce 等）。单看任何一列都不能定因：栈停在业务方法只说明采样时仍在该路径，不说明它消耗了整个超时时间。

**Q12: 怎样从两段线程栈判断死锁？ART 的 tid 和 Perfetto 的 TID 有什么区别？**

沿锁对象地址建等待图：`waiting to lock <0x...> held by thread N` 是等待边，`locked <0x...>` 是持有边。两条线程以相反顺序获取同一对锁即构成有向环，静态采样足以判定死锁；不成环则属于长临界区或锁竞争。典型案例是 Service 执行期间主线程持 DataManager 锁等 DatabaseHelper，工作线程持 DatabaseHelper 等 DataManager。不成环的锁竞争，修复方向是缩短持锁范围、把 I/O 移出临界区、跨模块锁定义固定层级；消除死锁的常见写法是先在锁内复制快照、释放锁后再调用另一对象，同时确认快照的并发安全。

跨工具追线程时注意标识符：ART 的 tid 是 ART 内部线程编号，Linux 的 sysTid 才是内核线程 ID，Perfetto 的 TID 对应 sysTid——在 ANR trace 内按 tid 找到持锁者后，切到 Perfetto 要用 sysTid 关联，不能沿用同一数字。

**Q13: 主线程栈停在 nativePollOnce 能证明它空闲吗？为什么要用采集延迟校准？**

不能。栈只证明采样瞬间在等待下一条消息：超时前执行的长任务可能刚结束，主线程已回到空闲；持锁线程也可能在采样后释放。反过来，栈停在业务方法中也不能单独证明该方法消耗了整个超时时间。

校准方法是把证据放回时间轴：记录检测器判定超时的时刻、ANR 记录进入处理队列的时刻、目标进程采样完成的时刻等关键点，再在 Perfetto 中定位采样点并向前回看完整超时窗口。AAOS13 没有 early-dump 阶段，目标进程的栈来自 firstPids 的第一位、仍早于附加进程；材料按 Android 17 核对的 AnrHelper 会先行提交目标进程临时转储，使其更接近现场。栈与时间线互相印证时提高置信度；冲突且采集窗口不完整时，结论降级为"疑似"并列出缺失证据。没有"采样延迟低于 N 毫秒即可信"的通用阈值。

**Q14: ANR 报告里的 CPU 百分比、Load 和 PSI 分别能说明什么、不能说明什么？**

CPU 百分比来自 ProcessCpuTracker 对某采样区间的统计：进程的 user/kernel 占比在多核上超过 100% 是合法结果（并行使用一个以上核）；TOTAL 行的分母是所有 CPU 的 /proc/stat 增量，非 idle 部分含 iowait，不能当作纯执行利用率，也不能与进程行直接相减。fault 计数是两个采样点间的缺页事件数，不等于内存分配量或 I/O 字节数。

Load 的三个值是 1/5/15 分钟衰减平均，统计可运行与不可中断等待的任务数：8 核设备 Load 为 8 既可能是八个任务持续用 CPU，也可能包含等待 I/O 的 D 状态任务；持续很短的 ANR 还会被长窗口稀释，所以 Load 不能单独证明 CPU 满载。PSI 的 some/full 与 avg10/avg60/avg300、total 描述资源停顿：判断当前事件要用 ANR 窗口内 total 的增量而非开机以来累计值；cpu.pressure 的 full 恒为 0。这些是环境证据，要与 trace、Perfetto 的调度和 I/O 轨迹在同一时间窗对齐后才能指向原因。

**Q15: SharedPreferences.apply() 已经返回，哪些组件边界还会替它等待写盘？**

apply() 先更新内存再异步落盘，并注册一个等待写盘完成的 finisher；组件收尾点会调用 QueuedWork.waitToFinish()，在调用线程执行尚未处理的任务并等待、运行所有 finisher——原本排给单线程执行器的写盘可能在收尾点变成主线程同步操作。AAOS13 ActivityThread 的调用点：handlePauseActivity 仅在 pre-Honeycomb 兼容路径调用（isPreHoneycomb 分支），handleStopActivity 在现代路径的 stop 回调后调用，handleServiceArgs 与 handleStopService 在向 AMS 回执前调用；manifest receiver 的 PendingResult.finish() 发现有待处理任务时，把 sendFinished() 排到 QueuedWork 队尾，广播 ANR 计时继续等待完成回执。

因此主线程可能已回到 nativePollOnce，Broadcast ANR 仍因慢写盘发生。归因时区分两种等待：waitToFinish 指向全进程共享的待完成任务（常见来源是 apply()，也可能来自其他框架代码），awaitLoadedLocked 指向该 SharedPreferences 实例的初次加载未完成。修复按序：合并同时段编辑、控制 XML 体积、把持久化移出收尾点；把 apply() 换成 commit() 会更早同步阻塞调用线程，通常加重风险。

**Q16: "A 调 B、B 回调 A"为什么不算死锁？看到 15 条 Binder 线程能断定线程池耗尽吗？**

Binder 支持嵌套同步事务和一定重入，回调可由线程池中其他线程处理，只有资源等待关系形成循环才发生死锁。典型循环：A 的线程持锁 L 发起同步事务到 B；B 处理时回调 A；A 的回调需要锁 L 或必须同步切到正等待 B 的主线程——A 等 B、B 等 A 的回调、回调等 L。另一种形态来自线程槽位：两端有限的 Binder 线程互相被嵌套事务占满，没有 Java 锁环也会互相等待。

线程数方面，AAOS13 libbinder ProcessState.cpp 的 DEFAULT_MAX_BINDER_THREADS 为 15，含义是驱动可按需请求的默认上限；进程可能显式加入线程池、修改上限或采用系统进程配置，15 条 Binder 栈不能证明池已耗尽，"再留一个线程"也解不开锁环。oneway 只省去调用方等待 reply，事务仍排队并占用内核 buffer，不能当无限容量通道。诊断要画出事务方向、线程状态、锁持有关系与线程池容量。

**Q17: 怀疑进程被 Cached Apps Freezer 冻结导致输入 ANR，证据链怎么建？**

需要三类证据在同一时间窗对齐：freeze/unfreeze 事件（am_freeze/am_unfreeze，字段为 PID 与进程名）与目标 PID 的冻结区间；该输入连接的事件派发与超时时间线（InputDispatcher reason 中的通道名与等待时长）；必要时配合 cgroup.events 的 frozen 状态。典型案例中，输入事件派发 13 毫秒后持有该连接的进程被冻结，约 5 秒后未完成事件触发超时；关闭 freezer 后复现消失、重新开启又复现（A/B 验证），时间、对象 PID 与开关实验互相吻合后才能写成已确认。

两个反例提醒：D 状态或栈中的 __refrigerator 都不足以单独证明冻结；该案例是个别 OEM 分支在 Gesture Monitor 仍有未完成输入事件时冻结进程的生命周期协同缺陷，不能外推为所有 Android 版本的通病。系统侧修复方向是：持有活跃输入 monitor 或未完成 WaitQueue 事件的进程不进入可冻结状态，monitor 销毁时先停止派发再允许降级。Freezer 本体的资格判断与冻结顺序见 [../memory/01-内存管理与压力治理.md](../05-memory/01-内存管理与压力治理.md)。

**Q18: ANR 根因结论的证据分级怎么定？**

三级判定，报告措辞跟随等级。已确认：日志、trace、源码语义加复现或 A/B 验证能构成完整因果链——A/B 指只改变一个条件，对比问题随之出现或消失；措辞"根因已确认"。强推断：多项证据指向同一方向，仍缺一段直接证据；措辞"高概率相关因素"。未闭合：只有相关日志、异常负载或静态堆栈，时间或对象无法对齐，因果链有缺口；保留线索并写明下一步取证项，不猜测补全。

修复验收同样受此约束：能指导修复的根因要能回答"移除某段工作、缩短某个持锁区后，为什么 deadline 就能满足"，修复后在相同场景比较期限内的主线程状态与 P95/P99 耗时，避免只让采样时的栈顶换了位置。

**Q19: 慢消息日志与 ANR 时间差了 52 秒，为什么不能据此归因？**

因为证据已离开本次超时的观察窗口。案例：InputDispatcher 报 `[Gesture Monitor] swipe-up (server)` 连接等 MotionEvent 5001 ms 超时，而材料里的 system_server 慢消息（Slow dispatch took 10578ms）与 Launcher trace 头时间都发生在 ANR 前约 52 秒，无法回答超时前 5 秒内谁在做什么。两个附带的误读也要排除：通道名中的 `(server)`/`(client)` 后缀只区分通道端点（Android 14 的 openInputChannelPair 自动追加、Android 17 保留调用者传入名），不能证明消费者在 system_server 或锁定责任进程；ANR 报告中 system_server 的 215% CPU 表示多线程合计用量，不等于主线程阻塞。

这个案例的结论只能写"未闭合"。下一轮取证：从 dumpsys input 找到该通道对应的 connection 与 PID；抓取超时 5 秒窗口内双方线程 trace；检查 WaitQueue 最老条目的序号与派发时间；把高 CPU 细分到具体线程。原则是时钟和对象身份优先于关键词相似度。

**Q20: am_process_start_timeout 能证明 Application.onCreate() 太慢吗？**

不能。这条事件表示新进程没有按时 attach 到 system_server：ProcessList 启动进程后安排启动超时消息，应用进程经 Binder attach 进入 attachApplicationLocked 并准备发送 bindApplication 时才移除；到期则按 REASON_INITIALIZATION_FAILURE、描述 start timeout 终止进程。它发生在 handleBindApplication 与应用 Java 初始化之前，排查方向是 Zygote fork 返回结果、调度延迟、runtime 启动、seccomp/SELinux 拒绝、崩溃信号和 Binder attach 事务——优化 Application.onCreate 不是这条证据链的起点。

附带后果常被忽略：焦点已交给目标应用而进程死亡时，无焦点窗口状态可能延续，前一个界面（如 Launcher）会在十几秒后收到自己的 no-focused-window ANR。系统侧修复要求启动事务取消时撤销对应的焦点状态并让原窗口重新参与焦点计算。

**Q21: NotificationManager.notify() 是异步的吗？发布线程在等 system_server 做什么？**

不是异步。notify() 最终调用 INotificationManager.enqueueNotificationWithTag()，该 AIDL 方法未声明 oneway，是同步 Binder 调用（AAOS13 接口核对）；INotificationListener.aidl 则声明为 oneway，所以 listener 回调与 SystemUI 上屏都不属于本次调用的返回条件。发布线程等待的是 NMS 的同步段：校验调用 UID、包名、渠道与前台服务策略，修正通知，构造 StatusBarNotification 与 NotificationRecord，检查数量与更新速率，为 PendingIntent 设置临时 allowlist，直到把入队 Runnable 投递到内部 handler 才返回。

因此主线程构造大通知（读文件、缩放图片、大量 action）或等待 NMS 同步段都可能拖过输入期限，形成发布应用的 Input ANR。排查时用应用自定义 trace section 分开"构造"与"发布"两段，再结合 Binder transaction 看 system_server 侧耗时；notify 很快返回但显示慢，则转查 NMS handler 与 SystemUI 消费链。

**Q22: 通知更新被限流丢弃时应用能收到失败回调吗？限流的判定单位是什么？**

收不到。更新速率限制按应用包统计，不按 channel 单独计数：AAOS13 的默认值是 DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 5f，可被 Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE 覆盖，不能当作固定系统契约。超限时 NMS 记录 Shedding 并在内部返回 false，而外部 AIDL 方法没有返回值，应用通常只看到某次进度没有展示；限流本身不触发 ANR，用于保护 NMS 与消费者负载。

命中限流的是"同一进度状态内的反复更新"：首次发布没有 previous 记录必然放行，NONE 到 ONGOING、ONGOING 到 COMPLETE 的状态转换因新旧状态不同也放行；同一状态内高频 update 受限。要可靠发布最终状态，应降低更新频率、按用户可见变化设计节奏，并在状态转换时显式发布终态通知，不能依赖异常重试。

**Q23: NotificationListenerService 的回调跑在哪个线程？在回调里查数据库会怎样？**

主线程。NotificationListenerService 用 mainLooper 创建内部 Handler，onNotificationPosted 标注 @MainThread，Binder stub 收到通知后先更新内部 ranking 再投递消息。在回调里做数据库、网络或分析，会延迟本次回调结束并推迟后续主线程消息，造成通知处理积压；只有当输入事件也被派发到该进程、等待超过 InputDispatcher 预算时，才会形成该进程自己的 Input ANR——回调本身没有独立的"通知 ANR timer"。

正确做法是回调只做快照与转交：复制业务需要的 key、包名、时间与文本摘要，交给有并发上限的专用执行器；队列满时按 notification key 合并同一通知的多次 update 并保留移除事件。RankingMap 每次携带的是该 listener 当前可见通知的排序快照，NMS 按 listener 过滤构造，成本随可见通知数与 listener 数增长；只关心当前通知时取出所需字段即可，不要缓存整代 map。

**Q24: ContentProvider 的 10 秒、20 秒、3 秒、23 秒分别计什么？哪一条会真正产生 ANR？**

AAOS13 的 ContentResolver 定义了四个固定预算（都乘 HW_TIMEOUT_MULTIPLIER），到期后果不同。publish 10 秒：已 attach 进程发布清单 Provider 的保护，到期由 processContentProviderPublishTimedOutLocked 以 REASON_INITIALIZATION_FAILURE、描述 "timeout publishing content providers" 移除宿主进程——这是初始化失败，不进 AnrHelper、不产生 ANR 记录。ready 20 秒：调用方等待正在启动的 Provider 发布，到期获取失败（日志 Failed to find provider info）。connected callback 3 秒：getTypeAsync 等短异步回调，到期结束等待并返回空结果或错误。remote callback 23 秒：经 system_server 异步获取 MIME type 等结果（ready 加 3 秒），同样是结果降级。

唯一进入 ANR 处理的是 call detector：特权调用方配置 ContentProviderClient.setDetectNotResponding() 后，远程调用超时会经 appNotRespondingViaProvider() 由 ContentProviderHelper 以 reason "ContentProvider not responding" 调 AnrHelper 处理 Provider 宿主（AAOS13 源码核对；Android 17 起该 reason 由 TimeoutRecord.forContentProvider 承载）。普通应用在主线程同步 query 慢而触发的 ANR，类型由外层契约决定（常是 Input），不能标成 Provider ANR。

**Q25: ContentProviderClient.setDetectNotResponding() 配置后，检测是如何运行的？为什么普通应用不能把它当通用超时？**

它是 @SystemApi、@hide 且要求 REMOVE_TASKS 权限的接口，普通 SDK 应用无权调用。运行机制：启用后远程方法经 execute() 包装——beforeRemote 把检测 runnable 延迟投递到调用方进程的 main looper，当前线程执行 Binder 调用，调用及时返回则 afterRemote() 移除 runnable；到期执行时调用 ContentResolver.appNotRespondingViaProvider() 通知 AMS，ContentProviderHelper 校验权限、从 connection 取出 Provider 宿主进程并交给 AnrHelper。被归责的是 Provider 宿主，调用方继续卡在原调用中；后续是杀进程还是弹窗由宿主的前后台状态决定。

注意检测的执行者是调用方 main looper：若调用方主线程自己阻塞执行远程调用，runnable 无法按期运行，更可能先触发调用方的输入或组件 ANR，所以系统组件使用时要把被监控调用放在工作线程。Android 17 另有受功能开关保护的取消感知变体 setDetectNotRespondingOnCancel（从 CancellationSignal.cancel() 起计时）；AAOS13 只有固定超时版本。普通应用没有系统级 Provider 调用超时，应把远程调用移出 UI 完成期限并用 CancellationSignal 控制自身等待。

**Q26: Android 17 的 ANR 预警回调提供什么信息？有哪些边界？AAOS13 能用吗？**

Android 17（API 37）加入 ActivityManager.registerAnrWarningListener()：应用注册 Executor 与 Consumer，在部分 ANR 检测路径接近 deadline 时收到 AnrWarningResult。载荷只有五项：anrId、anrType、consumedMillis、timeoutMillis、description（格式不稳定，只能原文保留，不能当协议解析）；没有 PID、线程栈、锁状态或组件对象。

四个边界：warning 表示计时进入预警点、系统尚未宣告 ANR，阻塞在 deadline 前解除则计时取消；投递是 best-effort，系统可能来不及调用，executor 排队期间也可能到达 deadline；回调不暂停、不延长原计时器，适合记录轻量状态，不适合全线程 dump、同步写盘或网络上传；官方要求 executor 不用主线程——主线程可能正是被监视的阻塞线程。版本门槛是运行平台 API 37（公开文档未要求 targetSdk 37），且受功能开关影响；AAOS13 源码中没有该 API 与 AnrWarningController，预警是 Android 17 新增能力，Android 14–16 也不能倒推获得。

**Q27: Android 17 的预警覆盖为什么小于 AnrTypes 的 11 个枚举？no-focused-window 的预警时刻怎么算？**

类型常量覆盖 ANR 分类，不表示每种类型都已接入产生预警的 producer。已核对的 producer：no-focused-window 输入路径；广播投递计时、Service 执行、short FGS、start-foreground 计时（走 AnrTimer 的 50% split point——AAOS13 没有 AnrTimer 类，广播与 Service 用 Handler 消息计时）。而普通 window-unresponsive（输入连接超时）在 InputDispatcher 侧没有 pre-ANR producer，ContentProvider、JobService、应用启动等也不能因存在枚举就推断有预警。统计时应保留"有 ANR 无预警"与"预警后恢复"两类记录。

no-focused-window 的预警时刻：pre_window = max(实际 timeout / 2, 2000 ms × HwTimeoutMultiplier)，warning_at = deadline − pre_window。默认 5 秒超时恰在一半（2.5 秒）发出；timeout 较短时预警早于 50% 进度；计算出的 warning_at 已过去则立即排队；同一等待状态只通知一次。预警经 InputDispatcher policy 回调、JNI、InputManagerCallback、WMS AnrController 到 AMS 投递给同 UID 已注册进程；正式 ANR 归因仍可能按焦点状态改判给另一进程，跨 UID 时普通应用无法关联。

**Q28: warning 的 anrId 与最终 ANR 如何关联？多进程应用为什么会收到重复通知？**

Android 17 为 ApplicationExitInfo 增加了 getAnrInfo()：退出原因为 REASON_ANR 且系统保留结构化信息时，提供 anrId、anrType、timeoutMillis 与 isUserPerceptible；warning 的 anrId 可与之匹配。anrId 只保证在每个 anrType 内唯一，持久化键至少用 (type, id) 并附加 boot/session，避免跨重启混数据。匹配存在三个正常分支：warning 后恢复则没有对应 exit；有 exit 无 warning 可能是该类型未接入 producer、开关关闭、投递失败或当时未注册；线上统计应同时保留 recovered、matched 与 ANR-without-warning 三类。isUserPerceptible 是系统记录的用户可感知标志，不等于一定展示了固定样式的对话框。

多进程重复的根源：AMS 的回调表按 UID 分组，同一 UID 的多个已注册进程都会收到同一 warning，而载荷没有目标 PID。应对：记录中加入 Application.getProcessName()，按 (type, id, boot/session) 去重后上传，预先决定由哪个进程持久化，并且不从"收到回调的进程"推断"发生阻塞的进程"。
