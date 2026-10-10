# 虚拟机与虚拟化

> 学习资料。主线：虚拟机监控程序两类的资源访问路径 → QEMU 的模拟范围与 CPU 执行方式 → pKVM 的受保护内存模型与代价 → 全虚拟化与半虚拟化的取舍 → virtio-blk 的协议本质、两侧形态与属性协商 → virtqueue 的请求流转、缓存语义与布局版本 → virtio-pci 的发现链路与 virtio-mmio 替代。依据：QEMU 官方文档、kernel.org 与 source.android.com（pKVM/AVF）、内核 5.15 UAPI（virtio_blk.h、virtio_ring.h、virtio_ids.h、virtio_pci.h）、virtio 规范与 QEMU 6.2 设备清单（2026-10 核对）。Android 专属集成（AVF/Crosvm/Microdroid）归 ../../01-android/08-platform-services/06-avf-virtualization.md。题序即文档结构。

**Q1: [learning] 第一类和第二类虚拟机监控程序有什么区别？**

第一类虚拟机监控程序（VMM）直接运行在硬件之上，自己仲裁 CPU、内存和设备。第二类运行在宿主操作系统之内，通过宿主访问设备与文件。两者都向 Guest OS 提供虚拟硬件，差别在 Guest 与真实资源之间隔了几层。

1. **第一类：**VMM 直接管理物理资源，Guest 的虚拟磁盘等设备由 VMM 分配和模拟，不经过另一套操作系统，常见于服务器虚拟化场景。
2. **第二类：**宿主操作系统掌握真实设备驱动，VMM 借用宿主能力运行，Guest 的虚拟磁盘可映射为宿主上的文件，安装和桌面集成更方便。
3. **判断边界：**不能仅凭类别断言性能或迁移能力，实际表现取决于硬件虚拟化支持、设备直通、镜像格式和宿主资源竞争，要结合具体实现判断。

**Q2: [done] QEMU（Quick Emulator）是什么，系统模拟和用户态模拟有什么区别？**

QEMU 是开源的机器模拟器与虚拟化工具。系统模拟提供一台由处理器、内存和设备组成的完整虚拟机器，用来启动客体操作系统。用户态模拟只在宿主上运行一个为其他架构编译的程序，只模拟该程序需要的指令，不提供完整的客体机器。两者对应不同的命令行程序：系统模拟是 qemu-system-<架构>，用户态模拟是 qemu-<架构>。

1. **系统模拟：**面向完整操作系统，处理器、内存和设备都由 QEMU 的机器模型提供，客体可以与宿主架构不同。例如 `qemu-system-x86_64 -kernel bzImage -initrd initramfs.cpio.gz`，直接启动一套内核加 initramfs。
2. **用户态模拟：**面向单个程序，把客体指令翻译成宿主指令执行，程序看到模拟的用户态环境，但没有客体内核。例如 `qemu-aarch64 ./hello`，在 x86 宿主上运行为 AArch64 编译的静态链接程序。
3. **边界：**用户态模拟不需要客体系统镜像，也不能启动 Guest OS。要运行完整操作系统必须用系统模拟。

**Q3: [learning] QEMU 与 KVM（Kernel-based Virtual Machine）是什么关系，TCG 与硬件加速分别做什么？**

QEMU 不等同于 KVM。QEMU 提供机器模型和设备模拟，并决定客体 CPU 指令如何执行。KVM 是 Linux 内核提供的硬件虚拟化加速接口。系统模拟下，客体指令可以走 TCG（Tiny Code Generator）翻译执行，也可以走 KVM 直接在宿主处理器上运行。

1. **TCG 翻译执行：**QEMU 把客体指令动态翻译成宿主指令，不依赖硬件虚拟化扩展，可跨架构，通常慢于硬件辅助路径。
2. **KVM 硬件辅助：**利用处理器虚拟化扩展直接执行客体代码，QEMU 仍负责虚拟机的设备模型与 I/O 模拟。
3. **边界：**能否使用 KVM 取决于宿主操作系统、处理器和 QEMU 构建。跨架构客体只能使用 TCG。

