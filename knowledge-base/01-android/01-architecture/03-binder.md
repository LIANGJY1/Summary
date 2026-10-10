# Binder

> 学习资料（文章模式沉淀）。主线：Binder 架构与数据传输、AIDL 接口契约、事务调度与缓冲区，以及排查工具和常见原生故障。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] Binder 的整体架构由哪几部分组成？“一次拷贝”到底省在哪里？**

Binder 由用户态 libbinder、内核 Binder 驱动和 servicemanager 名称注册表三部分完成传输，AIDL 在其上提供接口契约。“一次拷贝”指驱动把事务数据从发送方用户空间直接复制到接收方 mmap 映射的缓冲区，省去了传统 IPC“用户空间→内核→用户空间”的两次拷贝。

1. **libbinder**（`ProcessState`/`IPCThreadState`）：管理接收事务的映射区和 Binder 线程池，封装事务。Java 层 Binder 经 JNI 落到这里。
2. **内核驱动**：`/dev/binder`（框架）、`/dev/hwbinder`（旧 HAL）等独立设备节点维护各自上下文。Binder 驱动是 Linux 内核软件，不是硬件。它负责路由事务、管理缓冲区映射、请求用户态增减线程和唤醒目标线程。
3. **servicemanager**：Binder 的 context manager，服务进程按名字注册，调用方按名字查询到目标句柄后再发起事务。
4. **AIDL**：接口描述语言，编译期生成代理与 Stub，属于接口契约层，不是传输机制本身。

一次同步调用的端到端延迟还包含线程排队、上下文切换、权限与 SELinux 检查、目标服务执行和下游依赖。因此“一次拷贝”不能推出调用一定快。

**Q2: [learning] 为什么“Binder 单次调用可以安全传接近 1MB”是错误结论？**

因为约 1MB 是一个进程的接收映射区总大小，不是单笔事务的配额：精确值为 `1 MiB − 2×页大小`（4KiB 页设备约 1016KiB，16KiB 页设备约 992KiB），进程内所有在途事务——并发请求、回复、oneway、对象元数据——共享这块空间。`TransactionTooLargeException` 也无法区分是请求没有发出还是回复过大，只能按“操作可能部分完成”处理。

1. `BINDER_VM_SIZE = (1×1024×1024) − sysconf(_SC_PAGE_SIZE)×2` 是 libbinder 请求的映射长度。内核驱动另有 mmap 上限 `min(请求长度, 4 MiB)`，它是保护性上限，不代表默认分配 4 MB。
2. 请求 buffer 分配在目标进程的 `binder_alloc` 中。B 回复 A 时占用 A 的映射空间。多个中等事务并发也可能共同耗尽缓冲区。
3. 内核把一半映射作为异步事务初始预算（`free_async_space = buffer_size/2`），大量 oneway 事务也会触发分配失败。
4. Binder 事务应保持小。大数据改用文件描述符、分页或流式传递，不要把“单次不到 1 MB”当作安全线。

**Q3: Binder 相关的约 1 MiB、4 MiB、600 KiB、300 KiB 和 1000 ms 分别是什么限制或诊断值？**

这些数字分别描述不同路径和观察指标，不能相互替代。普通内核 Binder 的接收映射、RPC Binder 的包上限和用户态日志门槛不是同一个 buffer 限制。

1. **约 1 MiB**：AOSP libbinder 为每个进程请求的接收映射长度是 `1 MiB − 2×页大小`。实际 buffer 在 4 KiB 页设备约为 1016 KiB，在 16 KiB 页设备约为 992 KiB。
2. **4 MiB**：内核驱动对单次 Binder mmap 的保护上限是 `min(请求长度, 4 MiB)`。AOSP 进程请求约 1 MiB，因此该上限不表示默认分配 4 MiB。
3. **600 KiB**：RPC Binder 单条命令或回复包的协议上限。Android 15（V）及以前为 100 KiB，Android 16（Baklava）起提高到 600 KiB。它只作用于 socket/vsock 上的 RPC Binder，普通系统服务调用走内核 Binder，不受这个数字约束。Parcel 可用空间还要扣除 RPC 协议头与对象表。
4. **300 KiB**：`kLogTransactionsOverBytes` 是大事务告警线，不是硬上限。`BpBinder` 可记录 `Large outgoing transaction`，`BBinder` 可记录 `Large data transaction` 或 `Large reply transaction`。出现告警应检查是否把大块数据、无界列表或图片直接塞进 Parcel。
5. **1000 ms**：`BBinder` 慢事务日志观察服务端 `onTransact()` 执行区间，不含到达前的排队，因此不是端到端耗时。

