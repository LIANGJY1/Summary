# Xtrace

本组收录该工具或 API 的采集、使用与结果解释题。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q15 -->

**Q1: [learning] XTrace 这类“线上动态方法追踪”方案的核心机制是什么？"Non-Invasive"应该怎么理解？**

核心机制是“只改少量目标方法的入口 + 尽量保留原编译执行路径”：把配置指定的方法组成 target set，只更新这些方法的入口（其余方法沿原入口执行，避免全局遍历与改写）。目标方法已有 JIT/AOT 编译代码时选择 ART 的 instrumentation stub、回调后继续执行原机器码，解释态方法走解释器入口（自适应入口）。事件代理注册到方法进入事件，经 JNI 调用 Java 层 interceptor 执行采集动作。论文实验把启动增量压到约 6.5 ms（Frida 对照约 130 ms）、CPU 增量低于 1%，消融显示目标注入主要改善启动与全局 CPU、自适应入口主要改善高频调用的事件成本。1.08 亿 DAU 的 A/B 按 Crash +0.5%、ANR +0.8% 非劣界通过。

"Non-Invasive"是论文中的相对概念：它不改写 DEX 方法体、不做 inline hook，但仍 hook ART 私有函数、定位 `ArtMethod` 并改变方法执行入口——公共 API、Play Integrity 结果、SELinux 策略和厂商 ROM 兼容性都没有随之获得保证。论文把 `UpdateMethodsCode`、`EnableMethodTracing` 称为 "public subset"，这只表示作者选用的 ART 符号子集。按 Android API 定义，这些 C++ 接口仍是平台内部实现（AAOS13 已核对 `InstrumentationListener` 位于 `art/runtime/instrumentation.h`，事件分发前有无监听器的快速检查）。证据边界也要分清：论文报告按 arXiv v1（无源码、无 SDK），兼容范围自报 Android 5.0–15+，性能数字来自单台 Android 15 设备。Android 17 的入口与 tracing 路径已变化，适配远多于替换符号名。普通线上应用没有等价公共 API：目标在 Framework、事件短到采样难以命中且必须记录每次调用时，这类方案才有独特价值，且属于运行时平台工程。其余场景用 `ProfilingManager` 栈采样、编译期插桩或短录制的 `Debug.startMethodTracing()`。
