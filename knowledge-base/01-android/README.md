# Android 知识目录

> Android 与 AAOS 知识按 13 章组织：03-app-libraries 收应用侧常用库与组件，04-ui 含输入全链路，05-graphics 为图形管线域，07-memory-storage 合并内存与存储，13-performance 按基础、应用、系统、底层与工具组织性能知识。12-aaos 只收跨域集成，单域车机纵深知识留在对应功能章。新增内容先归并到现有主题册。只有主题边界独立且现有册无法承载时才新建册。

## 01-architecture/

- **平台层：分层架构、启动链、SystemServer、Binder/HAL、ART/JNI、包管理、沙箱、权限与 SELinux**（11 册 237 题）：
  - [01-system-architecture.md](01-architecture/01-system-architecture.md)（18 题）
  - [02-system-boot.md](01-architecture/02-system-boot.md)（98 题）
  - [03-binder.md](01-architecture/03-binder.md)（23 题）
  - [04-system-server.md](01-architecture/04-system-server.md)（11 题）
  - [05-hal.md](01-architecture/05-hal.md)（3 题）
  - [06-art-runtime.md](01-architecture/06-art-runtime.md)（20 题）
  - [07-jni.md](01-architecture/07-jni.md)（8 题）
  - [08-package-management.md](01-architecture/08-package-management.md)（9 题）
  - [09-app-sandbox.md](01-architecture/09-app-sandbox.md)（16 题）
  - [10-permissions.md](01-architecture/10-permissions.md)（3 题）
  - [11-selinux.md](01-architecture/11-selinux.md)（28 题）

## 02-app-framework/

- **应用框架与组件：四大组件、Activity 启动与任务栈、Fragment/ViewModel、Handler、Parcel、ContentProvider、MVP、线程与 IPC 稳定性、主线程缺陷、异步与 Flow 测试**（13 册 122 题）：
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
  - [12-async-dispatch.md](02-app-framework/12-async-dispatch.md)（4 题）
  - [13-async-flow-testing.md](02-app-framework/13-async-flow-testing.md)（5 题）

## 03-app-libraries/

- 目录边界与阅读入口见 [03-app-libraries/README.md](03-app-libraries/README.md)。
- **Android 应用常用库与组件：按库或组件分别记录使用机制；性能诊断与优化题留在对应性能册**（2 篇、2 题）：
  - [01-glide.md](03-app-libraries/01-glide.md)（1 题）
  - [02-recyclerview.md](03-app-libraries/02-recyclerview.md)（1 题）

## 04-ui/

- **UI 与输入：Activity 生命周期与首帧、View、资源适配、窗口系统、Compose、AAOS UI、驾驶安全、主题还原缺陷、UI 调试，以及输入全链路（分发、InputReader、按键映射、焦点多屏、AAOS 输入、排查）**（17 册 344 题）：
  - [01-activity.md](04-ui/01-activity.md)（14 题）
  - [02-view.md](04-ui/02-view.md)（27 题）
  - [03-resources.md](04-ui/03-resources.md)（26 题）
  - [04-window-system.md](04-ui/04-window-system.md)（39 题）
  - [05-compose.md](04-ui/05-compose.md)（19 题）
  - [06-aaos-ui.md](04-ui/06-aaos-ui.md)（19 题）
  - [07-driving-safety.md](04-ui/07-driving-safety.md)（18 题）
  - [08-defect-ui-theme-fidelity.md](04-ui/08-defect-ui-theme-fidelity.md)（32 题）
  - [09-ui-debugging.md](04-ui/09-ui-debugging.md)（16 题）
  - [10-input-system.md](04-ui/10-input-system.md)（27 题）
  - [11-app-event-dispatch.md](04-ui/11-app-event-dispatch.md)（21 题）
  - [12-input-reader.md](04-ui/12-input-reader.md)（17 题）
  - [13-key-mapping.md](04-ui/13-key-mapping.md)（17 题）
  - [14-focus-multi-display.md](04-ui/14-focus-multi-display.md)（14 题）
  - [15-aaos-input.md](04-ui/15-aaos-input.md)（17 题）
  - [16-input-diagnostics.md](04-ui/16-input-diagnostics.md)（17 题）
  - [17-ui-assist-services.md](04-ui/17-ui-assist-services.md)（4 题）

## 05-graphics/

