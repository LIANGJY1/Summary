# Measure APM

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q21 -->

**Q1: [learning] Measure APM 在 Android 17 上支持 OOM 自动 heap dump 吗？它的会话与采集模型有哪些边界？**

不支持。稳定版 `0.19.0` 仍以 compileSdk 36 构建，`ProfileCollector` 只注册 `TRIGGER_TYPE_APP_FULLY_DRAWN` 与 `TRIGGER_TYPE_ANR` 两种 `ProfilingTrigger`。Android 17 新增的 OOM、冷启动等 trigger 尚未接入 SDK，看板出现 `profile` 附件不能宣传为“已支持 OOM 自动 heap dump”。平台侧背景：`ProfilingManager` 是 API 35 引入、trigger 从 API 36 起可注册，AAOS13（API 33）的源码树中不存在这套 API。

其他能力边界：Android 端尚无 C/C++ native crash 上报——`app_exit: CRASH_NATIVE` 只是退出原因分类，不代表具备 native 崩溃堆栈采集。ANR 由 native `SIGQUIT` 处理器把信号让渡回 ART Signal Catcher 采集，保存主线程栈和有上限的其他线程栈（`MAX_THREADS_IN_EXCEPTION = 16`）。session 在应用后台超过 30 秒后再回前台即新开。CPU/内存默认 5 秒采样是曲线不是火焰图，上涨不能直接判泄漏。span 名称上限 64 字符，动态值放受控属性，`traceparent`（W3C Trace Context）可把移动 span 关联到服务端 trace。事件先写本地 SQLite（默认 50 MB 上限）再批量上传，远端 Adaptive Capture 修改后通常到下一次启动才生效。数据面是完整平台：self-host 由 ClickHouse、PostgreSQL、MinIO、Apache Iggy 等服务组成，需要持续运维。Measure 为第三方开源 SDK，未本地核对。