**Q4: [learning] 普通 AIDL、Stable AIDL 与 HIDL 在接口稳定性和传输模型上有什么区别？**

AIDL 是接口描述语言，Stable AIDL 是带跨版本兼容契约的 AIDL 用法，HIDL 是 Treble 引入的上一代 HAL 接口语言与运行时。跨 system/vendor 边界时应使用 Stable AIDL，维护现有 HIDL HAL 时才沿用 HIDL。

1. **传输与进程**：普通 AIDL 与 Stable AIDL 跨进程调用时使用 `/dev/binder` 和 libbinder。AIDL 本身不强制必须拆成独立服务进程。HIDL 的服务化实现使用 `/dev/hwbinder` 和 libhwbinder。passthrough 实现可作为共享库加载进调用方进程。
2. **稳定性契约**：Stable AIDL 使用 `@VintfStability`、版本化接口和 VINTF 清单约束跨分区兼容。接口可以通过新增方法或枚举值演进，但不能任意改变既有定义。普通 AIDL 不承诺独立于系统镜像的跨版本兼容，不能拿它代替跨 system/vendor 的稳定接口。HIDL 使用 `@1.0`、`@1.1` 等版本继承规则维持兼容。
3. **语言后端**：Stable AIDL 支持 Java、NDK C++ 和 Rust 后端。HIDL 生成 C++ 与 Java 后端。
4. **平台演进**：Android 11 起支持用 AIDL 实现 HAL，并要求跨 framework/vendor 的 AIDL HAL 使用稳定契约。Android 17 语境下，新 HAL 通常优先 Stable AIDL，存量 HIDL 仍可能因厂商实现与兼容性继续存在。不能仅凭系统版本推断设备上的 HAL 已全部迁移。
5. **选型**：应用或框架内部跨进程接口可使用普通 AIDL。新写或跨 system/vendor 的 HAL 使用 Stable AIDL，并按要求声明稳定性及加入 VINTF 清单。存量 HIDL 实现按既有版本维护。

**Q5: AIDL 生成的 Stub、Proxy 与 Binder 事务如何连接客户端和服务端？**

一次 AIDL 调用按客户端封送、Binder 传输、服务端分发和结果返回完成：

1. **客户端 Proxy**：实现生成的接口，把参数写入 Parcel，再通过 `transact()` 提交事务。
2. **服务端 Stub**：接收 Binder 事务，检查接口描述符，按事务码分发，并把 Parcel 参数还原成接口方法调用。
3. **生成接口的胶水方法**：AIDL 接口通常扩展 `IInterface`。Stub 继承 Binder 并实现接口，提供 `asInterface()` 包装 Binder 引用，在 `onTransact()` 中分发事务。Proxy 通过 `asBinder()` 暴露 Binder 引用。`DESCRIPTOR` 标识双方约定的接口。
4. **同步和异步调用**：同步方法通常等待服务端执行并读取 reply。`oneway` 方法不等待业务返回值，适合无结果的异步通知，但仍受 Binder 排队、线程池和事务大小约束。
5. **生成代码版本**：部分旧版生成器输出 Default 辅助实现。生成类名和回退辅助方法随 AIDL 工具版本变化，应用应依赖接口契约，不要手改生成文件。
6. **类型与封送**：AIDL 常用原始类型、String、CharSequence、Parcelable、Binder 接口及受工具链约束的 List/Map。自定义 Parcelable 类型要按当前 AIDL 语言版本提供声明或生成器可识别的类型。参数方向决定数据传输方向，复杂或大型载荷要评估序列化成本。

**Q6: Android 为什么以 Binder 作为常用 IPC，而不是所有场景都用 socket 或共享内存？**

