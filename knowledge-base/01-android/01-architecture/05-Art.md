# ART

> 学习资料（文章模式沉淀）。主线：ART 运行时职责、执行方式演进（AOT/JIT/profile 指导）、GC 演进与 Mainline 化。堆空间与 GC 机制深挖见 [../05-memory/01-内存管理与压力治理.md](../05-memory/01-内存管理与压力治理.md)；类加载与 JNI 链接见 [12-类加载ART编译与JNI链接.md](./12-类加载ART编译与JNI链接.md)；2026-09-25 增补实用调试题（Q2–Q5，按 AOSP 近版源码镜像核对）。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 中的 ART 如何理解？**

ART（Android Runtime）是 Android 的应用运行时：负责 dex 字节码的解释与编译执行、内存管理与 GC、线程管理，以及 JNI 调用支持；Android 5.0 起全面取代 Dalvik，每个应用进程都从 Zygote fork 继承一份 ART 实例，在架构分层中属"原生库与 ART"层。

理解它的三个关键演进：

1. **执行方式从 JIT 到混合编译**：Dalvik 只边解释边 JIT；ART 早期（Android 5.0/6.0）改为安装期全量 AOT，安装慢、占空间；Android 7.0 起转为 JIT + 基于 profile 的后台 AOT，兼顾安装速度与运行性能；
2. **GC 持续演进**：从早期 mark-sweep 的较长暂停，到并发复制（CC）回收把暂停降到毫秒级，但 GC 仍是掉帧与内存抖动分析的常客；
3. **ART 本身可更新**：Android 12 起 ART 作为 Mainline 模块（APEX）可独立于整机 OTA 更新，同版本号设备的 ART 行为可能不同，分析运行时问题要同时记录模块版本。

对开发的落点：启动与卡顿优化常落在 ART 上——baseline profile 让关键路径提前 AOT 化，GC 抖动要查对象分配与内存泄漏。

**Q2: 常用的 dalvik.vm.* 调试属性有哪些？为什么改了不重启就不生效？**

这些属性在 Zygote 启动创建 VM 时由 AndroidRuntime 逐个翻译成 -X 选项——启动期读取，改完必须重启 zygote（`stop; start`）才生效。常用映射（按近版 AOSP 源码核对）：

1. `dalvik.vm.checkjni` → `-Xcheck:jni`：全局开 CheckJNI，JNI 误用会直接 abort 并给出 `JNI DETECTED ERROR IN APPLICATION` 文案；
2. `dalvik.vm.heapstartsize/heapsize/heapgrowthlimit` → 堆初始大小/最大/应用增长上限；
3. `dalvik.vm.usejit/jitthreshold` 等 → JIT 开关与编译热度阈值；
4. `dalvik.vm.profilebootclasspath`、`dalvik.vm.hot-startup-method-samples` → 启动期 profile 采集（boot classpath 开关与采样数）；
5. `dalvik.vm.execution-mode` 可强制解释执行——排查 JIT/编译器可疑问题时的对照手段；
6. `getprop | grep dalvik.vm` 查看当前配置。

**Q3: 怎么在设备上验证/触发 AOT 编译？"装了 baseline profile"如何确认真的生效？**

编译命令加 dumpsys 验证形成闭环：`cmd package compile -m speed-profile -f <包名>` 手动按 speed-profile 编译；`dumpsys package dexopt` 逐包逐 ABI 输出 compilerFilter 与 compilationReason——filter 为 speed-profile/speed 且 reason 是 install-dm/baseline 类即生效。

1. **常用命令**：`cmd package bg-dexopt-job` 触发后台 dexopt 全流程；`cmd package dump-profiles <包名>` 导出 profile；Android 14+ 另有 ART 服务命令 `cmd art dexopt-packages/dump/clear-app-profiles` 等；
2. **边界**：filter 是意图，方法级是否真编了要用 oatdump（见 Q4）或性能对比确认。

**Q4: oatdump 和 dexlist 是干什么的？**

两者是构建侧（宿主机）工具：oatdump 检查 OAT/boot 镜像内容——`--oat-file=app.odex` dump 应用编译产物、`--symbolize` 从 oat 提取符号表供 perfetto/simpleperf 符号化、`--dump-imt` 看接口方法表冲突；dexlist 按 `class.method` 列出 dex 中的方法。验证"某方法是否真的被 AOT 编译"，就是在 oatdump 输出里查该方法的 dex_pc → oat code 映射。

**Q5: ART 的崩溃在 tombstone 里长什么样？Perfetto 里 GC/JIT 线程的轨道怎么读？**

ART 侧 fatal 统一走 Runtime::Abort，tombstone 呈 `Abort message: 'Check failed: …'`；高频类别：CheckJNI 误用（`JNI DETECTED ERROR IN APPLICATION`，如使用已删除的全局引用）、boot 镜像/oat 版本不匹配的 CHECK（换 boot-image 或 APEX 升级残留时）、编译器/JIT 缺陷形态的 `SIGSEGV in art::…`。

1. **Perfetto 判读**：`HeapTaskDaemon` 线程上每个 slice 是一次 GC 任务，slice 密集 = 频繁 GC/内存抖动；`Jit thread pool` 空闲时长期睡在任务队列是正常态，长 slice 才是在编译热点方法；
2. **因果纪律**：判"GC 导致掉帧"要看 GC slice 与主线程 SuspendAll/慢帧是否重叠——时间相近不等于因果（与内存册 Q12 的结论一致）。


