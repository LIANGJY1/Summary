# Android 学习资料目录

> 学习资料目录（不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。《android-internals-wiki》全书已于 2026-09-25 按 session-to-knowledge 文章模式全量沉淀为本目录下的 Q&A 学习资料：机制类结论按本地 AAOS13 源码（Android 13）核对并标注与材料 Android 17 语境的版本差异，Q 序列即结构、供 atlas 同源直读；`architecture/01–10` 等早期文档为既往沉淀，予以保留。yadi 车机项目《git 提交与缺陷分析》已于 2026-09-26 沉淀为 [11-defects/](11-defects/) 九册。维护者：session-to-knowledge。

## 面试冲刺

- [面试高频索引](面试高频索引.md) — 高频面试主题 → 册·Q 速查（★ 必备 / ★★ 高频 / ★★★ 加分，AAOS 专项段落）

## 机制层（源书第一部分）

- [architecture/](01-architecture/) — 系统架构与系统服务：分层架构、启动链路、Binder、ART、显示/WM、Telephony/Connectivity、AVF、logd/BPF（11–19 为本轮新增）
- [rendering/](02-rendering/) — 渲染系统：渲染管线与 VSync 调度、GPU 合成与显示管线、多窗口折叠屏与显示服务
- [input/](03-input/) — 输入系统：分发与安全、触摸延迟、手势导航、IMM、外设输入
- [memory/](05-memory/) — 内存管理：内存全景与 GC、lmkd/Freezer、回收压缩、ZRAM、MTE、跨进程内存
- [cpu-power/](08-cpu-power/) — CPU 调度与能耗：EAS/DVFS/Thermal、后台任务、ADPF、端侧 AI、传感器与 LE Audio
- [storage/](04-storage/) — 存储与 I/O：存储架构、文件系统调度、SharedPreferences/DataStore、vold/FUSE

## 性能问题（源书第二部分）

- [performance/](07-performance/) — 流畅性、响应速度、ANR、内存性能、功耗、网络性能、渲染管线专题（基础与图形 API、跨框架与媒体）

## 工具与方法论（源书第三部分）

- [tools/](10-tools/) — Perfetto 采集与 SQL、进阶与 SDK、通用分析工具、GPU 与专项工具、性能方法论、APM 平台与专项原理、学习方法与检查清单

## 系统与厂商（源书第四部分）

- [system/](06-system/) — AOSP 性能优化（构建/AutoFDO/Profile 编译/启动耗时/Rust）、OEM 与设备差异（SoC、游戏模式、Power HAL、AAOS）、CarService 服务速览（媒体源/蓝牙/遥测/诊断/投影）

## 应用实践（源书第五部分）

- [app-practice/](09-app-practice/) — 稳定性治理（度量崩溃/资源泄漏/线程 IPC/Native 与 SDK）、启动优化、渲染实战（View 与 Compose/图像显示/媒体混合栈）、内存实践、I/O 与存储、网络与连接、功耗优化、CPU 与体积、可观测性（体系治理/线上诊断）

## 缺陷复盘（yadi 项目）

- [11-defects/](11-defects/) — 某车机项目 85 天 1010 条提交、761 条缺陷修复的复盘沉淀：项目缺陷画像与复盘方法、车控信号语义、主线程与异步时序、蓝牙机制、Kanzi 双端状态同步、UI 还原与主题适配、状态缓存与启动时序、崩溃防护与偶现排查、提交治理与防回归

## 早期文档

- [framework/](../others/framework/) — Android 13 长文专题：addWindow 全链路、渲染架构解析、显示系统、电源、时间、硬按键
- 根级：[性能优化.md](../others/性能优化.md)、[Launcher3_Technical_Document.md](../others/Launcher3_Technical_Document.md)、[OTA_LIFECYCLE.md](../others/OTA_LIFECYCLE.md)、[MVVM_Optimization_Report.md](../others/MVVM_Optimization_Report.md)

## 全册速览（2026-09-25 快照，题数随修订变化）

