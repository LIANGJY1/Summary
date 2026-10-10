# JNI

> 学习资料（文章模式沉淀）。主线：Java/Kotlin 与原生代码的边界、JNI 注册、引用和线程规则、性能与崩溃排查。Q 序列即结构，供 Atlas 同源直读。

**Q1: Android 中 JNI 连接了哪两类代码？ART 在调用 native 方法时做什么？**

JNI（Java Native Interface）是 Java/Kotlin 代码与 C/C++ 原生实现互操作的接口。Java 层声明 `native` 方法，应用或系统库加载包含实现的 `.so` 后，ART 通过 JNI 绑定找到目标函数并完成调用边界转换。

JNI 是应用框架与原生库及 ART 运行时之间的实际调用边界。例如 Framework 调用 Skia、libbinder 等原生实现时，会经 JNI 进入 native 代码。JNI 只提供互操作机制，不会自动处理对象生命周期、线程附着或 native 内存释放。

**Q2: Android JNI 静态注册和动态注册有什么区别？应该如何选择？**

静态注册依赖导出函数名与 Java 类、方法名的约定，动态注册则在库加载时显式把 Java 方法映射到 native 函数。动态注册适合需要集中声明映射、避免导出大量 JNI 符号的项目。

1. **静态注册**：native 函数按 `Java_包名_类名_方法名` 命名，ART 根据约定查找实现。类名或方法签名变化时，符号名也必须同步变化。
2. **动态注册**：在 `JNI_OnLoad` 中取得目标类并调用 `RegisterNatives`，把 Java 方法名、签名和函数指针绑定起来。未被其他用途导出的实现函数可以保持内部符号，映射关系集中在注册表中。Android 官方也建议性能敏感的方法显式注册，避免依赖按名称发现 native 符号。
3. **选择边界**：两者都能实现 JNI 调用。动态注册便于显式管理方法签名和隐藏实现符号，但不会自动解决错误签名、类加载器或线程问题。选择时考虑工程生成方式、混淆规则和符号可见性需求。

**Q3: [learning] native 崩溃日志只有 pc 0x… libfoo.so 时，怎样定位源码行？线上包没有 tombstone 时怎么办？**

符号化需要与崩溃二进制完全匹配、包含调试符号的未 strip `.so`。崩溃回溯给出的库内相对偏移才能映射到函数、文件和行号，不能拿绝对运行地址直接查符号。

1. **准备匹配文件**：从相同构建版本保留未 strip 的库和构建 ID。ndk-build 的未 strip 库位于 `obj/local/<abi>/`。当前 AGP 文档给出的 CMake/ndk-build 工程路径是 `build/intermediates/cxx/<build-type>/<hash>/obj/<abi>`。其他构建任务（例如 merged native libraries）目录随 AGP 版本而变，优先使用 NDK 文档标出的 unstripped 输出。
2. **符号化整份日志**：实时日志可用 `adb logcat | ndk-stack -sym <未 strip 库目录>`。离线 tombstone 可用 `ndk-stack -sym <未 strip 库目录> -dump <tombstone 文件>`。
3. **查询单个偏移**：`llvm-symbolizer --obj=<未 strip 库> 0x<库内相对偏移>` 可把单个地址映射到源码位置。输入应是相对偏移，而不是进程中的绝对 PC。
4. **分析热点**：simpleperf 的 `app_profiler.py` 可按包采样并收集 `binary_cache`，`report_html.py` 可生成关联符号的报告。库文件必须与设备上实际运行的版本匹配。
5. **线上包取证**：应用不能直接读取系统 `/data/tombstones` 文件，但 Android 12（API 31）起可从 `ApplicationExitInfo.getTraceInputStream()` 读取本应用 native crash 的 tombstone protobuf。它存放在全局循环缓冲区，较新的崩溃可能覆盖旧记录，接口也可能返回 null。对旧系统或无 trace 的退出，可接入 Breakpad、Crashpad 一类方案捕获 minidump 并上传，再用对应版本的符号文件离线符号化。

**Q4: [learning] JNI 中反复调用 GetStringUTFChars 或 GetByteArrayElements 后 Native Heap 线性增长，怎样确认并修复泄漏？**

`GetStringUTFChars`、`GetByteArrayElements` 等接口可能返回副本，也可能返回 VM 管理的直接访问指针。无论是否复制，调用方都必须用对应的 Release 接口结束访问。遗漏 Release 会让高频或长驻路径持续占用 native 资源。

1. **确认增长类型**：用 `dumpsys meminfo` 观察操作次数增加时 Native Heap 或 Native PSS 是否同步增长，并确认 Java Heap 没有相同趋势。单看进程总内存不能证明问题来自 JNI。
2. **检查 API 配对**：每个 Get 都应在所有返回路径上与对应 Release 配对。不要依赖 `isCopy` 判断是否需要 Release。
3. **覆盖早退路径**：用 RAII 包装 Get/Release，例如构造时取得字符或数组元素、析构时 Release，避免异常、错误返回和多分支漏释放。
4. **启用检查**：在可调试设备上按该 Android 版本配置 CheckJNI，再执行稳定复现用例。可在应用 manifest 中启用 `android:debuggable` 以只对该应用启用，或在可用的调试设备上设置 `debug.checkjni=1`。CheckJNI 会检测部分 JNI 契约误用并报告 `JNI DETECTED ERROR IN APPLICATION`，但不能替代内存分析器发现所有泄漏。
5. **定位分配点**：使用 heapprofd 等 native 内存分析工具关联分配调用栈，并将增长曲线与复现操作次数对照。

