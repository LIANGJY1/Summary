# Linux 启动与 initramfs

> initramfs 的准备、归档、交付与 Linux 早期启动排查。Android 专属启动布局归 Android 系统启动专题。

## 第一章 早期用户态与 initramfs

**Q1: [done] [tags:具体命令原理不想学没有用] initramfs 的制作和启动流程是什么，哪些步骤可以变化？**

制作流程是准备早期程序及依赖 → 组织文件和路径 → 生成内核支持的归档 → 检查归档 → 交给内核并验证启动。具体程序、压缩方式和加载方式可按环境选择。

以外部归档和 QEMU 启动为例，按以下顺序操作：

1. **准备程序：**提供与目标架构兼容、具有执行权限的 `/init`。静态程序不需要动态链接器和共享库。动态程序须同时提供所需的动态链接器和共享库，脚本须提供解释器。
2. **组织文件：**在宿主目录中准备 init、配置和设备节点等所需内容。若内核启动 init 前需要打开控制台，应在归档中预置字符设备 `/dev/console`。`/proc`、`/sys` 等目录只作为挂载点，创建目录不等于挂载文件系统。
3. **打包：**将文件及元数据制成内核支持的 cpio newc 或 crc 归档。可不压缩，也可使用目标内核支持的压缩算法。gzip 是一种选择。
4. **检查：**检查压缩数据完整性，并列出归档中的路径、执行权限和设备节点。检查通过只能证明归档内容符合预期，还不能证明程序可以启动。
5. **启动验证：**QEMU 可通过 `-initrd` 提供外部归档。内核将其解包到初始 rootfs，默认尝试执行 `/init`。通过启动日志和程序输出确认是否成功，不能仅凭归档检查判断。

宿主目录名没有固定要求。若从 `rootfs/` 目录内打包，归档记录 `./init`，解包后得到 `/init`。若从父目录打包并记录 `rootfs/init`，解包后得到 `/rootfs/init`。内核不会自动移除外层目录。

入口和交付方式也可变化。`rdinit=` 可指定初始 rootfs 中的其他入口，省略时默认尝试 `/init`。归档也可通过内核构建配置 `CONFIG_INITRAMFS_SOURCE` 编入内核，不一定由 QEMU 外部传入。

依据：Linux 内核文档 initramfs buffer format、Ramfs, rootfs and initramfs，QEMU 文档 Direct Linux Boot，Mini_Android 学习记录 01-initramfs启动与内核重编。

**Q2: [done] 制作并用 QEMU 启动 initramfs 时，rootfs/ 要准备什么，/init、/dev/console 和 console= 如何配合？**

`rootfs/` 是 initramfs 的打包目录。内核解包后，其内容成为运行时根目录 `/`，并执行其中的 `/init` 作为 PID 1。要让用户态和内核日志都能通过 QEMU 串口显示，还需准备控制台设备节点、匹配的内核参数和 QEMU 串口连接。

1. `/init`：打包目录中的 `rootfs/init` 必须是目标内核可执行的文件。解包后它位于 `/init`，由内核作为第一个用户态程序启动。`/proc`、`/sys` 可作为挂载点，由 init 按需挂载。`/tmp`、`/etc` 等目录按启动需要准备。
2. `/dev/console`：它是 init 等用户态程序访问系统控制台的字符设备节点，普通文件或目录不能代替。未启用 devtmpfs 时，initramfs 应包含该节点。设备号和权限须与目标内核匹配。
3. `console=` 与 QEMU 串口：内核需内建对应的串口控制台驱动，`console=` 选择匹配的串口设备及通信参数，QEMU 再把该虚拟串口接到宿主终端。`console=` 决定内核日志的输出端，`/dev/console` 提供用户态访问入口，两者职责不同。

**Q3: [learning] 为什么 initramfs 用 cpio 归档、system 分区用文件系统镜像？**

选型取决于读取者与使用方式：内核把 initramfs 的 cpio 归档解包到初始 rootfs，供早期用户态使用。system 分区则需要可挂载的文件系统镜像，以便运行时访问其中的文件。

