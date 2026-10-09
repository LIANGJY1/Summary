# Linux 启动与 initramfs

> 通用 Linux 内核早期启动、initramfs 组成、控制台链路、归档操作，以及制作 rootfs 的文件实操命令（创建、权限与挂载，自 02-os/01-file 迁入）。内核配置选项两题（Kconfig/.config 机制与 virtio 启动链路口径）于 2026-10-08 迁往 ../06-linux-build/02-kernel-config.md；bzImage、zImage 两题迁往 ../06-linux-build/01-image.md。Android 专属的内核/GKI、启动镜像与分区布局归 Android 专题。

**Q1: [done] 制作并用 QEMU 启动 initramfs 时，`rootfs/` 要准备什么，`/init`、`/dev/console` 和 `console=` 如何配合？**

`rootfs/` 是 initramfs 的打包目录。内核解包后，其内容成为运行时根目录 `/`，并执行其中的 `/init` 作为 PID 1。要让用户态和内核日志都能通过 QEMU 串口显示，还需准备控制台设备节点、匹配的内核参数和 QEMU 串口连接。

1. `/init`：打包目录中的 `rootfs/init` 必须是目标内核可执行的文件。解包后它位于 `/init`，由内核作为第一个用户态程序启动。`/proc`、`/sys` 可作为挂载点，由 init 按需挂载；`/tmp`、`/etc` 等目录按启动需要准备。
2. `/dev/console`：它是 init 等用户态程序访问系统控制台的字符设备节点，普通文件或目录不能代替。未启用 devtmpfs 时，initramfs 应包含该节点；设备号和权限须与目标内核匹配。
3. `console=` 与 QEMU 串口：内核需内建对应的串口控制台驱动，`console=` 选择匹配的串口设备及通信参数，QEMU 再把该虚拟串口接到宿主终端。`console=` 决定内核日志的输出端，`/dev/console` 提供用户态访问入口，两者职责不同。

**Q2: [done] Linux 中 `std::cout` 的输出如何到达 QEMU 宿主机终端？**

输出链路从 `std::cout` 写入标准输出开始，经过 `/dev/console`、Linux 控制台和 QEMU 虚拟串口，最后到达宿主终端。要显示日志，init 的标准输出须连接到 `/dev/console`，内核参数须选择对应串口，QEMU 也须把该串口连接到宿主终端。

1. `std::cout`：写入当前进程的标准输出。
2. `/dev/console`：内核启动 init 前会尝试打开该设备，并将其连接到 init 的标准输入、输出和错误。
3. `console=` 与 QEMU：内核参数选择内核串口控制台，QEMU 把对应的虚拟串口转接到宿主终端。

`/dev/console` 提供用户态访问入口，`console=` 选择内核日志输出端，两者不能互相替代。

**Q3: [done] 为什么 initramfs 要预置 `/dev/console`，等 `/init` 挂好 devtmpfs 不行吗？**

不能等。devtmpfs 要由 `/init` 运行后执行挂载才会生成节点，而内核在启动 `/init` 前就要打开 `/dev/console`，把它接到早期用户态的标准输入、输出和错误——时序上 devtmpfs 帮不上忙，与本内核是否编译 devtmpfs 无关。节点缺失时，这条控制台通路无法按预期建立。

因此要在打包目录中创建真正的字符设备节点。initramfs 归档还须保留其设备类型和设备号。只创建 `rootfs/dev/` 目录或普通空文件不能代替 `/dev/console`。

**Q4: [done] 如何用 `find`、`cpio` 和 `gzip` 打包并检查 initramfs？**

`rootfs/` 是主机上的目录，内核启动时拿到的是 initramfs 镜像数据，不能直接遍历这个目录。因此使用 `cpio` 将文件路径和元数据编码成归档。`newc` 是内核支持的归档格式，能记录权限和设备节点等信息。内核据此还原初始根文件系统。`gzip` 用来减小镜像体积。打包后检查压缩流并列出归档内容，可提前发现损坏或漏文件，不必解包。

