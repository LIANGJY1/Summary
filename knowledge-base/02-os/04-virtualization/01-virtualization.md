# 虚拟机与虚拟化

> 学习资料。主线：虚拟机监控程序的类型与信任边界、QEMU（Quick Emulator）的模拟与虚拟化方式、pKVM 的受保护内存模型、全虚拟化与半虚拟化（virtio）的概念与取舍、virtio 虚拟磁盘（virtio-blk）的形态与请求流转、virtio-pci 的 PCI 呈现链路。Q2 按 QEMU 官方文档，Q3 按 kernel.org 与 source.android.com 官方文档口径（2026-10 检索），Q4 按 QEMU 官方文档与本机 -nic model 清单撰写，Q5、Q6 按内核 5.15 UAPI（virtio_blk.h、virtio_ring.h）、本机 QEMU 6.2 设备清单与 source.android.com Microdroid 文档核对（2026-10），Q7 按内核 uapi 头（virtio_ids.h、virtio_pci.h、pci_ids.h 本地观察）与 virtio 规范 PCI 设备 ID 布局核对（2026-10）；Android 专属集成归 ../../01-android/08-platform-services/06-avf-virtualization.md。题序即文档结构。

**Q1: [learning] 第一类和第二类虚拟机监控程序在资源访问路径上有什么区别？**

第一类虚拟机监控程序直接运行在硬件之上并管理虚拟机资源；第二类运行在宿主操作系统之上，通过宿主系统访问设备、文件和部分资源。两类都向 Guest OS 提供虚拟硬件，但中间的管理层不同。

1. **第一类**：VMM 直接仲裁物理 CPU、内存和设备，常见于服务器虚拟化场景；具体性能取决于硬件支持和设备虚拟化实现。
2. **第二类**：宿主系统负责设备驱动与底层资源，Guest 的虚拟磁盘等资源可映射为宿主文件；安装和桌面集成较方便，但资源链路多一层宿主依赖。

不能仅凭类别断言性能或迁移能力；需要结合设备直通、镜像格式、宿主资源竞争和产品实现判断。



**Q2: [done] QEMU（Quick Emulator）是什么？系统模拟、用户态模拟与 KVM（Kernel-based Virtual Machine）加速分别做什么？**

QEMU 是一个开源的机器模拟器和虚拟化工具，能模拟整台机器以运行客体操作系统，也能在不同处理器架构之间运行单个用户态程序。系统模拟时，QEMU 提供虚拟机的机器模型和设备；客体处理器（CPU，Central Processing Unit）指令可以由 QEMU 的 TCG（Tiny Code Generator）翻译执行，也可以借助 KVM 等 hypervisor 让客体代码直接运行在宿主处理器上。

理解 QEMU 时要区分两种模拟范围，以及系统模拟中的 CPU 执行方式：

1. **系统模拟：**为客体操作系统提供一台由处理器、内存和设备组成的虚拟机器。它适合启动完整操作系统，也可用于和实际机器架构不同的客体。
2. **用户态模拟：**在宿主操作系统中运行一个为另一种处理器架构编译的程序，只模拟该程序需要的指令与用户态环境，不提供完整的客体机器或操作系统。
3. **系统模拟配合 KVM：**这不是第三种模拟范围，而是系统模拟的一种 CPU 执行方式。QEMU 仍负责虚拟机机器模型和设备，KVM 利用宿主硬件虚拟化能力执行客体 CPU 指令；在支持的构建与目标上，QEMU 也可使用 TCG 翻译执行客体指令，通常慢于硬件辅助路径。

因此，QEMU 不等同于 KVM：QEMU 提供模拟与虚拟机设备模型，KVM 是 Linux 内核提供的硬件虚拟化加速接口；具体可用的加速器取决于宿主操作系统和硬件。以上区分依据 QEMU 官方文档的 About QEMU、System Emulation 与 Virtualisation Accelerators 说明。

**Q3: [learning] pKVM 与普通 KVM 的本质差别在哪？为什么说它的信任模型把宿主内核也排除在外？**

