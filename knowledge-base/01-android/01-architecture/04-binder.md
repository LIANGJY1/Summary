# Binder

> 学习资料（文章模式沉淀）。主线：Binder 架构组成与"一次拷贝"、AIDL/Stable AIDL/HIDL 契约差异、AIDL/Parcel 实战坑。等待链诊断与事务缓冲区深挖见 [04-binder.md](04-binder.md)；2026-09-25 增补调试工具题（Q16–Q18，按 AOSP 近版源码镜像核对）。Q 序列即结构，供 atlas 同源直读。

**Q1: Binder 的整体架构由哪几部分组成？"一次拷贝"到底省在哪里？**

Binder 由用户态 libbinder、内核 Binder 驱动和 servicemanager 名称注册表三部分完成传输，AIDL 在其上提供接口契约；"一次拷贝"指驱动把事务数据从发送方用户空间直接复制到接收方 mmap 映射的缓冲区，省去了传统 IPC"用户空间→内核→用户空间"的两次拷贝。

1. **libbinder**（`ProcessState`/`IPCThreadState`）：管理接收事务的映射区和 Binder 线程池，封装事务；Java 层的 Binder 经 JNI 落到这里。
2. **内核驱动**：`/dev/binder`（框架）、`/dev/hwbinder`（旧 HAL）等独立设备节点维护各自的上下文；负责路由事务、管理缓冲区映射、请求用户态增减线程、唤醒目标线程。
3. **servicemanager**：Binder 的 context manager，服务进程按名字注册，调用方按名字查询到目标句柄后再发起事务。
4. **AIDL**：接口描述语言，编译期生成代理与 Stub，属于接口契约层，不是传输机制本身。

边界："一次拷贝"只描述事务数据的搬运。一次同步调用的端到端延迟还包含线程排队、上下文切换、权限与 SELinux 检查、目标服务执行和下游依赖，不能用"一次拷贝"推出调用一定快；接收方默认映射区约 1 MB 减两页；Binder 线程上限按进程配置而不同（默认 15 个按需 lazy 线程，`system_server` 显式设 31）——"每个进程固定 15/16 个线程"会误导容量分析，饥饿判定与容量锚点见 [04-binder.md](04-binder.md) Q12。

**Q2: 为什么"Binder 单次调用可以安全传接近 1MB"是错误结论？**

因为约 1MB 是一个进程的接收映射区总大小，不是单笔事务的配额：精确值为 `1 MiB − 2×页大小`（4KiB 页设备约 1016KiB，16KiB 页设备约 992KiB），进程内所有在途事务——并发请求、回复、oneway、对象元数据——共享这块空间。`TransactionTooLargeException` 也无法区分是请求没有发出还是回复过大，只能按"操作可能部分完成"处理。

- `BINDER_VM_SIZE = (1×1024×1024) − sysconf(_SC_PAGE_SIZE)×2` 是 libbinder 请求的映射长度；内核驱动另有 mmap 上限 `min(请求长度, 4 MiB)`，那是保护性上限，不代表默认分配 4MB。
- 请求 buffer 分配在目标进程的 `binder_alloc` 中；B 回复 A 时占用的又是 A 的空间。多个中等事务并发也可能共同耗尽缓冲区。
- 内核把一半映射作为异步事务初始预算（`free_async_space = buffer_size/2`），大量 oneway 也会触发分配失败。
- 工程规则：Binder 事务保持小；大数据改用文件描述符、分页或流式传递，不要把"单次不到 1MB"当作安全线。

**Q3: Binder 相关的约 1MiB、4MiB、600KiB、300KiB 这几个数字分别约束什么？**

它们属于四个不同对象：`1 MiB − 2×页大小` 是 AOSP libbinder 为每个进程请求的接收映射长度；`min(请求长度, 4 MiB)` 是内核驱动接受的单个 mmap 保护上限；600 KiB 是 RPC Binder 单条命令/回复包的协议上限（Android 15/V 及以前为 100 KB，Android 16/Baklava 起提高到 600 KiB）；300 KiB（`kLogTransactionsOverBytes`）只是大事务告警线，不是硬上限。

