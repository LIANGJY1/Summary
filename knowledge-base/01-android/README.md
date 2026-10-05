# Android 学习资料目录

> 学习资料目录（不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。2026-10-06 定稿 12 章制：03-ui 含输入全链路，04-graphics 为图形管线域，06-memory-storage 合并内存与存储，12-performance 含原 CPU/功耗内容，其余章按"平台→框架→交互域→资源域→系统能力→产品→横切"排列。**册的存废只看主题边界、不看题数**——薄册是主题尚未扩展学习，标注扩写方向而非合并；新建册须先确认无既有主题册可归并。章的分工规则：11-aaos 只收跨域集成，单域车机纵深册留各功能域；12-performance 只收横切度量与方法，域机制归各域。历次重构明细见 `docs/superpowers/plans/`（android-kb-restructure、kb-full-restructure-2026-10）。维护者：session-to-knowledge。

## 面试冲刺

- [面试高频索引](high-frequency-interview-index.md) — 高频面试主题 → 册·Q 速查（★ 必备 / ★★ 高频 / ★★★ 加分，AAOS 专项段落）

## 推荐学习顺序

章号与章内册号的前缀即阅读顺序：

1. **平台与框架（01–02）**：架构与启动 → 应用框架与组件。
2. **交互域（03–05）**：UI 与输入 → 图形管线 → 音频。
3. **资源与网络（06–07）**：内存与存储 → 网络。
4. **系统能力（08–10）**：平台服务 → 原生层 → 构建系统。
5. **产品与横切（11–12）**：AAOS 跨域集成 → 性能与稳定性。

## 01-architecture/

- **平台层：分层架构、启动链、SystemServer、Binder/HAL、ART/JNI、包管理、沙箱、权限与 SELinux**（11 册 185 题）：
  - [01-system-architecture.md](01-architecture/01-system-architecture.md)（19 题）
  - [02-system-boot.md](01-architecture/02-system-boot.md)（46 题）
  - [03-binder.md](01-architecture/03-binder.md)（22 题）
  - [04-system-server.md](01-architecture/04-system-server.md)（11 题）
  - [05-hal.md](01-architecture/05-hal.md)（3 题）
  - [06-art-runtime.md](01-architecture/06-art-runtime.md)（20 题）
  - [07-jni.md](01-architecture/07-jni.md)（8 题）
  - [08-package-management.md](01-architecture/08-package-management.md)（9 题）
  - [09-app-sandbox.md](01-architecture/09-app-sandbox.md)（16 题）
  - [10-permissions.md](01-architecture/10-permissions.md)（3 题）
  - [11-selinux.md](01-architecture/11-selinux.md)（28 题）

## 02-app-framework/

- **应用框架与组件：四大组件、Activity 启动与任务栈、Fragment/ViewModel、Handler、Parcel、ContentProvider、MVP、线程与 IPC 稳定性、主线程缺陷**（11 册 113 题）：
  - [01-four-components.md](02-app-framework/01-four-components.md)（6 题）
  - [02-handler-looper.md](02-app-framework/02-handler-looper.md)（12 题）
  - [03-parcel.md](02-app-framework/03-parcel.md)（9 题）
  - [04-content-provider.md](02-app-framework/04-content-provider.md)（4 题）
  - [05-collections-annotations.md](02-app-framework/05-collections-annotations.md)（3 题）
  - [06-private-space.md](02-app-framework/06-private-space.md)（3 题）
  - [07-mvp-architecture.md](02-app-framework/07-mvp-architecture.md)（7 题）
  - [08-app-thread-ipc-stability.md](02-app-framework/08-app-thread-ipc-stability.md)（23 题）
  - [09-defect-main-thread-async.md](02-app-framework/09-defect-main-thread-async.md)（27 题）
  - [10-activity-launch-tasks.md](02-app-framework/10-activity-launch-tasks.md)（14 题）
  - [11-fragment-viewmodel.md](02-app-framework/11-fragment-viewmodel.md)（5 题）

## 03-ui/

