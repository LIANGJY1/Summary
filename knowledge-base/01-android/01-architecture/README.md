# Android 系统架构

> Android 平台机制地基：分层与进程边界、Binder/ART/沙箱/权限/SELinux 与包管理。本目录收录 10 篇资料、154 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)。Q 序列即结构，供 atlas 同源直读。

## 册

- [01-系统架构.md](01-系统架构.md)（18 题）— 五层架构与请求路径、ABI/API、Treble/GSI、16KB 页与 Mainline、进程回收角色、线程角色。
- [02-Binder.md](02-Binder.md)（23 题）— Binder 架构与一次拷贝、事务缓冲区限制、AIDL/HIDL 契约、oneway 排队、frozen 行为、工具链与故障形态。
- [03-SystemServer.md](03-SystemServer.md)（9 题）— system_server 进程构成、Watchdog 与恢复、AMS 双锁、WMS/SF 分工。
- [04-HAL.md](04-HAL.md)（5 题）— HAL 运行形态、VINTF 兼容、服务启动注册链路、Stable AIDL 版本冻结。
- [05-ART运行时.md](05-ART运行时.md)（20 题）— ART 运行时与编译、oat/vdex 语义、运行时 fatal 形态。
- [06-JNI.md](06-JNI.md)（8 题）— 静态/动态注册、引用与线程规则、@FastNative/@CriticalNative、native 崩溃符号化。
- [07-应用包管理.md](07-应用包管理.md)（18 题）— AAB 到 APK 的安装会话四阶段、dexopt 编排、增量与分阶段安装、应用归档、排查取证。
- [08-应用沙箱.md](08-应用沙箱.md)（16 题）— UID/SELinux/seccomp 三层沙箱、多用户 UID 段位、Zygote specialize 降权链、sharedUserId 与隔离进程。
- [09-权限系统.md](09-权限系统.md)（10 题）— protectionLevel 组合、运行时权限存储与写盘、撤销杀进程、AppOps 分工、check 系列语义。
- [10-SELinux.md](10-SELinux.md)（27 题）— avc 拒绝定位与四元组、策略分层与落点、service_manager 用户态检查链、allowxperm、dontaudit、工作模式。

系统启动链、SystemServer 启动阶段与 Zygote 预加载见 [系统启动](../02-系统启动/README.md)。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
