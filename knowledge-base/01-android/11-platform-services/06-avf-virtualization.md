# AVF 虚拟化

> 学习资料（文章模式沉淀）。边界：本文回答"AVF 组件拓扑、Microdroid 能力边界、pKVM 隔离与 pVM 性能结构"。源文档：android-internals-wiki §1.26（Android 17/ACK 6.18 语境），AVF 架构与 pKVM 安全模型已与 source.android.com 核对。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 17 的 AVF 实现里，VirtualizationService、virtmgr、crosvm、pKVM、pvmfw、Microdroid 分别运行在哪里、负责什么？**

它们分属不同进程与特权级：API 37 的宿主侧由多个独立进程组成，`VirtualizationService` 相关逻辑并不在 `system_server` 里。

1. **framework-virtualization（应用进程）**：`VirtualMachineManager`/`VirtualMachine` 等 `@SystemApi` 入口，经 Unix 域套接字上的 RpcBinder 与 virtmgr 通信；
2. **virtmgr（Rust 子进程）**：管理 AIDL 生命周期、镜像与文件描述符准备、启动并监控 crosvm；一个 virtmgr 可以管理多台 crosvm 子进程；
3. **VirtualizationServiceInternal（全局 Rust lazy Binder 服务）**：负责虚拟机上下文 ID（CID）、全局资源与统计，按需启动，与 virtmgr 不同进程、也不在 system_server；
4. **crosvm（每个运行中的 VM 一个进程）**：虚拟机监控器（VMM），通过 `/dev/kvm` 的 ioctl 创建并运行 VM，管理 vCPU 线程、virtio 设备和 VM 内存布局；
5. **pKVM（内核 EL2，来自 ACK 的 KVM）**：管理宿主与客户机的 Stage-2 地址转换权限、页面所有权、vCPU 切换和 pVM 保护；
6. **pvmfw（pVM 首段固件）**：验证初始镜像、维护实例身份、派生每台 VM 的机密；
7. **Microdroid（客户机 OS）**：验证启动、SELinux、Bionic、原生 payload 与基于 vsock 的 Binder RPC。

排查 AVF 问题时先确认组件落点：framework API 报错看应用与 virtmgr，VM 启动失败看 crosvm 与 pvmfw，内存隔离问题才到 pKVM 与内核层。

**Q2: Microdroid 是"小号完整 Android"吗？protected VM 里的 Microdroid 能直接用 GPU/NPU 加速通用 AI 推理吗？**

都不是。Microdroid 为原生 payload 提供的是基础设施：Bionic C 库、验证启动、SELinux、APEX 系统组件、日志与崩溃调试，以及基于 vsock 的 Binder RPC；它明确不提供 `system_server` 和 Zygote、图形 UI、HAL，也不提供 `android.*` Java framework API。启用 ART APEX 后能使用 `java.*` 核心 API，但不等于拥有常规 Android 应用运行环境。

推论：以下说法在 API 37 都没有依据——Microdroid 默认含完整 ART、SystemServer 和 Service Manager 服务集；protected 模式的 Microdroid 可以直接使用 virtio-gpu 或 NPU HAL 加速通用 AI 推理；它能承载完整工作资料、Launcher 或 SystemUI。payload 通常是 APK 内嵌的原生共享库，由 Microdroid payload launcher 执行。需要 UI、GPU 或设备直通时，应按具体客户机、crosvm 构建选项和产品安全策略单独评估自定义 VM，不能套用 Microdroid 的能力表。

**Q3: pKVM 靠什么阻止宿主 Android 读取 protected VM 的私有内存？这种保护覆盖什么、不承诺什么？**

pKVM 在宿主上下文中也启用 Stage-2 地址转换：宿主 Stage-2 使用恒等映射，EL2 维护每个物理页的所有者；创建 pVM 时宿主把页面 donate（捐赠）给客户机，这些页随即从宿主 Stage-2 的可访问映射中撤销——即使 crosvm 仍保留着建立 KVM memslot 的虚拟地址区间，宿主 CPU 和受宿主控制的设备也不能再读到这些页。

官方安全模型确认的覆盖范围：

1. **机密性**：pKVM 跟踪页面所有权，页面只有所有者显式 share 后才能被其他 pVM 映射，规则同样约束 CPU 与 DMA 访问；
2. **完整性**：pVM 之间不能未经同意修改对方内存、不能影响对方 CPU 状态；页面归还宿主前会被清理（撤销客户机映射并清写内容）；
3. **通信靠显式共享**：受保护客户机为 virtqueue 预留固定共享内存窗口，客户机在私有页与共享窗口之间做中转复制（bounce copy）——这也是 pVM I/O 额外延迟与尾延迟的来源。

不承诺的是可用性：KVM 有意把调度委托给宿主内核，恶意宿主可以选择永不调度客户机 vCPU，VMM 也能扣留内存和虚拟设备，宿主始终可以终止 crosvm 让整台 VM 停止。已 donate 的页不能被宿主换出或 KSM 合并，回收要靠客户机经 `relinquish` 或 balloon 主动归还。安全设计不能把"数据不被读取"写成"服务不会被中断"。

**Q4: pVM 内的任务变慢，为什么只看客户机内的 Perfetto 不够？vCPU 与 VM exit 的成本结构是什么？**

因为每个 vCPU 只是 crosvm 里的一个 POSIX 线程，客户机看到的慢可能来自三层，而后两层在客户机内完全不可见：客户机内线程没有被客户机调度器选中；对应 vCPU 线程在宿主上处于 runnable 却没获得 CPU；vCPU 因 MMIO、virtio 或中断等事件退出后在 crosvm 或宿主内核等待。

成本结构：

1. vCPU 线程调用 `KVM_RUN` 进入客户机，需要 VMM 处理时返回宿主用户空间；宿主调度器可以抢占它，也能用 CPU affinity、cpuset、uclamp 等常规 QoS 机制控制——客户机不能绕过宿主调度器拿到物理 CPU；
2. 一次 I/O 不等于一次完整 VM exit：virtio 控制面走 MMIO，数据面主要走共享 virtqueue，eventfd/epoll 通知、中断合并和队列深度都会改变退出与唤醒次数；
3. protected VM 的数据面还要叠加共享窗口 bounce copy 与缓存维护，影响吞吐与尾延迟。

做法：把客户机时间线与宿主 crosvm/vCPU 线程调度对齐，统计每单位业务数据的退出与唤醒次数。没有设备型号、频点、负载和分布数据时，"VM exit 5–10 μs"或"比 syscall 慢 3–10 倍"不是平台结论。
