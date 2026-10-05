# 操作系统学习资料

> 收录 14 篇、61 道通用操作系统题，按基础机制、文件系统、内存、进程、虚拟化和 Linux 启动组织。Android 专属实现归 `../01-android/`；Java/JVM 语言与运行时知识按知识路由归入相应主题。

## 推荐阅读顺序

1. 先读 `00-fundamentals/`，建立操作系统职责、运行机制和保护边界的基础认识。
2. 按兴趣学习 `01-file/`、`02-memory/` 和 `03-processes/` 中的文件、内存与进程机制。
3. 再读 `04-virtualization/`，理解虚拟机与宿主资源的关系。
4. 学习 Linux 进入用户态或制作 initramfs 时，读 `05-linux-boot/`。

## 目录与文件索引

### `00-fundamentals/`：操作系统基础（1 篇，6 题）

- `01-os-fundamentals.md`（6 题）：操作系统职责、运行特征、用户态与内核态、系统调用及内核组织结构。

### `01-file/`：文件与文件系统（8 篇，24 题）

- `01-file-management.md`（2 题）：文件属性、逻辑与物理结构、目录和存储之间如何分工。
- `02-file-structure.md`（2 题）：文件逻辑结构与物理结构的区别，以及常见块分配方式。
- `03-file-directories.md`（3 题）：目录组织方式、路径查找和目录项。
- `04-storage-space.md`（3 题）：空闲空间的记录方法、位图换算和空间管理。
- `05-file-operations.md`（6 题）：创建、打开、读写和复制文件；`chmod`、`mknod` 等命令，以及 C++ init 使用 `mount()` 的示例。
- `06-file-sharing-protection.md`（2 题）：口令保护、加密、访问控制列表和文件共享。
- `07-vfs-mounting.md`（3 题）：VFS 的统一访问接口、vnode 与 inode，以及挂载点如何改变路径查找。
- `08-filesystem-layout.md`（3 题）：文件系统分层、物理与逻辑格式化，以及挂载和存储布局的区别。

### `02-memory/`：内存（2 篇，12 题）

- `01-foundation.md`（2 题）：闪存与 RAM 的区别，以及 Android 中持久化存储和运行内存的用途。
- `02-memory-management.md`（10 题）：地址空间、分配、分页与分段、虚拟内存、进程映像和内存映射。

### `03-processes/`：进程与调度（1 篇，9 题）

- `01-processes-and-scheduling.md`（9 题）：程序与进程、进程状态和控制、线程、调度层次与策略。

### `04-virtualization/`：虚拟化（1 篇，1 题）

- `01-virtualization.md`（1 题）：第一类与第二类虚拟机监控程序的运行位置和资源访问路径。

### `05-linux-boot/`：Linux 启动与早期用户态（1 篇，9 题）

- `01-linux-boot.md`（9 题）：initramfs 的组成、`/init` 与控制台输出链路、QEMU 串口排查及 initramfs 归档检查。

## 内容边界

- 本目录收录通用操作系统机制和 Linux 通用启动知识。
- Linux 文件操作、系统调用示例集中在 `01-file/05-file-operations.md`；文件系统挂载模型与路径行为集中在 `01-file/07-vfs-mounting.md`。
- Android 内核/GKI、Android 启动镜像与分区布局等平台专属实现，归 `../01-android/` 对应专题。
- C++/Java 等语言语法和运行时知识，归 `03-language/` 对应专题；这里只保留操作系统机制所需的语言示例。
