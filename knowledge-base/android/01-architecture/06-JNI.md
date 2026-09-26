# JNI

> 学习资料（文章模式沉淀）。主线：JNI 定位与注册方式、引用与线程规则、性能与崩溃形态。类加载/ART 编译与 JNI 链接深挖见 [12-类加载ART编译与JNI链接.md](./12-类加载ART编译与JNI链接.md)；2026-09-25 增补符号化与泄漏排查题（Q2–Q3）。Q 序列即结构，供 atlas 同源直读。

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

1. **确认**：meminfo 观察 Native PSS 随操作次数线性增长；`dalvik.vm.checkjni` 打开后跑用例，CheckJNI 会以 `JNI DETECTED ERROR IN APPLICATION` 报出部分误用（ART 调试开关见 05-Art.md Q2）；
2. **修复纪律**：Get/Release 严格配对；用 RAII 包装（构造 Get、析构 Release）避免早退路径漏放；
3. **工具链**：heapprofd 等 native 内存工具可定位分配点——监控体系见 [../09-app-practice/02-稳定性治理-资源泄漏.md](../09-app-practice/02-稳定性治理-资源泄漏.md) Q10–Q16。


