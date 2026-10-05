# 广播队列与投递

> 学习资料（文章模式沉淀）。边界：本文回答"BroadcastQueue 的队列模型、超时契约、缓存态延后与投递语义"。ANR 诊断归 12-performance/03，应用侧广播用法归 02-app-framework/01。源文档：android-internals-wiki §1.14。实现细节对照 frameworks/base android17-release 中的 BroadcastQueueImpl、BroadcastProcessQueue 和 BroadcastConstants，公开行为对照 Android Developers 广播文档。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Android 17 还有前台/后台两条广播队列吗？`FLAG_RECEIVER_FOREGROUND` 到底影响什么？**

Android 17（API 37）不再用前台队列和后台队列各自维护全局待投递记录：AMS 持有一个 `mBroadcastQueue`，实际实现为 `BroadcastQueueImpl`，该实例接收前台与后台两套 `BroadcastConstants`。`FLAG_RECEIVER_FOREGROUND` 把广播标记为前台优先级，影响调度优先级和接收超时基线，但不会创建第二条全局队列。

1. **单个全局队列：**Android 17 的 `ActivityManagerService` 使用一个 `BroadcastQueueImpl` 实例。前台/后台两套常量对象分别提供策略参数，不代表有两条全局队列。历史版本实现类名和队列结构会变，应用代码应依赖公开广播 API，而不是内部类名。
2. **超时基线：**前台优先级广播使用前台 `TIMEOUT`，普通广播使用后台 `TIMEOUT`。Android 17 AOSP 默认均以 10 秒为基数乘 `Build.HW_TIMEOUT_MULTIPLIER`，并可由 `bcast_timeout` 配置覆盖。因此常见设备上经常看到 10 秒与 60 秒的说法，具体值仍应检查设备构建和生效配置。
3. **标志边界：**普通应用不应仅为抢占调度而滥用 `FLAG_RECEIVER_FOREGROUND`。它改变系统优先级和超时策略，不表示目标进程一定有前台 Activity，也不会保证应用级跨进程投递顺序。

**Q2: [learning] 广播按什么粒度排队？系统最多同时向几个进程投递？**

`BroadcastQueueImpl` 会为目标 `processName + uid` 建立 `BroadcastProcessQueue`，再把广播记录和目标接收器下标排入该进程的队列。这是逐进程调度，不是整台设备共享一条 FIFO。进程间先后顺序因此不构成通用保证。

队列分层、并发槽和防饥饿规则共同决定广播何时被调度：

1. **待处理队列：**每个进程队列含 `mPendingUrgent`、`mPending` 和 `mPendingOffload`。系统优先取 urgent，再取普通项，最后取 offload 项。连续调度 3 个 urgent 后会让更低优先级且等待更久的队列有机会，连续调度 10 个普通项后会考虑 offload，避免低优先级广播一直饥饿。
2. **并行槽：**Android 17 AOSP 默认普通设备允许 4 个暖进程队列并行，低内存设备允许 2 个。urgent 广播可在普通并行上限之外额外占 1 个槽。额外槽只为 urgent 留出推进机会，不代表所有场景都固定并行 5 个。常量可由 `activity_manager_native_boot` 命名空间的 DeviceConfig 调整。
3. **冷启动限制：**清单接收器可以使目标应用冷启动，但系统同一时刻只发起一个广播引起的冷启动，以控制启动资源竞争。冷启动完成、应用线程就绪后，系统才调度接收器执行。
4. `runnableAt`：这是进程队列进入可运行队列的排序时间，不是提前执行时间。Android 17 AOSP 的默认偏移包括：

    1. urgent、带前台标记或目标处于前台/测试插桩状态的队列可按 −120 秒偏移排序。
    2. 普通队列通常加 500 毫秒，缓存进程队列通常加 120 秒。
    3. 当缓存进程的全部待投递项都允许 `deferUntilActive` 时，队列时间为 `Long.MAX_VALUE`，即当前不可运行。
    4. ordered、闹钟、manifest 等特殊项会改变阻塞或排序判断，不能一律归为“时间偏移为 0”。
    5. 这些常量可能被设备配置覆盖。

