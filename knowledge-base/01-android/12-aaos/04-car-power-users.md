# AAOS 车辆电源、VHAL 属性与多用户服务

> 学习资料（文章模式沉淀）。边界：本文回答 AAOS 车辆电源切换对应用的约束、VHAL 属性读取/订阅方式、headless system user 的驾驶员切换流程，以及 OEM 服务在多用户下的作用域选择。状态名、接口和默认行为有平台版本差异，需按目标 AAOS 源码与 car-lib 确认。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] AAOS 进入关机或挂起状态时，电源回调与 completion future 对应用有什么约束？**

CarPowerManagementService（CPMS）与 VHAL 协调车辆电源状态转换。特权应用可以注册带完成回调的 CarPower listener，在平台允许完成确认的阶段用 future 表示本地工作已完成，但该确认有截止时间，不能阻止系统无限期进入下一状态。

1. **状态与策略分工：**CPMS 协调 On、Shutdown Prepare、Suspend-to-RAM、Suspend-to-Disk 等阶段以及关机转换。CarPowerPolicyDaemon 管理电源策略，可按车辆策略关闭显示、音频、定位或蓝牙等组件。状态转换回答系统处于哪一阶段，policy 回答阶段中哪些组件应可用。
2. **调用权限：**`setListenerWithCompletion()` 等 CarPower 系统接口属于受权限保护的 System API，需要 `CONTROL_SHUTDOWN_PROCESS`。普通第三方应用不能使用它们延迟电源切换，应按普通进程生命周期保存最少恢复状态，并接受进程可能被终止。
3. **完成通知：**AAOS 13 的 car-lib 已包含 `CarPowerStateListenerWithCompletion`、`STATE_SHUTDOWN_PREPARE`（值为 7）和 `CompletablePowerStateChangeFuture`。在支持 completion 的状态回调中，listener 收到 future 后可执行短小、必要的清理，再调用 `complete()` 表示本阶段处理完成。
4. **截止时间：**`getExpirationTime()` 返回基于系统 elapsed time 的到期时间戳。到期前没有调用 `complete()` 时，CPMS 仍会推进状态转换。它是有限的清理窗口，不是可延期的后台任务时长。
5. **工作边界：**在 Shutdown Prepare、Suspend Enter 或 Hibernation Enter 等转换阶段，不应启动大规模同步写入、长时间网络请求或全量缓存整理。把必要状态预先持久化，等网络和音频等能力恢复后重建会话。

**Q2: [learning] AAOS 应用读取和订阅 VHAL 属性时，怎样处理同步调用、更新率和 Android 版本差异？**

应用通过 CarPropertyManager 访问 VHAL 暴露的车辆属性，并接受 CarService 的属性级权限检查。同步读取可能阻塞数秒，属性订阅的回调线程和重复值策略则受 API 版本与订阅参数影响，不能在所有 AAOS 版本上假设同一行为。

1. **数据路径：**VHAL 以平台定义的 IVehicle 接口暴露车速、挡位等属性。AAOS 13 参考实现使用 AIDL VHAL v1。应用通过 CarPropertyManager 读取或订阅属性，不能绕过 CarService 直接向车内总线发命令。
2. **安全边界：**属性权限、支持的读写模式、areaId 和可用状态均由属性配置与系统授权决定。ADAS 等安全控制不能依赖信息娱乐应用的回调时序。显示车辆数据时应使用属性时间戳和过期判断，避免把旧值当作当前状态。
3. **同步读取：**`getProperty()` 可能耗时数秒，官方 API 明确要求在非主线程调用。短路径可使用工作线程，支持目标版本时也可使用异步属性读取 API。查询失败时要区分属性不支持、当前不可用、权限拒绝和车辆服务内部错误。
4. **订阅 API 版本：**AAOS 13 的 CarPropertyManager 提供 `registerCallback()`，源码没有将该接口标记为弃用。较新的 Android API 已转向 `subscribePropertyEvents()`，并将旧注册/注销接口标为弃用。跨版本应用应按编译 API 和目标运行 host 保留兼容实现。
5. **更新率和可变更新率：**较新的 `subscribePropertyEvents()` 对连续属性可以指定更新率。默认可变更新率启用且属性/area 支持时，底层仍可按订阅率采样，但相同值的重复事件可以不再回调。若明确关闭该选项，订阅方会按配置频率收到相同值事件。按变化更新适合 UI 和状态显示，持续心跳等确实依赖每次采样的客户端才应请求固定重复回调。
6. **回调线程：**若订阅没有显式提供 Executor，回调会投递到创建 Car 时设置的事件 Handler，未配置时可能落到主线程。回调应快速复制属性快照并入队，把解析、聚合和上传交给有界后台队列，避免阻塞属性事件通道。
7. **采样治理：**订阅更新率要与业务刷新需要相符。OEM 自定义属性更新过频或一次事件数据过大都可能挤占车辆属性通道，因此应同时观察硬件采样率、回调频率、队列积压和属性时间戳。

**Q3: [learning] AAOS headless system user 如何工作？驾驶员切换、初始用户选择和失败状态分别是什么？**

Headless system user 模式下，user 0 是没有交互 UI 的系统用户，实际驾驶员界面由非系统前台用户承载。CarUserService 与 User HAL 的 `SWITCH_USER` 流程共同完成切换，HAL 返回成功不等于 Android 用户切换最终成功。