- **UI 与输入：Activity 生命周期与首帧、View、资源适配、窗口系统、Compose、AAOS UI、驾驶安全、主题还原缺陷、UI 调试，以及输入全链路（分发、InputReader、按键映射、焦点多屏、AAOS 输入、排查）**（16 册 338 题）：
  - [01-activity.md](03-ui/01-activity.md)（14 题）
  - [02-view.md](03-ui/02-view.md)（27 题）
  - [03-resources.md](03-ui/03-resources.md)（25 题）
  - [04-window-system.md](03-ui/04-window-system.md)（38 题）
  - [05-compose.md](03-ui/05-compose.md)（19 题）
  - [06-aaos-ui.md](03-ui/06-aaos-ui.md)（19 题）
  - [07-driving-safety.md](03-ui/07-driving-safety.md)（18 题）
  - [08-defect-ui-theme-fidelity.md](03-ui/08-defect-ui-theme-fidelity.md)（32 题）
  - [09-ui-debugging.md](03-ui/09-ui-debugging.md)（16 题）
  - [10-input-system.md](03-ui/10-input-system.md)（27 题）
  - [11-app-event-dispatch.md](03-ui/11-app-event-dispatch.md)（21 题）
  - [12-input-reader.md](03-ui/12-input-reader.md)（17 题）
  - [13-key-mapping.md](03-ui/13-key-mapping.md)（17 题）
  - [14-focus-multi-display.md](03-ui/14-focus-multi-display.md)（14 题）
  - [15-aaos-input.md](03-ui/15-aaos-input.md)（17 题）
  - [16-input-diagnostics.md](03-ui/16-input-diagnostics.md)（17 题）

## 04-graphics/

- **图形管线域：渲染管线与 VSync、GPU 合成与显示、显示服务与折叠形态、图形 API、相机/媒体、XR、应用渲染实战与 GPU 诊断。它不是 UI 的子集，回答的是“像素如何出现”的完整链路**（13 册 254 题）：
  - [01-render-pipeline-vsync.md](04-graphics/01-render-pipeline-vsync.md)（36 题）
  - [02-gpu-composition-display.md](04-graphics/02-gpu-composition-display.md)（33 题）
  - [03-display-service-foldable.md](04-graphics/03-display-service-foldable.md)（10 题）
  - [04-graphics-api.md](04-graphics/04-graphics-api.md)（15 题）
  - [05-graphic-stack-preload.md](04-graphics/05-graphic-stack-preload.md)（6 题）
  - [06-camera-pipeline.md](04-graphics/06-camera-pipeline.md)（6 题）
  - [07-media-playback.md](04-graphics/07-media-playback.md)（6 题）
  - [08-android-xr.md](04-graphics/08-android-xr.md)（3 题）
  - [09-app-image-page-rendering.md](04-graphics/09-app-image-page-rendering.md)（26 题）
  - [10-app-media-hybrid-practice.md](04-graphics/10-app-media-hybrid-practice.md)（33 题）
  - [11-gpu-diagnostics-tools.md](04-graphics/11-gpu-diagnostics-tools.md)（30 题）
  - [12-app-view-compose-practice.md](04-graphics/12-app-view-compose-practice.md)（26 题）
  - [13-app-compose-advanced-practice.md](04-graphics/13-app-compose-advanced-practice.md)（24 题）

## 05-audio/

- **音频全链路：AOSP/AAOS 音频、焦点、延迟与蓝牙音频；06–12 册是点击音场景的连续学习路径（README 见目录内）**（13 册 125 题）：
  - [01-aosp-audio.md](05-audio/01-aosp-audio.md)（19 题）
  - [02-phone-audio-focus.md](05-audio/02-phone-audio-focus.md)（7 题）
  - [03-aaos-audio.md](05-audio/03-aaos-audio.md)（15 题）
  - [04-audio-latency.md](05-audio/04-audio-latency.md)（11 题）
  - [05-bluetooth-audio.md](05-audio/05-bluetooth-audio.md)（8 题）
  - [06-audio-decisions.md](05-audio/06-audio-decisions.md)（6 题）
  - [07-focus-api.md](05-audio/07-focus-api.md)（4 题）
  - [08-focus-flow.md](05-audio/08-focus-flow.md)（4 题）
  - [09-car-focus.md](05-audio/09-car-focus.md)（5 题）
  - [10-routing-volume.md](05-audio/10-routing-volume.md)（4 题）
  - [11-playback-hal.md](05-audio/11-playback-hal.md)（4 题）
  - [12-diagnostics.md](05-audio/12-diagnostics.md)（6 题）
  - [13-defect-bluetooth-mechanisms.md](05-audio/13-defect-bluetooth-mechanisms.md)（32 题）

## 06-memory-storage/

- **资源域：内存治理（GC、lmkd/Freezer、回收压缩、泄漏治理）与存储（分区、存储 I/O、文件/数据库实践）**（7 册 181 题）：
  - [01-memory-management.md](06-memory-storage/01-memory-management.md)（33 题）
  - [02-reclaim-compression.md](06-memory-storage/02-reclaim-compression.md)（26 题）
  - [03-app-memory-stability.md](06-memory-storage/03-app-memory-stability.md)（25 题）
  - [04-app-memory-practice.md](06-memory-storage/04-app-memory-practice.md)（32 题）
  - [05-partitions.md](06-memory-storage/05-partitions.md)（4 题）
  - [06-storage-io.md](06-memory-storage/06-storage-io.md)（23 题）
  - [07-app-io-storage-practice.md](06-memory-storage/07-app-io-storage-practice.md)（39 题）

