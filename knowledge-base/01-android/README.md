# Android 学习资料目录

> 学习资料目录（不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。2026-10-03 目录重构：全部文件名改为英文 kebab-case 短主题词；混合主题文档按"读者问题"拆分归位（拆分明细见 `docs/superpowers/plans/android-kb-restructure/` 的迁移台账）；两个空答案已处置（CarLauncher 启动题并入启动册、Android.bp 生效题与同册 Q11 去重）；`11-defects/08` 提交治理迁出至 [../04-exp/](../04-exp/)，`16-project-architecture` 项目个案迁出至 [../career/work-project-analysis/](../career/work-project-analysis/)，`14-network/10` 通用协议地基迁出至 [../网络/](../网络/)。维护者：session-to-knowledge。

## 面试冲刺

- [面试高频索引](面试高频索引.md) — 高频面试主题 → 册·Q 速查（★ 必备 / ★★ 高频 / ★★★ 加分，AAOS 专项段落）

当前目录按学习依赖排序：系统全景 → 应用框架与界面链路 → 系统能力和车机专题 → 平台底层与构建 → 性能实践 → 工具与缺陷。各目录内的两位数字前缀为建议阅读顺序；旧路径与新路径的对应关系见 `docs/superpowers/plans/android-kb-restructure/order-map.tsv`。

## 01-architecture/

- **跨层架构与系统服务：分层架构、启动链、SystemServer、Binder/HAL、ART/JNI、包管理、沙箱、权限与 SELinux**（11 册 174 题）：
  - [01-system-architecture.md](01-architecture/01-system-architecture.md)（18 题）
  - [02-system-boot.md](01-architecture/02-system-boot.md)（43 题）
  - [03-system-server.md](01-architecture/03-system-server.md)（10 题）
  - [04-binder.md](01-architecture/04-binder.md)（18 题）
  - [05-hal.md](01-architecture/05-hal.md)（3 题）
  - [06-art-runtime.md](01-architecture/06-art-runtime.md)（20 题）
  - [07-jni.md](01-architecture/07-jni.md)（6 题）
  - [08-package-management.md](01-architecture/08-package-management.md)（9 题）
  - [09-app-sandbox.md](01-architecture/09-app-sandbox.md)（16 题）
  - [10-permissions.md](01-architecture/10-permissions.md)（3 题）
  - [11-selinux.md](01-architecture/11-selinux.md)（28 题）

## 02-app-framework/

- **应用框架契约：四大组件、Handler/Looper 与 DeliQueue、ContentProvider 链路、Parcel 序列化、集合注解、Private Space**（6 册 28 题）：
  - [01-four-components.md](02-app-framework/01-four-components.md)（6 题）
  - [02-handler-looper.md](02-app-framework/02-handler-looper.md)（12 题）
  - [03-content-provider.md](02-app-framework/03-content-provider.md)（4 题）
  - [04-parcel.md](02-app-framework/04-parcel.md)（2 题）
  - [05-collections-annotations.md](02-app-framework/05-collections-annotations.md)（1 题）
  - [06-private-space.md](02-app-framework/06-private-space.md)（3 题）

## 03-ui/

- **UI 专题：Activity、View、资源适配、窗口系统、Compose、AAOS UI、驾驶安全、UI 排查**（8 册 167 题）：
  - [01-activity.md](03-ui/01-activity.md)（27 题）
  - [02-view.md](03-ui/02-view.md)（27 题）
  - [03-resources.md](03-ui/03-resources.md)（23 题）
  - [04-window-system.md](03-ui/04-window-system.md)（20 题）
  - [05-compose.md](03-ui/05-compose.md)（19 题）
  - [06-aaos-ui.md](03-ui/06-aaos-ui.md)（17 题）
  - [07-driving-safety.md](03-ui/07-driving-safety.md)（18 题）
  - [08-ui-debugging.md](03-ui/08-ui-debugging.md)（16 题）

## 04-input/

- **输入专项：分发全链路、应用层事件、InputReader、按键映射、焦点与多屏、AAOS 车机输入、排查实战**（7 册 130 题）：
  - [01-input-system.md](04-input/01-input-system.md)（27 题）
  - [02-app-event-dispatch.md](04-input/02-app-event-dispatch.md)（21 题）
  - [03-input-reader.md](04-input/03-input-reader.md)（17 题）
  - [04-key-mapping.md](04-input/04-key-mapping.md)（17 题）
  - [05-focus-multi-display.md](04-input/05-focus-multi-display.md)（14 题）
  - [06-aaos-input.md](04-input/06-aaos-input.md)（17 题）
  - [07-input-diagnostics.md](04-input/07-input-diagnostics.md)（17 题）

