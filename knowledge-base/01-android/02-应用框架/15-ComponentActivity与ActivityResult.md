# ComponentActivity 与 ActivityResult

> androidx.activity 装配机制学习资料（1.x 系源码锚点，AndroidLibs/androidx HEAD f38a5e5）。边界：本文回答“ComponentActivity 的能力接口化装配、ViewModel 跨旋转的机制点、OnBackPressedDispatcher 回调栈与预测式返回、ActivityResult 的存档-恢复闭环”；ViewModel 的作用域选择与观察者绑定归 [11-Fragment与ViewModel.md](11-Fragment与ViewModel.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] ComponentActivity 自己几乎不实现能力，各项能力是怎么装配进来的？ViewModel 跨旋转存活靠哪个机制点？**

ComponentActivity 的答案是最小内核加能力接口化：把 Lifecycle、ViewModelStore、SavedState、返回键、Result、菜单各自拆成 Owner 接口，由本类在构造与生命周期回调里装配实现，消费者（Fragment、Compose、各 Jetpack 库）只依赖接口不依赖 Activity 类型——各库由此脱离单根继承树独立演进。

装配链与两个保存通道：

1. **构造**：init 块试图从上次 NonConfigurationInstances 取回 ViewModelStore 与 lifecycle registry，并注册三个生命周期观察者——ON_STOP 撤销输入、ON_DESTROY 按 isChangingConfigurations 决定是否清 ViewModelStore、ON_CREATE 懒恢复自移除。
2. **onCreate**：恢复状态、派发 context 给 contextaware 监听者、注入 ReportFragment 作为生命周期驱动源、按 @ContentView 膨胀布局。
3. **ViewTree 挂载**：setContentView 经 initializeViewTreeOwners 把各 Owner 挂上 decorView 的 tag——此后膨胀出的子视图都能经 ViewTree 静态方法检索到宿主能力，Compose 的 BackHandler 就是这样找到 dispatcher 的。
4. **ViewModel 存活机制点**：onRetainNonConfigurationInstance 把 ViewModelStore 交系统在配置重建时保留，新实例 ensureViewModelStore 取回——它只跨配置重建存活；onSaveInstanceState 走序列化、可能被进程死亡清掉。两套通道保留期不同，大对象走前者、小而可序列化的状态走后者，选错通道就是“旋转后数据丢了”或“进程恢复后大对象泄漏”两类事故。

时序细节：onSaveInstanceState 里先把 lifecycle 压到 CREATED 再调 super——保证观察者在保存期看到的状态不高于 CREATED，否则“已保存”会被误当“活跃”继续发 UI 事件。

依据：AndroidLibs/androidx（activity 1.x，HEAD f38a5e5）ComponentActivity.kt 与沉淀文档 §1。

**Q2: [learning] OnBackPressedDispatcher 怎么替代 onBackPressed 单点覆写？预测式返回手势带来哪些新约束？**

Dispatcher 把返回键裁决从“全应用一个覆写点”变成“回调栈加链式询问”：后注册的回调优先被问，hasEnabledCallbacks 自下而上逐层上报——预测式手势动画开始前系统先问“有没有人要拦”，据此决定播手势动画还是直接关闭 Activity。

两条执行路径：

1. **一按式**：onBackPressed 从栈顶找第一个 enabled 的回调，命中即 handleOnBackPressed 消费并结束；全栈未命中才走默认关闭。回调经 addCallback(lifecycleOwner, callback) 注册，ON_START 压栈、ON_STOP 摘除、ON_DESTROY 解除。临时禁用用 isEnabled 谓词优于 remove，保住栈位。
2. **预测式（API 33+）**：注册 OnBackInvokedCallback 桥接，BackEventCompat 承载手势进度三段回调。桥接要求主线程注册；手势一旦开始进入会话锁定，此时要先取消会话再出栈回调，顺序颠倒会崩溃。