## 07-network/

- **Android 网络实现与车机网络：框架、蜂窝、传输协议、应用约束、多 APN、VPN、车载网络与安全、连接实践与诊断**（10 册 170 题）：
  - [01-network-framework.md](07-network/01-network-framework.md)（22 题）
  - [02-cellular-wireless.md](07-network/02-cellular-wireless.md)（26 题）
  - [03-transport-protocols.md](07-network/03-transport-protocols.md)（16 题）
  - [04-app-network-constraints.md](07-network/04-app-network-constraints.md)（14 题）
  - [05-multi-apn-veth.md](07-network/05-multi-apn-veth.md)（19 题）
  - [06-vpn.md](07-network/06-vpn.md)（10 题）
  - [07-vehicle-network.md](07-network/07-vehicle-network.md)（14 题）
  - [08-vehicle-security.md](07-network/08-vehicle-security.md)（8 题）
  - [09-app-network-practice.md](07-network/09-app-network-practice.md)（30 题）
  - [10-network-diagnostics.md](07-network/10-network-diagnostics.md)（10 题）

## 08-platform-services/

- **独立系统服务契约：广播、通知、位置、生物识别、aconfig 运行时、AVF 虚拟化、平台 AI 服务**（7 册 34 题）：
  - [01-broadcast.md](08-platform-services/01-broadcast.md)（4 题）
  - [02-notifications.md](08-platform-services/02-notifications.md)（6 题）
  - [03-location.md](08-platform-services/03-location.md)（6 题）
  - [04-biometrics.md](08-platform-services/04-biometrics.md)（5 题）
  - [05-aconfig-runtime.md](08-platform-services/05-aconfig-runtime.md)（5 题）
  - [06-avf-virtualization.md](08-platform-services/06-avf-virtualization.md)（4 题）
  - [07-ai-services.md](08-platform-services/07-ai-services.md)（4 题）

## 09-platform-native/

- **平台原生层（部分册为二手证据，逐册标注）：内核与 GKI、驱动、Binder 驱动、共享内存、Bionic、logd、BPF、Rust 与应用 Native 稳定性**（9 册 103 题）：
  - [01-kernel-gki.md](09-platform-native/01-kernel-gki.md)（10 题）
  - [02-driver-runtime.md](09-platform-native/02-driver-runtime.md)（12 题）
  - [03-binder-driver.md](09-platform-native/03-binder-driver.md)（11 题）
  - [04-shared-memory.md](09-platform-native/04-shared-memory.md)（10 题）
  - [05-bionic-linker.md](09-platform-native/05-bionic-linker.md)（21 题）
  - [06-logd.md](09-platform-native/06-logd.md)（3 题）
  - [07-bpf.md](09-platform-native/07-bpf.md)（6 题）
  - [08-rust-native.md](09-platform-native/08-rust-native.md)（6 题）
  - [09-app-native-stability.md](09-platform-native/09-app-native-stability.md)（24 题）

## 10-build-system/

- **构建系统：产品配置、Soong 模块、可执行文件、系统镜像、内核构建、内核模块与 aconfig**（7 册 83 题）：
  - [01-product-config.md](10-build-system/01-product-config.md)（15 题）
  - [02-soong-modules.md](10-build-system/02-soong-modules.md)（26 题）
  - [03-android-executables.md](10-build-system/03-android-executables.md)（7 题）
  - [04-android-system-images.md](10-build-system/04-android-system-images.md)（8 题）
  - [05-android-kernel-build.md](10-build-system/05-android-kernel-build.md)（14 题）
  - [06-kernel-modules.md](10-build-system/06-kernel-modules.md)（4 题）
  - [07-aconfig.md](10-build-system/07-aconfig.md)（9 题）

## 11-aaos/

- **AAOS 跨域集成：车机链路、CarService、VHAL、电源与多用户、CarLauncher、车控信号、Kanzi 状态同步。单域的车机纵深册（UI/输入/音频/网络）留在各功能域章**（9 册 101 题）：
  - [01-vehicle-links.md](11-aaos/01-vehicle-links.md)（3 题）
  - [02-car-services.md](11-aaos/02-car-services.md)（12 题）
  - [03-vhal-integration.md](11-aaos/03-vhal-integration.md)（4 题）
  - [04-car-power-users.md](11-aaos/04-car-power-users.md)（4 题）
  - [05-aaos-app-dev.md](11-aaos/05-aaos-app-dev.md)（5 题）
  - [06-car-launcher.md](11-aaos/06-car-launcher.md)（4 题）
  - [07-vehicle-signal-semantics.md](11-aaos/07-vehicle-signal-semantics.md)（24 题）
  - [08-kanzi-state-sync.md](11-aaos/08-kanzi-state-sync.md)（18 题）
  - [09-defect-state-cache-startup.md](11-aaos/09-defect-state-cache-startup.md)（27 题）