**Q4: [learning] pKVM（protected KVM）与普通 KVM 的本质差别是什么，为什么说信任模型把宿主内核排除在外？**

普通 KVM 下 guest 内存由宿主内核管理，宿主可以访问。pKVM 借助 ARM 的 EL2 与 Stage-2 页表强制 guest 内存只属于 guest，宿主内核即使被攻破也无法读写。信任基从“hypervisor 加宿主内核”收窄到 hypervisor 本身。

1. **普通 KVM：**guest 是宿主内核管理的普通负载，内存归属对宿主透明，适合可信环境下的资源复用。
2. **pKVM protected VM：**Stage-2 地址转换由 hypervisor 控制，guest 页面默认仅 guest 可访问，宿主与 guest 的任何共享都是显式授权操作。
3. **信任模型的变化：**被排除的是宿主而非 guest——安全边界从“内核可信”改为“只信 hypervisor”，因此能承载内容对宿主也保密的工作负载。

**Q5: [learning] pKVM 的代价是什么，适合承载什么负载？**

代价在宿主侧：内存划归 guest 后，宿主的内存回收、内容级调试与探测都拿不到数据，对 protected guest 的管理能力受限。适合的是内容必须对宿主保密的负载。Android 的 AVF（Android Virtualization Framework）以 pKVM 为标准 hypervisor、Crosvm 为 VMM、Microdroid 为最小 guest，正是用它保护这类场景。

1. **管理受限：**宿主不能随意回收或检查 protected guest 的页面，调试与诊断手段相应减少。
2. **适用负载：**密钥处理、机密计算等需要防止宿主窥探的场景。
3. **Android 集成：**AVF 的整体架构与接入方式归 Android 虚拟化专题，这里只说明机制归属。

**Q6: [learning] 全虚拟化与半虚拟化分别是什么，理解模型上有什么区别？**

按 guest 是否“自知在虚拟环境中”分界。全虚拟化把虚拟设备伪装成真实硬件，guest 不加修改、用真机驱动访问。半虚拟化与 guest 做协议约定，guest 安装专用前端驱动，按约定格式与后端协作。理解模型：全虚拟化靠欺骗换兼容，半虚拟化靠合作换效率。

1. **全虚拟化：**hypervisor 按真实芯片设计“假硬件”，如模拟 Intel e1000 网卡，寄存器布局与中断行为同真机一致，guest 零修改运行。
2. **半虚拟化：**不照抄任何真实芯片，virtio（Virtual I/O）按规范定义新接口，前端驱动与宿主后端按协议交换数据。
3. **分界的实践范围：**CPU 虚拟化已由硬件扩展（Intel VT-x、AMD-V）直接加速，guest 同样无感知。这一二分主要用于 I/O 设备，混合形态是常态。

**Q7: [learning] 半虚拟化省掉了什么开销，为什么效率更高？**

省掉的是“每次设备访问都陷入 hypervisor 模拟寄存器”的开销。全虚拟化的接口按单次访问设计，guest 每写一个寄存器都可能触发一次 VM exit。virtio 把请求批量放进共享内存队列，一次通知处理一批，控制路径开销从“每寄存器一次陷入”摊到“每批请求一次”。

1. **全虚拟化的开销来源：**一次磁盘读写会拆成多次陷入，由软件逐个模拟寄存器语义。
2. **半虚拟化的批量设计：**virtqueue 是共享内存环，请求以描述符链批量入队，数据搬运不经过寄存器模拟。
3. **前提：**省开销以 guest 安装前端驱动为条件。没有驱动的系统只能退回全虚拟化设备。

**Q8: [learning] 半虚拟化对 guest 有什么要求，设备选型怎么判断？**

半虚拟化要求 guest 内安装对应前端驱动（virtio-blk、virtio-net 等），否则设备不可用。选型规则：guest 不可修改或缺少驱动时用全虚拟化兜底，可配驱动且追求性能时用半虚拟化。

1. **驱动是前提：**老系统、闭源系统常缺少 virtio 前端驱动，只能使用模拟设备，这是全虚拟化设备仍然存在的原因。
2. **选型规则：**先看 guest 能否安装驱动，再看是否需要性能。现代平台默认 virtio，模拟设备作兼容兜底。

