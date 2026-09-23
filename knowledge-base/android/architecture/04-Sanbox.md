**Q1: 应用沙箱依靠哪些机制实现？为什么说"进程边界就是安全边界"？**

应用沙箱把 Linux 的 UID 隔离、SELinux 强制访问控制和 seccomp-BPF 系统调用过滤叠在同一条边界上：每个应用默认独占一个 Linux UID 和一个进程，任何跨边界访问都必须经过显式 IPC 和权限检查。

1. **UID 隔离**：文件权限与进程凭据按 UID 判定，应用之间的数据目录、进程、内存默认互不可见（Zygote fork 后把子进程降权到目标应用的 UID）；
2. **SELinux**：Android 5.0 起全局 enforcing，每个进程运行在自己的域里，未显式允许即拒绝；
3. **seccomp-BPF**：Android 8.0 起限制应用可用的系统调用集合，收窄内核攻击面。

这套设计的直接推论：应用不能互相读取文件、不能互相 kill，共享数据必须走 ContentProvider 或 Binder 这类受控通道；`android:sharedUserId` 让两个应用合并 UID，等于主动拆掉沙箱，它已从 API 29 起废弃，新应用不应依赖。


**Q2: Android 中的 Sanbox 怎么理解？**