```bash
set -o pipefail
(cd rootfs && find . -print0 | cpio --null --quiet -H newc -o | gzip -9 -n > ../build/initramfs.cpio.gz)
gzip -t build/initramfs.cpio.gz
gzip -cd build/initramfs.cpio.gz | cpio -itv
```

命令按“生成、验证、查看”执行：

1. **生成归档：**`set -o pipefail` 使管道中任一命令失败时返回失败状态，避免只看到最后一条命令的结果。括号创建子 Shell。`cd rootfs` 只改变子 Shell 的目录。`&&` 确保进入目录成功后才打包。

    1. `find . -print0`：列出文件路径，以 NUL 分隔，避免空格或换行导致路径被拆开。
    2. `cpio --null --quiet -H newc -o`：`--null` 按 NUL 读取路径。`--quiet` 隐藏块数提示。`-H newc` 选择归档格式。`-o` 创建归档。
    3. `gzip -9 -n`：压缩归档。`-9` 使用最高压缩级别。`-n` 不把原文件名和时间戳写入压缩头。
    4. `> ../build/initramfs.cpio.gz`：将结果写入上一级目录的 `build/` 并覆盖同名文件，该目录须预先存在。

2. **验证压缩流：**`gzip -t` 检查压缩数据是否完整，成功时通常无输出。
3. **查看归档清单：**`gzip -cd` 解压到标准输出，其中 `-c` 将结果写到标准输出，`-d` 表示解压。`cpio -itv` 读取并详细列出归档内容，不会解包到当前目录。

**Q5: [done] QEMU 在前台运行且终端没有提示符、串口空白，能判断 Linux 内核卡住了吗？**

不能。没有提示符只表示宿主 Shell 还在等待前台 QEMU 退出；QEMU 进程存活也不代表客体内核仍在正常执行。串口空白只能说明当前终端没有收到串口字符，无法单独判断是内核没有输出、Linux 没选中串口控制台，还是 QEMU 没把虚拟串口接到终端。

排查时分别确认两端：内核是否将控制台选为对应串口（如 `console=ttyS0`），QEMU 是否将该虚拟串口连接到终端（如 `-serial stdio`）。`-display none` 只关闭图形显示，`-monitor none` 只关闭 QEMU monitor，都不能代替这两项配置。

**Q6: [done] 已看到 `earlycon` 内核日志，却没有 `/init` 输出，说明什么？**

这只说明早期日志通道已经输出成功，不代表正式的 `ttyS0` 串口驱动已注册。`earlycon` 在正式驱动初始化前直接输出内核早期信息；`/init` 的输出则需要正式串口控制台、`/dev/console` 和 QEMU 串口连接组成完整通路。

例如 `earlycon=uart8250,io,0x3f8,115200n8` 指定早期控制台使用 8250 串口、I/O 端口 `0x3f8`（x86 COM1），通信格式为 115200 波特率、无校验、8 个数据位。早期日志可见而 `/init` 无输出，说明应继续检查正式串口驱动和后续控制台连接。

**Q7: [done] 8250 串口数设为 0 时为什么没有 `ttyS0`，`8250.nr_uarts=1` 有什么作用？**

`CONFIG_SERIAL_8250_RUNTIME_UARTS=0` 让 8250 驱动启动时注册 0 个串口，因此不会提供正式的 `ttyS0`。这是内核驱动的注册数量设置，不代表 QEMU 没有模拟 COM1。

1. `CONFIG_SERIAL_8250_NR_UARTS=4`：驱动最多支持 4 个串口。
2. `CONFIG_SERIAL_8250_RUNTIME_UARTS=0`：默认启动时注册 0 个串口。
3. `8250.nr_uarts=1`：加入 QEMU 的 `-append` 后，将本次启动的注册数设为 1。x86 第一个串口 COM1 对应 `ttyS0`，正式驱动注册后，`console=ttyS0` 才有可用设备。

