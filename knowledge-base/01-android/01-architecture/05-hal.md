# HAL

> 学习资料（文章模式沉淀）。主线：HAL 的运行形态、system/vendor 兼容边界、设备排查入口与服务崩溃处理。车机 VHAL 的接口与实现细节另见 AAOS 对应主题册。本文件描述通用 Android 机制，具体行为以目标设备的平台版本、VINTF 声明和产品实现为准。

**Q1: Android HAL 有哪些运行形态？Treble 如何约束 system 与 vendor 的兼容关系？**

HAL 的调用边界可能是独立 Binder 服务，也可能是进程内共享库。接口是否稳定、服务是否独立和使用哪个传输机制是不同维度。常见形态如下：

1. **Stable AIDL HAL**：跨 system/vendor 边界时使用稳定 AIDL 契约，经普通 Binder 服务交互。符合 VINTF 稳定性要求的 HAL 服务会在 VINTF manifest 中声明。
2. **Binderized HIDL HAL**：存量 HIDL HAL 作为服务注册到 hwservicemanager，通过 hwbinder 通信。
3. **Passthrough HIDL HAL**：实现作为共享库加载到客户端进程，不建立独立 HAL 服务进程。它主要用于兼容旧实现，故障可能直接发生在加载它的进程内。

Project Treble 从 Android 8.0 起把框架与 vendor 的接口边界正式化。设备侧 VINTF manifest 声明提供的接口，框架兼容矩阵声明所需接口，组合结果用于评估对应 system/vendor 镜像是否兼容。稳定接口降低独立更新的耦合，但不保证任意版本、任意厂商镜像组合都能正常启动或工作。新接口优先使用 Stable AIDL，存量 HIDL 仍可能存在。

排查时先确认 HAL 是独立服务还是 passthrough 库。独立服务沿服务事务检查服务线程、锁、系统调用和同步栅栏。Passthrough 库要在客户端进程中检查 native 调用栈与库内部等待。

**Q2: 如何确认设备上的 HIDL 或 AIDL HAL 是否运行并注册？**

分别检查对应服务管理器的运行时注册状态与设备的 VINTF 声明。manifest 表明构建期预期提供什么，不能单独证明服务已经启动或注册成功。运行时查询也可能受权限与服务可见性限制。

1. **HIDL**：使用 `adb shell lshal` 查看服务和 passthrough 实现。需要核对具体接口时，按完整名称筛选，例如 `android.hardware.foo@1.0::IFoo/default`。
2. **AIDL HAL**：使用 `adb shell service list` 查看普通 Binder 服务名。在可用的 userdebug/eng 或工程构建上，也可通过 `adb shell dumpsys <服务名>` 请求该服务的 dump。是否能从 shell 列出、是否实现可用 dump，取决于设备权限和服务实现。
3. **对照声明**：检查设备合并后的 VINTF 信息及相关 system、vendor 或 odm manifest 片段，确认 HAL 名称、版本和实例名与预期一致。若声明在但运行时没有服务，继续查 init 启动日志、服务进程状态和注册失败原因。

`lshal` 面向 HIDL，不是通用的 AIDL 查询工具。运行时已注册与 VINTF 已声明回答的是两个不同问题，应交叉验证。

**Q3: HAL 服务反复崩溃时如何定位？客户端怎样感知？如何取得 HAL 的调试信息？**

先确认崩溃进程、服务传输类型和重启策略，再结合 tombstone、服务日志和客户端事务判断故障链。若服务由 init 管理，init 会按对应 `.rc` 服务配置处理退出与重启。只有配置了 `critical` 等策略时，才可能触发额外的设备级动作，不能把某个固定重启次数规则套用到所有服务。

1. **确认是否重启**：崩溃前后查询 HIDL 的 `lshal` 或 AIDL 的 Binder 服务列表，并检查 init 与 HAL 进程日志。服务重新注册只说明有实例恢复，不代表客户端状态或设备硬件状态已经恢复。
2. **定位崩溃点**：从 bugreport、logcat 和可访问的 `/data/tombstones/` 获取崩溃时栈与进程信息。对比崩溃前后的 Binder/HIDL 事务、线程状态、锁和驱动返回值，区分 HAL 自身错误、内核/固件故障与上游输入异常。
3. **检查客户端恢复**：AIDL 客户端可为远端 Binder 注册死亡通知，HIDL 客户端可注册对应的 HIDL death recipient。发现服务对象失效后，应清理旧代理并按接口契约重新取得服务。死亡通知与服务重新注册之间存在时序。重试、请求是否可安全重放以及状态重建都由客户端策略决定。
4. **请求服务调试输出**：调试入口取决于 HAL 接口类型。

    1. AIDL HAL：通过 `dumpsys <服务名>` 调用 dump 接口，具体参数由服务实现定义。
    2. HIDL HAL：通过 `lshal debug <接口完整名>` 调用 debug 接口。

设备权限不足或服务未实现有用输出时，这些命令不会提供完整内部状态。

例如参考 VHAL 文档用 `adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --help` 查询 AIDL 调试命令，并用 `adb shell lshal debug android.hardware.automotive.vehicle@2.0::IVehicle/default` 调试 HIDL VHAL。应以目标设备实际注册的接口版本和访问权限为准。

资料来源：AOSP《AIDL for HALs》《VINTF objects》《Debug VHAL》及 `lshal` 命令实现说明。