IPC 机制要按调用语义、数据量和同步责任选择。Binder 适合本地服务 RPC，socket 适合字节流与自定义协议，共享内存适合反复访问的大块数据。

1. **Binder**：适合 Android 设备上的本地服务调用，提供对象引用、调用方身份传递与 RPC 语义，但仍有序列化、线程调度和事务大小成本。
2. **Socket**：适合字节流协议、网络通信或自定义传输。应用要自行处理消息边界、身份认证和 RPC 语义。
3. **共享内存**：适合高频大数据交换，可避免反复传输完整载荷，但同步、对象生命周期与访问控制要由双方设计。

没有一种机制对所有 IPC 负载都最快。

**Q7: Android IPC 应怎样按层选型？为什么一条实际链路经常组合多种机制？**

IPC 按职责可分为接口语义、控制面传输、数据面传输和通知同步四层。数据形态、方向、频率、生命周期与权限边界决定每层选什么，因此一条链路常把几种机制组合使用。

1. **接口语义**：AIDL、Messenger、Intent、ContentProvider 描述调用方要做什么。
2. **控制面传输**：Binder、Unix 域套接字以及 socket/vsock 上的 Binder RPC 传递少量控制信息。
3. **数据面传输**：SharedMemory/mmap、CursorWindow、DMA-BUF、FMQ 承载持续或较大的数据。
4. **通知与唤醒**：eventfd、signal 等机制通知另一端有状态变化或数据可读。
5. **典型组合**：InputManager 通过 Binder 把 `InputChannel` fd 交给应用，后续输入事件与确认走匿名 `SOCK_SEQPACKET` 套接字。Binder 也可传递 `SharedMemory` fd，由双方映射并读写同一块区域。HAL 可用 AIDL/HIDL 创建 FMQ，让持续数据走共享内存环形队列。
6. **资源所有权**：Parcel 适合方法号、少量参数、令牌和 fd。图像、音频帧、模型权重或大型结果集通常只通过句柄或 fd 引用。协议还要明确 fd 由谁关闭、映射何时解除、DMA-BUF 栅栏完成前谁不能复用缓冲，以及 FMQ 一端死亡后如何重建。
7. **性能比较**：不要引用固定的“Binder 0.5 ms、Socket 0.1 ms”数字。IPC 延迟包含 client 编组、驱动传输、server 排队调度、业务执行和 reply 返回。数据量、CPU、线程池与 SELinux 都会改变结果。比较必须使用同一设备、相同负载与统计口径。

**Q8: [learning] oneway 调用到底保证了什么、没保证什么？BR_TRANSACTION_COMPLETE 代表服务端执行完成吗？**

oneway 只保证“调用方不等待业务回复”与“发往同一个 Binder 节点的异步事务按发送顺序逐个分发”。它不保证服务端已执行、不保证跨节点全局有序、也不保证不会失败。`BR_TRANSACTION_COMPLETE` 只表示驱动完成了本次提交——目标进程可能尚未被调度，事务可能仍在 `proc->todo` 或 `node->async_todo` 中排队。

1. **排队结构**：同一 Binder node 的异步事务串行执行。第一笔事务处理时，后续事务进入该 node 的 `async_todo`，当前 buffer 释放后才取下一笔。不同 node 的异步事务可以并行。
2. **优先级**：oneway 事务不继承调用方线程优先级，使用目标进程默认优先级。目标 node 自身配置的 `min_priority` 仍参与服务端执行优先级。
3. **失败面**：提交仍要在目标进程分配 buffer，异步空间不足会得到 `-ENOSPC` 或 `FAILED_TRANSACTION`。目标进程死亡或冻结也可能导致失败。服务端 oneway 方法的异常不会写回调用方，需要业务确认时应设计独立回调并带超时。
4. **协议设计**：A 必须先于 B 生效时，让 A、B 经过同一个串行执行点，或为消息增加序列号与状态校验。不能只因两者都是 oneway 就推断顺序。用 oneway 掩盖服务端过载只会把延迟转移到队列。