- 4MiB 不等于默认 4MB：AOSP 进程只请求约 1MiB，实际 `buffer_size` 仍是 1016KiB（4KiB 页）或 992KiB（16KiB 页）。
- 600KiB 只作用于 RPC Binder 路径（socket/vsock 传输），Parcel 可用空间还要再扣除协议头与对象表；普通应用调用系统服务走内核 Binder，不受此值影响，也不会因此获得更大内核 Binder 空间。
- 300KiB 告警分别来自 `BpBinder` 的 `Large outgoing transaction` 与 `BBinder` 的 `Large data transaction`/`Large reply transaction`；出现说明事务在挤压进程并发空间，应检查接口是否把大块数据、无界列表或图片直接塞进 Parcel。
- 另有 `BBinder` 1000ms 慢事务日志：测量的是服务端 `onTransact()` 执行区间，不含到达前的排队，不是端到端耗时。

**Q4: AIDL、Stable AIDL 以及 HIDL 之间有什么区别？**

AIDL 是接口描述语言本身，编译期生成代理与 Stub；Stable AIDL 是给 AIDL 加上"接口不破坏兼容"稳定性承诺的用法（`@VintfStability` 注解 + VINTF 清单冻结），Android 10 起用于 system/vendor 边界并成为新 HAL 的首选；HIDL 是 Android 8.0 Treble 为解耦 system/vendor 专门设计的上一代接口语言，Android 11 起进入弃用流程、新接口不再采用，存量实现仍被支持。

按维度对比：

1. **定位**：AIDL 是语言；Stable AIDL 是同一语言加版本化与兼容性契约；HIDL 是独立设计的另一套接口语言与运行时；
2. **传输与进程模型**：普通 AIDL 与 Stable AIDL 走 `/dev/binder` 与 libbinder，服务以 Binder 服务进程运行；HIDL 服务化形态走 `/dev/hwbinder` 与 libhwbinder，另有直通式（passthrough）以共享库加载进调用方进程，没有独立 HAL 进程；
3. **稳定性契约**：Stable AIDL 用 `@VintfStability` 声明可跨 system/vendor，接口与枚举只能增不能改，并进 VINTF 兼容矩阵；普通 AIDL 无此承诺，接口随平台演进可改，因此不能跨 vendor 分区使用；HIDL 用 `@1.0`、`@1.1` 式版本继承维持兼容；
4. **语言后端**：Stable AIDL 支持 Java、NDK C++ 与 Rust 后端；HIDL 生成 C++ 与 Java；
5. **现状（Android 17 语境）**：新接口一律 Stable AIDL；存量 HIDL HAL 仍会保留以兼容旧厂商镜像，不能仅凭系统版本假定全部 HAL 已迁移。

选型规则：应用或框架内部跨进程用普通 AIDL；跨 system/vendor 边界或新写 HAL 用 Stable AIDL（声明 `@VintfStability` 并进 VINTF 清单）；HIDL 只在维护存量厂商实现时接触。

**Q5: AIDL 生成的 Stub、Proxy 与 Binder 事务如何连接客户端和服务端？**

生成的 Stub 是服务端 Binder 入口，负责检查接口描述符、分发事务码并把 Parcel 参数还原为接口方法调用；Proxy 是客户端生成的接口实现，把方法参数写入 Parcel，再通过 `transact()` 发送事务。AIDL 生成的接口描述符用于验证双方约定的是同一接口。

典型生成接口扩展 `IInterface`，Stub 继承 Binder 并实现接口，提供 `asInterface()` 把 Binder 引用包装成接口，并在 `onTransact()` 中按事务码分发；Proxy 负责跨进程封送参数。`DESCRIPTOR` 标识接口，`asBinder()` 暴露 Binder 引用。部分旧版生成器还会输出 Default 辅助实现；生成类名和回退辅助方法随 AIDL 工具版本变化，应用应依赖接口契约而非手改生成文件。

同步方法通常等待服务端执行并读取 reply；声明为 `oneway` 的方法不等待返回值，适合无结果的异步通知，但仍受 Binder 排队、线程池和事务大小约束。AIDL 支持的类型与生成模板随语言后端和工具链变化，需按项目 `.aidl` 编译器版本核对，不能把某个生成文件的内部命名当作稳定 API。

AIDL 接口常用原始类型、String、CharSequence、Parcelable 类型、Binder 接口，以及受工具链约束的 List/Map 等容器；自定义 Parcelable 类型通常还要提供对应 AIDL 声明或按当前 AIDL 语言版本可识别。参数方向决定对象传输方向，复杂或大型载荷应谨慎评估序列化成本。

