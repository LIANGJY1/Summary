# 内核配置选项

> 学习资料。主线：内核配置选项的机制（Kconfig 声明、.config 记录形态、=y/=m/=n）、.config 文件本体及其与 Kconfig/defconfig 的分工协作，以及具体选项在 QEMU virtio 磁盘启动场景中的职责。2026-10-08 自 05-linux-boot/01-linux-boot.md 迁入成册，同日增补 .config 文件本体一题。Q2、Q3 按内核 5.15 Kconfig 源文核对（drivers/base/Kconfig 在 AAOS13 树本地观察；virtio 两个 Kconfig 对照 v5.15 源文），.config 行格式另按本仓 AAOS13 树实际 .config 实样核对。题序即文档结构，供 atlas 同源直读。

**Q1: [learning] 内核源码树根的 .config 是个什么文件？它与 Kconfig、defconfig 如何分工协作？**

.config 是"这次构建"的配置实例清单：位于内核源码树根的纯文本文件，每行记录一个配置项的最终取值（=y、=m 或 not set 注释三种形态），数千行覆盖全部可见选项。它与 Kconfig、defconfig 不是三份同类配置文件，而是三种性质不同的东西，协作构成"一条单向生产线加一条回流线"。

三个文件的本质分工：

1. **Kconfig——规则层：**散布在源码各目录的 Kconfig 声明定义有哪些选项、类型、依赖和默认值，随源码版本走；它规定"可以配什么"，不记录"你配了什么"。
2. **defconfig——起点层：**arch/*/configs/ 下的 defconfig 是精简预填模板，只写与默认值不同的少数关键行；没写的项不是"关"，是"待定"。
3. **.config——实例层：**起点套用 Kconfig 规则（补默认值、满足依赖）展开后的完整结果。问"这次构建有什么功能"，只有它说得清。

协作关系按发生时刻分四步：

1. **配置阶段（生产）：**`make defconfig`、menuconfig 等配置工具读全部 Kconfig 建立选项全集与联动规则，盖上 defconfig 写过的少数行，再按规则补默认值、解依赖，写出完整 .config——规则和起点是输入，实例是输出。在 menuconfig 里把某项改成 =y 时，工具同时查 Kconfig 依赖，把它的依赖项一并打开写回。
2. **构建阶段（消费）：**Kconfig 在配置阶段完成后即退场；make 时 syncconfig 把 .config 转成 include/config/auto.conf（供 Makefile 做条件编译判断）与 include/generated/autoconf.h（供 C 源码 include 出 CONFIG_X 宏）。.config 本身不直接参与编译，源码里的宏都来自派生文件。
3. **沉淀回流（savedefconfig）：**`make savedefconfig` 拿当前 .config 与 Kconfig 默认值逐项对比，倒推出只含差异行的精简 defconfig；产品内核要固化自己的默认配置，走的就是这条从实例回到起点的路。
4. **跨版本演进（olddefconfig）：**内核升级后，由新版本的 Kconfig 解释旧 .config：新增选项按新默认值补行，已删除选项的行被当无效行丢弃，依赖变化的项被修正。离开 Kconfig，.config 只是一堆无法跟着源码演进的多余文本。

判断规则：三个文件单独拿出来都残缺——Kconfig 不含你的任何选择，defconfig 不是完整清单，.config 离开 Kconfig 无法跨版本演进。.config 回答"这次构建是什么样"，Kconfig 回答"允许配成什么样"，defconfig 回答"推荐从什么样开始"；纯文本形态让 .config 能提交进版本库、能逐行 diff 厂商基准与现场配置。

**Q2: 用 menuconfig 配内核时看到的选项（CONFIG_VIRTIO_PCI、CONFIG_DEVTMPFS 这些）怎么理解？=y、=m、=n 分别是什么意思，在 .config 里长什么样？**

每个选项是内核源码里一条 Kconfig 声明，包含选项名、类型、依赖、默认值和帮助文本。配置工具（menuconfig、defconfig）把全部选择汇总成源码树根的 .config 文件——一个纯文本"功能开关清单"，每行一个开关。构建系统按它决定这次编译包含哪些代码，并把每个选项变成 CONFIG_<选项名> 宏供源码条件编译，编出的基础内核镜像 vmlinux 再打包成启动用的 bzImage。三种取值的行格式与含义：

1. **=y（内建）：**`CONFIG_EXT4_FS=y`——功能编译进内核本体，随内核启动立即可用。
2. **=m（模块）：**`CONFIG_EXT4_FS=m`——编译成可加载模块，即单独的 .ko 文件，运行期用 modprobe 按需加载。
3. **=n（关闭）：**`# CONFIG_DEVTMPFS is not set`——关闭态存成注释形式而不是 `=n`；手写 `CONFIG_DEVTMPFS=n` 在重新生成时也会被规范化成这种注释行。

理解这类选项，还要抓住五点：

1. **类型决定状态数：**tristate（三态）选项才允许 =m，bool 只有两态。DEVTMPFS 是 bool——只有编进或不编，没有模块形态。依赖（depends on）不满足的选项在菜单里不可见或不可改。menuconfig 本身只是读写 .config 的交互界面。
2. **y 与 m 的取舍：**启动早期就要用的能力必须 =y——加载模块这件事需要已经运行起来的内核和能访问的文件系统，根盘驱动编成 =m 会遇到"没有盘就载不进驱动、没有驱动就找不到盘"的死结。发行版内核把大量驱动编成 =m 换取小体积，再把开机必需的模块打包进 initramfs 补上这一环。
3. **隐形选项：**没有提示文本的选项不出现在任何菜单里，由别的选项 select（反向选择）自动置位，或按默认值决定。比如 VIRTIO 总线核心就没有自己的菜单项——在 menuconfig 里"找不到 VIRTIO"是正常的，它会被开着的传输驱动自动带上。
4. **无效行会被静默忽略：**.config 里对应选项在当前源码中不存在、或依赖不再满足的行，会被 olddefconfig 当无效行忽略、回落默认值。选项名必须完全匹配：CONFIG_ 前缀加 Kconfig 符号名。
5. **"不写"不等于"关"：**注释掉或删掉一行，表示该选项回落默认值——默认值可能是 y，这不是关闭。要确定地关掉，必须保留 not set 注释行。本仓 AAOS13 树实测一份 .config 约七千行，关闭项全部是注释形式，没有一条 `CONFIG_X=n`，脚本判断开关状态要同时识别 =y/=m 行与 not set 注释行。

判断规则：问"这个功能编译进去了吗"，看 .config 里该选项的值。问"启动最早期能不能用"，看它是 =y，还是模块已被打包进 initramfs。想加一个功能，本质上就是把清单里对应行从 not set 注释改成 =y，再重新编译 bzImage。

**Q3: 让自编内核在 QEMU 里用 virtio 虚拟磁盘启动，CONFIG_VIRTIO_PCI、CONFIG_VIRTIO_BLK、CONFIG_DEVTMPFS 各承担什么？缺一个会怎样？**

三个选项各守"发现设备 → 变成块盘 → 自动出节点"这条链路的一段。QEMU 用 -drive file=rootfs.ext4,if=virtio 挂盘（file 指定镜像，if=virtio 表示以 virtio-blk 设备接入）时，guest 内核至少前两项要 =y：PCI 驱动 select 带上 VIRTIO 总线核心，块驱动依赖这个核心才能编译。三个选项的分工：

1. **CONFIG_VIRTIO_PCI——发现与认领：**virtio 设备的 PCI 传输层驱动，guest 靠它从 PCI 总线上认领 QEMU 挂出的 virtio 设备。
2. **CONFIG_VIRTIO_BLK——变成块盘：**virtio-blk 块设备驱动，把认领到的设备变成块设备盘，Linux 命名 /dev/vda。
3. **CONFIG_DEVTMPFS——自动出节点：**让内核驱动核心自动维护 /dev 下的设备节点，盘注册后节点自动出现。

落到排障与配置，有四个要点：

1. **缺一个的现象：**

    1. 缺 CONFIG_VIRTIO_PCI：lspci 仍能看到 QEMU 挂的 virtio 设备，但没有任何驱动认领它，块盘不会出现。
    2. 缺 CONFIG_VIRTIO_BLK：传输层把设备接上 virtio 总线，但没有块驱动消费它，同样没有 /dev/vda，dmesg 里没有 virtio_blk 注册日志。
    3. 缺 CONFIG_DEVTMPFS：盘其实已经注册，但 /dev 下没有自动生成的节点，要手工 mknod 或靠用户态守护进程建节点。没有 devtmpfs 的内核上，所有设备节点（包括 /dev/console）都得在 initramfs 里预置——initramfs 实验要手工建控制台节点就是这个原因。

2. **配置顺序的坑：**CONFIG_VIRTIO_BLK 的菜单项在 Device Drivers → Block devices 下，但一开始找不到它——它依赖 VIRTIO 总线核心，而这个核心没有提示文本，只有先到 Device Drivers → Virtio drivers 下把 CONFIG_VIRTIO_PCI 设为 =y（select 自动带上核心），块驱动选项才出现。
3. **devtmpfs 的两半：**CONFIG_DEVTMPFS 只是让内核"维护"节点数据。CONFIG_DEVTMPFS_MOUNT 才让内核在挂载根文件系统之后自动把 devtmpfs 挂到 /dev（行为可用内核参数 devtmpfs.mount=0|1 覆盖）。但它的 Kconfig 帮助文本明确写了不作用于 initramfs 启动——initramfs 场景必须由 /init 自己执行 mount -t devtmpfs devtmpfs /dev（-t 选择文件系统类型 devtmpfs，第一个 devtmpfs 是来源标识——这类伪文件系统没有磁盘设备，/dev 是挂载点）。CONFIG_DEVTMPFS 在 Device Drivers → Generic Driver Options 菜单下。
4. **=y 还是 =m：**自编最小内核建议三项全 =y，省去往 initramfs 放模块文件再加载的步骤。发行版内核把 virtio_blk 编成 =m 随 initramfs 携带，所以桌面系统也能直启云镜像。Android 的 Microdroid 同样把 virtio 驱动做成内核模块放在 vendor_boot 里随虚拟机携带。

"虚拟机里用虚拟盘"的配方是通用的：QEMU/KVM、Crosvm（Android 模拟器与 AVF 的 VMM）默认提供 virtio 设备，guest 内核按上面三项配好，宿主侧的一个镜像文件就成了 guest 里的启动盘或数据盘。