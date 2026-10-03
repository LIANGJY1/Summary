# ART

> 学习资料（文章模式沉淀）。主线：ART 运行时职责、执行方式演进（AOT/JIT/profile 指导）、GC 演进与 Mainline 化。堆空间与 GC 机制深挖见 [../07-memory/01-memory-management.md](../07-memory/01-memory-management.md)；类加载与 JNI 链接见 [06-art-runtime.md](06-art-runtime.md)；2026-09-25 增补实用调试题（Q2–Q5，按 AOSP 近版源码镜像核对）。Q 序列即结构，供 atlas 同源直读。

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

**Q6: 类加载的 define、verify、initialize 三步各做什么？loadClass 与 Class.forName 的差别落在哪一步？**

define/link 把字节流变成运行时 Class 结构并完成验证与链接，initialize 执行 `<clinit>`；`ClassLoader.loadClass` 只做到 define/link、不触发初始化，`Class.forName(name)` 默认 initialize=true 会执行 `<clinit>`。

前提：`<clinit>` 有副作用（静态块、静态字段赋值），触发时机影响行为。机制：ART 的类状态沿 loaded→resolved→verified→initialized 推进，验证失败可软回退解释器；要"只加载不初始化"时用 `Class.forName(name, false, loader)`。结果：反射工具选 API 要明确目的——拿 Class 对象用 loadClass，需要静态初始化副作用才用 forName(true)。

**Q7: ART 怎么加速跨 ClassLoader 的类查找？DelegateLastClassLoader 的查找顺序特殊在哪？自定义加载器会失去什么？**

API 37 上 ART 的 `FindClassInBaseDexClassLoader` 对"已知形状"的加载器走原生快路径，直接在 boot classpath 与 dex 数组上查找，省去 Java 层委派递归；但只识别精确类型：PathClassLoader/DexClassLoader、InMemoryDexClassLoader、DelegateLastClassLoader。DelegateLastClassLoader 的特殊点是 boot classpath 仍最先查，其次自身 dex，最后才父委派——child-first 只对非 boot 类生效。

机制：快路径按精确类型匹配，避免对未知加载器结构做错误假设，包装器或自定义加载器回退 Java 路径。结果：自定义 ClassLoader 失去快路径；需要 child-first 语义（类隔离、热修复场景）选 DelegateLastClassLoader，但 boot 类永远不可被覆盖。

**Q8: DEX 文件里如何定位一个类？Startup Profile 的 DEX 布局优化为什么能加快启动、从哪个 AGP 版本默认开启？**

先按类描述符在 TypeLookupTable 里哈希定位 class_def，未命中再从 type_id 起顺序扫描（不是二分查找）。Startup Profile 是 Baseline Profile 的子集，构建期按它重排 DEX 类布局、把启动路径聚拢，官方口径比只用 Baseline Profile 启动再快 15%–30%；DEX 布局优化从 AGP 8.1 起可用（dexLayoutOptimization）、8.3 起默认开启（已与 developer.android.com 核对）。

机制：类查找成本与目标类在 DEX 中的位置强相关，顺序扫描尤其受布局影响；Startup Profile 只能由启动测试生成、库无法贡献，启动代码控制在首个 classes.dex 内收益最大。结果：Baseline Profile 解决"提前 AOT"，Startup Profile 解决"布局聚集"，两者互补（编译侧见 Q11）。

**Q9: Boot Image 的 .art/.oat/.vdex 各存什么？进程间怎么共享、怎么判断共享是否被打破？**

`.art` 存预初始化的堆对象镜像，`.oat` 存 AOT 编译的原生码，`.vdex` 存验证元数据与原始 dex；三者以 MAP_PRIVATE 映射实现写时复制共享，镜像 bitmap 区段映射为 PROT_READ 只读。

前提：boot image 在开机时由 zygote 映射一次，把 BCP 类的编译与初始化成果摊给所有进程。机制：共享部分计入各进程 RSS 但不重复占物理内存，被写的页转入 Private_Dirty；压缩镜像映射后表现为匿名内存。结果：读 smaps 判断健康度——boot image 区域 RSS 高、Private_Dirty 低是常态，Private_Dirty 异常增大说明有进程在写共享镜像。

**Q10: 设备如何选择 boot image 的位置？odrefresh 什么时候触发重建？**

优先用 APEX 数据目录中经 odsign 验证的镜像：`odsign.verification.success=true` 且 `/data/misc/apexdata/com.android.art/dalvik-cache` 下镜像完整时使用；否则回退 `boot_minimal.art` 或 `/system/framework/<isa>/boot.art`，且 odsign 未验证时以 `deny_art_apex_data_files` 拒用 APEX 数据文件。

