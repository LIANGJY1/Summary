# 平台 Rust 与 FFI

> 学习资料（文章模式沉淀）。边界：本文回答 Android 平台引入 Rust 的安全动机、Rust Binder 服务调用链、设备端编译配置、分配器与 panic 边界，以及系统服务性能测量方法。平台语言全景归 [../01-architecture/01-system-architecture.md](../01-architecture/01-system-architecture.md)。源码版本分别注明。Q 序列供 atlas 同源直读。

**Q1: [learning] Android 平台为什么引入 Rust？历史漏洞数据能说明什么，不能说明什么？**

Android 引入 Rust 的主要动机是降低新增 native 代码中的内存安全缺陷。Google 在 2021 年公布的历史统计称，当时 Android 高严重性漏洞中约 70% 与内存安全有关。这是说明投入方向的历史数据，不是当前漏洞比例，也不能直接代表 Rust 已消除同类漏洞。

1. **治理对象：**优先把新代码和能够独立替换的组件写成 Rust，逐步降低新增 C/C++ 内存安全风险。既有系统规模和接口依赖意味着迁移是渐进过程，平台会长期保留 C++、Rust 等混合实现。
2. **判断边界：**Rust 的类型与所有权机制可在编译期约束许多悬垂引用、越界访问和数据竞争来源，但 `unsafe`、FFI、逻辑错误及依赖实现仍需审查。语言迁移是降低一类风险的工程手段，不等于组件自动安全。

**Q2: [learning] Android 平台的 Keystore2 等 Rust Binder 服务是否会经 JNI 和 C++ AIDL 中转？应怎样按组件判断语言边界？**

Android 17 源码中的 Keystore2 和 VirtualizationService 直接实现 Rust Binder 服务端，Java 客户端通过 Binder transaction 到达 Rust stub，不存在每次调用都必须先经 JNI 再经过 C++ AIDL 的固定链路。应按具体服务与库的实现划分语言边界，不能把包含 Rust 库的整套子系统统称为 Rust 服务。

1. **Rust Binder 服务：**Keystore2 与 VirtualizationService 使用 Rust AIDL backend 和 `libbinder_rs`，启动时将服务注册到 Service Manager。Java 客户端通过 Binder 接口发起跨进程事务，服务端 Rust stub 处理请求。
2. **混合语言解析器：**DnsResolver 保留 C++ 与 Rust 代码。DoH/HTTP3 的部分逻辑通过 `libresolvrs_ffi` 和 CXX bridge 连接，不能据此把整个 resolver 说成 Rust 实现。
3. **渐进替换库：**UWB 与 Bluetooth 中有 Rust 库逐步替换或承担特定组件。这表示组件级迁移，不表示整套 UWB 或蓝牙服务都已改写为 Rust。
4. **版本边界：**以上组件形态按 `android-17.0.0_r1` 源码核对。其他 Android 分支应检查对应分支的服务注册、AIDL backend 和 bridge 调用，不能把版本结论无条件外推。

**Q3: [learning] Android Soong 为设备端 Rust 设置了哪些编译参数？每项显式值和省略行为是什么？**

AAOS 13 所用 Soong Rust 配置在设备全局设置 `opt-level=3`、`overflow-checks=on`、`force-unwind-tables=yes`、`panic=abort`，并默认对适用的 Rust 最终链接启用 ThinLTO。下面的省略行为依据 Rust 编译器文档与 Soong 配置说明，项目模块仍可能覆写属性。

1. **优化级别：**`-C opt-level=3` 请求 LLVM 执行最高常规优化级别，面向运行性能。省略时 rustc 默认是 `0`，不执行常规优化。Android 在设备构建中显式设为 `3`，不能据此推断每个热点都更快，仍需测量。
2. **整数溢出检查：**`-C overflow-checks=on` 在运行时检测整数溢出并触发 panic。省略时检查是否启用取决于 debug assertions，启用断言时开启，否则关闭。AAOS 13 设备配置显式开启它，所以即使采用优化构建也不能按常见 release 默认值推断为关闭。
3. **栈展开表：**`-C force-unwind-tables=yes` 强制生成 unwind tables，供目标平台栈展开或诊断使用。省略时行为取决于 target。不能从 `panic=abort` 推断表一定被删除，也不能仅凭该选项推算二进制体积变化。
4. **panic 策略：**`-C panic=abort` 让 panic 终止进程而不进行 Rust 栈展开。省略时默认值取决于 target，Rust Reference 说明多数 target 默认 `unwind`。Android 设备构建在此显式选择 `abort`。
5. **ThinLTO：**Soong 的 Rust `lto.thin` 属性默认是 `true`，构建器在适用的最终链接类型上追加 `-C lto=thin`，让 LLVM 跨 crate 做 ThinLTO。模块显式设为 `false` 时不会走这个默认分支。这不等于 Rust 对所有 crate 输出形态都统一附加该参数，例如 rlib 编译路径不追加最终链接参数。
6. **版本核对：**AAOS 13 的具体设备参数应以 `build/soong/rust/config/global.go` 和 `build/soong/rust/builder.go` 为准。Soong 的 `lto.thin` 属性行为应看同一源码版本的 `rust/compiler.go`。Android 分支和模块配置变化时，应重新检查最终 rustc 命令，不能把当前 Soong 主分支行为倒推成旧版本事实。

**Q4: [learning] Rust Android 代码未声明 #[global_allocator] 时如何分配？Scudo 与 jemalloc 的适用边界是什么？**