## 05-rendering/

- **渲染系统：渲染管线与 VSync、GPU 合成与显示管线、图形 API（EGL/Vulkan/NDK/WebGPU）、图形栈预加载、相机与视频管线、Android XR**（8 册 130 题）：
  - [01-render-pipeline-vsync.md](05-rendering/01-render-pipeline-vsync.md)（36 题）
  - [02-gpu-composition-display.md](05-rendering/02-gpu-composition-display.md)（33 题）
  - [03-multi-window-foldable.md](05-rendering/03-multi-window-foldable.md)（25 题）
  - [04-graphics-api.md](05-rendering/04-graphics-api.md)（15 题）
  - [05-graphic-stack-preload.md](05-rendering/05-graphic-stack-preload.md)（6 题）
  - [06-camera-pipeline.md](05-rendering/06-camera-pipeline.md)（6 题）
  - [07-media-playback.md](05-rendering/07-media-playback.md)（6 题）
  - [08-android-xr.md](05-rendering/08-android-xr.md)（3 题）

## 06-storage/

- **存储与 I/O：运行时分区与虚拟 A/B、存储架构、文件系统调度与配置持久化**（2 册 27 题）：
  - [01-partitions.md](06-storage/01-partitions.md)（4 题）
  - [02-storage-io.md](06-storage/02-storage-io.md)（23 题）

## 07-memory/

- **内存管理：内存全景与 GC、lmkd/Freezer、回收压缩、ZRAM、MTE、跨进程内存**（2 册 58 题）：
  - [01-memory-management.md](07-memory/01-memory-management.md)（32 题）
  - [02-reclaim-compression.md](07-memory/02-reclaim-compression.md)（26 题）

## 08-network/

- **网络专项：网络框架、蜂窝连接、应用约束、传输协议、VPN、多 APN、车载网络、安全与排查**（9 册 139 题）：
  - [01-network-framework.md](08-network/01-network-framework.md)（22 题）
  - [02-cellular-wireless.md](08-network/02-cellular-wireless.md)（26 题）
  - [03-app-network-constraints.md](08-network/03-app-network-constraints.md)（14 题）
  - [04-transport-protocols.md](08-network/04-transport-protocols.md)（16 题）
  - [05-vpn.md](08-network/05-vpn.md)（10 题）
  - [06-multi-apn-veth.md](08-network/06-multi-apn-veth.md)（19 题）
  - [07-vehicle-network.md](08-network/07-vehicle-network.md)（14 题）
  - [08-vehicle-security.md](08-network/08-vehicle-security.md)（8 题）
  - [09-network-diagnostics.md](08-network/09-network-diagnostics.md)（10 题）

## 09-audio/

- **音频专项：AOSP 音频、手机侧焦点、AAOS 车机音频、延迟与蓝牙音频；06–12 为点击音场景连续学习路径（README 见目录内）**（12 册 91 题）：
  - [01-aosp-audio.md](09-audio/01-aosp-audio.md)（17 题）
  - [02-phone-audio-focus.md](09-audio/02-phone-audio-focus.md)（7 题）
  - [03-aaos-audio.md](09-audio/03-aaos-audio.md)（15 题）
  - [04-audio-latency.md](09-audio/04-audio-latency.md)（11 题）
  - [05-bluetooth-audio.md](09-audio/05-bluetooth-audio.md)（8 题）
  - [06-audio-decisions.md](09-audio/06-audio-decisions.md)（6 题）
  - [07-focus-api.md](09-audio/07-focus-api.md)（4 题）
  - [08-focus-flow.md](09-audio/08-focus-flow.md)（4 题）
  - [09-car-focus.md](09-audio/09-car-focus.md)（5 题）
  - [10-routing-volume.md](09-audio/10-routing-volume.md)（4 题）
  - [11-playback-hal.md](09-audio/11-playback-hal.md)（4 题）
  - [12-diagnostics.md](09-audio/12-diagnostics.md)（6 题）

## 10-aaos/

- **AAOS 专题：应用开发入口、车机链路地图、CarService、VHAL、车辆电源与多用户、CarLauncher**（6 册 27 题）：
  - [01-aaos-app-dev.md](10-aaos/01-aaos-app-dev.md)（3 题）
  - [02-vehicle-links.md](10-aaos/02-vehicle-links.md)（1 题）
  - [03-car-services.md](10-aaos/03-car-services.md)（12 题）
  - [04-vhal-integration.md](10-aaos/04-vhal-integration.md)（4 题）
  - [05-car-power-users.md](10-aaos/05-car-power-users.md)（3 题）
  - [06-car-launcher.md](10-aaos/06-car-launcher.md)（4 题）

## 11-platform-services/

