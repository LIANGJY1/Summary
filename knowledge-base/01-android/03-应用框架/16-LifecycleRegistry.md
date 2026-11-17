# LifecycleRegistry

> androidx.lifecycle 状态机机制学习资料（2.x/3.x 系源码锚点，AndroidLibs/androidx lifecycle-runtime，jvmCommonMain 实现）。边界：本文回答“生命周期事件的驱动来源、多观察者的一致性收敛、新观察者的补课语义、removeObserver 的特殊行为”；ComponentActivity 的装配入口与 Owner 挂载归 [15-ComponentActivity与ActivityResult.md](15-ComponentActivity与ActivityResult.md)，作用域用法归 [11-Fragment与ViewModel.md](11-Fragment与ViewModel.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Lifecycle 事件从哪里来？ReportFragment 为什么按 API 29 分两条通道？**

生命周期事件的最终来源是 Activity 生命周期回调：ComponentActivity 注入 ReportFragment，由它监听宿主 Activity 的 onCreate/onStart/onResume 等并转成 Lifecycle.Event 派发给 registry。API 29 是分界——系统在这一版提供了直接监听 Activity 生命周期的官方通道，两套机制并存按版本选择。

双通道结构：

1. **ReportFragment 注入**：ComponentActivity.onCreate 调 ReportFragment.injectIfNeededIn，向宿主 Activity 挂一个无界面 Fragment——它的生命周期回调与宿主严格同步，是“借 Fragment 生命周期转发 Activity 生命周期”的实现方式。
2. **API 29+ 通道**：经 ActivityInitializationListener 回调（reportFullyDrawn 等系统接口同路），与事件派发互不干扰。
3. **API 29- 通道**：ReportFragment 自身的 onCreate/onStart/onResume 直接 dispatch(ON_CREATE/ON_START/ON_RESUME) 对应事件。
4. **两通道并存**：injectIfNeededIn 同时布置两条，运行时按系统版本各自生效；ProcessLifecycleOwner 也借同一注入机制感知应用前后台。

排查“某个 observe 没收到 onStart”时先确认事件源这一层：Fragment 注入是否发生（自定义 Activity 未继承 ComponentActivity 且未手动注入就没有事件源）、事件是否被后续状态对齐吞掉（见状态机收敛）。

依据：AndroidLibs/androidx lifecycle-runtime 的 ReportFragment.android.kt（API 29 双通道注释）与沉淀文档。

**Q2: [learning] 多个观察者状态不同时，LifecycleRegistry 怎么把它们收敛到一致？**

核心是 sync() 收敛总闸加双向扫描：全局 state 先于表写入，之后 while(!isSynced) 反复扫描——全局状态低于最老观察者走 backwardPass（从最新观察者往回逐级 downFrom 撤回），高于最新观察者走 forwardPass（按登记序逐级 upFrom 补上），分发途中被再入改了状态（newEventOccurred 标志）则整轮重扫。

保证一致性的三个关键设计：

1. **登记序不变量**：先加者状态 ≥ 后加者。calculateTargetState 取三者的最小值作为新观察者的目标态——全局 state、前一个兄弟观察者的 state、补课栈 parentStates 栈顶。新观察者永远不超前于它前面的观察者。
2. **重入处理**：moveToState 在分发途中（handlingEvent）或补课中（addingObserverCounter≠0）被再次调用时，只挂 newEventOccurred 标志返回，由栈顶的 sync 统一收敛——避免递归派发。
3. **终态特判**：state 到 DESTROYED 时整表直接替换为空 Map，不再逐个摘观察者——ON_DESTROY 事件已随 sync 派发完毕，整表丢弃最快。

由此可推的排查纪律：观察者回调里再次改生命周期状态（如在 onDestroy 里 setCurrentState）不会立即派发，而是等顶层收敛——行为“迟到”不是丢失。owner 已被 GC 还在改状态时 registry 直接抛错，这是防静默错乱的使用方泄漏信号。

依据：AndroidLibs/androidx lifecycle-runtime 的 LifecycleRegistry.jvm.kt（moveToState/sync/forwardPass/backwardPass）。

**Q3: [learning] 中途 observe 的观察者为什么能立即收到“补课”事件？**

addObserver 对新观察者执行补课循环：计算目标态（三取最小）后，只要观察者当前状态落后于目标态，就沿 upFrom 逐级派发事件直到对齐——这就是“观察者一注册就收到当前状态”的粘性语义来源，LiveData 的粘性也是建立在这套机制上。

补课循环的细节：

1. **逐级而非直达**：从 INITIALIZED 到 RESUMED 之间按状态阶梯逐级 upFrom（CREATE→START→RESUME），观察者收到的是完整事件序列，不是一步跳到终态。
2. **循环内重算目标态**：每派发一个事件后重算 calculateTargetState——派发回调里可能又改了全局状态或有新观察者插入，目标态随之更新。
3. **防御检查**：循环条件带 observerMap.contains——派发过程中回调自己把自己移除了，循环立即终止。
4. **登记序的代价**：因为“先加者状态 ≥ 后加者”的不变量，后注册的观察者补课时最多追到先注册者的状态，不会越过——这保证了同一次状态推进中所有观察者看到的事件序与注册序一致。

依据：AndroidLibs/androidx lifecycle-runtime 的 LifecycleRegistry.jvm.kt（addObserver 补课循环与 calculateTargetState 注释）。

**Q4: [learning] removeObserver 为什么不发销毁事件？**

removeObserver 只把观察者从表中移除，不派发 ON_DESTROY。源码注释给出两条理由：这些事件根本没发生过，与 addObserver 的补课相反，补课派发的是“发生过但提前”的事件；部分移除源于致命事件，若强行发销毁事件，清理逻辑会变复杂。注释中的实例是，观察者在 onStop 向服务器报告会话结束并关闭连接——若断网导致移除时也派发销毁事件，onStop 就得额外区分“正常结束”与“被移除”两种语义。

使用边界：

1. **资源清理不依赖 ON_DESTROY**：removeObserver 后不会再有任何回调，清理要在移除前自行完成，或绑定生命周期让 ON_DESTROY 路径接管。
2. **对照记忆**：addObserver 补课是“把没赶上过去补上”，removeObserver 是“没发生就不补发”——两个方向的不对称是有意设计，不是遗漏。
3. **主线程约束**：add/removeObserver 与 setCurrentState 都 enforceMainThreadIfNeeded——registry 的状态机与派发不做线程同步，跨线程调用直接抛错。

依据：AndroidLibs/androidx lifecycle-runtime 的 LifecycleRegistry.jvm.kt（removeObserver 的设计注释）与沉淀文档。