这个内核参数只影响本次启动，无需重编译内核或重新打包 initramfs。

**Q8: [done] 启动日志 `Serial: 8250/16550 driver` 和 `Run /init as init process` 各说明到哪一步？**

这两条日志分别标记了串口驱动初始化和内核尝试启动 `/init`，都不能单独证明整条输出链路已经正常。

1. `Serial: 8250/16550 driver, ...`：8250 驱动已通过串口数量检查并打印端口信息；它不证明 QEMU 终端一定接到了输出。
2. `Run /init as init process`：内核即将调用 `kernel_execve()` 执行 `/init`；它不保证执行成功。还要检查其后的错误日志或 `/init` 自身输出。

**Q9: [done] Linux 中文件描述符 `1` 为什么不固定指向屏幕，Mini_Android 的 `/init` 又如何获得标准输出？**

文件描述符 `1` 是进程打开对象清单中的编号，按约定承担标准输出的作用；它指向哪里，由启动程序的一方安排，可以是终端、文件或管道。

1. 普通程序通常继承 Shell 设置的文件描述符。直接在终端运行时，`1` 通常指向终端；重定向或管道会让它指向文件或管道。
2. Mini_Android 启动时没有 Shell。内核的 `console_on_rootfs()` 打开 `/dev/console`，再通过三次 `init_dup()` 将它接到 PID 1 的文件描述符 `0`、`1`、`2`，分别作为标准输入、标准输出和标准错误。
3. 内核执行 `/init` 后，这些描述符继续保留。因此，`std::cout` 写入 fd `1` 后，会进入 `/dev/console`；能否显示在 QEMU 终端，还取决于 Linux 控制台和 QEMU 串口是否接通。

**Q10: [done] 如何创建普通文件？**

`touch` 可创建空普通文件，shell 重定向可创建并写入内容，设备节点则用 `mknod` 创建。设备节点是文件系统中的特殊文件，不存放普通文件数据，也不等于设备本身。

```bash
touch rootfs/etc/example.conf
printf '%s\n' 'example' > rootfs/etc/example.conf
sudo mknod -m 600 rootfs/dev/console c 5 1
```

1. `touch`：文件不存在时创建空文件；文件已存在时更新其时间戳，不会清空内容。
2. `printf ... > path`：`>` 在目标不存在时创建文件，存在时先清空再写入；父目录必须已存在。
3. `mknod ... c 5 1`：创建字符设备节点。访问该路径时，内核根据主、次设备号把操作交给相应驱动处理，设备号必须匹配目标内核。




**Q11: [done] `chmod`、`mknod`、`file` 和 `ls -l` 怎么用？**

这些命令分别设置权限、创建设备节点和检查文件。示例中的路径都相对于项目目录：

```bash
chmod 755 rootfs/init
chmod 1777 rootfs/tmp
sudo mknod -m 600 rootfs/dev/console c 5 1
file rootfs/init
ls -l rootfs/init rootfs/dev/console
```

1. `chmod`（change mode）：语法是 `chmod 权限 路径`。权限数字按所有者、组用户、其他用户排列，每位由读 `4`、写 `2`、执行 `1` 相加得到。

    1. `755`：所有者可读、写、执行；组用户和其他用户可读、执行。
    2. `777`：所有用户可读、写、执行。用于目录时，用户可创建、删除或改名目录项。
    3. `1777`：在 `777` 前加 sticky bit，常用于共享临时目录。用户不能删除或改名其他用户的文件，目录所有者和 root 除外。
    4. `666`：所有用户可读、写但不可执行，适合普通数据文件。目录没有执行权限就不能进入或遍历。
    5. `600`：仅所有者可读、写，组用户和其他用户无权限。

