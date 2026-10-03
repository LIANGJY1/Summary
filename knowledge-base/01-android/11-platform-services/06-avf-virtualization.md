# AVF 虚拟化

> 学习资料（文章模式沉淀）。边界：本文回答 AVF 组件拓扑、Microdroid 的客户机能力、pKVM 的隔离承诺，以及 pVM 的性能观察方式。依据 Android 官方 AVF 架构、安全模型、Microdroid 文档和 Android 17 AOSP。具体组件和能力须以设备产品配置及对应源码分支为准。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 17 的 AVF 中，framework-virtualization、virtmgr、VirtualizationServiceInternal、crosvm、pKVM、pvmfw 和 Microdroid 分别负责什么？**

AVF 将应用 API、宿主服务、虚拟机监控器、内核隔离和客户机启动拆分到不同边界。不能把所有逻辑都归入 `system_server`。

1. `framework-virtualization`：宿主应用侧的框架 API，例如 `VirtualMachineManager` 与 `VirtualMachine`。它为有权限的应用提供管理虚拟机的入口，并与宿主服务通信。
2. `virtmgr`：Android 17 实现中的 Rust 管理进程，处理虚拟机生命周期、镜像和文件描述符准备，并启动、监控 VMM。一个管理进程可负责多台虚拟机。进程数量和通信细节以目标分支实现为准。
3. `VirtualizationService`：宿主 Android 中负责管理 pVM 生命周期的服务。它建立宿主与客户机的通信能力，并向获准的客户端提供受控接口。其 API 和具体内部进程拓扑会随版本实现变化。
4. `VirtualizationServiceInternal`：Android 17 AOSP 中供宿主内部组件使用的全局服务实现，负责上下文 ID（CID）、全局资源与统计等内部虚拟机管理职责。不要把它与面向应用的 `VirtualizationService` 接口或 `virtmgr` 进程混为同一个组件。
5. `crosvm`：以 Rust 编写的虚拟机监控器（VMM），分配虚拟机内存、创建 vCPU 线程并实现虚拟设备后端。它使用 KVM 接口运行客户机，并处理需要宿主用户空间参与的虚拟设备事件。
6. pKVM：运行在 Arm EL2 的 KVM hypervisor 扩展，限制宿主及其他虚拟机对受保护客户机内存的访问，并执行内存所有权管理。它不是另一个 Android 用户空间服务。
7. `pvmfw`：pVM 首先执行的固件，验证启动镜像并派生 pVM 实例专属秘密。AVF 支持的客户机并不限于 Microdroid，但其他客户机必须符合对应启动和签名要求。
8. Microdroid：可运行于 pVM 的轻量客户机操作系统，负责验证启动并为客户机 payload 提供精简运行环境。它不是宿主 Android 的一个进程或普通应用容器。

排查时先按故障边界定位：框架 API 与权限问题看客户端和宿主服务，虚拟设备或 vCPU 运行问题看 VMM 与宿主内核，启动镜像验证看 `pvmfw` 和客户机启动链，隔离问题再检查 pKVM、平台硬件与 DMA/IOMMU 支持。

**Q2: Microdroid 提供什么运行能力，protected VM 能否直接使用 GPU 或 NPU 做通用推理？**

Microdroid 是为 pVM payload 提供的精简 Android 用户空间，不等同于具备完整框架和设备服务的 Android 系统。AVF 文档列明它支持的能力包括：

1. 原生运行环境：提供 Bionic 和一部分 NDK API，可加载并执行 APK 中携带的原生二进制及共享库。
2. 安全启动与隔离：提供验证启动链和 SELinux 等客户机侧保护。
3. 开发诊断：提供文档列出的调试能力，例如 ADB、`logcat`、tombstone 和 GDB。具体可用项取决于镜像构建。
4. 组件扩展：支持加载 APEX。激活 ART APEX 后可以使用 `java.*` 核心 Java API，但这不代表提供 `android.*` Java framework API。
5. 客户机通信：支持基于 vsock 的 Binder RPC，也支持经受完整性检查的文件交换。

