# Native 内存诊断工具

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q15 -->

**Q1: [learning] 排查 native 内存安全错误时，malloc debug、HWASan 与 MTE 应该怎么选？各自的前提是什么？**

按检出目标与运行前提选择：

1. **malloc debug（API 24+）：**在分配器前加入 shim，用 `guard`/`front_guard`/`rear_guard` 检测越界写、`free_track` 提高 use-after-free 检出机会、`backtrace` 记录分配栈，适合错误在分配器检查点才显现的场景；它会改变被测进程的时序与内存行为，不能开在生产或性能基准里。
2. **HWASan：**由编译器插桩并用影子内存保存地址标记，要求 ARM64、重编译目标 native 代码，且使用 libc++ 时必须选 `c++_shared`。Android 10 至 Android 13 上应用运行依赖 HWASan 系统镜像，Android 14 起普通系统才能用 `wrap.sh`（`LD_HWASAN=1`）启动 debuggable 应用（材料口径，AAOS13 属于前一档）。
3. **MTE：**由 Arm CPU 比较指针标记与内存标记，需要 SoC、内核与系统支持，应用通过 `android:memtagMode`（API 31+）请求；`sync` 在出错指令处触发信号、精度高，`async` 精度低。

三者都是测试变体工具。只是想找“哪些调用栈在持续分配”，默认选择仍是 heapprofd 而不是它们。