## 12-performance/

- **性能与稳定性横切：学习方法、方法论、工具、Perfetto、流畅性/响应/启动/ANR、稳定性与崩溃、可观测性、APM，以及调度与功耗（含原 14-cpu-power 全部内容）**（23 册 632 题）：
  - [01-performance-learning-path.md](12-performance/01-performance-learning-path.md)（8 题）
  - [02-performance-methodology.md](12-performance/02-performance-methodology.md)（35 题）
  - [03-performance-tools.md](12-performance/03-performance-tools.md)（38 题）
  - [04-perfetto-analysis.md](12-performance/04-perfetto-analysis.md)（35 题）
  - [05-smoothness.md](12-performance/05-smoothness.md)（35 题）
  - [06-responsiveness.md](12-performance/06-responsiveness.md)（31 题）
  - [07-app-startup-optimization.md](12-performance/07-app-startup-optimization.md)（34 题）
  - [08-anr.md](12-performance/08-anr.md)（29 题）
  - [09-memory-performance.md](12-performance/09-memory-performance.md)（23 题）
  - [10-power.md](12-performance/10-power.md)（25 题）
  - [11-network-performance.md](12-performance/11-network-performance.md)（20 题）
  - [12-platform-optimization.md](12-performance/12-platform-optimization.md)（13 题）
  - [13-app-stability.md](12-performance/13-app-stability.md)（25 题）
  - [14-app-crash-patterns.md](12-performance/14-app-crash-patterns.md)（20 题）
  - [15-perfetto-advanced.md](12-performance/15-perfetto-advanced.md)（30 题）
  - [16-app-observability-governance.md](12-performance/16-app-observability-governance.md)（27 题）
  - [17-app-observability-diagnostics.md](12-performance/17-app-observability-diagnostics.md)（33 题）
  - [18-apm-platform.md](12-performance/18-apm-platform.md)（30 题）
  - [19-apm-internals.md](12-performance/19-apm-internals.md)（32 题）
  - [20-scheduler-power-framework.md](12-performance/20-scheduler-power-framework.md)（35 题）
  - [21-energy-efficiency.md](12-performance/21-energy-efficiency.md)（18 题）
  - [22-app-power-governance.md](12-performance/22-app-power-governance.md)（28 题）
  - [23-app-cpu-size-optimization.md](12-performance/23-app-cpu-size-optimization.md)（28 题）

## 全册速览（2026-10-06 重排后实测题数）

