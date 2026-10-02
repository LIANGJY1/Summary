# Android 学习资料目录

> 学习资料目录（不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。《android-internals-wiki》全书已于 2026-09-25 按 session-to-knowledge 文章模式全量沉淀为本目录下的 Q&A 学习资料：机制类结论按本地 AAOS13 源码（Android 13）核对并标注与材料 Android 17 语境的版本差异，Q 序列即结构、供 atlas 同源直读；`architecture/01–10` 等早期文档为既往沉淀，予以保留。yadi 车机项目《git 提交与缺陷分析》已于 2026-09-26 沉淀为 [11-defects/](11-defects/) 技术分册八册（2026-09-30 项目级画像一册迁出至 [../04-exp/](../04-exp/) 项目经验目录）。维护者：session-to-knowledge。

## 面试冲刺

- [面试高频索引](面试高频索引.md) — 高频面试主题 → 册·Q 速查（★ 必备 / ★★ 高频 / ★★★ 加分，AAOS 专项段落）

## 机制层（源书第一部分）

- [architecture/](01-architecture/) — 系统架构与系统服务：分层架构、启动链路、Binder、ART、显示/WM、Telephony/Connectivity、AVF、logd/BPF（11–19 为本轮新增）
- [rendering/](02-rendering/) — 渲染系统：渲染管线与 VSync 调度、GPU 合成与显示管线、多窗口折叠屏与显示服务
- [input/](03-input/) — 输入专项（详见下方"输入专题（AOSP + AAOS）"）
- [memory/](05-memory/) — 内存管理：内存全景与 GC、lmkd/Freezer、回收压缩、ZRAM、MTE、跨进程内存
- [cpu-power/](08-cpu-power/) — CPU 调度与能耗：EAS/DVFS/Thermal、后台任务、ADPF、端侧 AI、传感器批处理与 CPU Cache
- [storage/](04-storage/) — 存储与 I/O：存储架构、文件系统调度、SharedPreferences/DataStore、vold/FUSE

## 输入专题（AOSP + AAOS）

