# 稳定性治理：Native 检测、Hook、动态库与 SDK

> 学习资料（文章模式沉淀）。主线：MTE 与 GWP-ASan 的检测实战边界（请求不等于生效、抽样决定覆盖、可恢复不等于安全），Native Hook 从命中路径出发的选型与实现风险，Native 动态库的可信发布、只读装载与回滚事务，第三方 SDK 的测量归因与退出治理。源文档：android-internals-wiki §20.11《MTE 与 GWP-ASan Native 内存安全检测》、§20.12《Native Hook 技术选型与实现》、§20.13《Native 动态库安全发布、装载与回滚》、§20.14《第三方 SDK 性能影响评估与治理实战》；可本地核对的机制按 AAOS13 源码（Android 13）核对并标注版本差异，工程实践按材料口径转写、不确定处已弱化；GWP-ASan 的 manifest 模式与 Recoverable 行为已与官方文档核对。MTE 标签机制、SYNC/ASYNC 报告差异与 memtagMode 决策链的机制层见 [../memory/02-回收压缩与专项内存.md](../05-memory/02-回收压缩与专项内存.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: `android:memtagMode` 的四个值在记录口径上有什么区别？为什么事件记录里不能把 `default` 写成 `off`？**

四个值的请求语义不同：`off` 明确不请求 MTE 访问检查；`default` 把决定交给兼容性开关、平台默认和设备策略继续处理；`sync` 请求同步检查，适合调试包、实验室和小范围诊断包；`async` 请求异步检查并允许设备按 CPU 首选模式增强，是充分测试后的正式发布候选。`default` 不能记成 `off`，因为设备策略仍可能为它启用 MTE，事件里写 `off` 会制造不存在的"已禁用"结论；同理，请求 `async` 的进程被逐 CPU 增强为 SYNC 时，也只能记 `requested_mode=async`，不能写成 `effective_mode=async`——普通应用没有可靠 API 读取每次访问所在 CPU 的最终模式，上报应保留原始 `tagged_addr_ctrl` 或 tombstone 证据并允许 `effective_mode=unknown`。

配置检查对象是最终 merged manifest：依赖库 manifest、构建类型和产品变体都可能改变结果，进程级 `<process>` 值覆盖应用级 `<application>` 值。工程上把 SYNC 放进 debug manifest（`app/src/debug/AndroidManifest.xml` 配 `tools:replace`），避免调试策略进入发布包。这段配置只表达应用请求，不能探测设备能力：设备缺少 MTE 时 Zygote 会按硬件能力降级，即使硬件支持，检查模式也可能受兼容性开关、系统属性和逐 CPU 策略影响。

**Q2: manifest 的 memtagMode 在什么时机生效？为什么不能用业务远程配置切换已运行进程的 MTE 模式？分批启用有哪些可行路径？**

在进程创建阶段生效：manifest 请求经 Zygote 决策链映射为运行时标志，再经 native 侧 `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, ...)` 交给分配器（决策链与 mallopt 通路按 AAOS13 源码核对，见相邻内存文档）。因此普通应用不能用业务远程配置把一个已经以 `off` 启动的进程切到 `async`，也不能保证正在运行的进程立刻响应新发布的 manifest。远程配置仍有用途：关闭高风险 Native 功能、降低并发、展示原生错误页或阻止问题页面进入——但都不是切换检查模式。

可行的分批启用路径按构建与发布控制：debug 与内部测试包用 SYNC 覆盖 Native 单测、集成测试和长稳；单独制作 canary 包或通过 Play 分阶段发布请求 ASYNC；多进程应用先选 Native 风险高、业务可恢复的独立进程；观察后再扩大覆盖，出现不可接受回归时停止扩大或发布关闭 MTE 的修正版。发布前要具备：精确 Build ID 与未剥离符号归档、只负责一次 fatal signal 的采集方案、tombstone 保留解析、关键状态的进程外持久化，以及同一负载下 off/SYNC/正式配置的性能对照。一个容易漏掉的诊断边界：ASYNC 请求在某个 CPU 上被增强为 SYNC 时错误现场可能更精确，但进程并未按 SYNC 配置分配器，所以仍未必有分配/释放调用栈，归因不能套用 SYNC 报告的权重。

**Q3: GWP-ASan 的两层抽样怎么理解？"应用启用了 GWP-ASan"或配置 `always` 意味着所有 `malloc` 都受保护吗？**

不意味着。GWP-ASan 先决定某次进程启动是否启用（进程级抽样），再从该进程的内存分配中抽样（分配级抽样），只有同时通过两层选择的对象才进入由不可访问 guard page 包围的受保护槽位。分配级默认值按 AAOS13 源码核对（`bionic/libc/bionic/gwp_asan_wrappers.cpp`）：`SampleRate = 2500`（选中进程内约 1/2500 的分配进入保护）、`MaxSimultaneousAllocations = 32`（同时可占用的槽位上限）、进程级抽样率默认 128——即"启用"本身还要先过一次约 1/128 的启动命中。`android:gwpAsanMode` 支持 `never`、`default`、`always` 三种请求（已与官方文档核对）：`always` 只取消进程启动这一层抽样（每次启动都启用），仍只保护部分分配；Android 13 及以下 `default` 对普通应用关闭，Android 14+ 的 `default` 是约 1% 启动命中的 Recoverable 模式。因此不能把两种覆盖率相加成"内存安全百分比"，也不能用固定 `1/N` 推导某个缺陷的发现率。

盲区要单独列出：未被抽中的分配不受保护；越界仍落在槽位可访问范围内时可能不触发；槽位复用会缩短旧地址的可诊断窗口；直接 `mmap`、自研 arena、栈、全局变量和 GPU 缓冲不属于这条分配器路径；普通内存泄漏不会因对象长期未释放而自动触发。guard page 跟随系统页大小，16 KB 页设备上的虚拟地址布局和固定内存成本不同，报告应记录实际页大小、ABI、Build ID 与进程配置。另外 AAOS13 的初始化流程是 GWP-ASan 先建立 dispatch 基座、`malloc_debug`/heapprofd 再叠加其上（`bionic/libc/bionic/malloc_common_dynamic.cpp`），泄漏画像与越界诊断通常要分开实验并记录 allocator hook 状态。

**Q4: Recoverable GWP-ASan 是什么？Android 13 上命中 GWP-ASan 会发生什么？两者行为差异有多大？**

Android 14 / API 34 起，manifest 未填写或使用 `default` 的普通应用采用 Recoverable GWP-ASan：约 1% 的进程启动会启用它（已与官方文档核对）。Android 17 的 Bionic 默认 `Recoverable=true`，命中受保护池错误后 debuggerd 先生成首份完整报告，再让分配器处理故障槽并允许进程继续运行，但同一进程只有第一次错误走完整 tombstone/DropBox 流程，后续只执行 pre/post hook；应用自定义的 `SIGSEGV` 处理函数不会收到这种可恢复错误。

Android 13 上行为完全不同：按 AAOS13 源码核对，`SetDefaultGwpAsanOptions()` 只设置 Enabled、SampleRate、MaxSimultaneousAllocations 等字段，没有 Recoverable 选项——命中即按致命信号终止进程，且 `default` 对普通应用默认关闭，应用要显式声明 `gwpAsanMode` 才启用。无论哪个版本，都要遵守同一条规则："进程没有立刻退出"不代表状态安全——发生释放后访问或越界后官方把后续行为定义为不确定，Recoverable 事件仍应进入稳定性指标、去重、告警和高优先级修复队列，支付、写入等有副作用的操作不能因为进程继续运行就自动重试。APM 分类还要区分两条可恢复路径：Recoverable GWP-ASan 与 Permissive MTE 最终都以可恢复崩溃处理，但入口条件不同——前者是带 fault address 且地址属于 guarded pool 的 `SIGSEGV`，后者看 `SEGV_MTESERR`/`SEGV_MTEAERR`，不能把所有可恢复 `SIGSEGV` 归为同一类。

**Q5: MTE 错误报告能不能靠吞掉 `SIGSEGV` 消除？应用崩溃采集器与 debuggerd 协作时要遵守什么边界？**

不能吞。MTE 标签不匹配通常表示进程确实违反了带标签内存的访问约束，不应简单归为"误报"——看起来像误报的情况往往错在归因过程：把 ASYNC 报告点当成错误访问点、使用了不匹配的符号版本、自定义分配器没有遵循标签语义、旧代码破坏了指针高位。正确做法是修复证据指向的内存使用错误；无法更新且持续触发缺陷的第三方 `.so`，短期只能隔离进程、回到上一版本或发布关闭该进程 MTE 的新构建。

采集器协作按 AAOS13 源码核对：debuggerd 的信号处理器注册了 `SA_SIGINFO | SA_ONSTACK | SA_RESTART | SA_EXPOSE_TAGBITS`（`system/core/debuggerd/handler/debuggerd_handler.cpp`），其中 `SA_EXPOSE_TAGBITS` 请求内核在错误地址中保留标签位，供 MTE 诊断使用。应用侧边界：致命信号只由一个采集器负责，避免多个 SDK 反复覆盖信号处理配置；信号处理函数不分配堆内存、不做完整符号化、不发网络请求；原样保存 `siginfo_t` 与 `ucontext_t` 的可用字段；不尝试从 MTE 错误恢复业务执行，保留系统默认终止与 tombstone 生成路径；ASYNC 报告不能把处理函数看到的当前 PC 写成"内存破坏发生点"。第三方采集器必须用受控故障验证：SYNC 释放后访问、SYNC 越界、ASYNC 错误、栈溢出和多线程同时崩溃，逐项确认报告、tombstone、Build ID 与 `si_code`。

**Q6: 启用 MTE 后崩溃率上升一定是坏事吗？性能收益该怎么评估？**

不一定是坏事。MTE 会把静默内存破坏更早转成可识别的 `SIGSEGV`，启用初期崩溃增加很可能说明以前隐藏的缺陷被暴露；目标是发现、定位并修复内存安全缺陷，而不是只追求 MTE 崩溃数下降。正确度量是崩溃率必须同时报告用户、会话或进程启动分母，修复后同一版本和测试负载下 MTE 事件下降才说明缺陷得到处理；"没有报告"不能证明没有缺陷。

性能没有可引用的单一百分比：开销受 CPU 实现、检查模式、分配栈记录、分配行为和负载影响，应使用同一正式版本、相同设备电源状态和相同负载，比较启动/交互/长任务时延分布、CPU time、功耗温升、内存占用，以及 Native 崩溃、ANR、业务失败与进程重启，并按进程和设备档位分组。SYNC 测试不能只跑正常路径，要覆盖不可信输入解析、跨语言对象所有权、异步回调、取消、对象池、JNI 引用、线程退出、热更新资源和第三方 `.so`。发布报告必须同时写清进程启动覆盖与内存分配抽样（GWP-ASan 场景），避免把"没有命中"解释为"没有缺陷"。

**Q7: PLT/GOT、Inline、Trap 三种 Native Hook 分别改写什么？同样是 Hook `malloc`，覆盖面差在哪？选型从哪里开始？**

三者改写的对象不同。PLT/GOT Hook 修改某个调用方 ELF 的动态重定位槽（PLT 负责把外部函数调用引向链接结果，GOT 保存运行时地址，实践中通常改 GOT 中与动态重定位对应的槽位），能看到该调用方经动态链接发出的外部调用；Inline Hook 改写目标函数入口或函数内指令，能覆盖所有经过该指令地址的执行流；Trap Hook 用断点指令触发 `SIGTRAP`、在信号处理函数中改写寄存器与 PC，接近用户态软件断点。同样是 `malloc`：修改 `libfoo.so` 中 `malloc` 的导入槽只影响 `libfoo.so` 经该槽发出的调用；修改 `libc.so` 中 `malloc` 的入口会影响进程内更多调用者，同时放大递归和并发风险。

选型从"需要命中哪条调用路径"开始，而不是"PLT 失败就换 Inline"。业务优先级是：自有模块的埋点、耗时或故障注入用显式代理或编译期插桩；观察指定 `.so` 对 libc/NDK 函数的外部调用用 PLT/GOT Hook；要拦截函数内部调用、隐藏符号或经 `dlsym` 保存的直接调用才用 Inline；分配器、线程同步等高频基础函数优先用 heapprofd、GWP-ASan、Perfetto 等平台工具——代理函数极易递归，故障影响整个进程。放入面向全部用户的路径前要逐项回答：目标调用是否被 LTO、内联或直接绑定消掉；ABI 是否稳定；安装时其他线程是否正在执行待覆盖指令；新装载和卸载的 ELF 如何进出 Hook 集合；失败、重复安装、多框架共存、远程关闭时状态是否可判定；代理函数能否在递归、信号、低内存和进程退出阶段安全运行。只要一项没有答案就不上线。

**Q8: bionic linker 对 Native Hook 有哪些硬约束？PLT/GOT Hook 为什么不依赖 `RTLD_LAZY` 的延迟解析？**

按 AAOS13 源码核对，四条约束构成边界。其一，`RTLD_LAZY` 不受支持：linker 处理 `DT_PLTGOT` 时直接忽略（源码注释 "Ignored (because RTLD_LAZY is not supported)"），PLT 重定位在装载期间就由 `relocate()` 完成——所以 PLT/GOT Hook 修改的是 linker 已写好的重定位结果，不依赖延迟解析入口，传入 `RTLD_LAZY` 也不会得到桌面 Linux 式的首次调用再解析行为。其二，RELRO：`soinfo::link_image()` 在重定位完成后调用 `protect_relro()` 把指定内存段改为只读，落入 RELRO 的槽位加载后不可写，Hook 需按目标 ELF 和运行时映射确认页权限，`mprotect()` 可能因地址、长度、映射类型或安全策略失败，失败要有可观测的降级分支。其三，64 位 ELF 声明 `DT_TEXTREL` 或 `DF_TEXTREL` 会被 linker 直接拒绝装载（LP64 分支报 "has text relocations"）；这项检查针对 ELF 自己声明的装载期代码重定位，与 Inline Hook 的运行时 patch 是两条独立路径，互不能为对方背书。其四，`dlopen()` 成功返回后新 ELF 的重定位和构造函数已经执行完，此时安装 Hook 只能覆盖后续调用，无法补采构造函数中的调用，新装载的 ELF 有独立重定位槽、必须另行扫描。

监听 `dlopen()` 只解决"何时重新扫描"，拿不到 linker 内部从重定位结束到 `protect_relro()` 之前的公开插入点；依赖这个内部时机的方案必须 Hook linker 私有实现，版本风险显著增加。还有一个校验细节：IFUNC 场景下 `dlsym()` 返回的是最终实现地址，`R_GENERIC_IRELATIVE` 重定位才经过 resolver——校验旧槽值时应比较最终实现地址，不能把 resolver 地址当普通函数入口。

**Q9: PLT/GOT Hook 安装成功后，哪些调用仍然不会被拦截？为什么"改写成功"不等于"全覆盖"？**

至少七类调用绕过被改写的槽：同一 ELF 内部已解析为直接分支的调用；编译器内联或 LTO 合并后的调用；hidden/protected visibility、`-Bsymbolic` 造成的本地绑定；经 `dlsym()` 保存到其他变量后再发出的间接调用；直接执行 `svc` 指令的系统调用封装；Hook 安装后通过 `dlopen()` 加载的新调用方；已卸载又复用同一地址区间的 ELF。所以"Hook 成功"只能说明某些 relocation slot 已被改写，测试报告应同时给出命中调用方列表和明确盲区。

正确的安装链路也决定了命中率：`dl_iterate_phdr()` 枚举 ELF（在回调内复制元数据、按路径或 Build ID 过滤）→ 从 `PT_DYNAMIC` 解析符号表、字符串表和重定位表 → 同时校验符号名、relocation type、当前槽值和预期目标 → 读取槽所在映射原权限并按运行时页大小计算 `mprotect()` 区间 → 先保存原函数地址（`orig` 必须先于新槽值发布，否则其他线程可能在窗口内经 proxy 跳到空地址）→ 对齐的原子指针写更新槽 → 明确决定是否恢复原权限并记录。多框架共存时由统一调度入口维护调用链，proxy 内不要随意递归调用 `dlopen()`、`dlsym()` 或日志组件（它们可能再次经过被 Hook 的路径），用线程局部重入标记和无分配记录器，unhook 前确认槽仍指向本框架入口、发现第三方改写时拒绝盲目恢复。

**Q10: AArch64 Inline Hook 难在哪里？搬迁指令、发布新指令和页大小各要注意什么？**

难点集中在指令搬迁与并发发布。Inline Hook 覆盖目标位置的一组指令、把它们搬到 trampoline，所有依赖原 PC 的指令都要重新编码：`B/BL`、`B.cond`、`CBZ/CBNZ`、`TBZ/TBNZ`、`ADR/ADRP`、literal load 每类都有独立的长度计算和重写分支。典型错误是 `ADRP`——它按 PC 相对页地址计算，搬到 trampoline 后 PC 变了必须重算目标页，否则寄存器指向错误页，多线程下崩在不可预期位点；最终偏差取决于新旧 PC 差，不能固定按 4 KB 判断。

发布与缓存边界：AArch64 直接 `B` 使用 26 位立即数、范围约当前 PC 前后 128 MiB——能在范围内分配 branch island 时，原入口只需改写一条对齐的 4 字节指令；否则要写多指令绝对跳转序列，多指令 patch 的窗口更大，其他核心可能取到新旧混合的指令序列，一次 `memcpy()` 提供不了并发保证。"数据写入不可撕裂"也不等于"其他核心立即按新指令执行"，发布协议必须包含线程协调和指令缓存同步：Android NDK 中用 `__builtin___clear_cache(begin, end)`；AAOS13 的 bionic 只在 32 位 `__arm__` 下声明 `cacheflush()`（按 `bionic/libc/include/unistd.h` 核对），新代码应使用跨架构 builtin。页大小用运行时 `getpagesize()` 或 `sysconf(_SC_PAGESIZE)` 获取，不写死 4096 或 `PAGE_SIZE`——16 KB 页设备上 `mmap`/`mprotect` 对齐、trampoline 分配和权限恢复都要按实际页大小计算。运行时 patch 还要把映射临时设为可写可执行（W+X），这个窗口本身是攻击面，应缩短窗口、限制可修改目标、记录权限变更失败并在框架允许时恢复最小权限；不能把关闭 SELinux 或依赖 root 当作普通应用方案。最后，PAC/BTI/CFI 三类控制流保护要纳入测试：搬迁函数序言不能遗漏或重复 PAC 签名指令，BTI 保护下间接跳转入口必须有 landing pad，CFI 类型检查可能因签名不兼容直接终止进程。

**Q11: 代理函数的 ABI 约束和 Hook 的生命周期（停用、卸载、兼容）应如何设计？**

代理函数必须与目标完全兼容调用约定：整数、浮点、向量参数的寄存器分配，大结构体经隐藏结果指针返回，C++ 成员函数的 `this` 与 name mangling 及异常边界，可变参数与栈对齐，PAC/BTI/CFI 编译选项，以及 `errno` 是否属于接口语义。重入保护用 `thread_local` 标记 + 保存/恢复 `errno`，并用 RAII 保证每条返回路径清理线程局部状态；`thread_local` 只约束当前线程的常规递归，不能让记录函数自动满足异步信号安全。

生命周期上，每个 Hook 应有明确状态机（`DECLARED → RESOLVING → INSTALLING → ACTIVE`，失败进入 `FAILED` 并保留错误阶段证据，停用走 `DISABLING → DISABLED`）。关闭时优先逻辑停用：保留稳定入口，用原子开关让 proxy 直接调用原函数——恢复 GOT 指针不等于旧 proxy 已无人执行，恢复函数入口也不等于 trampoline 可以立刻释放，线程可能仍在其间。物理 unhook 需要活动调用计数、宽限期或暂停线程协议，并确认目标 ELF 尚未卸载、槽或指令仍属于本框架。兼容判断上，API level 只能作第一层筛选：对 `libart.so` 等私有函数 Hook 在某台 Android 13/17 设备成功，不能推导到同 API level 的其他设备——ART 等 Mainline 模块可独立升级，厂商构建会改变符号与函数序言；私有符号必须按设备 API、ABI、Build ID、符号名和入口指令摘要建允许列表，任一不匹配就停用，研究用偏移与线上允许列表分开维护。安装记录（目标 ELF 路径、Build ID、load bias、符号、原地址与 proxy 地址、relocation type 或原指令、页大小与权限变更）随崩溃事件上传，否则无法判断崩溃在业务代码、proxy、trampoline 还是多框架冲突。

**Q12: Android 17 对 `System.load()` 加了什么约束？覆盖哪些加载入口？Android 13 上行为有何不同？**

Android 17 上、以 API 37 及更高版本为目标的应用调用 `System.load()` 时，如果目标文件仍然可写，会在进入 native linker 前抛出带 "Attempt to load writable file" 的 `UnsatisfiedLinkError`。按材料对 `android-17.0.0_r1` 源码的核对，检查只挂在 `Runtime.load0()` 这条路径上：`System.load("/abs/libx.so")` 走它；`System.loadLibrary("x")` 经 `loadLibrary0()` 由 `ClassLoader.findLibrary()` 解析文件后直接调 `nativeLoad()`，当前源码未经同一段检查；自定义 `ClassLoader.findLibrary()` 配合 `loadLibrary()` 同样不经过；原生代码直接 `dlopen()` 更不会回到 Java 层。但改用 `dlopen()` 只会绕开这段 Java 检查，不会让下载的代码变可信——原生库仍受 linker namespace、ELF 格式、`DT_NEEDED` 依赖、符号解析、进程位数和 16 KB 页约束，远程 DCL 还可能违反 Google Play 政策；确需保留 DCL 时，所有入口都应执行同一套只读发布与来源验证。

版本边界按 AAOS13 源码核对：Android 13 的 `Runtime.load0()` 只做绝对路径检查（本地 `libcore/ojluni/src/main/java/java/lang/Runtime.java`，无可写检查逻辑），这条强制从 Android 14 的更安全 DCL 行为演进而来、在 Android 17 对 targetSdkVersion 37 生效。版本敏感的调用边界（`loadLibrary` 是否被纳入检查）标注为当前版本实现，不能假设未来版本相同。

**Q13: 只读检查通过就代表库可信吗？可信发布至少需要哪四类校验？**

不代表。Android 17 的只读检查只处理"加载时文件仍可修改"造成的竞态，不能证明文件来自可信发布方，也不能代替签名、哈希、ABI、ELF、依赖与回滚检查。四类校验缺一不可：来源——发布系统用私钥为清单签名，应用用内置或受控公钥链验证；若 SHA-256 与文件由同一个未认证响应一起下发，它只能帮助发现传输损坏，攻击者仍可同时替换二者。内容——经签名的清单覆盖模块名、版本、ABI、长度、SHA-256、ELF Build ID、最低应用版本、最低/最高 API 以及允许回滚到哪些版本。位置——文件只进入应用私有目录，不从共享外部存储或其他进程可写路径加载。生命周期——每个版本独立路径、发布后不覆盖，版本选择记录只指向已验证文件，失败版本持久标记为 `BAD`。

发布包之外的制度边界也要记住：官方安全指南指出许多 DCL 形式（尤其从远端获取代码）可能违反 Google Play 政策并导致应用被暂停——"系统允许加载"推不出"商店允许发布"，是否合规按实际下载内容和分发方式单独审查。

**Q14: 发布状态机如何隔离半成品？`rename` 的原子性边界是什么？**

用状态机把下载、验证、发布、启用分开：`STAGED`（临时文件已创建，加载线程不可选择）→ `VERIFIED`（签名清单、摘要、长度、ABI、ELF 元数据均通过）→ `PUBLISHED`（临时文件在同一文件系统内经原子 `rename` 切到唯一版本路径）→ `SELECTED`（小型选择清单经 `AtomicFile` 或等价文件事务更新）→ `LOADED`（当前进程已加载）。签名、哈希、ELF 或加载任一环节失败都把该版本标记 `BAD`；回滚只更新版本选择记录，不改写已发布文件；启动扫描只接受签名完整且状态为 `PUBLISHED` 的版本，未被引用的临时文件延迟清理。

`rename` 的边界容易高估：原子 `rename` 只保证运行中的观察者不会看到半次目录项切换，不自动保证掉电后目录更新已持久化（要求掉电恢复需同步父目录），也不包含选择清单更新。`File.renameTo()` 只返回布尔、无法说明失败原因，也没有"目标存在时失败"的规则；`Os.rename()` 提供 `ErrnoException` 但同样没有"不覆盖"保证——先 `require(!finalFile.exists())` 再 `rename` 是两步操作，若威胁模型包含不遵守锁的同 UID 写入者，需要用支持原子"不覆盖"语义的接口或等价协议。发布进程应持有跨进程锁、临时文件与目标文件在同一私有文件系统；恢复逻辑分别处理"文件未发布""已发布但尚未选择""选择记录已提交"三种状态，不能根据"现在能取得锁"推测上一次操作成功。

**Q15: 为什么"先 `fchmod` 设只读、再用已打开的 fd 写入"是可行的？文件设为 0400 后为什么仍可能被替换或删除？**

利用了 Linux 文件权限的检查时机：`fchmod` 影响的是后续打开文件时的权限检查，不会撤销已经成功取得的写入权限。所以正确顺序是：用 `O_CREAT | O_EXCL | O_CLOEXEC` 创建唯一临时文件并拒绝跟随符号链接 → 保持写入 fd 打开、立即 `fchmod` 为仅所有者可读 → 经该 fd 流式写入并同步计算 SHA-256 → 校验通过后 `fsync`、原子 `rename` 到唯一版本路径。这样路径先变为只读，其他进程几乎拿不到可写窗口；摘要覆盖的就是写入 fd 的同一串字节，不必把整个库读入内存。诊断时可同时记录 mode bit 与 `File.canWrite()`——`0400` 代表"仅所有者可读"，但看到它不能断定平台检查一定通过，两者含义不同。

文件权限与目录权限要分开看：文件改成只读后，拥有可写父目录的进程仍可执行 `unlink` 或 `rename`——只读文件能阻止原地改写，却不能保证某个路径始终指向同一组字节。所以只读只是发布事务的一环，版本化路径、禁止覆盖、签名校验和版本选择记录必须配合使用。

**Q16: 只读检查通过后，独立 `.so` 还可能因为什么加载失败？`UnsatisfiedLinkError` 应该按什么分类处理？**

按失败来源分七类处理，不能把所有 `UnsatisfiedLinkError` 归因到 Android 17 只读规则。ABI/ELF 类：不要直接取 `Build.SUPPORTED_ABIS[0]`——应用进程可能以 32 位或 64 位运行，设备支持列表第一项不一定对应当前进程；应核对 `Process.is64Bit()` 与 ELF `EI_CLASS`、ELF `e_machine` 与清单声明的 ABI、版本与 Build ID 一致；多 ABI 复用同一版本目录会让错误文件覆盖正确文件，目录应含固定格式的模块名、ABI 和不可变版本号（如 `files/native/arm64-v8a/feature/42/libfeature.so`）。依赖与命名空间类：`DT_NEEDED` 声明的每个依赖都要能在调用方 `ClassLoader` 对应的命名空间中解析，不能依赖未向应用公开的私有平台库，也不按猜测顺序手工逐个 `dlopen()`；发布前用 `readelf -d` 列出依赖并与随包库及公共库核对。16 KB 页类：独立 `.so` 检查 ELF `PT_LOAD` 段的 `p_align`；APK ZIP 对齐只影响从 APK 直接 `mmap` 未压缩库的路径，解压到私有目录后不再是条件——两者分别检查。

分类直接决定动作：消息含 `Attempt to load writable file` 且 `canWrite()` 为真，是发布流程故障，禁止重试同一路径；`wrong ELF class`/`bad ELF magic` 禁用该文件并选匹配当前进程 ABI 的版本；`cannot locate symbol` 回滚库与调用方的版本组合；依赖不可访问修正 `DT_NEEDED` 或随包依赖；签名/摘要失败发生在调用加载 API 之前，删除临时文件并告警。可选功能捕获 `UnsatisfiedLinkError` 后关闭该功能入口且不再调用对应 JNI 方法；启动必需库优先使用随 APK/AAB 发布的版本。诊断记录保留应用版本、targetSdk、进程位数、加载入口、`DT_NEEDED` 与 16 KB 检查结果、Build ID 和回退版本；上报时保留受控目录类型和清理后的相对路径，不发送原始外部路径。

**Q17: 新版本原生库加载失败后如何回滚？为什么不能在运行中的进程里热切换原生库？**

回滚是"版本选择记录"层面的操作，不是卸载：版本 B 失败后写入 `BAD` 记录，把选择切回最近一次确认可用的版本 A，同一进程不无限重试 B；重启后启动扫描只接受 `PUBLISHED` 且被选择的版本。多进程场景的核心约束是：只有一个发布者持有跨进程锁；读取者只加载 `PUBLISHED` 的不可变路径、不打开临时文件；缺少可靠跨进程引用计数时，至少保留 active（当前选择）与 last-known-good（最近确认可用）两个版本并延迟清理更早版本——否则会出现"主进程发布 B、远程服务仍加载 A、清理线程删了 A"的时序故障。文件锁只表示当前有没有进程占用它，不代表上一次发布已完成；进程被杀后内核释放锁，磁盘可能留下临时文件或"已发布、选择未更新"的中间状态，恢复要读签名清单与持久状态。

热切换做不到的原因是 Android 没有面向应用的可靠原生库卸载协议：库被某个 `ClassLoader` 加载后，JNI 注册、静态对象、线程、TLS 和函数指针都可能仍被引用。稳妥的启用时机是下次进程启动；需要即时切换时，把插件放入能够单独重启的隔离进程。不要在已执行过新版 JNI 代码的进程中强行换库。

**Q18: "接入了一个 SDK"到底意味着什么？为什么"平均 20~40 个 SDK"这类数字不能当项目结论？**

一次商业能力接入带来的远不止一份 AAR：多个 Maven artifact 及其传递依赖；Java/Kotlin 字节码、资源、Manifest 合并项和按 ABI 区分的 `.so`；`ContentProvider`、`Service`、`Receiver`、`Activity`、独立进程与自定义权限；初始化代码、线程池、定时任务、网络协议、数据库与本地缓存；consumer R8 规则、基线配置文件和注解处理器；以及服务端开关、控制台配置和数据处理关系。所以治理对象应是"某个能力在某个版本发布制品中的完整执行路径"，并同时维护两种视图：能力/供应商视图回答谁提供什么能力、由谁负责、能否替换；发布制品视图回答本次 release 多了哪些代码、资源、组件、权限和原生库。

"一个应用平均有 20~40 个 SDK""SDK 代码占 30%~60%"不能当项目结论，因为它们高度依赖统计口径——按供应商、业务能力、Maven 发布单元、DEX 包名前缀还是安装字节计数结果完全不同：同一家供应商可能拆成十几个发布单元，一个基础库也可能被多个 SDK 复用；依赖声明数量推断不了最终体积，包名前缀也推断不了运行时责任。台账以准备发布的构建变体为准（`releaseRuntimeClasspath` 的依赖图与 `dependencyInsight`、合并 Manifest、APK/AAB 组成），Google Play 的 SDK 责任说明也明确：即使问题来自第三方代码，应用开发者仍对发布的应用负责。

**Q19: SDK 的 `ContentProvider` 初始化发生在 `Application.onCreate()` 之前吗？Jetpack App Startup 能解决什么、不能解决什么？**

发生在之前。按 AAOS13 源码核对（`frameworks/base/core/java/android/app/ActivityThread.java` 的 `handleBindApplication`）：系统先调用 `installContentProviders(...)`，随后才经 `Instrumentation.callApplicationOnCreate(...)` 调用 `Application.onCreate()`。因此 SDK 的自动 Provider 初始化先于应用自己的初始化代码执行，常见主线程成本包括 Provider 构造、类加载与静态初始化，SharedPreferences/文件/数据库/PackageManager 查询，Binder 调用、锁等待和同步网络探测，注册监听器、创建线程池或加载原生库，以及多个 SDK 争用主线程与类加载锁——只给 `Application.onCreate()` 打点会漏掉这一整段早期成本。

App Startup 能做的是把多个自动初始化 Provider 合并到一个 `InitializationProvider`，并用 `Initializer.dependencies()` 显式声明依赖顺序，适合统一入口和减少 Provider 数量；它不能自动把初始化移出主线程——由 Manifest 声明的 `Initializer.create()` 仍在 Provider 启动阶段执行。与首屏无关的初始化器可从 Manifest 移除对应 `meta-data`，改在用户同意或功能首用时经 `AppInitializer.initializeComponent(...)` 手动初始化。两个契约边界：关闭某组件的自动初始化也会关闭其 `dependencies()` 返回组件的自动初始化，随后手动初始化时依赖按图一并初始化；若某个依赖同时被其他 Manifest 声明的 `Initializer` 引用，它仍可能从另一条路径启动——必须检查完整依赖图。另外，按需、延迟与异步不是同义词：按需最节省未使用能力的成本，延迟只是挪时点且可能撞上首屏交互，异步没有改变后台执行限制与依赖时序；把同步调用包进协程不改变主线程成本（若仍在 Main dispatcher），换到后台线程也会引入 CPU、I/O 竞争或生命周期泄漏。

**Q20: SDK 性能归因要遵守什么测量纪律？"启动变慢来自 SDK"的强证据是什么？**

四条纪律缺一不可：同场景比较（启动模式、账号状态、网络、数据集、设备温度与电量一致）；单变量比较（A/B 产物只改变一个 SDK、一个版本或一个初始化策略）；重复采样（保留分布、P50/P95 和异常样本，不用单次结果下结论）；保留证据（产物、版本、编译模式、Trace、符号、mapping 与实验脚本一并归档）。实验分两种且不能互相替代：单 SDK 实验判断边际成本——有无该 SDK 或版本变更时的场景差值；全部 SDK 的集成实验识别竞争和叠加效应。

"主线程栈出现 SDK 类"只是弱证据；较强证据是"A/B 产物回归 + Trace 时间片 + 可复现调用路径"。工程上用 Macrobenchmark 建立可比较基线：它在应用进程外重复驱动冷/温/热启动并保存 Perfetto Trace，要求目标应用可 profile（发布构建加 `android:profileable`），比较时 release 配置、设备、启动模式和编译模式必须相同；用 `CompilationMode.Partial` 加 `BaselineProfileMode.Require` 固定编译条件，避免悄悄换成另一种编译。Baseline Profiles 能预编译关键路径，但不能消除同步磁盘、网络、锁等待，也不改变静态初始化语义。给可控的初始化入口加 `androidx.tracing` 命名切片时，切片应覆盖等待时间和结果，不能只包住一次异步任务提交。

**Q21: 为什么同进程内没有可靠的"每 SDK PSS"？内存归因应该怎么做？**

因为所有内存工具都以进程或虚拟内存区域为观察对象，不会凭空生成 SDK 所有权：PSS 把共享物理页按映射进程数分摊，适合进程级比较；RSS 读取成本低但共享页在多个进程重复出现；`smaps` 描述每个 VMA 的地址、权限与页统计；Java heap dump 是托管堆某一时刻的对象快照；heapprofd 按 native 分配调用栈归集。所有权层面的错配更根本：同一进程的 Java heap 与 native 分配器被宿主和各 SDK 共用；类的包名不等于对象的保留责任（宿主可能把 SDK 对象长期放进缓存）；`.so` 文件映射的 RSS 包含代码页与共享干净页，不等于该 SDK 的活跃 native heap；native 分配可能经过宿主封装、JNI 回调或公共库，必须看符号化调用栈。所以"按包名汇总 heap dump""把 `.so` RSS 当 SDK 内存""从 smaps 精确拆分 SDK PSS"都只能当线索。

可审计的做法是 A/B 内存实验：生成只相差一个 SDK 或一个版本的两个 release 产物，在相同设备档位、冷进程、账号数据和固定脚本下运行相同场景，在稳定点与峰值点重复采集 RSS/PSS、Java heap、native heap 和映射；Java 堆看 dominator 与 retained path 而不只按类名计数，native 堆用带 Build ID 的符号解析 heapprofd 分配栈，把加载映射、活跃分配、线程栈和图形缓冲分开解释，保存每轮原始数据与分布。SDK 独立进程时分别观测宿主和子进程，但仍要报告合计用户成本。平台边界：Android 17 引入的 App Memory Limits 只在部分设备启用、限制值由设备配置决定，不存在全设备统一阈值（已与官方文档核对）；嵌入宿主的 SDK 内存计入宿主进程，命中限制时 Android 17 的退出记录可能以 `REASON_OTHER` 出现并在描述中含 `MemoryLimiter:AnonSwap`——不要把更高版本新增的退出 reason 常量提前写进代码。

**Q22: Java 栈顶出现 SDK 类就能判定 Crash 属于 SDK 吗？为什么符号材料是上线条件而不是可选项？**

不能。栈顶出现 SDK 类只是候选相关：可能是宿主传入非法参数、调用顺序错误、线程模型不符或回调状态被破坏。归因分三档递进：候选相关（堆栈或线程状态涉及 SDK）；实验相关（关闭 SDK、改变版本或调用路径后可稳定复现或消失）；供应商确认（供应商确认根因并提供可验证修复或规避方案）。ANR 同理不能只看是否出现 SDK 帧，要找到主线程正在等什么、锁或 Binder 的另一端是谁、后台线程是否持有资源。没有 Java/Native 崩溃栈的异常退出要结合 `ApplicationExitInfo`、系统日志、内存样本和前台状态分析，低内存杀进程、用户停止、系统更新不与 SDK crash 混成同一指标。

符号是上线条件，因为每个 release 必须保存与产物一一对应的 R8 mapping 和 retrace 工具版本、未剥离 native library 或可用符号文件、ABI、Build ID、SDK 精确版本与制品校验值——`ndk-stack` 需要与崩溃二进制匹配的符号才能还原 native 栈。若闭源 SDK 只提供 stripped `.so` 且供应商无法按 Build ID 符号化，团队就无法可靠定位 native crash，这项缺口应直接记入准入风险并在发布前备齐材料。聚类键用异常类型或 signal、归一化栈、SDK 版本、ABI、Android 版本、设备、进程和功能路径；Android vitals 的"可能与 SDK 有关"仍是线索，不是判决。

**Q23: SDK 的退出能力为什么要在接入时设计？应用自有接口和远程开关各自的边界是什么？**

因为禁用和替换的成本由接入方式决定：业务代码到处暴露供应商类型，替换和禁用就会变成大规模改造。应用应定义自己的领域接口（只表达业务能力的抽象），供应商 API 留在适配层，由适配层处理同意、初始化、状态和异常；可禁用的能力用开关阻止后续调用，并只在供应商支持时执行停止。开关的能力边界要写进协议：它不能从进程中卸载已加载的 DEX/`.so`，不能撤销静态初始化、未知监听器或已排队的任务——SDK 必须提供成对的注册/反注册和可重复调用的停止契约。边界本身还要被构建与源码两条线约束（Gradle 依赖图 + Kotlin 源模型检查）：只有适配层可依赖供应商 artifact，公共接口只暴露应用自有类型；规则从少量高信号开始，避免笼统禁包名误伤合法库。

远程开关适合阻止后续初始化、停止新请求或隐藏功能，但要满足：读取开关不依赖待禁用 SDK；冷启动早期即可得到安全默认值；禁用路径不触发无限重试或 crash loop；本地保留上次可信状态并设过期策略；隐私同意撤回能停止采集并处理待上传数据。最关键的时间边界：若 SDK 在进程创建时由 Provider 自动运行，远程开关的读取往往已经太晚——高风险 SDK 应改为显式初始化，或至少让 Provider 只做轻量登记、不执行耗时网络或磁盘操作。

**Q24: Android 17 上还能为新应用接入 SDK Runtime（`SdkSandbox`）吗？Android 14~16 的遗留路径要注意什么？**

不能。Google 已于 2025 年 10 月宣布退役 SDK Runtime 等 Privacy Sandbox 技术，Android 17 / API 37 的 `SdkSandboxManager` 已废弃、API 文档明确 SDK sandbox 不再受支持，AndroidX `privacysandbox-sdkruntime` 也已废弃并停止更新（已与官方文档核对）。API 符号和系统服务类仍然存在（AOSP 保留 `SdkSandboxManagerService` 等兼容代码），但不能据此判断能力可用于新项目——面向 API 37 的接入决策应服从公开 API 的废弃契约，新项目不再围绕 runtime-enabled SDK bundle、`loadSdk()` 或旧 sandbox 生命周期设计。

Android 14~16 的遗留路径评估要点：旧 Runtime 把 SDK 放进每个应用对应的独立进程，宿主经 Binder 通信、两端没有共享 Java/native heap——评估要报告宿主与 Runtime 进程的合计 PSS/RSS，并测量加载延迟、Binder 回调、远端 UI 帧时间和进程死亡率与恢复成功率；兼容模式则把 DEX 载入宿主进程、只用独立 classloader 隔开类名空间，不提供进程隔离。现有产品按 Android 版本和 SDK 精确版本记录实际运行路径：Android 14~16 只维护已发布能力并做可用性探测与失败降级，Android 17 上关闭 sandbox 路径、转回受支持的普通嵌入式 SDK 或供应商明确支持的其他进程模型。版本边界：Android 13 同样存在该能力的早期形态，但本地 AAOS13 树未含 AdServices 模块目录，以上按材料与官方文档口径转写。
