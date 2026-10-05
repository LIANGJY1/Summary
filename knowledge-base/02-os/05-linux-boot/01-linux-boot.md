# Linux 启动与 initramfs

> 通用 Linux 内核早期启动、initramfs 组成、控制台链路、归档操作，以及制作 rootfs 的文件实操命令（创建、权限与挂载，自 02-os/01-file 迁入）。Android 专属的内核/GKI、启动镜像与分区布局归 Android 专题。

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

**Q3: [done] `CONFIG_DEVTMPFS` 未启用时，为什么 initramfs 要预置 `/dev/console`？**

Mini_Android 实验内核未启用 `CONFIG_DEVTMPFS`，不能依靠 devtmpfs 提供 `/dev/console`。内核启动 initramfs 中的 `/init` 前会尝试打开该节点，并将其接到早期用户态的标准输入、输出和错误。节点缺失时，这条控制台通路无法按预期建立。

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




**Q12: [done] C++ init 调用 `mount()` 挂载 procfs 和 sysfs 时，各参数是什么意思？**

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
