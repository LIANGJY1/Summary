# VHAL 集成与契约

> 学习资料（文章模式沉淀）。边界：本文回答 CarPropertyService 对属性访问的校验、VHAL 死亡后的服务恢复、属性订阅和事件契约，以及 HIDL 到 AIDL 迁移时的接口差异。每题只讨论对应契约，Q 序列供 Atlas 同源直读。

**Q1: [learning] CarPropertyService 为什么会对属性读写抛出 not writable at areaId 或 SecurityException？属性访问要通过哪些检查？**

一次属性访问必须同时满足属性配置允许该读写、目标区域有效，以及调用方具有框架权限。异常文字通常能定位失败层。应用查询属性列表时还可能因为权限不足而看不到 vendor 属性。

1. **VHAL 配置校验**：`VehiclePropConfig.access` 必须包含请求的操作，取值语义为 `READ`、`WRITE` 或 `READ_WRITE`。请求的 `areaId` 必须是该属性配置支持的区域；不支持的区域可能抛出 `IllegalArgumentException`，错误信息包含 `not writable at areaId` 等内容。
2. **框架权限校验**：CarPropertyService 经 `PropertyHalService` 检查 Android 权限。vendor 属性通过 `VehicleVendorPermission` 映射权限；未配置专用权限时通常使用 `Car.PERMISSION_VENDOR_EXTENSION`。映射为 `NOT_ACCESSIBLE` 的属性不能通过该机制访问。权限不足通常表现为 `SecurityException`，而不是 VHAL 返回属性值。
3. **属性可见性**：调用方没有 vendor 属性所需权限时，属性可能从其可见属性集合中被过滤，因此查询不到属性不等于 VHAL 没有实现它。排查前先核对调用方权限和该属性的权限映射。
4. **区域与范围元数据**：global 属性使用 `areaId = 0`。范围查询能力取决于接口版本和属性是否提供相应元数据。AIDL VHAL V4（Android 16 起）增加了 supported values、最小值和最大值的查询能力。旧接口或未提供对应数据时不能假定这些查询可用。
5. **定位方法**：用 `dumpsys car_service --services CarPropertyService` 检查服务侧属性配置与 listener 状态，并在 logcat 中核对异常类型、属性 ID、areaId 和调用方权限。不要把权限拒绝直接归因于 VHAL 实现缺失。

**Q2: [learning] VHAL 进程死亡后 CarService 为什么会重启？重启期间哪些状态会丢失，如何排查反复重启？**

CarService 持有 VHAL Binder 连接。连接死亡后继续使用旧句柄无法提供可信的属性服务。AOSP 的死亡处理会记录 VHAL death 并触发 CarService 进程重启，系统再按 sticky service 语义拉起服务。客户端连接和进程内订阅随旧进程结束而失效。

1. **后端选择**：AOSP `VehicleStub` 在设备声明 AIDL VHAL（`IVehicle/default`）时优先连接 AIDL；未找到时可回退到 HIDL 实现。具体可用后端受设备配置和 Android 分支影响。支持该测试配置的 userdebug 构建还可使用 `FakeVehicleStub`，在没有真实车辆 HAL 的环境中提供对照数据。
2. **死亡与重启**：`VehicleDeathRecipient` 收到 Binder death 后记录类似 `car_service_vhal_died` 的 EventLog，并触发 CarService 自身退出；CarService 服务以 `START_STICKY` 方式恢复。该路径的目标是丢弃失效 VHAL 句柄，避免以过期状态继续服务。
3. **客户端恢复**：CarService 重启会使客户端 Binder 连接断开；新进程中的 property listener 也不会保留。客户端应处理服务断连、重新获取 Car 服务并重新注册所需 listener，不能把重连理解为订阅状态已自动恢复。
4. **调用重试**：同步 get/set 的超时值、`TRY_AGAIN` 和远端异常重试策略属于具体 AOSP 分支实现，常见实现包含约 10 秒的同步等待上限及以约 100 毫秒间隔、最多约 2 秒的重试窗口；这些数字不是跨版本的 VHAL 接口保证。排查时应以设备分支源码和实际日志为准。
5. **诊断与处理**：在 logcat 中查找 `Vehicle HAL died`、`Car service will restart` 一类消息，并关联 EventLog 的 `car_service_vhal_died` 记录。如果重复出现，先定位 VHAL 崩溃、Binder 服务反复退出或启动失败的原因；CarService 的重启只是恢复机制，不会修复 VHAL 故障。