- **图形管线域：渲染管线与 VSync、GPU 合成与显示、显示服务与折叠形态、图形 API、相机/媒体、XR、应用渲染实战、GPU 诊断与显示异常排查。它不是 UI 的子集，回答的是“像素如何出现”的完整链路**（14 册 275 题）：
  - [01-render-pipeline-vsync.md](05-graphics/01-render-pipeline-vsync.md)（37 题）
  - [02-gpu-composition-display.md](05-graphics/02-gpu-composition-display.md)（33 题）
  - [03-display-service-foldable.md](05-graphics/03-display-service-foldable.md)（10 题）
  - [04-graphics-api.md](05-graphics/04-graphics-api.md)（16 题）
  - [05-graphic-stack-preload.md](05-graphics/05-graphic-stack-preload.md)（6 题）
  - [06-camera-pipeline.md](05-graphics/06-camera-pipeline.md)（6 题）
  - [07-media-playback.md](05-graphics/07-media-playback.md)（6 题）
  - [08-android-xr.md](05-graphics/08-android-xr.md)（3 题）
  - [09-app-image-page-rendering.md](05-graphics/09-app-image-page-rendering.md)（20 题）
  - [10-app-media-hybrid-practice.md](05-graphics/10-app-media-hybrid-practice.md)（33 题）
  - [11-gpu-diagnostics-tools.md](05-graphics/11-gpu-diagnostics-tools.md)（38 题）
  - [12-app-view-compose-practice.md](05-graphics/12-app-view-compose-practice.md)（26 题）
  - [13-app-compose-advanced-practice.md](05-graphics/13-app-compose-advanced-practice.md)（24 题）
  - [14-display-corruption-diagnostics.md](05-graphics/14-display-corruption-diagnostics.md)（17 题）

## 06-audio/

- **音频全链路：AOSP/AAOS 音频、焦点、延迟与蓝牙音频；06–12 册是点击音场景的连续学习路径（README 见目录内）**（13 册 125 题）：
  - [01-aosp-audio.md](06-audio/01-aosp-audio.md)（19 题）
  - [02-phone-audio-focus.md](06-audio/02-phone-audio-focus.md)（7 题）
  - [03-aaos-audio.md](06-audio/03-aaos-audio.md)（15 题）
  - [04-audio-latency.md](06-audio/04-audio-latency.md)（11 题）
  - [05-bluetooth-audio.md](06-audio/05-bluetooth-audio.md)（8 题）
  - [06-audio-decisions.md](06-audio/06-audio-decisions.md)（6 题）
  - [07-focus-api.md](06-audio/07-focus-api.md)（4 题）
  - [08-focus-flow.md](06-audio/08-focus-flow.md)（4 题）
  - [09-car-focus.md](06-audio/09-car-focus.md)（5 题）
  - [10-routing-volume.md](06-audio/10-routing-volume.md)（4 题）
  - [11-playback-hal.md](06-audio/11-playback-hal.md)（4 题）
  - [12-diagnostics.md](06-audio/12-diagnostics.md)（6 题）
  - [13-defect-bluetooth-mechanisms.md](06-audio/13-defect-bluetooth-mechanisms.md)（32 题）

## 07-memory-storage/

- **资源域：内存治理（GC、lmkd/Freezer、回收压缩、泄漏治理）与存储（分区、存储 I/O、文件/数据库实践）**（7 册 183 题）：
  - [01-memory-management.md](07-memory-storage/01-memory-management.md)（33 题）
  - [02-reclaim-compression.md](07-memory-storage/02-reclaim-compression.md)（26 题）
  - [03-app-memory-stability.md](07-memory-storage/03-app-memory-stability.md)（25 题）
  - [04-app-memory-practice.md](07-memory-storage/04-app-memory-practice.md)（32 题）
  - [05-partitions.md](07-memory-storage/05-partitions.md)（6 题）
  - [06-storage-io.md](07-memory-storage/06-storage-io.md)（23 题）
  - [07-app-io-storage-practice.md](07-memory-storage/07-app-io-storage-practice.md)（38 题）

## 08-network/

- **Android 网络实现与车机网络：框架、蜂窝、传输协议、应用约束、多 APN、VPN、车载网络与安全、连接实践与诊断**（10 册 178 题）：
  - [01-network-framework.md](08-network/01-network-framework.md)（24 题）
  - [02-cellular-wireless.md](08-network/02-cellular-wireless.md)（26 题）
  - [03-transport-protocols.md](08-network/03-transport-protocols.md)（16 题）
  - [04-app-network-constraints.md](08-network/04-app-network-constraints.md)（17 题）
  - [05-multi-apn-veth.md](08-network/05-multi-apn-veth.md)（19 题）
  - [06-vpn.md](08-network/06-vpn.md)（10 题）
  - [07-vehicle-network.md](08-network/07-vehicle-network.md)（14 题）
  - [08-vehicle-security.md](08-network/08-vehicle-security.md)（8 题）
  - [09-app-network-practice.md](08-network/09-app-network-practice.md)（33 题）
  - [10-network-diagnostics.md](08-network/10-network-diagnostics.md)（11 题）

## 09-platform-services/

