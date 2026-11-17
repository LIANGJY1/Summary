# HAL

> 学习资料（文章模式沉淀）。主线：HAL 的运行形态、system/vendor 兼容边界、服务启动与注册链路、稳定 AIDL 接口版本冻结、设备排查入口与服务崩溃处理。车机 VHAL 的接口与实现细节另见 AAOS 对应主题册。本文件描述通用 Android 机制，具体行为以目标设备的平台版本、VINTF 声明和产品实现为准。

**Q1: [learning] Android HAL 有哪些运行形态？Treble 如何约束 system 与 vendor 的兼容关系？**

HAL 的调用边界可能是独立 Binder 服务，也可能是进程内共享库。接口是否稳定、服务是否独立和使用哪个传输机制是不同维度。常见形态如下：

1. **Stable AIDL HAL**：跨 system/vendor 边界时使用稳定 AIDL 契约，经普通 Binder 服务交互。符合 VINTF 稳定性要求的 HAL 服务会在 VINTF manifest 中声明。
2. **Binderized HIDL HAL**：存量 HIDL HAL 作为服务注册到 hwservicemanager，通过 hwbinder 通信。
3. **Passthrough HIDL HAL**：实现作为共享库加载到客户端进程，不建立独立 HAL 服务进程。它主要用于兼容旧实现，故障可能直接发生在加载它的进程内。

Project Treble 从 Android 8.0 起把框架与 vendor 的接口边界正式化。设备侧 VINTF manifest 声明提供的接口，框架兼容矩阵声明所需接口，组合结果用于评估对应 system/vendor 镜像是否兼容。稳定接口降低独立更新的耦合，但不保证任意版本、任意厂商镜像组合都能正常启动或工作。新接口优先使用 Stable AIDL，存量 HIDL 仍可能存在。

排查时先确认 HAL 是独立服务还是 passthrough 库。独立服务沿服务事务检查服务线程、锁、系统调用和同步栅栏。Passthrough 库要在客户端进程中检查 native 调用栈与库内部等待。

**Q2: [learning] 如何确认设备上的 HIDL 或 AIDL HAL 是否运行并注册？**

分别检查对应服务管理器的运行时注册状态与设备的 VINTF 声明。manifest 表明构建期预期提供什么，不能单独证明服务已经启动或注册成功。运行时查询也可能受权限与服务可见性限制。

1. **HIDL**：使用 `adb shell lshal` 查看服务和 passthrough 实现。需要核对具体接口时，按完整名称筛选，例如 `android.hardware.foo@1.0::IFoo/default`。
2. **AIDL HAL**：使用 `adb shell service list` 查看普通 Binder 服务名。在可用的 userdebug/eng 或工程构建上，也可通过 `adb shell dumpsys <服务名>` 请求该服务的 dump。是否能从 shell 列出、是否实现可用 dump，取决于设备权限和服务实现。
3. **对照声明**：检查设备合并后的 VINTF 信息及相关 system、vendor 或 odm manifest 片段，确认 HAL 名称、版本和实例名与预期一致。若声明在但运行时没有服务，继续查 init 启动日志、服务进程状态和注册失败原因。

`lshal` 面向 HIDL，不是通用的 AIDL 查询工具。运行时已注册与 VINTF 已声明回答的是两个不同问题，应交叉验证。

**Q3: [learning] HAL 服务反复崩溃时如何定位？客户端怎样感知？如何取得 HAL 的调试信息？**

先确认崩溃进程、服务传输类型和重启策略，再结合 tombstone、服务日志和客户端事务判断故障链。若服务由 init 管理，init 会按对应 `.rc` 服务配置处理退出与重启。只有配置了 `critical` 等策略时，才可能触发额外的设备级动作，不能把某个固定重启次数规则套用到所有服务。

1. **确认是否重启**：崩溃前后查询 HIDL 的 `lshal` 或 AIDL 的 Binder 服务列表，并检查 init 与 HAL 进程日志。服务重新注册只说明有实例恢复，不代表客户端状态或设备硬件状态已经恢复。
2. **定位崩溃点**：从 bugreport、logcat 和可访问的 `/data/tombstones/` 获取崩溃时栈与进程信息。对比崩溃前后的 Binder/HIDL 事务、线程状态、锁和驱动返回值，区分 HAL 自身错误、内核/固件故障与上游输入异常。
3. **检查客户端恢复**：AIDL 客户端可为远端 Binder 注册死亡通知，HIDL 客户端可注册对应的 HIDL death recipient。发现服务对象失效后，应清理旧代理并按接口契约重新取得服务。死亡通知与服务重新注册之间存在时序。重试、请求是否可安全重放以及状态重建都由客户端策略决定。
4. **请求服务调试输出**：调试入口取决于 HAL 接口类型。

    1. AIDL HAL：通过 `dumpsys <服务名>` 调用 dump 接口，具体参数由服务实现定义。
    2. HIDL HAL：通过 `lshal debug <接口完整名>` 调用 debug 接口。

