#!/usr/bin/env python3
"""Migration spec: every old Q's destination. Keys: old rel path (without knowledge-base/01-android/ prefix) -> list of (old_q, new_path, note)."""

# Destination files that are pure renames (content order unchanged, no moves in/out)
PURE_RENAMES = {
    # 01-architecture
    "01-architecture/01-Android系统架构.md": "01-architecture/01-system-architecture.md",
    "01-architecture/02-Android系统启动流程.md": "01-architecture/02-system-boot.md",
    "01-architecture/03-Binder.md": "01-architecture/03-binder.md",
    "01-architecture/04-Sanbox.md": "01-architecture/04-app-sandbox.md",
    "01-architecture/05-Art.md": "01-architecture/05-art-runtime.md",
    "01-architecture/06-JNI.md": "01-architecture/06-jni.md",
    "01-architecture/08-SystemServer.md": "01-architecture/08-system-server.md",
    "01-architecture/09-HAL.md": "01-architecture/09-hal.md",
    "01-architecture/20-SELinux.md": "01-architecture/20-selinux.md",
    "01-architecture/21-Permission.md": "01-architecture/21-permissions.md",
    # 02-rendering
    "02-rendering/01-渲染管线与VSync调度.md": "02-rendering/01-render-pipeline-vsync.md",
    "02-rendering/02-GPU合成与显示管线.md": "02-rendering/02-gpu-composition-display.md",
    "02-rendering/03-多窗口折叠屏与显示服务.md": "02-rendering/03-multi-window-foldable.md",
    # 03-input
    "03-input/01-输入系统.md": "03-input/01-input-system.md",
    "03-input/02-应用层事件分发与多点触控.md": "03-input/02-app-event-dispatch.md",
    "03-input/03-按键系统与键值映射.md": "03-input/03-key-mapping.md",
    "03-input/04-设备接入与InputReader.md": "03-input/04-input-reader.md",
    "03-input/05-焦点分发与多屏输入.md": "03-input/05-focus-multi-display.md",
    "03-input/06-AAOS车机输入.md": "03-input/06-aaos-input.md",
    "03-input/07-输入排查工具与实战.md": "03-input/07-input-diagnostics.md",
    # 04-storage
    "04-storage/01-存储与IO.md": "04-storage/01-storage-io.md",
    # 05-memory
    "05-memory/01-内存管理与压力治理.md": "05-memory/01-memory-management.md",
    "05-memory/02-回收压缩与专项内存.md": "05-memory/02-reclaim-compression.md",
    # 07-performance
    "07-performance/01-流畅性.md": "07-performance/01-smoothness.md",
    "07-performance/02-响应速度.md": "07-performance/02-responsiveness.md",
    "07-performance/03-ANR.md": "07-performance/03-anr.md",
    "07-performance/04-内存性能.md": "07-performance/04-memory-performance.md",
    "07-performance/05-功耗.md": "07-performance/05-power.md",
    "07-performance/06-网络性能.md": "07-performance/06-network-performance.md",
    # 08-cpu-power
    "08-cpu-power/01-调度与功耗框架.md": "08-cpu-power/01-scheduler-power-framework.md",
    "08-cpu-power/02-能效专项.md": "08-cpu-power/02-energy-efficiency.md",
    # 09-app-practice
    "09-app-practice/01-稳定性治理-度量与崩溃.md": "09-app-practice/01-stability-metrics-crash.md",
    "09-app-practice/02-稳定性治理-资源泄漏.md": "09-app-practice/02-stability-leaks.md",
    "09-app-practice/03-稳定性治理-线程与IPC.md": "09-app-practice/03-stability-threads-ipc.md",
    "09-app-practice/04-稳定性治理-Native与SDK.md": "09-app-practice/04-stability-native-sdk.md",
    "09-app-practice/05-启动优化.md": "09-app-practice/05-startup-optimization.md",
    "09-app-practice/06-渲染实战-View与Compose基础.md": "09-app-practice/06-rendering-view-compose.md",
    "09-app-practice/07-渲染实战-Compose进阶.md": "09-app-practice/07-rendering-compose-advanced.md",
    "09-app-practice/08-渲染实战-图像显示与页面.md": "09-app-practice/08-rendering-image-pages.md",
    "09-app-practice/09-渲染实战-媒体与混合栈.md": "09-app-practice/09-rendering-media-hybrid.md",
    "09-app-practice/10-内存实践.md": "09-app-practice/10-memory-practice.md",
    "09-app-practice/11-IO与存储实践.md": "09-app-practice/11-io-storage-practice.md",
    "09-app-practice/12-网络与连接实践.md": "09-app-practice/12-network-practice.md",
    "09-app-practice/13-功耗优化实践.md": "09-app-practice/13-power-practice.md",
    "09-app-practice/14-CPU与体积优化.md": "09-app-practice/14-cpu-size-optimization.md",
    "09-app-practice/15-可观测性-体系与治理.md": "09-app-practice/15-observability-governance.md",
    "09-app-practice/16-可观测性-线上诊断.md": "09-app-practice/16-observability-diagnostics.md",
    "09-app-practice/17-资源与值域注解.md": "09-app-practice/17-resource-annotations.md",
    "09-app-practice/19-MVP架构.md": "09-app-practice/19-mvp-architecture.md",
    # 10-tools
    "10-tools/01-Perfetto-采集与SQL分析.md": "10-tools/01-perfetto-sql.md",
    "10-tools/02-Perfetto-进阶与SDK.md": "10-tools/02-perfetto-advanced.md",
    "10-tools/03-性能分析工具.md": "10-tools/03-performance-tools.md",
    "10-tools/04-GPU与专项工具.md": "10-tools/04-gpu-tools.md",
    "10-tools/05-性能方法论.md": "10-tools/05-performance-methodology.md",
    "10-tools/06-APM-平台与SDK.md": "10-tools/06-apm-platform.md",
    "10-tools/07-APM-专项原理与架构.md": "10-tools/07-apm-internals.md",
    "10-tools/08-学习方法与检查清单.md": "10-tools/08-diagnosis-method.md",
    # 11-defects -> 11-defect-patterns
    "11-defects/01-车控信号语义.md": "11-defect-patterns/01-vehicle-signal-semantics.md",
    "11-defects/02-主线程与异步时序.md": "11-defect-patterns/02-main-thread-async.md",
    "11-defects/03-蓝牙机制.md": "11-defect-patterns/03-bluetooth-mechanisms.md",
    "11-defects/04-Kanzi双端状态同步.md": "11-defect-patterns/04-kanzi-state-sync.md",
    "11-defects/05-UI还原与主题适配.md": "11-defect-patterns/05-ui-theme-fidelity.md",
    "11-defects/06-状态缓存与启动时序.md": "11-defect-patterns/06-state-cache-startup.md",
    "11-defects/07-崩溃防护与偶现排查.md": "11-defect-patterns/07-crash-protection.md",
    # 13-audio
    "13-audio/01-AOSP音频子系统.md": "13-audio/01-aosp-audio.md",
    "13-audio/02-AAOS车机音频.md": "13-audio/02-aaos-audio.md",
    "13-audio/03-音频延迟与应用实践.md": "13-audio/03-audio-latency.md",
    "13-audio/04-蓝牙音频.md": "13-audio/04-bluetooth-audio.md",
    "13-audio/05-手机侧音频焦点与路由.md": "13-audio/05-phone-audio-focus.md",
    # 14-network
    "14-network/01-Android网络框架.md": "14-network/01-network-framework.md",
    "14-network/02-车机多APN与虚拟网卡.md": "14-network/02-multi-apn-veth.md",
    "14-network/03-Android-VPN.md": "14-network/03-vpn.md",
    "14-network/04-蜂窝数据与无线连接.md": "14-network/04-cellular-wireless.md",
    "14-network/05-车载网络架构与设计.md": "14-network/05-vehicle-network.md",
    "14-network/06-网络排查工具与实践.md": "14-network/06-network-diagnostics.md",
    "14-network/07-车机网络安全.md": "14-network/07-vehicle-security.md",
    "14-network/08-应用网络编程与系统约束.md": "14-network/08-app-network-constraints.md",
    "14-network/09-传输细节与协议设计.md": "14-network/09-transport-protocols.md",
    # 15-ui / 18-build-system / 13-audio english stems stay, but we express them here too
    "18-build-system/05-android-executables.md": "18-build-system/05-android-executables.md",
    "18-build-system/06-android-system-images.md": "18-build-system/06-android-system-images.md",
}