**Q6: Android 为什么以 Binder 作为常用 IPC，而不是所有场景都用 socket 或共享内存？**

Binder 把跨进程调用包装成轻量 RPC：调用方通过强类型接口发起方法调用，驱动负责把事务送到目标进程并管理 Binder 对象引用，服务端在线程池处理请求；内核还能把调用方身份等信息带到服务端进行权限判断。AIDL 再把接口声明与参数编解码代码生成出来，减少手写协议和路由代码。

Socket 更适合字节流协议、网络通信或自定义传输，应用必须自行处理消息边界、身份认证和 RPC 语义；共享内存适合高频大数据交换，但同步、对象生命周期与访问控制要由双方设计。Binder 面向 Android 设备本地服务调用更方便，但仍有序列化、线程调度与事务大小成本，不能推导为所有 IPC 负载下都最快。

**Q7: Android 的 IPC 机制应怎样分层选型？为什么说一条真实链路经常同时使用两三层机制？**

按职责分四层：上层语义与接口（AIDL、Messenger、Intent、ContentProvider）描述"做什么"；控制面 transport（Binder、Unix 域套接字、socket/vsock 上的 Binder RPC）负责控制信息传输；数据面（SharedMemory/mmap、CursorWindow、DMA-BUF、FMQ）承载持续数据；通知与唤醒（eventfd、signal）负责同步。数据形态、方向、频率、生命周期与权限边界决定选择，一条真实链路经常组合使用。

- 典型组合：InputManager 经 Binder 把 `InputChannel` 的文件描述符交给应用，后续输入事件与确认走匿名 `SOCK_SEQPACKET` 套接字；Binder 先传 `SharedMemory` 的 fd，之后双方直接读写同一块映射；HAL 用 AIDL/HIDL 建 FMQ，持续数据走共享内存环形队列。
- 控制信息适合放进 Parcel（方法号、少量参数、令牌、fd）；图像、音频帧、模型权重或大型结果集不应反复写入 Parcel，常见做法是只传句柄或 fd，让接收方据此访问实际数据。
- 错误常出在所有权而非传输：fd 由谁关闭、映射何时解除、DMA-BUF 栅栏完成前谁不能复用缓冲、FMQ 读写方死亡后如何重建，都需要协议明确。
- 不要引用固定的"Binder 0.5ms、Socket 0.1ms"式数字：IPC 延迟至少包含 client 编组、驱动传输、server 排队调度、server 业务与 reply 返回，数据量、CPU、线程池与 SELinux 都会改变结果；比较必须在同一设备、相同负载与统计口径下做。

**Q8: oneway 调用到底保证了什么、没保证什么？`BR_TRANSACTION_COMPLETE` 代表服务端执行完成吗？**

oneway 只保证"调用方不等待业务回复"与"发往同一个 Binder 节点的异步事务按发送顺序逐个分发"；它不保证服务端已执行、不保证跨节点全局有序、也不保证不会失败。`BR_TRANSACTION_COMPLETE` 只表示驱动完成了本次提交——目标进程可能尚未被调度，事务可能仍在 `proc->todo` 或 `node->async_todo` 中排队。

- 排队结构：同一 Binder node 的异步事务串行执行——第一笔在处理时，后续进入该 node 的 `async_todo`，当前 buffer 释放后才取下一笔；不同 node 的异步事务可以并行。
- 优先级：oneway 事务不继承调用方线程优先级，使用目标进程默认优先级；目标 node 自身配置的 `min_priority` 仍参与服务端执行优先级。
- 失败面：提交仍要在目标进程分配 buffer，异步空间不足会得到 `-ENOSPC`/`FAILED_TRANSACTION`；目标进程死亡或冻结也有对应结果。服务端 oneway 方法抛出的异常不会写回调用方，需要业务确认时应设计独立回调并带超时。
- 协议设计：A 必须先于 B 生效时，让 A、B 经过同一个串行执行点，或给消息加序列号与状态校验，不能依赖"都是 oneway"；用 oneway 掩盖服务端过载只会把延迟转移到队列里。

**Q9: oneway 事务在驱动里如何排队？异步空间紧张时调用方会看到什么？**

