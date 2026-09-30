# Binder

> 学习资料（文章模式沉淀）。主线：Binder 架构组成与"一次拷贝"、AIDL/Stable AIDL/HIDL 契约差异、AIDL/Parcel 实战坑。等待链诊断与事务缓冲区深挖见 [13-MessageQueue锁竞争与Binder深化.md](./13-MessageQueue锁竞争与Binder深化.md)；2026-09-25 增补调试工具题（Q4–Q6，按 AOSP 近版源码镜像核对）。Q 序列即结构，供 atlas 同源直读。

**Q1: Binder 的整体架构由哪几部分组成？"一次拷贝"到底省在哪里？**

Binder 由用户态 libbinder、内核 Binder 驱动和 servicemanager 名称注册表三部分完成传输，AIDL 在其上提供接口契约；"一次拷贝"指驱动把事务数据从发送方用户空间直接复制到接收方 mmap 映射的缓冲区，省去了传统 IPC"用户空间→内核→用户空间"的两次拷贝。

1. **libbinder**（`ProcessState`/`IPCThreadState`）：管理接收事务的映射区和 Binder 线程池，封装事务；Java 层的 Binder 经 JNI 落到这里。
2. **内核驱动**：`/dev/binder`（框架）、`/dev/hwbinder`（旧 HAL）等独立设备节点维护各自的上下文；负责路由事务、管理缓冲区映射、请求用户态增减线程、唤醒目标线程。
3. **servicemanager**：Binder 的 context manager，服务进程按名字注册，调用方按名字查询到目标句柄后再发起事务。
4. **AIDL**：接口描述语言，编译期生成代理与 Stub，属于接口契约层，不是传输机制本身。

边界："一次拷贝"只描述事务数据的搬运。一次同步调用的端到端延迟还包含线程排队、上下文切换、权限与 SELinux 检查、目标服务执行和下游依赖，不能用"一次拷贝"推出调用一定快；接收方默认映射区约 1 MB 减两页；Binder 线程上限按进程配置而不同（默认 15 个按需 lazy 线程，`system_server` 显式设 31）——"每个进程固定 15/16 个线程"会误导容量分析，饥饿判定与容量锚点见 [13-MessageQueue锁竞争与Binder深化.md](./13-MessageQueue锁竞争与Binder深化.md) Q12。


**Q2: AIDL、Stable AIDL 以及 HIDL 之间有什么区别？**

AIDL 是接口描述语言本身，编译期生成代理与 Stub；Stable AIDL 是给 AIDL 加上"接口不破坏兼容"稳定性承诺的用法（`@VintfStability` 注解 + VINTF 清单冻结），Android 10 起用于 system/vendor 边界并成为新 HAL 的首选；HIDL 是 Android 8.0 Treble 为解耦 system/vendor 专门设计的上一代接口语言，Android 11 起进入弃用流程、新接口不再采用，存量实现仍被支持。

按维度对比：

1. **定位**：AIDL 是语言；Stable AIDL 是同一语言加版本化与兼容性契约；HIDL 是独立设计的另一套接口语言与运行时；
2. **传输与进程模型**：普通 AIDL 与 Stable AIDL 走 `/dev/binder` 与 libbinder，服务以 Binder 服务进程运行；HIDL 服务化形态走 `/dev/hwbinder` 与 libhwbinder，另有直通式（passthrough）以共享库加载进调用方进程，没有独立 HAL 进程；
3. **稳定性契约**：Stable AIDL 用 `@VintfStability` 声明可跨 system/vendor，接口与枚举只能增不能改，并进 VINTF 兼容矩阵；普通 AIDL 无此承诺，接口随平台演进可改，因此不能跨 vendor 分区使用；HIDL 用 `@1.0`、`@1.1` 式版本继承维持兼容；
4. **语言后端**：Stable AIDL 支持 Java、NDK C++ 与 Rust 后端；HIDL 生成 C++ 与 Java；
5. **现状（Android 17 语境）**：新接口一律 Stable AIDL；存量 HIDL HAL 仍会保留以兼容旧厂商镜像，不能仅凭系统版本假定全部 HAL 已迁移。

选型规则：应用或框架内部跨进程用普通 AIDL；跨 system/vendor 边界或新写 HAL 用 Stable AIDL（声明 `@VintfStability` 并进 VINTF 清单）；HIDL 只在维护存量厂商实现时接触。

