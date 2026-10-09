# Private Space 与应用可见性

> 学习资料（文章模式沉淀）。边界：本文区分 Private Space、厂商应用锁、AAOS App Lock 与应用内鉴权，说明 Launcher 接入边界和跨空间 `content://` 读取的生命周期影响。Q 序列即结构，供 Atlas 同源直读。

**Q1: 厂商应用锁、Private Space、AAOS App Lock 与应用内鉴权分别保护什么？误认会导致什么问题？**

四者保护的对象不同，不能互相推断。下方分别说明它们的保护边界与平台行为。

按保护边界区分：

1. **Private Space：**Android 15 引入的私密资料用户。锁定时系统停止该用户，其中的 Activity、Service、Job 和进程随用户停止，应用入口、最近任务与通知对外隐藏。解锁后用户再启动，应用作为该用户下的独立安装运行，主空间应用数据不会自动复制过去。
2. **厂商应用锁：**OEM 自行实现的设备功能。它可能在启动应用、显示最近任务或通知前要求认证，也可能采用其他做法。由于没有跨厂商通用的公开接口，不能据此推断应用进程或资料用户已停止。
3. **AAOS App Lock：**车载特权组件。Android 14 起它以非捆绑应用形式提供，集成需要厂商平台密钥签名并放入系统镜像。它服务车载次用户，其锁定状态与 Private Space 资料用户状态相互独立，不能外推到手持设备。
4. **应用内鉴权：**例如应用使用 AndroidX `BiometricPrompt` 保护自己的页面或会话。它不改变 Android 用户、资料用户或其他应用的状态。

把这些机制混为一谈会造成错误诊断：

1. 通知消失可能是资料用户停止后的系统可见性变化，不足以证明 `PendingIntent` 被删除。
2. 最近任务消失可能是系统隐藏该用户的任务，不足以证明 Activity 主动调用了 `finish()`。
3. Private Space 锁定、厂商认证取消和应用会话过期具有不同的生命周期、授权与恢复路径，日志和状态机应分别建模。
4. 截至 Android 17 的公开手持设备 API，没有提供通用的逐应用 `AppLockManager` 接口。Android Advanced Protection Mode 是整机安全模式，不是给单个应用设置启动口令的机制。

**Q2: 普通 App 能探测 Private Space 吗？默认 Launcher 如何接入，受哪些权限约束？**

普通 App 不能可靠探测 Private Space 是否存在或其中的应用状态。访问隐藏资料用户受到角色与权限限制，这是隐藏语义的一部分。业务逻辑应把没有该入口当作设备、用户或管理员策略可能造成的正常差异。

普通应用与 Launcher 的可见范围如下：

1. **普通应用：**没有默认 Launcher 的角色与相应访问权限时，`LauncherApps.getProfiles()` 不会提供隐藏私密资料用户。调用者位于 managed profile 或 private profile 时，该方法只返回当前资料用户。即使应用拿到某个 `UserHandle`，它也不能据此绕过访问控制。
2. **状态查询：**`UserManager.isQuietModeEnabled(UserHandle)` 查询给定资料用户是否处于 quiet mode，但前提是调用方能取得可访问的句柄。`requestQuietModeEnabled()` 只允许前台默认 Launcher 或持有 `MANAGE_USERS`、`MODIFY_QUIET_MODE` 权限的调用者请求切换状态。
3. **公开 Launcher 路径：**公开文档要求 Launcher 声明 normal 权限 `ACCESS_HIDDEN_PROFILES` 并持有 `ROLE_HOME`。仅声明该权限不足以访问私密资料用户。系统镜像内部的特权调用可能使用 `ACCESS_HIDDEN_PROFILES_FULL`，但它受系统签名和特权权限配置约束，不是普通第三方 Launcher 的公开替代路径。

默认 Launcher 应按系统提供的用户信息和广播维护容器状态：

1. 调用 `LauncherApps.getLauncherUserInfo(user)`，并将 `LauncherUserInfo.getUserType()` 与 `UserManager.USER_TYPE_PROFILE_PRIVATE` 比较，以识别私密资料用户。
2. 使用 `UserManager.isQuietModeEnabled(user)` 表示资料用户当前是否锁定。API 36 起，再从 `LauncherUserInfo.getUserConfig()` 读取 `PRIVATE_SPACE_ENTRYPOINT_HIDDEN`，判断锁定时是否应隐藏解锁入口。用户锁定状态与入口可见策略是两个维度，不能压成一个布尔值。
3. 注册 `ACTION_PROFILE_AVAILABLE` 和 `ACTION_PROFILE_UNAVAILABLE`，从广播的 `EXTRA_USER` 取得发生变化的用户，再重新查询状态。不要用只针对 managed profile 的 `ACTION_MANAGED_PROFILE_*` 代替通用资料用户广播。

受管设备、全托管设备或管理员策略都可能关闭 Private Space。应用遇到不可见或无法访问的结果时，应继续按当前用户中的常规生命周期和 API 失败结果处理，而不是推断设备一定存在或不存在私密空间。

**Q3: [learning] Private Space 锁定后跨空间读取 content:// 失败，能否判定 URI 授权被撤销？长任务应怎样处理？**

不能仅凭读取失败判定 URI 授权被撤销。锁定会停止私密资料用户，其中的 ContentProvider 也可能不可用。URI 授权是否仍持有，与提供方用户或 provider 是否运行是两个独立条件。解锁后，如果授权仍有效且源对象还存在，读取可能恢复。

排查读取失败时分别检查以下状态：

1. **URI 授权：**调用方当前是否持有该 URI 的读权限或写权限，以及授权是否已变化。
2. **提供方状态：**提供方资料用户是否正在运行，ContentProvider 当前是否可用。
3. **源对象状态：**用户是否删除、移动了内容，来源是否仍能通过同一 URI 打开。
4. **异常解释：**`FileNotFoundException`、`SecurityException` 或 provider 不可用相关异常都不能单独证明授权已撤销，应结合以上状态判断。`content://` 是内容标识，不是可直接当作文件路径使用的路径。

上传、转码、OCR 或索引等任务若要脱离 URI 来源长期运行，应把输入复制到当前 App 用户域内的受控存储，并记录复制是否完成。完整处理链还应包含以下边界：

1. 用户选择内容后，先检查 MIME 类型、声明长度和当前可读性。
2. 长任务开始前确认复制已完成。若继续依赖原 URI，则在重新打开时处理来源被删除、provider 不可用和授权变化。
3. 日志不记录原始 URI、文件名、媒体标题或私密空间包列表。私密空间的存在本身带有隐私含义，即使对 `userId` 做哈希，也可能形成跨会话稳定标识。

Android 16 QPR2 起，系统提供从主空间向 Private Space 移动或复制文件的可选能力：用户从私密空间 Launcher 容器的“添加文件”入口选择系统文件选择器中的内容，系统组件在私密资料用户内以前台服务完成传输，文件放入私密空间的 `Downloads` 目录。是否集成由 OEM 选择，不能假定所有设备都提供该入口。

验证跨空间行为时，至少覆盖锁定瞬间已选 URI 的同步读取与延迟读取，并组合检查以下六类入口：

1. Launcher 启动。
2. 通知操作。
3. App Link。
4. 分享。
5. Photo Picker。
6. DocumentsUI。

这些场景还要分别与厂商应用锁开关组合，因为 OEM 锁的拦截时点不等同于 Private Space 停止资料用户的时点。
