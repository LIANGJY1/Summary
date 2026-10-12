# Agp Instrumentation

本组收录该工具或 API 的采集、使用与结果解释题。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q1 -->

**Q1: [learning] 字节码插桩能改什么、不能改什么？“无业务手写埋点”为什么仍不等于零侵入？**

字节码插桩在源码编译成 JVM `.class` 之后、生成 DEX 之前自动检查或改写指令，只能处理进入当前 App 构建流程的 class。不能修改 Android framework、ART 或任意第三方库的内部实现——普通 App 的 AGP 插桩改不了设备 boot classpath 上的 `Choreographer`、`ActivityThread`、`Looper`，运行时反射或 ART hook 受隐藏 API、版本差异和完整性策略影响，不应作为常规生产方案。帧与主线程监控应优先 JankStats、`FrameMetrics`、Macrobenchmark 与 Perfetto。

可维护的插桩方案分四层，每层有典型错误：选择规则决定哪些 variant、class、method 需要处理（release 无条件全量插桩、误改生成代码是常见错误）。字节码变换要保证控制流、操作数栈和 frame 合法（只处理正常 `return`、异常路径没有清理）。运行时桥接负责采样、限流、隐私处理并写入 Trace/APM（在热路径分配对象、同步 I/O 或递归调用）。验证发布要校验 class、D8/R8 产物与运行时成本（只看编译成功、不检查优化后产物）。“无手写埋点”仍有侵入：每条注入指令都改变 class、构建时间和运行路径，是否启用、覆盖哪些方法以及容许多少成本，都应由当前工程的测量结果决定。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q2 -->

**Q2: [learning] Transform API 已经怎样了？用 AGP Instrumentation API 注册 AsmClassVisitorFactory 要遵守哪些约束？**

旧的 `Transform` API 在 AGP 7.2 废弃、AGP 8.0 移除，没有覆盖所有旧用法的单一替代。按任务选择入口：

1. **逐 class 变换：**使用 `variant.instrumentation.transformClassesWith()`。
2. **读取或变换整组 class：**使用 `variant.artifacts.forScope()` 与 `ScopedArtifact.CLASSES`。
3. **编译后生成 class 且需要读取已编译 class：**使用 `ScopedArtifact.POST_COMPILATION_CLASSES`；该 API 在 AGP 9.3.1 加入并标 `@Incubating`（构建侧版本按材料核对，本地源码树未包含）。
4. **全程序调用图：**逐 class 信息不足时使用 Scoped Artifacts 独立 task。

ASM 有两种常用 API：Core API 通过 visitor 流式处理，适合入口、出口和调用点替换等局部操作；Tree API 读入 `ClassNode` 便于多次遍历与控制流分析，但内存成本更高。

注册 factory 的硬约束：`isInstrumentable()` 与 `createClassVisitor()` 可能被异步、并发调用，factory 不能用可变全局集合统计结果或读写共享文件。所有配置必须声明为 Gradle `Property` 系输入，回调里临时读取未声明的文件会破坏 up-to-date 判断与 build cache 命中。ASM API 版本应取 `instrumentationContext.apiVersion`，不要把上游最新版（材料口径 2026-08 为 9.10.1）写死进 visitor。范围控制：`InstrumentationScope` 默认 `PROJECT`（自有代码），`ALL` 会处理传递依赖、可能重复插桩已插桩库并扩大兼容面。Android library 只能对本项目 class 注册。给既有方法增加控制流时用 `FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS` 让 AGP 重算 frame，只加注解可用成本更低的 `COPY_FRAMES`，`COMPUTE_FRAMES_FOR_ALL_CLASSES` 触发非增量全类计算应避免。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q3 -->

**Q3: [learning] 给方法插 Trace.beginSection()/endSection() 时，为什么只检查 RETURN 和 ATHROW 两条退出路径不够？section 名称与协程有什么限制？**

