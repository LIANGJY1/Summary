# ProfilingManager 与 ProfilingTrigger

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q28 -->

**Q1: [learning] ProfilingManager 解决什么问题？它是什么时候引入的，Android 13 上能用吗？**

ProfilingManager 补足量产设备上“问题发生时没有开启分析工具”的取证空档：应用可以在运行中请求 system trace、Java heap dump、heap profile 和 stack sampling，采集完成后通过回调拿到结果文件路径，不需要连接开发机。

版本边界必须点明：平台类 `android.os.ProfilingManager` 自 Android 15（API 35）引入，AndroidX Core 1.15.0 起提供 `androidx.core.os.Profiling` 与各 request builder 封装，系统触发（trigger）能力再分 API 36、36.1、API 37 三档（材料口径）。

Android 13 上不存在该 API——本地 AAOS13 树的 frameworks/base 与 packages/modules 下均无 ProfilingManager 对应类（本地源码核对），Android 13 及更早的替代是连接开发机对 debuggable/profileable 构建采集，或降级使用 Android vitals、FrameMetrics/JankStats、`ApplicationExitInfo` 等指标与记录。它属于 Mainline 模块、可经 Google Play 系统更新独立更新，因此设备实际行为可能与固定 AOSP tag 的源码有差异。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q29 -->

**Q2: [learning] 发起一次 ProfilingManager 显式请求要注意什么？“提交成功”等于“采集成功”吗？**

不等于。请求经 Binder 进入系统服务后还要依次通过并发状态、限流与参数检查，任一阶段失败都映射到错误码，只有 `errorCode` 为 `ERROR_NONE` 且 `getResultFilePath()` 非 null 才有文件。限流按 profiling 类型的资源成本在小时/天/周窗口以及应用 UID 与整机预算上分别检查，不是固定“每小时几次”，返回 `ERROR_FAILED_RATE_LIMIT_*` 时应退避而不是循环重试。

显式请求的要素：按问题选 builder——SystemTraceRequestBuilder 看线程时间线与调度交叠、JavaHeapDumpRequestBuilder 看对象存活与 GC Root 引用链、HeapProfileRequestBuilder 看持续分配调用栈、StackSamplingRequestBuilder 要低开销热点线索。`executor` 与 request-specific listener 必须成对传入，两者都为 null 仅在已注册全局 listener 时有意义，没有任何 listener 的请求会被丢弃。

`tag` 只取前 20 个字母、数字和连字符并转小写进入文件名，不能承载用户数据。heap dump 是某一时刻的快照、没有时长参数。`BufferFillPolicy` 只属于 system trace 请求，`RING_BUFFER` 保留停止点前的时间窗、`DISCARD` 保留采集开头的早期证据（材料口径）。结果文件只能通过返回的 `resultFilePath` 访问，成功结果用它去重。`ProfilingResult` 没有唯一结果 ID，失败时路径为 null。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q30 -->

**Q3: [learning] system-triggered profiling 为什么“注册了 ANR trigger 却常常拿不到文件”？APP_FULLY_DRAWN 与 COLD_START 两个 trigger 的窗口差在哪？**

注册 trigger 只是允许系统在条件满足时采样，不保证每次事件都有产物：系统会周期性、带随机性地启动后台 trace 并用 ring buffer 保存最近数据，事件发生时只有后台 trace 正在运行且配额充足，才会生成当前 trace 的快照——后台 trace 未运行、限流命中都会导致“事件发生了但没有文件”。两个启动 trigger 的窗口完全不同（材料按 API 36/37 口径）：`APP_FULLY_DRAWN` 保存触发点之前 ring buffer 里已在运行的后台 trace，适合看启动尾段与触发前的系统活动。

`COLD_START` 为这次冷启动新开一份 system trace 并同时做 stack sampling，使用 DISCARD 保留采集开头，在应用调用 `reportFullyDrawn()` 时停止、未调用时默认约 5 秒停止。两者的起点、停止点和产物都不同，样本必须按 trigger type 分组统计，不能混进同一个分位数。trigger 结果只送到全局 listener，进程重启后可再次投递。

版本上 API 36 提供 APP_FULLY_DRAWN 与 ANR，36.1 增加用户强停、最近任务移除等 kill 类 trigger 与 `requestRunningSystemTrace()`（判断用 `SDK_INT_FULL`），API 37 增加 OOM、COLD_START 等——其中 `TRIGGER_TYPE_OOM` 只覆盖 Java OOM，不含 lmkd 或 native OOM，应用自定义 `UncaughtExceptionHandler` 时必须续调默认 handler。

实验设备可用 `profiling_testing` 的 DeviceConfig 开关关闭限流或指定测试包，测试后必须恢复。


<!-- 来源：05-observability/02-online-diagnostics.md · Q23 -->

**Q4: [learning] ProfilingManager 与 ProfilingTrigger 的能力从哪个版本开始？AAOS13 上能用吗？结果为什么可能没有？**

`ProfilingManager` 自 Android 15 / API 35 提供四类请求（system trace、Java heap dump、heap profile、stack sampling），AAOS13（API 33）上不存在，低版本继续用自有指标、Perfetto、bugreport 等实验室路径。请求受系统级与进程级限流，且可能因已有 profiling 进行中、磁盘不足、参数无效或执行失败而没有结果——“请求已发出”与“结果已生成”是两个状态。必须成对提供 executor 与 listener（或提前注册全局监听器），两处都没有时请求被丢弃且无回调。只有 `error_code == ERROR_NONE` 才读 `getResultFilePath()`，路径来自回调、不能拼接固定目录。结果脱敏且只含请求进程的信息，其他进程 CPU 活动合并显示在 `OtherProcesses`。App 在结果投递前退出时，系统会在下次启动并注册全局监听器后尝试补投。

触发器按版本递增：Android 16 / API 36 加 `APP_FULLY_DRAWN`、`ANR`（结果只投递给 `registerForAllProfilingResults()` 的全局监听器，且依赖系统当时正在后台 trace）。SDK extension 36.1 加 `requestRunningSystemTrace()` 与用户终止类触发器（要用 `SDK_INT_FULL` 对比 `BAKLAVA_1` 判断，`SDK_INT == 36` 区分不了 36.0 和 36.1）。Android 17 / API 37 加 `OOM`（要求自定义 UncaughtExceptionHandler 继续调用默认处理器，否则系统无法使用）、`COLD_START`（新启动 trace + stack sampling，未调 `reportFullyDrawn()` 时默认 5 秒停止、用 discard buffer 保住启动早期内容）、`ANOMALY` 与过量 CPU 终止等。Android 17 部分设备的 MemoryLimiter 终止表现为 `REASON_OTHER` 且 `description` 含精确字符串 `"MemoryLimiter:AnonSwap"`——只有这个文档保证的完整标记可机器判断，不能匹配宽泛的 `"MemoryLimiter"` 或把所有 `REASON_OTHER` 归入内存限制。所有触发式采集都是尽力而为，“触发已登记”“系统产生结果”“结果已上传”要分开统计。