1. **早期归档：**内核先建立 rootfs，再由 `init/initramfs.c` 将支持的 cpio 归档解包进去，无须将归档作为块设备文件系统挂载。这样 `/init` 及其依赖可在 system 分区挂载之前使用。传统 initrd 和内核直接挂载根分区是不同的启动路径。
2. **system 分区：**system 作为分区使用时，需要内核文件系统驱动识别的可挂载镜像，例如 ext4 或 EROFS。cpio 归档本身不能作为这样的文件系统直接挂载。分区是否只读及具体格式取决于设备配置。
3. **早期程序依赖：**静态链接的 `/init` 不需要动态链接器和共享库。动态链接程序也可作为 `/init`，但归档中必须提供其需要的动态链接器与共享库。脚本则需要可用的解释器。

依据：Linux 内核 `init/initramfs.c` 的解包逻辑与 `init/main.c` 的启动日志（`Run /init as init process`），Mini_Android 的 initramfs.cpio.gz 与 system.img 双阶段启动实验。

**Q4: [learning] 把 rootfs/ 打包成 initramfs.cpio.gz 的命令链怎样逐段读，省略 -H newc 和 -n 分别是什么后果？**

这一行的总目标是把 rootfs 目录树打包成一个内核能直接解包的 initramfs 镜像：数据经列举路径、编码归档、压缩、写文件四道加工，靠管道逐段流动，中途不落盘。括号、cd 与 && 是控制结构，决定在哪里运行、是否运行。失败能否被看见则由 pipefail 决定。

1. **控制结构：**管道前的三个部分决定运行位置与执行条件：

    1. `( ... )`：创建子 Shell，cd 只在其中生效，外层脚本的工作目录不变，后面的验证命令仍以项目目录为基准。没有括号时 cd 会影响整个脚本，后续相对路径全部错位。
    2. `cd rootfs`：以树根为列举起点，归档条目路径因此是 `./init` 形态，内核解包后落在 `/init`。若不进目录而从上级打包，条目带 `rootfs/` 前缀，解包后根目录下会多出一层 `/rootfs`。它也决定重定向路径 `../build` 的基准。
    3. `&&`：cd 成功后才执行 find。没有它时 cd 失败也会让 find 按当前目录列举，打包出错误的树而命令链未必报错。

2. **列举路径：**`find . -print0` 以 rootfs 目录树为输入，递归列举全部条目（含目录与设备节点），输出以 NUL 字符分隔的路径流。`-print0` 把默认的行分隔改为 NUL 分隔：文件名可以包含换行，行分隔会把一个路径拆成两条。NUL 不会出现在文件名中，分隔没有歧义。它与下游 cpio 的 `--null` 是成对的约定。

3. **编码归档：**`cpio --null --quiet -o -H newc` 以路径流为输入，把每个条目的权限、属主、设备号与数据按 newc 格式写成归档字节流。各选项：

    1. `-o`：copy-out 模式，即创建归档。cpio 必须显式指定创建、解包、列表三种模式之一，不指定直接报用法错误。
    2. `--null`：按 NUL 读取路径名单，与 `-print0` 配对。默认按换行分隔，无法正确读取 `-print0` 输出的路径名单。
    3. `--quiet`：隐藏 cpio 结束时在 stderr 打印的块数提示（实测为 `2 blocks` 一行）。省略只多一行噪音，不影响产物。
    4. `-H newc`：指定归档格式为 newc（头部魔数是 ASCII `070701`）。本机 GNU cpio 的默认输出格式是旧式 bin（魔数 `c7 71`，实测），而内核 initramfs 解包代码只认 `070701` 与 `070702` 两种魔数（内核源码树 init/initramfs.c、lib/earlycpio.c 实测）。格式不对时报 `incorrect cpio method used: use -H newc option`，这是整行中后果最重的省略。

4. **压缩：**`gzip -9 -n` 以 cpio 字节流为输入，输出 gzip 流（魔数 `1f 8b`），内核启动时按魔数识别并解压。各选项：

    1. `-9`：最高压缩级别，gzip 默认是 `-6`（man 页）。压缩代价是打包时间（只付一次），收益是镜像体积（initramfs 启动时整体进内存），所以这里显式取最高级。
    2. `-n`：不把原文件名与修改时间写进 gzip 头。本命令从管道读取，宿主 GNU gzip 1.10 实测省略 `-n` 时也不写入原文件名，时间戳为 0，因此这里的选项是冗余的。归档内部文件元数据和排列顺序仍可能影响最终校验和。