**Q9: [learning] oneway 事务在驱动里如何排队？异步空间紧张时调用方会看到什么？**

oneway 事务仍要在目标进程分配 buffer 并由 Binder 线程执行。同一 Binder node 串行、不同 node 并行。当目标进程剩余异步预算低于总映射的 10%，且当前发送进程占用超过 50 个异步 buffer 或总占用超过总映射的 1/4 时，该笔事务被标记 `oneway_spam_suspect`：发送线程收到 `BR_ONEWAY_SPAM_SUSPECT`，libbinder 打印发送侧调用栈。这是诊断信号，不做限流，事务本身通常仍成功。

1. **记账模型**：`free_async_space` 初始化为 `buffer_size/2`，同步和异步事务从同一棵空闲树分配。异步分配前检查并扣减预算，释放后归还。它不是专供 oneway 使用的一半物理内存池。
2. **治理方式**：触发 spam 检测时，目标进程异步预算已经很紧张。应在协议层合并可覆盖的状态更新、限制频率、为事件队列设置容量与丢弃策略，不能等驱动告警才处理。
3. **buffer 生命周期**：oneway buffer 不一定比同步事务短。没有 reply 触发释放，它要到服务端处理完成并释放 Parcel 后才归还。高频发送会同时占据目标进程的接收地址池。

**Q10: [learning] “Binder 线程池默认 15 个线程”该怎么准确理解？怎样判断服务端真的发生了线程池饥饿？**

15（`DEFAULT_MAX_BINDER_THREADS`）是 libbinder 经 `BINDER_SET_MAX_THREADS` 告诉驱动“最多可按需请求启动的 lazy 线程”上限。`startThreadPool()` 还会主动创建 1 个主线程池线程，显式 `joinThreadPool()` 与服务自设上限再叠加。因此“每个进程固定 15 或 16 条 Binder 线程”都会误导容量分析。饥饿判定要看组合证据，而不是数线程或单看一条日志。

1. 驱动在没有未兑现线程请求、`waiting_threads` 为空且已启动线程数低于 `max_threads` 时返回 `BR_SPAWN_LOOPER`，libbinder 才按需创建线程。线程创建后通常存活到进程结束。
2. AOSP Android 17 的 system_server 设置 `sMaxBinderThreads = 31`，另有 1 个主动线程，总量上界通常可到 32。应用主线程默认不加入 Binder 池。
3. 执行中的 Binder 线程数达到配置上限并持续超过 100 ms 时，libbinder 启发式日志可输出 `binder thread pool (N threads) starved for M ms`。这表示该进程长时间没有空闲工作线程，不证明驱动队列有积压，也不证明延迟完全由 CPU 忙导致。`blockUntilThreadAvailable()` 存在，但标准 `transact()` 路径不会调用它。
4. 常见根因包括持锁跨进程调用、无超时 I/O、数据库长事务、嵌套同步调用和 oneway 积压。只有多个请求可安全并行、共享资源有余量且轨迹显示等待空闲 worker 占主导时，增加线程才可能有效。若所有 worker 都在等同一把锁，加线程只会增加等待者。

**Q11: [learning] 大数据跨进程传输应该如何设计？writeBlob 的 16KiB 分界、SharedMemory.setProtect() 和 FMQ 各自的边界是什么？**

原则是 Binder 只传控制信息与句柄，持续数据走共享内存、文件描述符或专用队列。C++ `Parcel::writeBlob()` 以 16KiB 为分界：不超过 16KiB 直接内联写入 Parcel。超过且允许传 fd 时改走兼容 ashmem 区域并用 fd 传递。Java `SharedMemory.setProtect()` 只能移除权限不能加回，应按“写入 → 解除映射 → 降为只读 → 交给对端”的最小权限顺序使用。FMQ 是共享内存上的有界单向队列，单个队列只有一个写入方。双向协议要建两条方向相反的队列。

