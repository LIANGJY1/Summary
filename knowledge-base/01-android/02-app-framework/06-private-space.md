# Private Space 与应用可见性

> 学习资料（文章模式沉淀）。边界：本文回答"Private Space 与厂商应用锁/AAOS App Lock 的区分、Launcher 接入与跨空间 content 访问"。Q 序列即结构，供 atlas 同源直读。

**Q1: 厂商应用锁、Private Space、AAOS App Lock 与应用内鉴权怎么区分？误认会出什么问题？**

四类机制的保护对象与系统行为不同：AOSP Private Space（Android 15 引入，材料口径；按 AAOS13 源码核对，本地 UserManager 无 USER_TYPE_PROFILE_PRIVATE，该机制不在 Android 13）锁定的是整个私密资料用户——用户停止后其中 Activity/Service/Job 与进程全部结束，应用入口、最近任务与通知被隐藏；OEM 手持设备应用锁由厂商实现，可能只在启动、最近任务或通知前插入认证，未必创建资料用户，没有跨厂商公开 API；AAOS App Lock 是车载特权组件（Android 14 起以非捆绑应用提供、须厂商平台密钥签名放入系统镜像），只服务车载次用户、与资料用户锁定状态相互独立，不能外推到手持设备；应用自有鉴权（如 AndroidX BiometricPrompt）只保护自己的页面与会话，不改变用户或资料用户状态。

误认的典型后果：把"通知不可见"当成 PendingIntent 被系统删除（实际是资料用户停止后的可见性隐藏）、把最近任务消失当成 Activity 主动 finish()（实际是系统隐藏相关任务）、把 Private Space 锁定、厂商认证取消与应用内会话过期归为同一种状态——三者的生命周期、权限与恢复路径都不同。另有两个名称陷阱：Android 17 手持设备的公开接口没有 AppLockManager 一类通用逐应用锁；Advanced Protection Mode 是整机安全总开关，不负责给单个应用加启动口令。

**Q2: 普通 App 能探测 Private Space 吗？默认 Launcher 用哪些 API 接入、要满足什么权限？**

普通 App 不能可靠探测：LauncherApps.getProfiles() 在调用者无权限时只返回当前资料用户，调用者本身位于 managed profile 或 private profile 时也只返回当前用户；UserManager.isQuietModeEnabled(UserHandle) 虽是公开方法，但调用者必须先持有目标 UserHandle，而隐藏私密资料用户的句柄普通 App 拿不到；requestQuietModeEnabled 只允许前台默认 Launcher 或持 MANAGE_USERS/MODIFY_QUIET_MODE 的调用者调用。这种限制是隐私设计的一部分——若任意 App 能判断设备有无 Private Space 及其运行与安装状态，隐藏语义就被削弱。业务上把"设备没有该入口"视为正常配置差异：受管设备、全托管设备或管理员策略都可能关闭该功能，App 应按当前用户中的常规生命周期与失败结果编程。

默认 Launcher 的接入有两条权限路径：声明 normal 级 ACCESS_HIDDEN_PROFILES 并持有 ROLE_HOME 角色；或系统应用持 signature/privileged 级 ACCESS_HIDDEN_PROFILES_FULL（无需 HOME 角色）。只声明 normal 权限不足以列出 Private Space。Launcher 从 LauncherApps.getLauncherUserInfo(user) 拿 LauncherUserInfo 并与 UserManager.USER_TYPE_PROFILE_PRIVATE 比较；用 isQuietModeEnabled 表示"资料用户已锁定"，用 userConfig 的 PRIVATE_SPACE_ENTRYPOINT_HIDDEN（API 36 起）表示"锁定时是否隐藏解锁入口"——两个状态不能合成一个布尔值，否则无法表达"已锁定但入口可见"；监听 ACTION_PROFILE_AVAILABLE/ACTION_PROFILE_UNAVAILABLE 并从 EXTRA_USER 取变化用户，收到广播后重新查询，不要用 managed profile 专属的 ACTION_MANAGED_PROFILE_* 替代。

**Q3: Private Space 锁定后，跨空间的 content:// 读取失败是授权被撤销了吗？涉及私密空间的长任务该怎么处理？**

不一定是授权撤销。锁定会停止私密资料用户，其中的 ContentProvider 不再运行，读取可能抛 FileNotFoundException、SecurityException 或 provider 不可用相关异常；但"调用方是否持有该 URI 的授权"与"提供方用户是否运行"是两个独立条件，空间解锁后若授权仍在且来源对象未被删除，读取可能恢复——单凭异常类型不能判定授权状态。URI 是 content:// 内容标识不是文件路径，读取失败排查时分别记录：授权持有状态、提供方用户与 provider 运行状态、来源对象是否存在。

长任务（上传、转码、OCR、索引）的处理原则：用户选择完成后立即检查 MIME 类型、声明长度与可读性；任务需要脱离来源长期执行时，把内容复制到当前 App 用户域内的受控存储并记录复制是否完成；重新打开 URI 时处理来源被删除、provider 不可用与授权变化；日志不写原始 URI、文件名、媒体标题或私密空间包列表——Private Space 的存在本身带隐私含义，即使对 userId 哈希，结果仍可能成为跨会话不变的标识。Android 16 QPR2 起增加从主空间向 Private Space 移动或复制文件的可选能力（由私密资料用户中的系统前台服务传输），AOSP 发布说明标注由厂商选择集成，不能假定所有设备都有同一入口。测试要覆盖锁定瞬间已选 URI 的同步与延迟读取、六类入口（Launcher、通知、App Link、分享、Photo Picker、DocumentsUI）与厂商应用锁开关组合。