# Dissolved / mixed sources: old_path -> list of (old_q, new_path, note)
# new_path relative to knowledge-base/01-android/ unless it starts with "OUT:" (external destination).
DISSOLVE = {
    "01-architecture/07-Android分区.md": [(q, "04-storage/02-partitions.md", "") for q in range(1, 4)],
    "01-architecture/10-Kernel.md": [(q, "12-platform-native/01-kernel-gki.md", "") for q in range(1, 7)],
    "01-architecture/11-版本演进与图形栈预加载.md": (
        [(1, "01-architecture/01-system-architecture.md", "并入平台版本判定簇"),
         (2, "01-architecture/01-system-architecture.md", "并入平台版本判定簇"),
         (3, "12-platform-native/01-kernel-gki.md", ""),
         (4, "01-architecture/05-art-runtime.md", "编译策略演进并入 ART 册"),
         (5, "05-memory/01-memory-management.md", "16KB 兼容时间线并入内存册")]
        + [(q, "02-rendering/05-graphic-stack-preload.md", "") for q in range(6, 12)]
        + [(q, "01-architecture/02-system-boot.md", "Zygote/USAP 簇") for q in range(12, 15)]
    ),
    "01-architecture/12-类加载ART编译与JNI链接.md": (
        [(q, "01-architecture/05-art-runtime.md", "") for q in range(1, 10)]
        + [(q, "01-architecture/06-jni.md", "") for q in range(10, 13)]
        + [(q, "12-platform-native/04-bionic-linker.md", "") for q in range(13, 17)]
    ),
    "01-architecture/13-MessageQueue锁竞争与Binder深化.md": (
        [(q, "16-app-framework/02-handler-looper.md", "") for q in range(1, 7)]
        + [(7, "09-app-practice/03-stability-threads-ipc.md", "等待链诊断并入线程与 IPC")]
        + [(q, "01-architecture/03-binder.md", "") for q in (8, 9, 10, 11, 12, 13, 16, 18, 19)]
        + [(14, "05-memory/01-memory-management.md", "冻结进程 Binder 语义"), (15, "05-memory/01-memory-management.md", "冻结决策")]
        + [(17, "12-platform-native/02-binder-driver.md", "驱动层 buffer 分配归还")]
    ),
    "01-architecture/14-系统服务调度核心.md": (
        [(1, "01-architecture/08-system-server.md", "AMS/ATMS 分工"), (2, "05-memory/01-memory-management.md", "adj 档位"),
         (3, "05-memory/01-memory-management.md", "OomAdjuster→lmkd"), (4, "07-performance/03-anr.md", "ANR 计时器总览"),
         (5, "01-architecture/08-system-server.md", "AMS 双锁")]
        + [(q, "08-cpu-power/01-scheduler-power-framework.md", "") for q in (6, 7, 8)]
        + [(9, "05-memory/01-memory-management.md", "memory cgroup 语义")]
        + [(q, "06-platform-services/07-broadcast.md", "") for q in range(10, 14)]
        + [(q, "16-app-framework/03-content-provider.md", "") for q in range(14, 18)]
    ),
    "01-architecture/15-安装归档与资源配置.md": (
        [(q, "01-architecture/15-package-management.md", "") for q in range(1, 10)]
        + [(q, "15-ui/03-resources.md", "") for q in range(10, 14)]
    ),
    "01-architecture/16-显示与窗口链路.md": [
        (1, "02-rendering/01-render-pipeline-vsync.md", ""), (2, "02-rendering/02-gpu-composition-display.md", ""),
        (3, "15-ui/05-window-system.md", ""), (4, "15-ui/05-window-system.md", ""),
        (5, "15-ui/05-window-system.md", ""), (6, "03-input/01-input-system.md", ""),
    ],
    "01-architecture/17-Telephony与Connectivity.md": (
        [(q, "14-network/04-cellular-wireless.md", "") for q in range(1, 7)]
        + [(q, "14-network/01-network-framework.md", "") for q in range(7, 13)]
    ),
    "01-architecture/18-Notification-Biometric-Location.md": (
        [(q, "06-platform-services/01-notifications.md", "") for q in range(1, 7)]
        + [(q, "06-platform-services/02-biometrics.md", "") for q in range(7, 12)]
        + [(q, "06-platform-services/03-location.md", "") for q in range(12, 17)]
    ),
    "01-architecture/19-AVF可观测与AI手机技术栈.md": (
        [(q, "06-platform-services/04-avf-virtualization.md", "") for q in range(1, 5)]
        + [(q, "12-platform-native/06-logd.md", "") for q in (5, 6, 7)]
        + [(8, "09-app-practice/14-cpu-size-optimization.md", "R8 日志删除并入体积优化")]
        + [(q, "12-platform-native/07-bpf.md", "") for q in (9, 11, 12)]
        + [(q, "06-platform-services/05-ai-services.md", "") for q in range(13, 17)]
    ),
    "01-architecture/22-四大组件.md": [(q, "16-app-framework/01-four-components.md", "") for q in range(1, 7)],
    "01-architecture/23-Handler消息机制.md": [(q, "16-app-framework/02-handler-looper.md", "") for q in range(1, 6)],
    "06-system/01-AOSP性能优化.md": [
        (1, "07-performance/09-platform-optimization.md", ""), (2, "16-app-framework/02-handler-looper.md", "DeliQueue 迁移风险并入 Handler 册"),
        (3, "01-architecture/05-art-runtime.md", ""), (4, "10-tools/05-performance-methodology.md", ""),
        (5, "18-build-system/01-product-config.md", ""), (6, "18-build-system/02-soong-modules.md", ""),
        (7, "18-build-system/03-android-kernel-build.md", ""), (8, "12-platform-native/01-kernel-gki.md", ""),
        (9, "05-memory/01-memory-management.md", "MGLRU 因果链"), (10, "12-platform-native/01-kernel-gki.md", ""),
        (11, "05-memory/02-reclaim-compression.md", "MTE 成本指标"),
        (12, "18-build-system/02-soong-modules.md", ""), (13, "18-build-system/02-soong-modules.md", ""),
        (14, "18-build-system/03-android-kernel-build.md", ""),
        (15, "01-architecture/05-art-runtime.md", ""), (16, "01-architecture/05-art-runtime.md", ""),
        (17, "01-architecture/05-art-runtime.md", ""), (18, "01-architecture/05-art-runtime.md", ""),
        (19, "07-performance/09-platform-optimization.md", ""), (20, "10-tools/03-performance-tools.md", ""),
        (21, "07-performance/09-platform-optimization.md", ""), (22, "01-architecture/02-system-boot.md", "Zygote preload 取舍"),
        (23, "12-platform-native/08-rust-native.md", ""), (24, "12-platform-native/08-rust-native.md", ""),
        (25, "12-platform-native/08-rust-native.md", ""),
        (26, "07-performance/09-platform-optimization.md", ""), (27, "07-performance/09-platform-optimization.md", ""),
        (28, "07-performance/09-platform-optimization.md", ""), (29, "07-performance/09-platform-optimization.md", ""),
        (30, "07-performance/09-platform-optimization.md", ""), (31, "07-performance/09-platform-optimization.md", ""),
    ],
    "06-system/02-OEM与设备差异.md": [
        (1, "10-tools/05-performance-methodology.md", ""), (2, "05-memory/01-memory-management.md", "Freezer 实现与 SIGSTOP"),
        (3, "01-architecture/02-system-boot.md", "USAP 与厂商预启动"), (4, "09-app-practice/13-power-practice.md", "后台任务选型"),
        (5, "08-cpu-power/01-scheduler-power-framework.md", ""), (6, "08-cpu-power/01-scheduler-power-framework.md", ""),
        (7, "08-cpu-power/02-energy-efficiency.md", "GPU/NPU 指标解读"), (8, "12-platform-native/07-bpf.md", "sched_ext 运行确认"),
        (9, "08-cpu-power/01-scheduler-power-framework.md", "MUSCHED VIP"),
        (10, "07-performance/09-platform-optimization.md", "Game Mode"), (11, "07-performance/02-responsiveness.md", "触控跟手测量"),
        (12, "08-cpu-power/01-scheduler-power-framework.md", ""), (13, "08-cpu-power/01-scheduler-power-framework.md", ""),
        (14, "08-cpu-power/01-scheduler-power-framework.md", ""), (15, "08-cpu-power/01-scheduler-power-framework.md", ""),
        (16, "07-performance/09-platform-optimization.md", "MPC"), (17, "07-performance/09-platform-optimization.md", "MPC"),
        (18, "07-performance/09-platform-optimization.md", "MPC"),
        (19, "16-app-framework/06-private-space.md", ""), (20, "16-app-framework/06-private-space.md", ""),
        (21, "16-app-framework/06-private-space.md", ""),
        (22, "17-aaos/06-aaos-app-dev.md", ""), (23, "17-aaos/06-aaos-app-dev.md", ""),
        (24, "15-ui/06-aaos-ui.md", "地图 Surface"), (25, "17-aaos/06-aaos-app-dev.md", ""),
        (26, "17-aaos/01-car-services.md", "CarWatchdog"), (27, "17-aaos/04-car-power-users.md", "电源状态机"),
        (28, "17-aaos/03-vhal-integration.md", ""), (29, "17-aaos/03-vhal-integration.md", ""),
        (30, "17-aaos/03-vhal-integration.md", ""), (31, "17-aaos/01-car-services.md", "CarEvsService"),
        (32, "17-aaos/01-car-services.md", "ClusterHomeService"), (33, "17-aaos/03-vhal-integration.md", ""),
        (34, "17-aaos/02-car-launcher.md", "TaskView 修复案例"), (35, "15-ui/06-aaos-ui.md", "CarSystemUI"),
        (36, "15-ui/07-driving-safety.md", ""), (37, "15-ui/07-driving-safety.md", ""),
        (38, "15-ui/07-driving-safety.md", ""), (39, "17-aaos/04-car-power-users.md", "headless system user"),
        (40, "17-aaos/04-car-power-users.md", "OEM 服务多用户"),
    ],
    "06-system/03-CarService服务速览.md": [(q, "17-aaos/01-car-services.md", "") for q in range(1, 10)],
    "07-performance/07-渲染管线-基础与图形API.md": (
        [(1, "07-performance/01-smoothness.md", "trace 四坐标"), (2, "02-rendering/01-render-pipeline-vsync.md", "管线判定"),
         (3, "02-rendering/02-gpu-composition-display.md", ""), (4, "02-rendering/02-gpu-composition-display.md", ""),
         (5, "07-performance/01-smoothness.md", "dequeueBuffer 归因")]
        + [(q, "02-rendering/01-render-pipeline-vsync.md", "") for q in (6, 7, 8, 9)]
        + [(q, "15-ui/02-view.md", "View layer/SurfaceView/TextureView 用法") for q in range(10, 16)]
        + [(18, "15-ui/02-view.md", "GLSurfaceView 用法")]
        + [(q, "02-rendering/04-graphics-api.md", "") for q in (16, 17, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28)]
    ),
    "07-performance/08-渲染管线-跨框架与媒体.md": (
        [(q, "09-app-practice/09-rendering-media-hybrid.md", "") for q in range(1, 6)]
        + [(q, "15-ui/04-compose.md", "Compose 渲染机制") for q in range(6, 10)]
        + [(q, "09-app-practice/09-rendering-media-hybrid.md", "") for q in range(10, 14)]
        + [(q, "02-rendering/06-camera-pipeline.md", "") for q in range(14, 20)]
        + [(q, "02-rendering/07-media-playback.md", "") for q in range(20, 26)]
        + [(q, "07-performance/01-smoothness.md", "游戏性能归因") for q in range(26, 30)]
        + [(q, "02-rendering/08-android-xr.md", "") for q in range(30, 33)]
        + [(q, "02-rendering/04-graphics-api.md", "WebGPU") for q in range(33, 36)]
    ),
    "09-app-practice/18-应用开发机制与常用API.md": [
        (1, "16-app-framework/05-collections-annotations.md", "SparseArray"),
        (2, "15-ui/02-view.md", "View tag"),
        (3, "16-app-framework/04-parcel.md", "Parcelable/Serializable 选择"),
        (4, "09-app-practice/12-network-practice.md", "Retrofit 契约"), (5, "09-app-practice/12-network-practice.md", "Retrofit 参数"),
        (6, "06-platform-services/01-notifications.md", "通知渠道"),
        (7, "04-storage/01-storage-io.md", "SharedPreferences 读"), (8, "04-storage/01-storage-io.md", "SharedPreferences 写/监听"),
        (9, "16-app-framework/04-parcel.md", "Parcel 读写顺序"),
    ],
    "10-tools/08-学习方法与检查清单.md": [(9, "15-ui/08-ui-debugging.md", "Studio 预览缩放"), (10, "10-tools/03-performance-tools.md", "ADB 进程查询")],
    "11-defects/08-提交治理与防回归.md": [(q, "OUT:knowledge-base/04-exp/01-提交治理与防回归.md", "非技术项目治理迁出") for q in range(1, 21)],
    "12-platform-native/01-内核与原生层.md": (
        [(q, "12-platform-native/02-binder-driver.md", "") for q in (1, 2, 3, 10, 11, 13, 14, 15, 16)]
        + [(q, "12-platform-native/03-shared-memory.md", "") for q in (4, 5, 17, 6, 7, 8, 9, 18, 19, 20)]
        + [(12, "12-platform-native/01-kernel-gki.md", "GKI 边界")]
    ),
    "12-platform-native/03-aconfig特性开关.md": [
        (1, "18-build-system/07-aconfig.md", ""), (2, "18-build-system/07-aconfig.md", ""),
        (3, "06-platform-services/06-aconfig-runtime.md", "purpose/storage 后端"),
        (4, "18-build-system/07-aconfig.md", ""), (5, "18-build-system/07-aconfig.md", ""),
        (6, "18-build-system/07-aconfig.md", ""), (7, "06-platform-services/06-aconfig-runtime.md", "aconfigd 存储"),
        (8, "06-platform-services/06-aconfig-runtime.md", "整数标志"), (9, "06-platform-services/06-aconfig-runtime.md", "aflags 工具"),
        (10, "18-build-system/07-aconfig.md", "release config"), (11, "18-build-system/07-aconfig.md", "namespace 约束"),
        (12, "18-build-system/07-aconfig.md", "版本核对"), (13, "18-build-system/07-aconfig.md", "端到端链路"),
        (14, "06-platform-services/06-aconfig-runtime.md", "自建镜像维护"),
    ],
    "13-audio/11-playback-hal.md": [(2, "13-audio/03-audio-latency.md", "MERGE:控制面/数据面与 03-Q2 同题，并入该题答案")],
    "14-network/10-网络分层原理与地基机制.md": [(q, "OUT:knowledge-base/网络/01-network-fundamentals.md", "通用协议地基教学迁出") for q in range(1, 15)],
    "16-project-architecture/01-应用进程启动与全局服务生命周期.md": [(1, "OUT:knowledge-base/career/work-project-analysis/01-launcher-vehicle-service.md", "项目个案迁出")],
    "17-car-app/01-Launcher.md": [
        (1, "17-aaos/02-car-launcher.md", ""), (2, "REMOVE-DUP:01-architecture/02-system-boot.md", "空答案：与启动册 HOME 解析题（原 Q43）重复，删除并指向该题"),
        (3, "17-aaos/02-car-launcher.md", ""), (4, "17-aaos/02-car-launcher.md", ""),
        (5, "15-ui/01-activity.md", "NEW_TASK 语义"), (6, "15-ui/01-activity.md", "进程存活仍建 Activity"),
    ],
    "18-build-system/04-linux-kernel-drivers.md": (
        [(q, "12-platform-native/05-driver-runtime.md", "") for q in list(range(1, 10)) + [13, 14]]
        + [(q, "18-build-system/04-kernel-modules.md", "") for q in (10, 11, 12, 15)]
    ),
}