1. `writeBlob` 分流只作用于二进制块路径。普通字节数组和集合仍会被内联编组，因此不是所有大 `byte[]` 都自动走共享内存。
2. 共享内存不是端到端零拷贝。生产方写入、缺页与缓存同步、消费方读取复制仍可能发生。共享页也没有消息边界与顺序，需要自行定义版本、长度、状态和校验规则。
3. FMQ synchronized 队列是单读单写且不允许覆盖。Unsynchronized 队列可有多个读者，但新写入可覆盖旧数据，落后的读者会丢数据。FMQ 适合固定布局、高频、小单元数据流，不适合可变长复杂对象或需要逐条权限检查的协议。
4. socket/vsock 上的 Binder RPC（例如 Microdroid pVM 场景）不使用内核 Binder 的接收映射，因此不受“1 MB 接收映射”结论约束。

**Q12: 多进程应用的共享内存与消息传递各有什么取舍？**

选择取决于数据规模、更新频率、同步需求和安全边界：

1. **共享内存**：适合多个进程反复访问较大数据块，可避免每次调用都复制完整载荷。调用双方必须自行设计同步、所有权与生命周期，避免读写竞态和过期数据。
2. **消息传递**：适合边界清晰、数据较小的请求与事件，可把权限和调用契约集中到接口上，但会产生序列化、排队与传输开销。
3. **Android 常用组合**：Binder/AIDL 传递结构化请求与响应。大型二进制内容通常通过文件描述符、共享内存句柄或 URI 引用，不直接塞进 Binder Parcel。

**Q13: [learning] AIDL 的 in、out、inout 参数方向怎样影响传输成本，什么时候该避免 inout？**

参数方向决定对象在哪一侧编组和回传。`inout` 要双向传输同一对象，通常比单向参数有更高的编组成本，因此只在接口语义确实需要双向修改时使用。

1. `in`：客户端把对象发送给服务端。原语类型默认是 `in`。
2. `out`：客户端提供待填充对象，服务端写入后把结果回传。它不是“可选返回值”的通用替代方案。
3. `inout`：对象先发送到服务端，服务端修改后再回传。Stable AIDL 指南建议尽量避免，因为要承担来回封送成本。
4. **替代方式**：能拆成明确返回值或单向请求/响应接口时，优先选这种方式，让调用方知道哪些字段跨进程、在哪个方向更新。

方向标记只适用于需要标记方向的非原语参数。设计时同时检查 AIDL 版本、生成后类型与对象复制成本。

**Q14: [learning] 跨进程 Bundle 中的自定义 Parcelable 为什么会在读取时抛 ClassNotFoundException，怎样避免相关 Parcel 错误？**

Bundle 可以先保存尚未解包的 Parcel 数据，直到接收端首次读取 Parcelable 才执行反序列化。因此发送调用成功并不证明接收端类加载器能解析该对象。若自定义类不在接收端 classpath 或加载器不正确，异常会出现在读取位置。

1. **确认读写两端类型一致**：检查发送端放入 Bundle 的 Parcelable 实际类、接收进程依赖与类加载器。接收端应在读取前设置适当的 class loader。
2. **区分异常位置**：首次 `getParcelable` 才 `unparcel()` 时出现的 `ClassNotFoundException` 或 `BadParcelableException` 属于接收端解析问题，不一定是 Binder 事务发送失败。
3. **自写 Parcelable**：对象包含文件描述符时，`describeContents()` 必须返回 `CONTENTS_FILE_DESCRIPTOR`，并聚合嵌套对象的标志。`writeToParcel` 与 `CREATOR` 的字段读写顺序必须严格一致。
4. **定位方向**：记录发送端写入的键和值类型，并在接收端记录读取时使用的 class loader，先确认双方使用相同定义再追查 Parcel 格式。

**Q15: [learning] 冻结状态、扩展错误、AIDL trace 和 Perfetto Binder 事件分别能证明什么？**

这些观察源回答不同问题。驱动能力声明、线程状态快照、一次性错误详情和时间轨迹不能互相替代。