**Q9: [learning] virtio 虚拟磁盘（virtio-blk）是什么，为什么说它是协议约定出来的磁盘？**

virtio-blk 是按 virtio 规范实现的半虚拟化块设备：guest 侧前端驱动把它呈现为普通块设备（Linux 下第一块是 `/dev/vda`），宿主侧后端把读写落到宿主文件或块设备。它不是对某种真实硬盘控制器的模拟——容量、缓存行为和命令集合都由规范定义、双方按特性位协商，不存在被照抄寄存器语义的“真实盘”。

1. **是什么：**guest 看到一块标准块设备，文件系统与挂载照常工作。宿主侧由 VMM 后端承接真实 I/O。
2. **为什么说“协议约定”：**没有照抄任何真实芯片，盘的容量、命令集与缓存行为按规范和特性位协商，磁盘几何只是可选兼容项。
3. **行为边界：**宿主后端从镜像文件换成块设备，guest 毫无感知。代价是 guest 必须安装 virtio 前端驱动。

**Q10: [learning] virtio 磁盘在 guest 侧和宿主侧分别是什么形态？**

guest 侧，virtio_blk 前端驱动把设备注册为标准块设备，盘名以 vd 开头，区别于 SATA/SCSI 盘的 sd。宿主侧，后端由 VMM 提供：QEMU 用 `-device virtio-blk-pci` 在 PCI 总线上挂设备，等价简写是 `-drive file=disk.img,if=virtio`，盘体可以是宿主文件（raw、qcow2）或宿主块设备。

1. **guest 侧：**virtio_blk 驱动注册块设备，上层文件系统与挂载机制无感知。Microdroid 的 APEX 与 APK 就以 `/dev/vdc1` 这类虚拟块设备挂入 guest。
2. **宿主侧：**QEMU 后端持有盘体镜像。Android AVF 的 VMM 是 Crosvm，系统盘与 payload 盘由它挂入。
3. **非 PCI 形态：**非 PCI 机器上用 virtio-blk-device，协议相同、传输不同。

**Q11: [learning] virtio 磁盘的容量、缓存等属性由谁决定？**

由设备在配置空间给出、双方按特性位协商，不是模拟盘面参数。容量以 512 字节扇区计。flush、discard、缓存模式等能力对应特性位，如 VIRTIO_BLK_F_FLUSH、VIRTIO_BLK_F_DISCARD、VIRTIO_BLK_F_CONFIG_WCE。

1. **配置空间：**virtio 设备向 guest 暴露一块属性区，容量等静态属性放在其中。
2. **特性位协商：**能力支持与否由特性位逐位确认，guest 只使用双方都支持的子集。
3. **与模拟盘的差别：**不需要柱面、磁头、扇区几何这类兼容字段，属性集由规范定义。

**Q12: [learning] virtqueue 是什么，guest 的一次读写请求怎么流转到宿主存储？**

virtqueue 是两端共享的内存队列，由描述符表、available 环、used 环三段组成。前端驱动把命令头与数据缓冲区挂成描述符链入队并通知后端，后端取出请求对宿主文件或块设备执行真实 I/O，完成后把状态写入 used 环并中断 guest。数据搬运全程走共享内存，不经过寄存器模拟。

1. **组装请求：**驱动填命令头——type 字段区分读写（VIRTIO_BLK_T_IN/T_OUT），sector 字段给出以 512 字节为单位的起始偏移——再把命令头与数据缓冲区用 NEXT 标志串成描述符链。
2. **入队并通知：**描述符链序号写入 available 环表示可处理，随后通知后端（PCI 上是一次 doorbell 寄存器写）。
3. **后端执行：**后端从 available 环取出请求，按命令头对宿主镜像文件或块设备发起实际读写。
4. **完成回收：**后端把完成状态写入 used 环并注入中断。前端驱动取回结果、释放描述符，向上层完成请求。

**Q13: [learning] virtio 的缓存语义由什么决定，T_FLUSH 起什么作用？**

