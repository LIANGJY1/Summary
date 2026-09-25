**Q1: Android 中的 ART 如何理解？**

ART（Android Runtime）是 Android 的应用运行时：负责 dex 字节码的解释与编译执行、内存管理与 GC、线程管理，以及 JNI 调用支持；Android 5.0 起全面取代 Dalvik，每个应用进程都从 Zygote fork 继承一份 ART 实例，在架构分层中属"原生库与 ART"层。

理解它的三个关键演进：

1. **执行方式从 JIT 到混合编译**：Dalvik 只边解释边 JIT；ART 早期（Android 5.0/6.0）改为安装期全量 AOT，安装慢、占空间；Android 7.0 起转为 JIT + 基于 profile 的后台 AOT，兼顾安装速度与运行性能；
2. **GC 持续演进**：从早期 mark-sweep 的较长暂停，到并发复制（CC）回收把暂停降到毫秒级，但 GC 仍是掉帧与内存抖动分析的常客；
3. **ART 本身可更新**：Android 12 起 ART 作为 Mainline 模块（APEX）可独立于整机 OTA 更新，同版本号设备的 ART 行为可能不同，分析运行时问题要同时记录模块版本。

对开发的落点：启动与卡顿优化常落在 ART 上——baseline profile 让关键路径提前 AOT 化，GC 抖动要查对象分配与内存泄漏。


