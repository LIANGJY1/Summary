# Android 学习资料目录

> 学习资料目录（不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。《android-internals-wiki》全书已于 2026-09-25 按 session-to-knowledge 文章模式全量沉淀为本目录下的 Q&A 学习资料：机制类结论按本地 AAOS13 源码（Android 13）核对并标注与材料 Android 17 语境的版本差异，Q 序列即结构、供 atlas 同源直读；`architecture/01–10` 等早期文档为既往沉淀，予以保留。yadi 车机项目《git 提交与缺陷分析》已于 2026-09-26 沉淀为 [11-defects/](11-defects/) 九册。维护者：session-to-knowledge。

## 面试冲刺

- [面试高频索引](面试高频索引.md) — 高频面试主题 → 册·Q 速查（★ 必备 / ★★ 高频 / ★★★ 加分，AAOS 专项段落）

## 机制层（源书第一部分）

- [architecture/](01-architecture/) — 系统架构与系统服务：分层架构、启动链路、Binder、ART、显示/WM、Telephony/Connectivity、AVF、logd/BPF（11–19 为本轮新增）
- [rendering/](02-rendering/) — 渲染系统：渲染管线与 VSync 调度、GPU 合成与显示管线、多窗口折叠屏与显示服务
- [input/](03-input/) — 输入系统：分发与安全、触摸延迟、手势导航、IMM、外设输入
- [memory/](05-memory/) — 内存管理：内存全景与 GC、lmkd/Freezer、回收压缩、ZRAM、MTE、跨进程内存
- [cpu-power/](08-cpu-power/) — CPU 调度与能耗：EAS/DVFS/Thermal、后台任务、ADPF、端侧 AI、传感器批处理与 CPU Cache
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

## 平台原生层（外部资料，证据等级：二手）

- [12-platform-native/](12-platform-native/) — **证据等级低于 01–11 册**：来自官方 `source.android.com` 文档、AOSP/Soong 源码与中文社区源码分析，未经本地源码逐条核对。补 01–11 册未覆盖的内核与原生层：Binder 驱动的四组核心对象与"一次拷贝"成立条件、ashmem 的 pin/unpin 与 LRU 回收、ION 到 DMA-BUF heap 迁移、GKI 边界；Bionic 动态链接器的命名空间隔离与符号解析；aconfig 特性开关的声明、代码生成与运行期存储

## 音频子系统（AOSP + AAOS）

- [13-audio/](13-audio/) — 音频专项（2026-09-28 新增并全网扩写，机制按本地 AAOS13 源码核对；16 册音频部分、08-cpu-power 的 LE Audio 题与 09-app-practice 的 Offload 题已并入本目录）：[01-AOSP音频子系统](13-audio/01-AOSP音频子系统.md)——audioserver、输出线程选型、策略引擎、混音管线与 FastMixer/NBAIO、音量与效果链、AAudio 服务端内部、时间戳与 TeeSink；[02-AAOS车机音频](13-audio/02-AAOS车机音频.md)——多音区配置、context 路由、落区判定、硬件增益、焦点矩阵与延迟焦点、duck/mute、HalAudioFocus、AudioControl 版本差异、13/14 版本边界；[03-音频延迟与应用实践](13-audio/03-音频延迟与应用实践.md)——延迟口径与实测、FAST 轨、AAudio 回退与缓冲调优、xrun、Offload 取舍、AudioTrack/SoundPool 语义；[04-蓝牙音频](13-audio/04-蓝牙音频.md)——蓝牙延迟实测、LC3、单播/广播、LE Audio、ASHA/HAP、A2DP/SCO 切换与绝对音量；[05-手机侧音频焦点与路由](13-audio/05-手机侧音频焦点与路由.md)——焦点栈仲裁、丢失处理、音量滑条与 usage、BECOMING_NOISY、通信路由、并发录音与隐私

