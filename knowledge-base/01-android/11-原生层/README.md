# Android 平台原生层

> 收录 9 册、99 道 Android 平台原生题，覆盖内核与 GKI、驱动、原生 IPC、运行库、日志、BPF、Rust 和 Native 应用稳定性。Android 系统栈中 Framework 以下、由内核或原生组件实现的平台机制归入本目录；通用 Linux 进程、启动与驱动机制归 `knowledge-base/02-os/`，Android 产品构建与内核编译归 `12-构建系统/`。

1. [01-内核与GKI.md](01-内核与GKI.md)：Android Common Kernel、GKI/KMI、内核版本兼容、厂商模块、vendor hooks 与内核运行时特征（11 题）。
2. [02-驱动运行时.md](02-驱动运行时.md)：Android 设备驱动位置、访问、调试与测试（4 题）。
3. [03-Binder驱动.md](03-Binder驱动.md)：Binder 内核驱动、事务处理、缓冲区与观测（11 题）。
4. [04-共享内存.md](04-共享内存.md)：ashmem、ION 与 DMA-BUF 等共享内存机制（11 题）。
5. [05-Bionic链接器.md](05-Bionic链接器.md)：Bionic 动态链接器、库命名空间与符号解析（21 题）。
6. [06-日志服务.md](06-日志服务.md)：Android 日志调用、丢弃与 logcat 过滤边界（4 题）。
7. [07-BPF.md](07-BPF.md)：Android BPF 的可观测与可编程边界（7 题）。
8. [08-平台Rust.md](08-平台Rust.md)：Android 平台 Rust 与 FFI（6 题）。
9. [09-原生层稳定性.md](09-原生层稳定性.md)：Native 应用检测、Hook、动态库与 SDK 稳定性（24 题）。

**目录边界：**`01-内核与GKI.md` 保留在此处，因为它讨论 Android 公共内核和 GKI 的平台契约；Linux 通用启动概念不属于 GKI 专题。