- **独立系统服务契约：广播、通知、位置、生物识别、aconfig 运行时、AVF 虚拟化、平台 AI 服务**（7 册 34 题）：
  - [01-broadcast.md](11-platform-services/01-broadcast.md)（4 题）
  - [02-notifications.md](11-platform-services/02-notifications.md)（7 题）
  - [03-location.md](11-platform-services/03-location.md)（5 题）
  - [04-biometrics.md](11-platform-services/04-biometrics.md)（5 题）
  - [05-aconfig-runtime.md](11-platform-services/05-aconfig-runtime.md)（5 题）
  - [06-avf-virtualization.md](11-platform-services/06-avf-virtualization.md)（4 题）
  - [07-ai-services.md](11-platform-services/07-ai-services.md)（4 题）

## 12-platform-native/

- **平台原生层（部分册为二手证据，逐册标注）：内核与 GKI、驱动运行时、Binder 驱动、共享内存、Bionic、logd、BPF 与 Rust**（8 册 73 题）：
  - [01-kernel-gki.md](12-platform-native/01-kernel-gki.md)（11 题）
  - [02-driver-runtime.md](12-platform-native/02-driver-runtime.md)（11 题）
  - [03-binder-driver.md](12-platform-native/03-binder-driver.md)（10 题）
  - [04-shared-memory.md](12-platform-native/04-shared-memory.md)（10 题）
  - [05-bionic-linker.md](12-platform-native/05-bionic-linker.md)（21 题）
  - [06-logd.md](12-platform-native/06-logd.md)（3 题）
  - [07-bpf.md](12-platform-native/07-bpf.md)（4 题）
  - [08-rust-native.md](12-platform-native/08-rust-native.md)（3 题）

## 13-build-system/

- **构建系统：产品配置、Soong 模块、可执行文件、系统镜像、内核构建、内核模块与 aconfig**（7 册 82 题）：
  - [01-product-config.md](13-build-system/01-product-config.md)（15 题）
  - [02-soong-modules.md](13-build-system/02-soong-modules.md)（26 题）
  - [03-android-executables.md](13-build-system/03-android-executables.md)（6 题）
  - [04-android-system-images.md](13-build-system/04-android-system-images.md)（8 题）
  - [05-android-kernel-build.md](13-build-system/05-android-kernel-build.md)（14 题）
  - [06-kernel-modules.md](13-build-system/06-kernel-modules.md)（4 题）
  - [07-aconfig.md](13-build-system/07-aconfig.md)（9 题）

## 14-cpu-power/

- **CPU 调度与能耗：cgroup/task profile、EAS/DVFS/Thermal、Power HAL、能效专项**（2 册 53 题）：
  - [01-scheduler-power-framework.md](14-cpu-power/01-scheduler-power-framework.md)（35 题）
  - [02-energy-efficiency.md](14-cpu-power/02-energy-efficiency.md)（18 题）

## 15-performance/

- **性能问题：流畅性、响应速度、ANR、内存性能、功耗、网络性能、平台优化与前沿评估**（7 册 176 题）：
  - [01-smoothness.md](15-performance/01-smoothness.md)（35 题）
  - [02-responsiveness.md](15-performance/02-responsiveness.md)（31 题）
  - [03-anr.md](15-performance/03-anr.md)（29 题）
  - [04-memory-performance.md](15-performance/04-memory-performance.md)（23 题）
  - [05-power.md](15-performance/05-power.md)（25 题）
  - [06-network-performance.md](15-performance/06-network-performance.md)（20 题）
  - [07-platform-optimization.md](15-performance/07-platform-optimization.md)（13 题）

## 16-app-practice/

- **应用实践：资源注解与应用架构、稳定性治理、启动与渲染优化、内存/I/O/网络/功耗实践、可观测性、CPU 与体积**（18 册 461 题）：
  - [01-resource-annotations.md](16-app-practice/01-resource-annotations.md)（2 题）
  - [02-mvp-architecture.md](16-app-practice/02-mvp-architecture.md)（6 题）
  - [03-stability-metrics-crash.md](16-app-practice/03-stability-metrics-crash.md)（25 题）
  - [04-stability-leaks.md](16-app-practice/04-stability-leaks.md)（25 题）
  - [05-stability-threads-ipc.md](16-app-practice/05-stability-threads-ipc.md)（21 题）
  - [06-stability-native-sdk.md](16-app-practice/06-stability-native-sdk.md)（24 题）
  - [07-startup-optimization.md](16-app-practice/07-startup-optimization.md)（34 题）
  - [08-rendering-view-compose.md](16-app-practice/08-rendering-view-compose.md)（24 题）
  - [09-rendering-compose-advanced.md](16-app-practice/09-rendering-compose-advanced.md)（24 题）
  - [10-rendering-image-pages.md](16-app-practice/10-rendering-image-pages.md)（26 题）
  - [11-rendering-media-hybrid.md](16-app-practice/11-rendering-media-hybrid.md)（33 题）
  - [12-memory-practice.md](16-app-practice/12-memory-practice.md)（32 题）
  - [13-io-storage-practice.md](16-app-practice/13-io-storage-practice.md)（39 题）
  - [14-network-practice.md](16-app-practice/14-network-practice.md)（30 题）
  - [15-power-practice.md](16-app-practice/15-power-practice.md)（28 题）
  - [16-cpu-size-optimization.md](16-app-practice/16-cpu-size-optimization.md)（28 题）
  - [17-observability-governance.md](16-app-practice/17-observability-governance.md)（27 题）
  - [18-observability-diagnostics.md](16-app-practice/18-observability-diagnostics.md)（33 题）

