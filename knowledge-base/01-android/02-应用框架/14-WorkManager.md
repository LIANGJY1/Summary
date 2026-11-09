# WorkManager

> WorkManager 机制学习资料。边界：本文回答“保证执行的内部支撑、约束与重试的检测位置、expedited 与前台服务、Doze 下的执行时机”；Doze/App Standby 对应用的约束行为与“什么任务该交给 WorkManager”的选型归 [../08-network/04-应用网络约束.md](../08-network/04-应用网络约束.md)。依据以 Android Developers 官方后台任务文档为准。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] WorkManager 的“保证执行”靠什么支撑？一次 enqueue 之后系统里发生了什么？**

保证执行来自三层接力：任务先持久化到应用私有的 Room 数据库，再由 WorkManager 把它委托给系统调度器（API 23 起为 JobScheduler，更老系统走 AlarmManager 加广播的兼容后端），约束满足后由系统唤醒应用执行——进程死亡、应用被杀甚至设备重启都不会丢任务，因为登记与状态都在磁盘上而不在内存里。

一次 enqueue 的链路：

1. **落库**：Worker 与 WorkRequest（含约束、重试策略、输入数据）写入 WorkManager 自带的 Room 表，任务状态进入 ENQUEUED。此时任务还不对应任何系统调度。
2. **委托调度**：WorkManager 按系统版本把任务映射到 JobScheduler 的 JobInfo（或兼容后端的等价物），约束条件同步翻译成系统侧约束。
3. **系统触发**：约束满足且系统允许时，JobScheduler 唤醒应用进程，WorkManager 从 Room 取出任务、反射或经 WorkerFactory 实例化 Worker 并在后台执行器上运行。
4. **状态回写**：成功/失败/重试状态回写 Room，链式任务（beginWith/then）按依赖图推进下一节点。

推论与边界：“保证”指最终会跑，不指立即跑——enqueue 返回只说明落库与登记完成，不对应任何执行时刻；任务输入数据必须是可持久化的小数据（Data 有尺寸上限），大输入应放入文件或网络存储、任务里只带引用。

依据：Android Developers《Task scheduling: persistent work》与 WorkManager 架构说明（Room 持久化与 JobScheduler 委托）。

**Q2: [learning] Constraints 与重试策略由谁检测？为什么应用不应该自己轮询条件？**

约束由系统侧检测：WorkManager 把 NetworkType、Charging、BatteryNotLow 等翻译成 JobScheduler 的约束后，跟踪发生在系统服务里，条件不满足时任务完全不占用应用资源，满足时由系统回调唤醒——应用轮询既浪费电，也会在 Doze 下错过窗口。

配置与行为：

1. **常用约束**：`setRequiredNetworkType(NetworkType.CONNECTED/UNMETERED)`、`setRequiresCharging(true)`、`setRequiresBatteryNotLow(true)`、`setRequiresStorageNotLow(true)`；约束是“全部满足才跑”的与关系。
2. **重试策略**：Worker 返回 `Result.retry()` 后按 BackoffPolicy 重试，`setBackoffCriteria(BackoffPolicy.EXPONENTIAL/LINEAR, 时长, 单位)` 控制退避曲线与起点；`Result.failure()` 终止该任务（链式任务中后续节点标记失败），`Result.success()` 正常推进。
3. **观测执行结果**：经 `WorkManager.getWorkInfoByIdLiveData/Flow` 观察 State（ENQUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED、BLOCKED），链式任务中等待前驱的节点处于 BLOCKED。

边界：约束解决“条件满足才执行”，不解决“到点必须执行”——需要精确时刻触发的任务（闹钟类）应使用 AlarmManager，WorkManager 只保证不丢不抢跑。

依据：Android Developers《Define work requests》（Constraints、BackoffPolicy）与 WorkManager 状态机文档。

**Q3: [learning] expedited 工作与 setForeground 各解决什么问题？分别受什么限制？**

两者都让工作“更急、更不被打断”，但机制与配额不同：expedited 工作是短时关键任务的标记位，系统尽量立即调度且限制时长；setForeground 则把 Worker 升级为前台服务形态，用持续通知换取长时间运行。前者受异步配额约束，后者受前台服务类型与通知义务约束。

对比与限制：

1. **expedited**：用 `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` 声明。API 31 上以短时前台服务形态运行，配额按应用近期使用度发放，超出配额时按声明的策略降级为普通工作或拒绝。适合用户正在等待的同步、上传类短任务。
2. **setForeground**：Worker 里调用 `setForeground(ForegroundInfo(...))`，必须声明对应前台服务类型（如 dataSync）并展示持续通知；长下载、大文件处理等分钟级任务用它，受系统对前台服务类型与时长的新版本限制。
3. **选择**：

    1. 任务时长短且用户可感知 → expedited。
    2. 时长长且必须完成 → setForeground。
    3. 可延迟且不需要用户感知 → 普通 WorkRequest 加约束。

共同边界：两者都不是绕过后台限制的手段——expedited 配额耗尽会降级，前台服务在 Android 15+ 需要声明类型并受触发时机约束，任务设计应保留“被降级为普通工作后仍正确”的逻辑。

依据：Android Developers《Expedited work》《Long-running worker》文档。

**Q4: [learning] 设备进 Doze 后，WorkManager 的任务什么时候真正运行？**

Doze 期间系统冻结网络与 CPU 密集工作，WorkManager 的任务集中在系统安排的维护窗口执行：约束与延迟在 Doze 下照样记账，但 JobScheduler 会把窗口外任务挂起，进入维护窗口后一次性放行。因此“息屏后任务不跑”是系统语义而非 WorkManager 缺陷。

按阶段的行为：

1. **轻 Doze（灭屏后不久）**：网络与 JobScheduler 调度开始受限，任务延迟但维护窗口仍较频繁。
2. **重 Doze（长时间静止）**：维护窗口间隔拉长到小时级，任务在窗口内成批执行；需要网络的任务必须等窗口内有网络可用。
3. **设计对策**：

    1. 同步类任务接受延迟并保证幂等（窗口内重试安全）。
    2. 对时效敏感的路径改用高优先级推送触发前台服务，而不是指望 WorkManager 提前执行。
    3. 测试时用 `adb shell dumpsys deviceidle force-idle` 复现，不要用息屏几分钟的结果推断重 Doze 行为。

与网络约束册的分工：该册回答 Doze 对应用整体的影响与归因方法，本题回答 WorkManager 任务在其中的执行时刻——同一现象的两个视角，结论应互相印证而不是各查一遍。

依据：Android Developers《Doze and App Standby》对维护窗口的说明与 WorkManager 调度文档。