两个高频坑：OnBackPressedCallback 绑 viewLifecycleOwner 时视图销毁即自动摘除，绑 Activity 则常驻——选错 owner 表现为“页面关了返回键还在被拦”；Fragment 与 Compose 的接入全部经 ViewTree 检索宿主能力而非直接引用 Activity，跨窗口 Dialog 有断连父级兜底上溯。

依据：AndroidLibs/androidx 的 OnBackPressedDispatcher.kt 与沉淀文档 §2。

**Q3: [learning] registerForActivityResult 的回调为什么进程重建后也不丢？注册顺序为什么不能放在条件分支里？**

ActivityResultRegistry 把“请求-响应”封装成类型化契约（ActivityResultContract\<I, O\>）并做存档-恢复闭环：pendingResults 在 onSaveInstanceState 存档、重建后 onCreate 之前恢复、按注册顺序对号入座——回调按“重建后同序重注册”对上号，结果不丢。

闭环的完整链路：

1. **注册**：生成随机 key（自 65536 起跳、撞码重摇，高位 16 位归注册表）并挂生命周期三钩子——ON_START 挂回调加补发信箱、ON_STOP 摘回调、ON_DESTROY 自动反注册。
2. **launch 与分发**：createIntent 产出 Intent 发起请求；结果回来时回调在场即分发并摘 launchedKeys 防重复消费，不在场（页面还没回 STARTED）落 pendingResults 信箱，ON_START 时补发。
3. **重建恢复**：存档 key 按 activity_rq#N 递增，与随机注册 key 是两套编号。恢复发生在 onCreate 之前，重注册对号依赖注册顺序稳定——条件注册（放在 if 里）会让重建后对不上号，这是“旋转后回调失联”的常见根因。
4. **时序边界**：register 必须在 STARTED 之前（onCreate/onStart），注册晚于结果分发会错过补发窗口。

契约设计的适用边界：createIntent 与 parseResult 把协议两端钉死在一个类型里，适合可枚举的交互（权限、拍照、选文件）；一次性过程式交互不必强行抽契约。降级路径也要留意——PickVisualMedia 的多选上限在系统照片选择器生效，降级到 OPEN_DOCUMENT 后限不了量，安全假设不能建立在降级路径上。

依据：AndroidLibs/androidx 的 ActivityResultRegistry.kt、ActivityResultContracts.kt 与沉淀文档 §3。

**Q4: [learning] enableEdgeToEdge、FullyDrawnReporter 这些边缘机制各自解决什么？**

activity 库的边缘机制是一组小型但完整的能力件，各有一处值得记住的设计选择：

1. **EdgeToEdge**：enableEdgeToEdge 按 API 版本阶梯选实现（Base/21/23/26/28/29/30），各版本只覆写自己变化的窗口 flag 维度——兼容代码按版本分层而不是 flag 堆叠。29+ 把强制对比度裁决交还系统。
2. **FullyDrawnReporter**：多模块首帧加载各自调用 drawn() 计数，全部归零且未上报过才真正调 reportFullyDrawn()；一次性闸门的状态机防止重复上报，二次查数保证“此刻全部就绪”语义——启动指标以此为终点比首帧更贴近可用。
3. **ImmLeaksCleaner**：API 23- 的输入法泄漏链（IMM→View→Activity）在 ON_DESTROY 反射置空 mCurRootView，OEM 上反射失败静默降级——典型的伤疤修复代码，不要按常规风格重构它。
4. **contextaware**：解决“对象先于 Context 诞生”（Fragment 构造期想要 ApplicationContext）：监听者先注册，context 就绪时先补发后登记，CopyOnWriteArraySet 保证广播期间增删安全。

其中 callbackFlow 冷流三段式加 awaitClose 清理（PipHintTracker 等使用）是 Flow 桥接回调 API 的标准形状，可在自研组件里复用同一形状。

依据：AndroidLibs/androidx 的 EdgeToEdge.kt、FullyDrawnReporter、ImmLeaksCleaner 与沉淀文档 §4。