1. **系统用户模式：**`isHeadlessSystemUserMode()` 查询设备是否采用 headless system user 模式。User 0 保持系统服务运行但不作为正常驾驶员会话。平台可同时运行 user 0、一个前台用户和受配置限制的后台用户，具体数量由系统配置决定。
2. **仿真入口：**在 userdebug 构建上，`cmd user set-system-user-mode-emulation headless` 可用于切换仿真模式。其中 `set-system-user-mode-emulation` 指定用户模式仿真命令，`headless` 是目标模式值。不要直接改写 persist 仿真属性，系统源码警告这可能损坏设备状态。
3. **HAL 与 Android 切换结果：**`SWITCH_USER` 支持由 Android 发起或由 HAL 请求的用户切换。HAL 明确失败时 Android 不执行该用户切换。HAL 成功但 Android 的用户切换失败时，服务会发送 `POST_SWITCH` 请求以报告实际结果。应用不能把收到 HAL 成功响应当成前台用户已切换的最终凭证。
4. **并发目标相同：**目标用户已经是前台用户时返回 `STATUS_OK_USER_ALREADY_IN_FOREGROUND`。同一目标用户已有切换正在进行时，新请求返回 `STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO`。
5. **并发目标不同：**切换进行中收到另一个不同目标用户的请求时，旧请求会被放弃并开始处理新目标。旧请求会以 `STATUS_TARGET_USER_ABANDONED_DUE_TO_A_NEW_REQUEST` 结束，且不发送 `POST_SWITCH`。调用方应根据新请求的结果刷新界面状态。
6. **初始前台用户：**InitialUserSetter 可执行默认行为、切换到指定用户、创建用户或替换 guest。默认行为通常在首次启动创建并切换到新用户，后续启动回到最近活动用户，产品也可配置启动用户覆盖。User HAL 的 InitialUserInfoResponse 可返回切换、创建或采用默认行为的决策，并可覆盖默认选择。
7. **选择时机：**初始用户请求可因 `ON_BOOT`、`ON_RESUME` 或 `ON_SUSPEND` 发生。它们分别对应设备启动、从挂起恢复和进入挂起相关流程。目标版本中可用的时机与具体决策以 User HAL 和 CarUserService 实现为准。
8. **默认锁屏与诊断：**AAOS 的默认产品体验通常不要求像手机那样设置锁屏，但 OEM 可定制用户解锁策略。`dumpsys car_service --services CarUserService` 中 `--services` 用于筛选指定 CarService 服务，可查看初始用户与切换状态。切换失败时同时检查结果状态码、目标用户可切换状态和 VHAL 的 `SWITCH_USER` 支持。

**Q4: [learning] AAOS 多用户下，OEM 早启动服务应选 system、foreground 还是 visible 用户作用域？如何判断服务实例消失或重复？**

OEM 可通过 `config_earlyStartupServices` 为 CarService 托管的服务声明用户作用域、启动方式和启动时机。服务需要跨用户保持单实例时可选 system user，需要随驾驶员切换时选 foreground，面向所有可见座舱用户的组件则按 visible 范围设计。

1. **用户作用域：**`all` 表示 system 和所有可见用户，`system` 表示只在 user 0，`foreground` 表示当前前台用户，`visible` 表示所有可见用户，`backgroundVisible` 只表示可见的后台用户。`visible` 和 `backgroundVisible` 作用域从 Android 14 起受支持，较旧平台可能忽略配置。默认 user scope 是 `all`。前台用户切换时，foreground 服务会停止或解绑旧用户实例，再为新前台用户启动或绑定。
2. **启动方式：**配置项 `bind` 可选 `bind`、`start` 或 `startForeground`。`bind` 通过 `Context.bindService()` 绑定，CarService 持有连接。`start` 通过 `Context.startService()` 启动。`startForeground` 通过 `Context.startForegroundService()` 启动，服务应按 Android 前台服务规则及时调用 `startForeground()`。未声明时默认使用 `start`。
3. **生命周期与进程优先级：**被 CarService 绑定的服务运行在 CarService 所在持久进程关系中，进程优先级通常高于普通后台服务。它仍会受用户作用域约束，foreground/visible 用户失去对应状态后服务可能被停止。绑定服务默认也不能因此获得位置、相机、麦克风或网络访问权。
4. **启动时机：**`trigger` 可选 `asap`、`resume`、`userUnlocked` 或 `userPostUnlocked`。`asap` 可在用户完全加载前启动，服务需支持 direct boot。`resume` 在设备从挂起恢复时启动。`userUnlocked` 在用户解锁时启动，是默认值。`userPostUnlocked` 在解锁后再启动，适用于非紧急服务。`maxRetries` 控制断连后的重启/重绑定尝试次数，参考源码默认值为 6，重试延迟从 4 秒起逐步加倍。
5. **组件选型：**需要一份跨驾驶员共享并绑定 HAL 的进程内状态时，system user 作用域更合适。需要访问当前驾驶员应用数据或 UI 时，使用 foreground user。需要为同时可见的乘客区域分别提供服务时，考虑 visible 或 backgroundVisible，并确保服务代码正确处理每个 userId 的独立实例。
6. **排查“消失/重复”：**先核对配置的 user scope 和 bind 方式，再用 `dumpsys activity services` 查看实际服务实例。命令输出按 userId 识别实例：只有一个 userId 记录通常表示单用户范围，同一组件出现在多个 userId 下可能是 `all` 或 visible 作用域的预期行为。最后确认调用方是否用正确的 UserHandle 重新绑定，以及目标用户是否仍在运行或可见。
7. **任务与 Recents：**Recents 按 user 和 task 管理。切换用户后 TaskView 嵌入应用白屏时，应确认目标任务仍属于预期 user。不要假设进程或任务跨用户存活，切换后用目标用户对应的 UserHandle 重新绑定服务和任务。