pKVM（protected KVM）在 Linux KVM 上扩展了"受保护虚拟机"模式：普通 KVM 下宿主内核拥有 guest 内存的管理权，而 pKVM 在硬件虚拟化扩展（ARM 的 EL2/Stage-2 页表）层面强制 guest 内存只属于 guest，宿主内核即使被攻破也无法直接读取或改写 guest 内存——信任基从"hypervisor + 宿主内核"收窄到"hypervisor 本身"。代价是宿主对 protected guest 的管理能力受限：内存归属 guest 之后，宿主侧的内存回收、内容级调试与探测都拿不到数据。

1. **普通 KVM**：guest 是宿主内核管理的普通负载，宿主可按需访问其内存，适合可信环境下的资源复用。
2. **pKVM protected VM**：Stage-2 地址转换由 hypervisor 控制，guest 页面默认仅 guest 可访问；宿主与 guest 间的任何共享都是显式授权操作。
3. **工程意义**：适合承载"内容对宿主保密"的工作负载。Android 的 AVF（Android Virtualization Framework）以 pKVM 为标准 hypervisor、Crosvm 为 VMM、Microdroid 为最小 guest（Android 专属集成归 `../../01-android/08-platform-services/06-avf-virtualization.md`）。



**Q4: [learning] 全虚拟化与半虚拟化（virtio）分别是什么、怎么理解？半虚拟化省掉的开销是什么？**

两者按"guest 对虚拟环境是否自知"分界。全虚拟化把虚拟设备伪装成真实硬件——guest 不加修改，用真机驱动访问，每次访问被 hypervisor 截获后模拟寄存器语义，或由硬件虚拟化扩展加速。半虚拟化跟 guest 做协议约定——guest 明知自己在虚拟环境里，安装专用前端驱动，按约定格式把请求批量放进共享内存队列，由宿主侧后端直接消费，virtio（Virtual I/O）是事实标准。理解模型：全虚拟化靠"欺骗"，半虚拟化靠"合作"。欺骗换兼容，合作换效率。

两类手段的具体形态：

1. **全虚拟化——照抄真实芯片：**hypervisor 提供的"假硬件"按真实芯片设计，如模拟 Intel e1000 网卡（QEMU 内建型号）或 IDE 控制器，寄存器布局与中断行为同真机一致。价值在兼容：guest 用自带驱动零修改运行。代价在效率：真实硬件接口按单次访问设计，guest 每写一个寄存器都可能触发一次 VM exit 陷入 hypervisor，由软件模拟寄存器语义，一次磁盘读写会拆成多次陷入。
2. **半虚拟化——专为虚拟化设计接口：**不照抄任何真实芯片，virtio 按规范定义新接口：前端驱动把请求描述符写入共享环（virtqueue），通知后端批量消费，数据走共享内存。接口按批量、异步设计，控制路径开销从"每寄存器访问一次陷入"摊到"每批请求一次"。

边界两点会改变判断：

1. **配合是前提：**半虚拟化要求 guest 侧有前端驱动（virtio-blk、virtio-net 等）。没有对应驱动的系统只能退回全虚拟化设备——老系统、特殊系统仍需要模拟设备就是这个原因。
2. **CPU 侧另有路径：**现代平台的 CPU 虚拟化由硬件扩展（Intel VT-x、AMD-V）直接加速，guest 同样无感知，属于全虚拟化的硬件实现路径。"全虚拟化 vs 半虚拟化"的二分在实践中主要用于 I/O 设备：CPU 走硬件辅助、磁盘网卡走 virtio 的混合形态是常态。

选择规则：给不可修改的 guest（老系统、闭源系统）配设备，用全虚拟化。给可配驱动的 guest 求性能，用半虚拟化。现代平台默认后者，前者作兼容兜底。



**Q5: [learning] virtio 虚拟磁盘（virtio-blk）是什么？为什么说它是"协议约定出来的磁盘"，而不是模拟出来的硬盘？**