机制：ART 模块更新或 BCP 构成变化会让现镜像失效，odrefresh 校验当前镜像的组件校验和与依赖（含 dirty-image-objects），不满足就重建；boot image 编译过滤器用 speed-profile（Android 12 起官方配置）。结果：ART 模块更新后的首次开机可能明显变慢，要区分"镜像回退到最小镜像"与"odrefresh 重建进行中"两种慢。

**Q11: 应用侧可用哪几种编译过滤器？speed-profile 没有 profile 时会发生什么？**

ART Service（Android 14 起管理应用 dexopt）只对应用暴露 verify、speed-profile、speed 三种；speed-profile 依赖 profile 指导，没有可用 profile 时实际生效的过滤器回退为 verify——只验证不编译（已与 source.android.com 的 ART Service 文档核对）。

前提：过滤器决定验证与编译的范围，speed-profile 只编 profile 覆盖的方法。机制：新装应用无 profile、baseline profile 未就位时无热点可依，回退 verify 是官方设计而非异常；查询实际生效值用 `adb shell cmd package art dump`。结果：安装后首启走解释与 JIT 不代表配置错误；要首启即有 AOT，必须让安装时就有 profile 可用（Baseline Profile 途径）。

**Q12: pm.dexopt.* 各场景的默认过滤器是什么？"安装后第一次启动慢"的完整链路怎么解释？**

默认值为 first-boot=verify、boot-after-ota=verify、boot-after-mainline-update=verify、bg-dexopt=speed-profile、inactive=verify、cmdline=verify、shared=speed（已与 ART Service 文档核对）；于是安装或 OTA 后首启只有验证过的码可跑、热点靠 JIT 现编，慢是设计使然，后台空闲充电时 bg-dexopt 以 speed-profile 补齐 AOT。

机制：dexopt 生命周期是"安装期验证 → 运行期 JIT 采热 → 后台 speed-profile"；A/B OTA 还支持重启前 dexopt，让更新后首启直接可用。结果：优化首启不要等后台 dexopt——用 Baseline Profile 让安装期就有 profile，speed-profile 才能生效；判断后台优化是否完成看 bg-dexopt 的执行记录。

**Q13: AOT 编译产物何时失效？vdex 记录的验证信息怎么被复用？**

失效依赖是 boot classpath 构成、boot image 校验和与 classpath context（CLC）——任一变化都会让已编译码的假设失效；vdex 保存验证结果与原始 dex，CLC 变化但验证前提未破坏时，dexopt 可复用验证、只重编代码。

前提：AOT 码可能内联 BCP 方法，BCP 变化即内联假设失真。机制：依赖检查按（BCP 指纹、boot image checksum、CLC）比对，通过则直接复用旧产物。结果：ART 模块是 BCP 的一部分，模块更新会触发大面积后台重编；排查设备"突然大量 dexopt"先看是否刚发生过模块更新。

**Q14: ART 的反优化有哪些触发场景？单帧反优化如何执行、怎么判断健康度？**

典型触发有 inline cache 失效（新类型到达单态调用点）、边界检查消除的假设被破坏、CHA 类层次变化、调试介入（kDebugging）、方法句柄类型不匹配等；执行时编译码在守卫点插入 HDeoptimize，命中后把当前帧重建为解释器 ShadowFrame、从该帧继续解释。

前提：优化基于假设，假设破坏要有受控回退通道。机制：单帧反优化只回退当前帧，外层编译帧不动；观测用 Perfetto 的 `Deoptimizing <方法>: <原因>` 切片与 SIGQUIT 里的 deopt 计数，GC 段的 RemoveUnmarkedCode 属于 JIT 缓存清理（kMaxCapacity 64 MB 上限）。结果：反优化是正确性机制而非故障——健康度看"反优化后是否收敛（profile 更新、重编译）"，而不是追求零反优化；另注意 debuggable 构建会额外触发 kDebugging 反优化与解释器插桩，其数据不能与 release 直接对比。

**Q15: "云端下发 profile"等于"云端编译"吗？Android 编译策略的演进主线是什么？**

不等。Cloud Profile 是 Google Play 向设备分发的聚合使用 profile，指导的是设备端 dexopt，编译产物并不在云端生成；演进主线是 Android 4.4 ART 预览 → 5.0 安装期全量 AOT → 7.0 JIT 与 profile 驱动的混合 → 12 起 ART 模块化。

前提：全量 AOT 安装慢、占空间大，纯 JIT 首启慢，混合模型用"安装只验证、运行 JIT 采热、后台按 profile AOT"折中。机制：profile 来源可以是本机使用，也可以是 Play 下发的 Cloud Profile，后台 dexopt 按它决定编译哪些方法。结果：看到"Google 云编译 odex 下发"的说法应纠正——Play 分发的是 profile 数据而非编译产物；profile 缺失时 speed-profile 会回退 verify（机制见 [06-art-runtime.md](06-art-runtime.md)）。

