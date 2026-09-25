# APM 平台与 SDK

> 学习资料（文章模式沉淀）。主线：线上 APM 的信号分层与统计口径，客户端 SDK（Tracing、Matrix、btrace、KOOM、LeakCanary、DoKit、Measure）与托管平台（Firebase、商业 APM）各自能证明什么，以及实验室回归（Microbenchmark、Macrobenchmark、Baseline Profile）的测量协议。源文档：android-internals-wiki §17.1《APM 全景、Firebase 与商业平台选型》、§17.2《Matrix、btrace 与 Tracing SDK》、§17.3《KOOM 与 LeakCanary 内存诊断》、§17.4《DoKit 调试工具与 Measure APM 平台》、§17.5《历史开源 APM：BlockCanary、ArgusAPM、AndroidGodEye、Collie 与 Rabbit》、§17.6《Jetpack Benchmark：Microbenchmark、Macrobenchmark 与测量协议》；可本地核对的平台侧机制（`android.os.Trace` 名称限制与 counter、`ApplicationExitInfo` 记录容量与持久化、`FrameMetrics.DEADLINE`、`Looper` 单 Printer 槽位、`Debug.dumpHprofData`、`pm compile speed-profile`、`libmemunreachable` 与 ART 私有符号）按 AAOS13 源码（Android 13）核对并标注版本差异，第三方 SDK 生态（Matrix、btrace、KOOM、LeakCanary、DoKit、Measure、Firebase、Sentry、APMPlus、Bugly 及历史项目）按材料口径转写、不确定处已弱化，材料按 Android 17（API 37）撰写而本地为 Android 13（API 33）；Firebase 的 `_app_start` 区间、帧阈值与限流等文档级口径按材料转写（材料已与官方文档核对），云端服务行为本地无法核对。Trace 采集与 SQL 分析机制见 [./01-Perfetto-采集与SQL分析.md](./01-Perfetto-采集与SQL分析.md)，内存指标与泄漏判定的机制层见 [../performance/04-内存性能.md](../07-performance/04-内存性能.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 应用上线后的性能问题由哪四类工具能力分层处理？为什么客户端 APM、官方信号、线下诊断和 Benchmark 不能互相替代？**

四类能力按采集位置、运行阶段和证据深度互补：客户端 APM 覆盖大量真实设备但受权限与采样预算限制，官方信号口径稳定但服从版本与数据可见性限制，线下诊断证据深但只覆盖可复现场景，Benchmark 擅长改动前后对比但不能代表线上分布。选型的分类依据是这四项差异，不能按"都能看性能"合并。

1. **客户端 APM**：Matrix、KOOM、Measure、自研 SDK 等，运行在 Release 与灰度设备上，输出聚合指标、异常样本、堆栈与 trace；代价是包体、CPU/内存、hook 与插桩的兼容风险和上传平台建设；
2. **官方信号与 SDK**：Android vitals、JankStats、FrameMetrics、`ApplicationExitInfo`、`ProfilingManager`、Tracing，来自系统、Play 或应用进程，指标定义由平台固定；代价是最低 API 版本与回调开销限制；
3. **线下诊断**：Perfetto、Android Studio Profiler、simpleperf、LeakCanary 等，运行在 Debug/QA/实验室，输出完整 trace、调用栈与 heap；代价是需要设备、复现和人工分析；
4. **Benchmark/CI**：Macrobenchmark、Microbenchmark 等，输出可比较的耗时、帧与吞吐；代价是环境控制（编译模式、设备、温度）。

典型协作链路是：线上指标发现"哪个版本、页面、设备群在变差"并筛出样本，线下工具把一个样本展开到线程、调用栈与系统时间线定位原因，Benchmark 用同一实验验证修复，最后回到线上指标确认真实用户分布改善。两个常被忽略的边界：Android vitals 只覆盖 Play 认证设备上从 Play 安装且同意共享数据的用户；设备上的 Android 平台版本与应用内 APM SDK 版本要分别记录，升级 SDK 不会改变 framework 与内核实现，反之亦然。

**Q2: 把 Android vitals、Firebase Performance 与自建 APM 的同类发生率放进同一看板时，为什么不能强求数值一致？上线前要先固定哪些数据合同要素？**

不能强求一致，因为各来源的人群覆盖、分母、时间窗、采样与去重规则不同：Play 的 vitals 只统计认证设备上从 Play 安装且同意共享数据的用户，问题率按日活跃用户（DAU）计算；第三方 SDK 通常按使用会话（session）、启动次数或采样事件计算。合并看板前若不写清这些差异，同一现象会呈现几条互相矛盾的曲线。

数据合同要先于 SDK 接入固定下来，它规定"客户端采什么、服务端怎样解释、多久删除、如何关联构建"：

- 事件名、schema version、字段类型、单位与可空规则，耗时字段的单位写进名称或 schema；
- 指标窗口、分母、去重规则、分位数算法和时区；
- 采样单位是用户、session 还是事件，并把 `sample_probability` 随事件上报用于加权校正；
- Java 的 R8 Mapping ID 与 native 的 ELF Build-ID，用于把堆栈还原到对应构建；
- URL、请求头、日志、路径的脱敏规则与本地保留、上传重试、服务端删除周期。

另一个容易漏的要素是丢弃原因计数（配额耗尽、磁盘已满、已过期、触发限流、上传失败）：服务端收到事件不代表数据完整，不上报丢弃统计的看板只描述"成功上传的人群"。最后，样本数不能直接当发生率——异常触发采样、设备离线、进程死亡和上传限流都会改变样本被看见的概率。

**Q3: Firebase Performance 的 `_app_start` 从什么时刻计到什么时刻？为什么它不能当进程启动或首屏完整耗时用？**

`_app_start` 的起点是 Firebase 首个类的早期 class-load（类加载）近似时间，终点是第一个 Activity 的 `onResume()`；SDK 内部把这条 trace 记为 `_as`，计时使用单调时钟 `elapsedRealtime`（按设备启动后经过时间递增，不受修改系统时间影响）。它是"SDK 可见的启动区间"，不是进程启动或首屏耗时。

它漏掉的时间有两段：Firebase 初始化之前的全部进程时间（从 Zygote fork 到 SDK 早期初始化之间的工作）没有进入起点；第一帧和首屏内容可用也不在终点里——`onResume()` 之后页面可能还在加载数据。两个版本相关的边界：API 24 起源码会读取 `Process.getStartElapsedRealtime()`（AAOS13 的 `Process.java` 中存在该 API），但它只用于实验性 TTID trace 和 `process start → class load` 子区间，不是稳定 `_app_start` 的起点；SDK 会过滤后台触发的进程启动，`22.0.6` 起在 Android 14+ 通过 `ActivityManager.getMyMemoryState()`（AAOS13 同样存在）判断只有前台重要性才生成 `_app_start`。

因此 `_app_start` 适合比较版本趋势，不能回答"首屏何时可交互"。若启动完成的定义是骨架绘制、首批数据展示或可交互，需要另建 custom trace 覆盖该区间，并用 Macrobenchmark 或 Perfetto 校验各阶段。

**Q4: Firebase 自动 screen trace 的 slow/frozen frame 阈值和汇总口径是什么？为什么 120 Hz 设备上的卡顿可能被低估？**

自动 screen trace 的单帧分类是固定 60 Hz 阈值：slow frame 耗时大于 16 ms，frozen frame 大于 700 ms（SDK `22.0.6` 的常量）；控制台的汇总百分比也不是"慢帧数除以总帧数"，而是 screen instance 占比——slow frame 超过该 screen instance 总帧数 50% 的 screen instance 占比，frozen frame 超过 0.1% 的占比。screen instance 指一次 Activity 或 Fragment 展示区间，不是一帧。

高刷新率设备被低估的原因在阈值本身：60 Hz 的刷新周期约 16.67 ms，90/120 Hz 缩短为约 11.11/8.33 ms。一帧即使错过了设备的真实刷新周期，也可能没有超过 16 ms，于是不被计入 slow frame；官方文档明确这是固定 60 Hz 口径，发布前应在真实 90/120 Hz 设备上评估偏差。

采集方式上还有两个边界：Activity 的窗口是 `onActivityStarted()` 到 `onActivityStopped()`，Fragment（SDK 20.1.0+）是 `onFragmentResumed()` 到 `onFragmentPaused()`，Fragment 不独立读取一条 FrameMetrics 流，而是从宿主 Activity 的 `Window` 收集后按生命周期区间取差值——Fragment 重叠或单 Activity + Compose 导航会让页面级结果难解释；自动 screen trace 不能附加 custom metric 与 attribute，需要按 route、滚动阶段归因时应改用 JankStats 记录状态。

**Q5: 接入 Firebase Performance 时，为什么关掉运行时采集仍可能继续执行构建期插桩？两套开关分别怎么配？**

构建期与运行期是两套独立开关：Gradle 插件在构建期对受支持的网络库和 `@AddTrace` 做字节码插桩，运行时 SDK 在进程中记录、采样、暂存并上传——只关闭其中一套，另一套照常工作。经常出现的误会是用 Manifest 元数据关了采集，却让 debug 构建继续插桩。

- **构建期插桩**：`FirebasePerfExtension.setInstrumentationEnabled(false)` 关闭指定 variant（如 debug）的自动网络与 `@AddTrace` 插桩；Gradle property `firebasePerformanceInstrumentationEnabled=false` 对整次构建全局关闭，适合 CI 参数。
- **运行期采集**：Manifest 元数据 `firebase_performance_collection_enabled=false` 使应用默认不采集，之后可调 `setPerformanceCollectionEnabled(true)` 动态开启（例如用户同意隐私政策后）；`firebase_performance_collection_deactivated=true` 强制停用并覆盖 enabled，只有删除该元数据并重新发版才能恢复。

若只想停止采集与上传，无需关闭插桩；若 debug variant 不想携带插桩产物，才需要前者。多进程应用还有一条官方边界：只支持主进程中的 Performance Monitoring，独立 `:remote`、`:push` 等远程进程不会自动获得同等采集能力，需要观察这些进程时仍要保留应用自己的监控。接入清单还应覆盖插件分工——`com.google.gms.google-services` 处理 `google-services.json`，BoM 只管理 Firebase 库版本、不管理 Gradle plugin 版本。

**Q6: Firebase Performance 记录到的事件为什么不一定出现在控制台？采样、限流、时效与告警门槛各怎么影响解读？**

端侧采样、设备限流、批量上传和服务端处理都会减少最终样本，控制台分布只代表"被捕获并被接受的事件"。官方 troubleshooting 的限流口径是：code trace 与 network request trace 合计每台设备每 10 分钟最多发送 300 个事件；SDK 还会通过 Remote Config 获取动态采样率随机选择设备；启用 BigQuery 集成会提高 network trace 上限，但仍不是全量。

各环节的口径：

- **时效**：SDK `19.0.10+`（BoM `26.1.0+`）约每 30 秒批量发送，控制台通常几分钟内可见——"几分钟"不是严格实时 SLA，离线、初始化失败和服务端处理都会继续推迟；旧 SDK 约 36 小时延迟。
- **告警门槛**：App start、custom trace、network 与 screen rendering 告警需要过去一小时至少 100 个样本，低流量版本"没有告警"不等于"没有问题"。
- **BigQuery**：导出对象是 captured events（已被采集并接受的事件），首次启用传播最多 48 小时；它补分析能力，不取消端侧采样。

落地判断：高频轮询和图片请求不会保证逐条保留；小流量分阶段发布可能因样本不足看不出变化；看板结论要同时携带采样率与限流规则。排查采集是否生效时，可在测试构建临时设置 `firebase_performance_logcat_enabled=true` 看 Logcat 的 `FirebasePerformance` 日志，验证后移除。

**Q7: 选型 Sentry、APMPlus、Bugly 这类商业 APM 时，为什么功能清单不足以形成验收结论？以 Sentry 的 UI profiling 为例说明版本分支风险。**

商业平台的能力随版本、合同与部署形态变化，同一产品不同制品线的边界也不同——功能清单是供应商承诺，验收要用项目实际拿到的 artifact 和构造样本做 PoC。通用评审维度至少包括：SDK 覆盖（Android 版本、targetSdk、ABI、主流网络库）、SDK 自身开销（crash、启动、线程、包体）、符号化与样本还原率、远程采样与 kill switch、数据所有权与删除 SLA、16 KB ELF/ZIP 对齐、退出导出成本。

Sentry 的 UI profiling 说明了版本分支的必要性：SDK `8.51.0` 起按系统版本走两条路径——Android 15（API 35）及以上调用系统 `ProfilingManager`，结果是 Perfetto trace，但系统对请求限流、不保证每次都有 profile、且该后端不支持 app-start profiling；API 34 及以下回退到 legacy ART runtime tracer，存在 Sentry 文档列出的 runtime crash 风险。若 API 34 及以下新增 crash 集中在 `libart.so`、`art::Trace::StopTracing` 附近，应先关闭 legacy profiling 或降低采样率再分组复现，不能全部归因给业务 native 代码。Session Replay 默认遮盖文本与图片也不等于满足合规——PixelCopy 策略与 View hierarchy 时序不一致时可能错位，涉及支付、身份的页面要逐屏检查录制结果。

Bugly 的教训是制品线要拆开：普通版 Android changelog 停在 `3.4.4`（2021 年），不能推断 Pro `4.4.x` 的能力；Pro 的 Maven 最新是 `4.4.7.16` 而公开 changelog 只到 `4.4.7.8`，这之间的版本不能按号猜行为；crash、ANR、OOM 默认 100% 上报且不支持采样，其他监控项才支持采样，直接影响事件费用与流量估算；16 KB 适配必须使用 `com.tencent.bugly_16kb` 的 groupId，只升版本号不换 groupId 不算选择了 16 KB 制品。APMPlus 则要核对国内与海外制品的上报地域差异，PoC 设备需加入白名单强制进入采集，否则"没有数据"可能只是没命中采样。以上均为第三方商业 SDK，未本地核对。

**Q8: androidx.tracing 2.0 的进程内 `Tracer` 与经典 `trace {}` 为什么是两条路径而不是替代关系？**

经典 `Trace.beginSection()` / `trace {}` 把同步 slice、异步 slice 和 counter 写入系统 trace 缓冲区，随系统 trace 会话进入 Perfetto；2.0 新增的进程内路径把带字段的事件写入应用进程控制的缓冲区，由 `TraceDriver` 持有 `Tracer` 管理一次 tracing 生命周期、`TraceSink` 决定事件如何序列化输出，`tracing-wire:2.0.0` 提供 Perfetto 格式的 `TraceSink` 实现。两者用途不同，官方也没有弃用经典 API——低频、希望始终进入系统 trace 缓冲区的事件仍推荐它。

进程内路径的采集边界：Android Studio 的 System Trace 目前不会自动采集这部分数据；Benchmark 1.5.0-rc01（预发布线）才能在测试结束后采集并合并目标包的进程内 trace；`tracing-perfetto:1.0.1` 又是另一条独立接入路径（自带 native binary 与启用握手），与经典 API、`tracing-wire` 互不相同。新 API 还支持 category 筛选、metadata、Perfetto flow、counter 与 `traceCoroutine()`，但接入时要自己设计采集启动方式、文件生命周期、丢事件策略和体积控制。

版本坐标：稳定线 `androidx.tracing:tracing:2.0.0` 的 Android 变体 minSdk 23；仍需覆盖 API 21–22 的应用用 1.3.0 兼容线（自 1.3.0 起 `tracing-ktx` 已并入主 artifact）。平台侧的 `android.os.Trace` 机制（同步/异步/counter 语义、127 字符限制、AAOS13 的 libcutils 单路径）已在 01-Perfetto 文档核对，此处不重复。

**Q9: 非 debuggable 进程里 `Trace.isEnabled()` 返回 true 需要什么条件？各 API 段允许应用写 trace 的前提有什么差别？**

`isEnabled()` 为 true 需要同时满足两件事：当前存在能接收应用事件的 trace 会话，且该进程被允许写 app trace。Android 12 以后"默认允许应用 tracing"不表示系统一直在后台记录——没有采集会话时返回 false，昂贵的名称构造应借此跳过或改用 lazy label。

非 debuggable 进程的允许条件按 API 段递进（AndroidX Tracing 源码口径，属第三方库实现，未本地核对；平台行为按版本转写）：

1. **API 21–28**：需在应用启动早期调用 `Trace.forceEnableAppTracing()`——这是 AndroidX 提供的兼容入口（AAOS13 平台源码没有同名公开 API），内部反射平台隐藏方法；
2. **API 29–30**：Manifest 设置 `<profileable android:shell="true"/>`，或仍用 `forceEnableAppTracing()`；
3. **API 31+**：默认允许；显式设置 `<profileable android:enabled="false"/>` 或 `shell="false"` 会限制该能力，`forceEnableAppTracing()` 在这一段是无操作。

采集侧还要包含目标应用：Macrobenchmark 会自动采集目标应用的自定义 trace point；自定义 Perfetto 配置要把目标包放进 atrace 的应用配置。正式性能结论应来自 non-debuggable、profileable 且接近发布配置的构建——debuggable 包的调试设施与运行时行为会改变时间分布。

**Q10: btrace 3.0 用"运行时 Hook + 同步抓栈"采集方法栈——为什么调小 `-sampleInterval` 也不能保证固定周期采样？`-m` 参数最常见的误解是什么？**

btrace 3.0 已删除 2.0 的编译期全量插桩，改为 ShadowHook 与 JNI Hook 拦截一批高频或可能阻塞的 ART 路径（对象分配、JNI 调用、Monitor 锁、GC、`Object.wait()`、`Unsafe.park()`），Hook 点在目标线程上同步抓栈、只保存 `ArtMethod*` 与轻量信息——样本只在经过 Hook 点时产生。因此 `-sampleInterval`（默认 1,000,000 ns，即 1 ms）是同一线程两次同步抓栈之间的最小间隔，不会启动严格每 1 ms 唤醒的定时采样器；线程在两次 Hook 之间执行的短方法不会被记录，长期阻塞在未覆盖入口的线程也可能没有样本。最终 Perfetto 里的 slice 由样本与 Hook 上下文重建，不能当每个方法精确的 enter/exit 计时。

`-m` 的误解源于版本差异：它接收的是 ProGuard/R8 mapping（混淆名到原始名的映射，用于反混淆），不是 btrace 2.0 的方法 include/exclude 插桩表——3.0 已不存在那种配置。其他边界：`RheaTrace3.init()` 会直接跳过非主进程，官方 3.0 不能采集 remote 进程的 Service；冷启动采集靠 PC 端写入系统属性再重启应用；README 把 `perfetto` 模式的默认边界写成 Android 8.1，但实现按 `SDK_INT >= 28`（Android 9）选择，文档与实现冲突时应以所用 commit 的代码为准。btrace 为第三方 SDK，未本地核对。

**Q11: btrace 的开源 Android 产物为什么不能直接接入 Android 17 / API 37 生产项目？维护 source fork 至少要验收什么？**

因为它 Hook 并解析 ART 私有实现且没有公开 ABI 承诺，预编译产物也不满足 16 KB 对齐。落到具体机制：核心方法采样解析旧的 `art::StackVisitor::WalkStack<CountTransitions::kNo>(bool)` C++ 符号；AAOS13 的 `art/runtime/stack.h` 中该成员模板已是"单模板参数 `CountTransitions` + bool 参数"的形态，材料称 Android 17 又给 `WalkStack` 增加了第二个模板参数、新旧 mangled symbol 不同——符号解析失败时 `StackVisitor::init()` 返回 false，方法采样整体失效。随包 `librheatrace.so` 与 `libc++_shared.so` 的 ELF LOAD 段对齐为 4 KB（`2**12`），不满足 16 KB 环境要求的 `2**14`。

结果上的陷阱：Android 17 设备上可能仍产出一份只有系统轨道的 `.pb`，文件存在不能证明方法采样成功。维护 fork 的验收至少包括：

1. 更新 `WalkStack` 的函数类型与 mangled symbol，并在量产 user/release 构建上验证（不能只在 AOSP 调试构建搜索同名函数）；
2. 用 NDK r28+ 重编 native 库，`mmap` 偏移改按运行时 page size（`getpagesize()`）计算，移除写死的 4 KB 常量；
3. 对最终 APK 执行 16 KB ELF 与 ZIP 对齐检查（`llvm-objdump -p ... | grep LOAD` 与 `zipalign -c -P 16 -v 4`）；
4. 以"采样记录数大于零、方法能正确符号化"为自动验收条件，解析失败要显式报错；
5. 量化开启/关闭采集时的启动、帧、CPU、内存与包体开销。

没有私有 ART Hook 维护能力时，替代方案是 Perfetto、`androidx.tracing`、simpleperf 或 Profiler。btrace 为第三方 SDK，未本地核对；ART 符号存在性按 AAOS13 核对。

**Q12: Matrix 2.1.0 的 Gradle 插件为什么在 AGP 8+ 工程无法工作？评估 Matrix 时运行时模块和构建插件为什么要拆开？**

AGP 8.0 删除了整个 `com.android.build.api.transform` 包，而 Matrix 2.1.0 的构建插件仍继承 `Transform`、把 `android` extension 强转为旧 `AppExtension`，并依赖 `BaseVariant`、`DexArchiveBuilderTask` 等未承诺兼容的内部 API——切换到 task injection 也不足以支持 AGP 8，官方仓库没有迁移到新 Instrumentation API 的实现。官方 README 只声明插件可配合 AGP 3.5.0/4.0.0/4.1.0；Maven Central 的 `matrix-android-lib` 最新正式版停在 2.1.0（2023-03），公开维护明显放缓。

运行时模块与构建插件拆开评估的原因是它们的风险面不同：IO Canary、Resource Canary 等运行时模块不经过 Transform，可以在现代工程中单独引入，但要逐项核对——IO Canary 通过 PLT hook 代理指定 Java 运行库的 `open/read/write/close` 且只在主线程进入收集器，覆盖范围小于"全进程 I/O 审计"；Resource Canary 以 Activity 弱引用重检为中心，Fragment/View 引用要靠 LeakCanary 补足；所有带 native 代码的模块都要检查 2023 年预编译 `.so` 的 16 KB ELF 对齐。构建插件则要自行移植到 Instrumentation API 或采用持续维护的 fork，移植时必须保留类过滤、方法 ID 分配与 `methodMapping.txt` 的写入语义——方法 ID 不天然跨构建稳定，未正确使用 `baseMethodMapFile` 时同一方法下次构建可能换 ID。

运行时接入顺序本身简单但有序：`Matrix.Builder` 上 `plugin()` 注册插件、`pluginListener()` 接收 `Issue`，`Matrix.init()` 安装进程内单例后 `startAllPlugins()`；未注册的插件实例不会进入插件集合，`getPluginByClass()` 也找不到它。多进程应用不要在每个进程照搬同一配置，还要分别记录"未安装、安装失败、已停止"三种状态。Matrix 为第三方 SDK，未本地核对。

**Q13: Trace Canary 的方法调用树报告依赖哪两份映射文件？插桩失效时报告会出现什么"假象"？**

依赖两份职责不同的文件：构建期 `MethodCollector` 为选中方法分配整数 ID 并写出 `methodMapping.txt`（方法 ID 到方法签名的映射），服务端还需要 R8 mapping（混淆前后名称映射）才能还原最终符号。缺任一份，整数方法栈就还原不成可读调用树；mapping 与 APK 构建不匹配时会还原成错误的方法名。

运行时机制是：修改后的字节码在方法进入和退出处调用 `AppMethodBeat.i(id)` / `AppMethodBeat.o(id)`，`AppMethodBeat` 用 ring buffer 记录 ID、进出标志与相对时间；主线程 Looper 每次 dispatch 消息的开始与结束界定一条消息的分析窗口，超时窗口内整理出调用树、耗时与场景再经 `PluginListener.onReportIssue(Issue)` 上报。这里有两个必须知道的假象：其一是插桩器失效后，Looper/FPS 信号可能仍然存在而方法树缺失——不能据此下结论"主线程没有执行过业务方法"；其二是没有保存与该构建一一对应的 `methodMapping.txt` 时，服务端会把整数栈还原成不可靠的名称。

使用边界：阈值应按场景配置（卡顿窗口、冷启动、FPS 分桶使用不同信号），没有通用固定毫秒数；方法插桩无法替代逐帧指标——jank 判定与界面状态交给 JankStats/FrameMetrics，调度、锁、Binder 等系统原因转到 Perfetto；CI 中应让插桩失败显式使构建失败，而不是静默发布空方法栈。Trace Canary 为第三方 SDK，未本地核对。

**Q14: LeakCanary 2.14 默认自动观察哪几类对象？为什么 Service watcher 被列为高版本验证重点？**

`AppWatcher.appDefaultWatchers()` 默认安装四组 watcher：Activity（`onDestroy()` 完成）、Fragment 与 ViewModel（`onDestroy()`、fragment view 的 `onDestroyView()`、`onCleared()`）、root view（从 WindowManager 脱离）、Service（`onDestroy()` 后 system_server 收到 `serviceDoneExecuting()`）。"默认观察 View"不能推广成"所有 detached View 都会被扫一遍"——Fragment view 来自 Fragment 生命周期，root view 来自对 WindowManager 根 View 的监听，普通子 View 没有统一生命周期，业务仍要在明确终点自行调用 `expectWeaklyReachable()`。

Service watcher 是重点，因为它依赖 framework 私有实现：反射 `ActivityThread.mH`、`mServices`、`Handler.mCallback`、`IActivityManagerSingleton` 并识别消息号 `STOP_SERVICE = 116`。AAOS13 源码中这些成员与消息号同样存在（`ActivityThread.java` 的 `mServices` 与 `H.STOP_SERVICE`、`Handler.java` 的 `mCallback`），但它们不属于公开 SDK 兼容契约——hidden API 策略、OEM 修改或平台演进都可能让反射失败，而 watcher 失败是静默的（记日志并放弃 Service 自动观察）。做法：测试包启动后检查 Logcat 是否出现 `Could not watch destroyed services`，关键 Service 在 `onDestroy()` 末尾再做一次业务侧观察；自定义观察放在不可逆的资源清理终点，不观察 Application、全局 cache 等设计上常驻的对象。LeakCanary 为第三方 SDK，未本地核对；反射目标存在性按 AAOS13 核对。

**Q15: LeakCanary 把泄漏分为 Application Leak 与 Library Leak 的依据是什么？给自家问题挂 matcher 会造成什么后果？**

依据是 `ReferenceMatcher`：命中已知系统或第三方依赖引用模式的是 Library Leak（应用按正常 API 使用时仍受系统/依赖 bug 影响，修复权可能不在应用侧，但内存仍被占用），没有命中的按 Application Leak 报告——多数 suspect reference 可由应用控制。matcher 是"某条引用在指定版本与设备条件下如何分类"的规则，不删除 heap 中的对象，但会改变分类结果和路径搜索。

后果来自默认断言行为：instrumentation 测试的默认 reporter 只对 Application Leak 抛 `NoLeakAssertionFailedError`——如果把自家 singleton、listener 或 adapter 的泄漏挂成 Library Leak matcher，CI 会误判为通过，而泄漏继续占内存。例外规则因此要守纪律：matcher 精确到 class、field、依赖版本，不用宽泛包名前缀；description 关联上游 issue、内部 owner、加入日期与删除条件；升级 SDK 或 Android 版本后重跑一遍不带自定义 matcher 的对照测试，删除不再命中的规则；`@SkipLeakDetection` 也要写明原因并设到期检查。

对 Library Leak 本身的处置是按影响管理：记录 signature、系统版本、依赖版本与 retained size，查上游修复版本并用升级/降级对照验证；高流量页面或大 retained size 即使来自系统，也要评估生命周期顺序或隔离方案。CI 可以暂不为团队无法修复的问题失败，但要保留报告和趋势。LeakCanary 为第三方 SDK，未本地核对。

**Q16: 为什么 LeakCanary 的完整能力（自动 dump、分析、通知）只能进 debug 变体？线上要用 heap 分析时要处理哪些问题？**

官方要求 `leakcanary-android` 用于 debuggable 构建，非 debuggable 进程初始化默认抛错，以便尽早暴露误接入。完整能力的成本是结构性的：heap dump 会暂停被测进程并把当时 Java 堆写入文件，文件可能包含字符串、URL、token、业务对象，体积可达数十到数百 MB；Shark 分析还要消耗 CPU、内存、I/O 与电量。`stripHeapDump` 把 primitive array 内容归零，能降低字符串暴露，但不等于文件已匿名化。

线上路径的三个 artifact 边界不同：`leakcanary-object-watcher-android` 只提供候选计数（`retainedObjectCount`），没有引用链，5 秒观察窗口与生命周期噪声不能换算成线上泄漏发生率；`leakcanary-android-release` 提供生产环境 heap analysis API，但官方标为 experimental，触发、取消、脱敏、存储与上报都要自行设计。若确要上线，至少要有：服务端开关、低比例采样与单设备上限；只在后台或用户同意时触发；使用应用私有且不备份的临时目录、分析后删除 Hprof；优先上传分析结果而非原始 Hprof；dump 时长、失败率、文件大小超限时自动熔断停用。

因此默认策略应是分工：线上信号交给 KOOM、自研 APM 或 Android Vitals 回答"影响多大、在哪里发生"，LeakCanary 在可控的 Debug/QA 包里回答"哪条 Java 强引用需要修改"。LeakCanary 为第三方 SDK，未本地核对。

**Q17: KOOM 的 java/native/thread leak 三个模块各判定什么？公共版本的 API 与 ABI 门槛怎么限制接入？**

三个模块的判定口径不同：java leak 轮询主进程前台状态，命中 heap 使用率、线程数或 FD 数阈值后用 `fork()` 派生子进程生成 Hprof，再由 Shark 生成引用链 JSON；native leak 用 PLT hook 记录目标 `.so` 的活跃分配，与 `libmemunreachable` 的不可达内存结果求交集产出候选；thread leak 只识别一种情况——joinable 线程已退出但没有被 `pthread_detach` 或 `pthread_join` 回收。仍在运行的匿名线程、无界线程池的 worker、长期 WAITING 的业务线程都不是 thread leak 的判定对象，需要另一套观测。

门槛直接写在源码里（KOOM 为第三方 SDK，未本地核对；涉及的平台符号按 AAOS13 核对）：`DefaultInitTask` 与 `ForkJvmHeapDumper` 限 API 21–36，Android 17 / API 37 会被拒绝；native leak 限 API 24+ 且仅 arm64；thread leak 限 API 28–34 且仅 arm64。Maven Central 最新正式版 `2.2.2`（2024-04）不含上游后来合入的 Android 15 fast dump 与 16 KB 修改。因此 Android 17 项目不能把 `2.2.2` 当生产依赖，需要维护 fork 并逐符号验证。

fork 依赖的私有符号在平台侧确实存在：KOOM fast dump 解析 `libart.so` 的 `art::hprof::DumpHeap`、`art::ScopedSuspendAll`、`art::gc::ScopedGCCriticalSection` 等——AAOS13 的 `art/runtime/hprof/hprof.h`、`art/runtime/thread_list.h`、`art/runtime/gc/scoped_gc_critical_section.h` 中都能找到对应声明，native leak 依赖的 `libmemunreachable` 及其 `GetUnreachableMemoryString(bool, size_t)` 接口也在 `system/memory/libmemunreachable/` 中存在。但"源码里有"不构成应用可依赖的 ABI 承诺，平台版本升级需逐符号重新验证。

**Q18: KOOM 用 fork 子进程写 Hprof 来降低主进程停顿——为什么诊断动作本身仍可能加速进程退出？自动路径产出的 Hprof 会脱敏吗？**

fork dump 的顺序是暂停 ART、调用 `fork()`、恢复父进程、由子进程写 Hprof。copy-on-write 让子进程创建时不复制整块堆，主进程停顿因此缩短；但父子进程随后修改的页面仍会各自增加物理内存，子进程还要消耗 CPU、文件 I/O 与磁盘——在可用内存已经很低的场景（正是触发 dump 的场景），诊断动作本身可能失败或加快进程退出。这是"降低停顿"与"消除资源风险"的区别，接入方要为 dump 失败、子进程被杀、磁盘不足设计降级。

触发与额度边界：`OOMMonitor` 只在主进程前台轮询（默认 15 秒），heap 使用率连续 3 次超过阈值（大堆 80%、中堆 85%、小堆 90%）或线程数超 750、FD 数超 1000 会触发 dump，heap 超 90% 或单间隔增长超 350000 KB 立即触发；每个进程生命周期最多自动 dump 一次，非 debug 构建还有"每版本 5 次、首个 15 天内"的额度。这些默认值是上游策略而非通用安全值，业务还要叠加远程开关、采样与冷却时间。

Hprof 的答案是否定的：自动路径调用 `ForkJvmHeapDumper`，产出原始 Hprof——源码虽有 `ForkStripHeapDumper`，但自动监控不使用它。文件可能包含字符串、账号数据、图片字节与业务对象，生产环境应在设备内完成分析、只上传引用链 JSON 摘要；确需保留 Hprof 要单独授权并用私有目录、短保留期与加密传输。另一个坑是 `isSpaceEnough()` 只检查约 1.2 MB 可用空间，远低于真实 Hprof 需求，磁盘配额要自己算。KOOM 为第三方 SDK，未本地核对。

**Q19: DoKit 的"断网""超时"与 Mock 模式为什么都会先发出真实请求？哪些接口绝对不能用它们测试？**

因为它们的实现位置在 OkHttp network interceptor：`DokitWeakNetworkInterceptor` 的断网与超时模式都先执行 `chain.proceed(chain.request())` 让真实请求出网，真实请求成功后才丢弃响应内容、构造 HTTP 400 返回给应用（超时模式先 sleep 再发真实请求）；`DokitMockInterceptor` 同样先执行原请求，命中本地规则后再向 Mock 平台发第二个请求、用平台数据替换应用最终收到的响应。Mock 发生在真实请求之后，不是请求发出前的短路。

由此产生的风险是服务端状态已被改变而界面显示失败：支付、下单、创建、删除、上传、埋点等会改变服务端状态的接口绝对不能用这两个模式测试——用户看到失败后重试，还会再提交一次。另外若底层真实请求本身抛出异常，异常会直接向上返回，DoKit 不会生成 400，所以要验证 `IOException`、`UnknownHostException`、`SocketTimeoutException` 分支，必须用受控测试服务器、MockWebServer 或代理按目标异常构造真实失败。

适用范围因此收窄：断网/超时模式只用于安全、幂等的接口（幂等指同一请求重复执行不产生额外业务结果），观察 UI 收到 400 后的状态与重试入口；Mock 用于无副作用查询接口上的空列表、字段缺失、超大列表与错误码页面；限速模式适合观察大 Body 慢速读写下的 UI 表现。对状态修改接口，改用测试环境服务端 Mock 或依赖注入替换数据层。DoKit 为第三方 SDK，未本地核对。

**Q20: DoKit 性能浮窗的 FPS、CPU、内存、卡顿读数各来自什么数据源？为什么浮窗数值不能当版本级性能结论？**

四个读数的来源与缺陷各不相同，共同点是"线索级精度"：FPS 统计的是每秒收到的 `Choreographer.FrameCallback` 回调数，主线程上的 Handler 任务每 1000 ms 读取并清零计数，上限取屏幕刷新率——而统计任务也在主线程，主线程堵塞时"一秒窗口"会延后且代码不按真实经过时间归一化，内置健康体检路径还会把上限压到 60，丢失高刷信息；CPU 每 500 ms 执行一次 `top -n 1` 再把 `%CPU` 除以核数，`top` 输出格式不属于 SDK 兼容契约且算法与其他工具未必一致；内存每 500 ms 读 `Debug.getMemoryInfo()` 的 totalPss，PSS 是采样时刻的驻留值，不指向具体对象；卡顿监控阈值固定 200 ms、堆栈从 300 ms 后开始按 300 ms 间隔采样——200–300 ms 已越阈值但没采到栈的事件会被丢弃，一份堆栈只代表某个采样时刻。

卡顿模块还有一个结构性冲突：它依赖 `Looper.setMessageLogging()`，而一个 Looper 只有一个 `mLogging` Printer 槽位（AAOS13 的 `Looper.java` 同为单字段）。DoKit 的卡顿与 TimeCounter 模块会互相停用、停止时把 logger 置 null，应用内其他使用同一接口的监控器也会被覆盖——现场没有记录不能证明没有卡顿。

所以浮窗的用途是发现"哪个页面、哪个操作值得继续查"；版本级帧结论用 JankStats/FrameMetrics，启动与交互结论用 Macrobenchmark，网络阶段拆分用 OkHttp `EventListener` 或系统 trace。DoKit 为第三方 SDK，未本地核对。

**Q21: 在 API 37 工程里接入 DoKit 3.7.11 有哪些硬边界？Release 隔离最终以什么为准？**

3.7.11 公开维护停在 2023 年，接入 API 37 工程前要先接受四条硬边界：其一，`dokitx-plugin` 在 `DoKitPlugin.kt` 里走 AGP 8.0 已移除的 Transform API（`registerTransform()`），AGP 8+ 不能加载，且它只按顶层 task 名判断 release 变体、聚合构建时可能误判；其二，运行时 `HandlerHooker` 反射 `ActivityThread.currentActivityThread`、`mH`、`Handler.mCallback`（AAOS13 源码同样存在这些成员，但无兼容承诺），并在 Android 12+ 跳过 SandHook 全局 hook（源码注明会崩溃）——hook 失败是静默的，只打日志；其三，库 manifest 声明 targetSdk 31 与悬浮窗、存储、电话、相机、前台服务等权限，合并进宿主后要按 target 37 重审（Android 14 起录屏前台服务需要 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限而库未声明）；其四，运行时默认 `ENABLE_UPLOAD=true` 会出站上传应用信息，必须显式 `disableUpload()` 并抓包核对测试包流量，可选模块 `dokitx-pthread-hook` 还传递 Matrix 2.0.2 的 4 KB 对齐 `.so`，不满足 16 KB。

Release 隔离分两层验收。依赖层：`dokitx-no-op` 只提供空实现 API，能降低误引用成本但不能阻止误加完整运行时，CI 要检查 `releaseRuntimeClasspath` 只允许 no-op。产物层：解开最终签名的 APK/AAB，确认没有 DoKit 类与面板资源、没有多余权限与宽路径 FileProvider、没有统计与健康检查出站请求、没有测试账号入口与调试 deep link——依赖声明只是证据之一，签名产物才是验收对象。DoKit 为第三方 SDK，未本地核对。

**Q22: Measure APM 在 Android 17 上支持 OOM 自动 heap dump 吗？它的会话与采集模型有哪些边界？**

不支持。稳定版 `0.19.0` 仍以 compileSdk 36 构建，`ProfileCollector` 只注册 `TRIGGER_TYPE_APP_FULLY_DRAWN` 与 `TRIGGER_TYPE_ANR` 两种 `ProfilingTrigger`；Android 17 新增的 OOM、冷启动等 trigger 尚未接入 SDK，看板出现 `profile` 附件不能宣传为"已支持 OOM 自动 heap dump"。平台侧背景：`ProfilingManager` 是 API 35 引入、trigger 从 API 36 起可注册，AAOS13（API 33）的源码树中不存在这套 API。

其他能力边界：Android 端尚无 C/C++ native crash 上报——`app_exit: CRASH_NATIVE` 只是退出原因分类，不代表具备 native 崩溃堆栈采集；ANR 由 native `SIGQUIT` 处理器把信号让渡回 ART Signal Catcher 采集，保存主线程栈和有上限的其他线程栈（`MAX_THREADS_IN_EXCEPTION = 16`）；session 在应用后台超过 30 秒后再回前台即新开；CPU/内存默认 5 秒采样是曲线不是火焰图，上涨不能直接判泄漏；span 名称上限 64 字符，动态值放受控属性，`traceparent`（W3C Trace Context）可把移动 span 关联到服务端 trace；事件先写本地 SQLite（默认 50 MB 上限）再批量上传，远端 Adaptive Capture 修改后通常到下一次启动才生效。数据面是完整平台：self-host 由 ClickHouse、PostgreSQL、MinIO、Apache Iggy 等服务组成，需要持续运维。Measure 为第三方开源 SDK，未本地核对。

**Q23: BlockCanary 检测主线程卡顿的采样窗口是怎么安排的？为什么阈值内前段的热点可能完全错过？**

采样不是从 dispatch 开始就固定间隔抓栈：第一次 Printer 回调记录 wall time 与主线程 CPU time 并启动 sampler，首次采样被安排在 `threshold × 0.8` 时刻，之后按 dump interval（默认等于阈值）重复抓栈，dispatch 结束时按 wall time 判定是否超阈值。举例：阈值 1000 ms、自定义间隔 300 ms 时，一次持续 1200 ms 的 dispatch 只在约 800 ms 和 1100 ms 两个时间点采样——前 200 ms 的真实热点可能完全错过；若时间窗内没有栈样本，原实现不会生成 `BlockInfo`。

它测到的是一次 message dispatch 的 wall time，不包含消息在队列里的等待（delivery delay），也不覆盖输入处理、RenderThread、GPU 到 present 的完整帧流水线；采样栈只说明取样瞬间主线程所在位置——wall time 长而 thread CPU time 短时，要用 Perfetto 区分 Runnable 饥饿、Binder、锁等待或 I/O。

共享冲突与现代替代：BlockCanary 用公开的 `Looper.setMessageLogging()` 安装 Printer，而一个 Looper 只有一个 message logger 槽位（AAOS13 的 `Looper.mLogging` 为单字段），后安装的 SDK 会覆盖先安装者，BlockCanary 停止时还会把 logger 置 null——自研方案可装统一分发器转发回调，但无法阻止其他 SDK 后续覆盖。现代 Android 上帧体验交给 JankStats 与 FrameMetrics，系统已判定的 ANR 用 `ApplicationExitInfo` 与系统 trace，BlockCanary 只借鉴 Looper 长消息原理。BlockCanary 为第三方 SDK，未本地核对。

**Q24: Collie 的 FPS、"ANR"、启动与泄漏监控里有哪些口径陷阱？为什么这些实现只能当反例教学？**

Collie 用很少的代码拼出第一批信号，但它的信号是 SDK 自定规则推断的近似值，不是系统定义的指标，反例集中在口径命名与实现方式两类：

- **FPS 按 60 Hz 假设**：把 dispatch 耗时按 16 ms 分桶、用 `cost / 16 - 1` 推掉帧数并把平均 FPS 封顶 60——在 90/120 Hz 与动态刷新率设备上系统性误差；
- **5 秒 dispatch 预警命名成 ANR**：Looper dispatch 超过 5 秒只是"单次主线程停顿"预警，系统 ANR 有 input、Broadcast、Service、Provider 等多类型且超时不统一，事件应命名 `main_dispatch_stall`；
- **启动与页面可见**：用透明 View 的 `onDraw()` 与 window focus 估算——`onDraw()` 只证明该 View 进入绘制，window focus 受启动窗口、弹窗与多窗口影响，都不能命名成系统 TTID/TTFD（TTFD 需要应用调用 `reportFullyDrawn()`）；
- **流量只有 RX**：只读 `TrafficStats.getUidRxBytes()`，没有 TX、URL、请求阶段与错误类型，同 UID 多进程共用计数；
- **泄漏检测主动制造内存压力**：Activity 销毁后放入 `WeakHashMap`，退后台时两次分配约 4 MiB 数组并请求 GC——GC 后仍存活只是"值得检查"的候选信号，主动分配与 GC 还会扰动被测应用；
- **无界队列**：部分事件经无容量上限的 `LinkedBlockingQueue` 转交，消费跟不上生产时把流量高峰转成内存增长——没有处理背压。

可借鉴的是"少量公开入口快速验证信号"的思路；采集器本身应按系统语义重写与命名。Collie 为第三方 SDK，未本地核对。

**Q25: 评估 AndroidGodEye、Rabbit 这类历史开源 APM 要看哪四个维度？把它们的旧能力迁到现代 Android 时怎么映射？**

四个维度决定"仓库里有这个功能"是否等于"能在现代工程里用"：信号语义（采到的是系统定义指标还是 SDK 自推近似值）、运行开销（是否在主线程反射、抓栈、主动 GC）、构建兼容性（Gradle 插件是否依赖已删除的 Transform API 或 AGP 内部类）、维护证据（固定 commit、compileSdk/targetSdk 与发布记录）。AndroidGodEye 的分层与浏览器看板值得借鉴但其构建链停在 AGP 3.2.1；Rabbit 的统一研发入口有价值，但它的插件直接调用 `registerTransform()` 与 `com.android.build.api.transform.*`——AGP 8.0 删除该 API 后没有兼容入口。

迁移映射按能力对号入座：

1. **固定 16 ms 的 FPS/掉帧** → JankStats 或 FrameMetrics，处理可变刷新率与回调复用；
2. **window focus 启动耗时** → `ApplicationStartInfo`（API 35+，AAOS13 无此 API）与应用主动调用 `reportFullyDrawn()`，区分 TTID 与 TTFD；
3. **5 秒 Looper "ANR"** → 命名为主线程 stall 并配 `ApplicationExitInfo`（API 30+，AAOS13 已核对该 API 与 reason 常量；API 34 新增 `REASON_PACKAGE_STATE_CHANGE/UPDATED` 不在 AAOS13，API 37 的 ANR warning 需用 `SDK_INT_FULL` 区分次版本）；
4. **UID RX 流量** → 网络层 interceptor，补 TX、阶段耗时与脱敏；
5. **主动 GC 的泄漏提示** → LeakCanary 对象可达性分析或受控 heap dump；
6. **旧 Transform 插桩** → Instrumentation API（逐类改写）或 ScopedArtifacts（需读全工程 class 时），回归插桩范围、增量构建与 R8 mapping 关联。

默认动作是阅读和移植需要的设计，而不是把旧 artifact 一次接入——旧项目没有现代验证报告时，接入决定必须由自己的回归测试支撑。以上项目均为第三方 SDK，未本地核对。

**Q26: Microbenchmark 里手工加一万次循环、循环内打日志、返回值没人用分别测错了什么？warmup 与 AOT 口径为什么必须写进报告？**

三种写法测的都不是目标代码：手工循环把被测循环与 `BenchmarkRule` 自己的 warmup 和测量循环叠在一起，样本混合了多套循环节奏；循环内打日志引入 I/O、锁与 Logcat 成本；返回值无人消费时，编译器或 R8 可能把计算连同结果一起删除——需要用 `BlackHole.consume()` 防止消除（stable 1.4.1 中它仍标 experimental 需 opt-in，1.5.0-rc01 才稳定化）。Microbenchmark 的协议要点由此确定：`measureRepeated {}` 的 block 表示一次操作，循环由库管理；固定 seed 的 fixture 在 `@Before` 准备、循环内只放被测工作与消费操作；可变状态在 `runWithMeasurementDisabled()`（旧名 `runWithTimingDisabled` 已弃用，暂停的是全部测量指标不只计时）内恢复且恢复代码保持轻量；UI 场景用 `measureRepeatedOnMainThread()` 避免长测量触发 ANR。

编译口径决定结果可解释性：使用 AGP 8.4.0+ 并应用 `androidx.benchmark` Gradle 插件时，Microbenchmark APK 默认 full AOT 编译，目标是压掉 JIT 稳定期的波动；`androidx.benchmark.forceaotcompilation=false` 退出该默认、结果更接近 warmup 后的 JIT 状态。full AOT 更稳定但不代表用户设备的常态编译状态——依赖 JIT 行为的局部优化要补一组关闭强制 AOT 的实验。两组结果只在编译配置一致时才可比较，报告必须记录 Benchmark、AGP、Kotlin、R8 与该开关。

**Q27: Macrobenchmark 为什么必须用独立的 `com.android.test` 模块和 non-debuggable 且 profileable 的目标应用？setupBlock 与 measureBlock 各承担什么？**

Macrobenchmark 由单独安装的 test APK 从外部进程驱动目标应用（UiAutomator 负责启动、手势与等待），所以目标应用要保持 non-debuggable 并声明 `<profileable android:shell="true"/>`——允许 adb shell 启动的受控工具读取详细 trace，又不会把应用变成 debuggable。工程结构随之固定：`:app` 的 `benchmark` build type 用 `initWith(release)` 继承 R8 与资源压缩、只换成 debug 签名，`matchingFallbacks` 指向 release 让依赖模块正确匹配；`:macrobenchmark` 模块用 `com.android.test` 插件并声明 `targetProjectPath`，test APK 的 benchmark variant 可以 debuggable（与目标应用的 non-debuggable 不要混淆）；`:app` 还要依赖 ProfileInstaller（1.3+），它负责把 Baseline Profile 交给系统，也提供清 shader cache、重置 profile 的命令通道。

每轮的控制流是：先按 `CompilationMode` 重置或编译目标应用（编译状态重置在平台侧依赖 `pm compile`——AAOS13 的 `PackageManagerShellCommand` 提供 compile 子命令并支持 `speed-profile` 与 `--check-prof`），然后执行 `setupBlock` 与 `measureBlock`。`setupBlock` 不计入指标但决定每轮起点是否一致（按 home 键、deep link 载入固定数据、等待就绪标记）；`measureBlock` 执行被测交互，其中的任何等待都会进入 trace 的测量窗口，某项指标是否计入这段等待由该指标的定义决定。每个 measured iteration 生成一份 Perfetto trace，是解释数字变化的直接证据。

**Q28: 为什么"生成 Baseline Profile"和"验证 Baseline Profile 收益"是两项任务？生成环境与验证实验各有什么要求？**

生成只产出热点方法与类的清单，文件存在不证明它在该 APK 与设备上生效——收益要靠成对的编译状态对照实验证明，两者环境要求也不同。

生成侧：`BaselineProfileRule.collect()` 在非 root 设备需要 API 33+、rooted 设备需要 API 28+，这只是 profile 收集门槛；`includeInStartupProfile = true` 把启动入口到 fully drawn 的路径纳入启动 profile，普通滚动或二级页面旅程应另建 `collect()` 并保持 `false`。生成后不是一劳永逸：关键用户旅程变化、R8 mapping 变化、启动依赖调整和大版本升级都要求重新生成。

验证侧要求成对运行两个 `CompilationMode`：`None()` 清除预编译作为对照，`Partial(BaselineProfileMode.Require)` 要求 APK 内 Baseline Profile 可安装并以 `speed-profile` 编译、装不上直接失败——不能用 `DEFAULT`（等价于 `Partial(UseIfAvailable)`，profile 缺失时不失败）代替严格验证。两组必须使用相同 APK、fixture、设备与迭代数；`Partial(Require)` 需要 API 24+（API 23 只有 `Full()`），其编译动作在平台侧走 `pm compile` 的 `speed-profile` 路径（AAOS13 已核对）。补充证据可用 `ArtMetric`（API 24+）观察启动期 JIT、类加载与校验工作是否减少，再配合启动/滚动指标确认端到端改善。

**Q29: `FrameTimingMetric` 的三个输出怎么读？`TraceSectionMetric` 默认 `Mode.Sum` 的陷阱是什么？**

`FrameTimingMetric` 输出三类样本：`frameDurationCpuMs` 是 UI thread 与 RenderThread 产出一帧的 CPU duration（API 31 前无法计入 `Choreographer#doFrame` 开始前的时间）；`frameOverrunMs`（API 31+，AAOS13 的 `FrameMetrics.DEADLINE` 已支持）是相对帧 deadline 的超期或余量——正值超期、负值仍有余量，变刷新率设备上应优先用它判断 deadline 表现；`frameCount` 记录测量窗口内产出的帧数，用来解释"删掉无效帧后分位数反而上升"这类样本变化。它仍要与 FrameTimeline、主线程、RenderThread 与 GPU 轨道联读，不能凭一个分位数定位根因。

`TraceSectionMetric` 的陷阱在默认聚合方式：stable 1.4.1 中它标注为 `ExperimentalMetricApi`，默认 `Mode.Sum` 会把全部匹配 section 的时长求和并输出次数——遇到递归或重入 section 时，重叠时间会被重复相加；只取第一条要显式选 `Mode.First`，另有 `Min`、`Max`、`Count`、`Average`。它默认只匹配目标包，且忽略未闭合（`dur = -1`）的 slice。用于基准指标的 trace 名称必须稳定，并打开每轮 Perfetto trace 复核选中的区间是否符合预期。

其他指标的门槛：`PowerMetric`（API 29+）的高精度读数依赖设备 power rail，使用前检查 `deviceSupportsHighPrecisionTracking()`；`MemoryUsageMetric` 适合固定操作窗口，不能替代线上 OOM 与 LMKD 证据。

**Q30: 把 Benchmark 接进 CI 门禁时，为什么不能照搬单元测试的 pass/fail？门禁阈值应该怎么定？**

Benchmark 的输出是连续数值，受设备、温度、编译状态与系统负载影响，单次运行越界可能只是噪声——门禁必须先量化噪声再定阈值，判定规则是"差值同时超过业务绝对预算与历史噪声上界才判回归"。

落地步骤按依赖顺序：

1. 固定一小组物理设备，为每台设备建立历史序列；
2. 对 main 分支定时运行，估计每个 metric 的自然波动带（MAD、置信区间或团队已有统计模型），不要在没有历史数据时随意写"慢 8% 就失败"；
3. PR 在同型号、同系统 fingerprint 的设备上运行 baseline 与 candidate；
4. 判定用 `candidateMedian - baselineMedian > max(业务预算, 历史噪声上界)`；
5. 首次越界在同一设备自动重跑，两轮方向一致才阻断；
6. 归档 raw JSON 与每轮 Perfetto trace，供人工复核起点、样本数与异常负载。

样本数决定统计口径：启动跑 10 次适合看 median 与逐次 trace，P95 接近极值——CI 要用 P95 必须增加样本并从 JSON 的 `runs` 计算；帧指标一轮就有大量样本，可看 p90/p95/p99 但要同时看 frame count。两个边界：`androidx.benchmark.suppressErrors` 会把 debuggable、模拟器、低电量等配置错误降级为警告，只可用于验证脚本能否跑通的 smoke job，绝不能出现在性能门禁；模拟器可跑 smoke test，性能门禁使用物理设备。
