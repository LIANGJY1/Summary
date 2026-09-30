# 渲染实战：Compose 进阶

> 学习资料（文章模式沉淀）。主线：应用侧渲染优化的进阶层——Snapshot 的版本视图与并发边界决定状态正确性，View/Compose 互操作的成本在桥接、销毁策略与复用协议，动画与共享元素的性能由读取阶段、退出保留与布局遍历决定，Runtime 图形效果与 Compose Canvas 的成本在离屏边界与逐像素工作量。源文档：android-internals-wiki §22.5《Compose Snapshot、状态一致性与并发》§22.6《Compose First 与 View/Compose 互操作性能实战》§22.7《View、Compose 动画与共享元素性能》§22.8《Runtime 图形效果与 Compose Canvas》（材料按 Android 17 撰写）；RenderEffect（API 31）与 RuntimeShader（API 33）在 AAOS13（Android 13）已存在并按本地源码核对，RuntimeColorFilter/RuntimeXfermode（API 36）、setWorkingColorSpace（API 37）、getGpuHeadroom（API 36）、HardwareBufferRenderer（API 34）与 Choreographer 缓冲区积压恢复为 AAOS13 之后的新增、enableOnBackInvokedCallback（API 33）已按本地核对；Compose Runtime/UI/Animation 1.12.0 与 androidx.graphics 等 AndroidX 外部库按材料口径转写、未本地核对、不确定处已弱化；官方文档口径沿用源材料标注转写、本次未联网复核。渲染调度与硬件层机制层见 [../rendering/01-渲染管线与VSync调度.md](../02-rendering/01-渲染管线与VSync调度.md)，出图分型与 SurfaceView/EGL/Vulkan 机制层见 [../performance/07-渲染管线-基础与图形API.md](../07-performance/07-渲染管线-基础与图形API.md)，View 布局、列表与 Compose 重组测量基础见 [./06-渲染实战-View与Compose基础.md](./06-渲染实战-View与Compose基础.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 在后台线程读取 mutableStateOf 时读到的可能是哪个版本，Snapshot 按什么条件从状态记录链里选值？**

每个 `StateObject` 挂一条 `StateRecord` 链，每条记录带创建它的 Snapshot ID；对当前 Snapshot 可见需同时满足：记录 ID 不是 `INVALID_SNAPSHOT`、ID 小于等于当前 Snapshot ID、不在当前 Snapshot 的 `invalid` 集合，读取时选满足条件且 ID 最大的一条。机制（Compose Runtime 1.12.0，AndroidX 外部库材料口径）：`Snapshot.current` 使用线程局部状态，`snapshot.enter { }` 只在当前线程临时替换它，其他线程不随之切换；Snapshot 创建后，晚于它产生的记录不进这份视图，创建时仍未 apply 的其他 Snapshot 会被列入 `invalid` 集合，所以后台线程读到的正是它进入时刻的版本。读路径通常不加锁、直接遍历链表，但另一线程刚推进全局 Snapshot 时快路径可能暂时失败，Runtime 会进入 `sync` 临界区按新的 `Snapshot.current` 重试——"State 读取永远无锁"不成立。边界：可见性协议只解决"读到哪个版本"，`state.value++` 这类复合操作的正确性另需应用自己保证。

**Q2: takeSnapshot() 创建的只读视图为什么必须显式 dispose，不 dispose 会怎样？**

开放的 Snapshot 会固定（pin）自己仍可能读取的旧版本，限制状态记录的复用；存活越久，频繁变化的状态对象越可能积累越长的记录链，内存与遍历成本持续上升。GC 不能替代 dispose——源码没有应用可依赖的 `AbandonedSnapshot` 类型，只有被明确结束且尚未 apply 的 `MutableSnapshot` 才走内部 `abandon()` 路径，把自己的记录标成 `INVALID_SNAPSHOT` 供后续写入复用。资源管理规则（Compose Runtime 1.12.0 材料口径）：`takeSnapshot()` 放在 try/finally 中 dispose；`takeMutableSnapshot()` 在 apply 或放弃后仍要 dispose；嵌套 Snapshot 每层独立结束；`asContextElement()` 只负责协程恢复时进入或离开 Snapshot，不负责 dispose。边界：在只读 Snapshot 内写 State 会抛 `IllegalStateException`；`dispose()` 不修改全局状态，它只释放这份视图对旧状态记录的保留需求。

**Q3: 两个并发 MutableSnapshot 修改同一个 StateObject，apply 冲突按什么规则合并，内置变更策略有什么区别？**

apply 用三方记录判断冲突：`previous` 是待应用 Snapshot 修改前读到的版本，`current` 是父或全局状态现在的版本，`applied` 是它写出的版本；`StateObject.mergeRecords(previous, current, applied)` 返回合并记录则 apply 继续，返回 null 则得到失败结果。`MutableState` 把合并委托给自己的 `SnapshotMutationPolicy.merge()`：`structuralEqualityPolicy()` 按 `a == b` 判等、`referentialEqualityPolicy()` 按 `a === b` 判等，两者默认都不合并两个不同的值；`neverEqualPolicy()` 永远不等价，只应在每次赋值都代表有效变化时使用，否则并行写更容易冲突。做法：计数器这类有明确增量语义的领域可自定义 merge（如 `current + (applied - previous)`），merge 必须是纯函数、结果确定、执行快速、无副作用；"两个值相等"只消除无效写与冲突，不定义累加、集合并集或字段级合并。成本边界：apply 不扫描进程里全部 StateObject，以已修改对象集合为入口，但每个对象还可能遍历记录链、跑自定义合并、通知多个观察器，成本不能简写成严格 O(changed states)。

**Q4: 两个 Snapshot 各改不同 State 都 apply 成功，跨对象业务约束仍可能被破坏——这是为什么，怎么防？**

Snapshot apply 的源码明确注明不保证可串行化、也不阻止交叉写入：两个 Snapshot 读取同一组旧状态、各自修改不同对象时，已修改对象集合没有交集，记录级冲突检查全部放行，但"至少一名值班者在线"这类跨对象不变量在两次 apply 后失败——这就是写偏差（write skew）。`Snapshot.withMutableSnapshot { }` 是 `takeMutableSnapshot()`、`enter`、`apply().check()` 与 `dispose()` 的封装：代码块成功返回才尝试 apply，抛异常不 apply，冲突抛 `SnapshotApplyConflictException`；它只保证这批修改作为整体可见（apply 前其他 Snapshot 看不到、apply 后一起看到），不承诺一次重组或一帧，也不修复跨字段写偏差。做法：跨对象不变量放进单一不可变状态、`Mutex`、Actor、`StateFlow` 原子更新或数据库事务；多字段天然共同变化时一份不可变 `UiState` 通常优于多个 State 加事务。边界：代码块不能挂起、避免外部副作用；`withMutableSnapshot` 本身不重试，调用方捕获冲突重跑时代码块必须允许重复计算；外部 I/O、Binder 调用与文件写入不会随 Snapshot 回滚。

**Q5: mutableStateOf 可以从工作线程读写吗，state.value++ 为什么仍可能丢更新，enter 为什么不能跨协程挂起点保留？**

可以读写——全局 Snapshot State 跨线程可用，Runtime 保护记录结构并发布变化；但 `sync` 锁只保护状态记录结构，`state.value++` 是读、改、写三步，多个生产者仍会丢更新，复合原子性、多请求排序要应用自己用 `MutableStateFlow.update`、`Mutex`、Actor 或数据库事务提供。`Snapshot.enter` 修改的是当前线程的 Snapshot，协程挂起后可能在别的线程恢复，手工跨挂起点保留 enter 区间会破坏配对；单个协程内随恢复进入同一只读视图用 `asContextElement()`，它也不负责 dispose、也不授权多协程并行修改同一个 MutableSnapshot——对单个 MutableSnapshot 的记录选择按"多读取者、单写者"设计，多个线程同时写它属于未定义用法。边界：State 可跨线程不代表整条 UI 链可跨线程，View、ComposeView、Canvas 与依赖 Looper 的回调仍受线程约束，`SnapshotStateObserver` 也要求同一 Owner 的测量/布局/绘制观察在同一线程；在未 apply 的 Snapshot 内创建的 State 若提前泄露，较早的 Snapshot 读不到合法记录并报错，长期状态容器应在全局上下文创建、事务内初始化的引用在 apply 成功后再发布。

**Q6: 一次 state.value = x 写入后重组是怎么被安排的，为什么多次写入可能合并成一次重组？**

写入先被变更策略判断等价（相等则提前结束），随后修改状态记录、把 StateObject 加入当前 Snapshot 的已修改对象集合；全局推进或 apply 后 apply 观察器拿到对象集合，Recomposer 把它与各 Composition 的读取关系相交得到失效作用域，重组循环在帧时钟调度下执行——帧回调开始前的多次写入可以合并，同一次回调内也可能继续处理新到达的失效，所以"每次写入必重组一次"和"每帧恰重组一次"都不成立。机制（Compose Runtime 1.12.0 材料口径）：组合执行时 `recordReadOf` 登记"值 → RecomposeScope"，重启函数由编译器生成的 `ScopeUpdateScope` 保存；布局与绘制阶段用 `SnapshotStateObserver.observeReads()` 建立各自的作用域，状态读取发生在哪个阶段、失效就停在哪个阶段；变更对象集合只含对象身份，不含业务语义，也不等于重组函数列表。`SnapshotStateObserver` 实例不具备通用线程安全性，内部原子队列不代表观察器可被多个布局线程共享。观测边界：Perfetto 默认没有逐 State 轨道，详细跟踪的 `Compose:applyObservers` 切片只覆盖观察器调用阶段，不能当完整 apply 耗时。

**Q7: AndroidView 把一个 View 接进 Compose 后由谁测量谁，Compose 状态变化什么时候会让这个 View 重新布局或重绘？**

Compose 创建 `ViewFactoryHolder`（父类 `AndroidViewHolder` 是 ViewGroup）持有 View 并关联一个 `LayoutNode`：Compose 把自身 `Constraints` 与 View 的 `LayoutParams` 合成 `MeasureSpec` 调 `View.measure()`，用 `measuredWidth/Height` 确定 LayoutNode 尺寸，放置阶段调 `View.layout()`；View 没有转成可组合函数，它的测量、布局、绘制与事件模型仍然存在，外层调度由 Compose 接管（Compose UI 1.12.0 材料口径）。状态影响的分流：`update` 内读取的 Snapshot 状态变化由承载器的观察器安排再次执行 `update`（只读 `model.title` 时 `model.image` 变化不会触发）；View 属性设置调 `requestLayout()` 时找到对应 LayoutNode 请求重新测量，尺寸变化后再放置受影响节点；只调 `invalidate()` 最终走 `LayoutNode.invalidateLayer()`，View 报告的局部脏区不会原样传给 Compose 图层；绘制进行中发生的失效会用 `postOnAnimation` 延后到下一帧，避免同帧重复绘制。做法：分析混合页面要分别计数 `update` 次数、View `requestLayout` 次数、View `invalidate` 次数与帧数，重组计数替代不了后三项。`AndroidViewHolder` 实现了 `NestedScrollingParent3`，常规嵌套滚动可跨边界传递增量，但同向无限嵌套、`requestDisallowInterceptTouchEvent` 切换手势控制方要专项验证。

**Q8: AndroidView 的 factory/update/onReset/onRelease 各自的调用时机与职责是什么，怎样避免 View 与 Compose 状态互相回写的反馈循环？**

`factory` 在 UI 线程为每个 View 实例调用一次（构造 View、一次性属性、注册长期监听器）；`update` 在 factory 后至少一次、其读取的 State 变化后再次运行（以幂等方式把当前模型写入 View）；`onReset` 在兼容实例准备复用前清理瞬时状态；`onRelease` 在实例永久离开时终止释放资源。反馈循环的解法是唯一状态所有者加双边等值保护：Compose 参数是唯一状态源，View 监听器只上报用户操作（用 `fromUser` 之类参数区分来源），`update` 里比较旧值避免重复赋值，绝不在 `update` 内无条件回写 Compose 状态。

```kotlin
AndroidView(
    factory = { ctx -> LegacySlider(ctx).apply {
        setOnValueChangedListener { v, fromUser -> if (fromUser) currentCallback(v) } } },
    update = { slider -> if (slider.value != value) slider.value = value },
    onRelease = { slider -> slider.setOnValueChangedListener(null) },
)
```

边界：重操作不能放 `update`（状态每次变化都会执行），但 View 属性修改仍留在 UI 线程；模型是可变对象且原地更新时引用比较识别不了内容变化，应改用不可变界面模型或版本号；把 View 预先放进 `remember` 不能绕过 factory 成本，还破坏宿主、附着与复用语义。`AndroidViewBinding` 沿用同一套桥接与复用语义，减少的只是 `findViewById` 与类型转换，XML 加载与 View 测量布局绘制照旧。

**Q9: Lazy 列表里的 AndroidView 为什么必须提供非空 onReset 才能复用实例，onReset 之后 View 处于什么状态？**

Compose UI 提供两组 `AndroidView` 重载：没有 `onReset` 的版本不参与可复用内容容器的实例复用，带非空 `onReset` 的重载才创建可复用节点；`onReset` 之后实例可能进入 deactivated（暂时停用但仍由 Compose 保留）状态，下一次 `update` 不保证立刻发生，所以重置后的 View 必须处于不显示旧内容的安全状态。机制：两个 `AndroidView` 能否互换取决于它们在 Compose 中的分组结构是否一致，不只取决于业务 key；`onReset` 之后可能紧接着 `update`，也可能先停用再释放，清理逻辑要允许重复调用。复用清理按来源分类：

- **列表项痕迹**：旧监听器、标签、选择/按压/激活态与无障碍文案。
- **进行中工作**：动画、延迟任务、图片请求、协程与播放器任务。
- **内嵌组件**：RecyclerView、WebView、地图、视频自己的滚动与资源状态。
- **外部订阅**：与旧 `LifecycleOwner`、`SavedStateRegistryOwner` 或业务宿主绑定的回调。

资源型 View 可以保留可复用引擎，把列表项专属会话停在 `onReset`、引擎销毁留到 `onRelease`，前提是组件 API 明确支持；`onReset + update` 比新建还贵时回到控件实现调整复用边界，用 factory 调用数下降且绑定耗时稳定来证明复用生效。

**Q10: ComposeView 放进 RecyclerView 与放进 Fragment 各该用什么 ViewCompositionStrategy，为什么不要在 onViewRecycled 里 disposeComposition()？**

默认策略 `DisposeOnDetachedFromWindowOrReleasedFromPool` 就是为池化容器设计的：普通 View 树中从窗口分离即销毁 Composition，位于 RecyclerView 等池化容器时短暂分离保留 Composition，列表项被池永久释放或容器与窗口分离时才销毁；Fragment 的 View 生命周期短于 Fragment 实例，应设 `DisposeOnViewTreeLifecycleDestroyed`，跟随下一次 attach 所在 View 树的 `LifecycleOwner` 在 ON_DESTROY 销毁（Compose UI 1.12.0 材料口径）。做法：ViewHolder 初始化时只调用一次 `setContent`，数据绑定只更新 ViewHolder 持有的 `mutableStateOf` 状态；每次 `onViewRecycled()` 都调 `disposeComposition()` 会丢掉对象池保留 Composition 的全部收益，列表再次需要该 ViewHolder 时重建整个 Composition。边界：列表项含 `remember` 专属状态时用稳定 `key`、把状态提升到列表模型或明确设计保存恢复策略；多个静态 ComposeView 要有唯一 View ID 才能正确恢复 `savedInstanceState`；自定义宿主或手工窗口要确认 `ViewTreeLifecycleOwner` 与 `SavedStateRegistryOwner` 存在。

**Q11: 同一窗口里多个 ComposeView 是共享一个 Recomposer 还是各有一个，"每个 ComposeView 都很轻"为什么不成立？**

同一窗口的多个 ComposeView 通常按"显式父级 `CompositionContext` → View 树中可找到的 → 有效缓存 → 窗口 Recomposer"的顺序解析出同一个 Recomposer，但每个实例仍各自拥有独立的 Composition、槽位表、`AndroidComposeView`、LayoutNode 树、语义管理、状态注册与测量边界，内存与调度并不等价于一个根。机制：`ComposeViewContext`（1.11 引入、1.12.0 去掉实验注解，材料口径）可为兼容宿主边界内的多个 ComposeView 共享配置缓存、字体加载器、无障碍等宿主对象，共享范围受 Context、`LifecycleOwner` 约束，且不合并业务状态；它还支持用已附着的 View 构造上下文、为未附着的 ComposeView 调 `createComposition(context)` 做预组合（RecyclerView 预热路径），但预热后始终不附着的实例必须由调用方自行 `disposeComposition()`——这是要自管取消与销毁的路径，不是自动预取保证。做法：评估混合页面时记录 Compose 根数量、堆内存与销毁时机，十个小组件根与一个十节点根的差异要在目标版本实测，没有跨项目通用字节数。

**Q12: Compose 外层动画请求高帧率后，内嵌 AndroidView 里的视频为什么不会跟着提帧，混合树里帧率请求按什么边界传播？**

ViewGroup 的帧率请求不会自动传播给子 View，Compose 修饰符的偏好也不会自动设置 `AndroidView` 内部 View 的 `requestedFrameRate`；独立 Surface 的内容还可能在媒体或图形 API 边界自己表达帧率，最终由系统汇总可见内容请求并结合面板能力与功耗策略决定显示模式。机制与版本：自适应刷新率（ARR）自 Android 15 QPR1 起提供，Android 17 用 `Display.hasArrSupport()` 查询，Compose 1.9 起有 `Modifier.preferredFrameRate`（材料口径）；AAOS13 没有 ARR，只支持多刷新率模式切换，低版本上调用相关 API 需降级。边界：宿主界面每帧重组不会让视频凭空产生新缓冲区，SurfaceView 的视频更新也不要求宿主应用窗口同步重画——两条内容节奏可以不同；判断内嵌组件是否引入独立生产节奏属于出图分型问题，机制层由渲染管线专题覆盖，互操作层只需确认"普通 View 承载"与"独立 Surface 承载"对应的性能模型完全不同。

**Q13: View 属性动画里每个 tick 用 updateLayoutParams 改宽度为什么会持续掉帧，scaleX 这类变换属性能替代到什么程度？**

`updateLayoutParams` 会把新参数重新设置给 View 并触发 `requestLayout()`，动画每一帧都进入测量布局路径；transform 属性（scale/translation/rotation/alpha）只改绘制变换，不缩小 measured width、布局边界、触摸命中区域或无障碍边界，所以只有视觉展开且布局占位与命中区域可以保持目标尺寸时，才能用 `scaleX` 表达同一段视觉过程。机制（AAOS13 `ViewPropertyAnimator` 已核对）：`startAnimation()` 创建一个 `ValueAnimator`，在 UI 线程的更新回调中计算属性值、更新 View 状态并合并发出一次失效请求，它不会把回调迁到 RenderThread；`ObjectAnimator` 传入 `Property` 时直接调 `Property.set()`，传属性名时解析并缓存 getter/setter——"每帧都用反射"不准确，成本要回到 setter 的副作用判断。边界：产品要求周围内容随宽度移动、收起后不可点击或无障碍边界同步变化时，必须更新布局与交互状态并用 trace 确认参与重排的子树；`withLayer()` 在动画期间经 setup/cleanup 临时切硬件层，内容保持不变的短时 alpha/transform 有收益，动画同时改内容并 `invalidate()` 时图层重栅格化、收益消失；逐帧图片按解码后像素估算（1920 × 1080 RGBA_8888 一张约 7.9 MiB，30 张约 237 MiB），density 缩放、复用与硬件位图都会改变实际驻留。

**Q14: Compose 里 Animatable、Transition 与 animate*AsState 的动画值逐帧更新时，重组范围由什么决定，主线程卡一下动画会怎样？**

由读取位置决定——API 名称不预先决定失效阶段，`Animatable.value`、`Transition.animate*` 与 `animate*AsState` 都受 Snapshot 系统观察，在函数体读则逐帧重组，在 `offset`/绘制/`graphicsLayer` 回调读则停在对应阶段，一个值在多个阶段读取就建立多处观察。帧时钟：Compose 1.12.0 的 `AndroidUiFrameClock.withFrameNanos()` 注册 `Choreographer.FrameCallback`，Android 上没有脱离 Choreographer 自行计时的 Compose 界面时钟；主线程阻塞后回调延迟，下一次执行看到更大的时间差，动画值向前跳，Compose 不补画错过的中间帧，用户看到停顿后直接跳到较后状态。做法：透明度、位移、缩放一律在 `graphicsLayer { }` 或绘制回调中读取；alpha 低于 1 的图层可能需要中间合成，主线程变轻不代表 GPU 变轻，应在目标设备同时观察界面线程、RenderThread 与 FrameTimeline。判断帧是否按期用当前帧 deadline 或 FrameTimeline，不能把 16.6/11.1/8.3 ms 固化成设备常量；`Choreographer#doFrame` 只覆盖主线程回调，不含 GPU 完成与送显。

**Q15: 同一个 Animatable 被手势高频调用 snapTo 与 animateTo 有什么行为差异，Transition 在 1.12.0 的组合语义是什么？**

`Animatable` 经 `MutatorMutex` 互斥：新的 `animateTo`/`animateDecay`/`snapTo`/`stop` 会取消正在运行的变更，`animateTo` 从当前值继续、弹簧动画延续当前速度，取消以 `CancellationException` 沿协程父子关系传播，需要释放的资源放 `finally`；每个触摸采样点都调 `animateTo` 会形成连续取消的追赶效果，拖动跟随用 `snapTo`、松手用 `animateDecay`/`animateTo` 收尾更可控。`Transition`：`updateTransition(targetState)` 是可组合函数，内部用 `remember` 保存 Transition 并以 `DisposableEffect` 处理离开清理，参数或被观察 State 变化仍会让相关作用域重组——它不提供"组合永不重启"的语义；目标变化时更新迁移段，不同动画参数对中断连续性的处理不同，不能一概说成"每次切换都从头重启"。版本与选型边界：1.12.0 已弃用接收 `MutableTransitionState` 的 `updateTransition` 重载，改用 `rememberTransition(transitionState)`；`DeferredTransitionState` 与 `mutableTransform` 面向预测性返回场景，不是通用性能开关；防抖属交互约束——每次输入都有效时允许重定向并检查中断连续性，只允许一次的按业务状态屏蔽输入。按控制语义选 API：少量数值加协程顺序控制用 `Animatable`，一个离散状态协调多项属性用 `Transition`，单值自动过渡用 `animate*AsState`，内容进出用 `AnimatedVisibility`/`AnimatedContent`。

**Q16: AnimatedVisibility 退出动画期间旧内容还在组合里吗，AnimatedContent 快速切换为什么会同时保留多份内容？**

在——`AnimatedVisibility` 的内容保留到内建进入/退出动画以及注册在 `AnimatedVisibilityScope.transition` 上的自定义动画全部完成，期间 `LaunchedEffect` 任务未取消、`DisposableEffect.onDispose` 未执行、内容持有的图片与订阅仍然存活；`AnimatedContent` 切换目标时新内容执行进入动画、旧内容退出完成后才从组合移除，快速连续切换可能同时保留多份未退出的内容，默认 `SizeTransform` 还会为容器尺寸变化创建动画。机制：普通 `AnimatedVisibility` 的默认进入/退出包含展开与收缩，容器报告给父布局的尺寸随动画变化，父布局与同级可能反复测量；淡入淡出与缩放主要更新图层属性，滑动只改放置偏移、不缩小容器报告尺寸。做法与边界：独立创建的 `animate*AsState` 不属于 scope 的 transition，容器不知道它何时结束、可能与退出不同步——需要同步的自定义动画要注册到作用域提供的 transition 上；父布局无须跟随伸缩时给外层稳定约束、内部用淡入淡出或滑动；内容重时把昂贵订阅上移统一管理或退出后停止数据更新，缩短时长只是缩短保留窗口，没有通用毫秒数；`contentKey` 定义内容身份，两个目标映射同一键时不触发切换动画，可用来过滤"状态对象变、视觉身份不变"的更新。

**Q17: 共享元素过渡里 sharedElement 与 sharedBounds 怎么选，scaleToBounds 与 RemeasureToBounds 的成本差在哪？**

两端是相同视觉内容（跨页主图、相同图标）用 `sharedElement`——过渡期间只绘制正在进入的目标内容，子节点按动画 bounds 生成的约束逐帧重测重排；容器连续但内容变化（卡片到详情、Text 样式变化）用 `sharedBounds`——进入与退出内容经默认 fadeIn/fadeOut 共存，默认 `scaleToBounds()` 用 Lookahead 得到的稳定目标布局只做图形缩放。机制（Compose Animation 1.12.0，AndroidX 外部库材料口径）：匹配由 `SharedTransitionScope` 按 key 与 `AnimatedVisibilityScope` 的可见性完成，两端必须从同一业务 ID 派生相同 key（如 `article-image:42`），`rememberSharedContentState(key)` 用 remember 保存；`Text` 宽度变化会重新换行，官方优先建议 `scaleToBounds()`，需要随宽高比持续改变裁剪的图片才实测 `RemeasureToBounds()` 的逐帧 measure/layout 成本。placeholder 默认 `ContentSize`——退出端报初始尺寸、进入端报目标尺寸，父布局不逐帧跟随动画 bounds；改成 `AnimatedSize` 会让周边内容跟随，也可能放大逐帧重排范围。边界：`sharedBounds` 同时绘制两棵子树，全屏内容大范围 alpha 混合会推高 GPU fill rate 与带宽；overlay 绘制脱离原父级的 clip/alpha/scale，视觉需要这些效果要显式应用或用 `clipInOverlayDuringTransition`；两端 Modifier 顺序不一致会让起始帧位置或尺寸跳变。

**Q18: 共享元素过渡的 Lookahead 布局是不是"第二次 GPU 渲染"，GraphicsLayer 是不是 Bitmap 缓存，元素数量有平台阈值吗？**

都不是：Lookahead/approach 是布局阶段对节点树的两次遍历——先算动画结束时的尺寸与坐标，再按当前动画 bounds 接近目标，一帧仍按 Compose 布局结果进入一次绘制，把它说成"两次 GPU 绘制"混淆了 CPU 布局与 GPU 绘制；`GraphicsLayer` 在需要 overlay 绘制时按需创建、记录绘制命令并在原位置与 overlay 之间复用，不是把源 Composable 截成 Bitmap 交给目标端，图片内容是否复用由图片库缓存决定，共享 key 不能代替图片内存缓存 key；源码没有"最多五个元素"的限制或推荐值。机制与边界：活跃 overlay entry 才持有 GraphicsLayer，退出 overlay、detach 或 reset 时释放，应用无须手工清理；overlay 是 `SharedTransitionScope` 根 draw pass 内的绘制区域，内容仍进宿主 App Window buffer，SurfaceFlinger 通常只看到宿主 layer，不会多出独立合成层；成本主要来自额外布局遍历、按需图形层与进入/退出内容同时绘制，判断依据是内容复杂度、覆盖面积、resize mode 与设备，不是元素个数。能力边界：SharedTransition 是 Compose Animation 1.7 实验、1.10 稳定的库能力，没有平台版本门槛，但不支持 View/Compose 互操作与跨独立窗口容器，也不能替代 `ActivityOptions.makeSceneTransitionAnimation()` 的平台共享元素；Predictive Back 由 Navigation 或 `PredictiveBackHandler` 把手势进度送入 transition（`enableOnBackInvokedCallback` 开关 AAOS13 已有，系统动画默认启用是 Android 16+ 且 targetSdk 36+ 的行为）。

**Q19: View.setRenderEffect 做模糊时 GPU 成本从哪来，它与窗口背景模糊是同一套机制吗？**

`View.setRenderEffect()` 把效果挂到 View 背后的 RenderNode（AAOS13 已核对：`mRenderNode.setRenderEffect()` 返回 true 时触发属性失效重绘），模糊这类效果要先把内容画进离屏图层、再由 GPU 对该图层处理——成本来自中间纹理的分配与读写、随半径增大的采样范围、内容失效引发的重复处理；窗口模糊是另一套接口，由系统模糊窗口后方的画面，用 `Window.setBackgroundBlurRadius()` 并监听 `WindowManager.addCrossWindowBlurEnabledListener()` 处理运行中被关闭的情况（两个 API AAOS13 均已存在）。机制与边界（AAOS13 `RenderEffect.java` 已核对，API 31+）：RenderEffect 只处理目标 RenderNode 自己的输出，读不到背后兄弟 View 或其他窗口；1080 × 2400 RGBA_8888 的全屏中间纹理约 9.9 MiB（十进制约 10.4 MB），实际受 stride、tile buffer、驱动复用与压缩影响，只表示量级；同窗口内的"毛玻璃背板"要先有明确背景内容或快照，再对它应用效果。做法：适合效果区域可控、内容变化频率低、视觉收益覆盖 GPU 成本的场景；列表项逐帧动态模糊、转场全程改变半径都按低收益处理，转场结束、页面不可见、列表项离开可视区时 `setRenderEffect(null)`（业务对象持有的 RenderEffect 引用要一并清，清 View 属性不会自动释放对象）；`createChainEffect(outer, inner)` 表示 `outer(inner(source))`，链长不等于临时纹理数量，是否变慢要逐项开关对照 trace。

**Q20: RuntimeShader 更新 uniform 后画面不动是什么原因，AGSL 着色器的构造与使用有哪些硬约束？**

`View.setRenderEffect()` 只在 RenderNode 的效果属性变化时请求重绘，同一个 RenderEffect 安装后更新 `RuntimeShader` 的 uniform 不会自动请求下一帧，动画必须显式调用 `postInvalidateOnAnimation()` 或由动画框架驱动重绘。构造约束（AAOS13 `RuntimeShader.java` 已核对，API 33+）：`RuntimeShader(source)` 在构造时编译 AGSL，源码或 uniform 声明不合法抛 `IllegalArgumentException`，不能在动画回调或 Composable 高频路径里构造，也不要页面加载时一次性创建所有着色器——按场景懒创建、按效果实例复用。语言语义：AGSL 在绘制过程中按像素计算颜色，`coord` 是 Canvas 局部像素坐标（原点左上），不是 0 到 1 的归一化纹理坐标；`uniform shader` 经 `RenderEffect.createRuntimeShaderEffect(shader, uniformShaderName)` 绑定目标 RenderNode 内容，每次 `input.eval(coord)` 都执行一次输入求值，多点采样、循环采样与多输入叠加线性增加每像素成本。边界：AGSL 要求 `main()` 返回预乘 alpha 颜色（透明度为 A 时 RGB 先乘 A），输入带透明边缘时新增颜色也要乘 `src.a`，否则透明像素保留非零 RGB、合成后出色边；默认在目标缓冲区的颜色空间计算；API 33 以下用静态效果或不加效果，低端机与省电模式准备关闭动态着色器的降级路径。

**Q21: 按 Android 13 设备开发时，RuntimeColorFilter、RuntimeXfermode、getGpuHeadroom、HardwareBufferRenderer 这些效果相关 API 的可用状态是什么？**

AAOS13（Android 13/API 33）里 `RenderEffect` 与 `RuntimeShader` 已可用（本地源码核对），而 `RuntimeColorFilter`/`RuntimeXfermode`（API 36）、`RuntimeShader.setWorkingColorSpace()`（API 37）、`SystemHealthManager.getGpuHeadroom()`（API 36）、`HardwareBufferRenderer`（API 34）都不存在（本地源码确认缺席）。机制分层：Paint 级效果属于后续版本的表达——`RuntimeColorFilter` 改写当前绘制命令产生的 source color（`Paint.setColorFilter()` 安装），`RuntimeXfermode` 的 `main(src, dst)` 决定新颜色与目标已有像素的合成方式（`Paint.setXfermode()` 安装），作用位置与处理整棵已绘制内容的 RenderEffect 不同；`getGpuHeadroom()` 估算 GPU 还能承受的额外负载（0 到 100，`Float.NaN` 表示暂不可用），是质量调节信号而非逐帧利用率，即便在支持的版本上一次有效调用也含同步 Binder、不应出现在 UI 线程或逐帧回调。降级做法：Paint 级效果用传统 `ColorFilter`/`Xfermode`；颜色空间运算先用默认目标空间实现；GPU 余量用设备档位、温控与帧超期分布的远程开关替代；离屏 RenderNode 生产用 EGL/Vulkan 写 HardwareBuffer 或 AndroidX 封装。版本判断要包住所有调用点，包括清空效果的分支。

**Q22: Compose 的 Canvas 没有独立 Surface，一次 drawRect 从 Kotlin 调用到 GPU 经过了哪几段，哪些绘制对象需要应用自己管理？**

Compose Foundation 1.12.0 的 `Canvas(modifier, onDraw)` 实现就是 `Spacer(modifier.drawBehind(onDraw))`——它是带绘制回调的布局参与者，不创建独立 Surface、BufferQueue 或 SurfaceFlinger layer；标准硬件窗口中 `DrawScope` 把调用转成 Android Canvas 命令，经 framework `RecordingCanvas`/RenderNode 记录，`syncAndDrawFrame()` 后交给 RenderThread，再经 Skia OpenGL 或 Vulkan 管线生成 GPU 工作（AAOS13 的 HWUI 源码锚点与材料一致）。机制：`AndroidComposeView.dispatchDraw()` 用可复用的 `CanvasHolder` 把 framework Canvas 交给 Compose 根，`drawIntoCanvas` 的 `nativeCanvas` 可取回 `android.graphics.Canvas`；`CanvasDrawScope` 延迟创建并复用内部 fill/stroke 两个 Paint（1.12.0 源码口径），`drawRect`/`drawCircle`/`drawPath` 不会每次新建底层 Paint。需要应用自己管的：每帧新建的复杂 Path、渐变 Brush/Shader、文本测量、大点列表与排序，以及 `drawIntoCanvas` 中新建的 `android.graphics.Paint`/Path/Drawable——`remember` 或 `drawWithCache` 二选一按依赖放置。边界：绘制到 `ImageBitmap` 等软件目标时走 CPU 即时栅格路径，即使窗口开着硬件加速，判断后端要看 `canvas.isHardwareAccelerated()`；"Compose Canvas 比 View Canvas 快"缺少前提——相同几何与目标时后半段 GPU 成本接近，差异在录制与失效范围，迁移要用同条件数据证明。

**Q23: drawWithCache 缓存什么、什么时候失效，drawWithContent 里不调用 drawContent 会发生什么？**

`drawWithCache` 把缓存构建与每次绘制分开：构建块可创建 Path、Brush、Shader、`TextLayoutResult`、Stroke 或受管 GraphicsLayer，size、density、layout direction、构建块身份或其读取的 Snapshot 状态变化时缓存失效，返回的绘制块里读取的状态只请求重绘、不重建缓存；`drawWithContent` 把顺序交给调用方——在自定义绘制之前调用 `drawContent()` 则后续绘制覆盖业务内容，之后调用则内容覆盖自定义绘制，不调用则业务内容不会被画出，调用多次则重复绘制。机制（Compose 1.12.0 材料口径）：静态几何与对象放构建块，进度、alpha、指针坐标等高频标量留在绘制块；`Canvas` 的实现即 `drawBehind`（先自定义绘制再画内容），给现有组件加背景时直接用 `drawBehind` 少一层包装。边界：没有可缓存对象时它只增加 lambda 与管理成本，单色 `drawRect` 用 `drawBehind` 即可；"只触发绘制"仍会重录 display list——不断增长的 Path 逐帧重录整条路径，应按 segment 拆分、对过密输入做有界简化或把不变背景放进独立图形层；`BlendMode.Clear`/`DstIn` 等影响目标像素的操作要判断是否需要 `CompositingStrategy.Offscreen`，否则混合可能作用到图形层之外已有的窗口内容。

**Q24: 图表、粒子、手写这类持续绘制的场景什么时候该离开 Compose Canvas 转 SurfaceView，绘制回调的线程边界是什么？**

图表、装饰与有限动画适合 Canvas：大量图元放进一个布局节点，数据排序、聚合与坐标索引放数据层或缓存构建块，只生成当前 viewport 内的点和标签；视频、相机、需要独立生产节奏或专业低延迟手写的场景应评估 SurfaceView/前缓冲路径——Compose Canvas 的绘制块由绘制阶段在 UI 线程调用，粒子状态可以后台算好再提交不可变帧快照，但绘制本身不能迁到后台线程。机制与边界：Canvas 减少的是 Composable 节点数，draw op 数量、Path 复杂度与文字栅格成本仍在；每帧在 CPU Bitmap 上改像素再画进硬件窗口会产生纹理上传与同步，动态内容优先直接录制矢量命令；`Bitmap.Config.HARDWARE`（API 26+）是不可变只读绘制源，可包装为 `ImageBitmap` 画到硬件 Canvas，但不能作为 `Canvas(bitmap)` 的可写目标、不能画入软件 Canvas，需要读回或软件滤镜时用软件分配或显式复制。专业手写的低延迟路径 `CanvasFrontBufferedRenderer`（androidx.graphics，外部库）基于 SurfaceView 与前缓冲、在内部渲染线程回调，引入独立 Surface、事务与画面撕裂权衡，不是普通 Canvas 的透明替换。收尾两条：Canvas 中的线、点、柱不会自动成为语义节点，表达业务信息或响应点击要补 `contentDescription`/`semantics`，画得更快但读屏拿不到数据不算完成；同帧重复绘制的过度绘制先确认 GPU 时长确有下降空间再优化，优化前后用同一脚本与帧指标对照。