- [14-network/](14-network/) — 网络专项（2026-09-28 新增）：[01-Android网络框架](14-network/01-Android网络框架.md)——"已连接"分层语义、默认网络评分与切换、多网络绑定、captive portal 验证、NetworkAgent、DNS 与 Private DNS、策略路由、车机以太网；[02-车机多APN与虚拟网卡](14-network/02-车机多APN与虚拟网卡.md)——多 APN 与 rmnet、veth+SNAT 业务隔离拓扑（项目实例已脱敏）、网卡绑定两套机制、DNS 分流与五段定位、双公网切换、APN 定制链与流量策略；[03-Android-VPN](14-network/03-Android-VPN.md)——VpnService tun 数据面、protect 防回环、分应用、always-on/lockdown、平台 IKEv2、与多 APN 共存、配置存储与诊断（V1.0，2026-09-28）；[04-蜂窝数据与无线连接](14-network/04-蜂窝数据与无线连接.md)——APN 类型与配置链、DataNetwork 模型、数据开关分层、蜂窝验证器、热点共享、Wi-Fi 编程面、NSC、网络库切换坑；[05-车载网络架构与设计](14-network/05-车载网络架构与设计.md)——E/E 分域与座舱职责、车载以太网骨干与 TSN/gPTP、时间同步三层、域融合演进、跨域隔离、mDNS 服务发现、eBPF 流量统计与计费、多路径传输、长连接推送、远程运维通道；[06-网络排查工具与实践](14-network/06-网络排查工具与实践.md)——抓包权限边界、tcpdump SLL 语义与过滤器、ip/ss 工具族、无 root 取证、Perfetto 网络轨迹、ICMP 工具误读、DNS 诊断替代、弱网模拟测试矩阵；[07-车机网络安全](14-network/07-车机网络安全.md)——攻击面盘点、指令防重放与完整性、传输安全基线、固件下发校验与防降级、量产调试接口治理、检测与响应（V1.0，2026-09-28）；[08-应用网络编程与系统约束](14-network/08-应用网络编程与系统约束.md)——Doze/App Standby 网络限制、WorkManager 网络约束、HTTPS 校验失败分类与时钟坑、IPv6 双栈 socket 坑、车机 eSIM/LPA、连接竞速、网络指标监控体系；[09-传输细节与协议设计](14-network/09-传输细节与协议设计.md)——TCP 粘包与协议定界（长度前缀/TLV）、Nagle 与延迟 ACK、keepalive 参数真相、证书链不完整事故、拥塞控制启示、字节序与版本兼容、车云通道选型、热点网段冲突、TCP 半开假在线、DNS 劫持与 HTTPDNS、流量控制与零窗口、UDP 选型、TIME_WAIT、ARP 排查、HTTP·2 取舍、多通道保序、连接池 key、0-RTT、HTTP 缓存；[10-网络分层原理与地基机制](14-network/10-网络分层原理与地基机制.md)——分层模型与包的旅行、DNS 层级与递归、TLS 握手、TCP 状态机、IP 分片、NAT 与 conntrack、全链路走读、分层校验

## UI 专题（AOSP + AAOS）