**Q3: AIDL 接口"能用但慢"，或跨进程 Bundle 在读取处抛 ClassNotFoundException——参数方向与 Parcel 实战有哪些坑？**

三个实战要点：方向标签决定序列化成本；oneway 的保序只在"同一 Binder 节点"内成立；Bundle 的反序列化是延迟的，崩溃点在读端而非调用处。

1. **方向标签**：`in` 只把数据从客户端送到服务端（原语类型默认）；`out` 由服务端填充空对象回传；`inout` 双向序列化、成本约为 in 的两倍——官方 Stable AIDL 指南明确建议避免 inout，能拆成返回值或两次调用就不要用；
2. **oneway 保序的精确边界**：同一 Binder 节点的异步事务按发送顺序串行执行，不同节点之间没有顺序保证；`BR_TRANSACTION_COMPLETE` 只表示驱动受理完成，不代表服务端已执行——排队结构、优先级与失败面见 [13-MessageQueue锁竞争与Binder深化.md](./13-MessageQueue锁竞争与Binder深化.md) Q9/Q13；
3. **Bundle 延迟反序列化**：Bundle 收到时只保存原始数据，首次 `getParcelable` 才真正 `unparcel()`——自定义 Parcelable 的类不在接收方进程 classpath 时，崩溃发生在**读取处**而不是 Binder 调用处，表现为 `ClassNotFoundException`/`BadParcelableException`；排查先看发送方塞了什么；
4. **自写 Parcelable 纪律**：`describeContents()` 含文件描述符必须返回 `CONTENTS_FILE_DESCRIPTOR` 且嵌套对象要聚合；`writeToParcel` 与 `CREATOR` 的字段读写顺序必须严格一致。

**Q4: 排查 Binder 问题有哪些实用工具？驱动调试节点在新内核上从哪里读？**

三个层次：用户态统计用 `dumpsys binder_calls`，驱动态状态用 binder 调试节点，单个服务的在位确认用 `dumpsys -l`/`service list`（见 Q5）。

1. **dumpsys binder_calls**：按 UID 汇总调用 CPU 耗时，列格式为 cpu_time（微秒）| 占比 | recorded_call_count | call_count | 包名/UID——单 UID 占比异常高即 Binder 热点；call_count 远大于 recorded_call_count 说明被采样截断；末尾 Exceptions 段计数大于 0 表示远端调用抛过异常，按类名追；
2. **驱动调试节点（双路径）**：新内核经 binderfs 挂在 `/dev/binderfs/binder_logs/`（旧路径 `/sys/kernel/debug/binder/`），节点含 state、stats、transactions、transaction_log、failed_transaction_log 等，另有 `proc/<pid>` 按进程列出各线程正等待/处理的事务——线程池耗尽的快速取证就是读它；
3. **判读要点**：failed_transaction_log 里 BR_DEAD_REPLY/BR_FAILED_RETURN 密集说明对端死亡或句柄失效（环形日志，只保留最近若干条）；
4. **权限**：userdebug + root 才能读驱动节点（`su 0 cat`）。

**Q5: 怎么确认某个 Binder/AIDL 服务在设备上注册了？`service call` 能做什么？**

1. **列服务**：`dumpsys -l` 只列服务名不 dump 内容；`service check <名>` 返回在/不在；
2. **探活**：`service call <名> <事务码>` 手动发一笔事务（事务码对应 AIDL 方法在接口里的声明序号），可确认服务存活且响应——参数构造要谨慎；
3. **版本差异**：Android 11+ 的 AIDL servicemanager 统一注册所有 Binder 服务，因此 AIDL HAL 也出现在 `dumpsys -l` 里（名字形如 `android.hardware.xxx.IXxx/default`）——与只列 HIDL 的 lshal 区分（见 [09-HAL.md](./09-HAL.md) Q2）。

**Q6: tombstone 里 "RefBase: object ... with strong count 1 deleted. Double owned?" 是什么错？**

RefBase（libutils/Binder 原生对象的基类）在引用计数被破坏时直接 abort："Double owned?" 的典型成因是同一对象被 `sp<>` 智能指针与手动 delete（或两套所有权机制）同时管理，强计数归零析构后又有释放动作到达——Java 侧 GC/finalize 与 native `sp` 释放的竞态是常见来源。

