# Debug Memory Info

本组收录该工具或 API 的采集、使用与结果解释题。

<!-- 来源：03-resource-domains/01-memory-performance.md · Q1 -->

**Q1: [learning] Debug.MemoryInfo 的 App Summary 口径是什么？为什么 getProcessMemoryInfo() 不能高频调用？**

App Summary 是 dumpsys meminfo 的应用摘要口径。常用值回答不同问题：

1. `getMemoryStats()`：返回 `summary.java-heap`、`summary.native-heap`、`summary.graphics` 等域摘要键（AAOS13 `Debug.java` 核对）。
2. `getTotalPss()`：按比例分摊共享页，并包含已换出页的 SwapPss。
3. `getTotalUss()`：各域 Private Clean 与 Private Dirty 的总和。
4. `getMemoryClass()`：返回 MB 为单位的 ART 托管堆近似预算；它与 PSS 不是同一口径，不能相除得到使用率。

getProcessMemoryInfo() 只面向调试或进程管理 UI：官方文档明确 Android Q 起普通应用只返回调用 UID 进程的信息（其余为 0），且采样被显著限频——调用快于限频会返回上一次的数据。AAOS13 AMS 实现为按 MEMORY_INFO_THROTTLE_TIME 节流加缓存。因此它适合放在实验步骤边界或低频诊断任务中，不能放进每帧回调、紧循环或高频定时器。更短的采样周期未必产生新数据。
