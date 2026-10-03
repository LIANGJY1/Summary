#!/usr/bin/env python3
"""Preambles & H1 for new / re-scoped destination docs (used by migrate.py)."""

P = {}

def pre(path, h1, text):
    P[path] = (h1, text)

pre("01-architecture/15-package-management.md", "应用包管理：安装、校验与归档",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"应用从分发制品到安装事务、dexopt 编排、增量与分阶段安装、应用归档与恢复\"；资源与 Configuration 更新归 [15-ui/03-resources.md](../15-ui/03-resources.md)；构建期模块声明归 18-build-system。源文档：android-internals-wiki §1.16–§1.17；机制按本地 AAOS13 源码（Android 13）核对，安装 dexopt 迁往 ART Service（Android 14 起）与平台级 App Archiving（Android 15 起）已标注版本差异。Q 序列即结构，供 atlas 同源直读。")
pre("04-storage/02-partitions.md", "运行时分区与挂载",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"设备上有哪些分区、各放什么、动态分区与虚拟 A/B 在运行时如何挂载与生效\"；镜像如何构建、打包与刷写归 [18-build-system/06-android-system-images.md](../18-build-system/06-android-system-images.md)；启动期 fstab/first-stage 挂载时序归 [01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)。分区名与布局随设备/版本有差异，权威以设备 fstab 与构建配置为准。Q 序列即结构，供 atlas 同源直读。")
pre("02-rendering/04-graphics-api.md", "图形 API：EGL、Vulkan 与 NDK 出图接口",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"EGL/Vulkan/NDK（ASurfaceControl、HardwareBufferRenderer、WebGPU）各对象与调用语义、Android 平台在其间的行为\"；渲染管线与 VSync 调度归 [01-render-pipeline-vsync.md](01-render-pipeline-vsync.md)，GPU 合成归 [02-gpu-composition-display.md](02-gpu-composition-display.md)，测量归因归 07-performance。证据：AAOS13 源码核对与官方文档口径，逐题标注。Q 序列即结构，供 atlas 同源直读。")
pre("02-rendering/05-graphic-stack-preload.md", "图形栈预加载与驱动选择",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Zygote 预加载覆盖图形栈的哪一段、GPU 驱动/ANGLE 由谁选择、首帧开销中预加载帮不上的部分\"；Zygote 与启动链归 [01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)，应用侧首帧优化归 09-app-practice。源文档：android-internals-wiki §1.3（Android 17 语境），官方资料已核对。Q 序列即结构，供 atlas 同源直读。")
pre("02-rendering/06-camera-pipeline.md", "相机管线：缓冲、栅栏与时间戳",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Camera 输出流的 BufferQueue、fence 交接、ZSL 与时间戳基准\"；应用侧 CameraX 用法归 09-app-practice，性能归因归 07-performance。证据：AAOS13 源码核对，逐题标注。Q 序列即结构，供 atlas 同源直读。")
pre("02-rendering/07-media-playback.md", "视频播放与合成路径",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"视频帧如何经 SurfaceView/MediaCodec/tunneled 路径上屏、编解码能力与容器格式支持的版本边界\"；渲染机制归 01–02 册，应用侧 Media3 实践归 09-app-practice。证据：AAOS13 源码核对，逐题标注。Q 序列即结构，供 atlas 同源直读。")
pre("02-rendering/08-android-xr.md", "Android XR 出图与预算",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"XR 形态应用如何出图、环境资产与每帧预算的口径\"；通用渲染机制归 01–02 册。证据：XR 官方文档口径（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/01-notifications.md", "通知服务链路",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"NotificationManager 的进程边界、限流、渠道与权限语义\"；SystemUI 渲染归 15-ui，应用通知实践归 09-app-practice。源文档：android-internals-wiki §1.23（Android 17 语境），POST_NOTIFICATIONS 与渠道行为已与官方文档核对。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/02-biometrics.md", "生物识别服务链路",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"BiometricPrompt/BiometricService 的请求链路、会话替换、强度等级与回调时序\"。源文档：android-internals-wiki §1.24（Android 17 语境），强度能力与会话语义已与官方文档核对。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/03-location.md", "位置服务链路",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"LocationManager 的 provider 语义、请求合并、权限改写与回调背压\"；功耗策略归 08-cpu-power，应用实践归 09-app-practice。源文档：android-internals-wiki §1.25（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/04-avf-virtualization.md", "AVF 虚拟化",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"AVF 组件拓扑、Microdroid 能力边界、pKVM 隔离与 pVM 性能结构\"。源文档：android-internals-wiki §1.26（Android 17/ACK 6.18 语境），AVF 架构与 pKVM 安全模型已与 source.android.com 核对。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/05-ai-services.md", "平台 AI 服务",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"端侧 AI 能力的交付分层、NNAPI/NN HAL 现状、AICore 与 AppFunctions 的运行条件\"。源文档：android-internals-wiki §1.29（Android 17 语境），NNAPI 弃用状态已与官方文档核对。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/06-aconfig-runtime.md", "aconfig 运行时：存储与 aflags",
    "> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答\"标志值运行期从哪读、aconfigd 如何初始化存储、aflags 怎么查看与修改\"；声明与构建期代码生成归 [18-build-system/07-aconfig.md](../18-build-system/07-aconfig.md)。源文档：Android 官方 feature-flagging 文档与 AOSP 源码口径，版本差异已标注。Q 序列即结构，供 atlas 同源直读。")
