# ApplicationStartInfo

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：01-user-experience/02-app-startup.md · Q4 -->

**Q1: [learning] ApplicationStartInfo 提供哪些系统侧启动信息，Android 13 设备上能用吗，读取时间戳有哪些陷阱？**

`ApplicationStartInfo` 是 Android 15（API 35）引入的系统启动记录，AAOS13（Android 13）上不存在该 API（本地核对 frameworks/base 无此类、system_server 无 `AppStartInfoTracker`），Android 10–14 仍需自建埋点。

它提供以下结构化字段：

- 启动原因 `getReason()`、启动类型 `getStartType()`（cold/warm/hot）与启动状态 `getStartupState()`；
- 进程名、PID 与 UID；
- launch、fork、bindApplication、`Application.onCreate`、first frame、fully drawn 等单调时间戳。

Android 16（API 36）增加 `getStartComponent()` 区分组件类型，API 35 上调用它必须先查版本。

读取陷阱按 Android 17 行为核对转写：历史列表 `getHistoricalProcessStartReasons()` 混有同应用其他进程与未完成记录，先按 PID 与进程名过滤再按 `LAUNCH` 取最新，不能无条件取第 0 项。时间戳 Map 的 key 可能缺失，不能把“未写入”当 0，也不能按常量编号排序——只有起止节点都存在且终点不早于起点，区间才有效。

平台没有 `FULLY_DRAWN` 状态，fully drawn 只是依赖应用调用 `reportFullyDrawn()` 的可选时间戳，首帧完成监听在 `FIRST_FRAME` 时回调、回调快照通常没有 `FULLY_DRAWN`，需要 TTFD 时应在上报后重新查询新副本。API 36 及以下 Service 启动的 `LAUNCH` 时间戳可能不准确（官方标注）。可用 21–30 的保留 key 经 `addStartInfoTimestamp()` 写业务时间点，兼容不同版本实现的做法是每个 key 首帧前只写一次、回读缺失就按缺失处理。


<!-- 来源：05-observability/02-online-diagnostics.md · Q24 -->

**Q2: [learning] ApplicationStartInfo 给启动样本补了什么？它和 TTID/TTFD、业务埋点是什么关系？**

它是 Android 15 / API 35 的系统侧启动记录（AAOS13 上不存在），提供进程身份（PID 与 package/real/defining 三类 UID）、启动 reason、冷/温/热类型、启动状态与单调时钟时间戳。价值是为每条启动样本补系统归因：冷启动从创建进程开始，温启动复用部分已保存状态，热启动把仍在运行的应用带回前台——同样首帧耗时，桌面图标冷启动、最近任务恢复与广播拉起的优化方向完全不同。

它不能定位慢代码，也不能替代平台 TTID/TTFD 或业务埋点：TTID 是系统收到启动 Intent 到首帧显示，TTFD 到应用调用 `reportFullyDrawn()`。业务首页可用、路由与异步数据完成仍要自己记录。公开 start reason 覆盖 launcher、broadcast、push、job 等。API 36 加 `getStartComponent()` 区分 Activity/Service/Broadcast/Provider，API 35 只能按 reason 粗分并接受较低分类精度。`getIntent()` 已移除 extras 且不应直接上传，上报只走白名单映射的 route 与 entry scene。


<!-- 来源：05-observability/02-online-diagnostics.md · Q25 -->

**Q3: [learning] 读 ApplicationStartInfo 时为什么先看 startupState 再看 startType？时间戳相减要满足什么条件？**

历史查询可能包含尚未完成的启动记录：`STARTUP_STATE_STARTED` 只保证身份/原因字段与 `START_TIMESTAMP_LAUNCH`。`STARTUP_STATE_FIRST_FRAME_DRAWN` 才额外保证 `BIND_APPLICATION`、`APPLICATION_ONCREATE` 与 `FIRST_FRAME`。`getStartType()` 只在首帧完成状态有保证，未完成记录可能返回 `START_TYPE_UNSET`，线上协议用 nullable 或 `unavailable` 表示，不能把未设置值当成热启动。`addApplicationStartInfoCompletionListener()` 在当前启动首帧完成时投递一次后自动移除，不等待 `reportFullyDrawn()`。需要 `FULLY_DRAWN` 时间戳要在调用 `reportFullyDrawn()` 后重新查询或重新注册监听器。

时间戳是纳秒单调时钟（同一次开机周期内可比较），只在同一条记录、两个 key 均存在时相减：`FIRST_FRAME − LAUNCH` 在 launch 可信时可保存为系统首帧区间。`APPLICATION_ONCREATE − BIND_APPLICATION` 包含框架在两点之间执行的工作，不能当 `onCreate()` 自身耗时。不能与 `System.currentTimeMillis()` 直接相减，要同刻保存两种时钟快照再在同一 boot session 内近似换算。开发者自定义 key 限定在 21–30 区间、重复写覆盖旧值、`reportFullyDrawn()` 之后写入被丢弃。版本注意：Service 启动的 `START_TIMESTAMP_LAUNCH` 在 Android 15/16 可能不正确，不要给 Service 启动设耗时门禁。