oneway 事务仍要在目标进程分配 buffer 并由 Binder 线程执行；同一 Binder node 串行、不同 node 并行。当目标进程剩余异步预算低于总映射的 10%，且当前发送进程占用超过 50 个异步 buffer 或总占用超过总映射的 1/4 时，该笔事务被标记 `oneway_spam_suspect`：发送线程收到 `BR_ONEWAY_SPAM_SUSPECT`，libbinder 打印发送侧调用栈。这是诊断信号，不做限流，事务本身通常仍成功。

- 记账模型：`free_async_space` 初始化为 `buffer_size/2`，同步与异步从同一棵空闲树分配；异步分配前检查并扣减预算，释放后归还。它不是把一半物理划给 oneway 的独立内存池。
- 触发 spam 检测时，目标进程的异步预算已经非常紧张；治理要在协议层完成——合并可覆盖的状态更新（只留最新值）、限频、为事件队列设置容量与丢弃策略，不能等驱动告警才处理。
- oneway 的 buffer 生命周期不一定比同步短：没有回复触发释放，要到服务端处理完成并释放 Parcel 后才归还；高频发送会同时占据目标进程地址池。

**Q10: "Binder 线程池默认 15 个线程"该怎么准确理解？怎样判断服务端真的发生了线程池饥饿？**

15（`DEFAULT_MAX_BINDER_THREADS`）是 libbinder 经 `BINDER_SET_MAX_THREADS` 告诉驱动"最多可按需请求启动的 lazy 线程"上限；`startThreadPool()` 还会主动创建 1 个主线程池线程，显式 `joinThreadPool()` 与服务自设上限再叠加。因此"每个进程固定 15 或 16 条 Binder 线程"都会误导容量分析。饥饿判定要看组合证据，而不是数线程或单看一条日志。

- 驱动在"没有尚未兑现的线程请求、`waiting_threads` 为空、已启动线程数低于 `max_threads`"时返回 `BR_SPAWN_LOOPER`，libbinder 才按需建线程；线程创建后通常存活到进程结束。
- AOSP Android 17 的 system_server 设 `sMaxBinderThreads = 31`（另有 1 个主动线程，总量上界通常可到 32）；应用主线程不默认加入 Binder 池。
- libbinder 的启发式日志：执行中的 Binder 线程数达到配置上限并持续超过 100ms 时输出 `binder thread pool (N threads) starved for M ms`。它只说明"该进程长时间没有空闲工作线程"，不能证明驱动队列有积压或延迟全由 CPU 忙导致；`blockUntilThreadAvailable()` 存在但标准 `transact()` 路径不会调用它。
- 饥饿常见根因：慢接口实现（持锁跨进程调用、无超时 I/O、数据库长事务）、嵌套同步调用占用 worker、oneway 积压。只有当多个请求可安全并行、共享资源有余量、轨迹证明等待空闲 worker 占主导时，增加线程才可能有效；所有 worker 都在等同一把锁时，加线程只会增加等待者。

**Q11: 大数据跨进程传输应该如何设计？`writeBlob` 的 16KiB 分界、`SharedMemory.setProtect()` 和 FMQ 各自的边界是什么？**

原则是 Binder 只传控制信息与句柄，持续数据走共享内存、文件描述符或专用队列。C++ `Parcel::writeBlob()` 以 16KiB 为分界：不超过 16KiB 直接内联写入 Parcel；超过且允许传 fd 时改走兼容 ashmem 区域并用 fd 传递。Java `SharedMemory.setProtect()` 只能移除权限不能加回，应按"写入 → 解除映射 → 降为只读 → 交给对端"的最小权限顺序使用。FMQ 是共享内存上的有界单向队列，单个队列只有一个写入方；双向协议要建两条方向相反的队列。

- `writeBlob` 分流只作用于二进制块路径；普通字节数组与集合仍会被内联编组，"所有大 `byte[]` 自动走共享内存"不成立。
- 共享内存不是端到端零拷贝：生产方写入、缺页与缓存同步、消费方读取复制仍然存在；共享页也没有消息边界与顺序，需要自建协议（版本、长度、状态、校验）。
- FMQ 的 synchronized 队列单读单写、不允许覆盖；unsynchronized 队列可多读但写入可覆盖旧数据、落后的读取方丢数据。它适合固定布局、高频、小单元的数据流，不适合可变长复杂对象或需要逐条权限检查的协议。
- 走 socket/vsock 的 Binder RPC（如 Microdroid pVM 场景）不使用内核 Binder 的接收映射，"1MB 缓冲区"结论对它不适用。