2. `mknod`（make node）：创建特殊文件节点。`mkdir` 创建目录，不能代替 `mknod` 创建设备节点。命令参数如下：

    1. `sudo`：以管理员权限执行。
    2. `-m 600`：设置节点权限，仅所有者可读、写。
    3. `rootfs/dev/console`：节点路径。
    4. `c 5 1`：`c` 表示字符设备，`5` 和 `1` 分别是主、次设备号。`5:1` 必须匹配目标内核。

3. `file`：不是缩写，命令名就是英文单词 file。它识别文件类型、目标架构和链接信息，不会运行程序。
4. `ls -l`：`ls` 是 list，`-l` 表示 long listing format。长格式会显示权限、所有者和文件类型等信息。字符设备应显示 `c` 和设备号 `5, 1`。


**Q12: [done] chmod 如何使用？**

`chmod` 修改文件或目录的权限位，语法是 `chmod 模式 路径`——模式在前、路径在后。给脚本加执行权限最简写法是 `chmod +x 路径`（符号模式，在现有权限上增加执行位），完整指定三位权限用 `chmod 755 路径`（数字模式）。

1. **参数顺序：**模式在前、路径在后。写反成 `chmod scripts/build.sh 755` 时，chmod 把第一个操作数当模式解析，遇到非法模式直接报错退出，不会修改任何文件的权限。
2. **数字模式：**三位数字依次是所有者、组用户、其他用户，每位由读 `4`、写 `2`、执行 `1` 相加。`755` 是所有者可读写执行、其他人可读执行；`666` 每位是 `4+2`，执行位为 0，给脚本设 `666` 后脚本仍然不可执行。
3. **符号模式：**`+x` 只增加执行位，不动其他位；数字模式是绝对设置，会覆盖原有权限。例如 `644` 的脚本用 `chmod +x` 得到 `755`；原本 `600` 的文件用 `chmod 755` 会让其他用户也获得读和执行权限。
4. **执行与验证：**通过 `./build.sh` 执行脚本时，文件需要执行位，解释器还要读取脚本内容，读位同样必需；改用 `bash build.sh` 执行则不需要执行位。用 `ls -l` 查看权限位，确认后再执行；递归修改目录树用 `chmod -R 模式 目录`。

例如，给项目目录下的 `scripts/build.sh` 增加执行位并确认：

```bash
chmod +x scripts/build.sh
ls -l scripts/build.sh   # -rwxr-xr-x（等效于 755）
./scripts/build.sh
```

`chmod +x` 只添加执行位，`ls -l` 输出的权限位与预期一致后再运行脚本；等价写法是 `chmod 755 scripts/build.sh`，它直接把权限设为 `rwxr-xr-x`。

**Q13: [done] C++ init 调用 `mount()` 挂载 procfs 和 sysfs 时，各参数是什么意思？**

`mount()` 是 Linux 的挂载系统调用接口，不是终端里的 `mount` 命令。代码把提供进程信息的 procfs 挂到 `/proc`，把提供内核设备信息的 sysfs 挂到 `/sys`；成功返回 `0`，失败返回 `-1` 并设置 `errno`。

```cpp
mount("proc", "/proc", "proc", 0, nullptr);
mount("sysfs", "/sys", "sysfs", 0, nullptr);
```

每次调用的五个参数依次表示：

1. 来源 `source`：`"proc"` 或 `"sysfs"` 是来源标识；这类虚拟文件系统没有要挂载的磁盘设备。
2. 挂载点 `target`：`"/proc"` 或 `"/sys"` 是 initramfs 中已存在的目录，挂载后用于访问对应文件系统。
3. 文件系统类型 `filesystemtype`：`"proc"` 选择 procfs，`"sysfs"` 选择 sysfs。
4. 挂载标志 `mountflags`：`0` 表示不额外指定挂载标志。
5. 文件系统参数 `data`：`nullptr` 表示不传递额外的文件系统专用选项。

init 通常以 root 身份执行挂载。任一调用返回 `-1` 时，示例只打印通用错误；检查 `errno` 才能知道具体原因，例如权限不足或挂载点不存在。