它不提供常规 Android 应用框架所依赖的完整系统服务、Zygote、图形 UI 或 Android Java framework。AVF 的标准 Microdroid 能力列表也没有承诺可直接访问 GPU、NPU 或对应设备 HAL，因此不能由存在虚拟化推断出 protected VM 已能加速通用 AI 推理。若需求依赖 UI、GPU、NPU 或设备直通，必须针对具体客户机、VMM 配置、驱动和产品安全策略验证。需要时可评估其他受支持的客户机系统。Microdroid payload 通常是 APK 中嵌入的原生代码，由客户机 payload launcher 执行。

**Q3: pKVM 如何保护 protected VM 的私有内存？它保证什么，又不保证什么？**

pKVM 跟踪物理页所有权，并在 hypervisor 控制的 Stage-2 页表中限制哪些执行环境可以映射各页。客户机页只有在所有者显式共享后，其他获准实体才可访问。

1. 保密性：宿主或其他 pVM 未获页所有者授权时，不能映射其私有页。该规则也覆盖代表虚拟机访问内存的 DMA 设备。实际保证依赖满足 pKVM 要求的平台硬件和 IOMMU 支持。
2. 完整性：pVM 未经同意不能修改彼此的内存，也不能影响彼此的 CPU 状态。宿主无法借由保留一个旧的虚拟地址映射绕过 hypervisor 执行的物理页所有权检查。
3. 显式共享：客户机需要与宿主交换数据时，必须通过受支持的共享机制。受保护客户机的 virtio 通信可以使用共享内存区域。客户机私有数据移入共享区域时可能需要复制和相应缓存维护，这会增加 I/O 成本。
4. 页面回收：pVM 可通过 `relinquish` 等机制主动把不再需要的页面交还宿主。销毁 pVM 并将页面交还宿主前，页面内容会被清理。pVM 私有页不能被宿主当作普通可换出的匿名页随意换出或合并。
5. 不保证可用性：宿主仍能影响客户机何时获得 CPU 和资源，也能扣留虚拟设备、抢占或终止客户机。因此 pKVM 的核心承诺是保密性与完整性，而不是保证 pVM 持续运行或服务必定可用。

还要区分内存隔离与所有客户机数据的完整性。虚拟磁盘、持久化状态和外部输入可能需要 dm-verity、AuthFS、加密或其他机制保护，不能仅凭 pKVM 推断所有存储内容都防篡改。

**Q4: 为什么只看 pVM 内 Perfetto 不足以解释性能问题，vCPU 与 VM exit 的成本来自哪里？**

客户机内 Perfetto 只能观察客户机获得 CPU 后的执行过程，无法独自解释宿主没有调度对应 vCPU 线程，或 VMM 正在宿主侧处理虚拟设备的时间。一次客户机内的慢操作可能涉及客户机、宿主调度器和 VMM 三层。

1. vCPU 调度：每个 vCPU 由 VMM 对应的宿主线程承载。线程进入客户机执行后，宿主调度器仍可抢占它。客户机调度器无法保证该线程获得物理 CPU。宿主侧 affinity、cpuset 或 uclamp 等配置也会影响实际运行时间。
2. VM exit 与处理：vCPU 因需要宿主处理而退出客户机后，可能返回 VMM 用户空间或宿主内核。MMIO 仿真、虚拟设备请求和中断处理都可能参与这条路径。不同事件不必然对应同样的退出次数或处理耗时。
3. virtio 通信：virtio 通常用共享 virtqueue 传递数据，通知和控制路径还可能涉及 MMIO、eventfd/epoll、中断及中断合并。实际唤醒和退出频率受设备实现、批处理策略和队列深度影响，不能把“一次 I/O”直接等同于“一次完整 VM exit”。
4. protected VM 数据路径：私有页与共享缓冲区之间的数据搬运及缓存维护会增加额外工作，可能影响吞吐和尾延迟。影响大小必须在目标设备和负载下测量。
5. 联合观察：将客户机时间线与宿主 crosvm/vCPU 线程调度、VMM 事件和虚拟设备处理时间对齐，并统计单位业务数据对应的退出、通知和唤醒次数。没有设备型号、频点、负载和延迟分布数据时，不应把固定的“VM exit 微秒数”或相对 syscall 的倍数写成平台结论。
