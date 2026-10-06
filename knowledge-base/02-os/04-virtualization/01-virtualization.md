# 虚拟机与虚拟化

> 学习资料。主线：虚拟机监控程序的类型与信任边界、QEMU 的模拟与虚拟化方式、pKVM 的受保护内存模型、全虚拟化与半虚拟化（virtio）的取舍。Q2–Q3 按 kernel.org 与 source.android.com 官方文档口径（2026-10 检索），Q4 按 QEMU 官方文档撰写；Android 专属集成归 ../../01-android/08-platform-services/06-avf-virtualization.md。题序即文档结构。

**Q1: [learning] 第一类和第二类虚拟机监控程序在资源访问路径上有什么区别？**

第一类虚拟机监控程序直接运行在硬件之上并管理虚拟机资源；第二类运行在宿主操作系统之上，通过宿主系统访问设备、文件和部分资源。两类都向 Guest OS 提供虚拟硬件，但中间的管理层不同。

1. **第一类**：VMM 直接仲裁物理 CPU、内存和设备，常见于服务器虚拟化场景；具体性能取决于硬件支持和设备虚拟化实现。
2. **第二类**：宿主系统负责设备驱动与底层资源，Guest 的虚拟磁盘等资源可映射为宿主文件；安装和桌面集成较方便，但资源链路多一层宿主依赖。

不能仅凭类别断言性能或迁移能力；需要结合设备直通、镜像格式、宿主资源竞争和产品实现判断。

**Q2: [learning] pKVM 与普通 KVM 的本质差别在哪？为什么说它的信任模型把宿主内核也排除在外？**

pKVM（protected KVM）在 Linux KVM 上扩展了"受保护虚拟机"模式：普通 KVM 下宿主内核拥有 guest 内存的管理权，而 pKVM 在硬件虚拟化扩展（ARM 的 EL2/Stage-2 页表）层面强制 guest 内存只属于 guest，宿主内核即使被攻破也无法直接读取或改写 guest 内存——信任基从"hypervisor + 宿主内核"收窄到"hypervisor 本身"。代价是宿主对 protected guest 的管理能力受限：内存归属 guest 之后，宿主侧的内存回收、内容级调试与探测都拿不到数据。

1. **普通 KVM**：guest 是宿主内核管理的普通负载，宿主可按需访问其内存，适合可信环境下的资源复用。
2. **pKVM protected VM**：Stage-2 地址转换由 hypervisor 控制，guest 页面默认仅 guest 可访问；宿主与 guest 间的任何共享都是显式授权操作。
3. **工程意义**：适合承载"内容对宿主保密"的工作负载。Android 的 AVF（Android Virtualization Framework）以 pKVM 为标准 hypervisor、Crosvm 为 VMM、Microdroid 为最小 guest（Android 专属集成归 `../../01-android/08-platform-services/06-avf-virtualization.md`）。

**Q3: [learning] 全虚拟化与半虚拟化（virtio）的边界在哪？半虚拟化省掉的开销是什么？**

区别在 guest 是否知道自己运行在虚拟环境并主动配合：全虚拟化对 guest 完全透明，设备访问靠陷入模拟或硬件辅助处理；半虚拟化要求 guest 安装半虚拟化驱动，I/O 走前后端共享内存协议——virtio 是事实标准的前后端规范。

1. **全虚拟化 I/O 的开销**：guest 每次设备访问陷入 hypervisor，由软件模拟真实硬件的寄存器语义，一次磁盘读写可能引发多次退出（VM exit）。
2. **virtio 的做法**：guest 前端驱动（virtio-blk、virtio-net 等）把请求描述符写入共享环（virtqueue），hypervisor 侧后端直接消费；退出次数压缩到"整批请求"粒度，数据路径走共享内存而非寄存器模拟。
3. **取舍**：半虚拟化要求 guest 侧配合（驱动随宿主平台分发），换取显著更低的 I/O 虚拟化开销；云主机与嵌入式 hypervisor（含 Android 模拟器与 Crosvm）默认提供 virtio 系列设备。

**Q4: [done] QEMU 是什么？系统模拟、用户态模拟与 KVM 加速分别做什么？**

QEMU 是一个开源的机器模拟器和虚拟化工具，能模拟整台机器以运行 Guest OS，也能在不同 CPU 架构之间运行单个用户态程序。系统模拟时，QEMU 提供虚拟机的机器模型和设备；CPU 指令可以由 QEMU 的 TCG 翻译执行，也可以借助 KVM 等 hypervisor 让客体代码直接运行在宿主 CPU 上。

理解 QEMU 时要区分两种模拟范围，以及系统模拟中的 CPU 执行方式：

1. **系统模拟：**为 Guest OS 提供一台由 CPU、内存和设备组成的虚拟机器。它适合启动完整操作系统，也可用于和实际机器架构不同的客体。
2. **用户态模拟：**在宿主操作系统中运行一个为另一种 CPU 架构编译的程序，只模拟该程序需要的指令与用户态环境，不提供完整的客体机器或 Guest OS。
3. **系统模拟配合 KVM：**这不是第三种模拟范围，而是系统模拟的一种 CPU 执行方式。QEMU 仍负责虚拟机机器模型和设备，KVM 利用宿主硬件虚拟化能力执行客体 CPU 指令；在支持的构建与目标上，QEMU 也可使用 TCG 翻译执行客体指令，通常慢于硬件辅助路径。

因此，QEMU 不等同于 KVM：QEMU 提供模拟与虚拟机设备模型，KVM 是 Linux 内核提供的硬件虚拟化加速接口；具体可用的加速器取决于宿主操作系统和硬件。以上区分依据 QEMU 官方文档的 About QEMU、System Emulation 与 Virtualisation Accelerators 说明。