**Q16: Android 17 ART 的分代 Concurrent Mark-Compact 与 userfaultfd 是什么关系？启用条件是什么，为什么不能承诺所有应用都降低暂停时间？**

两者处在不同维度：userfaultfd 是让用户态参与处理缺页事件的 Linux 接口，是 CMC 的实现路径之一；分代是按对象代际选择回收范围的策略。Android 10 起的并发复制（CC）回收器已有分代，Android 17 新增的是 Concurrent Mark-Compact（CMC）的分代能力——"Android 17 才有分代 GC"混淆了回收器与策略。

模型上（材料按 android-17.0.0_r1 的 mark_compact.cc 核对）：上次 GC 后的新分配为 young，存活一次进入 mid，下一次 young GC 同时标记 young 与 mid、经 card table 处理 old 到年轻区域的引用，存活的 mid 压缩后晋升 old；full GC 覆盖全堆并重置分代边界——young GC 并非完全忽略 old，跨代引用仍靠 card table 保证可达性。启用是三项 AND：兼容的 read barrier 或 userfaultfd 路径、运行时 GC 选项 generational_gc、以及 ShouldUseGenerationalGC()；device-config 属性默认为 true，所以 device_config get 返回空值不能判定功能关闭。这项改进还能经 Google Play 系统更新下发到 Android 12 及以上设备，"系统不是 Android 17"也不能证明它不存在。

收益边界：官方只承诺更频繁、更低成本的年轻代回收可减轻 GC 干扰并改善最大 RSS；对象存活率高、跨代引用多或堆压力大时收益会变化。验证以目标进程的 GC 事件为准，对比 young/full collection 次数、暂停分布、GC CPU 时间与峰值 RSS，而不是只查一个属性或数总 GC 次数。

**Q17: Profile、DM、SDM、SDC 四类文件分别解决什么问题？各自的版本边界是什么？**

四类文件分工：Profile（Baseline/Startup/Cloud）是 ART 或构建工具选择热点的输入；.dm（Dex Metadata，ZIP 格式，与 APK 同基名如 base.apk 对应 base.dm）携带 primary.prof（供 speed-profile 的 AOT 输入）与可选 primary.vdex（验证数据）；.sdm（Secure Dex Metadata）携带面向特定 ISA 的云端 AOT 产物（至少含 primary.odex），文件名带 ISA 段（如 base.arm64.sdm），必须用与 APK 相同的 signer 做 v3 签名；.sdc 由设备端 artd 生成，记录 SDM 时间戳与设备 ART APEX 版本，解决文件代际与设备环境匹配——它不是签名文件，签名验证在安装阶段完成。

版本边界：SDM/SDC 是 Android 16 引入（ArtManagedInstallFileHelper 源码注释标记）、Android 17 延续；按 AAOS13 源码核对，本地树没有 ArtManagedInstallFileHelper，SDM 在 Android 13 不存在。DM 则早已有之但行为有版本差异：AAOS13 的 frameworks/base/core/java/android/content/pm/dex/DexMetadataHelper.java 会用 ZIP 内 manifest.json 校验包名与版本号，并提供 pm.dexopt.dm.require_manifest 属性；Android 17 的实现已移除 manifest 读取与相关属性——不能用 A17 口径判断 A13 的 DM 状态，反之亦然。

范围边界：SDM 只覆盖随 APK 打包的 primary dex，运行时动态生成或自定义 ClassLoader 加载的 secondary dex 不在支持范围；同一 APK 覆盖两种 ISA 需要两个 SDM，arm64 产物不能供 arm 进程使用。三类 Profile 也职责不同：Baseline Profile 面向 Day-0、随 APK 发布；Startup Profile 只影响构建期 DEX 布局（安装后的文件系统里没有 startup.prof 可查）；Cloud Profile 由分发侧生成，设备仍可能要跑 dex2oat。

**Q18: 安装一个带 SDM 的应用时，系统怎样决定跳过本机 dex2oat？SDM 无效或缺失会怎样？**

决策链是：Package Manager 完成安装事务并发起安装 dexopt，ART Service 选择 primary dex、ABI、compiler filter、Profile 与优先级，artd 作为特权辅助进程校验路径、检查现有产物并启动编译器。artd.getDexoptNeeded() 结合 APK、boot classpath、现有本地产物与 SDM 组合判断目标是否已满足：现有产物（含 SDM 产物）满足目标则无需本机 dexopt，缺失或过期才启动 dex2oat。SDM 只是 OatFileAssistant 可选择的产物位置之一，没有取代 dexopt 调度器。

