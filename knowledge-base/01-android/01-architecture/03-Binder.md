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

