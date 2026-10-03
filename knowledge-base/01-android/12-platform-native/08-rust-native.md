# 平台 Rust 与 FFI

> 学习资料（文章模式沉淀）。边界：本文回答"Android 平台 Rust 的引入动机、设备端编译配置与分配器路径、Rust 服务的性能评估边界"；平台语言全景归 [../01-architecture/01-system-architecture.md](../01-architecture/01-system-architecture.md)。源文档：android-internals-wiki（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 平台为什么引入 Rust？Keystore2 这类 Rust Binder 服务与"Rust 经 JNI 跳 C++ AIDL"的说法差在哪？**

Rust 的主要目标是减少新增 native 代码中的内存安全缺陷（Google 2021 年发布支持时引用内存不安全约占当时 Android 高严重性漏洞 70% 的历史数据，不能当现状统计），迁移策略侧重新增代码与可独立替换的组件，长期保留混合语言结构。组件形态要按源码说（材料按 android-17.0.0_r1 核对）：Keystore2 与 VirtualizationService 是 Rust Binder 服务，直接用 Rust AIDL backend 与 libbinder_rs，服务启动后 add_service 注册——Java 客户端经一笔 Binder transaction 到达 Rust stub，没有固定的 C++ AIDL 中转层，"每次调用经 JNI 再进 C++ AIDL 再到 Rust"是误判。DnsResolver 仍是 C++ 与 Rust 混合（libresolvrs_ffi 经 CXX bridge 参与 DoH/HTTP3 部分），UWB 与 Bluetooth 的 Rust 库是渐进替换组件——把整个 resolver 或蓝牙栈标成 Rust 服务超出源码证据。

性能上语言迁移没有固定方向：跨进程 Binder 的成本主要在 Parcel 编解码、内核事务、线程调度与服务端工作；同进程 C ABI 边界可能只是一次普通函数调用，但两侧编译单元通常无法内联，字符串、容器与所有权转换会引入分配复制，回调频率高会放大固定成本。Keystore 操作常见的耗时来源是 Binder 排队、数据库事务、SELinux 检查、KeyMint HAL 与 TEE——只测一个空 FFI 函数的纳秒值解释不了端到端时延。

**Q2: Android 设备端的 Rust 编译配置与分配器路径是什么？"panic 可以在 FFI 入口用 catch_unwind 恢复"错在哪？**

按 AAOS13 源码核对（build/soong/rust/config/global.go），设备端 Rust 全局启用 -C opt-level=3、-C overflow-checks=on、-C force-unwind-tables=yes 与 -C panic=abort，且 build/soong/rust/builder.go 默认追加 -C lto=thin（ThinLTO 默认开启）——这套配置与 Android 17 材料一致，比通用 Rust 项目的经验数字更适合解释 Android。panic=abort 意味着 panic 直接终止进程、不沿栈展开，catch_unwind 不能为设备构建提供进程内恢复；FFI API 应把可预期失败编码为 Result、错误码或 AIDL Status，panic 只留给内部不变量被破坏。force-unwind-tables 同时保留了栈回溯与诊断所需的 unwind table，不能根据 panic 策略推算二进制缩小比例。

分配路径：未设 #[global_allocator] 的 Rust 标准库代码经 libc malloc/free 进入设备 native allocator——常规设备是 Scudo（硬化分配器，含 chunk 元数据校验、隔离与 quarantine），低内存产品可能是 jemalloc；改用 Rust 不会消除分配器的安全成本，Scudo 也不是逐对象 guard page、不是完整 ASan，不能用"每块分配前后都有保护页"当成本模型。整数溢出检查在运行期进行，"安全检查全部在编译期完成"的说法与设备构建配置不符；LLVM 能在循环边界清晰时消除部分检查，但结果依赖代码形态，评估要用 benchmark 与反汇编确认热点是否真有检查残留。

**Q3: 评估一个 Rust 系统服务的性能时，应把哪些边界成本拆开测量？**

四类边界分开。跨进程 Binder：成本在 Parcel 编码与 fd 处理、用户/内核切换、目标线程唤醒排队、大参数复制、服务端锁与硬件调用、回包与重新调度；Perfetto 里建议记五段——client 发起、driver 排队、server 进入 runnable、server 执行、reply 返回，只在服务函数入口计时会漏掉调用方阻塞与调度延迟。同进程 FFI：extern "C" 的标量与 ABI 兼容结构传递可很轻，但跨语言内联通常不存在；参数形态决定复制——借用 slice（&[u8]/rust::Slice）以指针加长度传递可零复制，owned String/Vec、CString 或 C++ 容器则按构造方式分配复制，opaque object 还要约定析构方与线程安全，创建对象的一侧导出 destroy 是常见做法。JNI：拆成 managed/native 切换、局部/全局引用管理、字符串数组转换、线程 attach/detach 与异常查询；大 payload 可评估 direct ByteBuffer 或共享内存。

运行时成本另算：Arc<Mutex<T>> 的未竞争、跨核竞争、优先级反转与持锁 I/O 是不同问题；泛型单态化与 async state machine 扩大 .text，动态链接共享代码页而静态 rlib 让 ThinLTO 删未用代码——Keystore2 选 prefer_rlib 的源码理由是 /system 上动态 Rust 进程数不足，属组件级取舍，不能推广成所有服务都应静态链接。工具上用 simpleperf 统计 cycles/instructions/branch-misses、heapprofd 看 malloc 热点、llvm-size/readelf 与 showmap 区分磁盘体积与运行时 PSS；Soong 用 Rust v0 symbol mangling，采样报告先确认符号与 build ID 已加载，不要对着 unknown 符号比较语言。优化顺序先架构级成本（减少 Binder 往返、缩短持锁区、避免重复编码复制），profiler 显示 bridge 占比高时才调 bridge 表示或 linkage。