- [03-input/](03-input/) — 输入专项（2026-09-29 扩写为七册；01 册为源书沉淀，02–07 册为本轮新增并全网扩写：机制按本地 AAOS13 源码核对，多点触控协议与内核驱动为 kernel.org 官方文档口径，触控 IC 实战为厂商/社区二手结论；Rotary 与 FocusArea 按官方文档口径，2026-09 检索）：[01-输入系统](03-input/01-输入系统.md)——分发全链路与分段定位、延迟五时间戳、InputChannel 与反压、iq/oq/wq 与输入 ANR、过期丢弃、旁路拦截与注入安全、手势导航与 Predictive Back、手势识别、IME 显示链路、物理键与鼠标触控板（26 题）；[02-应用层事件分发与多点触控](03-input/02-应用层事件分发与多点触控.md)——Activity→View 方法链与返回值语义（DecorView 回调转发为第一站）、onTouch/onClick/onLongClick 时序与互斥、拦截与 TouchTarget 生命周期、可点击性默认值、action 编码与 pointer id/index、getX/getRawX、窗口与 View 两级触摸拆分、滑动冲突两策略与嵌套滚动（disallow 的 DOWN 重置坑）、scrollTo 滑动模型、CANCEL 清理义务、触摸遮挡过滤、TouchDelegate 热区扩大、ACTION_OUTSIDE 点外关闭、滚轮悬停与旋钮的通用运动事件（isHoverable 前提）、InputConnection 文本路径、BACK 默认行为与 startTracking 协议、点击无效排查（21 题）；[03-按键系统与键值映射](03-input/03-按键系统与键值映射.md)——扫描码/键码/字符三层、`.kl`（五个顶级声明）/`.kcm`/`.idc` 分工语法与查找链、新增硬键端到端定制、WAKE 与唤醒两级裁决（含外接键盘自动补位、唤醒键 UP 吞除、双击唤醒传感器通道）、组合键与 framework 级长按多击模板、ACTION_MULTIPLE 历史形态、方向盘 HID 与 VHAL 两条接入路线、uinput 虚拟设备、媒体键路由、HOME 等系统键拦截、方向键焦点导航、downTime/eventTime 与 repeat、fallback 合成键（策略层 dispatchUnhandledKey）、按键排查路径（17 题）；[04-设备接入与InputReader](03-input/04-设备接入与InputReader.md)——内核 input/evdev 与 input_event 语义、多点触控协议 A/B 与槽位同步、EventHub 三路 epoll 与热插拔、能力位推断设备分类（手柄三路 mapper）、触摸屏-显示绑定三级来源与视口四级查找、仿射校准先于旋转、虚拟按键 sysfs 定义、旋钮编码器 AXIS_SCROLL、内核重复与框架重复、SYN_DROPPED 溢出、鬼触摸/断触成因对策、触控 IC 联调入口与热力图管道、Switch 开关输入双路消费（17 题）；[05-焦点分发与多屏输入](03-input/05-焦点分发与多屏输入.md)——每屏焦点与 FocusResolver 令牌记账、两类焦点请求的持久化差异、窗口命中与遮挡校验、slippery 出界换窗、窗口级触摸拆分、监视窗口三真实用户（手势导航/屏下指纹/全局监听）、窗口级指针捕获、拖放会话、触摸模式、误触抑制与事件分类组件及版本演进、事件流向窗口级排查、副驾屏无反应分发侧归因、焦点请求持久化的反直觉行为（14 题）；[06-AAOS车机输入](03-input/06-AAOS车机输入.md)——VHAL→应用完整上行链路、三个 VHAL 输入属性的 int32Values 语义（对角线 nudge 合成）、InputHalService 加工与防御、CarInputService 五步分发、语音/通话键车载长按、CustomInputEvent 无人捕获即丢弃、capture 排他栈仲裁（含音量旋钮不可捕获）、旋钮 VHAL 与 Linux 设备双链路、RotaryService 无障碍形态与三模式、焦点移动与滚动加速、FocusArea/FocusParkingView 契约、RotaryService 内部机制（触摸退出检测/HUN nudge 劫持/SurfaceView 修正/历史缓存）、cluster 按键两代路由、车机 IME 与旋钮输入法、注入调试命令、旋钮失灵五层排查（17 题）；[07-输入排查工具与实战](03-input/07-输入排查工具与实战.md)——getevent 全参数与 `-r` 上报率、/proc/bus/input/devices 取证、dumpsys input 分段与队列字段语义、input 命令族（swipe 缺省 300 ms 匀速插值）、四种注入对比、uinput 命令造设备、Perfetto 输入轨道、输入 ANR 输入侧证据链、整机无响应五层决策树、鬼触摸取证矩阵、防误触三层、自动化注入稳定性坑、输入 ANR 五类根因识别、IME 丢字与组合输入时序排查、外设接入四步排查、一次到位采集清单（17 题）

## 性能问题（源书第二部分）

- [performance/](07-performance/) — 流畅性、响应速度、ANR、内存性能、功耗、网络性能、渲染管线专题（基础与图形 API、跨框架与媒体）

## 工具与方法论（源书第三部分）

- [tools/](10-tools/) — Perfetto 采集与 SQL、进阶与 SDK、通用分析工具、GPU 与专项工具、性能方法论、APM 平台与专项原理、学习方法与检查清单

## 系统与厂商（源书第四部分）

- [system/](06-system/) — AOSP 性能优化（构建/AutoFDO/Profile 编译/启动耗时/Rust）、OEM 与设备差异（SoC、游戏模式、Power HAL、AAOS）、CarService 服务速览（媒体源/蓝牙/遥测/诊断/投影）

## 构建与集成

- [18-complie/](18-complie/) — Android/AAOS 产品配置与应用裁剪、Soong C/C++/Java 模块、AAOS 13 模拟器内核编译，以及 Linux 字符设备驱动与验证

## 应用实践（源书第五部分）

- [app-practice/](09-app-practice/) — 稳定性治理（度量崩溃/资源泄漏/线程 IPC/Native 与 SDK）、启动优化、渲染实战（View 与 Compose/图像显示/媒体混合栈）、内存实践、I/O 与存储、网络与连接、功耗优化、CPU 与体积、可观测性（体系治理/线上诊断）
  - [17-资源与值域注解.md](09-app-practice/17-资源与值域注解.md) — AndroidX 资源注解与 `@IntDef` 的静态检查边界
  - [18-应用开发机制与常用API.md](09-app-practice/18-应用开发机制与常用API.md) — SparseArray、View Tag、Parcelable/Serializable、SharedPreferences 与 Retrofit
  - [19-MVP架构.md](09-app-practice/19-MVP架构.md) — Android MVP 职责边界、异步协作、生命周期与测试性