没有自定义 `#[global_allocator]` 时，使用 Rust 标准库的系统分配器。Android 上它与进程使用的 native allocator 协同工作，因此 Rust `String`、`Vec` 等堆分配仍会产生本机堆分配成本。Android 11 起 Scudo 用于 native 代码，低内存设备仍可能使用 jemalloc，具体产品与版本应以设备配置为准。

1. **Rust 分配入口：**`#[global_allocator]` 用于选择进程级 Rust 全局分配器。未声明时使用 Rust 标准库提供的默认全局分配器。Android 目标上的 `System` 分配器以 Unix `malloc`/`free` 接口为底层入口，因此 Rust 堆分配进入进程的 native allocator 路径。具体 target 和链接产物应以所用 Rust 标准库实现为准。
2. **常规设备：**Android 11 起 Scudo 服务 native 堆分配，提供 chunk 元数据校验、延迟释放队列和其他一致性检查，用于缓解堆越界、释放后使用、重复释放等问题。产品配置可能改变具体实现，排查时要核对目标设备。
3. **低内存设备：**Android 文档说明低内存设备仍使用 jemalloc。不能将 Scudo 描述为所有 Android 设备唯一的 allocator，也不能把某个设备的分配器选择直接推广到所有产品。
4. **安全能力边界：**Scudo 是缓解机制，不是完整的内存错误检测器，也不等同于 ASan。不能假定它为每次分配都在对象两侧建立 guard page，性能分析要针对实际 allocator 与负载。

**Q5: [learning] Android 设备构建使用 panic=abort 时，catch_unwind 为什么不能恢复 FFI 调用？错误应该怎样表达？**

`catch_unwind` 只能捕获通过栈展开传播的 Rust panic。Android 设备构建使用 `panic=abort` 时，panic 直接终止进程，所以它不能在 FFI 入口恢复调用。跨语言边界的可预期失败应使用显式错误结果，而不是把 panic 当作常规控制流。

1. **策略决定行为：**`unwind` 会展开 Rust 栈，`catch_unwind` 才可能在同一线程捕获这类 panic。`abort` 直接终止进程，不执行可恢复的展开流程。
2. **FFI ABI 限制：**普通 `extern "C"` 是不允许 unwind 穿越的 ABI。即使其他构建采用 `unwind`，也不能默认让 panic 越过 C ABI 或 C++ 异常越过不支持 unwind 的边界。跨语言展开必须使用明确支持它的 ABI，并遵守各语言运行时限制。
3. **错误表达：**参数校验、资源不可用和服务调用失败等预期情况应由 Rust API 返回 `Result`，再映射为 C 错误码、AIDL `Status` 或协议状态。内部不变量被破坏时可以 panic，但设备端会按 abort 策略终止进程，不能承诺局部恢复。
4. **诊断能力：**`force-unwind-tables=yes` 只强制保留 unwind tables，不会把 `panic=abort` 改成可恢复展开。崩溃诊断信息与进程内异常恢复是两种不同能力。

**Q6: [learning] 评估 Android Rust 系统服务性能时，怎样拆分 Binder、FFI、JNI 和运行时成本？**

不要用语言标签或空函数微基准推断服务快慢。按调用边界拆分，再用端到端跟踪与采样确认主要耗时。如果热点来自排队、复制、锁或硬件访问，改语言边界通常不会解决根因。

1. **跨进程 Binder：**分别观察 Parcel 编解码与 fd 处理、用户态和内核态切换、目标线程唤醒与排队、大参数复制、服务端锁及硬件调用、回包与调用方重新调度。Perfetto 可记录 client 发起、driver 排队、server 进入 runnable、server 执行、reply 返回五个时间点。只在服务函数入口计时会漏掉调用方阻塞和调度延迟。
2. **同进程 FFI：**标量和 ABI 兼容结构的 `extern "C"` 调用可以很轻，但跨语言编译单元通常不能互相内联。借用 slice 可按指针和长度传递，避免为边界本身复制 payload。owned `String`、`Vec`、`CString` 或 C++ 容器的构造与所有权转换可能分配或复制。opaque object 还需约定析构方与线程安全，常见做法是由创建对象的一侧导出 destroy 函数。
3. **JNI：**把 managed/native 转换、局部与全局引用管理、字符串和数组转换、线程 attach/detach、异常检查分别纳入测量。大 payload 可比较 direct `ByteBuffer` 或共享内存路径，不能把避免数组复制的收益与其他 JNI 成本混为一谈。
4. **服务工作与锁：**Keystore 操作的总耗时可能来自 Binder 排队、数据库事务、SELinux 检查、KeyMint HAL 或 TEE。`Arc<Mutex<T>>` 未竞争、跨核竞争、优先级反转和持锁 I/O 是不同瓶颈，应通过线程状态和锁等待证据区分。
5. **二进制与运行时：**泛型单态化和 async state machine 可能增加 `.text`。动态链接可共享代码页，静态 rlib 配合 ThinLTO 则可能移除未用代码。Keystore2 使用 `prefer_rlib` 的源码理由与 `/system` 上动态 Rust 进程数量有限有关，这是组件级取舍，不是所有服务都应静态链接的通则。
6. **测量工具：**用 simpleperf 观察 cycles、instructions 和 branch misses，用 heapprofd 定位 malloc 热点，用 llvm-size 或 readelf 查看文件组成，再用 showmap 区分磁盘体积与运行时 PSS。Soong 使用 Rust v0 符号 mangling。分析采样报告前先确认符号和 build ID 已加载，避免拿 unknown symbol 结果比较语言。
7. **优化顺序：**先减少不必要的 Binder 往返、缩短持锁区并避免重复编码或复制。只有 profiler 证明 bridge 或 linkage 占比显著时，才优先调整参数表示、所有权边界或链接方式。