5. **写文件：**`> ../build/initramfs.cpio.gz` 把 gzip 的标准输出重定向到文件，截断后重新写入。重定向在子 Shell 内求值，基准是 rootfs/，`../build` 正是项目的 build/。目标目录必须预先存在，重定向不创建目录，目录缺失时这一行直接失败。

6. **失败如何暴露：**Shell 默认只取管道最后一段的退出码作为整条管道的结果——cpio 中途失败时，gzip 仍会把不完整的流压缩成功，命令行看起来“成功”。启用 Bash 的 `pipefail` 后，管道会报告失败。这个选项不会自动停止脚本，也不会清理已经写出的文件，需要检查退出状态后再使用产物。

依据：宿主实测（GNU cpio 默认 bin 格式魔数与 --quiet 的 stderr、gzip -n 的头部字段与校验和复现）、内核源码树 init/initramfs.c 与 lib/earlycpio.c 的魔数校验、man gzip 与 man cpio 的默认值。

打包前可用 `cp build/init-static rootfs/init` 把已编译的程序置于归档根目录。省略复制时，打包只会使用目录里原有的文件。用 `sha256sum build/init-static rootfs/init` 分别计算两个文件的 SHA-256（Secure Hash Algorithm 256-bit）摘要。摘要一致说明该次复制的内容一致，执行权限仍需单独检查。

**Q5: [done] 怎样检查 initramfs 归档是否完整、内容是否正确？**

先验证压缩数据，再检查归档中的路径、权限和设备号。两项检查分别判断数据是否损坏和打包内容是否符合预期。确认入口能否执行还需要启动验证。

以 GNU gzip 和 GNU cpio 2.13 检查 gzip 压缩的 cpio 归档为例，先进入项目目录，并确认 `build/initramfs.cpio.gz` 已生成。

1. **检查压缩流**。运行 `gzip -t build/initramfs.cpio.gz`。`-t` 只测试完整性，成功时通常无输出。去掉它会使用默认的压缩操作。
2. **查看归档条目**。运行 `gzip -cd build/initramfs.cpio.gz | cpio -tv`。`-d` 选择解压，缺省为压缩。`-c` 将结果交给管道，省略时通常解压到文件。`-t` 选择列出归档且不创建文件，缺少它且未指定其他模式会报错。`-v` 显示权限、类型等细节，省略时只显示名称。
3. **对照启动所需内容**。入口应在归档根部且可执行。若实验需要内核在启动 init 前打开控制台，确认 `dev/console` 是主设备号 5、次设备号 1 的字符设备。应在 Bash 中检查整条管道的退出状态，启用 `pipefail` 可让上游失败反映到管道结果。

依据：GNU gzip、GNU cpio 手册与 Linux 内核文档 Ramfs, rootfs and initramfs。

## 第二章 启动日志与串口

**Q6: [done] Linux 中 std::cout 的输出如何到达 QEMU 宿主机终端？**

输出链路从 `std::cout` 写入标准输出开始，经过 `/dev/console`、Linux 控制台和 QEMU 虚拟串口，最后到达宿主终端。要显示日志，init 的标准输出须连接到 `/dev/console`，内核参数须选择对应串口，QEMU 也须把该串口连接到宿主终端。

1. `std::cout`：写入当前进程的标准输出。
2. `/dev/console`：内核启动 init 前会尝试打开该设备，并将其连接到 init 的标准输入、输出和错误。
3. `console=` 与 QEMU：内核参数选择内核串口控制台，QEMU 把对应的虚拟串口转接到宿主终端。

`/dev/console` 提供用户态访问入口，`console=` 选择内核日志输出端，两者不能互相替代。

**Q7: [done] 内核怎样为早期 init 建立标准输出？**

内核启动早期 init 前，尝试打开 `/dev/console`，并将其连接到 PID 1 的标准输入、标准输出和标准错误。执行 `/init` 后，程序沿用这些文件描述符。

因此，init 写标准输出能否显示在 QEMU 终端，还取决于控制台设备节点、内核控制台选择和 QEMU 串口连接是否匹配。

**Q8: [done] 为什么 initramfs 要预置 /dev/console，等 /init 挂好 devtmpfs 不行吗？**

不能等。devtmpfs 要由 `/init` 运行后执行挂载才会生成节点，而内核在启动 `/init` 前就要打开 `/dev/console`，把它接到早期用户态的标准输入、输出和错误——时序上 devtmpfs 帮不上忙，与本内核是否编译 devtmpfs 无关。节点缺失时，这条控制台通路无法按预期建立。