- **独立系统服务契约：广播、通知、位置、生物识别、aconfig 运行时、AVF 虚拟化、平台 AI 服务**（10 册 46 题）：
  - [01-broadcast.md](09-platform-services/01-broadcast.md)（4 题）
  - [02-notifications.md](09-platform-services/02-notifications.md)（8 题）
  - [03-location.md](09-platform-services/03-location.md)（6 题）
  - [04-biometrics.md](09-platform-services/04-biometrics.md)（6 题）
  - [05-aconfig-runtime.md](09-platform-services/05-aconfig-runtime.md)（5 题）
  - [06-avf-virtualization.md](09-platform-services/06-avf-virtualization.md)（4 题）
  - [07-ai-services.md](09-platform-services/07-ai-services.md)（4 题）
  - [08-keystore-operations.md](09-platform-services/08-keystore-operations.md)（3 题）
  - [09-play-integrity.md](09-platform-services/09-play-integrity.md)（3 题）
  - [10-agent-harness.md](09-platform-services/10-agent-harness.md)（3 题）

## 10-platform-native/

- **平台原生层（部分册为二手证据，逐册标注）：内核与 GKI、驱动、Binder 驱动、共享内存、Bionic、logd、BPF、Rust 与应用 Native 稳定性**（9 册 103 题）：
  - [01-kernel-gki.md](10-platform-native/01-kernel-gki.md)（10 题）
  - [02-driver-runtime.md](10-platform-native/02-driver-runtime.md)（12 题）
  - [03-binder-driver.md](10-platform-native/03-binder-driver.md)（11 题）
  - [04-shared-memory.md](10-platform-native/04-shared-memory.md)（10 题）
  - [05-bionic-linker.md](10-platform-native/05-bionic-linker.md)（21 题）
  - [06-logd.md](10-platform-native/06-logd.md)（3 题）
  - [07-bpf.md](10-platform-native/07-bpf.md)（6 题）
  - [08-rust-native.md](10-platform-native/08-rust-native.md)（6 题）
  - [09-app-native-stability.md](10-platform-native/09-app-native-stability.md)（24 题）

## 11-build-system/

- **构建系统：按构建流水线组织——平台构建主线（产品配置、Soong 模块、可执行文件、系统镜像、OTA 升级包）、内核支线（内核构建、内核模块）与构建期特性开关 aconfig**（8 册 90 题）：
  - [01-product-config.md](11-build-system/01-product-config.md)（17 题）
  - [02-soong-modules.md](11-build-system/02-soong-modules.md)（24 题）
  - [03-executables.md](11-build-system/03-executables.md)（13 题）
  - [04-system-images.md](11-build-system/04-system-images.md)（8 题）
  - [05-ota-packages.md](11-build-system/05-ota-packages.md)（1 题）
  - [06-kernel-build.md](11-build-system/06-kernel-build.md)（14 题）
  - [07-kernel-modules.md](11-build-system/07-kernel-modules.md)（4 题）
  - [08-aconfig.md](11-build-system/08-aconfig.md)（9 题）

## 12-aaos/

- **AAOS 跨域集成：车机链路、CarService、VHAL、电源与多用户、CarLauncher、车控信号、Kanzi 状态同步，以及整车通信、诊断、升级和安全边界。单域的车机纵深册（UI/输入/音频/网络）留在各功能域章**（10 册 64 题）：
  - [01-vehicle-links.md](12-aaos/01-vehicle-links.md)（3 题）
  - [02-car-services.md](12-aaos/02-car-services.md)（12 题）
  - [03-vhal-integration.md](12-aaos/03-vhal-integration.md)（4 题）
  - [04-car-power-users.md](12-aaos/04-car-power-users.md)（4 题）
  - [05-aaos-app-dev.md](12-aaos/05-aaos-app-dev.md)（5 题）
  - [06-car-launcher.md](12-aaos/06-car-launcher.md)（4 题）
  - [07-vehicle-signal-semantics.md](12-aaos/07-vehicle-signal-semantics.md)（8 题）
  - [08-kanzi-state-sync.md](12-aaos/08-kanzi-state-sync.md)（8 题）
  - [09-defect-state-cache-startup.md](12-aaos/09-defect-state-cache-startup.md)（8 题）
  - [10-vehicle-system-boundaries.md](12-aaos/10-vehicle-system-boundaries.md)（8 题）

## 13-performance/

- 目录边界与阅读顺序见 [13-performance/README.md](13-performance/README.md)。
- 按“基础、应用、系统、底层、工具”五类组织；应用、系统和底层按优化方向查找，工具各自成组：
  - [00-基础/](13-performance/00-基础/)：共用性能指标、测量与分析方法的简要归纳。
  - [01-应用/](13-performance/01-应用/)：App 的启动、渲染、内存、网络、CPU、图片加载、能效与稳定性。
  - [02-系统/](13-performance/02-系统/)：Framework、system_server、系统服务与系统 Native。
  - [03-底层/](13-performance/03-底层/)：HAL、内核与驱动。
  - [04-工具/](13-performance/04-工具/)：Perfetto、Profiler、基准测试、线上诊断等独立工具组。