安装会话先用文件名映射（去掉 .arm64.sdm 补回 .apk）与 APK v3 签名校验 SDM：文件名无受支持 ISA 后缀、找不到同基名 APK、任一侧签名无法验证或 signer 集合不同都判为无效。A17 的新验证分支将其标为删除无效文件、记 warning 后继续安装，旧分支可能直接拒绝安装——分析日志要先确认设备走哪条分支。SDM 缺失是常态：dexopt 开始前 PrimaryDexopter 会按每个 primary dex 与 ABI 调用 artd.maybeCreateSdc()，SDM 不存在时 artd 返回成功，不构成安装错误；本机编译执行后，PrimaryDexopter 会尽早删除对应 SDM 与 SDC 释放空间——这正说明 SDM 是可被本机编译结果替代的产物，即使未删，ART 的产物垃圾回收也会清理。

版本口径：材料把"应用 dexopt 调度入口迁到 ART Service"记为 Android 14 起；按 AAOS13 源码核对，本地树已有 art/libartservice（ArtManagerLocal 在 SystemServer 经 LocalManagerRegistry 注册）与 art/artd，同时 frameworks 的 DexOptHelper.java 仍在 PMS 侧——Android 13 处于过渡期，分析安装编译链路要按具体版本定位入口。

**Q19: 运行时怎么使用 SDM 里的云端 AOT 代码？为什么"SDM 文件存在"不能证明应用在执行其中的机器码？**

运行时不会在安装阶段把 SDM 解包到应用的 oat 目录：OatFileBase::OpenOatFileFromSdm() 把 ZIP 内部条目拼成 <sdm>!/primary.odex 直接加载——ODEX 来自 SDM，VDEX 来自同 APK 配套 DM 的 primary.vdex，缺少可用 DM/VDEX 时仅有 SDM 无法组成这条加载路径。抓启动 trace 时，Open sdm file <path> 这个 slice 比"oat 目录里有没有 .odex"更能说明 SDM 被运行时打开。

云端产物没有绕过兼容性检查：OatFileAssistant 仍核对 APK dex checksum、boot classpath 与 class loader context、compiler filter 是否满足请求、OAT/VDEX 状态、SDM 与 SDC 的时间戳关系（st_mtim 不一致说明 SDM 被替换过）、以及 SDC 记录的 ART APEX 版本上下文——samegrade placebo（版本号不变换入另一版 ART APEX）就靠它识别。任一条件不满足时，系统可选择较低编译级别的产物、解释执行、JIT 或安排本机 dexopt。

因此文件存在只证明安装器接收过它。确证要组合三类证据：pm art dump <package> 的 filter/reason/location（location 指向 SDM 内 primary.odex 时证据强于 reason 字符串，但它是调试字符串、格式不保证稳定）；trace 中有无 dex2oat 与 Open sdm file；安装日志的 validation warning 与 artd 错误。带 -dm 后缀的 reason 也不够——源码注释明确它只表示本次调用传入过 DM，空 DM 也可能出现。

**Q20: SDM 能省掉安装期的哪些工作？为什么不能照搬"安装时间减少 40%–60%"这类百分比？**

能省的是：当 SDM/DM/SDC 与当前 APK、ISA、boot classpath 和编译目标兼容且满足安装目标时，设备无需为同一目标再跑 dex2oat，从而减少编译器 wall time、dex2oat 的 CPU 时间与临时内存峰值、生成 ODEX/VDEX 的写放大，以及安装期编译带来的热量与功耗。不能省的是：三个文件的下载与会话写盘、APK 与 SDM 的 v3 签名解析与 signer 比较、包扫描与权限更新、原生库提取或映射、fsync 与 SELinux 操作、SDC 创建与兼容性检查，以及未被 AOT 覆盖方法的解释/JIT 和应用自身初始化——"携带 SDM 后安装近似零成本"不成立；安装总耗时若主要花在下载或包扫描，dex2oat 的减少对总值影响有限。

百分比不可照搬：AOSP 源码没有定义能推出这些数字的基准；收益随 dex 规模、目标 filter、CPU、存储、温控与产品安装策略变化，安装策略原本用 verify 的设备能省的本机编译本来就少。冷启动还可能出现方向不同的变化：SDM 的映射、ZIP 访问与页缺失特征可能与本地产物不同，应把安装 wall time、首次启动与后续启动分开测。

可信验证是 A/B/C 三组实验：同一 APK 分别以 APK、APK+DM、APK+DM+对应 ISA 的 SDM 冷安装，固定设备构建、ART APEX 版本、温度与存储余量，每组多轮并报告 P50/P90 与失败回退次数；用 pm art dump、ART_DEX2OAT_REPORTED 统计与 trace 把"SDM 被接收"和"SDM 被运行时采用"当成两个检查点分别确认。