因此要在打包目录中创建真正的字符设备节点。initramfs 归档还须保留其设备类型和设备号。只创建 `rootfs/dev/` 目录或普通空文件不能代替 `/dev/console`。

**Q9: [done] QEMU 在前台运行且终端没有提示符、串口空白，能判断 Linux 内核卡住了吗？**

不能。没有提示符只表示宿主 Shell 还在等待前台 QEMU 退出；QEMU 进程存活也不代表客体内核仍在正常执行。串口空白只能说明当前终端没有收到串口字符，无法单独判断是内核没有输出、Linux 没选中串口控制台，还是 QEMU 没把虚拟串口接到终端。

排查时分别确认两端：内核是否将控制台选为对应串口（如 `console=ttyS0`），QEMU 是否将该虚拟串口连接到终端（如 `-serial stdio`）。`-display none` 只关闭图形显示，`-monitor none` 只关闭 QEMU monitor，都不能代替这两项配置。

**Q10: [done] 已看到 earlycon 内核日志，却没有 /init 输出，说明什么？**

这只说明早期日志通道已经输出成功，不代表正式的 `ttyS0` 串口驱动已注册。`earlycon` 在正式驱动初始化前直接输出内核早期信息。`/init` 的输出则需要正式串口控制台、`/dev/console` 和 QEMU 串口连接组成完整通路。

例如 `earlycon=uart8250,io,0x3f8,115200n8` 指定早期控制台使用 8250 串口、I/O 端口 `0x3f8`（x86 COM1），通信格式为 115200 波特率、无校验、8 个数据位。早期日志可见而 `/init` 无输出，说明应继续检查正式串口驱动和后续控制台连接。

**Q11: [done] 8250 串口数设为 0 时为什么没有 ttyS0，8250.nr_uarts=1 有什么作用？**

`CONFIG_SERIAL_8250_RUNTIME_UARTS=0` 让 8250 驱动启动时注册 0 个串口，因此不会提供正式的 `ttyS0`。这是内核驱动的注册数量设置，不代表 QEMU 没有模拟 COM1。

1. `CONFIG_SERIAL_8250_NR_UARTS=4`：驱动最多支持 4 个串口。
2. `CONFIG_SERIAL_8250_RUNTIME_UARTS=0`：默认启动时注册 0 个串口。
3. `8250.nr_uarts=1`：加入 QEMU 的 `-append` 后，将本次启动的注册数设为 1。x86 第一个串口 COM1 对应 `ttyS0`，正式驱动注册后，`console=ttyS0` 才有可用设备。

这个内核参数只影响本次启动，无需重编译内核或重新打包 initramfs。

**Q12: [done] 启动日志 Serial: 8250/16550 driver 和 Run /init as init process 各说明到哪一步？**

这两条日志分别标记了串口驱动初始化和内核尝试启动 `/init`，都不能单独证明整条输出链路已经正常。

1. `Serial: 8250/16550 driver, ...`：8250 驱动已通过串口数量检查并打印端口信息。它不证明 QEMU 终端一定接到了输出。
2. `Run /init as init process`：内核即将调用 `kernel_execve()` 执行 `/init`。它不保证执行成功。还要检查其后的错误日志或 `/init` 自身输出。

## 第三章 分区接入与挂载

**Q13: [done] 怎么把制作好的 system.img 作为 virtio 磁盘交给 QEMU 启动，命令各参数是什么意思，启动后会发生什么？**

QEMU 在模拟一台完整的电脑，每条参数都是往虚拟机上焊硬件或规定行为：`-m` 插内存条，`-kernel` 与 `-initrd` 绕过硬盘引导、直接把内核和初始内存盘交给这台机器，`-append` 写内核启动参数，`-drive` 焊一块盘片为宿主机 system.img 文件的硬盘，其余参数规定输出与退出行为。

例如，Mini_Android 的完整启动命令：

```bash
cd /home/liang/Project/MyProject/Mini_Android
qemu-system-x86_64 \
    -m 1024 \
    -kernel /home/liang/Project/AAOS13_kernel/common/arch/x86/boot/bzImage \
    -initrd /home/liang/Project/MyProject/Mini_Android/build/initramfs.cpio.gz \
    -drive file=/home/liang/Project/MyProject/Mini_Android/build/system.img,format=raw,if=virtio \
    -append "earlycon=uart8250,io,0x3f8,115200n8 8250.nr_uarts=1 console=ttyS0,115200n8 rdinit=/init loglevel=8" \
    -display none \
    -serial stdio \
    -monitor none \
    -no-reboot
```

