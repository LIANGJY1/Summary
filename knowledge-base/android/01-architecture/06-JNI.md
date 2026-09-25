**Q1: Android 中的 JNI 如何理解？**

JNI（Java Native Interface）是 Java/Kotlin 与 C/C++ 原生代码互操作的标准接口：Java 层声明 `native` 方法、实现在 `.so` 里，经 `System.loadLibrary` 加载后由 ART 找到对应函数执行；它就是架构分层中"应用框架 ↔ 原生库与 ART"边界的落地方式——Framework 调 Skia、libbinder 等原生库都走 JNI。

Android 上要掌握的使用规则：

1. **两种注册方式**：静态注册按 `Java_包名_类名_方法名` 命名查找；动态注册在 `JNI_OnLoad` 里用 `RegisterNatives` 绑定，不暴露导出符号，利于混淆与查找性能；
2. **引用与线程规则**：局部引用随 native 方法返回自动释放（大循环里要 `DeleteLocalRef` 防局部引用表溢出），全局引用必须显式 `DeleteGlobalRef`；`JNIEnv` 线程私有，native 自建线程要先 `AttachCurrentThread`，跨线程只能缓存 `JavaVM`；
3. **性能与崩溃形态**：每次跨界有状态切换与引用管理开销，高频调用应批量传数据并缓存 method/field ID；native 层崩溃表现为 tombstone（SIGSEGV/SIGABRT），用 `ndk-stack` 或 addr2line 符号化定位。


