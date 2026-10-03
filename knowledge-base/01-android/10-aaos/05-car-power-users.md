# 车辆电源与多用户

> 学习资料（文章模式沉淀）。边界：本文回答"车辆电源状态机、headless system user、切用户流程与 OEM 服务归属"。Q 序列即结构，供 atlas 同源直读。

**Q1: AAOS 的车辆电源状态机与 VHAL 属性访问对应用意味着什么？跨版本订阅接口有什么差异？**

电源状态由 CarPowerManagementService（CPMS）与 VHAL 协调，覆盖 On、Shutdown Prepare、Suspend-to-RAM、Suspend-to-Disk 与关机；CarPowerPolicyDaemon 集中管理电源策略，可按车辆状态关闭显示、音频、定位、蓝牙等组件。setListenerWithCompletion() 等 CarPower 接口是受权限保护的 System API（需要 CONTROL_SHUTDOWN_PROCESS），普通第三方应用不能用它延长电源切换——应按常规生命周期持久化最小恢复状态、接受进程随时被终止、在网络或音频能力恢复后重建会话。按 AAOS13 源码核对，CarPowerStateListenerWithCompletion、STATE_SHUTDOWN_PREPARE（值 7）与 CompletablePowerStateChangeFuture 已存在于本地 car-lib：特权服务在 Shutdown Prepare、Suspend Enter、Hibernation Enter 等状态会拿到非空 future，须在 getExpirationTime() 期限内 complete()，到期后系统仍继续转换；挂起或关机准备阶段不应启动大规模同步与缓存整理。

VHAL 使用 AIDL 接口 IVehicle.aidl（AAOS13 树已冻结 v1），把车速、挡位等车辆属性转换成 Android 侧统一接口，应用经 CarPropertyManager 访问并接受权限检查，不能绕过 CarService 直接向车内总线发消息；ADAS 等安全控制不依赖应用时序，信息娱乐显示的车辆数据要带时间戳与过期判定。订阅接口有版本差异：AAOS13 的 CarPropertyManager 用 registerCallback()（本地源码无 subscribePropertyEvents 也无弃用标注），Android 17 材料口径是 registerCallback() 已弃用、推荐 subscribePropertyEvents() 且默认启用可变更新率（数值未变化时省略重复回调）——跨版本代码要保留兼容路径。同步 getProperty() 可能耗时数秒、不能在主线程调用；订阅回调未显式指定 Executor 时落到创建 Car 时的事件线程或主线程，回调里只保存快照，解析、聚合与上传放到有队列上限的线程池，OEM 自定义属性更新过频或单次数据过大都会挤占车辆属性通道。

**Q2: AAOS 的 headless system user 是什么？切驾驶员的完整流程和失败语义是什么？**

headless 模式下 user 0 不可见、始终视为已解锁，前台是代表当前驾驶员的全（非系统）用户；驾驶员切换由 CarUserService 与 VHAL 的 SWITCH_USER 流程协作，失败语义按状态码区分。

1. **模式与仿真**：`isHeadlessSystemUserMode()` 读构建期只读属性；userdebug 可用 `cmd user set-system-user-mode-emulation headless` 复现问题（直接设 persist 仿真属性可能损坏设备，官方注释明确禁止）；
2. **SWITCH_USER 状态码**：已在前台 = USER_ALREADY_IN_FOREGROUND；HAL 内部失败则不做 Android 切换；HAL 成功但 Android 切换失败会再发 POST_SWITCH；同目标并发 = ALREADY_BEING_SWITCHED_TO；不同目标并发 = 旧请求被放弃且不发 POST_SWITCH；
3. **初始用户**：上电进谁由 InitialUserSetter 决定（默认行为/切换/创建/替换 guest 四类动作，ON_BOOT/ON_RESUME/ON_SUSPEND 三个时机），可被 HAL 的 InitialUserInfoResponse 覆盖；AAOS 用户默认禁用锁屏；
4. **诊断**：`dumpsys car_service --services CarUserService` 看 Initial user 与切换状态；切不动时按状态码对号，并确认 VHAL 实现了 SWITCH_USER。

**Q3: OEM 服务在 AAOS 多用户下"切用户后消失/重复多份"——组件该跑在 system user 还是前台用户？**

AAOS 的 OEM 服务注入点 `config_earlyStartupServices` 允许每个服务声明用户作用域：需要单实例（跨用户存活、绑定 HAL）的放 system（u0），UI/前台组件放 foreground——CarService 本体就运行在 system user。

1. **scope 关键字**：all/system/foreground/visible/backgroundVisible 加 bind 选项（含 startForeground）；服务"消失/重复"先查 scope 声明，再 `dumpsys activity services` 按 userId 过滤确认实际实例；
2. **任务与 Recents**：Recents 按 (user, task) 管理——切用户后嵌入应用白屏，先确认目标任务是否还在该 user 下（TaskView 修剪问题见 06-car-launcher Q4）；不要假设进程或任务跨用户存活，切换后用正确的 UserHandle 重新绑定；
3. **初始用户与 guest**：上电进哪个用户的决策链见 Q2。
