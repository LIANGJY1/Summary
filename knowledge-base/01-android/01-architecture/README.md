# Android 系统架构

> Android 平台机制地基：分层与进程边界、完整启动链、Binder/ART/沙箱/权限/SELinux 与包管理。本目录收录 11 篇资料、257 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)Q 序列即结构，供 atlas 同源直读。

## 册

- [01-系统架构.md](01-系统架构.md)（18 题）— 五层架构与请求路径、ABI/API、Treble/GSI、16KB 页与 Mainline、进程回收角色、线程角色。
- [02-系统启动.md](02-系统启动.md)（98 题）— 上电到桌面的完整启动链：init/rc/Zygote/system_server、镜像与 AVB、A/B slot、init 机制、启动度量与车机 ACC/Car API 时序。
- [03-Binder.md](03-Binder.md)（23 题）— Binder 架构与一次拷贝、事务缓冲区限制、AIDL/HIDL 契约、oneway 排队、frozen 行为、工具链与故障形态。
- [04-SystemServer.md](04-SystemServer.md)（11 题）— system_server 进程构成、四波装配与 BootPhase、Watchdog 与恢复、AMS 双锁、WMS/SF 分工。
- [05-HAL.md](05-HAL.md)（5 题）— HAL 运行形态、VINTF 兼容、服务启动注册链路、Stable AIDL 版本冻结。
- [06-ART运行时.md](06-ART运行时.md)（22 题）— ART 产物识别与选择、oat/vdex 语义、运行时 fatal 形态。
- [07-JNI.md](07-JNI.md)（8 题）— 静态/动态注册、引用与线程规则、@FastNative/@CriticalNative、native 崩溃符号化。
- [08-应用包管理.md](08-应用包管理.md)（18 题）— AAB 到 APK 的安装会话四阶段、dexopt 编排、增量与分阶段安装、应用归档、排查取证。
- [09-应用沙箱.md](09-应用沙箱.md)（16 题）— UID/SELinux/seccomp 三层沙箱、多用户 UID 段位、Zygote specialize 降权链、sharedUserId 与隔离进程。
- [10-权限系统.md](10-权限系统.md)（10 题）— protectionLevel 组合、运行时权限存储与写盘、撤销杀进程、AppOps 分工、check 系列语义。
- [11-SELinux.md](11-SELinux.md)（28 题）— avc 拒绝定位与四元组、策略分层与落点、service_manager 用户态检查链、allowxperm、dontaudit、工作模式。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