5. **积压保护：**待处理数量达到 `MAX_PENDING_BROADCASTS` 时，系统会绕过队列延迟以帮助排空。Android 17 AOSP 默认普通设备上限为 256、低内存设备为 128。设备可通过同一 DeviceConfig 命名空间调整，不能把默认阈值当成所有 ROM 的固定值。
6. **接收器调用：**同一进程的多条广播共用进程队列。系统仍针对每个接收器分别调度 `scheduleRegisteredReceiver()` 或 `scheduleReceiver()`。“按进程排队”不等于把多个接收器回调合并成一次应用线程调用。

**Q3: [learning] 哪些广播"发出即算投递成功"？缓存态应用的广播会怎样处理？**

对无序、没有结果回调的运行时注册接收者，系统发出调度调用后会按 assumed-delivered 处理：AMS 不等待应用回报这次回调完成，也不为该接收者启动广播完成 ANR 计时。Android 14 起，缓存态进程的运行时接收器广播可能被延后，待进程回到 active 后再投递。清单接收器不适用这条“等应用变 active”的运行时接收器路径。

仍需等待 `finishReceiver()` 的情况包括清单接收器、有序广播接收器和带完成回调的动态接收器。assumed-delivered 只表示广播队列不等待这个无序回调完成，不表示应用主线程不会被回调阻塞。长时间占用主线程仍会影响 UI 和后续消息，并可能触发输入等其他 ANR。

可通过 `BroadcastOptions` 分别控制缓存态延后和待投递项合并：

1. **延后策略：**API 34 加入 `setDeferralPolicy(DEFERRAL_POLICY_UNTIL_ACTIVE)`，要求运行时注册接收器通常等目标进程变为 active 后再执行，因此可能无限期延后。该策略不适用于有序、闹钟、交互型广播和清单接收器。
2. **投递分组：**API 34 加入 `setDeliveryGroupPolicy(DELIVERY_GROUP_POLICY_MOST_RECENT)`，同一投递组只保留最新广播，较旧待投递项可被丢弃。它决定“是否每条都要投”，延后策略决定“何时投”，二者处理不同问题。
3. **跨进程顺序：**Android 16 起，接收者 `priority` 不再保证不同进程之间的广播顺序，只在同一应用进程内生效。不能用它建立跨应用的协议顺序或同步关系。

**Q4: [learning] 广播 ANR 的计时从哪里开始？`goAsync()` 能把窗口延长多少？**

对于需要等待接收器完成的广播，Android 17 `BroadcastQueueImpl.dispatchReceivers()` 在调度回调前启动 ANR 定时器，`finishReceiverLocked()` 在完成回执到达后取消它。排队等待和拉起冷进程发生在回调调度前，不计入这个接收器完成窗口。因此端到端广播耗时长，不能单凭这一点断定发生了接收器超时。无序且无结果回调的动态接收器属于 assumed-delivered，不走此完成等待计时。

1. **超时结果：**接收器超过对应的 `TIMEOUT` 后，其投递状态记为超时并进入应用无响应处理。超时记录可包含 Intent 与接收包名、类名。调查时应区分进程队列等待、冷启动、回调运行和完成回执等待。
2. `goAsync()` 契约：它只把完成时点从 `onReceive()` 返回延后到 `PendingResult.finish()`，不会暂停计时或增加超时额度。应在 `finally` 中调用 `finish()`，并把任务提交点定义为工作已可靠移交，例如已持久化入队。下载、迁移和大规模扫描应交给 `JobScheduler` 或 WorkManager。
3. **状态维度：**`delivery[]` 记录每个接收器的 PENDING、SCHEDULED、DEFERRED、DELIVERED、SKIPPED、TIMEOUT 或 FAILURE。`APP_RECEIVE` 等状态描述整条广播记录的执行阶段。逐接收器投递结果和整条记录状态回答不同问题，不能互相替代。