**Q3: [learning] 连续属性上报频率与订阅结果为什么可能和应用请求不同？VHAL 对订阅、VUR 和可用性恢复有什么契约？**

订阅频率是 VHAL 可执行范围内的请求，不是对每个采样时刻的硬实时承诺。相同 callback 对同一属性再次订阅会更新该订阅。事件是否重复上报还取决于属性变化语义、VUR 支持情况和所用 Car API。

1. **采样率范围**：连续属性配置中的 `minSampleRate` 和 `maxSampleRate` 描述该 VHAL 支持的采样范围。客户端请求超出范围时，Car 服务可将速率限制到配置范围；配置范围不代表 VHAL 必须支持区间内任意精确频率，也不保证每个时间窗口严格等间隔。上报过慢时，应先核对属性配置和 VHAL 实际事件节奏。
2. **同一 callback 的更新**：同一个 callback 对同一属性只有一个有效订阅；再次订阅会更新其采样率等订阅参数，而不是新增一份并行订阅。不同 callback 可以分别订阅同一属性。客户端若更改订阅速率，应确认更新作用于预期 callback。
3. **VUR（Variable Update Rate）**：VUR 只对支持它的连续属性/API 生效。当前 `subscribePropertyEvents` 的订阅配置默认对连续属性启用 VUR；未显式关闭且属性支持时，值未变化的重复事件可被抑制。旧 `registerCallback` 等接口的默认行为不同，不能概括成“服务端始终默认开启”。使用 VUR 的应用在值不变时不应期待周期性重复回调；`ON_CHANGE` 属性本身按变化上报，不应把连续属性的速率语义套用到它。
4. **不可用状态恢复**：属性状态从 `NOT_AVAILABLE` 恢复为 `AVAILABLE` 时，VHAL 应通过状态变化事件通知客户端；区域属性要分别报告恢复的 area。若值已恢复但状态事件缺失，客户端可能继续把该区域视为不可用。
5. **诊断方法**：检查 `dumpsys car_service --services CarPropertyService` 中的属性配置、listener 和采样率；再对比 VHAL 侧事件日志、属性状态与客户端 callback。先区分“数值没变化而被 VUR 抑制”“属性仍不可用”和“VHAL 没有发事件”，再判断是否为断流。

**Q4: [learning] HIDL VHAL 迁移到 AIDL VHAL 后，批量 get/set、大数据传输和错误处理要适配哪些接口契约？**

AIDL VHAL 将属性读写改为按请求列表异步处理：一次调用可以通过多次 callback 返回部分结果，且结果顺序不保证与请求顺序一致。迁移代码必须以 request ID 和每项结果关联请求，不能沿用 HIDL 逐项同步返回的假设。

1. **批量和回调**：`getValues`、`setValues` 可接受一批请求；结果 callback 可能多次到达，每次只含当前完成的子集，顺序不保证。调用函数的返回状态表示本次调用是否被接受或整体是否成功，单个属性操作的成功与否还要检查对应 `GetValueResult` 或 `SetValueResult` 的状态。不得假定一次 callback 就包含整批结果。
2. **状态码与请求校验**：尚无初始值时应按契约返回 `TRY_AGAIN`；属性因车辆电源状态等原因不可用时使用 `NOT_AVAILABLE`。重复 request ID，或同一批次包含重复的 `(propId, areaId)` 请求，属于无效参数，应报告 `INVALID_ARG`。状态码要按请求/结果粒度处理，不能把一项失败当成整批全部失败。
3. **大型 payload**：AIDL 使用 `LargeParcelable` 支持大型列表和共享内存传输。生产者与消费者必须使用相匹配的序列化/反序列化辅助库，按该接口版本处理 inline payload 或共享内存文件描述符，并遵守文件描述符生命周期及平台限制；不能把共享内存 fd 当普通对象直接解析，也不应假定所有版本都要求相同的显式归还调用。
4. **能力查询与订阅**：supported values、最小值和最大值等查询由 AIDL VHAL V4（Android 16 起）提供；迁移到较早的 AIDL 版本时不能假设这些接口存在。采样率范围和同一 callback 重订阅更新仍按 AIDL VHAL 的属性订阅契约处理。
5. **兼容性回归**：使用目标分支 VHAL AIDL 接口仓库提供的 AIDL 测试和设备侧属性测试，核对迁移覆盖的属性、状态码、异步 callback 和大 payload 路径；同时检查 `aidl_api` 冻结快照中的接口版本与签名。具体测试目标名称随仓库分支变化，不应把某一分支的 `aidl_test` 命令当作通用接口保证。