**Q14: [done] 怎么把制作好的 system.img 作为 virtio 磁盘交给 QEMU 启动，命令各参数是什么意思，启动后会发生什么？**

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

1. **`-drive` 的三个字段：**

    1. `file=`：盘片。虚拟机读磁盘第 0 扇区，QEMU 就返回文件的第 0–511 字节；虚拟机写盘，QEMU 就写回这个文件。同一份字节，在宿主机是文件，在虚拟机里是硬盘。
    2. `format=raw`：声明文件是裸字节、没有包装，字节内容即磁盘内容；qcow2 等格式另带元数据层（快照、压缩），mkfs 造出的是裸镜像，与盘 1:1 对应。
    3. `if=virtio`：interface，即盘插在哪种总线上。virtio 是半虚拟化接口，前端驱动与 QEMU 约定协作，不逐位模仿真实硬件；省略时默认挂到 IDE，guest 看到的是 sd 盘而不是 vd 盘。

2. **guest 侧的落地链：**

    ```text
    QEMU 把盘挂上 virtio 总线
      → 内核 VIRTIO_PCI 驱动发现总线上的设备
      → 内核 VIRTIO_BLK 驱动认领"这是一块盘"
      → 内核给它登记为块设备,起名 vda
      → 打出你确认过的那行日志
    ```

    编内核时开 VIRTIO_PCI 与 VIRTIO_BLK 两个选项，正是为了这条链。vda 即 virtio disk a，第二块是 vdb；SATA 盘叫 sda、老式 IDE 叫 hda，前缀暴露接口类型。

3. **日志行对账：**`virtio_blk virtio0: [vda] 131072 512-byte logical blocks (67.1 MB/64.0 MiB)` 中，131072×512 字节 = 64MiB，正是 mkfs 时分配的容量；67.1MB 与 64.0MiB 是十进制与二进制两种数法，是同一容量不是两块盘。
4. **启动方式参数：**

    1. `-m 1024`：虚拟机内存 1GB；省略时用 QEMU 默认值（常见 128MB），可能不足以启动系统。
    2. `-kernel` 与 `-initrd`：分别指定内核镜像与 initramfs，绕过磁盘引导直接加载；省略则 QEMU 按默认引导顺序从磁盘启动。
    3. `-append`：传给内核的命令行。`earlycon` 与 `console=ttyS0` 决定日志输出去向；`rdinit=/init` 显式指定 initramfs 里第一个用户态程序，省略时内核默认执行 `/init`。

5. **输出控制参数：**

    1. `-display none`：关闭图形显示窗口；本场景只靠串口观察输出。
    2. `-serial stdio`：把虚拟机串口绑定到当前终端，日志才能显示在宿主终端上。
    3. `-monitor none`：关闭 QEMU 监视器界面，避免它与串口争抢同一终端。
    4. `-no-reboot`：guest 请求重启时直接退出而不是重新启动，便于判断实验结束。

6. **硬件在册不等于名字在册：**[vda] 日志只代表内核已注册块设备；`/dev/vda` 节点要等 devtmpfs 挂到 /dev 后由内核生成，读出内容还要再挂载。本内核 CONFIG_DEVTMPFS=y 但 CONFIG_DEVTMPFS_MOUNT 未设置，initramfs 场景也不会自动挂载，必须由 init 执行 `mount("devtmpfs", "/dev", ...)`，节点才出现。

**Q15: [learning] vda 是什么，为什么内核已经认出这块盘，挂载时还是会找不到 `/dev/vda`？**

vda 是内核 virtio_blk 驱动给 QEMU 虚拟磁盘起的注册名，取 virtio disk a 之意。“内核认得这块盘”和“用户态能通过 /dev/vda 访问它”是两层：驱动只完成设备登记，/dev/vda 这个节点文件要另有人创建；mount() 按路径字符串找节点，找不到就以 ENOENT 失败，根本走不到磁盘那一步。