## 缺陷复盘（yadi 项目）

- [11-defects/](11-defects/) — 某车机项目 85 天 1010 条提交、761 条缺陷修复复盘的技术根因分册（八册）：车控信号语义、主线程与异步时序、蓝牙机制、Kanzi 双端状态同步、UI 还原与主题适配、状态缓存与启动时序、崩溃防护与偶现排查、提交治理与防回归；项目级缺陷画像与复盘方法已迁 [../04-exp/00-项目缺陷画像与复盘方法.md](../04-exp/00-项目缺陷画像与复盘方法.md)

## 平台原生层（外部资料，证据等级：二手）

- [12-platform-native/](12-platform-native/) — **证据等级低于 01–11 册**：来自官方 `source.android.com` 文档、AOSP/Soong 源码与中文社区源码分析，未经本地源码逐条核对。补 01–11 册未覆盖的内核与原生层：Binder 驱动的四组核心对象与"一次拷贝"成立条件、ashmem 的 pin/unpin 与 LRU 回收、ION 到 DMA-BUF heap 迁移、GKI 边界；Bionic 动态链接器的命名空间隔离与符号解析；aconfig 特性开关的声明、代码生成与运行期存储

## 音频子系统（AOSP + AAOS）

- [13-audio/README.md](13-audio/README.md) — AAOS 13 音频焦点与播放的连续学习路径：06–12 册从点击音语义、应用焦点申请、AudioService/CarAudioFocus、焦点矩阵与多音区、路由和音量组、AudioFlinger/HAL，递进到实车实验与排障。源码按公开 `android13-release` 核对，目标车仍需验证配置与 HAL 行为。

- [13-audio/](13-audio/) — 音频专项（2026-09-28 新增并全网扩写，机制按本地 AAOS13 源码核对；16 册音频部分、08-cpu-power 的 LE Audio 题与 09-app-practice 的 Offload 题已并入本目录）：[01-AOSP音频子系统](13-audio/01-AOSP音频子系统.md)——audioserver、输出线程选型、策略引擎、混音管线与 FastMixer/NBAIO、音量与效果链、AAudio 服务端内部、时间戳与 TeeSink；[02-AAOS车机音频](13-audio/02-AAOS车机音频.md)——多音区配置、context 路由、落区判定、硬件增益、焦点矩阵与延迟焦点、duck/mute、HalAudioFocus、AudioControl 版本差异、13/14 版本边界；[03-音频延迟与应用实践](13-audio/03-音频延迟与应用实践.md)——延迟口径与实测、FAST 轨、AAudio 回退与缓冲调优、xrun、Offload 取舍、AudioTrack/SoundPool 语义；[04-蓝牙音频](13-audio/04-蓝牙音频.md)——蓝牙延迟实测、LC3、单播/广播、LE Audio、ASHA/HAP、A2DP/SCO 切换与绝对音量；[05-手机侧音频焦点与路由](13-audio/05-手机侧音频焦点与路由.md)——焦点栈仲裁、丢失处理、音量滑条与 usage、BECOMING_NOISY、通信路由、并发录音与隐私