# Destination order: new_path -> explicit list of (src, old_q). Sources use old rel path.
# Destinations not listed here get: pure-rename source order, or dissolution-source order.
ORDER = {
    "01-architecture/01-system-architecture.md": (
        [("01-architecture/01-Android系统架构.md", q) for q in (1, 2, 4, 5)]
        + [("01-architecture/03-Binder.md", 8)]
        + [("01-architecture/01-Android系统架构.md", q) for q in (6,)]
        + [("01-architecture/11-版本演进与图形栈预加载.md", q) for q in (1, 2)]
        + [("01-architecture/01-Android系统架构.md", q) for q in range(7, 17)]
    ),
    "01-architecture/02-system-boot.md": (
        [("01-architecture/02-Android系统启动流程.md", q) for q in range(1, 21)]
        + [("06-system/01-AOSP性能优化.md", 22)]
        + [("01-architecture/02-Android系统启动流程.md", q) for q in (21, 22)]
        + [("06-system/02-OEM与设备差异.md", 3)]
        + [("01-architecture/02-Android系统启动流程.md", 23)]
        + [("01-architecture/11-版本演进与图形栈预加载.md", q) for q in (12, 13, 14)]
        + [("01-architecture/02-Android系统启动流程.md", q) for q in range(27, 36)]
        + [("01-architecture/02-Android系统启动流程.md", q) for q in range(39, 45)]
    ),
    "01-architecture/03-binder.md": (
        [("01-architecture/03-Binder.md", 1), ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 10),
         ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 16), ("01-architecture/03-Binder.md", 2),
         ("01-architecture/03-Binder.md", 7), ("01-architecture/03-Binder.md", 10),
         ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 8), ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 9),
         ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 13), ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 12),
         ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 11), ("01-architecture/03-Binder.md", 9),
         ("01-architecture/03-Binder.md", 3), ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 18),
         ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 19), ("01-architecture/03-Binder.md", 4),
         ("01-architecture/03-Binder.md", 5), ("01-architecture/03-Binder.md", 6)]
    ),
    "01-architecture/05-art-runtime.md": (
        [("01-architecture/05-Art.md", q) for q in range(1, 6)]
        + [("01-architecture/12-类加载ART编译与JNI链接.md", q) for q in range(1, 10)]
        + [("01-architecture/11-版本演进与图形栈预加载.md", 4)]
        + [("06-system/01-AOSP性能优化.md", q) for q in (3, 15, 16, 17, 18)]
    ),
    "01-architecture/06-jni.md": (
        [("01-architecture/06-JNI.md", q) for q in range(1, 4)]
        + [("01-architecture/12-类加载ART编译与JNI链接.md", q) for q in range(10, 13)]
    ),
    "01-architecture/08-system-server.md": (
        [("01-architecture/08-SystemServer.md", 1), ("01-architecture/02-Android系统启动流程.md", 24),
         ("01-architecture/02-Android系统启动流程.md", 25), ("01-architecture/14-系统服务调度核心.md", 1),
         ("01-architecture/02-Android系统启动流程.md", 26), ("01-architecture/02-Android系统启动流程.md", 37),
         ("01-architecture/02-Android系统启动流程.md", 38), ("01-architecture/02-Android系统启动流程.md", 36),
         ("01-architecture/14-系统服务调度核心.md", 5), ("01-architecture/08-SystemServer.md", 2)]
    ),
    "01-architecture/15-package-management.md": [("01-architecture/15-安装归档与资源配置.md", q) for q in range(1, 10)],
    "02-rendering/01-render-pipeline-vsync.md": (
        [("02-rendering/01-渲染管线与VSync调度.md", 1), ("01-architecture/16-显示与窗口链路.md", 1)]
        + [("02-rendering/01-渲染管线与VSync调度.md", q) for q in range(2, 31)]
        + [("07-performance/07-渲染管线-基础与图形API.md", q) for q in (2, 6, 7, 8, 9)]
    ),
    "02-rendering/02-gpu-composition-display.md": (
        [("02-rendering/02-GPU合成与显示管线.md", q) for q in range(1, 31)]
        + [("01-architecture/16-显示与窗口链路.md", 2),
           ("07-performance/07-渲染管线-基础与图形API.md", 3), ("07-performance/07-渲染管线-基础与图形API.md", 4)]
    ),
    "02-rendering/04-graphics-api.md": [("07-performance/07-渲染管线-基础与图形API.md", q) for q in (16, 17, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28)] + [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in (33, 34, 35)],
    "02-rendering/05-graphic-stack-preload.md": [("01-architecture/11-版本演进与图形栈预加载.md", q) for q in range(6, 12)],
    "02-rendering/06-camera-pipeline.md": [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in range(14, 20)],
    "02-rendering/07-media-playback.md": [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in range(20, 26)],
    "02-rendering/08-android-xr.md": [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in range(30, 33)],
    "03-input/01-input-system.md": [("03-input/01-输入系统.md", q) for q in range(1, 27)] + [("01-architecture/16-显示与窗口链路.md", 6)],
    "04-storage/01-storage-io.md": [("04-storage/01-存储与IO.md", q) for q in range(1, 23) if q != 3] + [("09-app-practice/18-应用开发机制与常用API.md", 7), ("09-app-practice/18-应用开发机制与常用API.md", 8)],
    "04-storage/02-partitions.md": [("01-architecture/07-Android分区.md", 1), ("01-architecture/07-Android分区.md", 2), ("01-architecture/07-Android分区.md", 3), ("04-storage/01-存储与IO.md", 3)],
    "05-memory/01-memory-management.md": (
        [("05-memory/01-内存管理与压力治理.md", q) for q in range(1, 15)]
        + [("06-system/01-AOSP性能优化.md", 9)]
        + [("05-memory/01-内存管理与压力治理.md", q) for q in range(15, 20)]
        + [("01-architecture/14-系统服务调度核心.md", 2), ("01-architecture/14-系统服务调度核心.md", 3),
           ("01-architecture/14-系统服务调度核心.md", 9)]
        + [("06-system/02-OEM与设备差异.md", 2)]
        + [("05-memory/01-内存管理与压力治理.md", 20), ("05-memory/01-内存管理与压力治理.md", 21)]
        + [("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 14), ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 15)]
        + [("01-architecture/11-版本演进与图形栈预加载.md", 5)]
        + [("05-memory/01-内存管理与压力治理.md", q) for q in (22, 23, 24)]
    ),
    "05-memory/02-reclaim-compression.md": [("05-memory/02-回收压缩与专项内存.md", q) for q in range(1, 26)] + [("06-system/01-AOSP性能优化.md", 11)],
    "06-platform-services/01-notifications.md": [("01-architecture/18-Notification-Biometric-Location.md", q) for q in range(1, 7)] + [("09-app-practice/18-应用开发机制与常用API.md", 6)],
    "06-platform-services/02-biometrics.md": [("01-architecture/18-Notification-Biometric-Location.md", q) for q in range(7, 12)],
    "06-platform-services/03-location.md": [("01-architecture/18-Notification-Biometric-Location.md", q) for q in range(12, 17)],
    "06-platform-services/04-avf-virtualization.md": [("01-architecture/19-AVF可观测与AI手机技术栈.md", q) for q in range(1, 5)],
    "06-platform-services/05-ai-services.md": [("01-architecture/19-AVF可观测与AI手机技术栈.md", q) for q in range(13, 17)],
    "06-platform-services/06-aconfig-runtime.md": [("12-platform-native/03-aconfig特性开关.md", q) for q in (3, 7, 8, 9, 14)],
    "06-platform-services/07-broadcast.md": [("01-architecture/14-系统服务调度核心.md", q) for q in range(10, 14)],
    "07-performance/01-smoothness.md": (
        [("07-performance/01-流畅性.md", q) for q in range(1, 30)]
        + [("07-performance/07-渲染管线-基础与图形API.md", 1), ("07-performance/07-渲染管线-基础与图形API.md", 5)]
        + [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in range(26, 30)]
    ),
    "07-performance/02-responsiveness.md": [("07-performance/02-响应速度.md", q) for q in range(1, 31)] + [("06-system/02-OEM与设备差异.md", 11)],
    "07-performance/03-anr.md": [("07-performance/03-ANR.md", q) for q in range(1, 29)] + [("01-architecture/14-系统服务调度核心.md", 4)],
    "07-performance/09-platform-optimization.md": (
        [("06-system/01-AOSP性能优化.md", q) for q in (1, 19, 21, 26, 27, 28, 29, 30, 31)]
        + [("06-system/02-OEM与设备差异.md", q) for q in (10, 16, 17, 18)]
    ),
    "08-cpu-power/01-scheduler-power-framework.md": (
        [("08-cpu-power/01-调度与功耗框架.md", q) for q in range(1, 26)]
        + [("01-architecture/14-系统服务调度核心.md", q) for q in (6, 7, 8)]
        + [("06-system/02-OEM与设备差异.md", q) for q in (5, 6, 9, 12, 13, 14, 15)]
    ),
    "08-cpu-power/02-energy-efficiency.md": [("08-cpu-power/02-能效专项.md", q) for q in range(1, 18)] + [("06-system/02-OEM与设备差异.md", 7)],
    "09-app-practice/03-stability-threads-ipc.md": [("09-app-practice/03-稳定性治理-线程与IPC.md", q) for q in range(1, 21)] + [("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 7)],
    "09-app-practice/09-rendering-media-hybrid.md": (
        [("09-app-practice/09-渲染实战-媒体与混合栈.md", q) for q in range(1, 25)]
        + [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in (1, 2, 3, 4, 5, 10, 11, 12, 13)]
    ),
    "09-app-practice/12-network-practice.md": [("09-app-practice/12-网络与连接实践.md", q) for q in range(1, 29)] + [("09-app-practice/18-应用开发机制与常用API.md", 4), ("09-app-practice/18-应用开发机制与常用API.md", 5)],
    "09-app-practice/13-power-practice.md": [("09-app-practice/13-功耗优化实践.md", q) for q in range(1, 28)] + [("06-system/02-OEM与设备差异.md", 4)],
    "09-app-practice/14-cpu-size-optimization.md": [("09-app-practice/14-CPU与体积优化.md", q) for q in range(1, 28)] + [("01-architecture/19-AVF可观测与AI手机技术栈.md", 8)],
    "10-tools/03-performance-tools.md": [("10-tools/03-性能分析工具.md", q) for q in range(1, 37)] + [("10-tools/08-学习方法与检查清单.md", 10), ("06-system/01-AOSP性能优化.md", 20)],
    "10-tools/05-performance-methodology.md": [("10-tools/05-性能方法论.md", q) for q in range(1, 34)] + [("06-system/01-AOSP性能优化.md", 4), ("06-system/02-OEM与设备差异.md", 1)],
    "10-tools/08-diagnosis-method.md": [("10-tools/08-学习方法与检查清单.md", q) for q in range(1, 9)],
    "12-platform-native/01-kernel-gki.md": (
        [("01-architecture/10-Kernel.md", 1), ("01-architecture/11-版本演进与图形栈预加载.md", 3),
         ("01-architecture/19-AVF可观测与AI手机技术栈.md", 10)]
        + [("01-architecture/10-Kernel.md", q) for q in range(2, 7)]
        + [("12-platform-native/01-内核与原生层.md", 12), ("06-system/01-AOSP性能优化.md", 8), ("06-system/01-AOSP性能优化.md", 10)]
    ),
    "12-platform-native/02-binder-driver.md": (
        [("12-platform-native/01-内核与原生层.md", 1), ("01-architecture/13-MessageQueue锁竞争与Binder深化.md", 17)]
        + [("12-platform-native/01-内核与原生层.md", q) for q in (10, 2, 3, 11, 13, 14, 15, 16)]
    ),
    "12-platform-native/03-shared-memory.md": [("12-platform-native/01-内核与原生层.md", q) for q in (4, 5, 17, 18, 6, 7, 8, 9, 19, 20)],
    "12-platform-native/04-bionic-linker.md": [("12-platform-native/02-Bionic链接器与命名空间.md", q) for q in range(1, 18)] + [("01-architecture/12-类加载ART编译与JNI链接.md", q) for q in range(13, 17)],
    "12-platform-native/05-driver-runtime.md": [("18-build-system/04-linux-kernel-drivers.md", q) for q in list(range(1, 10)) + [13, 14]],
    "12-platform-native/06-logd.md": [("01-architecture/19-AVF可观测与AI手机技术栈.md", q) for q in (5, 6, 7)],
    "12-platform-native/07-bpf.md": [("01-architecture/19-AVF可观测与AI手机技术栈.md", q) for q in (9, 11, 12)] + [("06-system/02-OEM与设备差异.md", 8)],
    "12-platform-native/08-rust-native.md": [("06-system/01-AOSP性能优化.md", q) for q in (23, 24, 25)],
    "14-network/01-network-framework.md": [("14-network/01-Android网络框架.md", q) for q in range(1, 17)] + [("01-architecture/17-Telephony与Connectivity.md", q) for q in range(7, 13)],
    "14-network/04-cellular-wireless.md": [("14-network/04-蜂窝数据与无线连接.md", q) for q in range(1, 18)] + [("01-architecture/17-Telephony与Connectivity.md", q) for q in range(1, 7)],
    "15-ui/01-activity.md": [("15-ui/01-activity.md", q) for q in range(1, 20)] + [("17-car-app/01-Launcher.md", 5), ("17-car-app/01-Launcher.md", 6)],
    "15-ui/02-view.md": [("15-ui/02-view.md", q) for q in range(1, 18)] + [("09-app-practice/18-应用开发机制与常用API.md", 2)] + [("07-performance/07-渲染管线-基础与图形API.md", q) for q in (10, 11, 12, 13, 14, 15, 18)],
    "15-ui/03-resources.md": [("15-ui/03-resources.md", q) for q in range(1, 17)] + [("01-architecture/15-安装归档与资源配置.md", q) for q in range(10, 14)],
    "15-ui/04-compose.md": [("15-ui/04-compose.md", q) for q in range(1, 16)] + [("07-performance/08-渲染管线-跨框架与媒体.md", q) for q in range(6, 10)],
    "15-ui/05-window-system.md": [("15-ui/05-window-system.md", q) for q in range(1, 18)] + [("01-architecture/16-显示与窗口链路.md", q) for q in (3, 4, 5)],
    "15-ui/06-aaos-ui.md": [("15-ui/06-aaos-ui.md", q) for q in range(1, 16)] + [("06-system/02-OEM与设备差异.md", 24), ("06-system/02-OEM与设备差异.md", 35)],
    "15-ui/07-driving-safety.md": [("15-ui/07-driving-safety.md", q) for q in range(1, 16)] + [("06-system/02-OEM与设备差异.md", q) for q in (36, 37, 38)],
    "15-ui/08-ui-debugging.md": [("15-ui/08-ui-debugging.md", q) for q in range(1, 16)] + [("10-tools/08-学习方法与检查清单.md", 9)],
    "16-app-framework/01-four-components.md": [("01-architecture/22-四大组件.md", q) for q in range(1, 7)],
    "16-app-framework/02-handler-looper.md": (
        [("01-architecture/23-Handler消息机制.md", q) for q in range(1, 6)]
        + [("01-architecture/13-MessageQueue锁竞争与Binder深化.md", q) for q in range(1, 7)]
        + [("06-system/01-AOSP性能优化.md", 2)]
    ),
    "16-app-framework/03-content-provider.md": [("01-architecture/14-系统服务调度核心.md", q) for q in range(14, 18)],
    "16-app-framework/04-parcel.md": [("09-app-practice/18-应用开发机制与常用API.md", 3), ("09-app-practice/18-应用开发机制与常用API.md", 9)],
    "16-app-framework/05-collections-annotations.md": [("09-app-practice/18-应用开发机制与常用API.md", 1)],
    "16-app-framework/06-private-space.md": [("06-system/02-OEM与设备差异.md", q) for q in (19, 20, 21)],
    "17-aaos/01-car-services.md": (
        [("06-system/03-CarService服务速览.md", q) for q in range(1, 10)]
        + [("06-system/02-OEM与设备差异.md", q) for q in (26, 31, 32)]
    ),
    "17-aaos/02-car-launcher.md": [("17-car-app/01-Launcher.md", 1), ("17-car-app/01-Launcher.md", 3), ("17-car-app/01-Launcher.md", 4), ("06-system/02-OEM与设备差异.md", 34)],
    "17-aaos/03-vhal-integration.md": [("06-system/02-OEM与设备差异.md", q) for q in (28, 29, 30, 33)],
    "17-aaos/04-car-power-users.md": [("06-system/02-OEM与设备差异.md", q) for q in (27, 39, 40)],
    "17-aaos/05-vehicle-links.md": [("01-architecture/01-Android系统架构.md", 3)],
    "17-aaos/06-aaos-app-dev.md": [("06-system/02-OEM与设备差异.md", q) for q in (22, 23, 25)],
    "18-build-system/01-product-config.md": None,  # filled dynamically below (own + 06/01 Q5 appended)
    "18-build-system/02-soong-modules.md": None,
    "18-build-system/03-android-kernel-build.md": None,
    "18-build-system/04-kernel-modules.md": [("18-build-system/04-linux-kernel-drivers.md", q) for q in (10, 11, 12, 15)],
    "18-build-system/07-aconfig.md": [("12-platform-native/03-aconfig特性开关.md", q) for q in (1, 2, 4, 5, 6, 10, 11, 12, 13)],
    "13-audio/03-audio-latency.md": [("13-audio/03-音频延迟与应用实践.md", q) for q in range(1, 12)],  # 11-Q2 merged into Q2's answer
    "13-audio/11-playback-hal.md": [("13-audio/11-playback-hal.md", q) for q in (1, 3, 4, 5)],
    "OUT:knowledge-base/04-exp/01-提交治理与防回归.md": [("11-defects/08-提交治理与防回归.md", q) for q in range(1, 21)],
    "OUT:knowledge-base/网络/01-network-fundamentals.md": [("14-network/10-网络分层原理与地基机制.md", q) for q in range(1, 15)],
    "OUT:knowledge-base/career/work-project-analysis/01-launcher-vehicle-service.md": [("16-project-architecture/01-应用进程启动与全局服务生命周期.md", 1)],
}

# answer merge: (dest, dest_old_src_q) -> list of (src, old_q) whose text appends into that answer
MERGE_INTO = {
    ("13-audio/03-音频延迟与应用实践.md", 2): [("13-audio/11-playback-hal.md", 2)],
}