1. binderfs `features`：列出驱动编译或启用的能力，例如 `oneway_spam_detection`、`extended_error`、`freeze_notification`。它说明功能可用，不表示该功能当前发生。
2. **冻结状态**：`BINDER_GET_FROZEN_INFO` 返回 `sync_recv` 和 `async_recv` 位标志，不提供累计次数或耗时。`sync_recv == 3` 表示两个状态位都为 1，不是发生三次同步事务。`async_recv` 也不是单调计数。
3. **扩展错误**：`BINDER_GET_EXTENDED_ERROR` 返回线程级一次性信息（`id`、`command`、`param`），读取后立即重置。libbinder 只对 `ENOSPC` 提供专门解释。
4. **AIDL trace**：`ATRACE_TAG_AIDL` 可提供 `AIDL::cpp::<接口>::<方法>::server` 形式的方法时间片，不含参数内容。方法名依赖轨迹映射。只采集内核 Binder 事件时 `interface` 或 `method_name` 可能为空，映射缺失时切片名可退化为 `UNKNOWN_CODE_<n>`。
5. **Perfetto Binder 表**：`android_binder_txns` 可关联事务两端和同步类型，但没有 `dispatch_dur` 列。服务端开始延迟需结合 `server_ts − client_ts` 自行计算，并解释所选时间戳口径。
6. **内核 tracepoint**：可见事件包括 `binder_transaction`、`binder_transaction_received`、`binder_transaction_alloc_buf` 和 `binder_txn_latency_free`。不存在名为 `binder_reply` 或 `binder_freeze` 的 tracepoint，不要用虚构事件名标注区间。
7. **调试节点快照**：debugfs/binderfs 状态文件只反映读取时刻。两次读取之间完成的事务可能完全不在快照中。

**Q16: [learning] RecordedTransaction 能做什么？为什么不能当线上常驻监控？**

`RecordedTransaction` 是 libbinder 的事务录制能力，可保存接口名、事务码、flags、返回状态与请求/回复 Parcel 内容，适合受控环境下复现协议问题与离线检查。它同时受三个条件限制：libbinder 编译期定义 `BINDER_ENABLE_RECORDING`、使用内核 Binder、发起录制的调用方 UID 为 root。源码明确标记文件格式仍在开发、不稳定，录制内容可能包含令牌等敏感数据，序列化与写文件还会改变被测路径的时延。

1. 录制时间戳在服务端 `onTransact()` 返回后采集，不能当作事务开始时间。发送路径没有对称的客户端录制入口，不能把“两端各录一次”拼成端到端耗时。
2. 端到端时间应由 Perfetto Binder flow 分析。录制文件可能含敏感数据，应按敏感数据管理并在分析完成后清理。
3. `binder_calls_stats` 是 framework 按 UID、接口和方法聚合的统计，带抽样。它不是驱动队列监视器，也不是“更细统计模式”开关。

**Q17: [learning] 排查 Binder 问题有哪些实用工具？驱动调试节点在新内核上从哪里读？**

用户态统计、驱动态状态和服务注册状态要分别观察：

1. `dumpsys binder_calls`：按 UID 汇总调用 CPU 耗时。列格式为 cpu_time（微秒）、占比、recorded_call_count、call_count、包名/UID。单 UID 占比异常高可能表示 Binder 热点。`call_count` 远大于 `recorded_call_count` 表示统计受采样影响。末尾 Exceptions 段计数大于 0 表示远端调用抛过异常，可按异常类名追查。
2. **驱动调试节点（双路径）**：新内核通常经 binderfs 提供 `/dev/binderfs/binder_logs/`，旧内核常见 `/sys/kernel/debug/binder/`。节点包括 `state`、`stats`、`transactions`、`transaction_log` 和 `failed_transaction_log`。`proc/<pid>` 按进程列出各线程正在等待或处理的事务，可用于调查线程池是否耗尽。
3. **判读要点**：`failed_transaction_log` 中 `BR_DEAD_REPLY` 或 `BR_FAILED_RETURN` 密集出现，说明对端死亡或句柄失效。它是环形日志，只保留最近若干条。
4. **权限**：userdebug + root 才能读驱动节点（`su 0 cat`）。

**Q18: [learning] 怎么确认某个 Binder/AIDL 服务在设备上注册了？service call 能做什么？**

确认注册有两类入口：枚举类命令看全局注册表，service call 按接口描述直接发一次事务。常用手段如下：