**Q12: 多进程应用的共享内存与消息传递各有什么取舍？**

共享内存适合多个进程反复访问较大数据块，可避免每次调用都复制完整载荷，但必须自行设计同步、所有权与生命周期，避免读写竞态和过期数据。消息传递适合边界清晰、数据较小的请求与事件，能把权限与调用契约集中在接口上，但会有序列化、排队和传输开销。

Android 常用 Binder/AIDL 传递结构化请求与响应；大型二进制内容通常传递文件描述符、共享内存句柄或 URI 等引用，而不是塞进 Binder Parcel。选型看数据规模、更新频率、同步需求和安全边界，不能只按“快”选共享内存。

**Q13: AIDL 接口"能用但慢"，或跨进程 Bundle 在读取处抛 ClassNotFoundException——参数方向与 Parcel 实战有哪些坑？**

三个实战要点：方向标签决定序列化成本；oneway 的保序只在"同一 Binder 节点"内成立；Bundle 的反序列化是延迟的，崩溃点在读端而非调用处。

1. **方向标签**：`in` 只把数据从客户端送到服务端（原语类型默认）；`out` 由服务端填充空对象回传；`inout` 双向序列化、成本约为 in 的两倍——官方 Stable AIDL 指南明确建议避免 inout，能拆成返回值或两次调用就不要用；
2. **oneway 保序的精确边界**：同一 Binder 节点的异步事务按发送顺序串行执行，不同节点之间没有顺序保证；`BR_TRANSACTION_COMPLETE` 只表示驱动受理完成，不代表服务端已执行——排队结构、优先级与失败面见 [04-binder.md](04-binder.md) Q12/Q13；
3. **Bundle 延迟反序列化**：Bundle 收到时只保存原始数据，首次 `getParcelable` 才真正 `unparcel()`——自定义 Parcelable 的类不在接收方进程 classpath 时，崩溃发生在**读取处**而不是 Binder 调用处，表现为 `ClassNotFoundException`/`BadParcelableException`；排查先看发送方塞了什么；
4. **自写 Parcelable 纪律**：`describeContents()` 含文件描述符必须返回 `CONTENTS_FILE_DESCRIPTOR` 且嵌套对象要聚合；`writeToParcel` 与 `CREATOR` 的字段读写顺序必须严格一致。

**Q14: 排查 Binder 问题时，冻结状态位、扩展错误、AIDL trace 与 Perfetto 表各自能回答什么、不能回答什么？**

它们是不同观察面，没有统一的"粗到细"层级：binderfs `features` 文件声明驱动能力（如 `oneway_spam_detection`、`extended_error`、`freeze_notification`），不表示运行状态；`BINDER_GET_FROZEN_INFO` 返回 `sync_recv`/`async_recv` 位标志，不提供次数或耗时；`BINDER_GET_EXTENDED_ERROR` 是线程级一次性信息（`id`/`command`/`param`，读取后立即重置），libbinder 只对 `ENOSPC` 给出专门解释；AIDL trace（`ATRACE_TAG_AIDL`）提供 `AIDL::cpp::<接口>::<方法>::server` 形式的方法时间片，不含参数内容；Perfetto `android_binder_txns` 关联两端与同步类型，但没有 `dispatch_dur` 列——服务端开始延迟要用 `server_ts − client_ts` 自行计算并解释。

- `sync_recv == 3` 表示两个状态位都为 1，不是"发生了三次同步事务"；`async_recv` 也不是单调计数。
- AIDL 名称依赖轨迹数据可解析：只采集内核 Binder 事件时 `interface`/`method_name` 可能为空；映射缺失或事务码不在用户方法范围时，切片名退化为 `UNKNOWN_CODE_<n>`。
- 内核 tracepoint 有 `binder_transaction`、`binder_transaction_received`、`binder_transaction_alloc_buf`、`binder_txn_latency_free`；不存在名为 `binder_reply` 或 `binder_freeze` 的 tracepoint，不要用不存在的内核事件名标记区间。
- debugfs/binderfs 状态文件是读取时刻的快照，两次读取之间完成的事务可能完全看不到。

