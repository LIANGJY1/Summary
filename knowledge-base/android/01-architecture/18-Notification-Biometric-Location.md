# 通知、生物识别与位置系统服务链路

> 学习资料（文章模式沉淀）。主线：NotificationManager、BiometricService、LocationManager 三条高频系统服务链路的进程边界、请求语义与性能归因——一次调用返回只代表哪一段完成、请求在哪里被限流或合并、耗时应当归因给谁。源文档：android-internals-wiki §1.23《Android 17 NotificationManager 架构与性能优化》、§1.24《Android 17 BiometricService 架构与性能优化》、§1.25《Android 17 LocationManager 架构与性能优化》（Android 17 / ACK 6.18 语境）；POST_NOTIFICATIONS 权限行为、Live Update 提升条件、生物识别强度能力边界、BiometricPrompt 会话替换语义已于 2026-09-25 与 developer.android.com、source.android.com 核对。系统分层与进程边界背景见 [01-Android系统架构.md](./01-Android系统架构.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 调用 NotificationManager.notify() 返回后，系统已经完成了哪些工作、哪些还没有发生？**

notify() 返回只说明同步提交阶段结束：客户端完成构建与序列化，system_server 中的 NotificationManagerService（NMS）完成同步校验；此时通知可能尚未正式发布，SystemUI 更是一定还没有画出界面。通知从应用到屏幕要穿过三个进程边界——应用组装数据、NMS 校验入队排序并分发、SystemUI 把快照转成界面列表项。

同步 Binder 调用（`INotificationManager.enqueueNotificationWithTag()`）返回前，NMS 已经可能完成：

1. 验证调用 UID 能否代表目标包发布，应用前台服务（FGS）通知策略与 flag 修正；
2. 读取 `ApplicationInfo`，校验 full-screen intent 等权限；
3. 检查自定义 `RemoteViews` 的估算内存；
4. 按渠道 ID 从 `PreferencesHelper` 读取渠道，渠道不存在时直接拒绝发布；
5. 创建 `NotificationRecord`，执行速率、数量、snooze 等淘汰检查，并给通知内的 PendingIntent 设置临时 allowlist。

返回之后才发生：`EnqueueNotificationRunnable` 入队（继承旧记录 ranking、交给通知助手）、`PostNotificationRunnable` 正式发布（写入列表、提取信号、排序、决定提醒效果）、监听器分发和 SystemUI 的视图 inflate。启用通知助手时发布还会被 `DELAY_FOR_ASSISTANT_TIME` 延后一段，这是设计行为而非线程阻塞。因此大图解码、`Notification.Builder.build()` 和 `notify()` 都放在主线程会先卡住应用自身；"已调用 notify()"不能作为业务成功确认，关键状态应另存。

**Q2: 对同一通知高频刷新进度（如 41% 变 42%）时，更新为什么会静默丢失？两级限流如何豁免状态变化？**

Android 17 对高频进度更新设置了两级限流，触发时更新被直接丢弃且应用收不到异常或回调，所以持续刷新百分比经常不显示；豁免依据是进度状态变化，不是进度值本身。

1. **客户端限流**：`NotificationManager` 实例对同一 `(user, package, tag, id)` 且 `getProgressState()` 未变化的高频更新，执行以 5 次/秒为目标的客户端限流。状态只有 `NONE`、`ONGOING`、`COMPLETE` 三种，41% 变 42% 仍是 `ONGOING`，会被限流；从 `ONGOING` 变为 `COMPLETE` 的关键更新可以通过"状态改变"条件。
2. **服务端限流**：NMS 的 `checkDisqualifyingFeatures()` 用 `NotificationUsageStats.getAppEnqueueRate(pkg)` 做包级速率估算，阈值来自 `Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE`（AOSP 默认 5 次/秒），对已有同 key 已发布或待发布记录、进度状态相同且非系统自动分组的更新拒绝。

做法：按通知 key 合并状态，只发送用户能看见的变化；完成、失败、暂停等状态切换立即发送，中间进度按时间窗口采样。业务层不能把每次 `notify()` 当成可靠消息投递。

**Q3: 重复调用 createNotificationChannel() 或删除后重建同 ID 渠道，能绕过用户对渠道的设置吗？**

不能。渠道配置的最终决定权在用户：重复创建只能更新 name、description 等少数字段，应用不能提高现有渠道的 importance，也不能覆盖用户在设置中做出的修改；删除渠道只是留下 tombstone 标记（记录 `isDeleted` 和删除时间），同 ID 重建会恢复原有设置而不是得到全新配置。

机制上，sound、vibration 等多数行为在首次创建后由系统保存，后续用同一 ID 创建不会重置；用户从未修改过的渠道允许应用降低 importance。"用户不能提高 importance"是错误说法——用户在设置中可以自由提高或降低。tombstone 设计正是为了防止应用用"删掉再创建"绕过用户选择。需要全新的声音、重要性或用途时，做法是设计新的稳定 channel ID 并向用户解释迁移原因；渠道 ID 是持久身份，不要把版本号、语言或临时业务状态编码进去。

**Q4: POST_NOTIFICATIONS 运行时权限被拒绝后，notify() 还能调用吗？通知会进入 NotificationManagerService 吗？**

通常仍能调用、也仍会进入 NMS。Android 13（API 33）起非豁免通知受 `POST_NOTIFICATIONS` 运行时权限控制，但权限被拒绝不阻止 `notify()` 调用：AOSP 实现里 NMS 会照常创建和检查记录，在 post 阶段才根据包权限与渠道状态抑制显示，普通通知通常也不会因此向 `notify()` 抛异常——"权限拒绝后通知完全不进入 NMS"的说法不准确。

官方文档口径：用户选择不允许后，应用不能再发送通知（除非符合豁免），所有渠道被封锁；前台服务通知不再出现在通知面板，但前台服务任务仍显示在系统的活动应用管理（Task Manager）界面。媒体会话通知和满足条件的 self-managed call（应用自管通话，CallStyle）属于豁免。

边界：参数非法、包身份错误、危险 PendingIntent 或无效 FGS 通知仍可能抛异常，`notify()` 不是永不失败；`areNotificationsEnabled()` 在 Android 17 检查包级 `POST_NOTIFICATIONS` 状态，不能替代渠道级检查。应用应把通知发布设计成尽力而为的界面更新，关键业务状态另存于可靠存储。

**Q5: Android 17 对通知里的自定义 RemoteViews 施加了哪两层内存检查？超限后分别发生什么？**

第一层在 NMS：同步提交阶段用 `RemoteViews.estimateMemoryUsage()` 检查自定义折叠、展开、heads-up 和 public 视图，估算内存大于 2,000,000 字节写 warning，达到 5,000,000 字节直接剥离该 `RemoteViews`。第二层在 SystemUI：`NotificationCustomContentMemoryVerifier` 在 apply 自定义视图后遍历其中的 `ImageView`，`BitmapDrawable` 按实际像素内存 `allocationByteCount` 计入，其他 Drawable 按固有宽高乘 4 估算。

分两层的原因：第一层检查的是传输对象的估算值，覆盖不了 URI 或资源图片在 SystemUI 解码后的真实占用，第二层补上这部分。版本边界：`CHECK_SIZE_OF_INFLATED_CUSTOM_VIEWS` 使用 `@EnabledAfter(BAKLAVA)`，target API 37 起强制拒绝超限视图，target 更低的应用同样超限时只收到迁移警告；两个 AOSP 阈值是 framework resource，可被设备配置覆盖，不能当作应用可用预算。

做法：能用 `BigTextStyle`、`MessagingStyle`、`CallStyle`、`ProgressStyle`、`MetricStyle` 表达的内容优先用系统模板；确需自定义视图时在解码前限制图片尺寸，并为视图被 NMS 剥离或被 SystemUI 拒绝准备可接受的退化展示。

**Q6: Android 17 想让进行中通知获得 Live Update 提升展示（promoted ongoing），只调用 setRequestPromotedOngoing(true) 够吗？**

不够。提升展示要求一组条件同时成立，缺一项系统就不会提升，而且是否最终设置 `FLAG_PROMOTED_ONGOING` 由系统决定。条件包括：

1. manifest 声明非运行时权限 `android.permission.POST_PROMOTED_NOTIFICATIONS`；
2. 调用 `setRequestPromotedOngoing(true)` 请求提升；
3. 通知处于进行中（ongoing）；
4. 提供内容标题（content title）；
5. 不使用自定义内容视图，只能用 Standard Style、`BigTextStyle`、`CallStyle`、`ProgressStyle` 或 `MetricStyle`；
6. 不是 group summary；
7. 不请求 colorized（整张通知使用强调色背景）；
8. 渠道 importance 不是 `IMPORTANCE_MIN`；
9. 普通 `POST_NOTIFICATIONS` 权限等其他通知条件仍然成立。

边界：`Notification.hasPromotableCharacteristics()` 只检查通知对象本身的结构条件，不包含用户是否允许、渠道 importance 或 OEM 附加条件；`NotificationManager.canPostPromotedNotifications()` 用于检查应用当前是否获准发布。用途上，Live Update 面向已开始、由用户发起且对时间敏感的活动（导航、行程、配送、进行中的训练）；促销或没有明确结束点的状态不符合用途，请求提升也不保证系统一定提升。

**Q7: BiometricPrompt.authenticate() 很快返回，为什么不能说明传感器已经开始采集？请求依次经过哪些组件？**

快速返回只说明请求已被系统接受：访问控制在 system_server 完成后，后续工作被投递到专用线程异步执行，应用不会在 Binder 调用栈中等待传感器。Android 17 的一次认证依次经过：

1. 应用进程 `BiometricPrompt` 经 `IAuthService`（`Context.AUTH_SERVICE`）进入 system_server——普通应用不直接持有内部 `IBiometricService`；
2. `AuthService` 做访问控制：生物识别权限、AppOps、token 与 `PromptInfo` 检查、调用方前台状态，随后清除来访 Binder 身份；
3. 内部 `BiometricService` 用 `PreAuthInfo` 计算本次有资格参与的传感器（强度、录入、锁定、设备策略、相机隐私等），创建 `AuthSession`；
4. `FingerprintService`/`FaceService` 的 provider 创建认证 client 放入该传感器的 `BiometricScheduler`，经 AIDL `ISession` 驱动 vendor HAL；
5. 采集、模板匹配与 Strong 认证的 HAT 生成在安全隔离环境（TEE）完成——HAL 进程本身不是 TEE。

从调用返回到传感器真正开始采集之间，还隔着预认证、cookie 握手和 SystemUI 显示 Prompt。排查"生物识别慢"时先分段定位时间落在哪一段，而不是把整条链路当成一个黑盒耗时。

**Q8: BiometricPrompt 认证进行中再次调用 authenticate()，旧请求会排队等待还是被替换？**

会被替换，不会排队。Android 17 的 `BiometricService` 只有一个 `mAuthSession`：新请求会强制取消旧会话、关闭旧 UI，旧客户端通过回调收到取消；官方参考文档也明确写出认证进行中再次调用会停止前一个客户端，被中断的客户端收到取消错误。排队只存在于每个传感器自己的 `BiometricScheduler`，那是单个传感器内部对录入、认证等操作的串行调度，不是所有应用请求的全局队列。

应用侧应避免在配置变化、重复点击或状态重组时快速"取消—重建—认证"：这种写法会产生界面重建、HAL 取消确认和 scheduler 队列清理开销，还会让一次用户操作产生多份互相覆盖的回调。页面销毁时是否取消要结合保留策略，`onDestroy()` 里只对真正结束的页面取消。

**Q9: BIOMETRIC_WEAK（Class 2）认证成功后，为什么拿不到可用于 Keystore 的 HardwareAuthToken？**

因为只有 Class 3（`BIOMETRIC_STRONG`）传感器生成的 HAT 才会被 Keystore 采纳：Class 2 可以进入 BiometricPrompt，但不支持 Keystore 的定时授权和单次操作授权，即使回传 token，`AuthSession.onAuthenticationSucceeded()` 也会将其丢弃。按官方能力表：

1. `BIOMETRIC_STRONG` / Class 3：BiometricPrompt、Keystore 时间窗授权、Keystore 单次操作授权都支持；
2. `BIOMETRIC_WEAK` / Class 2：仅 BiometricPrompt；
3. Class 1（convenience）：不进入公开 BiometricPrompt API；
4. `DEVICE_CREDENTIAL`（PIN/图案/密码）：全部支持。

边界：指纹、人脸是认证模态，Class 是安全等级——二维相机不自动等于 Class 2，深度相机也不自动等于 Class 3，强度由设备按 CDD 要求声明并通过测试决定；OEM 声明强度与运行时降级合并后不会比初始声明更强。Class 2 与 Class 3 的采集、录入和匹配都要求在安全隔离环境中完成，framework 只能看到事件、受限标识和 HAT，看不到原始模板。需要 `CryptoObject` 或密钥绑定时使用 `BIOMETRIC_STRONG`；`canAuthenticate()` 只是调用时刻的状态快照，不预留传感器也不保证稍后成功，最终结果以 `AuthenticationCallback` 为准。

**Q10: HAL 已报告生物识别匹配成功，为什么应用的成功回调还要再等一会儿？**

因为 Strong 传感器成功后系统还有收尾流程：`AuthSession` 暂存 HAT 并通知 SystemUI 播放成功或确认界面，等 SystemUI dismiss 后才把 HAT 交给 `KeyStoreAuthorization.addAuthToken()`，之后才调用应用的 `onAuthenticationSucceeded()` 并清理其余传感器。HAL 匹配成功不代表 App callback 已经执行。

三个"完成"时间点不能混用：

1. **HAL success**：secure matcher 已接受样本；
2. **SystemUI success**：Prompt 已进入成功或确认状态；
3. **App callback**：token 已处理、SystemUI 已 dismiss、回调已进入应用 Executor。

用户可能在成功动画开始时就认为认证完成，业务代码必须等 App callback；性能指标要写明采用哪个结束点。另一个边界：应用回调运行在调用方传入的 `Executor` 上，用 `getMainExecutor()` 时在回调里读数据库、访问网络或做复杂解密会占用主线程，重任务应交给后台执行器、仅把 UI 结果切回主线程。

**Q11: 人脸与指纹同时可用时，Android 17 的 BiometricPrompt 会做特征融合吗？两种模态的启动顺序如何协调？**

不做融合。framework 只协调各传感器的启动、UI、成功、失败和取消，不执行厂商级特征融合：任一传感器成功后记录 `mAuthenticatedSensorId` 并取消其余传感器（需要显式确认的部分场景除外），后到的成功或错误回调按当前 session/requestId 过滤。

启动顺序：`AuthSession` 等所有有资格传感器的 cookie 就绪后，先启动非指纹传感器并请求 SystemUI 显示 Prompt；指纹则等 SystemUI 入场动画完成（`onDialogAnimatedIn`）后才启动——源码注释说明这是为了避免指纹交互提示在对话框出现前露出。人脸可以先于 Prompt 界面运行。

推论与边界：双模态设备的总延迟不能写成 `max(人脸, 指纹)`——两个模态启动时刻不同，失败后的 UI 策略也不同；人脸的 `onAuthenticationFailed()` 是终止回调（进入可重试暂停），指纹的同名回调不是终止（operation 可继续等待下一次触摸）。framework 没有"所有认证 30–60 秒强制结束"的统一常量，超时可能来自 HAL error、sensor client 实现、SystemUI 交互状态或 scheduler watchdog，排查时记录实际 error 和 modality，不套用固定秒数。

**Q12: 向 LocationManager 的 network provider 提交 QUALITY_HIGH_ACCURACY 请求，会自动启动 GPS 吗？**

不会。平台 API 的规则是 provider 名称决定请求交给哪个 `LocationProviderManager`，`LocationRequest.quality` 只是给该 provider 的质量与功耗提示：向 `gps` 提 `QUALITY_LOW_POWER` 不会迁移到 `network`，向 `network` 提 `QUALITY_HIGH_ACCURACY` 也不会启动 GNSS，quality 的解释由具体 provider 实现决定。旧的 `Criteria` API 也只是调用前从现有 provider 中挑选一个名称，不是一次请求同时驱动多个 provider。

两个易混名称要分清：`LocationManager.FUSED_PROVIDER` 是平台定义的 provider 名称，调用者仍通过 `LocationManager` 使用；`FusedLocationProviderClient` 是 Google Play services 的客户端 API，回调类型是 `LocationCallback`，而平台 `LocationManager` 没有接收 `LocationCallback` 的 `requestLocationUpdates` 重载。排查时先看应用链接的是 `android.location.*` 还是 `com.google.android.gms.location.*`——API 入口不同，进程、日志和版本依赖都不同。做法：明确写出使用哪个平台 API 和哪个 provider，不把 quality 当自动选源器。

**Q13: getLastKnownLocation、getCurrentLocation、requestLocationUpdates 三类读取位置方式的成本与保证有什么不同？**

最近位置只读服务端缓存、不为这次调用启动 provider，可能为 null 或已过时；单次位置先尝试不超过 30 秒的合格缓存、请求 duration 超过 30 秒会被截到 30 秒；连续更新建立长期注册并参与 provider 请求合并，需要自己管理生命周期。

1. **`getLastKnownLocation(provider)`**：读指定 provider 的缓存，不启动硬件；判断新鲜度用 `Location.getElapsedRealtimeAgeMillis()`（单调时钟，不受修改系统时间影响），不要用 `System.currentTimeMillis() - location.getTime()` 做唯一依据，并结合 accuracy 判断能否使用。
2. **`getCurrentLocation()`**：注册激活时缓存不超过 30 秒可立即返回；duration 超 30 秒被 `LocationProviderManager` 截断；权限、位置开关、provider 状态或 AppOps 不满足时可能很快收到 null，超时也返回 null——应保留 `CancellationSignal` 并在业务结束时触发，不把"等待单次结果"写成无期限状态。
3. **`requestLocationUpdates()`**：Listener 适合进程存活且生命周期清晰的页面或服务，保存同一实例并在 `onStop()` 用 `removeUpdates(listener)` 取消——及时取消既断开对回调对象的引用，也把注册从服务端合并请求中移除，避免页面不可见后仍维持高频 provider 请求；`PendingIntent` 适合跨组件交付，但受后台位置权限与系统节流约束。

**Q14: 同一 GPS provider 上同时有 1 秒与 30 秒两个注册，底层 GNSS 按什么工作？请求如何合并？**

底层按合并请求工作：同一 provider 上所有 active 注册合并成一条 `ProviderRequest`——interval 取最小值（1 秒）、quality 取数值最小即最强、max update delay 取最小、`lowPower` 做 AND；GNSS 以 1 秒节奏工作，30 秒注册只是被自己的投递过滤限速，不会降低底层功耗。一条高频请求会抬高同一 provider 上所有注册的共同成本。

合并规则（非 passive provider）：

1. interval、quality、max update delay 各取最小值，即最强要求；
2. ADAS bypass 与 ignore-settings 标志做 OR；
3. `lowPower` 做 AND；
4. `WorkSource` 收集接近最短 interval、会影响底层工作量的注册用于归因。

边界：合并只发生在单个 provider 内，不是"全系统挑一个最佳位置源"；若 `maxUpdateDelay / 2 < interval`，framework 把合并后的 batching delay 置 0。优化时用 `dumpsys location` 查看服务端接受的合并请求与注册调用者，找出最短 interval 和最强 quality 来自哪条请求，而不是只看某个业务模块自己的配置。

**Q15: 应用只有 coarse（大致位置）权限时，位置请求和回调结果会被系统怎样改写？**

两层改写：`LocationProviderManager` 把注册的 quality 改为 `QUALITY_LOW_POWER`，并把 interval 与最小更新间隔提高到 framework 内部的 10 分钟下限；返回的位置再经 `LocationFudger` 用随时间变化的偏移和网格化生成 coarse 位置——不是简单截断经纬度小数位。

前提是 Android 12 起用户可以在应用同时请求 fine 与 coarse 权限时选择 approximate location（大致位置）。边界："10 分钟"是 `android-17.0.0_r1` 的 framework 内部调度下限，不是公开 API 对所有设备、所有版本的回调承诺；后台另有动态 interval 调整，官方文档把普通后台应用描述为每小时只能收到少量位置更新，具体节流值由系统配置、进程状态和豁免条件决定。应用不应依赖固定的模糊半径或固定小数位数，需要大致位置时按能力降级设计。

**Q16: 位置回调的 oneway Binder 投递为什么仍有背压？Executor 拥塞给系统带来什么额外成本？**

因为服务端在交付前会为非 passive 的连续更新持有一个 partial wakelock（30 秒超时），应用侧 `LocationListenerTransport` 在指定 Executor 上执行完回调后，才通过 `IRemoteCallback` 通知服务端释放。oneway 只表示发送方不等同步返回，不表示接收端没有队列压力。

因果链：Executor 队列长时间拥塞 → 处理完成通知延后 → 服务端 wakelock 只能等回调或 30 秒超时 → 系统无谓耗电。所以回调应保持轻量，重计算转交工作线程；比这更长的后台工作应采用符合系统约束的执行与保活机制，不能把 framework 的交付 wakelock 当成业务 wakelock。