## 17-tools/

- **工具与方法论：诊断与性能方法、分析工具、Perfetto、GPU 专项工具与 APM**（8 册 238 题）：
  - [01-diagnosis-method.md](17-tools/01-diagnosis-method.md)（8 题）
  - [02-performance-methodology.md](17-tools/02-performance-methodology.md)（35 题）
  - [03-performance-tools.md](17-tools/03-performance-tools.md)（38 题）
  - [04-perfetto-sql.md](17-tools/04-perfetto-sql.md)（35 题）
  - [05-perfetto-advanced.md](17-tools/05-perfetto-advanced.md)（30 题）
  - [06-gpu-tools.md](17-tools/06-gpu-tools.md)（30 题）
  - [07-apm-platform.md](17-tools/07-apm-platform.md)（30 题）
  - [08-apm-internals.md](17-tools/08-apm-internals.md)（32 题）

## 18-defect-patterns/

- **跨项目技术缺陷复盘（某车机项目）：主线程时序、状态缓存、崩溃防护、UI 还原、蓝牙、车控信号与 Kanzi 同步**（7 册 177 题）：
  - [01-main-thread-async.md](18-defect-patterns/01-main-thread-async.md)（27 题）
  - [02-state-cache-startup.md](18-defect-patterns/02-state-cache-startup.md)（26 题）
  - [03-crash-protection.md](18-defect-patterns/03-crash-protection.md)（20 题）
  - [04-ui-theme-fidelity.md](18-defect-patterns/04-ui-theme-fidelity.md)（31 题）
  - [05-bluetooth-mechanisms.md](18-defect-patterns/05-bluetooth-mechanisms.md)（31 题）
  - [06-vehicle-signal-semantics.md](18-defect-patterns/06-vehicle-signal-semantics.md)（24 题）
  - [07-kanzi-state-sync.md](18-defect-patterns/07-kanzi-state-sync.md)（18 题）

## 配套与外部

- [09-audio/README.md](09-audio/README.md) — AAOS 13 音频焦点与播放的连续学习路径说明（06–12 册场景线）
- 早期长文：[framework/](../../docs/others/framework/)、[性能优化.md](../../docs/others/性能优化.md)、[Launcher3_Technical_Document.md](../../docs/others/Launcher3_Technical_Document.md)、[OTA_LIFECYCLE.md](../../docs/others/OTA_LIFECYCLE.md)、[MVVM_Optimization_Report.md](../../docs/others/MVVM_Optimization_Report.md)
- 项目个案：[../career/work-project-analysis/](../career/work-project-analysis/)；通用协议地基：[../网络/](../网络/)

## 全册速览（2026-10-03 重排后实测题数）