**Q15: `RecordedTransaction` 能做什么？为什么不能当线上常驻监控？**

`RecordedTransaction` 是 libbinder 的事务录制能力，可保存接口名、事务码、flags、返回状态与请求/回复 Parcel 内容，适合受控环境下复现协议问题与离线检查。它同时受三个条件限制：libbinder 编译期定义 `BINDER_ENABLE_RECORDING`、使用内核 Binder、发起录制的调用方 UID 为 root；源码明确标记文件格式仍在开发、不稳定，录制内容可能包含令牌等敏感数据，序列化与写文件还会改变被测路径的时延。

- 时间戳在服务端 `onTransact()` 返回后采集，不能当作事务开始时间；发送路径没有对称的客户端录制入口，"两端各录一次拼出端到端"不成立。
- 端到端时间应由 Perfetto 的 Binder flow 解释；录制文件要按敏感数据管理，分析完成后清理。
- 与 `binder_calls_stats` 区分：后者是 framework 按 UID/接口/方法聚合的统计（带抽样），不是驱动队列监视器，也不是"更细统计模式"开关。

**Q16: 排查 Binder 问题有哪些实用工具？驱动调试节点在新内核上从哪里读？**

三个层次：用户态统计用 `dumpsys binder_calls`，驱动态状态用 binder 调试节点，单个服务的在位确认用 `dumpsys -l`/`service list`（见 Q17）。

1. **dumpsys binder_calls**：按 UID 汇总调用 CPU 耗时，列格式为 cpu_time（微秒）| 占比 | recorded_call_count | call_count | 包名/UID——单 UID 占比异常高即 Binder 热点；call_count 远大于 recorded_call_count 说明被采样截断；末尾 Exceptions 段计数大于 0 表示远端调用抛过异常，按类名追；
2. **驱动调试节点（双路径）**：新内核经 binderfs 挂在 `/dev/binderfs/binder_logs/`（旧路径 `/sys/kernel/debug/binder/`），节点含 state、stats、transactions、transaction_log、failed_transaction_log 等，另有 `proc/<pid>` 按进程列出各线程正等待/处理的事务——线程池耗尽的快速取证就是读它；
3. **判读要点**：failed_transaction_log 里 BR_DEAD_REPLY/BR_FAILED_RETURN 密集说明对端死亡或句柄失效（环形日志，只保留最近若干条）；
4. **权限**：userdebug + root 才能读驱动节点（`su 0 cat`）。

**Q17: 怎么确认某个 Binder/AIDL 服务在设备上注册了？`service call` 能做什么？**

1. **列服务**：`dumpsys -l` 只列服务名不 dump 内容；`service check <名>` 返回在/不在；
2. **探活**：`service call <名> <事务码>` 手动发一笔事务（事务码对应 AIDL 方法在接口里的声明序号），可确认服务存活且响应——参数构造要谨慎；
3. **版本差异**：Android 11+ 的 AIDL servicemanager 统一注册所有 Binder 服务，因此 AIDL HAL 也出现在 `dumpsys -l` 里（名字形如 `android.hardware.xxx.IXxx/default`）——与只列 HIDL 的 lshal 区分（见 [05-hal.md](05-hal.md) Q2）。

**Q18: tombstone 里 "RefBase: object ... with strong count 1 deleted. Double owned?" 是什么错？**

RefBase（libutils/Binder 原生对象的基类）在引用计数被破坏时直接 abort："Double owned?" 的典型成因是同一对象被 `sp<>` 智能指针与手动 delete（或两套所有权机制）同时管理，强计数归零析构后又有释放动作到达——Java 侧 GC/finalize 与 native `sp` 释放的竞态是常见来源。

1. **定位**：tombstone 的 `Abort message:` 行给出断言文案与对象地址，backtrace 指向出错的 decStrong/incStrong 调用方；
2. **相关形态**：把栈上对象交给 `sp` 会触发专门拦截（引用计数无处存放）；弱计数下溢、显式析构后使用弱引用也各有专门断言；
3. **调试与修复**：竞态类问题可开 libutils 的引用计数调试编译重跑；修复思路是收敛所有权——一个对象只归一种所有权机制管理。
