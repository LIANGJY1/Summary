# JNI

> 学习资料（文章模式沉淀）。主线：JNI 定位与注册方式、引用与线程规则、性能与崩溃形态。类加载/ART 编译与 JNI 链接深挖见 [06-art-runtime.md](06-art-runtime.md)；2026-09-25 增补符号化与泄漏排查题（Q2–Q3）。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 中的 JNI 如何理解？**

JNI（Java Native Interface）是 Java/Kotlin 与 C/C++ 原生代码互操作的标准接口：Java 层声明 `native` 方法、实现在 `.so` 里，经 `System.loadLibrary` 加载后由 ART 找到对应函数执行；它就是架构分层中"应用框架 ↔ 原生库与 ART"边界的落地方式——Framework 调 Skia、libbinder 等原生库都走 JNI。

Android 上要掌握的使用规则：

1. **两种注册方式**：静态注册按 `Java_包名_类名_方法名` 命名查找；动态注册在 `JNI_OnLoad` 里用 `RegisterNatives` 绑定，不暴露导出符号，利于混淆与查找性能；
2. **引用与线程规则**：局部引用随 native 方法返回自动释放（大循环里要 `DeleteLocalRef` 防局部引用表溢出），全局引用必须显式 `DeleteGlobalRef`；`JNIEnv` 线程私有，native 自建线程要先 `AttachCurrentThread`，跨线程只能缓存 `JavaVM`；
3. **性能与崩溃形态**：每次跨界有状态切换与引用管理开销，高频调用应批量传数据并缓存 method/field ID；native 层崩溃表现为 tombstone（SIGSEGV/SIGABRT），用 `ndk-stack` 或 addr2line 符号化定位。

**Q2: native 崩溃只有 "pc 0x… libfoo.so"，怎么符号化到源码行？线上包拿不到 tombstone 怎么办？**

用未 strip 的 so（含 DWARF 符号；App 工程在 `obj/local/<abi>/` 或 `intermediates/merged_native_libs`）做符号化——so 必须与崩溃版本完全一致，backtrace 里是"库基址 + 相对偏移"，要用偏移而不是绝对地址求符号。

1. **ndk-stack**：实时 `adb logcat | ndk-stack -sym <unstripped 目录>`；离线 `ndk-stack -sym <目录> -dump tombstone_05`；
2. **llvm-symbolizer**：对单个相对偏移求文件：行号（`llvm-symbolizer --obj=unstripped.so 0x<rel_pc>`）；
3. **simpleperf 热点**：设备端采样、主机端报告——`app_profiler.py -p <包> -lib <unstripped 目录>` 自动收集 binary_cache，`report_html.py` 出带源码关联的报告；
4. **线上**：非 debuggable 包拿不到 /data/tombstones，业界常用 breakpad/crashpad 类方案在应用内捕获 minidump 上传后符号化（接入前对上游文档核验细节）。

**Q3: native 内存随每次 JNI 调用线性上涨，最终被 LMK——典型的 GetStringUTFChars 类泄漏怎么确认与修？**

`GetStringUTFChars/GetByteArrayElements` 这类 Get 调用可能分配副本，必须与对应的 Release 配对；只 Get 不 Release 在常驻进程或高频回调里累积成 native 泄漏——表现是 `dumpsys meminfo` 的 Native Heap 线性增长而 Java 堆正常。

1. **确认**：meminfo 观察 Native PSS 随操作次数线性增长；`dalvik.vm.checkjni` 打开后跑用例，CheckJNI 会以 `JNI DETECTED ERROR IN APPLICATION` 报出部分误用（ART 调试开关见 06-art-runtime.md Q2）；
2. **修复纪律**：Get/Release 严格配对；用 RAII 包装（构造 Get、析构 Release）避免早退路径漏放；
3. **工具链**：heapprofd 等 native 内存工具可定位分配点——监控体系见 [../16-app-practice/04-stability-leaks.md](../16-app-practice/04-stability-leaks.md) Q10–Q16。

**Q4: @FastNative 与 @CriticalNative 加速什么？各自的硬约束与版本可用性是什么？**

两者都通过推迟 GC 挂起等待与减少过渡开销加速 JNI 调用——@FastNative 让线程保持 runnable；@CriticalNative 更激进，要求方法必须静态、参数与返回值不得含对象（ABI 中没有 JNIEnv*/jclass），官方 2016 年 angler 设备数据为普通 JNI 约 115ns、@FastNative 约 35ns、@CriticalNative 约 25ns。

可用性（已与官方参考文档核对）：Android 8 起平台内部使用；8–11 必须配合 RegisterNatives 动态注册；12 起支持内建动态 JNI 链接查找；14（API 34）起成为公开 API。Android 7 及以下注解被忽略，强行套用会因 ABI 不匹配导致错误编组；非静态或带对象参数的方法会抛 VerifyError。

做法：只用于短小纯计算的高频方法——推迟 GC 挂起意味着临界区内不可做阻塞或回调 Java 的事，否则拖慢整个 GC。

**Q5: native 自建线程里 FindClass 应用类为什么失败？attach 的线程还要注意什么？**

FindClass 沿"当前线程关联的类加载器"查找，native 自建线程 attach 后没有应用加载器上下文，回退只落到系统类加载器，所以应用类返回 null；解法是在 JNI_OnLoad 或有 Java 上下文时把 Class 缓存为全局引用并缓存 method/field ID。

前提：JavaVM 进程级共享、JNIEnv 线程私有。机制：attach 的线程在 detach 前局部引用表持续存活累积，长驻线程要显式 DeleteLocalRef；系统类在任何线程都能找到，失败的只会是应用类。结果：跨线程回调基建（全局引用、ID）统一预建，线程入口只消费缓存。

**Q6: GetStringCritical 返回的指针可以直接保存吗？数组 Elements API 的三种 release 模式怎么选？**

不可以——GetStringCritical 可能返回拷贝：compact strings 把字符以 latin-1 压缩存储，叠加移动 GC，VM 可能选择复制而非暴露原指针；数组 `Get<Type>ArrayElements` 同样是 pin-or-copy 二选一，release 模式 0 回写并释放、JNI_ABORT 不回写只释放、JNI_COMMIT 回写不释放。

前提：Critical 区段禁用 GC，必须短小且成对出现，区内不得调用其他 JNI。做法：不依赖 isCopy 的值写代码——按"可能拷贝"处理；大数组只读检查用 JNI_ABORT 省回写，分批写回用 JNI_COMMIT，常规"改完要生效"用 0。结果：把 Critical 指针存起来跨 GC 使用是悬空崩溃的典型来源。