设备权限不足或服务未实现有用输出时，这些命令不会提供完整内部状态。

例如参考 VHAL 文档用 `adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --help` 查询 AIDL 调试命令，并用 `adb shell lshal debug android.hardware.automotive.vehicle@2.0::IVehicle/default` 调试 HIDL VHAL。应以目标设备实际注册的接口版本和访问权限为准。

**Q4: [learning] AIDL 与 HIDL 的 HAL 服务进程是如何启动并注册的？**

HAL 服务进程由 init 按 `.rc` 配置拉起，进程内再用各自的服务管理器注册实例。AIDL HAL 用普通 Binder 注册到 servicemanager，HIDL binderized 注册到 hwservicemanager（hwbinder 域），HIDL passthrough 不建立独立服务进程，客户端按接口名加载实现库。确认服务形态与排查启动失败都依赖这条链路。

1. **AIDL HAL：**init 从接口模块的 `.rc` 启动进程。AAOS 13 默认 AIDL VHAL 的 `vhal-default-service.rc` 声明 `service vendor.vehicle-hal-default /vendor/bin/hw/android.hardware.automotive.vehicle@V1-default-service`，属 `class early_hal`、`user vehicle_network`。进程内 `VehicleService.cpp` 构造 HAL 实现后调用 `AServiceManager_addService` 注册实例名 `android.hardware.automotive.vehicle.IVehicle/default`，再启动 Binder 线程池并等待请求。注册失败会导致进程退出（如 keymint 实现用 CHECK 断言注册状态），由 init 按服务策略处理重启。
2. **HIDL binderized：**实现进程调用 `registerAsService` 注册到 hwservicemanager，客户端经 hwbinder 取得代理。hwbinder 与框架使用的 binder 是不同的设备节点，配合 SELinux 域实现隔离。
3. **HIDL passthrough：**没有独立服务进程。客户端侧的 PassthroughServiceManager 把接口全名解析为实现库名与入口符号，例如 android.hardware.foo@1.0::IFoo 对应 android.hardware.foo@1.0-impl.so 与 HIDL_FETCH_IFoo，按 ODM、VENDOR、VNDK-SP、SYSTEM 目录顺序查找加载，vendor 分区的库经 sphal 机制加载。因此实现库的崩溃直接发生在加载它的客户端进程内。

排查衔接：独立服务进程看 init 启动日志与 `service list`、`lshal` 的注册结果。passthrough 库问题查加载方进程的 native 栈。

依据：AAOS 13 的 hardware/interfaces/automotive/vehicle/aidl/impl/vhal/src/VehicleService.cpp、impl/vhal/vhal-default-service.rc，system/core/trusty/keymaster/keymint/service.cpp 及其 `.rc`，frameworks/native/cmds/lshal/ListCommand.cpp。passthrough 的库名解析与目录顺序按 HIDL ServiceManagement（libhidl）实现整理，本地树未含该源码。

**Q5: [learning] 稳定 AIDL HAL 接口如何冻结版本？发布后的接口还能改吗？**

稳定 AIDL 接口靠冻结快照管理版本兼容：接口首次发布时把 `.aidl` 签名快照存入 `aidl_api/接口包名/版本号/`，每个对外版本各留一份只读快照，开发中的内容放在 `current/`。构建工具对比当前 `.aidl` 与已冻结快照，不兼容改动会构建失败。已冻结版本不可修改，接口演进只能在新版本号下追加兼容性变更，VINTF manifest 按版本号声明设备提供的接口。

1. **快照内容与位置**：接口仓内 `aidl_api/android.hardware.automotive.vehicle/` 下有 `1/` 与 `current/` 两份目录。`1/` 是已发布版本的全部 parcelable 与接口签名快照，`current/` 是开发中的版本，下次发布时冻结为新版本号。
2. **兼容性规则**：冻结后的版本不能修改字段类型、删除字段或改变方法签名。向后兼容的扩展（如给 parcelable 追加带默认值的新字段）进入新版本。`.aidl` 中为字段写默认值（如 `VehiclePropConfig.access = VehiclePropertyAccess.NONE`）正是为了让读取旧数据的进程能按默认值跳过新增字段。
3. **声明与匹配**：VINTF manifest 用 `<hal format="aidl">` 声明接口名、版本与实例，例如 VHAL 声明 `<version>1</version>` 加 `<fqname>IVehicle/default</fqname>`。框架兼容矩阵按版本与设备声明做匹配，评估对应 system/vendor 镜像组合是否兼容。

依据：AAOS 13 的 hardware/interfaces/automotive/vehicle/aidl/aidl_api 目录结构、impl/vhal/vhal-default-service.xml、VehiclePropConfig.aidl 的默认值写法。

资料来源：AOSP《AIDL for HALs》《VINTF objects》《Debug VHAL》及 `lshal` 命令实现说明。服务注册与接口冻结按 AAOS 13 本地源码核对。