| 册 | 标题 | 题数 |
| --- | --- | ---: |
| [01-architecture/01-system-architecture.md](01-architecture/01-system-architecture.md) | Android 系统架构 | 19 |
| [01-architecture/02-system-boot.md](01-architecture/02-system-boot.md) | Android 系统启动流程 | 46 |
| [01-architecture/03-binder.md](01-architecture/03-binder.md) | Binder | 22 |
| [01-architecture/04-system-server.md](01-architecture/04-system-server.md) | SystemServer | 11 |
| [01-architecture/05-hal.md](01-architecture/05-hal.md) | HAL | 3 |
| [01-architecture/06-art-runtime.md](01-architecture/06-art-runtime.md) | ART | 20 |
| [01-architecture/07-jni.md](01-architecture/07-jni.md) | JNI | 8 |
| [01-architecture/08-package-management.md](01-architecture/08-package-management.md) | 应用包管理：安装、校验与归档 | 9 |
| [01-architecture/09-app-sandbox.md](01-architecture/09-app-sandbox.md) | 应用沙箱 | 16 |
| [01-architecture/10-permissions.md](01-architecture/10-permissions.md) | Android 权限系统 | 3 |
| [01-architecture/11-selinux.md](01-architecture/11-selinux.md) | Android SELinux | 28 |
| [02-app-framework/01-four-components.md](02-app-framework/01-four-components.md) | Android 四大组件：职责、启动方式与生命周期 | 6 |
| [02-app-framework/02-handler-looper.md](02-app-framework/02-handler-looper.md) | Handler 消息机制与 MessageQueue 实现 | 12 |
| [02-app-framework/03-parcel.md](02-app-framework/03-parcel.md) | Parcel 与序列化契约 | 9 |
| [02-app-framework/04-content-provider.md](02-app-framework/04-content-provider.md) | ContentProvider 服务链路 | 4 |
| [02-app-framework/05-collections-annotations.md](02-app-framework/05-collections-annotations.md) | 集合与注解的框架契约 | 3 |
| [02-app-framework/06-private-space.md](02-app-framework/06-private-space.md) | Private Space 与应用可见性 | 3 |
| [02-app-framework/07-mvp-architecture.md](02-app-framework/07-mvp-architecture.md) | Android MVP 架构 | 7 |
| [02-app-framework/08-app-thread-ipc-stability.md](02-app-framework/08-app-thread-ipc-stability.md) | 稳定性治理：线程、协程与 IPC | 23 |
| [02-app-framework/09-defect-main-thread-async.md](02-app-framework/09-defect-main-thread-async.md) | 主线程与异步时序：缺陷模式与修复范式 | 27 |
| [02-app-framework/10-activity-launch-tasks.md](02-app-framework/10-activity-launch-tasks.md) | Activity 启动、任务栈与 Intent 匹配 | 14 |
| [02-app-framework/11-fragment-viewmodel.md](02-app-framework/11-fragment-viewmodel.md) | Fragment 生命周期与 ViewModel 作用域 | 5 |
| [03-ui/01-activity.md](03-ui/01-activity.md) | Activity 生命周期与首帧 | 14 |
| [03-ui/02-view.md](03-ui/02-view.md) | View 测量、布局与绘制 | 27 |
| [03-ui/03-resources.md](03-ui/03-resources.md) | 资源、主题与多屏适配 | 25 |
| [03-ui/04-window-system.md](03-ui/04-window-system.md) | 窗口系统与 WindowManagerService | 38 |
| [03-ui/05-compose.md](03-ui/05-compose.md) | Compose 运行期与 View 互操作 | 19 |
| [03-ui/06-aaos-ui.md](03-ui/06-aaos-ui.md) | AAOS 车机 UI 架构与 CarService | 19 |
| [03-ui/07-driving-safety.md](03-ui/07-driving-safety.md) | 车机交互安全与驾驶分心 | 18 |
| [03-ui/08-defect-ui-theme-fidelity.md](03-ui/08-defect-ui-theme-fidelity.md) | UI 还原与主题适配：资源完整性与设计稿落地的可迁移规则 | 32 |
| [03-ui/09-ui-debugging.md](03-ui/09-ui-debugging.md) | UI 疑难排查与体验踩坑 | 16 |
| [03-ui/10-input-system.md](03-ui/10-input-system.md) | Android 输入系统：分发、延迟与安全边界 | 27 |
| [03-ui/11-app-event-dispatch.md](03-ui/11-app-event-dispatch.md) | 应用层事件分发：方法链、返回值语义与多点触控 | 21 |
| [03-ui/12-input-reader.md](03-ui/12-input-reader.md) | 设备接入与 InputReader：内核 input 事件、设备分类与触摸适配 | 17 |
| [03-ui/13-key-mapping.md](03-ui/13-key-mapping.md) | 按键系统与键值映射：扫描码、键值定制与物理按键接入 | 17 |
| [03-ui/14-focus-multi-display.md](03-ui/14-focus-multi-display.md) | 焦点分发与多屏输入：窗口命中、每屏焦点与分发管线组件 | 14 |
| [03-ui/15-aaos-input.md](03-ui/15-aaos-input.md) | AAOS 车机输入：VHAL 按键链路、CarInputService 与旋钮 | 17 |
| [03-ui/16-input-diagnostics.md](03-ui/16-input-diagnostics.md) | 输入排查工具与实战：命令族、队列字段与现场决策树 | 17 |
| [04-graphics/01-render-pipeline-vsync.md](04-graphics/01-render-pipeline-vsync.md) | 渲染管线与 VSync 调度 | 36 |
| [04-graphics/02-gpu-composition-display.md](04-graphics/02-gpu-composition-display.md) | GPU 合成与显示管线 | 33 |
| [04-graphics/03-display-service-foldable.md](04-graphics/03-display-service-foldable.md) | 多窗口、折叠屏与显示服务 | 10 |
| [04-graphics/04-graphics-api.md](04-graphics/04-graphics-api.md) | 图形 API：EGL、Vulkan 与 NDK 出图接口 | 15 |
| [04-graphics/05-graphic-stack-preload.md](04-graphics/05-graphic-stack-preload.md) | 图形栈预加载与驱动选择 | 6 |
| [04-graphics/06-camera-pipeline.md](04-graphics/06-camera-pipeline.md) | 相机管线：缓冲、栅栏与时间戳 | 6 |
| [04-graphics/07-media-playback.md](04-graphics/07-media-playback.md) | 视频播放与合成路径 | 6 |
| [04-graphics/08-android-xr.md](04-graphics/08-android-xr.md) | Android XR 应用出图、环境资产与帧预算 | 3 |
| [04-graphics/09-app-image-page-rendering.md](04-graphics/09-app-image-page-rendering.md) | 渲染实战：图像显示、帧率监控与页面切换 | 26 |
| [04-graphics/10-app-media-hybrid-practice.md](04-graphics/10-app-media-hybrid-practice.md) | 渲染优化实战：Vulkan/Impeller、WebView、Media3、CameraX、App Widget 与系统取色 | 33 |
| [04-graphics/11-gpu-diagnostics-tools.md](04-graphics/11-gpu-diagnostics-tools.md) | GPU 与专项工具 | 30 |
| [04-graphics/12-app-view-compose-practice.md](04-graphics/12-app-view-compose-practice.md) | 渲染实战：View 与 Compose 基础 | 26 |
| [04-graphics/13-app-compose-advanced-practice.md](04-graphics/13-app-compose-advanced-practice.md) | 渲染实战：Compose 进阶 | 24 |
| [05-audio/01-aosp-audio.md](05-audio/01-aosp-audio.md) | AOSP 音频子系统 | 19 |
| [05-audio/02-phone-audio-focus.md](05-audio/02-phone-audio-focus.md) | 手机侧音频焦点与路由 | 7 |
| [05-audio/03-aaos-audio.md](05-audio/03-aaos-audio.md) | AAOS 车机音频 | 15 |
| [05-audio/04-audio-latency.md](05-audio/04-audio-latency.md) | 音频延迟与应用实践 | 11 |
| [05-audio/05-bluetooth-audio.md](05-audio/05-bluetooth-audio.md) | 蓝牙音频 | 8 |
| [05-audio/06-audio-decisions.md](05-audio/06-audio-decisions.md) | 音效与音源决策：先决定声音的语义 | 6 |
| [05-audio/07-focus-api.md](05-audio/07-focus-api.md) | 应用焦点契约：怎样申请、响应和释放 | 4 |
| [05-audio/08-focus-flow.md](05-audio/08-focus-flow.md) | 焦点系统调用链：AudioManager 到 CarAudioFocus | 4 |
| [05-audio/09-car-focus.md](05-audio/09-car-focus.md) | AAOS 焦点矩阵与音区：何时共存、抢占、拒绝或等待 | 5 |
| [05-audio/10-routing-volume.md](05-audio/10-routing-volume.md) | 路由配置与音量组：声音走向哪只扬声器 | 4 |
| [05-audio/11-playback-hal.md](05-audio/11-playback-hal.md) | 播放数据与 HAL：PCM 怎样变成车内声音 | 4 |
| [05-audio/12-diagnostics.md](05-audio/12-diagnostics.md) | 全链路实验与排障：从点击到扬声器逐层取证 | 6 |
| [05-audio/13-defect-bluetooth-mechanisms.md](05-audio/13-defect-bluetooth-mechanisms.md) | 蓝牙机制：缺陷模式与修复范式 | 32 |
| [06-memory-storage/01-memory-management.md](06-memory-storage/01-memory-management.md) | Android 内存管理与压力治理 | 33 |
| [06-memory-storage/02-reclaim-compression.md](06-memory-storage/02-reclaim-compression.md) | 回收压缩与专项内存 | 26 |
| [06-memory-storage/03-app-memory-stability.md](06-memory-storage/03-app-memory-stability.md) | 稳定性治理：资源泄漏与进程恢复 | 25 |
| [06-memory-storage/04-app-memory-practice.md](06-memory-storage/04-app-memory-practice.md) | 内存实践：堆预算、泄漏治理、Native 排查与线上监控 | 32 |
| [06-memory-storage/05-partitions.md](06-memory-storage/05-partitions.md) | 运行时分区与挂载 | 4 |
| [06-memory-storage/06-storage-io.md](06-memory-storage/06-storage-io.md) | 存储与 I/O：架构分层、文件系统调度与配置持久化 | 23 |
| [06-memory-storage/07-app-io-storage-practice.md](06-memory-storage/07-app-io-storage-practice.md) | I/O 与存储实践：文件、数据库、缓存、媒体与网络 | 38 |
| [07-network/01-network-framework.md](07-network/01-network-framework.md) | Android 网络框架 | 22 |
| [07-network/02-cellular-wireless.md](07-network/02-cellular-wireless.md) | 蜂窝数据与无线连接 | 26 |
| [07-network/03-transport-protocols.md](07-network/03-transport-protocols.md) | 传输细节与协议设计 | 16 |
| [07-network/04-app-network-constraints.md](07-network/04-app-network-constraints.md) | 应用网络编程与系统约束 | 14 |
| [07-network/05-multi-apn-veth.md](07-network/05-multi-apn-veth.md) | 车机多 APN 与虚拟网卡 | 19 |
| [07-network/06-vpn.md](07-network/06-vpn.md) | Android VPN | 10 |
| [07-network/07-vehicle-network.md](07-network/07-vehicle-network.md) | 车载网络架构与设计 | 14 |
| [07-network/08-vehicle-security.md](07-network/08-vehicle-security.md) | 车机网络安全 | 8 |
| [07-network/09-app-network-practice.md](07-network/09-app-network-practice.md) | 网络与连接实践：HTTPDNS、选网、配额与近场连接治理 | 30 |
| [07-network/10-network-diagnostics.md](07-network/10-network-diagnostics.md) | 网络排查工具与实践 | 10 |
| [08-platform-services/01-broadcast.md](08-platform-services/01-broadcast.md) | 广播队列与投递 | 4 |
| [08-platform-services/02-notifications.md](08-platform-services/02-notifications.md) | 通知服务链路 | 6 |
| [08-platform-services/03-location.md](08-platform-services/03-location.md) | 位置服务链路 | 6 |
| [08-platform-services/04-biometrics.md](08-platform-services/04-biometrics.md) | 生物识别服务链路 | 5 |
| [08-platform-services/05-aconfig-runtime.md](08-platform-services/05-aconfig-runtime.md) | aconfig 运行时：存储与 aflags | 5 |
| [08-platform-services/06-avf-virtualization.md](08-platform-services/06-avf-virtualization.md) | AVF 虚拟化 | 4 |
| [08-platform-services/07-ai-services.md](08-platform-services/07-ai-services.md) | 平台 AI 服务 | 4 |
| [09-platform-native/01-kernel-gki.md](09-platform-native/01-kernel-gki.md) | 内核与 GKI | 10 |
| [09-platform-native/02-driver-runtime.md](09-platform-native/02-driver-runtime.md) | 内核驱动运行时 | 12 |
| [09-platform-native/03-binder-driver.md](09-platform-native/03-binder-driver.md) | Binder 驱动（内核层） | 11 |
| [09-platform-native/04-shared-memory.md](09-platform-native/04-shared-memory.md) | 共享内存：ashmem、ION 与 DMA-BUF | 10 |
| [09-platform-native/05-bionic-linker.md](09-platform-native/05-bionic-linker.md) | Bionic 动态链接器：命名空间隔离与符号解析 | 21 |
| [09-platform-native/06-logd.md](09-platform-native/06-logd.md) | Android 日志调用、丢弃与 logcat 过滤边界 | 3 |
| [09-platform-native/07-bpf.md](09-platform-native/07-bpf.md) | BPF 可观测与可编程边界 | 6 |
| [09-platform-native/08-rust-native.md](09-platform-native/08-rust-native.md) | 平台 Rust 与 FFI | 6 |
| [09-platform-native/09-app-native-stability.md](09-platform-native/09-app-native-stability.md) | 稳定性治理：Native 检测、Hook、动态库与 SDK | 24 |
| [10-build-system/01-product-config.md](10-build-system/01-product-config.md) | Android 产品配置与裁剪 | 15 |
| [10-build-system/02-soong-modules.md](10-build-system/02-soong-modules.md) | AAOS 添加 Soong 模块 | 26 |
| [10-build-system/03-android-executables.md](10-build-system/03-android-executables.md) | Android 可执行文件 | 7 |
| [10-build-system/04-android-system-images.md](10-build-system/04-android-system-images.md) | Android 系统镜像 | 8 |
| [10-build-system/05-android-kernel-build.md](10-build-system/05-android-kernel-build.md) | Android 内核构建与验证 | 14 |
| [10-build-system/06-kernel-modules.md](10-build-system/06-kernel-modules.md) | 内核模块构建与部署 | 4 |
| [10-build-system/07-aconfig.md](10-build-system/07-aconfig.md) | aconfig：声明与构建期代码生成 | 9 |
| [11-aaos/01-vehicle-links.md](11-aaos/01-vehicle-links.md) | Android 车机九类端到端链路、通信边界与排查方法 | 3 |
| [11-aaos/02-car-services.md](11-aaos/02-car-services.md) | CarService 服务速览 | 12 |
| [11-aaos/03-vhal-integration.md](11-aaos/03-vhal-integration.md) | VHAL 集成与契约 | 4 |
| [11-aaos/04-car-power-users.md](11-aaos/04-car-power-users.md) | AAOS 车辆电源、VHAL 属性与多用户服务 | 4 |
| [11-aaos/05-aaos-app-dev.md](11-aaos/05-aaos-app-dev.md) | Android Auto 与 AAOS 应用执行、模板生命周期和媒体性能 | 5 |
| [11-aaos/06-car-launcher.md](11-aaos/06-car-launcher.md) | CarLauncher 实现与任务嵌入 | 4 |
| [11-aaos/07-vehicle-signal-semantics.md](11-aaos/07-vehicle-signal-semantics.md) | 车控信号语义：超时显示、双编码与值域换算 | 24 |
| [11-aaos/08-kanzi-state-sync.md](11-aaos/08-kanzi-state-sync.md) | Kanzi 双端状态同步：状态残留、乐观更新与能力差异 | 18 |
| [11-aaos/09-defect-state-cache-startup.md](11-aaos/09-defect-state-cache-startup.md) | 状态缓存与启动时序：从卡开机到缓存失步的因果链 | 27 |
| [12-performance/01-performance-learning-path.md](12-performance/01-performance-learning-path.md) | 学习方法与检查清单 | 8 |
| [12-performance/02-performance-methodology.md](12-performance/02-performance-methodology.md) | 性能方法论 | 35 |
| [12-performance/03-performance-tools.md](12-performance/03-performance-tools.md) | 性能分析工具 | 38 |
| [12-performance/04-perfetto-analysis.md](12-performance/04-perfetto-analysis.md) | Perfetto 采集与 SQL 分析 | 35 |
| [12-performance/05-smoothness.md](12-performance/05-smoothness.md) | 流畅性：卡顿定义、分析方法与系统链路 | 35 |
| [12-performance/06-responsiveness.md](12-performance/06-responsiveness.md) | 响应速度：从输入到反馈的延迟分析与专项优化 | 31 |
| [12-performance/07-app-startup-optimization.md](12-performance/07-app-startup-optimization.md) | 启动优化：应用侧启动治理 | 34 |
| [12-performance/08-anr.md](12-performance/08-anr.md) | ANR：超时契约、诊断与预警 | 29 |
| [12-performance/09-memory-performance.md](12-performance/09-memory-performance.md) | 内存性能：增长归因、低内存影响与抖动诊断 | 23 |
| [12-performance/10-power.md](12-performance/10-power.md) | Android 功耗：模型、归因与 App 优化 | 25 |
| [12-performance/11-network-performance.md](12-performance/11-network-performance.md) | Android 网络性能：请求分段、TLS 与 DNS 诊断 | 20 |
| [12-performance/12-platform-optimization.md](12-performance/12-platform-optimization.md) | 平台性能优化与前沿评估 | 13 |
| [12-performance/13-app-stability.md](12-performance/13-app-stability.md) | 稳定性治理：度量、崩溃与 ANR | 25 |
| [12-performance/14-app-crash-patterns.md](12-performance/14-app-crash-patterns.md) | 崩溃防护与偶现排查：缺陷形态与排查方法 | 20 |
| [12-performance/15-perfetto-advanced.md](12-performance/15-perfetto-advanced.md) | Perfetto 进阶：Profile 火焰图、CPU 频率、BufferQueue、Agent 协议、SDK 与 FrameTimeline | 30 |
| [12-performance/16-app-observability-governance.md](12-performance/16-app-observability-governance.md) | 可观测性体系与治理 | 27 |
| [12-performance/17-app-observability-diagnostics.md](12-performance/17-app-observability-diagnostics.md) | 可观测性线上诊断 | 33 |
| [12-performance/18-apm-platform.md](12-performance/18-apm-platform.md) | APM 平台与 SDK | 30 |
| [12-performance/19-apm-internals.md](12-performance/19-apm-internals.md) | APM 专项原理与架构 | 32 |
| [12-performance/20-scheduler-power-framework.md](12-performance/20-scheduler-power-framework.md) | 调度与功耗框架 | 35 |
| [12-performance/21-energy-efficiency.md](12-performance/21-energy-efficiency.md) | 能效专项：LLM DVFS、传感器批处理与 CPU Cache | 18 |
| [12-performance/22-app-power-governance.md](12-performance/22-app-power-governance.md) | 功耗优化实践：诊断取证与 App 侧治理 | 28 |
| [12-performance/23-app-cpu-size-optimization.md](12-performance/23-app-cpu-size-optimization.md) | CPU 与体积优化 | 28 |
