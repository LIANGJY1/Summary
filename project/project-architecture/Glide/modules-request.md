# Glide 模块解码：入口与请求层（根包 / manager / request）

> 锚点 commit `f38a5e5`｜归属主文档 [ARCHITECTURE.md](./ARCHITECTURE.md) §5，本文展开入口与请求层的完整模块卡片与核心类深卡片。

## 模块卡片

### 根包 com.bumptech.glide

**职责**：静态门面（Glide）、进程级装配（GlideBuilder/GlideContext）、一次加载的配置与执行 API（RequestBuilder/RequestManager）、过渡动画选项、列表预加载。
**对外接口**：`Glide#with/load/get/tearDown`；`GlideBuilder` 全部 setter（仅装配期可用）；`RequestBuilder#load/into/apply`；`RequestManager#pauseRequests/resumeRequests/clear`。
**关键协作**：下行 manager（生命周期分发）、request（请求树）、module（装配回调）、provider（Registry 零件）。
**设计动机**：门面无状态可变点外移（见知识库条目）——Glide 自身只存基础设施引用，请求级状态全部在 RequestManager/RequestBuilder/Request 里；`GlideContext` 强制收敛 ApplicationContext（`GlideContext` 构造器）防门面攥住 Activity。
**雷区**：`Glide#with(framework Activity)` 已废弃语义降级为 application 级（Fragment 不可见仍加载）——迁移目标 FragmentActivity。

### manager

**职责**：把 Android 生命周期翻译成请求启停；连通性监控；RequestManager 的创建与复用。
**对外接口**：`Lifecycle/LifecycleListener` 回调对；`RequestManagerRetriever#get`（全部重载收口 `#getSupportRequestManagerFragment` 一类的宿主挂载）；`ConnectivityMonitor`。
**关键协作**：`RequestManagerRetriever` 挂 SupportRequestManagerFragment 到宿主；`LifecycleLifecycle` 桥接 androidx Lifecycle；`RequestTracker` 持有请求集合。
**设计动机**：生命周期感知靠"给宿主塞一个透明 Fragment"实现——Fragment 与宿主同生命周期，回调天然对齐；framework Activity 版在 androidx 时代已退化（兼容性伤疤，见 Glide.md「RequestManagerRetriever」章）。
**雷区**：`RequestManagerRetriever#get` 的 pendingRequests Maps（按 tag 挂起）是主线程异步挂 Fragment 的补丁；第一个加载会触发首帧等待器（FrameWaiter）。

### request

**职责**：请求树（主/缩略图/error）构建与协调、单个请求状态机、Target 显示契约、过渡动画实现。
**对外接口**：`Request#begin/clear/isEquivalentTo`；`Target` 五生命周期回调 + `#getRequest/setRequest`；`Transition#transition` 返回值契约；`RequestCoordinator` 三 can* 裁决。
**关键协作**：根包 44 处 import 依赖本包（配置期主链）；`resource → request` ⚠ 过渡工厂与 `Target.SIZE_ORIGINAL` 哨兵在本包；`Target extends LifecycleListener`（manager 包）⚠。
**设计动机**：into() 幂等复用——`Request#isEquivalentTo` 支撑列表复用场景的"等价跳过"；协调器树（`RequestCoordinator#getRoot`）让缩略图/error 嵌套组合。
**雷区**：回调里重入 into/clear 抛 IllegalStateException（重入护栏）；`CustomTarget` 用完必须 clear 否则资源无法回池。

## 核心类深卡片

### RequestBuilder（根包）

**职责**：一次加载的全部配置装配与执行触发（泛型 TranscodeType 决定产物类型契约）。
**协作者**：`RequestBuilder#into(ImageView) → ImageViewTargetFactory` 建 Target → 私有 `into(Y, listener, options, executor)` 构建请求树；`#buildRequest → #buildThumbnailRequest/#buildErrorRequest` 组合协调器。
**设计动机**：load() 纯配置（`#loadGeneric` 只存 model + isModelSet 哨兵），ModelLoader 延迟到执行期解析——新增数据类型不需要动 load() 族。
**不变量**：into() 必须先 load()（isModelSet 断言）；autoClone——已发出的 builder 再改配置自动 clone（`BaseRequestOptions#autoClone` 语义）。

### RequestManager（根包）

**职责**：生命周期感知的请求总控——启停/恢复/清除 + Target 登记。
**协作者**：`RequestManager#track → RequestTracker#runRequest`；`#untrackOrAdjust` 取消登记；构造时 `ConnectivityMonitorFactory` 可选联网重试。
**设计动机**：页面退后台批量暂停（默认 pauseRequests）防白跑流量；销毁 clear 防泄漏——一套 API 同时解决体验与泄漏。
**不变量**：TargetTracker 弱引用登记（Target 被 GC 自动出表）；请求强引用由 RequestTracker 持有直到 clear（#346 的 pendingRequests 补位见知识库条目）。

### SingleRequest（request）

**职责**：单请求状态机：PENDING → WAITING_FOR_SIZE → RUNNING → COMPLETE/FAILED/CLEARED。
**协作者**：`#begin → #onSizeReady → Engine#load`；`#onResourceReady`（EngineJob 回调线程）校验类型 + 动画 + 交 Target；`RequestCoordinator` 询问三 can*。
**设计动机**：一把 requestLock 贯穿（组合状态原子性）+ 回调重入护栏（`SingleRequest#begin` 断言）。
**不变量**：全部转移持锁；EngineJob 回调先请求锁再 EngineJob 锁（b/136032534 锁序）。

### RequestManagerRetriever（manager）

**职责**：把任意 Context 参数翻译成正确作用域的 RequestManager（application/Activity/Fragment）。
**协作者**：`#get` 全重载 → SupportRequestManagerFragment 挂载 → `LifecycleRequestManagerRetriever` 按 Lifecycle 缓存/复用 manager；`Glide#with` 是唯一生产者。
**设计动机**：同宿主复用同 manager（Fragment findFragmentByTag 路径）；主线程未就绪时挂 pending Maps 延后完成。
**不变量**：RequestManager 与 Lifecycle 一一绑定（`RequestManagerFragment#getRequestManager`）；framework Activity 路径已降级为 application 语义。

### RequestCoordinator 家族（request）

**职责**：多请求裁决——谁能操作 Target。
**协作者**：`ThumbnailRequestCoordinator`（竞速：thumb 先到先显示、full 到达覆盖并 clear thumb）；`ErrorRequestCoordinator`（主失败才启动 error）。
**设计动机**：两/多请求共享一个 Target 时，"旧图覆盖新图""缩略图回退覆盖全图"必须单点裁决；实现类内部用 RequestState 枚举自行跟踪进度（防回调中查子请求状态死锁）。
**不变量**：全部状态迁移持共享 requestLock；`#getRoot` 支持树形嵌套。