pre("06-platform-services/07-broadcast.md", "广播队列与投递",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"BroadcastQueue 的队列模型、超时契约、缓存态延后与投递语义\"；ANR 诊断归 07-performance/03，应用侧广播用法归 16-app-framework/01。源文档：android-internals-wiki §1.14（Android 17 语境），缓存态延后已与官方资料核对。Q 序列即结构，供 atlas 同源直读。")
pre("07-performance/09-platform-optimization.md", "平台性能优化与前沿评估",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"平台级性能优化的证据要求、启动指标口径、AOSP 与论文方案（AppFlow、AOHP）、Game Mode 与设备能力分级的取舍\"；构建命令归 18-build-system，机制描述归各机制册。论文/原型结论均标注证据等级，不得当平台承诺。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/01-kernel-gki.md", "内核与 GKI",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"ACK/GKI 形态、KMI 边界、内核启动基础（进程概念、ramdisk/rootfs）、vendor hooks 与内核观测点\"；Binder 驱动归 [02-binder-driver.md](02-binder-driver.md)，共享内存归 [03-shared-memory.md](03-shared-memory.md)，内核构建归 18-build-system。01–11 册（Framework 层）之外的内核结论部分为二手口径（官方文档/社区分析），逐题标注核对状态。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/02-binder-driver.md", "Binder 驱动（内核层）",
    "> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答\"Binder 驱动的内核实现：mmap/一次拷贝、四组核心对象、binder_write_read、buffer 分配归还、引用计数与进程退出回收、servicemanager\"；Framework 侧 Binder 契约归 [../01-architecture/03-binder.md](../01-architecture/03-binder.md)。源码引用以官方文档与社区分析为准，未逐条核对本地 AOSP。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/03-shared-memory.md", "共享内存：ashmem、ION 与 DMA-BUF",
    "> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答\"ashmem pin/unpin、fd 跨进程传递、ION 到 DMA-BUF heap 迁移与回收路径分工\"；图形缓冲在渲染链路的使用归 02-rendering。源码引用以官方文档与社区分析为准。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/05-driver-runtime.md", "内核驱动运行时",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"字符设备/VFS 分发、file/inode/fops、设备号与 /dev 节点、copy_from_user、MMIO、ioctl/mmap、内核数据结构\"；模块构建与部署归 [../18-build-system/04-kernel-modules.md](../18-build-system/04-kernel-modules.md)。材料中的固定物理地址与宿主机裸 gcc 命令不是生产做法。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/06-logd.md", "logd 日志链路",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"一条日志从调用到 logd 的路径、级别过滤省什么、丢弃与裁剪机制、logcat 过滤执行位置\"；R8 构建期删日志归 09-app-practice/14。源文档：android-internals-wiki §1.27（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/07-bpf.md", "BPF 可观测与可编程边界",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"BPF 能力的四道门槛、装载与附着条件、Arena/sched_ext 的启用语义\"；网络 eBPF 统计归 14-network/05。源文档：android-internals-wiki §1.28（Android 17/ACK 6.18 语境）。Q 序列即结构，供 atlas 同源直读。")
pre("12-platform-native/08-rust-native.md", "平台 Rust 与 FFI",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Android 平台 Rust 的引入动机、设备端编译配置与分配器路径、Rust 服务的性能评估边界\"；平台语言全景归 [../01-architecture/01-system-architecture.md](../01-architecture/01-system-architecture.md)。源文档：android-internals-wiki（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。")
pre("16-app-framework/02-handler-looper.md", "Handler 消息机制与 MessageQueue 实现",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Handler/Looper/MessageQueue 的契约与线程边界、Android 17 DeliQueue 的实现与迁移\"；锁等待诊断归 09-app-practice/03，Binder 语义归 01-architecture/03。经典机制与 DeliQueue 部分分属 Android 通用与 Android 17 语境，逐题标注。Q 序列即结构，供 atlas 同源直读。")
pre("16-app-framework/03-content-provider.md", "ContentProvider 服务链路",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Provider 初始化时序、CursorWindow 传输、超时契约与批量操作\"；四组件入门契约归 [01-four-components.md](01-four-components.md)。源文档：android-internals-wiki §1.15（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。")
pre("16-app-framework/04-parcel.md", "Parcel 与序列化契约",
    "> 应用框架 API 契约。边界：本文回答\"Parcelable/Serializable 选型与 Parcel 读写契约\"。Q 序列即结构，供 atlas 同源直读。")
