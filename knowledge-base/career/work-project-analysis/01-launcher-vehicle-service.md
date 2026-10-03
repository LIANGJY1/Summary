# Launcher 项目架构案例：全局车辆服务与首页 Activity 的协作

> 真实工作项目分析（案例边界）。结论以本项目源码为边界，不概括为通用 Android 规则；通用机制归 01-android 各册。项目信息按仓库脱敏惯例处理。

**Q1: 在这个 Launcher 项目中，VehicleService 从进程启动到车辆服务可用的完整链路是什么，它如何与首页 Activity 的启动时序协作，为什么由 MainService 管理其生命周期？**

VehicleService 先由 BaseManager 创建并登记为可共享的 Manager 实例，再由框架 MainService 提供 LifecycleOwner，最后在车辆连接回调成功后进入业务可用状态。首页 Activity 与 MainService 都在 Application.onCreate() 之后进入各自的组件生命周期，但源码没有建立两者之间的同步屏障；因此，VehicleService 的创建和生命周期初始化都不能直接等同于车辆服务 ready，也不能据此保证车辆数据在首页首帧前可用。

启动链按实际依赖关系展开如下：

1. **进程启动与框架入口**：合并后的 Manifest 注册 AndroidX InitializationProvider，并声明 FrameworkInitializer。AndroidX Startup 在 Application.onCreate() 前运行 Provider；FrameworkInitializer 随后创建 LocalMainServiceManager，并请求框架启动 MainService。
2. **Launcher 初始化服务创建 Manager**：LocalMainServiceManager 创建 MainDependentService。它读取应用资源 android_ext_main_service；Launcher 在 configs.xml 中将其配置为 com.yadea.launcher.control.InitService，因此框架反射创建 InitService。
3. **先创建 VehicleService 单例**：InitService.doInit() 调用 createInstance(context, null, VehicleService.class)。BaseManager 创建并保存 VehicleService 实例，但这次没有 LifecycleOwner，所以尚未把它绑定到宿主 Lifecycle，也不会调用 VehicleService.onCreate(owner)。
4. **MainService 补充生命周期宿主**：框架请求启动 AAR 中的 com.androidext.core.init.MainService。它是 LifecycleService；创建时将自身作为 LifecycleOwner 交给已有的 LocalMainServiceManager。LifecycleOwner 沿 LocalMainServiceManager → MainDependentService → InitService 向下传递，最终再次调用 createInstance(context, owner, VehicleService.class)。BaseManager 复用现有 VehicleService 实例，将它注册为生命周期观察者。
5. **Lifecycle ON_CREATE 触发业务初始化**：MainService 的 Lifecycle 发出 ON_CREATE 后，VehicleService 的 DefaultLifecycleObserver.onCreate(owner) 被调用。这里的 onCreate 是 Lifecycle 回调，不是 Android Service.onCreate()；VehicleService 本身不是 Android Service 子类。回调中开始初始化 CarServiceManager、L2A 通信、屏幕广播监听，并发送延迟的 IVI 就绪消息。
6. **车辆连接异步变为 ready**：CarServiceManager.init() 通过连接回调报告底层服务状态。只有连接成功、VehicleService 的 isReady 变为 true 后，代码才注册车辆属性回调并读取 VIN。发送车辆属性时也会检查 mIsReady，尚未 ready 时可能直接返回失败。

首页 Activity 的时序与上面的框架链路有交集，但没有“MainService 必须先完成 Lifecycle ON_CREATE”的保证：

1. AndroidX InitializationProvider 在 Myapplication.onCreate() 之前执行，并在早期请求框架启动 MainService。
2. Android 随后调用 Myapplication.onCreate()；Launcher 在这里初始化 RearBoxCoverAlertManager、KanziDataSourceManager 等 Application 级对象。这些调用方通过 BaseManager.getInstance(VehicleService.class) 获取共享实例。
3. 系统再按 HOME Intent 启动 MainActivity。它继承 BaseActivity；BaseActivity.onCreate() 设置布局、ViewModel 并调用 initView()，MainActivity.initView() 初始化 Kanzi 页面。之后 MainActivity.onStart()/onResume() 推动 Kanzi 页面进入可见和前台状态。
4. MainService 与 Activity 的组件回调都发生在 Application.onCreate() 之后。框架发起 MainService 启动请求很早，但这些源码没有创建 MainService Lifecycle 回调与 MainActivity.onCreate() 之间的显式同步关系；车辆连接本身还是异步的。因此，Activity 首帧不是车辆服务 ready 的判断点，调用 getInstance() 也只表示 Manager 实例可取得。

由代码关系可以看出，这个项目让 Activity 和全局车辆服务分别依附于适合自己的生命周期边界：

1. **Activity 管界面可见性**：MainActivity.onStart()/onResume() 启动或恢复 Kanzi 显示，onPause()/onStop() 通知 Kanzi 页面离开前台。这些回调适合管理首页 UI，不适合承载需要跨首页暂停、销毁或重建继续工作的车辆信号订阅。
2. **MainService 管车辆资源边界**：VehicleService 不只被 MainActivity 使用。Myapplication 初始化的 RearBoxCoverAlertManager 和 KanziDataSourceManager 也会获取 VehicleService；尾箱盖安全提醒需要持续监听车辆状态。Application 是进程级初始化入口，但本身不提供这里使用的 LifecycleOwner，因此项目借框架 MainService 的 Lifecycle 统一承载 VehicleService，并在对应销毁事件到达时撤销广播和属性回调、释放 CarServiceManager。
3. **两阶段创建解决实例共享与生命周期绑定**：先以 null owner 创建并登记单例，使 InitService 和 Application 级调用方可以取得同一个 VehicleService；框架拿到 LifecycleOwner 后再次创建时复用该实例并补挂生命周期。它解决的是 Manager 实例复用与生命周期资源管理，不代表底层车载服务已连接。
4. **生命周期与业务就绪分开处理**：实例存在、Lifecycle 初始化执行、底层车辆服务 ready 是三个不同状态。Lifecycle 界定资源何时开始和释放；连接回调及 isReady 界定车辆业务何时可用。调用方必须允许未连接阶段存在，而不能把启动先后关系当作连接成功的保证。

这项取舍的效果是，首页 Activity 的重建或离开前台不会直接决定全局车辆订阅的生命周期，多个调用方也能共享同一个 VehicleService，并由框架宿主集中管理资源清理。代价是车辆能力可能晚于首页首帧 ready，调用方需要处理暂不可用状态。以上设计动机是根据本项目调用关系作出的推断，仓库未提供专门 ADR；它是该 Launcher 的特定方案，不是通用 Android 应用必须采用的模式。进程被系统终止时，Manager 和 MainService 仍会随进程消失，重启后需重新走初始化链。

源码核对入口：Launcher 的 `application/Launcher/src/main/res/values/configs.xml` 配置 InitService；`application/Launcher/src/main/java/com/yadea/launcher/control/InitService.kt` 分别在 doInit() 与 initLifecycle(owner) 创建 VehicleService；`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java` 实现 Lifecycle 回调；`Myapplication.kt` 初始化 Application 级 Manager；`MainActivity.kt` 管理 Kanzi 生命周期；`RearBoxCoverAlertManager.java` 和 `KanziDataSourceManager.java` 使用 VehicleService；`component/commonlibs/basemanager.aar` 提供 BaseManager、FrameworkInitializer、LocalMainServiceManager、MainDependentService 和 MainService 的框架实现。AndroidX Startup、DefaultLifecycleObserver 与 LifecycleService 仅用于解释平台时序和接口语义。