`RETURN` 和 `ATHROW` 只覆盖显式出现在当前方法字节码中的退出指令：`repository.load()` 抛出的异常会沿调用栈传播，当前方法没有自己的 `ATHROW`，却在 `INVOKEVIRTUAL` 调用指令处退出——只看退出 opcode 时 `beginSection()` 无法配对，后续同线程的嵌套 section 全部错位。稳妥做法是给原方法体加 catch-all handler，语义等价 `try/finally`：正常返回前调用 `endSection()`，异常路径由 handler 调用后原样重抛。ASM 的 `AdviceAdapter` 已处理“构造方法必须等父类构造完成”的限制。注册了新增控制流后必须选择能重算 frame 的 `FramesComputationMode`，并用包含多 return、显式 throw、被调用方法抛异常、try/catch/finally 的 fixture 验证正常与异常路径 begin/end 数量相等。

平台契约（AAOS13 已核对）：`android.os.Trace` 的同步 section 必须在同一线程成对并正确嵌套，名称上限 127 个 UTF-16 code unit（`MAX_SECTION_NAME_LEN = 127`。AAOS13 的 Java Trace 走 libcutils trace_marker 单路径，Android 15+ 才有 TrackEvent 路径可选）。tag 应在构建期生成固定值、替换竖线/换行/空字符并处理截断时的 UTF-16 代理对，且不包含业务参数、URL 或账号。`Trace.isEnabled()` 从 API 29 提供：固定字符串的 `beginSection()` 可以直接调用（平台内部会检查 tracing 状态），只有运行时构造临时对象或格式化字符串时才值得先检查。Kotlin 协程是结构性例外：suspend 函数的主要执行逻辑位于生成的 `ContinuationImpl.invokeSuspend()` 状态机，一次源码调用可能跨线程恢复多次，同步 section 无法表达跨线程生命周期——需要异步 trace 或支持关联信息传播的 API。Compose 则用官方 `runtime-tracing` 而不是基于生成方法名的 ASM 规则。

<!-- 来源：02-tracing/03-runtime-profiling.md · Q4 -->

**Q4: [learning] 插桩产物进入 R8 之后，为什么必须在 minified release 上验证 trace？“注入直接调用”和“反射桥接”对 keep 规则的要求差在哪？**

AGP Instrumentation 产生的 class 会继续进入 D8/R8，R8 的内联、合并、删除和 outlining（把重复指令抽成共享方法的优化）都会改变发布产物中的执行形态，只检查未优化 class 不能证明线上 trace 正确。keep 要求分三类：注入的是直接方法调用时，R8 能从调用图找到桥接类，通常无需为整包加 `-keep`。桥接类通过反射或 JNI 按名称查找时，R8 无法从调用图推导目标，需要精确 keep 规则。监控要求与源码方法一一对应时，必须接受发布产物形态变化并直接在 minified release 上验证。不要为保留 trace 结构对整包禁用优化，正确顺序是缩小插桩范围、确认关键方法在 release 中的形态、再为少量边界设规则。

完整验证链有四步：class 级 fixture 测试（`CheckClassAdapter` 不报 verifier 问题、隔离 ClassLoader 可加载执行、原返回值与异常 cause/suppressed 不变、重复经过插件不二次注入——`ClassWriter.COMPUTE_FRAMES` 能修 frame 但证明不了业务语义）。Gradle 集成测试（clean/incremental、configuration cache、build cache、多 variant 多模块）。minified release 产物检查（APK/DEX 大小、R8 `mapping.txt`/seeds/usage 归档、目标调用存在、排除类未被修改）。代表设备上采集 Perfetto 检查 section 嵌套与 R8 后名称。若 trace tag 保存插桩前类名会暴露内部命名，可改用稳定 method ID 加受控映射，与崩溃栈还原用的 `mapping.txt` 分开保存授权。成本门禁用本工程启用/禁用插桩的 A/B 实测建立，不用文章固定百分比。