1. **定位**：tombstone 的 `Abort message:` 行给出断言文案与对象地址，backtrace 指向出错的 decStrong/incStrong 调用方；
2. **相关形态**：把栈上对象交给 `sp` 会触发专门拦截（引用计数无处存放）；弱计数下溢、显式析构后使用弱引用也各有专门断言；
3. **调试与修复**：竞态类问题可开 libutils 的引用计数调试编译重跑；修复思路是收敛所有权——一个对象只归一种所有权机制管理。

**Q7: AIDL 生成的 Stub、Proxy 与 Binder 事务如何连接客户端和服务端？**

生成的 Stub 是服务端 Binder 入口，负责检查接口描述符、分发事务码并把 Parcel 参数还原为接口方法调用；Proxy 是客户端生成的接口实现，把方法参数写入 Parcel，再通过 `transact()` 发送事务。AIDL 生成的接口描述符用于验证双方约定的是同一接口。

典型生成接口扩展 `IInterface`，Stub 继承 Binder 并实现接口，提供 `asInterface()` 把 Binder 引用包装成接口，并在 `onTransact()` 中按事务码分发；Proxy 负责跨进程封送参数。`DESCRIPTOR` 标识接口，`asBinder()` 暴露 Binder 引用。部分旧版生成器还会输出 Default 辅助实现；生成类名和回退辅助方法随 AIDL 工具版本变化，应用应依赖接口契约而非手改生成文件。

同步方法通常等待服务端执行并读取 reply；声明为 `oneway` 的方法不等待返回值，适合无结果的异步通知，但仍受 Binder 排队、线程池和事务大小约束。AIDL 支持的类型与生成模板随语言后端和工具链变化，需按项目 `.aidl` 编译器版本核对，不能把某个生成文件的内部命名当作稳定 API。

AIDL 接口常用原始类型、String、CharSequence、Parcelable 类型、Binder 接口，以及受工具链约束的 List/Map 等容器；自定义 Parcelable 类型通常还要提供对应 AIDL 声明或按当前 AIDL 语言版本可识别。参数方向决定对象传输方向，复杂或大型载荷应谨慎评估序列化成本。

**Q8: Android 进程与线程有什么区别，同一应用为什么会使用多个进程？**

进程拥有独立的虚拟地址空间和运行时状态；线程是进程内执行流，共享进程内存与资源，但各自有调用栈和调度状态。同一应用的组件默认运行在应用主进程，需要时可通过 Manifest 的 `android:process` 将组件放到另一进程。

多进程可隔离特定服务的崩溃或内存压力，也可承载有独立进程约束的组件；代价是进程内单例、静态字段和对象缓存不共享，组件间通信要经过 IPC，内存占用也会增加。进程隔离不是把主线程工作自动变成后台任务。

以冒号开头的进程名（如 `:worker`）表示应用私有的进程名后缀，实际名称会以应用包名为前缀；显式完整名称可能允许不同应用在共享 UID 等条件下请求同一命名进程，具体可用性还受组件与平台权限规则约束。不要仅凭进程名推断线程、隔离权限或执行优先级。

**Q9: 多进程应用的共享内存与消息传递各有什么取舍？**

共享内存适合多个进程反复访问较大数据块，可避免每次调用都复制完整载荷，但必须自行设计同步、所有权与生命周期，避免读写竞态和过期数据。消息传递适合边界清晰、数据较小的请求与事件，能把权限与调用契约集中在接口上，但会有序列化、排队和传输开销。

Android 常用 Binder/AIDL 传递结构化请求与响应；大型二进制内容通常传递文件描述符、共享内存句柄或 URI 等引用，而不是塞进 Binder Parcel。选型看数据规模、更新频率、同步需求和安全边界，不能只按“快”选共享内存。

**Q10: Android 为什么以 Binder 作为常用 IPC，而不是所有场景都用 socket 或共享内存？**

Binder 把跨进程调用包装成轻量 RPC：调用方通过强类型接口发起方法调用，驱动负责把事务送到目标进程并管理 Binder 对象引用，服务端在线程池处理请求；内核还能把调用方身份等信息带到服务端进行权限判断。AIDL 再把接口声明与参数编解码代码生成出来，减少手写协议和路由代码。

Socket 更适合字节流协议、网络通信或自定义传输，应用必须自行处理消息边界、身份认证和 RPC 语义；共享内存适合高频大数据交换，但同步、对象生命周期与访问控制要由双方设计。Binder 面向 Android 设备本地服务调用更方便，但仍有序列化、线程调度与事务大小成本，不能推导为所有 IPC 负载下都最快。
