# 广播队列与投递

> 学习资料（文章模式沉淀）。边界：本文回答"BroadcastQueue 的队列模型、超时契约、缓存态延后与投递语义"；ANR 诊断归 15-performance/03，应用侧广播用法归 02-app-framework/01。源文档：android-internals-wiki §1.14（Android 17 语境），缓存态延后已与官方资料核对。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 17 还有前台/后台两条广播队列吗？`FLAG_RECEIVER_FOREGROUND` 到底影响什么？**

没有。API 37 的 `ActivityManagerService` 只持有一个 `mBroadcastQueue`（实现类 `BroadcastQueueImpl`），前台与后台是传给同一实例的两套 `BroadcastConstants`——前台 10 秒、后台 60 秒基础超时（乘 `HW_TIMEOUT_MULTIPLIER`，可用 Settings.Global 的 `bcast_timeout` 覆盖）。`FLAG_RECEIVER_FOREGROUND` 仍然有效，但它影响紧急程度、接收进程调度组与超时基线，不会把广播放进另一条全局队列。

- Android 17 源码中不存在 `mFgBroadcastQueue`/`mBgBroadcastQueue`，也没有 `BroadcastQueueModernImpl`；内部类名在历史分支变过，应用应依赖公开广播语义而非类名。
- 广播超时基准随标志变化：带 `FLAG_RECEIVER_FOREGROUND` 用 10 秒，普通用 60 秒。普通应用不应为抢占调度滥用该标志——它会让接收者以更高调度优先级运行，并把超时窗口缩短到 10 秒。

**Q2: 广播按什么粒度排队？系统最多同时向几个进程投递？**

`BroadcastQueueImpl` 以目标进程为粒度排队：按 `processName + uid` 找到该进程的 `BroadcastProcessQueue`，把"记录 + 接收者下标"入队；每个进程队列内部有 `mPendingUrgent`、`mPending`、`mPendingOffload` 三条待处理队列，按 urgent → normal → offload 取项，并用"连续 3 个 urgent 后考虑更早入队的低优先级项、连续 10 个 normal 后考虑 offload 项"防饥饿。并行度由固定大小的 running 数组控制：普通设备 4 个进程槽（低内存设备 2 个），urgent 广播可额外占 1 槽，同一时刻只允许 1 个广播冷启动。

- `runnableAt` 决定进程队列何时可运行：urgent/foreground/instrumented 偏移 −120 秒（排序前移而非提前执行），ordered/alarm/manifest 为 0，普通广播 +500ms 调度余量，cached 且不能无限延后 +120 秒，cached 且全部 `deferUntilActive` 为 `Long.MAX_VALUE`。
- 队列积压达到 `MAX_PENDING_BROADCASTS`（普通设备 256、低内存设备 128）时会绕过已施加的延迟帮助排空。
- 清单接收者可能触发进程冷启动；同一进程的多条广播共用一个进程队列，但源码仍对每个接收者分别调用 `scheduleRegisteredReceiver()`/`scheduleReceiver()`，不会合并成一次 Binder 调用。

**Q3: 哪些广播"发出即算投递成功"？缓存态应用的广播会怎样处理？**

对无序、没有完成回调的运行时注册接收者，`BroadcastRecord.isAssumedDelivered()` 为 true：system_server 成功发出 `scheduleRegisteredReceiver()` 后立即标记已投递，不等待应用回报，也不为它启动广播 ANR 定时器。Android 14 起，应用处于缓存态时系统可延后发给运行时注册接收者的广播，等应用回到 active 再投递（重复广播可能合并）；清单注册接收者不走这套无限延迟路径，重要清单广播会让应用离开缓存态再投递。

- 仍要等待 `finishReceiver()` 并受超时跟踪的投递：清单接收者、有序广播接收者、带完成回调的动态接收者。assumed-delivered 不代表无序动态接收者可以长期占用主线程——它仍会阻塞该应用自己的 UI 与后续消息，可能触发输入等其他类型 ANR。
- API 34 的 `BroadcastOptions` 提供两个正交能力：`setDeferralPolicy(DEFERRAL_POLICY_UNTIL_ACTIVE)` 控制何时投递（不适用于有序、闹钟、交互型与清单接收者）；`setDeliveryGroupPolicy(DELIVERY_GROUP_POLICY_MOST_RECENT)` 让同一投递组只保留最近一条、旧的待投递项被跳过。延后策略与 delivery group 解决的是"何时投"与"是否都要投"两个维度。
- Android 16 起，接收者 `priority` 只保证同一应用进程内的顺序，跨进程全序不再保证，不能把优先级设计成跨应用协议顺序。

**Q4: 广播 ANR 的计时从哪里开始？`goAsync()` 能把窗口延长多少？**

定时器在 `BroadcastQueueImpl.dispatchReceivers()` 调用 `scheduleRegisteredReceiver()`/`scheduleReceiver()` 之前启动，由 `finishReceiverLocked()` 取消；队列等待和广播触发的冷启动发生在定时器启动之前，所以"广播端到端等了很久"不等于"接收者执行超时"，排障要区分调度延迟与完成延迟。`goAsync()` 只把完成回执从 `onReceive()` 返回点延后到 `PendingResult.finish()`，不会暂停 ANR 计时、也不提供额外时间——需要等待完成的投递仍受 10/60 秒基准约束。

- 超时把该接收者标为 `DELIVERY_TIMEOUT` 并进入 `appNotResponding()`；`TimeoutRecord` 描述包含 Intent 与接收包名/类名。
- `goAsync()` 的正确用法：先定义提交点（任务已持久化入队、有序结果已写完），并在 `finally` 中调用 `finish()`；下载、迁移、大扫描等长任务交给 `JobScheduler`/WorkManager，而不是占用广播窗口。
- 逐接收者状态用 `delivery[]` 表达（PENDING/SCHEDULED/DEFERRED/DELIVERED/SKIPPED/TIMEOUT/FAILURE）；`APP_RECEIVE` 等是整条记录的执行状态，两者不能互相替代。
