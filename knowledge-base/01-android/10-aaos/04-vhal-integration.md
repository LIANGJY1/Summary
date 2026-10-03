# VHAL 集成与契约

> 学习资料（文章模式沉淀）。边界：本文回答"CarPropertyService 两层校验、VHAL 崩溃恢复、订阅契约与 HIDL→AIDL 迁移差异"。Q 序列即结构，供 atlas 同源直读。

**Q1: VHAL 属性 get/set 抛 "not writable at areaId" 或 SecurityException——CarPropertyService 的两层校验是怎么做的？（服务侧视角，与 Q27 的应用侧互补）**

VHAL 属性读写要同时过两层校验：VHAL 侧 `VehiclePropConfig.access`（READ/WRITE/READ_WRITE）与 areaId 是否在配置的 areaIds 里；框架侧 `PropertyHalService` 的 Android 权限判断（vendor 属性经 `VehicleVendorPermission` 映射——默认落到 `Car.PERMISSION_VENDOR_EXTENSION`，NOT_ACCESSIBLE 则完全不可访问）。以下机制按 AOSP 近版源码核对。

1. **典型异常**：`IllegalArgumentException: Property ... is not writable at areaId ...`（areaId 不在配置里）、`SecurityException: Platform does not have permission ...`（权限层拒绝）；
2. **静默消失**：应用无权限的 vendor 属性不出现在属性列表、也不抛异常——排查"为什么拿不到某属性"先确认权限而不是怀疑 VHAL 没实现；
3. **areaId 规则**：global 属性 areaId 固定为 0；supportedValues/MinMax 查询只在配置带对应描述信息时可用；
4. **诊断**：`dumpsys car_service --services CarPropertyService` 看配置与权限映射；logcat 搜上述异常文案。

**Q2: VHAL 崩溃后整车服务什么表现？CarService 为什么会"自杀"重建？**

VHAL 死亡会连带 CarService 自杀：`VehicleDeathRecipient` 收到死亡回调后写 EventLog（car_service_vhal_died）并杀死自身进程，靠 `START_STICKY` 被系统拉起——期间所有 car_service 客户端断连、已注册的 property listener 全部丢失需重注册。

1. **后端选择**：`VehicleStub` 优先 AIDL VHAL（`IVehicle/default`），未声明则打 "No AIDL vehicle HAL found, fall back to HIDL version" 换 HIDL 兜底；userdebug 下可叠加 FakeVehicleStub 做无车对照测试；
2. **超时与重试**：同步 get/set 默认 10 秒超时；对 TRY_AGAIN/RemoteException 以 100ms 间隔重试、最长约 2 秒，之后以异常上抛；
3. **诊断**：logcat 搜 `***Vehicle HAL died. Car service will restart***`；EventLog 查 car_service_vhal_died；VHAL 反复崩溃会把 CarService 拖进 crash loop——先修 VHAL；
4. **机制取舍**：数据完整性优先于可用性——宁可整个 car 服务栈重建，也不带着失效的 VHAL 句柄继续跑（CarService 在启动链的挂点见 01-architecture/02 启动册 Q18）。

**Q3: 车速等连续属性上报频率低于配置、ON_CHANGE 属性"偶尔漏事件"——VHAL 订阅契约里哪些条款最容易被忽略？**

最常被忽略的三条：sample rate 只是指导值；同一 callback 的新订阅会覆盖旧订阅；ON_CHANGE 属性从不可用恢复时必须补发 AVAILABLE 事件。

1. **rate 是 guidance**：VHAL 只保证平均速率落在 [minSamplingRate, maxSamplingRate]，越界即夹取——"上报太慢"先查 VHAL 侧的 maxSamplingRate 配置；
2. **订阅覆盖**：同一 callback 对同一属性只有一个订阅，新订阅以不同 sampleRate 会覆盖旧订阅——"换个订阅者频率就变了"的首因；
3. **VUR（可变更新率）**：服务侧默认启用，值不变不上报——被误判为"断流"时先确认 VUR 语义（应用侧接口差异见 05-car-power-users Q1）；ON_CHANGE 属性强制关闭 VUR；
4. **恢复语义**：属性从 NOT_AVAILABLE 恢复时，VHAL 必须为每个恢复的 area 各发一条 AVAILABLE 事件，缺失就表现为"长期不更新"；
5. **诊断**：`dumpsys car_service --services CarPropertyService` 看 listener 与速率；比对 VehicleHal eventLog 的事件计数与实际取值。

**Q4: HIDL VHAL 迁移到 AIDL 后 get/set 偶发超时、大数据传输失败——两代 VHAL 接口的契约差异有哪些？**

AIDL VHAL 的批量接口是"可能多次回调、每次子集、不保证顺序"——与 HIDL 的逐条语义不同，迁移时最容易踩的是时序与错误处理的假设。

1. **批量语义**：getValues/setValues 的回调可分批到达且不保证顺序；整体失败看函数返回值，单条失败看每个 Result 的 status；
2. **状态码契约**：初始期无数据必须返回 TRY_AGAIN；属性未上电返回 NOT_AVAILABLE；重复 requestId 或同批重复 (propId, areaId) 是 INVALID_ARG；
3. **大包**：大数据量属性必须走 LargeParcelable 共享内存（共享内存文件数有上限、用完必须归还），解析要用配套库，否则共享内存 fd 解不开；
4. **能力差异**：supportedValues/MinMax 仅 AIDL 支持；sample rate 同样只是 guidance、新订阅覆盖旧订阅（见 Q3）；
5. **回归工具**：接口仓库自带 aidl_test 验证"HIDL 属性在 AIDL 全部支持"；对照 aidl_api 冻结版本检查属性签名。
