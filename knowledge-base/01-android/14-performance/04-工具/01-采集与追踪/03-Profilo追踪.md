# Profilo

本组收录该工具或 API 的采集、使用与结果解释题。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q11 -->

**Q1: [learning] Profilo 的 ATrace provider 到底捕到了什么？“它采集的 trace 与 Perfetto system trace 一样”错在哪？**

Profilo（Meta 开源，上游 2023-02 归档、README 声明 API 不稳定）在 API 27+ 的实现是：`dlsym()` 查找 libcutils 私有变量 `atrace_enabled_tags` 与 `atrace_marker_fd` 的地址，对 `libcutils.so` 的 `__write_chk` 安装 PLT hook，启用时把 tag mask 原子交换为全 1。之后命中 hook 的 ATrace 写入只解析 `B`/`E`（同线程同步 slice 开始/结束）转写进 Profilo 缓冲区并直接返回成功——异步事件 `S`/`F`/`G`/`H`、instant `I`/`N` 和 counter `C` 被静默丢弃，调用方无法从返回值发现丢失。AAOS13 源码核对：`trace-dev.cpp` 先打开 `/sys/kernel/tracing/trace_marker`（失败回退 debug 路径），`atrace_enabled_tags` 是普通 `uint64_t`——Profilo 把 `dlsym` 地址当 `std::atomic<uint64_t>*` 操作属于私有 ABI 假设。

“与 system trace 一样”错在两点：可见范围上它只改本进程的重定向槽，能看到的是当前进程内 framework/ART/HWUI 执行到的 ATrace 点，触不到 `system_server`、SurfaceFlinger，也没有 sched、Binder、块 I/O 等内核事件——它只是一份“当前进程同步 slice 的应用内副本”。完整性上，启用期间命中 hook 的写入不再进内核 trace_marker，与 Perfetto system trace 并发采集会缺本进程的 ATrace 事件，必须禁止重叠或把缺失写进采集协议。继续维护 fork 时应做能力协商（安装失败上报并跳过该 provider）、事件语义测试（嵌套、多线程、缓冲区回绕、启停后 tag mask 恢复）与目标构建的开销实测。其 Java 栈采样依赖的 ART 内部布局版本表只列到 Android 9，不能宣称 Android 17 支持。