| 册 | 标题 | 题数 |
| --- | --- | ---: |
| [01-architecture/01-system-architecture.md](01-architecture/01-system-architecture.md) | Android 系统架构 | 18 |
| [01-architecture/02-system-boot.md](01-architecture/02-system-boot.md) | Android 系统启动流程 | 43 |
| [01-architecture/03-system-server.md](01-architecture/03-system-server.md) | SystemServer | 10 |
| [01-architecture/04-binder.md](01-architecture/04-binder.md) | Binder | 18 |
| [01-architecture/05-hal.md](01-architecture/05-hal.md) | HAL | 3 |
| [01-architecture/06-art-runtime.md](01-architecture/06-art-runtime.md) | ART | 20 |
| [01-architecture/07-jni.md](01-architecture/07-jni.md) | JNI | 6 |
| [01-architecture/08-package-management.md](01-architecture/08-package-management.md) | 应用包管理：安装、校验与归档 | 9 |
| [01-architecture/09-app-sandbox.md](01-architecture/09-app-sandbox.md) | 应用沙箱 | 16 |
| [01-architecture/10-permissions.md](01-architecture/10-permissions.md) | Android 权限系统（android.permission.*） | 3 |
| [01-architecture/11-selinux.md](01-architecture/11-selinux.md) | Android SELinux | 28 |
| [02-app-framework/01-four-components.md](02-app-framework/01-four-components.md) | Android 四大组件：职责、启动方式与生命周期 | 6 |
| [02-app-framework/02-handler-looper.md](02-app-framework/02-handler-looper.md) | Handler 消息机制与 MessageQueue 实现 | 12 |
| [02-app-framework/03-content-provider.md](02-app-framework/03-content-provider.md) | ContentProvider 服务链路 | 4 |
| [02-app-framework/04-parcel.md](02-app-framework/04-parcel.md) | Parcel 与序列化契约 | 2 |
| [02-app-framework/05-collections-annotations.md](02-app-framework/05-collections-annotations.md) | 集合与注解的框架契约 | 1 |
| [02-app-framework/06-private-space.md](02-app-framework/06-private-space.md) | Private Space 与应用可见性 | 3 |
| [03-ui/01-activity.md](03-ui/01-activity.md) | Activity 与窗口生命周期 | 27 |
| [03-ui/02-view.md](03-ui/02-view.md) | View 测量、布局与绘制 | 27 |
| [03-ui/03-resources.md](03-ui/03-resources.md) | 资源、主题与多屏适配 | 23 |
| [03-ui/04-window-system.md](03-ui/04-window-system.md) | 窗口系统与 WindowManagerService | 20 |
| [03-ui/05-compose.md](03-ui/05-compose.md) | Compose 运行期与 View 互操作 | 19 |
| [03-ui/06-aaos-ui.md](03-ui/06-aaos-ui.md) | AAOS 车机 UI 架构与 CarService | 17 |
| [03-ui/07-driving-safety.md](03-ui/07-driving-safety.md) | 车机交互安全与驾驶分心 | 18 |
| [03-ui/08-ui-debugging.md](03-ui/08-ui-debugging.md) | UI 疑难排查与体验踩坑 | 16 |
| [04-input/01-input-system.md](04-input/01-input-system.md) | Android 输入系统：分发、延迟与安全边界 | 27 |
| [04-input/02-app-event-dispatch.md](04-input/02-app-event-dispatch.md) | 应用层事件分发：方法链、返回值语义与多点触控 | 21 |
| [04-input/03-input-reader.md](04-input/03-input-reader.md) | 设备接入与 InputReader：内核 input 事件、设备分类与触摸适配 | 17 |
| [04-input/04-key-mapping.md](04-input/04-key-mapping.md) | 按键系统与键值映射：扫描码、键值定制与物理按键接入 | 17 |
| [04-input/05-focus-multi-display.md](04-input/05-focus-multi-display.md) | 焦点分发与多屏输入：窗口命中、每屏焦点与分发管线组件 | 14 |
| [04-input/06-aaos-input.md](04-input/06-aaos-input.md) | AAOS 车机输入：VHAL 按键链路、CarInputService 与旋钮 | 17 |
| [04-input/07-input-diagnostics.md](04-input/07-input-diagnostics.md) | 输入排查工具与实战：命令族、队列字段与现场决策树 | 17 |
| [05-rendering/01-render-pipeline-vsync.md](05-rendering/01-render-pipeline-vsync.md) | 渲染管线与 VSync 调度 | 36 |
| [05-rendering/02-gpu-composition-display.md](05-rendering/02-gpu-composition-display.md) | GPU 合成与显示管线 | 33 |
| [05-rendering/03-multi-window-foldable.md](05-rendering/03-multi-window-foldable.md) | 多窗口、折叠屏与显示服务 | 25 |
| [05-rendering/04-graphics-api.md](05-rendering/04-graphics-api.md) | 图形 API：EGL、Vulkan 与 NDK 出图接口 | 15 |
| [05-rendering/05-graphic-stack-preload.md](05-rendering/05-graphic-stack-preload.md) | 图形栈预加载与驱动选择 | 6 |
| [05-rendering/06-camera-pipeline.md](05-rendering/06-camera-pipeline.md) | 相机管线：缓冲、栅栏与时间戳 | 6 |
| [05-rendering/07-media-playback.md](05-rendering/07-media-playback.md) | 视频播放与合成路径 | 6 |
| [05-rendering/08-android-xr.md](05-rendering/08-android-xr.md) | Android XR 出图与预算 | 3 |
| [06-storage/01-partitions.md](06-storage/01-partitions.md) | 运行时分区与挂载 | 4 |
| [06-storage/02-storage-io.md](06-storage/02-storage-io.md) | 存储与 I/O：架构分层、文件系统调度与配置持久化 | 23 |
| [07-memory/01-memory-management.md](07-memory/01-memory-management.md) | Android 内存管理与压力治理 | 32 |
| [07-memory/02-reclaim-compression.md](07-memory/02-reclaim-compression.md) | 回收压缩与专项内存 | 26 |
| [08-network/01-network-framework.md](08-network/01-network-framework.md) | Android 网络框架 | 22 |
| [08-network/02-cellular-wireless.md](08-network/02-cellular-wireless.md) | 蜂窝数据与无线连接 | 26 |
| [08-network/03-app-network-constraints.md](08-network/03-app-network-constraints.md) | 应用网络编程与系统约束 | 14 |
| [08-network/04-transport-protocols.md](08-network/04-transport-protocols.md) | 传输细节与协议设计 | 16 |
| [08-network/05-vpn.md](08-network/05-vpn.md) | Android VPN | 10 |
| [08-network/06-multi-apn-veth.md](08-network/06-multi-apn-veth.md) | 车机多 APN 与虚拟网卡 | 19 |
| [08-network/07-vehicle-network.md](08-network/07-vehicle-network.md) | 车载网络架构与设计 | 14 |
| [08-network/08-vehicle-security.md](08-network/08-vehicle-security.md) | 车机网络安全 | 8 |
| [08-network/09-network-diagnostics.md](08-network/09-network-diagnostics.md) | 网络排查工具与实践 | 10 |
| [09-audio/01-aosp-audio.md](09-audio/01-aosp-audio.md) | AOSP 音频子系统 | 17 |
| [09-audio/02-phone-audio-focus.md](09-audio/02-phone-audio-focus.md) | 手机侧音频焦点与路由 | 7 |
| [09-audio/03-aaos-audio.md](09-audio/03-aaos-audio.md) | AAOS 车机音频 | 15 |
| [09-audio/04-audio-latency.md](09-audio/04-audio-latency.md) | 音频延迟与应用实践 | 11 |
| [09-audio/05-bluetooth-audio.md](09-audio/05-bluetooth-audio.md) | 蓝牙音频 | 8 |
| [09-audio/06-audio-decisions.md](09-audio/06-audio-decisions.md) | 音效与音源决策：先决定声音的语义 | 6 |
| [09-audio/07-focus-api.md](09-audio/07-focus-api.md) | 应用焦点契约：怎样申请、响应和释放 | 4 |
| [09-audio/08-focus-flow.md](09-audio/08-focus-flow.md) | 焦点系统调用链：AudioManager 到 CarAudioFocus | 4 |
| [09-audio/09-car-focus.md](09-audio/09-car-focus.md) | AAOS 焦点矩阵与音区：何时共存、抢占、拒绝或等待 | 5 |
| [09-audio/10-routing-volume.md](09-audio/10-routing-volume.md) | 路由配置与音量组：声音走向哪只扬声器 | 4 |
| [09-audio/11-playback-hal.md](09-audio/11-playback-hal.md) | 播放数据与 HAL：PCM 怎样变成车内声音 | 4 |
| [09-audio/12-diagnostics.md](09-audio/12-diagnostics.md) | 全链路实验与排障：从点击到扬声器逐层取证 | 6 |
| [10-aaos/01-aaos-app-dev.md](10-aaos/01-aaos-app-dev.md) | AAOS 应用开发要点 | 3 |
| [10-aaos/02-vehicle-links.md](10-aaos/02-vehicle-links.md) | 车机链路场景地图 | 1 |
| [10-aaos/03-car-services.md](10-aaos/03-car-services.md) | CarService 服务速览 | 12 |
| [10-aaos/04-vhal-integration.md](10-aaos/04-vhal-integration.md) | VHAL 集成与契约 | 4 |
| [10-aaos/05-car-power-users.md](10-aaos/05-car-power-users.md) | 车辆电源与多用户 | 3 |
| [10-aaos/06-car-launcher.md](10-aaos/06-car-launcher.md) | CarLauncher 实现与任务嵌入 | 4 |
| [11-platform-services/01-broadcast.md](11-platform-services/01-broadcast.md) | 广播队列与投递 | 4 |
| [11-platform-services/02-notifications.md](11-platform-services/02-notifications.md) | 通知服务链路 | 7 |
| [11-platform-services/03-location.md](11-platform-services/03-location.md) | 位置服务链路 | 5 |
| [11-platform-services/04-biometrics.md](11-platform-services/04-biometrics.md) | 生物识别服务链路 | 5 |
| [11-platform-services/05-aconfig-runtime.md](11-platform-services/05-aconfig-runtime.md) | aconfig 运行时：存储与 aflags | 5 |
| [11-platform-services/06-avf-virtualization.md](11-platform-services/06-avf-virtualization.md) | AVF 虚拟化 | 4 |
| [11-platform-services/07-ai-services.md](11-platform-services/07-ai-services.md) | 平台 AI 服务 | 4 |
| [12-platform-native/01-kernel-gki.md](12-platform-native/01-kernel-gki.md) | 内核与 GKI | 11 |
| [12-platform-native/02-driver-runtime.md](12-platform-native/02-driver-runtime.md) | 内核驱动运行时 | 11 |
| [12-platform-native/03-binder-driver.md](12-platform-native/03-binder-driver.md) | Binder 驱动（内核层） | 10 |
| [12-platform-native/04-shared-memory.md](12-platform-native/04-shared-memory.md) | 共享内存：ashmem、ION 与 DMA-BUF | 10 |
| [12-platform-native/05-bionic-linker.md](12-platform-native/05-bionic-linker.md) | Bionic 动态链接器：命名空间隔离与符号解析 | 21 |
| [12-platform-native/06-logd.md](12-platform-native/06-logd.md) | logd 日志链路 | 3 |
| [12-platform-native/07-bpf.md](12-platform-native/07-bpf.md) | BPF 可观测与可编程边界 | 4 |
| [12-platform-native/08-rust-native.md](12-platform-native/08-rust-native.md) | 平台 Rust 与 FFI | 3 |
| [13-build-system/01-product-config.md](13-build-system/01-product-config.md) | Android 产品配置与裁剪 | 15 |
| [13-build-system/02-soong-modules.md](13-build-system/02-soong-modules.md) | AAOS 添加 Soong 模块 | 26 |
| [13-build-system/03-android-executables.md](13-build-system/03-android-executables.md) | Android 可执行文件 | 6 |
| [13-build-system/04-android-system-images.md](13-build-system/04-android-system-images.md) | Android 系统镜像 | 8 |
| [13-build-system/05-android-kernel-build.md](13-build-system/05-android-kernel-build.md) | Android 内核构建与验证 | 14 |
| [13-build-system/06-kernel-modules.md](13-build-system/06-kernel-modules.md) | 内核模块构建与部署 | 4 |
| [13-build-system/07-aconfig.md](13-build-system/07-aconfig.md) | aconfig：声明与构建期代码生成 | 9 |
| [14-cpu-power/01-scheduler-power-framework.md](14-cpu-power/01-scheduler-power-framework.md) | 调度与功耗框架 | 35 |
| [14-cpu-power/02-energy-efficiency.md](14-cpu-power/02-energy-efficiency.md) | 能效专项：LLM DVFS、传感器批处理与 CPU Cache | 18 |
| [15-performance/01-smoothness.md](15-performance/01-smoothness.md) | 流畅性：卡顿定义、分析方法与系统链路 | 35 |
| [15-performance/02-responsiveness.md](15-performance/02-responsiveness.md) | 响应速度：从输入到反馈的延迟分析与专项优化 | 31 |
| [15-performance/03-anr.md](15-performance/03-anr.md) | ANR：超时契约、诊断与预警 | 29 |
| [15-performance/04-memory-performance.md](15-performance/04-memory-performance.md) | 内存性能：增长归因、低内存影响与抖动诊断 | 23 |
| [15-performance/05-power.md](15-performance/05-power.md) | Android 功耗：模型、归因与 App 优化 | 25 |
| [15-performance/06-network-performance.md](15-performance/06-network-performance.md) | Android 网络性能：请求分段、TLS 与 DNS 诊断 | 20 |
| [15-performance/07-platform-optimization.md](15-performance/07-platform-optimization.md) | 平台性能优化与前沿评估 | 13 |
| [16-app-practice/01-resource-annotations.md](16-app-practice/01-resource-annotations.md) | Android 资源与值域注解 | 2 |
| [16-app-practice/02-mvp-architecture.md](16-app-practice/02-mvp-architecture.md) | Android MVP 架构 | 6 |
| [16-app-practice/03-stability-metrics-crash.md](16-app-practice/03-stability-metrics-crash.md) | 稳定性治理：度量、崩溃与 ANR | 25 |
| [16-app-practice/04-stability-leaks.md](16-app-practice/04-stability-leaks.md) | 稳定性治理：资源泄漏与进程恢复 | 25 |
| [16-app-practice/05-stability-threads-ipc.md](16-app-practice/05-stability-threads-ipc.md) | 稳定性治理：线程、协程与 IPC | 21 |
| [16-app-practice/06-stability-native-sdk.md](16-app-practice/06-stability-native-sdk.md) | 稳定性治理：Native 检测、Hook、动态库与 SDK | 24 |
| [16-app-practice/07-startup-optimization.md](16-app-practice/07-startup-optimization.md) | 启动优化：应用侧启动治理 | 34 |
| [16-app-practice/08-rendering-view-compose.md](16-app-practice/08-rendering-view-compose.md) | 渲染实战：View 与 Compose 基础 | 24 |
| [16-app-practice/09-rendering-compose-advanced.md](16-app-practice/09-rendering-compose-advanced.md) | 渲染实战：Compose 进阶 | 24 |
| [16-app-practice/10-rendering-image-pages.md](16-app-practice/10-rendering-image-pages.md) | 渲染实战：图像显示、帧率监控与页面切换 | 26 |
| [16-app-practice/11-rendering-media-hybrid.md](16-app-practice/11-rendering-media-hybrid.md) | 渲染优化实战：Vulkan/Impeller、WebView、Media3、CameraX、App Widget 与系统取色 | 33 |
| [16-app-practice/12-memory-practice.md](16-app-practice/12-memory-practice.md) | 内存实践：堆预算、泄漏治理、Native 排查与线上监控 | 32 |
| [16-app-practice/13-io-storage-practice.md](16-app-practice/13-io-storage-practice.md) | I/O 与存储实践：文件、数据库、缓存、媒体与网络 | 39 |
| [16-app-practice/14-network-practice.md](16-app-practice/14-network-practice.md) | 网络与连接实践：HTTPDNS、选网、配额与近场连接治理 | 30 |
| [16-app-practice/15-power-practice.md](16-app-practice/15-power-practice.md) | 功耗优化实践：诊断取证与 App 侧治理 | 28 |
| [16-app-practice/16-cpu-size-optimization.md](16-app-practice/16-cpu-size-optimization.md) | CPU 与体积优化 | 28 |
| [16-app-practice/17-observability-governance.md](16-app-practice/17-observability-governance.md) | 可观测性体系与治理 | 27 |
| [16-app-practice/18-observability-diagnostics.md](16-app-practice/18-observability-diagnostics.md) | 可观测性线上诊断 | 33 |
| [17-tools/01-diagnosis-method.md](17-tools/01-diagnosis-method.md) | 学习方法与检查清单 | 8 |
| [17-tools/02-performance-methodology.md](17-tools/02-performance-methodology.md) | 性能方法论 | 35 |
| [17-tools/03-performance-tools.md](17-tools/03-performance-tools.md) | 性能分析工具 | 38 |
| [17-tools/04-perfetto-sql.md](17-tools/04-perfetto-sql.md) | Perfetto 采集与 SQL 分析 | 35 |
| [17-tools/05-perfetto-advanced.md](17-tools/05-perfetto-advanced.md) | Perfetto 进阶：Profile 火焰图、CPU 频率、BufferQueue、Agent 协议、SDK 与 FrameTimeline | 30 |
| [17-tools/06-gpu-tools.md](17-tools/06-gpu-tools.md) | GPU 与专项工具 | 30 |
| [17-tools/07-apm-platform.md](17-tools/07-apm-platform.md) | APM 平台与 SDK | 30 |
| [17-tools/08-apm-internals.md](17-tools/08-apm-internals.md) | APM 专项原理与架构 | 32 |
| [18-defect-patterns/01-main-thread-async.md](18-defect-patterns/01-main-thread-async.md) | 主线程与异步时序：缺陷模式与修复范式 | 27 |
| [18-defect-patterns/02-state-cache-startup.md](18-defect-patterns/02-state-cache-startup.md) | 状态缓存与启动时序：从卡开机到缓存失步的因果链 | 26 |
| [18-defect-patterns/03-crash-protection.md](18-defect-patterns/03-crash-protection.md) | 崩溃防护与偶现排查：缺陷形态与排查方法 | 20 |
| [18-defect-patterns/04-ui-theme-fidelity.md](18-defect-patterns/04-ui-theme-fidelity.md) | UI 还原与主题适配：资源完整性与设计稿落地的可迁移规则 | 31 |
| [18-defect-patterns/05-bluetooth-mechanisms.md](18-defect-patterns/05-bluetooth-mechanisms.md) | 蓝牙机制：缺陷模式与修复范式 | 31 |
| [18-defect-patterns/06-vehicle-signal-semantics.md](18-defect-patterns/06-vehicle-signal-semantics.md) | 车控信号语义：超时显示、双编码与值域换算 | 24 |
| [18-defect-patterns/07-kanzi-state-sync.md](18-defect-patterns/07-kanzi-state-sync.md) | Kanzi 双端状态同步：状态残留、乐观更新与能力差异 | 18 |