virtio 虚拟磁盘是按 virtio 规范实现的半虚拟化块设备：guest 侧由前端驱动把它呈现为一块普通块设备（Linux 下以 vd 命名，第一块是 `/dev/vda`），宿主侧由 VMM 的后端把对它的读写落到宿主文件或宿主块设备上。理解它的关键在于：这块"磁盘"不是对某种真实硬盘控制器的模拟，而是一份 guest 驱动与宿主后端共同实现的协议——盘的容量、缓存行为和命令集合都由规范定义、双方按特性位协商出来，并不存在一块被照抄寄存器语义的"真实盘"。

它的身份可以拆成三面：

1. **guest 侧形态：**内核的 virtio_blk 前端驱动把设备注册为标准块设备，文件系统、挂载等上层机制照常工作。盘名以 vd 开头，区别于 SATA/SCSI 盘的 sd。Android AVF 的 Microdroid 里，APEX 与 APK 就是以 `/dev/vdc1` 这样的虚拟块设备形态挂进 guest 的。
2. **宿主侧承载：**后端由 VMM 提供。QEMU 用 -device virtio-blk-pci 在 PCI 总线上挂一个 virtio-blk 设备，等价简写是 -drive file=disk.img,if=virtio（file 指定盘体镜像，if=virtio 指明把这块盘接到 virtio 总线）。盘体可以是宿主文件（raw、qcow2）或宿主块设备。非 PCI 机器上另有 virtio-blk-device 形态，协议相同、传输不同。Android AVF 的 VMM 是 Crosvm，Microdroid 的系统盘与 payload 盘都由它挂入。
3. **属性协商：**容量（以 512 字节扇区计）、每段最大尺寸、读写缓存模式、discard 支持等属性，由设备在配置空间（向 guest 暴露的属性区）里给出、按特性位协商（如 VIRTIO_BLK_F_FLUSH、VIRTIO_BLK_F_DISCARD），而不是模拟盘面的几何参数。

理解成"协议约定的磁盘"能直接解释它的几个行为边界：

1. **后端可替换：**宿主后端从镜像文件换成块设备，guest 毫无感知，因为它只面对协议。
2. **兼容包袱少：**模拟真实硬件才需要的兼容字段（柱面/磁头/扇区几何）在这里只是可选兼容项。
3. **代价在 guest：**guest 必须安装 virtio 前端驱动——半虚拟化"要求 guest 配合"落在磁盘设备上就是这一点。



**Q6: [learning] guest 对 virtio 虚拟磁盘的一次读写请求，是怎么经过 virtqueue 流转到宿主存储的？**

前端驱动把"命令头 + 数据缓冲区"组装成描述符链挂进 virtqueue——两端可直接读写的共享内存，由描述符表、available 环、used 环三段组成——后端取出请求、对宿主文件或块设备执行真实 I/O，完成后把状态写入 used 环并中断 guest。数据搬运全程走共享内存，不经过寄存器模拟。控制路径上只有一次 guest 到宿主的通知和一次宿主到 guest 的中断。

完整过程分四步：

1. **组装请求：**guest 块层把读写请求交给前端驱动。驱动填命令头——type 字段区分操作（VIRTIO_BLK_T_IN/T_OUT 为读/写，T_FLUSH 要求把宿主侧缓存落盘），sector 字段给出以 512 字节为单位的起始偏移——再把命令头与数据缓冲区挂成描述符链，用 NEXT 标志串联、WRITE 标志区分方向。
2. **挂队列并通知：**描述符链的序号写入 available 环表示"可处理"，随后通知后端（PCI 上是一次 doorbell 寄存器写）。
3. **后端执行：**VMM 的后端从 available 环取出请求，按命令头对宿主侧的镜像文件或块设备发起实际读写。
4. **完成回收：**后端把完成状态写入 used 环并注入中断。前端驱动在中断处理里取回结果、释放描述符，向上层完成请求。

两个边界会改变对行为与正确性的判断：

