**Q1: Binder 的整体架构由哪几部分组成？"一次拷贝"到底省在哪里？**

Binder 由用户态 libbinder、内核 Binder 驱动和 servicemanager 名称注册表三部分完成传输，AIDL 在其上提供接口契约；"一次拷贝"指驱动把事务数据从发送方用户空间直接复制到接收方 mmap 映射的缓冲区，省去了传统 IPC"用户空间→内核→用户空间"的两次拷贝。

1. **libbinder**（`ProcessState`/`IPCThreadState`）：管理接收事务的映射区和 Binder 线程池，封装事务；Java 层的 Binder 经 JNI 落到这里。
2. **内核驱动**：`/dev/binder`（框架）、`/dev/hwbinder`（旧 HAL）等独立设备节点维护各自的上下文；负责路由事务、管理缓冲区映射、请求用户态增减线程、唤醒目标线程。
3. **servicemanager**：Binder 的 context manager，服务进程按名字注册，调用方按名字查询到目标句柄后再发起事务。
4. **AIDL**：接口描述语言，编译期生成代理与 Stub，属于接口契约层，不是传输机制本身。

边界："一次拷贝"只描述事务数据的搬运。一次同步调用的端到端延迟还包含线程排队、上下文切换、权限与 SELinux 检查、目标服务执行和下游依赖，不能用"一次拷贝"推出调用一定快；接收方默认映射区约 1 MB 减两页，驱动侧动态增线程上限默认 15，这些常量不能直接换算成"该配几个线程"的建议。


**Q2: AIDL、Stable AIDL 以及 HIDL 之间有什么区别？**

AIDL 是接口描述语言本身，编译期生成代理与 Stub；Stable AIDL 是给 AIDL 加上"接口不破坏兼容"稳定性承诺的用法（`@VintfStability` 注解 + VINTF 清单冻结），Android 10 起用于 system/vendor 边界并成为新 HAL 的首选；HIDL 是 Android 8.0 Treble 为解耦 system/vendor 专门设计的上一代接口语言，Android 11 起进入弃用流程、新接口不再采用，存量实现仍被支持。

按维度对比：

1. **定位**：AIDL 是语言；Stable AIDL 是同一语言加版本化与兼容性契约；HIDL 是独立设计的另一套接口语言与运行时；
2. **传输与进程模型**：普通 AIDL 与 Stable AIDL 走 `/dev/binder` 与 libbinder，服务以 Binder 服务进程运行；HIDL 服务化形态走 `/dev/hwbinder` 与 libhwbinder，另有直通式（passthrough）以共享库加载进调用方进程，没有独立 HAL 进程；
3. **稳定性契约**：Stable AIDL 用 `@VintfStability` 声明可跨 system/vendor，接口与枚举只能增不能改，并进 VINTF 兼容矩阵；普通 AIDL 无此承诺，接口随平台演进可改，因此不能跨 vendor 分区使用；HIDL 用 `@1.0`、`@1.1` 式版本继承维持兼容；
4. **语言后端**：Stable AIDL 支持 Java、NDK C++ 与 Rust 后端；HIDL 生成 C++ 与 Java；
5. **现状（Android 17 语境）**：新接口一律 Stable AIDL；存量 HIDL HAL 仍会保留以兼容旧厂商镜像，不能仅凭系统版本假定全部 HAL 已迁移。

选型规则：应用或框架内部跨进程用普通 AIDL；跨 system/vendor 边界或新写 HAL 用 Stable AIDL（声明 `@VintfStability` 并进 VINTF 清单）；HIDL 只在维护存量厂商实现时接触。

