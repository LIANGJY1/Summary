# BlockCanary

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q22 -->

**Q1: [learning] BlockCanary 检测主线程卡顿的采样窗口是怎么安排的？为什么阈值内前段的热点可能完全错过？**

采样不是从 dispatch 开始就固定间隔抓栈：第一次 Printer 回调记录 wall time 与主线程 CPU time 并启动 sampler，首次采样被安排在 `threshold × 0.8` 时刻，之后按 dump interval（默认等于阈值）重复抓栈，dispatch 结束时按 wall time 判定是否超阈值。举例：阈值 1000 ms、自定义间隔 300 ms 时，一次持续 1200 ms 的 dispatch 只在约 800 ms 和 1100 ms 两个时间点采样——前 200 ms 的真实热点可能完全错过。若时间窗内没有栈样本，原实现不会生成 `BlockInfo`。

它测到的是一次 message dispatch 的 wall time，不包含消息在队列里的等待（delivery delay），也不覆盖输入处理、RenderThread、GPU 到 present 的完整帧流水线。采样栈只说明取样瞬间主线程所在位置——wall time 长而 thread CPU time 短时，要用 Perfetto 区分 Runnable 饥饿、Binder、锁等待或 I/O。

共享冲突与现代替代：BlockCanary 用公开的 `Looper.setMessageLogging()` 安装 Printer，而一个 Looper 只有一个 message logger 槽位（AAOS13 的 `Looper.mLogging` 为单字段），后安装的 SDK 会覆盖先安装者，BlockCanary 停止时还会把 logger 置 null——自研方案可装统一分发器转发回调，但无法阻止其他 SDK 后续覆盖。现代 Android 上帧体验交给 JankStats 与 FrameMetrics，系统已判定的 ANR 用 `ApplicationExitInfo` 与系统 trace，BlockCanary 只借鉴 Looper 长消息原理。BlockCanary 为第三方 SDK，未本地核对。