1. **缓存语义：**后端可以配置成 writeback——写入进入宿主侧缓存即应答完成。需要持久性保证时，guest 必须显式发 T_FLUSH，由后端把缓存刷到宿主存储。设备是否支持 flush、缓存模式能否运行中切换，都由特性位协商（VIRTIO_BLK_F_FLUSH、VIRTIO_BLK_F_CONFIG_WCE）。
2. **布局版本：**上述三段式环是 split virtqueue 布局。virtio 1.1 起新增 packed virtqueue，把三段合并成单环以减少遍历，Linux 5.15 的 UAPI 头已包含两种布局的定义。



**Q7: [learning] QEMU 把 virtio 磁盘"呈现"给虚拟机，这条从虚拟 PCI 总线到 /dev/vda 的链路怎么理解？**

virtio-pci 本身不是总线，而是 virtio 设备"乘坐" PCI 总线的传输层。真正的总线是 QEMU 机器模型模拟出来的虚拟 PCI 总线，与真实 PC 的 PCI 总线同构。QEMU 把 virtio-blk 做成一个标准 PCI 设备挂上去（厂商 ID 0x1AF4），guest 用与发现真实硬件完全相同的 PCI 枚举机制找到它。此后 guest 内核发生两级接力：virtio_pci 驱动认领 PCI 设备并把它注册到内核内部的 virtio 总线，virtio_blk 驱动按设备类型匹配 probe，块设备 /dev/vda 才出现。guest 全程不需要知道 QEMU 存在。

完整链路分五步：

1. **QEMU 侧构造：**-device virtio-blk-pci（或 -drive ...,if=virtio 简写）在机器模型的 PCI 总线上实例化一个 virtio-blk-pci 设备，盘体镜像由后端持有。QEMU 模拟的是设备，总线由机型提供——pc 机型对应 i440FX，q35 机型对应其 PCIe 控制器。
2. **guest PCI 枚举：**内核 PCI 子系统扫描总线，从设备配置空间读到厂商 ID 0x1AF4（内核头文件常量名为 REDHAT_QUMRANET，即 KVM 起源公司的注册 ID）与设备 ID——现代布局从 0x1040 起按设备类型编号，块设备为 0x1042。
3. **virtio_pci 认领：**CONFIG_VIRTIO_PCI 驱动按 ID 接管设备，从 BAR 里的能力区拿到各段地址：common config 放特性协商与队列配置，notify 区是 doorbell，ISR 区放中断状态，device config 放磁盘容量等属性（内核 uapi 头里对应能力类型常量 1 到 4）。
4. **virtio 总线匹配：**virtio_pci 在内核内部注册一个 virtio 设备（块设备的类型 ID 为 2），内核的 virtio 总线按类型把它匹配给声明服务该类型的 virtio_blk 驱动。probe 里分配 virtqueue、协商特性。
5. **块设备出现：**probe 完成后向块层注册 gendisk，/dev/vda 出现，此后读写走 virtqueue 数据通路。

理解这条链路还有三个要点：

1. **为什么借 PCI 呈现：**枚举、BAR 内存映射、MSI-X 中断都是 PCI 栈的既有机制，任何内核都自带，virtio 不必发明新的发现协议。代价是虚拟机必须同时模拟 PCI 控制器。
2. **传输可替换：**没有 PCI 的环境用 virtio-mmio——同一套 virtio 协议直接挂在内存映射总线上，QEMU 里对应非 PCI 机器的 virtio-blk-device。guest 换 virtio_mmio 传输驱动，块驱动与协议本身不变。
3. **两代布局并存：**现代 virtio-pci 用能力区（capability）描述各段位置，旧版用固定 I/O 端口布局。现代 guest 驱动兼容两者。

理解模型一句话：virtio-pci 解决"guest 怎么发现设备"，virtqueue 解决"数据怎么过去"。前者让宿主的一个镜像文件冒充成标准 PCI 硬件，后者让访问不必陷入逐寄存器模拟，两层拼合，磁盘才从宿主的一个文件变成 guest 里的一块盘。