后端可配置为 writeback——写入进入宿主侧缓存即应答完成，此时应答不等于数据已落宿主存储。需要持久性保证时，guest 必须显式发送 T_FLUSH，由后端把缓存刷到宿主存储。设备是否支持 flush、缓存模式能否运行中切换，都由特性位协商（VIRTIO_BLK_F_FLUSH、VIRTIO_BLK_F_CONFIG_WCE）。

1. **writeback 的后果：**宿主崩溃可能丢失已经应答的写入。
2. **T_FLUSH 的作用：**guest 显式请求落盘，后端把宿主缓存刷到存储后才报告完成。
3. **能力来源：**是否支持 flush 与缓存模式切换由特性位协商，不能假设默认行为。

**Q14: [learning] split virtqueue 和 packed virtqueue 有什么区别？**

两者是 virtqueue 的两种布局。split 是描述符表、available 环、used 环各自独立的三段式。virtio 1.1 起新增 packed，把三段合并成单环，描述符与完成状态同环存放，减少遍历开销。双方按特性位协商使用哪一种，Linux 5.15 的 UAPI 头已包含两种布局的定义。

1. **split：**三段各自独立，驱动与后端通过环下标间接引用描述符。
2. **packed：**单环结构，减少缓存遍历，是 virtio 1.1 引入的替代布局。
3. **选择方式：**由特性位协商，同一驱动可兼容两种布局。

**Q15: [learning] virtio-pci 是什么，guest 怎么通过它发现这块磁盘？**

virtio-pci 不是总线，而是 virtio 设备“乘坐”PCI 总线的传输层。QEMU 在机器模型的虚拟 PCI 总线上把 virtio-blk 做成标准 PCI 设备，guest 用与发现真实硬件完全相同的 PCI 枚举机制找到它，再经两级驱动匹配出现 `/dev/vda`。

1. **QEMU 侧构造：**`-device virtio-blk-pci`（或 `-drive ...,if=virtio` 简写）在 PCI 总线上实例化设备，盘体镜像由后端持有。总线由机型提供——pc 机型对应 i440FX，q35 机型对应其 PCIe 控制器。
2. **guest PCI 枚举：**内核从设备配置空间读到厂商 ID 0x1AF4（内核常量 REDHAT_QUMRANET）与设备 ID——现代布局从 0x1040 起按设备类型编号，块设备为 0x1042。
3. **virtio_pci 认领：**驱动从 BAR 的能力区拿到各段地址：common config 放特性协商与队列配置，notify 区是 doorbell，ISR 区放中断状态，device config 放磁盘容量。
4. **virtio 总线匹配：**virtio_pci 注册一个 virtio 设备（块设备类型 ID 为 2），virtio 总线按类型匹配给 virtio_blk 驱动。probe 里分配 virtqueue、协商特性。
5. **块设备出现：**probe 完成向块层注册 gendisk，`/dev/vda` 出现，此后读写走 virtqueue 数据通路。

**Q16: [learning] 为什么 virtio 借 PCI 总线呈现，没有 PCI 的环境用什么？**

借 PCI 是为了复用既有发现机制：枚举、BAR 内存映射、MSI-X 中断都是 PCI 栈自带的，virtio 不必发明新的设备发现协议。代价是虚拟机必须同时模拟 PCI 控制器。没有 PCI 的环境用 virtio-mmio——同一套协议直接挂在内存映射总线上，guest 换 virtio_mmio 传输驱动，块驱动与协议不变。

1. **复用 PCI 栈：**发现、地址映射、中断都有现成机制，virtio 只定义设备本身。
2. **代价：**虚拟机型必须模拟 PCI 控制器，机器模型更重。
3. **virtio-mmio：**QEMU 里对应非 PCI 机器的 virtio-blk-device，传输层替换不影响 virtio_blk 与协议。

一句话收束两层的关系：virtio-pci 解决“guest 怎么发现设备”，virtqueue 解决“数据怎么过去”——前者让宿主的一个镜像文件冒充标准 PCI 硬件，后者让访问不必逐寄存器陷入，两层拼合，磁盘才从宿主的一个文件变成 guest 里的一块盘。