1. **名字怎么来的：**v、d、a 分别是 virtio、disk 和第一块，第二块是 vdb。这不是配置文件里起的名字，而是驱动发现硬件后的注册名。各驱动的命名习惯：

    1. virtio_blk 驱动管理 QEMU 虚拟磁盘，命名为 vda、vdb。
    2. sd 驱动管理 SATA、USB、SCSI 盘，命名为 sda、sdb。
    3. nvme 驱动管理 NVMe 固态盘，命名为 nvme0n1。
    4. 老式 IDE 驱动命名为 hda、hdb。

    日志行 `virtio_blk virtio0: [vda] 131072 512-byte logical blocks (67.1 MB/64.0 MiB)` 就是起名现场：virtio_blk 驱动在 virtio 总线上发现一块盘，起名 vda，大小 131072×512 字节 = 64MiB，正是 mkfs 做出的那张盘。

2. **设备与节点是两层：**

    1. 第一层在内核里，已就绪：驱动向内核登记一块块设备，名字 vda，设备号 254:0（主设备号:次设备号）。到这里硬件已经能干活。
    2. 第二层在用户态，还不存在：mount() 的参数是路径字符串 /dev/vda，它像找普通文件一样解析路径，去 /dev 目录里找名为 vda 的文件；找不到就立刻返回 -1，且 errno 为 2（ENOENT）。
    3. 设备节点就是这个文件：本身不存数据，存的是一对设备号。手工创建的 console 节点正是如此，`ls -l` 显示 `crw------- root root 5, 1`——c 表示字符设备，5, 1 是主、次设备号。vda 无法提前创建：盘是 QEMU 运行时才插进来的，设备号运行时才知道。

3. **宿主机的对照：**宿主机自己的 NVMe 盘同样是一个节点，`ls -l /dev/nvme0n1` 输出 `brw-rw---- 1 root disk 259, 5`——b 表示块设备，259, 5 是它的设备号。整机硬盘和 QEMU 里的 vda 在 Linux 眼里是同一类东西，区别只在节点由谁创建。

4. **devtmpfs 负责自动建节点：**devtmpfs 是一种伪文件系统：挂载后里面看起来有文件，内容却由内核实时生成——驱动每登记一个设备，内核就在已挂载的 devtmpfs 里建出对应节点，所以挂上它之后 /dev/vda 会自己出现。内核配置里两个开关分管“会不会”和“替不替你做”（AAOS13 源码树 .config 第 1709–1710 行实测）：

    ```text
    CONFIG_DEVTMPFS=y                    # 内核会挂这个文件系统
    # CONFIG_DEVTMPFS_MOUNT is not set   # 内核不会替你自动挂
    ```

    因此必须由 init 亲自动手：`mount("devtmpfs", "/dev", "devtmpfs", 0, nullptr)`。这就是启动流程里挂 devtmpfs 那一行的全部意义。

5. **真 Android 的做法：**AAOS 13 的 first_stage_init 挂的是 tmpfs，再靠 uevent 机制建节点——用户态监听内核发出的新设备事件，再调用 mknod 创建，比 devtmpfs 复杂但更灵活。Mini 版用 devtmpfs 一行简化，思路同源：都是挂一个东西到 /dev，让节点出现。

6. **挂载成功的三个条件：**`mount("/dev/vda", "/system", "ext4", 0, nullptr)` 要成功，三条缺一不可：

    1. 盘已登记：virtio_blk 的 [vda] 日志即铁证，已满足。
    2. /dev/vda 节点存在：挂 devtmpfs 就是为了它；缺节点时 errno 为 2。
    3. 内容是合法 ext4：mkfs 已经做好；节点在但内核认不出文件系统时 errno 为 22。

    由此可以自己推出：挂载报 errno 2 时先查 /dev/vda 是否存在，而不是先怀疑盘或镜像。
