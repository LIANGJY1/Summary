# btrace

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q9 -->

**Q1: [learning] btrace 3.0 用“运行时 Hook + 同步抓栈”采集方法栈——为什么调小 -sampleInterval 也不能保证固定周期采样？-m 参数最常见的误解是什么？**

btrace 3.0 已删除 2.0 的编译期全量插桩，改为 ShadowHook 与 JNI Hook 拦截一批高频或可能阻塞的 ART 路径（对象分配、JNI 调用、Monitor 锁、GC、`Object.wait()`、`Unsafe.park()`），Hook 点在目标线程上同步抓栈、只保存 `ArtMethod*` 与轻量信息——样本只在经过 Hook 点时产生。因此 `-sampleInterval`（默认 1,000,000 ns，即 1 ms）是同一线程两次同步抓栈之间的最小间隔，不会启动严格每 1 ms 唤醒的定时采样器。线程在两次 Hook 之间执行的短方法不会被记录，长期阻塞在未覆盖入口的线程也可能没有样本。最终 Perfetto 里的 slice 由样本与 Hook 上下文重建，不能当每个方法精确的 enter/exit 计时。

`-m` 的误解源于版本差异：它接收的是 ProGuard/R8 mapping（混淆名到原始名的映射，用于反混淆），不是 btrace 2.0 的方法 include/exclude 插桩表——3.0 已不存在那种配置。其他边界：`RheaTrace3.init()` 会直接跳过非主进程，官方 3.0 不能采集 remote 进程的 Service。冷启动采集靠 PC 端写入系统属性再重启应用。README 把 `perfetto` 模式的默认边界写成 Android 8.1，但实现按 `SDK_INT >= 28`（Android 9）选择，文档与实现冲突时应以所用 commit 的代码为准。btrace 为第三方 SDK，未本地核对。


<!-- 来源：05-observability/03-apm-platforms.md · Q10 -->

**Q2: [learning] btrace 的开源 Android 产物为什么不能直接接入 Android 17 / API 37 生产项目？维护 source fork 至少要验收什么？**

因为它 Hook 并解析 ART 私有实现且没有公开 ABI 承诺，预编译产物也不满足 16 KB 对齐。落到具体机制：核心方法采样解析旧的 `art::StackVisitor::WalkStack<CountTransitions::kNo>(bool)` C++ 符号。AAOS13 的 `art/runtime/stack.h` 中该成员模板已是"单模板参数 `CountTransitions` + bool 参数"的形态，材料称 Android 17 又给 `WalkStack` 增加了第二个模板参数、新旧 mangled symbol 不同——符号解析失败时 `StackVisitor::init()` 返回 false，方法采样整体失效。随包 `librheatrace.so` 与 `libc++_shared.so` 的 ELF LOAD 段对齐为 4 KB（`212`），不满足 16 KB 环境要求的 `214`。

结果上的陷阱：Android 17 设备上可能仍产出一份只有系统轨道的 `.pb`，文件存在不能证明方法采样成功。维护 fork 的验收至少包括：

1. 更新 `WalkStack` 的函数类型与 mangled symbol，并在量产 user/release 构建上验证（不能只在 AOSP 调试构建搜索同名函数）。
2. 用 NDK r28+ 重编 native 库，`mmap` 偏移改按运行时 page size（`getpagesize()`）计算，移除写死的 4 KB 常量。
3. 对最终 APK 执行 16 KB ELF 与 ZIP 对齐检查（`llvm-objdump -p ... | grep LOAD` 与 `zipalign -c -P 16 -v 4`）。
4. 以“采样记录数大于零、方法能正确符号化”为自动验收条件，解析失败要显式报错。
5. 量化开启/关闭采集时的启动、帧、CPU、内存与包体开销。

没有私有 ART Hook 维护能力时，替代方案是 Perfetto、`androidx.tracing`、simpleperf 或 Profiler。btrace 为第三方 SDK，未本地核对。ART 符号存在性按 AAOS13 核对。