- [14-network/](14-network/) — 网络专项（2026-09-28 新增）：[01-Android网络框架](14-network/01-Android网络框架.md)——"已连接"分层语义、默认网络评分与切换、多网络绑定、captive portal 验证、NetworkAgent、DNS 与 Private DNS、策略路由、车机以太网；[02-车机多APN与虚拟网卡](14-network/02-车机多APN与虚拟网卡.md)——多 APN 与 rmnet、veth+SNAT 业务隔离拓扑（项目实例已脱敏）、网卡绑定两套机制、DNS 分流与五段定位、双公网切换、APN 定制链与流量策略；[03-Android-VPN](14-network/03-Android-VPN.md)——VpnService tun 数据面、protect 防回环、分应用、always-on/lockdown、平台 IKEv2、与多 APN 共存、配置存储与诊断（V1.0，2026-09-28）；[04-蜂窝数据与无线连接](14-network/04-蜂窝数据与无线连接.md)——APN 类型与配置链、DataNetwork 模型、数据开关分层、蜂窝验证器、热点共享、Wi-Fi 编程面、NSC、网络库切换坑；[05-车载网络架构与设计](14-network/05-车载网络架构与设计.md)——E/E 分域与座舱职责、车载以太网骨干与 TSN/gPTP、时间同步三层、域融合演进、跨域隔离、mDNS 服务发现、eBPF 流量统计与计费、多路径传输、长连接推送、远程运维通道；[06-网络排查工具与实践](14-network/06-网络排查工具与实践.md)——抓包权限边界、tcpdump SLL 语义与过滤器、ip/ss 工具族、无 root 取证、Perfetto 网络轨迹、ICMP 工具误读、DNS 诊断替代、弱网模拟测试矩阵；[07-车机网络安全](14-network/07-车机网络安全.md)——攻击面盘点、指令防重放与完整性、传输安全基线、固件下发校验与防降级、量产调试接口治理、检测与响应（V1.0，2026-09-28）；[08-应用网络编程与系统约束](14-network/08-应用网络编程与系统约束.md)——Doze/App Standby 网络限制、WorkManager 网络约束、HTTPS 校验失败分类与时钟坑、IPv6 双栈 socket 坑、车机 eSIM/LPA、连接竞速、网络指标监控体系；[09-传输细节与协议设计](14-network/09-传输细节与协议设计.md)——TCP 粘包与协议定界（长度前缀/TLV）、Nagle 与延迟 ACK、keepalive 参数真相、证书链不完整事故、拥塞控制启示、字节序与版本兼容、车云通道选型、热点网段冲突、TCP 半开假在线、DNS 劫持与 HTTPDNS、流量控制与零窗口、UDP 选型、TIME_WAIT、ARP 排查、HTTP·2 取舍、多通道保序、连接池 key、0-RTT、HTTP 缓存；[10-网络分层原理与地基机制](14-network/10-网络分层原理与地基机制.md)——网络组成与分类、标准化与 RFC、分层模型与包的旅行、OSI/TCP-IP 参考模型、网络性能指标、DNS 层级与递归、TLS 握手、TCP 状态机、IP 分片、NAT 与 conntrack、全链路走读、分层校验

## UI 专题（AOSP + AAOS）

- [15-ui/](15-ui/) — UI 专题（8 册 127 题，按学习路径编排：Activity 生命周期与首帧 → View 布局绘制 → 资源与多屏适配 → Compose 与 View 互操作 → 窗口系统 → AAOS 架构 → 驾驶安全 → 综合排查；机制版本与证据来源见各册引言）

## 早期文档

