# 操作系统学习资料

> 收录 5 节 12 篇 72 道通用操作系统题，按基础机制、文件系统、内存、进程、虚拟化和 Linux 启动组织。2026-10-06 自原 05-os 目录迁入并重整：`01-file/` 八册按主题合并为六册（三道命令实操题随 initramfs 语境移入 `05-linux-boot/`），全部补齐规范格式；本目录改名 `02-os/`。册的存废只看主题边界、不看题数——薄册在索引中标注扩写方向。Android 专属实现归 `../01-android/`；Java/JVM 语言与运行时知识按知识路由归 `../04-language/`。

## 推荐阅读顺序

1. 先读 `00-fundamentals/`，建立操作系统职责、运行机制和保护边界的基础认识。
2. 按兴趣学习 `01-file/`、`02-memory/` 和 `03-processes/` 中的文件、内存与进程机制。
3. 再读 `04-virtualization/`，理解虚拟机与宿主资源的关系。
4. 学习 Linux 进入用户态或制作 initramfs 时，读 `05-linux-boot/`。

## 目录与文件索引

### `00-fundamentals/`：操作系统基础（1 篇，8 题）

- `01-os-fundamentals.md`（8 题）：操作系统职责、运行特征、用户态与内核态、系统调用与中断/陷阱、内核组织结构、存储层次与局部性。

### `01-file/`：文件与文件系统（6 篇，21 题）

- `01-files-and-directories.md`（6 题）：文件属性与四种管理的分工、逻辑与物理结构、连续/链接/索引分配、目录结构演进、路径解析、目录项与索引节点分离。
- `02-file-operations-model.md`（4 题）：open/read/close 阶段划分、两级打开文件表、open 与 read 的语义边界、复制为何不是原子操作。
- `03-storage-space.md`（5 题）：空闲表/盘块链/盘区链、位视图换算、成组链接法、伙伴系统、slab 分配器。
- `04-sharing-and-protection.md`（4 题）：口令保护/加密/ACL 的安全边界、共享目录项的删除语义、硬链接与符号链接、ACL 与能力模型。
- `05-vfs-mounting.md`（3 题）：VFS 统一接口与分派、vnode 与 inode 辨析、挂载后的路径查找。扩写方向：mount namespace、overlayfs（与 Android 前后衔接）。
- `06-filesystem-layout.md`（3 题）：分层实现、物理/逻辑格式化与挂载的层次、磁盘与内存中的信息分布。

### `02-memory/`：内存（2 篇，12 题）

- `01-foundation.md`（2 题）：闪存与 RAM 的区别，以及 Android 中持久化存储和运行内存的用途。
- `02-memory-management.md`（10 题）：地址空间、分配、分页与分段、虚拟内存、进程映像和内存映射。

### `03-processes/`：进程与调度（1 篇，12 题）

- `01-processes-and-scheduling.md`（12 题）：程序与进程、进程状态和控制、线程、fork/COW 与 exec、僵尸与孤儿、调度层次与策略、上下文切换成本。

### `04-virtualization/`：虚拟化（1 篇，1 题）

- `01-virtualization.md`（1 题）：第一类与第二类虚拟机监控程序的运行位置和资源访问路径。扩写方向：pKVM、virtio 与 Android AVF 的通用机制侧。

### `05-linux-boot/`：Linux 启动与早期用户态（1 篇，12 题）

- `01-linux-boot.md`（12 题）：initramfs 的组成、`/init` 与控制台输出链路、QEMU 串口排查、initramfs 归档检查，以及制作 rootfs 的文件实操命令（创建、权限、`mknod` 与 `mount()`，2026-10-06 自 01-file 迁入）。

## 内容边界

- 本目录收录通用操作系统机制和 Linux 通用启动知识。
- 文件操作命令实操集中在 `05-linux-boot/`（initramfs 制作语境）；VFS 与挂载模型集中在 `01-file/05-vfs-mounting.md`。
- Android 内核/GKI、Android 启动镜像与分区布局等平台专属实现，归 `../01-android/` 对应专题。
- C++/Java 等语言语法和运行时知识，归 `../04-language/` 对应专题；这里只保留操作系统机制所需的语言示例。