pre("16-app-framework/05-collections-annotations.md", "集合与注解的框架契约",
    "> 应用框架 API 契约。边界：本文回答\"SparseArray 等平台集合的取舍与平台注解契约\"。Q 序列即结构，供 atlas 同源直读。")
pre("16-app-framework/06-private-space.md", "Private Space 与应用可见性",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Private Space 与厂商应用锁/AAOS App Lock 的区分、Launcher 接入与跨空间 content 访问\"。Q 序列即结构，供 atlas 同源直读。")
pre("17-aaos/02-car-launcher.md", "CarLauncher 实现与任务嵌入",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"CarLauncher 与 Launcher3 的职责差异、应用发现与网格构造、点击启动链与 TaskView 嵌入\"；HOME 请求的系统侧解析归 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)，任务/launchMode 通用语义归 15-ui/01。Q 序列即结构，供 atlas 同源直读。")
pre("17-aaos/03-vhal-integration.md", "VHAL 集成与契约",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"CarPropertyService 两层校验、VHAL 崩溃恢复、订阅契约与 HIDL→AIDL 迁移差异\"。Q 序列即结构，供 atlas 同源直读。")
pre("17-aaos/04-car-power-users.md", "车辆电源与多用户",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"车辆电源状态机、headless system user、切用户流程与 OEM 服务归属\"。Q 序列即结构，供 atlas 同源直读。")
pre("17-aaos/05-vehicle-links.md", "车机链路场景地图",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"车机九类链路（输入/显示/摄像头/音频/车辆信号/互联/网络/定位/电源）的端到端组成与取证维度\"；各链路机制细节归对应领域目录。Q 序列即结构，供 atlas 同源直读。")
pre("17-aaos/06-aaos-app-dev.md", "AAOS 应用开发要点",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Android Auto 与 AAOS 的执行边界、模板应用与地图/媒体应用的开发约束\"。Q 序列即结构，供 atlas 同源直读。")
pre("18-build-system/04-kernel-modules.md", "内核模块构建与部署",
    "> 学习资料（文章模式沉淀）。边界：本文回答\"Kconfig/Kbuild 如何决定内建与模块、外部模块编译与工具链匹配、.ko 部署验证与迭代流程\"；驱动运行时设计归 [../12-platform-native/05-driver-runtime.md](../12-platform-native/05-driver-runtime.md)。Q 序列即结构，供 atlas 同源直读。")
pre("18-build-system/07-aconfig.md", "aconfig：声明与构建期代码生成",
    "> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答\".aconfig 声明字段、Soong 接入、codegen 模板、release config 与版本核对\"；运行期存储与 aflags 归 [../06-platform-services/06-aconfig-runtime.md](../06-platform-services/06-aconfig-runtime.md)。源文档：Android 官方 feature-flagging 文档与 AOSP 源码口径。Q 序列即结构，供 atlas 同源直读。")
pre("OUT:knowledge-base/04-exp/01-提交治理与防回归.md", "提交治理与防回归",
    "> 项目经验（非技术）。边界：本文回答\"某车机项目 85 天复盘中沉淀的提交消息治理、单号/Change-Id 纪律、回退与分支合入规范、防回归卡点\"；技术根因归 01-android/11-defect-patterns。项目名脱敏沿用\"某车机项目\"惯例。Q 序列即结构，供 atlas 同源直读。")
pre("OUT:knowledge-base/网络/01-network-fundamentals.md", "网络分层原理与地基机制",
    "> 通用网络协议学习资料。边界：本文回答\"分层模型、DNS/TLS/TCP/IP/NAT 等协议地基机制\"；Android/车机网络实现与诊断归 [01-android/14-network/](../01-android/14-network/)。Q 序列即结构，供 atlas 同源直读。")
pre("OUT:knowledge-base/career/work-project-analysis/01-launcher-vehicle-service.md", "Launcher 项目架构案例：全局车辆服务与首页 Activity 的协作",
    "> 真实工作项目分析（案例边界）。结论以本项目源码为边界，不概括为通用 Android 规则；通用机制归 01-android 各册。项目信息按仓库脱敏惯例处理。")