1. **列服务**：`dumpsys -l` 或 `service list` 可列出当前注册的服务名，不会自动 dump 每个服务的详细内容。`service check <名>` 可检查指定名称是否注册。
2. **探活**：`service call <名> <事务码>` 手动发送一笔事务。事务码通常对应生成接口的方法编号，但编号与参数编码受接口版本影响，不应当作稳定 shell API。只有正确构造参数并选择无副作用的方法时，响应结果才适合作为探活证据。
3. **版本差异**：Android 11 及更新版本的 AIDL servicemanager 统一注册 Binder 服务，因此 AIDL HAL 也会出现在 `dumpsys -l` 中，服务名形如 `android.hardware.xxx.IXxx/default`。`lshal` 用于发现 HIDL 服务，不能用它代替 AIDL 服务列表。

**Q19: tombstone 里 "RefBase: object ... with strong count 1 deleted. Double owned?" 是什么错？**

这个 `RefBase` fatal 表示对象被显式析构时仍有强引用计数，日志中的 `strong count 1` 说明至少还有一个强引用。常见原因是手动 `delete` 了仍由 `sp<>` 持有的对象，或在 Java/native 包装层让显式销毁与 native 强引用生命周期失配。不能只凭文案认定 GC/finalize 是根因，必须从实际析构调用栈确认所有权冲突。

1. **定位**：tombstone 的 `Abort message:` 给出断言和对象地址。backtrace 指向触发显式析构的调用路径，沿其调用方检查对象所有权。
2. **相关断言**：把栈上对象交给 `sp` 会因引用计数无处存放而触发拦截。弱计数下溢、显式析构后使用弱引用也可能触发其他专门断言，应以实际 message 和栈为准。
3. **调试与修复**：竞态类问题可启用 libutils 引用计数调试构建后重现。修复时收敛所有权，让对象只由一种生命周期机制负责释放。

**Q20: 应用如何取得应用服务或系统服务的 Binder 接口？服务名由谁注册和查询？**

应用应通过公开组件或系统 API 获取接口。Binder 服务名的注册与查询由 ServiceManager 协调，普通应用不应直接依赖隐藏的 ServiceManager API。

1. **应用服务**：客户端调用 `bindService()`。服务的 `onBind()` 返回 `IBinder`，系统再通过 `ServiceConnection.onServiceConnected()` 交给客户端。AIDL 的 `Stub.asInterface()` 会按 Binder 是否本地选择本地实现或远端代理。
2. **系统服务**：应用通过公开管理类获取能力，例如用 `Context.getSystemService()` 获取对应 manager。Framework 内部再查询相应 Binder 服务名。具体服务是否公开由 Android API 与权限决定。
3. **服务发现**：服务进程向 ServiceManager 注册名称和 Binder 对象，客户端查询后取得 Binder 引用。ServiceManager 负责发现服务，后续业务事务由 Binder 驱动路由到目标进程。

**Q21: Binder 如何支持服务端回调客户端？回调会在哪个线程执行？**

客户端把回调接口作为 Binder 对象传给服务端。服务端保存该接口后，可以反向发起 Binder 事务，调用方向因此从原来的“客户端到服务端”变成“服务端到客户端”。

1. **远端回调**：通常由客户端 Binder 线程池中的线程执行，不保证回到主线程。需要更新 UI 时，应显式切换到主线程或指定执行器。
2. **同进程调用**：本地 Binder 可能直接调用实现，不经过驱动，也不一定切换线程。
3. **生命周期**：客户端死亡、注销竞态、并发和重入都需处理。回调接口本身不保证必达或业务顺序，必要时应设计确认与序列号。

**Q22: 不使用 AIDL 时，手写 Binder IPC 需要自行维护什么？**

AIDL 生成代理与分派样板代码。手写 Binder IPC 时，调用方和服务端必须独立遵守同一套事务契约。

1. **编码契约**：约定事务码、Parcel 字段顺序与类型、返回值和异常格式。
2. **演进契约**：维护接口版本、兼容规则和权限检查，避免一端升级后另一端误解数据。
3. **运行边界**：自行处理线程模型、阻塞、服务死亡和调用失败。AIDL 能减少重复编码，但不替代接口设计。