**Q5: [learning] Android 的 @FastNative 和 @CriticalNative 分别减少什么调用开销？它们有哪些限制？**

两种注解都针对短小、高频的 JNI 调用，减少托管代码与 native 之间的转换开销。执行期间 GC 不能为关键工作挂起该线程，因而长时间运行或阻塞会延误 GC。`@FastNative` 保留常规 JNI 参数能力。`@CriticalNative` 更严格，适用的方法不能访问 Java 对象，ABI 中也没有 `JNIEnv*` 和 `jclass` 参数。

1. `@FastNative`：ART 在 native 调用期间延迟挂起检查，因此方法应快速返回。不要在其中执行阻塞 I/O、长时间等待或回调 Java，否则会延迟 GC 和其他线程的挂起。
2. `@CriticalNative`：仅用于静态方法，参数与返回值不能含 Java 对象。调用 ABI 不传 `JNIEnv*` 和 `jclass`，native 函数签名必须与这种约定匹配。
3. **版本与注册**：Android 8 起平台内部实现这些优化。Android 8–10 的按名称动态查找尚未实现，Android 11 存在已知问题，因此 Android 8–11 必须使用 `RegisterNatives` 显式注册。Android 12 起支持内建动态查找，但性能敏感方法仍建议显式注册。Android 14（API 34）起成为 CTS 测试的公开 API。Android 7 及更早版本会忽略注解，`@CriticalNative` 的 ABI 不匹配可能造成参数编组错误和崩溃。Android 8–13 的兼容保证弱于 Android 14 之后，面向广泛设备兼容时应谨慎使用。
4. **性能证据**：官方曾在特定设备上报告普通 JNI、FastNative 和 CriticalNative 的微基准时延约为 115 ns、35 ns 和 25 ns。该数字只代表对应设备和测试条件，不能当作应用实际收益保证。
5. **使用判断**：先测量跨界调用是否为热点，再确认方法满足线程挂起、参数类型和耗时约束。若主要成本在计算、分配或数据复制，换注解不一定解决瓶颈。

**Q6: [learning] native 自建线程调用 FindClass 为什么可能找不到应用类？跨线程使用 JNI 引用要遵守什么规则？**

native 自建线程通过 `AttachCurrentThread` 附着到 ART 后，没有原始 Java 调用栈提供的应用类加载器上下文。该线程上的 `FindClass` 可能退回系统类加载器，因此能找到系统类却找不到应用类。

1. **预缓存应用类**：在 `JNI_OnLoad` 或具有正确应用类加载器上下文的 Java 调用中取得 `Class`，创建全局引用并缓存所需 method/field ID。native 线程随后使用缓存，不要依赖它重新按名称查找应用类。
2. **区分 VM 与环境指针**：`JavaVM` 在进程内共享，可用于获取当前线程的 JNI 环境。`JNIEnv*` 是线程私有，不能把一个线程的指针传给另一个线程复用。
3. **附着和分离线程**：native 创建的线程调用 Java 前先 attach。线程退出时若仍由 native 管理，应 detach，避免线程状态和资源遗留。
4. **管理引用生命周期**：局部引用通常在 native 方法返回时释放。自建线程上的局部引用要到 detach 或显式删除才释放。长循环应及时 `DeleteLocalRef`。全局引用跨调用和线程存活，必须显式 `DeleteGlobalRef`。

统一在有 Java 类加载器上下文的阶段准备全局引用与 ID，线程入口只使用缓存，并在完成时清理引用和线程附着。

**Q7: [learning] GetStringCritical 返回的指针能否保存到函数调用之外？critical 区域有哪些限制？**

不能把 `GetStringCritical` 返回的指针保存到配对的 `ReleaseStringCritical` 之后，也不能跨越可能改变对象状态的调用继续使用。VM 可能直接暴露内部存储，也可能返回副本，调用方必须按短时借用指针处理。

1. 在使用区间内保持指针有效，并保证每次成功取得都恰好配对调用 `ReleaseStringCritical`。
2. critical 区域必须短小，不得阻塞、等待锁或调用其他 JNI 函数，因为 VM 可能在此期间延迟 GC 或线程挂起。
3. 不要根据 `isCopy` 的值决定是否释放，也不要把指针缓存到全局变量或异步任务中。

**Q8: [learning] Get<Type>ArrayElements 的三种 Release 模式有什么区别，修改后的数组应选哪一种？**

`Get<Type>ArrayElements` 可能返回 pin 住的数组存储，也可能返回副本。Release 时要按是否提交修改和是否结束访问选择模式。

1. `0`：提交修改并释放访问资源。常规读写完成后要让结果生效时使用。
2. `JNI_ABORT`：不提交对副本所做的修改并释放访问资源。只读访问适合用它避免不必要的回写。若 VM 给出的是直接数组存储，则修改已发生，不能把它当作撤销修改。
3. `JNI_COMMIT`：提交对副本的修改，但保留访问资源，之后仍须再调用一次 Release 结束访问。适用于需要阶段性提交、但还要继续使用指针的场景。
4. **配对规则**：每次成功取得的 Elements 指针最终都必须释放。不要跨 Release 保存指针，因为 VM 可能使用临时副本或移动后的存储。