命令的效果分三层看：磁盘怎么接上、guest 侧怎么发现、节点与内容怎么到位。

1. `-drive` 的三个字段：

    1. `file=`：盘片。虚拟机读磁盘第 0 扇区，QEMU 就返回文件的第 0–511 字节。虚拟机写盘，QEMU 就写回这个文件。同一份字节，在宿主机是文件，在虚拟机里是硬盘。
    2. `format=raw`：声明文件是裸字节、没有包装，字节内容即磁盘内容。qcow2 等格式另带元数据层（快照、压缩），mkfs 造出的是裸镜像，与盘 1:1 对应。
    3. `if=virtio`：interface，即盘插在哪种总线上。virtio 是半虚拟化接口，前端驱动与 QEMU 约定协作，不逐位模仿真实硬件。省略时默认挂到 IDE，guest 看到的是 sd 盘而不是 vd 盘。

2. **guest 侧的落地链：**

    ```text
    QEMU 把盘挂上 virtio 总线
      → 内核 VIRTIO_PCI 驱动发现总线上的设备
      → 内核 VIRTIO_BLK 驱动认领“这是一块盘”
      → 内核给它登记为块设备，起名 vda
      → 打出你确认过的那行日志
    ```

    编内核时开 VIRTIO_PCI 与 VIRTIO_BLK 两个选项，正是为了这条链。vda 即 virtio disk a，第二块是 vdb。SATA 盘叫 sda、老式 IDE 叫 hda，前缀暴露接口类型。

3. **日志行对账：**`virtio_blk virtio0: [vda] 131072 512-byte logical blocks (67.1 MB/64.0 MiB)` 中，131072×512 字节 = 64MiB，正是 mkfs 时分配的容量。67.1MB 与 64.0MiB 是十进制与二进制两种数法，是同一容量不是两块盘。
4. **启动方式参数：**

    1. `-m 1024`：虚拟机内存 1GB。省略时用 QEMU 默认值（常见 128MB），可能不足以启动系统。
    2. `-kernel` 与 `-initrd`：分别指定内核镜像与 initramfs，绕过磁盘引导直接加载。省略则 QEMU 按默认引导顺序从磁盘启动。
    3. `-append`：传给内核的命令行。`earlycon` 与 `console=ttyS0` 决定日志输出去向。`rdinit=/init` 显式指定 initramfs 里第一个用户态程序，省略时内核默认执行 `/init`。

5. **输出控制参数：**

    1. `-display none`：关闭图形显示窗口。本场景只靠串口观察输出。
    2. `-serial stdio`：把虚拟机串口绑定到当前终端，日志才能显示在宿主终端上。
    3. `-monitor none`：关闭 QEMU 监视器界面，避免它与串口争抢同一终端。
    4. `-no-reboot`：guest 请求重启时直接退出而不是重新启动，便于判断实验结束。

6. **硬件在册不等于名字在册：**[vda] 日志只代表内核已注册块设备。`/dev/vda` 节点要等 devtmpfs 挂到 /dev 后由内核生成，读出内容还要再挂载。本内核 CONFIG_DEVTMPFS=y 但 CONFIG_DEVTMPFS_MOUNT 未设置，initramfs 场景也不会自动挂载，必须由 init 执行 `mount("devtmpfs", "/dev", ...)`，节点才出现。

**Q14: [learning] 启动时内核已发现 vda，为什么挂载仍找不到 /dev/vda？**

内核注册 virtio 块设备与 `/dev/vda` 节点出现是两步。驱动日志表明设备已登记，但挂载通过路径查找节点，节点不存在时会返回 ENOENT。

1. **检查设备：**确认内核已加载所需 virtio 驱动，并发现目标磁盘。
2. **检查节点：**确认 `/dev` 已通过 devtmpfs 或其他设备管理机制提供正确节点。initramfs 场景不能依赖 devtmpfs 的自动挂载。
3. **检查内容：**节点存在后仍需确认文件系统类型及镜像内容正确，才能成功挂载。