- [framework/](../../docs/others/framework/) — Android 13 长文专题：addWindow 全链路、渲染架构解析、显示系统、电源、时间、硬按键
- 根级：[性能优化.md](../../docs/others/性能优化.md)、[Launcher3_Technical_Document.md](../../docs/others/Launcher3_Technical_Document.md)、[OTA_LIFECYCLE.md](../../docs/others/OTA_LIFECYCLE.md)、[MVVM_Optimization_Report.md](../../docs/others/MVVM_Optimization_Report.md)

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
| [01-architecture/23-Handler消息机制.md](01-architecture/23-Handler消息机制.md) | Handler 消息机制 | 4 | Looper 与消息队列 / 子线程 Handler / 内存泄漏 / HandlerThread |
| [01-architecture/14-系统服务调度核心.md](01-architecture/14-系统服务调度核心.md) | 系统服务调度核心 | 17 |  |
| [01-architecture/15-安装归档与资源配置.md](01-architecture/15-安装归档与资源配置.md) | 安装归档与资源配置 | 13 |  |
| [01-architecture/16-显示与窗口链路.md](01-architecture/16-显示与窗口链路.md) | 显示与窗口链路 | 6 |  |
| [01-architecture/17-Telephony与Connectivity.md](01-architecture/17-Telephony与Connectivity.md) | Telephony 与 Connectivity | 12 |  |
| [01-architecture/18-Notification-Biometric-Location.md](01-architecture/18-Notification-Biometric-Location.md) | 通知、生物识别与位置系统服务链路 | 16 |  |
| [01-architecture/19-AVF可观测与AI手机技术栈.md](01-architecture/19-AVF可观测与AI手机技术栈.md) | AVF 虚拟化、logd 日志、BPF 与端侧 AI 技术栈 | 16 |  |
| [01-architecture/20-SELinux.md](01-architecture/20-SELinux.md) | Android SELinux | 8 |  |
| [16-project-architecture/01-应用进程启动与全局服务生命周期.md](16-project-architecture/01-应用进程启动与全局服务生命周期.md) | Launcher 项目架构案例：全局车辆服务与首页 Activity 的协作 | 1 | 单题完整分析：VehicleService 启动调用链、Activity 时序、车辆 ready 边界与 MainService 生命周期宿主的项目取舍 |
| [02-rendering/01-渲染管线与VSync调度.md](02-rendering/01-渲染管线与VSync调度.md) | 渲染管线与 VSync 调度 | 30 |  |
| [02-rendering/02-GPU合成与显示管线.md](02-rendering/02-GPU合成与显示管线.md) | GPU 合成与显示管线 | 30 |  |
| [02-rendering/03-多窗口折叠屏与显示服务.md](02-rendering/03-多窗口折叠屏与显示服务.md) | 多窗口、折叠屏与显示服务 | 25 |  |
| [03-input/01-输入系统.md](03-input/01-输入系统.md) | Android 输入系统：分发、延迟与安全边界 | 26 | 全链路分段定位 / 延迟五时间戳 / InputChannel 反压 / iq·oq·wq 与输入 ANR / 过期丢弃 / 旁路拦截与注入 / 手势导航与 Predictive Back / IME 显示 / 外设 |
| [03-input/02-应用层事件分发与多点触控.md](03-input/02-应用层事件分发与多点触控.md) | 应用层事件分发与多点触控 | 21 | 方法链与返回值 / onTouch·onClick·onLongClick / 拦截与 TouchTarget / pointer id·index / 两级拆分 / 滑动冲突 / CANCEL 清理 / 遮挡过滤 / TouchDelegate / ACTION_OUTSIDE / InputConnection |
| [03-input/03-按键系统与键值映射.md](03-input/03-按键系统与键值映射.md) | 按键系统与键值映射 | 17 | 三层映射 / .kl·.kcm·.idc 查找链 / 硬键定制 / WAKE 唤醒 / 组合键与连击 / ACTION_MULTIPLE / 方向盘两路线 / uinput / 媒体键与 HOME / fallback |
| [03-input/04-设备接入与InputReader.md](03-input/04-设备接入与InputReader.md) | 设备接入与 InputReader | 17 | input_event / MT 协议 A·B / 能力位分类 / mapper 族（手柄三路）/ 显示绑定 / 校准与旋转 / 虚拟键 / 旋钮 / Switch 开关 / SYN_DROPPED / 鬼触摸·断触 |
| [03-input/05-焦点分发与多屏输入.md](03-input/05-焦点分发与多屏输入.md) | 焦点分发与多屏输入 | 14 | 每屏焦点与请求持久化 / 窗口命中与遮挡 / slippery / 拆分 / 监视窗口 / 指针捕获 / 拖放 / 触摸模式 / 分类组件 |
| [03-input/06-AAOS车机输入.md](03-input/06-AAOS车机输入.md) | AAOS 车机输入 | 17 | VHAL 三属性 / CarInputService 五步 / capture 栈 / 旋钮双链路 / RotaryService·FocusArea·内部机制 / cluster / 车机 IME / 五层排查 |
| [03-input/07-输入排查工具与实战.md](03-input/07-输入排查工具与实战.md) | 输入排查工具与实战 | 17 | getevent / dumpsys input 字段 / input 命令族 / 注入对比 / Perfetto 轨道 / ANR 证据链 / 决策树 / 外设接入 / IME 丢字 / 取证矩阵 |
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
| [09-app-practice/17-资源与值域注解.md](09-app-practice/17-资源与值域注解.md) | 资源与值域注解 | 2 | `@DrawableRes` / `@IntDef` 静态约束 |
| [09-app-practice/18-应用开发机制与常用API.md](09-app-practice/18-应用开发机制与常用API.md) | 应用开发机制与常用 API | 6 | SparseArray / View Tag / 序列化 / Retrofit / Notification 渠道 |
| [10-tools/01-Perfetto-采集与SQL分析.md](10-tools/01-Perfetto-采集与SQL分析.md) | Perfetto 采集与 SQL 分析 | 35 |  |
| [10-tools/02-Perfetto-进阶与SDK.md](10-tools/02-Perfetto-进阶与SDK.md) | Perfetto 进阶：Profile 火焰图、CPU 频率、BufferQueue、Agent 协议、SDK 与 FrameTimeline | 30 |  |
| [10-tools/03-性能分析工具.md](10-tools/03-性能分析工具.md) | 性能分析工具 | 36 |  |
| [10-tools/04-GPU与专项工具.md](10-tools/04-GPU与专项工具.md) | GPU 与专项工具 | 30 |  |
| [10-tools/05-性能方法论.md](10-tools/05-性能方法论.md) | 性能方法论 | 33 |  |
| [10-tools/06-APM-平台与SDK.md](10-tools/06-APM-平台与SDK.md) | APM 平台与 SDK | 30 |  |
| [10-tools/07-APM-专项原理与架构.md](10-tools/07-APM-专项原理与架构.md) | APM 专项原理与架构 | 32 |  |
| [10-tools/08-学习方法与检查清单.md](10-tools/08-学习方法与检查清单.md) | 学习方法与检查清单 | 8 |  |
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
| [15-ui/01-activity.md](15-ui/01-activity.md) | Activity 启动与窗口生命周期 | 19 | 启动到首帧 / 窗口焦点与首帧 / 配置变化 / 状态恢复 / 任务栈 / Fragment 视图与状态 |
| [15-ui/02-view.md](15-ui/02-view.md) | View 测量、布局与绘制 | 17 | MeasureSpec / requestLayout 与 invalidate / display list / 绘制顺序 / 图层 / SurfaceView 与 TextureView |
| [15-ui/03-resources.md](15-ui/03-resources.md) | 资源、主题与多屏适配 | 16 | 限定符 / 默认资源 / 深色模式 / 主题与 RRO / 字号与 RTL / 多显示 / Configuration |
| [15-ui/04-compose.md](15-ui/04-compose.md) | Compose 运行期与 View 互操作 | 15 | 重组与状态 / remember / derivedStateOf / 稳定性 / Effect / ComposeView 与 AndroidView / 掉帧 |
| [15-ui/05-window-system.md](15-ui/05-window-system.md) | 窗口系统与 WindowManagerService | 17 | 窗口层级 / token 与 addWindow / relayout / Insets / Configuration / IME / 多窗口 / 黑屏排查 |
| [15-ui/06-aaos-ui.md](15-ui/06-aaos-ui.md) | AAOS 车机 UI 架构与 CarService | 15 | UI 定制分层 / CarService / 模板与原生应用 / occupant zone / 多用户 / 电源策略 / 仪表 / 投影 |
| [15-ui/07-driving-safety.md](15-ui/07-driving-safety.md) | 车机交互安全与驾驶分心 | 15 | UX 限制 / 驾驶状态 / restriction mode / 应用声明 / 乘员屏 / 受限交互 / 验证 |
| [15-ui/08-ui-debugging.md](15-ui/08-ui-debugging.md) | UI 疑难排查与体验踩坑 | 15 | 白屏与黑屏 / 首帧 / 掉帧归因 / 绘制诊断 / Insets / 输入超时 / 多显示 / 回归检查 |

## 项目架构设计

- [16-project-architecture/](16-project-architecture/) — 特定项目、特定场景的架构方案与问题分析；结论以案例源码为边界，不概括为通用 Android 规则：[Launcher 项目架构案例：全局车辆服务与首页 Activity 的协作](16-project-architecture/01-应用进程启动与全局服务生命周期.md)（以一个问题完整串联 VehicleService 启动链、Activity 时序、车辆 ready 边界与 MainService 生命周期宿主的项目取舍）

## 车载应用专题

- [17-car-app/](17-car-app/) — AAOS 车载应用专题；[Launcher 启动流程](17-car-app/01-Launcher.md)覆盖 CarLauncher 与手机 Launcher3 的职责差异、车载应用发现、媒体源入口、Activity 启动链及任务/进程复用。
