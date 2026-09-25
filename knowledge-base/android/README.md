# Android 学习资料目录

> 学习资料目录（不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。《android-internals-wiki》全书已于 2026-09-25 按 session-to-knowledge 文章模式全量沉淀为本目录下的 Q&A 学习资料：机制类结论按本地 AAOS13 源码（Android 13）核对并标注与材料 Android 17 语境的版本差异，Q 序列即结构、供 atlas 同源直读；`architecture/01–10` 等早期文档为既往沉淀，予以保留。维护者：session-to-knowledge。

## 机制层（源书第一部分）

- [architecture/](01-architecture/) — 系统架构与系统服务：分层架构、启动链路、Binder、ART、显示/WM、Telephony/Connectivity、AVF、logd/BPF（11–19 为本轮新增）
- [rendering/](02-rendering/) — 渲染系统：渲染管线与 VSync 调度、GPU 合成与显示管线、多窗口折叠屏与显示服务
- [input/](03-input/) — 输入系统：分发与安全、触摸延迟、手势导航、IMM、外设输入
- [memory/](06-memory/) — 内存管理：内存全景与 GC、lmkd/Freezer、回收压缩、ZRAM、MTE、跨进程内存
- [cpu-power/](08-cpu-power/) — CPU 调度与能耗：EAS/DVFS/Thermal、后台任务、ADPF、端侧 AI、传感器与 LE Audio
- [storage/](04-storage/) — 存储与 I/O：存储架构、文件系统调度、SharedPreferences/DataStore、vold/FUSE

## 性能问题（源书第二部分）

- [performance/](07-performance/) — 流畅性、响应速度、ANR、内存性能、功耗、网络性能、渲染管线专题（基础与图形 API、跨框架与媒体）

## 工具与方法论（源书第三部分）

- [tools/](10-tools/) — Perfetto 采集与 SQL、进阶与 SDK、通用分析工具、GPU 与专项工具、性能方法论、APM 平台与专项原理、学习方法与检查清单

## 系统与厂商（源书第四部分）

- [system/](06-system/) — AOSP 性能优化（构建/AutoFDO/Profile 编译/启动耗时/Rust）、OEM 与设备差异（SoC、游戏模式、Power HAL、AAOS）

## 应用实践（源书第五部分）

- [app-practice/](09-app-practice/) — 稳定性治理（度量崩溃/资源泄漏/线程 IPC/Native 与 SDK）、启动优化、渲染实战（View 与 Compose/图像显示/媒体混合栈）、内存实践、I/O 与存储、网络与连接、功耗优化、CPU 与体积、可观测性（体系治理/线上诊断）

## 早期文档

- [framework/](../others/framework/) — Android 13 长文专题：addWindow 全链路、渲染架构解析、显示系统、电源、时间、硬按键
- 根级：[性能优化.md](../others/性能优化.md)、[Launcher3_Technical_Document.md](../others/Launcher3_Technical_Document.md)、[OTA_LIFECYCLE.md](../others/OTA_LIFECYCLE.md)、[MVVM_Optimization_Report.md](../others/MVVM_Optimization_Report.md)