| 册 | 标题 | 题数 | 主线 |
| --- | --- | ---: | --- |
| [01-architecture/01-Android系统架构.md](01-architecture/01-Android系统架构.md) | Android 系统架构 | 16 |  |
| [01-architecture/02-Android系统启动流程.md](01-architecture/02-Android系统启动流程.md) | Android 系统启动流程 | 43 |  |
| [01-architecture/03-Binder.md](01-architecture/03-Binder.md) | Binder | 6 |  |
| [01-architecture/04-Sanbox.md](01-architecture/04-Sanbox.md) | 应用沙箱 | 4 |  |
| [01-architecture/05-Art.md](01-architecture/05-Art.md) | ART | 5 |  |
| [01-architecture/06-JNI.md](01-architecture/06-JNI.md) | JNI | 3 |  |
| [01-architecture/07-Android分区.md](01-architecture/07-Android分区.md) | Android 分区 | 3 |  |
| [01-architecture/08-SystemServer.md](01-architecture/08-SystemServer.md) | SystemServer | 2 |  |
| [01-architecture/09-HAL.md](01-architecture/09-HAL.md) | HAL | 3 |  |
| [01-architecture/10-Kernel.md](01-architecture/10-Kernel.md) | 公共内核与 GKI | 4 |  |
| [01-architecture/11-版本演进与图形栈预加载.md](01-architecture/11-版本演进与图形栈预加载.md) | 版本演进与图形栈预加载 | 14 |  |
| [01-architecture/12-类加载ART编译与JNI链接.md](01-architecture/12-类加载ART编译与JNI链接.md) | 类加载、ART 编译与 JNI 链接 | 16 |  |
| [01-architecture/13-MessageQueue锁竞争与Binder深化.md](01-architecture/13-MessageQueue锁竞争与Binder深化.md) | MessageQueue 锁竞争与 Binder 深化 | 19 |  |
| [01-architecture/14-系统服务调度核心.md](01-architecture/14-系统服务调度核心.md) | 系统服务调度核心 | 17 |  |
| [01-architecture/15-安装归档与资源配置.md](01-architecture/15-安装归档与资源配置.md) | 安装归档与资源配置 | 13 |  |
| [01-architecture/16-显示窗口与音频链路.md](01-architecture/16-显示窗口与音频链路.md) | 显示窗口与音频链路 | 12 |  |
| [01-architecture/17-Telephony与Connectivity.md](01-architecture/17-Telephony与Connectivity.md) | Telephony 与 Connectivity | 12 |  |
| [01-architecture/18-Notification-Biometric-Location.md](01-architecture/18-Notification-Biometric-Location.md) | 通知、生物识别与位置系统服务链路 | 16 |  |
| [01-architecture/19-AVF可观测与AI手机技术栈.md](01-architecture/19-AVF可观测与AI手机技术栈.md) | AVF 虚拟化、logd 日志、BPF 与端侧 AI 技术栈 | 16 |  |
| [01-architecture/20-SELinux.md](01-architecture/20-SELinux.md) | Android SELinux | 8 |  |
| [02-rendering/01-渲染管线与VSync调度.md](02-rendering/01-渲染管线与VSync调度.md) | 渲染管线与 VSync 调度 | 30 |  |
| [02-rendering/02-GPU合成与显示管线.md](02-rendering/02-GPU合成与显示管线.md) | GPU 合成与显示管线 | 30 |  |
| [02-rendering/03-多窗口折叠屏与显示服务.md](02-rendering/03-多窗口折叠屏与显示服务.md) | 多窗口、折叠屏与显示服务 | 25 |  |
| [03-input/01-输入系统.md](03-input/01-输入系统.md) | Android 输入系统：分发、延迟与安全边界 | 26 |  |
| [04-storage/01-存储与IO.md](04-storage/01-存储与IO.md) | 存储与 I/O：架构分层、文件系统调度与配置持久化 | 22 |  |
| [05-memory/01-内存管理与压力治理.md](05-memory/01-内存管理与压力治理.md) | Android 内存管理与压力治理 | 24 |  |
| [05-memory/02-回收压缩与专项内存.md](05-memory/02-回收压缩与专项内存.md) | 回收压缩与专项内存 | 25 |  |
| [06-system/01-AOSP性能优化.md](06-system/01-AOSP性能优化.md) | AOSP 性能优化 | 31 |  |
| [06-system/02-OEM与设备差异.md](06-system/02-OEM与设备差异.md) | OEM 与设备差异 | 41 |  |
| [06-system/03-CarService服务速览.md](06-system/03-CarService服务速览.md) | CarService 服务速览 | 9 |  |
| [07-performance/01-流畅性.md](07-performance/01-流畅性.md) | 流畅性：卡顿定义、分析方法与系统链路 | 29 |  |
| [07-performance/02-响应速度.md](07-performance/02-响应速度.md) | 响应速度：从输入到反馈的延迟分析与专项优化 | 30 |  |
| [07-performance/03-ANR.md](07-performance/03-ANR.md) | ANR：超时契约、诊断与预警 | 28 |  |
| [07-performance/04-内存性能.md](07-performance/04-内存性能.md) | 内存性能：增长归因、低内存影响与抖动诊断 | 23 |  |
| [07-performance/05-功耗.md](07-performance/05-功耗.md) | Android 功耗：模型、归因与 App 优化 | 25 |  |
| [07-performance/06-网络性能.md](07-performance/06-网络性能.md) | Android 网络性能：请求分段、TLS 与 DNS 诊断 | 20 |  |
| [07-performance/07-渲染管线-基础与图形API.md](07-performance/07-渲染管线-基础与图形API.md) | 渲染管线专题：出图分型与图形 API | 28 |  |
| [07-performance/08-渲染管线-跨框架与媒体.md](07-performance/08-渲染管线-跨框架与媒体.md) | 渲染管线专题：跨框架与媒体管线 | 35 |  |
| [08-cpu-power/01-调度与功耗框架.md](08-cpu-power/01-调度与功耗框架.md) | 调度与功耗框架 | 25 |  |
| [08-cpu-power/02-能效专项.md](08-cpu-power/02-能效专项.md) | 能效专项：LLM DVFS、传感器批处理、CPU Cache 与 LE Audio | 22 |  |
| [09-app-practice/01-稳定性治理-度量与崩溃.md](09-app-practice/01-稳定性治理-度量与崩溃.md) | 稳定性治理：度量、崩溃与 ANR | 25 |  |
| [09-app-practice/02-稳定性治理-资源泄漏.md](09-app-practice/02-稳定性治理-资源泄漏.md) | 稳定性治理：资源泄漏与进程恢复 | 25 |  |
| [09-app-practice/03-稳定性治理-线程与IPC.md](09-app-practice/03-稳定性治理-线程与IPC.md) | 稳定性治理：线程、协程与 IPC | 20 |  |
| [09-app-practice/04-稳定性治理-Native与SDK.md](09-app-practice/04-稳定性治理-Native与SDK.md) | 稳定性治理：Native 检测、Hook、动态库与 SDK | 24 |  |
| [09-app-practice/05-启动优化.md](09-app-practice/05-启动优化.md) | 启动优化：应用侧启动治理 | 34 |  |
| [09-app-practice/06-渲染实战-View与Compose基础.md](09-app-practice/06-渲染实战-View与Compose基础.md) | 渲染实战：View 与 Compose 基础 | 24 |  |
| [09-app-practice/07-渲染实战-Compose进阶.md](09-app-practice/07-渲染实战-Compose进阶.md) | 渲染实战：Compose 进阶 | 24 |  |
| [09-app-practice/08-渲染实战-图像显示与页面.md](09-app-practice/08-渲染实战-图像显示与页面.md) | 渲染实战：图像显示、帧率监控与页面切换 | 26 |  |
| [09-app-practice/09-渲染实战-媒体与混合栈.md](09-app-practice/09-渲染实战-媒体与混合栈.md) | 渲染优化实战：Vulkan/Impeller、WebView、Media3、CameraX、App Widget 与系统取色 | 24 |  |
| [09-app-practice/10-内存实践.md](09-app-practice/10-内存实践.md) | 内存实践：堆预算、泄漏治理、Native 排查与线上监控 | 32 |  |
| [09-app-practice/11-IO与存储实践.md](09-app-practice/11-IO与存储实践.md) | I/O 与存储实践：文件、数据库、缓存、媒体与网络 | 32 |  |
| [09-app-practice/12-网络与连接实践.md](09-app-practice/12-网络与连接实践.md) | 网络与连接实践：HTTPDNS、选网、配额与近场连接治理 | 28 |  |
| [09-app-practice/13-功耗优化实践.md](09-app-practice/13-功耗优化实践.md) | 功耗优化实践：诊断取证与 App 侧治理 | 29 |  |
| [09-app-practice/14-CPU与体积优化.md](09-app-practice/14-CPU与体积优化.md) | CPU 与体积优化 | 27 |  |
| [09-app-practice/15-可观测性-体系与治理.md](09-app-practice/15-可观测性-体系与治理.md) | 可观测性体系与治理 | 27 |  |
| [09-app-practice/16-可观测性-线上诊断.md](09-app-practice/16-可观测性-线上诊断.md) | 可观测性线上诊断 | 33 |  |
| [10-tools/01-Perfetto-采集与SQL分析.md](10-tools/01-Perfetto-采集与SQL分析.md) | Perfetto 采集与 SQL 分析 | 35 |  |
| [10-tools/02-Perfetto-进阶与SDK.md](10-tools/02-Perfetto-进阶与SDK.md) | Perfetto 进阶：Profile 火焰图、CPU 频率、BufferQueue、Agent 协议、SDK 与 FrameTimeline | 30 |  |
| [10-tools/03-性能分析工具.md](10-tools/03-性能分析工具.md) | 性能分析工具 | 36 |  |
| [10-tools/04-GPU与专项工具.md](10-tools/04-GPU与专项工具.md) | GPU 与专项工具 | 30 |  |
| [10-tools/05-性能方法论.md](10-tools/05-性能方法论.md) | 性能方法论 | 33 |  |
| [10-tools/06-APM-平台与SDK.md](10-tools/06-APM-平台与SDK.md) | APM 平台与 SDK | 30 |  |
| [10-tools/07-APM-专项原理与架构.md](10-tools/07-APM-专项原理与架构.md) | APM 专项原理与架构 | 32 |  |
| [10-tools/08-学习方法与检查清单.md](10-tools/08-学习方法与检查清单.md) | 学习方法与检查清单 | 8 |  |
| [11-defects/00-项目缺陷画像与复盘方法.md](11-defects/00-项目缺陷画像与复盘方法.md) | 项目缺陷画像与复盘方法 | 17 |  |
| [11-defects/01-车控信号语义.md](11-defects/01-车控信号语义.md) | 车控信号语义 | 24 |  |
| [11-defects/02-主线程与异步时序.md](11-defects/02-主线程与异步时序.md) | 主线程与异步时序 | 26 |  |
| [11-defects/03-蓝牙机制.md](11-defects/03-蓝牙机制.md) | 蓝牙机制 | 31 |  |
| [11-defects/04-Kanzi双端状态同步.md](11-defects/04-Kanzi双端状态同步.md) | Kanzi 双端状态同步 | 17 |  |
| [11-defects/05-UI还原与主题适配.md](11-defects/05-UI还原与主题适配.md) | UI 还原与主题适配 | 29 |  |
| [11-defects/06-状态缓存与启动时序.md](11-defects/06-状态缓存与启动时序.md) | 状态缓存与启动时序 | 22 |  |
| [11-defects/07-崩溃防护与偶现排查.md](11-defects/07-崩溃防护与偶现排查.md) | 崩溃防护与偶现排查 | 20 |  |
| [11-defects/08-提交治理与防回归.md](11-defects/08-提交治理与防回归.md) | 提交治理与防回归 | 20 |  |