- [15-ui/](15-ui/) — UI 专题（2026-09-29 新增并深化，8 册 125 题；AOSP 机制按本地 AAOS13 源码核对，Car App Library / CarUxRestrictions / Power Policy 按官方文档口径，2026-09 检索）：[01-窗口系统与WMS](15-ui/01-窗口系统与WMS.md)——窗口三副面孔、addWindow 校验与返回码、relayout 与遍历调度分工、WindowState/Token/DisplayContent、z 序与 surface placement、Insets 体系与 WindowInsetsController、Configuration 两条路径、IME 适配、多窗口与 letterbox、窗口不显示排查；[02-Activity与窗口生命周期](15-ui/02-Activity与窗口生命周期.md)——启动到首帧时序、四个可观测时点、setContentView 与 DecorView、透明主题尺寸陷阱、relaunch vs recreate、状态保存、启动模式与任务栈、切换动画双窗口、首帧度量、onTrimMemory、车机多用户归属、时序踩坑、Fragment 生命周期与视图分离、三种切换语义、commit 三态与状态保存、ViewModel 作用域；[03-View测量布局与绘制](15-ui/03-View测量布局与绘制.md)——三趟管线、MeasureSpec 与尺寸契约、layout vs onLayout、requestLayout vs invalidate、display list 与"不 invalidate 就不重绘"、脏区传播、绘制顺序与裁剪、onDraw 分配、图层类型、SurfaceView vs TextureView、属性动画与布局动画；[04-资源主题与多屏适配](15-ui/04-资源主题与多屏适配.md)——限定符优先级与顺序、默认资源兜底、版本限定符、深色模式三件事、动态取色取舍、主题属性与 RRO 定制契约、dp/sp/fontScale、限定符≠窗口尺寸、车机多屏分歧、RTL、ViewBinding 联合字段缺失、Configuration 变更纪律、生效变体探针；[05-Compose运行期与跨栈互操作](15-ui/05-Compose运行期与跨栈互操作.md)——组合期写状态、remember/rememberSaveable、derivedStateOf、稳定性推断与 strong skipping、延迟读取、列表键、三个 Effect、ComposeView 组合策略、AndroidView 代价、自定义宿主帧时钟、重组归因工具、迁移坑；[06-AAOS车机UI架构与CarService](15-ui/06-AAOS车机UI架构与CarService.md)——UI 四层与定制点、CarService 与 car-lib、车机 Launcher、模板 vs 原生两条路线、CarAppService 注册契约与 Car App API level、车机 SystemUI、应用焦点、occupant zone 座位-显示-用户映射、多用户、CarPowerManager 与 power policy、日夜模式、旋钮与自定义输入、仪表通道、投影共存、调试与特性开关；[07-车机交互安全与驾驶分心](15-ui/07-车机交互安全与驾驶分心.md)——分心约束来源、驾驶状态判定、car_ux_restrictions_map.xml 与自动提升规则、restriction mode、消费姿势、distractionOptimized 声明、全限制兜底、config_ignore_ux_restrictions、乘员屏触控锁定、限制变化处理、内容级限制、语音与物理控件、验证方法、其他安全约束；[08-UI疑难排查与体验踩坑](15-ui/08-UI疑难排查与体验踩坑.md)——五层归因、界面没出来/白屏判定、掉帧阶段归因与 FrameMetrics 字段、抓帧工具集与命令归属、跳过绘制原因字段、重绘过大治理、insets 遮挡解法、输入超时与 UI 阻塞、渲染内存、SurfaceView 黑区、车机多显示与电源策略故障、回归机制

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
| [01-architecture/16-显示与窗口链路.md](01-architecture/16-显示与窗口链路.md) | 显示与窗口链路 | 6 |  |
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
| [06-system/02-OEM与设备差异.md](06-system/02-OEM与设备差异.md) | OEM 与设备差异 | 40 |  |
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
| [08-cpu-power/02-能效专项.md](08-cpu-power/02-能效专项.md) | 能效专项：LLM DVFS、传感器批处理与 CPU Cache | 17 |  |
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
| [09-app-practice/13-功耗优化实践.md](09-app-practice/13-功耗优化实践.md) | 功耗优化实践：诊断取证与 App 侧治理 | 27 |  |
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
| [11-defects/00-项目缺陷画像与复盘方法.md](11-defects/00-项目缺陷画像与复盘方法.md) | 项目缺陷画像与复盘方法 | 18 |  |
| [11-defects/01-车控信号语义.md](11-defects/01-车控信号语义.md) | 车控信号语义 | 24 |  |
| [11-defects/02-主线程与异步时序.md](11-defects/02-主线程与异步时序.md) | 主线程与异步时序 | 27 |  |
| [11-defects/03-蓝牙机制.md](11-defects/03-蓝牙机制.md) | 蓝牙机制 | 31 |  |
| [11-defects/04-Kanzi双端状态同步.md](11-defects/04-Kanzi双端状态同步.md) | Kanzi 双端状态同步 | 18 |  |
| [11-defects/05-UI还原与主题适配.md](11-defects/05-UI还原与主题适配.md) | UI 还原与主题适配 | 31 |  |
| [11-defects/06-状态缓存与启动时序.md](11-defects/06-状态缓存与启动时序.md) | 状态缓存与启动时序 | 26 |  |
| [11-defects/07-崩溃防护与偶现排查.md](11-defects/07-崩溃防护与偶现排查.md) | 崩溃防护与偶现排查 | 20 |  |
| [11-defects/08-提交治理与防回归.md](11-defects/08-提交治理与防回归.md) | 提交治理与防回归 | 20 |  |
| [12-platform-native/01-内核与原生层.md](12-platform-native/01-内核与原生层.md) | 内核与原生层（二手） | 20 | Binder 驱动 / ashmem / ION→DMA-BUF / GKI |
| [12-platform-native/02-Bionic链接器与命名空间.md](12-platform-native/02-Bionic链接器与命名空间.md) | Bionic 链接器与命名空间（二手） | 17 | 命名空间隔离 / 符号解析 / dlopen 流程 |
| [12-platform-native/03-aconfig特性开关.md](12-platform-native/03-aconfig特性开关.md) | aconfig 特性开关（二手） | 14 | 声明 / codegen 模板 / aconfigd 存储 |
| [13-audio/01-AOSP音频子系统.md](13-audio/01-AOSP音频子系统.md) | AOSP 音频子系统 | 17 | audioserver / 混音线程选型 / 混音管线与 FastMixer / 策略引擎 / 音量与效果链 |
| [13-audio/02-AAOS车机音频.md](13-audio/02-AAOS车机音频.md) | AAOS 车机音频 | 15 | 多音区配置 / context 路由 / 硬件增益 / 焦点矩阵 / duck·mute / AudioControl 版本 |
| [13-audio/03-音频延迟与应用实践.md](13-audio/03-音频延迟与应用实践.md) | 音频延迟与应用实践 | 11 | 延迟口径 / FAST 轨 / AAudio 回退与调优 / xrun / Offload / AudioTrack·SoundPool 语义 |
| [13-audio/04-蓝牙音频.md](13-audio/04-蓝牙音频.md) | 蓝牙音频 | 8 | 蓝牙延迟实测 / LC3 / 单播广播 / LE Audio / ASHA·HAP / SCO 切换与绝对音量 |
| [13-audio/05-手机侧音频焦点与路由.md](13-audio/05-手机侧音频焦点与路由.md) | 手机侧音频焦点与路由 | 7 | 焦点栈 / 丢失处理 / 音量滑条 / BECOMING_NOISY / 通信路由 / 并发录音与隐私 |
| [14-network/01-Android网络框架.md](14-network/01-Android网络框架.md) | Android 网络框架 | 16 | 已连接语义 / 评分切换 / 多网络绑定 / captive portal / NetworkAgent / 按需拉网 / DNS / 策略路由 / 计费网络 / 系统代理 PAC |
| [14-network/02-车机多APN与虚拟网卡.md](14-network/02-车机多APN与虚拟网卡.md) | 车机多 APN 与虚拟网卡 | 19 | 多 APN 与 rmnet / veth+SNAT / 网卡绑定 / DNS 分流五段定位 / 双公网 / APN 定制链与错误码 / IPv6-only·CLAT / OTA 下载 / 车云 MQTT 可靠会话 |
| [14-network/03-Android-VPN.md](14-network/03-Android-VPN.md) | Android VPN | 10 | tun 数据面 / Builder 路由 / protect / 分应用 / always-on / IKEv2 / 多 APN 共存 / MTU 分片黑洞 |
| [14-network/04-蜂窝数据与无线连接.md](14-network/04-蜂窝数据与无线连接.md) | 蜂窝数据与无线连接 | 17 | APN 类型与配置链 / DataNetwork / 数据开关 / 热点共享 / Wi-Fi 编程面与企业 802.1X / NSC / 连接池坑 / QUIC / 证书 pinning / 双卡 DDS / 5G 显示 / modem 重启恢复 |
| [14-network/05-车载网络架构与设计.md](14-network/05-车载网络架构与设计.md) | 车载网络架构与设计 | 14 | E/E 分域 / 车载以太网与 TSN / 时间同步 / 域融合 / 服务发现与 MulticastLock / 流量统计 / 多路径 / 长连接 / 远程运维 / STA+AP 并发 / 电源状态与网络存活 |
| [14-network/06-网络排查工具与实践.md](14-network/06-网络排查工具与实践.md) | 网络排查工具与实践 | 10 | 抓包权限边界 / tcpdump SLL / ip·ss 工具族 / 无 root 取证 / Perfetto 轨迹 / ICMP 误读 / DNS 诊断 / 弱网模拟矩阵 |
| [14-network/07-车机网络安全.md](14-network/07-车机网络安全.md) | 车机网络安全 | 8 | 攻击面盘点 / 指令防重放 / 传输基线 / 固件校验防降级 / 调试接口治理 / 信任锚管理 / 近场通道加固 / 检测响应 |
| [14-network/08-应用网络编程与系统约束.md](14-network/08-应用网络编程与系统约束.md) | 应用网络编程与系统约束 | 14 | Doze 网络限制 / WorkManager 约束 / HTTPS 诊断 / 双栈 socket / eSIM / 连接竞速 / 指标监控 / 重试与幂等 / 主线程网络异常 / 权限之辨 / TrafficStats |
| [14-network/09-传输细节与协议设计.md](14-network/09-传输细节与协议设计.md) | 传输细节与协议设计 | 11 | 粘包与协议定界 / Nagle 延迟 / keepalive 参数 / 证书链事故 / 拥塞控制 / 字节序与版本 / 通道选型 / 网段冲突 / 半开假在线 / DNS 劫持 |
| [15-ui/01-窗口系统与WMS.md](15-ui/01-窗口系统与WMS.md) | 窗口系统与 WindowManagerService | 16 | 窗口三副面孔 / addWindow 校验与返回码 / relayout 与遍历调度 / WindowState·Token·DisplayContent / z 序与 surface placement / Insets 与 WindowInsetsController / Configuration 两条路径 / IME 适配 / letterbox / 窗口排查 |
| [15-ui/02-Activity与窗口生命周期.md](15-ui/02-Activity与窗口生命周期.md) | Activity 与窗口生命周期 | 18 | 启动到首帧时序 / 四个可观测时点 / setContentView 与 DecorView / 透明主题尺寸陷阱 / relaunch vs recreate / 状态保存 / 启动模式与任务栈 / 切换动画双窗口 / 首帧度量 / onTrimMemory / 车机多用户归属 |
| [15-ui/03-View测量布局与绘制.md](15-ui/03-View测量布局与绘制.md) | View 测量、布局与绘制 | 15 | 三趟管线 / MeasureSpec 与尺寸契约 / layout vs onLayout / requestLayout vs invalidate / display list 与失效语义 / 脏区传播 / 绘制顺序与裁剪 / onDraw 分配 / 图层类型 / SurfaceView vs TextureView / 属性动画与布局动画 |
| [15-ui/04-资源主题与多屏适配.md](15-ui/04-资源主题与多屏适配.md) | 资源、主题与多屏适配 | 15 | 限定符优先级与顺序 / 默认资源兜底 / 版本限定符 / 深色模式三件事 / 动态取色取舍 / 主题属性与 RRO 定制契约 / dp·sp·fontScale / 限定符≠窗口尺寸 / 车机多屏分歧 / RTL / ViewBinding 联合字段缺失 / 生效变体探针 |
| [15-ui/05-Compose运行期与跨栈互操作.md](15-ui/05-Compose运行期与跨栈互操作.md) | Compose 运行期与跨栈互操作 | 15 | 组合期写状态 / remember 与 rememberSaveable / derivedStateOf / 稳定性推断与 strong skipping / 延迟读取 / 列表键 / 三个 Effect / ComposeView 组合策略 / AndroidView 代价 / 自定义宿主帧时钟 / 重组归因 |
| [15-ui/06-AAOS车机UI架构与CarService.md](15-ui/06-AAOS车机UI架构与CarService.md) | AAOS 车机 UI 架构与 CarService | 15 | UI 四层与定制点 / CarService 与 car-lib / 模板 vs 原生两条路线 / CarAppService 注册契约与 Car App API level / 车机 SystemUI / 应用焦点 / occupant zone 映射 / 多用户 / CarPowerManager 与 power policy / 日夜模式 / 旋钮与自定义输入 / 仪表通道 / 投影共存 |
| [15-ui/07-车机交互安全与驾驶分心.md](15-ui/07-车机交互安全与驾驶分心.md) | 车机交互安全与驾驶分心 | 15 | 约束来源与建模 / 驾驶状态判定 / uxr 映射表与自动提升 / restriction mode / 消费姿势 / distractionOptimized 声明 / 全限制兜底 / config_ignore_ux_restrictions / 乘员屏触控锁定 / 内容级限制 / 语音与物理控件 / 验证方法 |
| [15-ui/08-UI疑难排查与体验踩坑.md](15-ui/08-UI疑难排查与体验踩坑.md) | UI 疑难排查与体验踩坑 | 15 | 五层归因 / 界面没出来与白屏判定 / 掉帧阶段归因与 FrameMetrics / 抓帧工具集与命令归属 / 跳过绘制原因字段 / 重绘过大治理 / insets 遮挡解法 / 输入超时与 UI 阻塞 / SurfaceView 黑区 / 车机多显示与电源策略故障 / 回归机制 |
