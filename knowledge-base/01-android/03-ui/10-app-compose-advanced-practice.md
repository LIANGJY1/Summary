# 渲染实战：Compose 进阶

> 学习资料（文章模式沉淀）。主线：应用侧渲染优化的进阶层——Snapshot 的版本视图与并发边界决定状态正确性，View/Compose 互操作的成本在桥接、销毁策略与复用协议，动画与共享元素的性能由读取阶段、退出保留与布局遍历决定，Runtime 图形效果与 Compose Canvas 的成本在离屏边界与逐像素工作量。源文档：android-internals-wiki §22.5《Compose Snapshot、状态一致性与并发》§22.6《Compose First 与 View/Compose 互操作性能实战》§22.7《View、Compose 动画与共享元素性能》§22.8《Runtime 图形效果与 Compose Canvas》（材料按 Android 17 撰写）。RenderEffect（API 31）与 RuntimeShader（API 33）在 AAOS 13（Android 13）已存在并按本地源码核对，RuntimeColorFilter/RuntimeXfermode（API 36）、setWorkingColorSpace（API 37）、getGpuHeadroom（API 36）、HardwareBufferRenderer（API 34）与 Choreographer 缓冲区积压恢复为 AAOS 13 之后的新增、enableOnBackInvokedCallback（API 33）已按本地核对。Compose Runtime/UI/Animation 1.12.0 与 androidx.graphics 等 AndroidX 外部库按题内版本材料描述，未本地源码核对处已弱化。2026-10-04 复核了 Android 官方 Modifier.Node、Shared Transition、Strong Skipping 和 Compose 发布文档。其他 AndroidX 内部实现仍按题内固定版本描述，未本地源码核对的外部库实践不外推。渲染调度与硬件层机制层见 [../05-rendering/01-render-pipeline-vsync.md](../05-rendering/01-render-pipeline-vsync.md)，出图分型与 SurfaceView/EGL/Vulkan 机制层见 [../05-rendering/04-graphics-api.md](../05-rendering/04-graphics-api.md)，View 布局、列表与 Compose 重组测量基础见 [08-rendering-view-compose.md](09-app-view-compose-practice.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 在后台线程读取 mutableStateOf 时读到的可能是哪个版本，Snapshot 按什么条件从状态记录链里选值？**

后台线程在某个 Snapshot 中读取 StateObject 时，会按该 Snapshot 的版本视图选择一条 StateRecord。它读到的不是“当前最新值”的无条件保证。

1. **可见条件**：记录的 Snapshot ID 不是 `INVALID_SNAPSHOT`，不大于当前 Snapshot ID，并且不属于当前 Snapshot 的 `invalid` 集合。
2. **选择规则**：从可见记录中选 Snapshot ID 最大的一条。
3. **线程范围**：Compose Runtime 1.12.0 中 `Snapshot.current` 是线程局部状态。`snapshot.enter { }` 只在当前线程临时替换它，不会更改其他线程的当前 Snapshot。
4. **快照时点**：快照创建后才产生的记录不会进入其视图。创建快照时尚未 apply 的其他 Snapshot 会进入 `invalid` 集合。
5. **并发边界**：读取通常无锁遍历记录链，但全局 Snapshot 刚推进时快路径可能失败，Runtime 会在 `sync` 临界区按新的 `Snapshot.current` 重试。因此“State 读取永远无锁”不成立。可见性只决定读哪个版本，不会让 `state.value++` 这类复合操作原子化。

**Q2: takeSnapshot() 创建的只读视图为什么必须显式 dispose，不 dispose 会怎样？**

`takeSnapshot()` 创建的只读视图必须由调用方 `dispose()`。否则它会继续固定仍可能读取的旧记录，增加记录链长度、遍历和内存成本。GC 不能替代显式结束。

1. **普通只读快照**：用 `try/finally` 保证 `takeSnapshot()` 的结果最终 dispose。
2. **可变快照**：`takeMutableSnapshot()` 在 apply 或放弃后仍须 dispose。未 apply 且被明确结束的 MutableSnapshot 才走内部 `abandon()` 路径，将自己的记录标成 `INVALID_SNAPSHOT` 供后续写入复用。
3. **嵌套快照**：每层快照分别结束和释放，不能只处理最外层。
4. **协程上下文**：`asContextElement()` 只在协程恢复时进入或离开 Snapshot，不负责 dispose。
5. **只读边界**：在只读 Snapshot 中写 State 会抛 `IllegalStateException`。`dispose()` 不修改全局状态，只解除该视图对旧记录的保留。Compose Runtime 1.12.0 实现中不存在应用可依赖的 `AbandonedSnapshot` 类型。

**Q3: 两个并发 MutableSnapshot 修改同一个 StateObject，apply 冲突按什么规则合并，内置变更策略有什么区别？**

并发 `MutableSnapshot` 应用修改时，Runtime 用同一 StateObject 的 previous、current、applied 三条记录尝试合并。能生成合并记录就继续 apply，返回 null 则报告冲突。

1. **记录含义**：`previous` 是待应用 Snapshot 开始修改前读取的记录，`current` 是父或全局状态当前记录，`applied` 是待应用 Snapshot 写出的记录。
2. **策略委托**：`MutableState` 将合并委托给 `SnapshotMutationPolicy.merge()`。`structuralEqualityPolicy()` 用 `==` 判等，`referentialEqualityPolicy()` 用 `===` 判等，`neverEqualPolicy()` 永不判等。
3. **默认冲突**：结构相等和引用相等策略都可在两支没有产生不同有效更新时选择可用版本。若双方把同一字段改成彼此不等且都不同于 previous 的值，就不能自动合并。`neverEqualPolicy()` 更容易将并发赋值视为冲突，只适用于每次赋值都必须算变化的语义。
4. **领域合并**：计数器可自定义 `current + (applied - previous)` 合并增量。合并函数必须是纯函数、结果确定、执行快速且没有副作用。
5. **复杂度边界**：apply 从已修改对象集合出发，不扫描进程中全部 StateObject。但仍可能遍历单个对象的记录链、调用合并函数并通知观察器，不能简化成严格 O(changed states)。相等只解决无效写和冲突判定，不定义增量相加、集合并集或字段级合并。

**Q4: 两个 Snapshot 各改不同 State 都 apply 成功，跨对象业务约束仍可能被破坏——这是为什么，怎么防？**

Snapshot apply 只检查写入记录冲突，不提供可串行化保证。两个 Snapshot 修改不同对象仍可能破坏跨对象不变量，这种现象叫写偏差（write skew）。

1. **形成条件**：两个快照基于同一组旧状态读取，各自修改不同 StateObject。因为修改集合没有交集，记录级冲突检查都可能通过。
2. **业务结果**：例如两名值班者同时判断“至少一人在线”，各自下线不同的人，两个更新应用后却无人在线。
3. **原子可见性**：`Snapshot.withMutableSnapshot { }` 封装 `takeMutableSnapshot()`、`enter`、`apply().check()` 和 `dispose()`。代码块成功返回才尝试 apply，异常不会 apply，冲突抛 `SnapshotApplyConflictException`。成功后这批修改一起可见，但它不会保证一次重组或一帧，也不会修复写偏差。
4. **不变量保护**：把共同变化字段放进一份不可变状态，或使用 `Mutex`、Actor、`StateFlow` 原子更新或数据库事务。多字段共同变化时，单一不可变 `UiState` 通常比多个 State 更直接。
5. **副作用边界**：代码块不能挂起，也不应执行外部副作用。该 API 不自动重试，调用方若因冲突重跑，代码块必须允许重复计算。I/O、Binder 和文件写入不会随 Snapshot 回滚。

**Q5: mutableStateOf 可以从工作线程读写吗，state.value++ 为什么仍可能丢更新，enter 为什么不能跨协程挂起点保留？**

全局 Snapshot State 可跨线程读写，但这只保证记录访问可用，不保证 `state.value++` 这样的读改写原子化。`Snapshot.enter` 也不能跨协程挂起点手工保持。

1. **丢更新原因**：`state.value++` 包含读取、计算、写入三步。多个生产者可能读到同一旧值再写入，彼此覆盖。用 `MutableStateFlow.update`、`Mutex`、Actor 或数据库事务为复合操作提供原子性与排序。
2. **enter 线程范围**：`Snapshot.enter` 替换当前线程的 Snapshot。协程挂起后可能在另一线程恢复，因此手工跨挂起点保留 enter 区间会破坏进入/退出配对。
3. **协程传递**：单个协程需要在恢复后进入同一只读视图时用 `asContextElement()`。它不负责 dispose，也不允许多个协程并行修改同一个 MutableSnapshot。该对象按多读者、单写者使用，并发多写属于未定义行为。
4. **UI 线程边界**：State 可跨线程不代表 View、ComposeView、Canvas 或依赖 Looper 的回调能跨线程操作。`SnapshotStateObserver` 对同一 Owner 的测量、布局、绘制观察也要求在同一线程。
5. **发布时机**：未 apply Snapshot 中创建的 State 若提前泄露，较早 Snapshot 可能读不到合法记录并报错。长期状态容器应在全局上下文创建，事务内初始化的引用要等 apply 成功后再发布。

**Q6: 一次 state.value = x 写入后重组是怎么被安排的，为什么多次写入可能合并成一次重组？**

一次 State 写入不会对应一次重组。Snapshot 先记录有效变化，再由观察关系标记受影响的 Composition，重组循环按帧时钟调度并可合并失效。

1. **写入记录**：变更策略先判断新值是否等价。等价则提前结束，否则修改状态记录，并将 StateObject 加入当前 Snapshot 的修改集合。
2. **观察关系**：Compose Runtime 1.12.0 在组合时用 `recordReadOf` 登记“值 → RecomposeScope”，编译器生成的 `ScopeUpdateScope` 支持重启函数。布局和绘制阶段则由 `SnapshotStateObserver.observeReads()` 建立各自观察关系。
3. **失效阶段**：状态读取发生在哪一阶段，失效就可局限在哪一阶段。失效对象集合只有对象身份，没有业务语义，也不等于待执行的重组函数列表。
4. **合并时机**：帧回调开始前的多次写入可以合并。一次回调处理中也可能继续处理新失效，因此既不是每次写入重组一次，也不是每帧恰好重组一次。
5. **观测边界**：`SnapshotStateObserver` 不是通用线程安全对象，内部原子队列不表示可由多个布局线程共享。Perfetto 默认没有逐 State 轨道。`Compose:applyObservers` 只覆盖观察器调用阶段，不能代表完整 apply 耗时。

**Q7: AndroidView 把一个 View 接进 Compose 后由谁测量谁，Compose 状态变化什么时候会让这个 View 重新布局或重绘？**

`AndroidView` 创建 `ViewFactoryHolder`（父类是 `AndroidViewHolder`），将传统 View 与 Compose 的 LayoutNode 测量、放置和失效流程桥接起来。View 本身仍使用原有的测量、布局、绘制和事件模型。

1. **测量与放置**：Compose 将 `Constraints` 和 View 的 `LayoutParams` 合成为 MeasureSpec 并调用 `View.measure()`，再用 `measuredWidth/Height` 确定 LayoutNode 尺寸。放置阶段调用 `View.layout()`。
2. **模型变化**：`update` 中读取的 Snapshot 状态变化会通过承载器观察器再次执行 `update`。只读取 `model.title` 时，`model.image` 变化不会自动触发该更新。
3. **尺寸失效**：View 调用 `requestLayout()` 后，承载器找到对应 LayoutNode 请求重新测量，尺寸变化后再放置受影响节点。
4. **绘制失效**：View 调用 `invalidate()` 后最终使 LayoutNode 图层失效。View 报告的局部脏区不会原样传给 Compose 图层。绘制过程中产生的失效会通过 `postOnAnimation` 延到下一帧。
5. **性能分析**：分别统计 `update`、View `requestLayout()`、View `invalidate()` 和帧数，不能用重组计数替代这些桥接指标。
6. **滚动边界**：`AndroidViewHolder` 实现 `NestedScrollingParent3`，可传递普通嵌套滚动增量。相同方向的无限嵌套，以及 `requestDisallowInterceptTouchEvent` 切换手势控制方时，需要专项验证。

**Q8: AndroidView 的 factory/update/onReset/onRelease 各自的调用时机与职责是什么，怎样避免 View 与 Compose 状态互相回写的反馈循环？**

`factory` 创建 View，`update` 把当前模型同步到 View，`onReset` 清理将复用的瞬时状态，`onRelease` 释放永久离开的实例。避免反馈循环的关键是明确唯一状态所有者，并让两个方向的同步具备来源区分和等值保护。

1. **factory**：每个 View 实例调用一次，在 UI 线程创建控件、设置一次性属性并注册长期监听器。
2. **update**：factory 后至少调用一次，读取的 State 变化时再次运行。它应幂等地把当前模型写入 View，不执行重操作。
3. **用户事件**：Compose 参数作为唯一状态源，View 监听器只上报用户操作，可用 `fromUser` 区分来源。更新 View 属性前比较现值，避免重复赋值。不要在 `update` 中无条件回写 Compose 状态。
4. **清理与释放**：复用时由 `onReset` 清理瞬时状态，实例永久离开时由 `onRelease` 终止并释放资源。
5. **可变模型**：模型原地变化时，引用比较无法识别内容变化。改用不可变 UI 模型或版本号。把 View 预先放入 `remember` 不能绕过 factory 成本，还会破坏宿主、附着和复用语义。
6. **AndroidViewBinding**：沿用相同桥接与复用语义，只减少 `findViewById` 与类型转换，不会省掉 XML 加载和 View 测量、布局、绘制。

**Q9: Lazy 列表里的 AndroidView 为什么必须提供非空 onReset 才能复用实例，onReset 之后 View 处于什么状态？**

Lazy 列表中的 `AndroidView` 只有在使用带非空 `onReset` 的重载时才参与 View 实例复用。`onReset` 后 View 可能进入 deactivated 状态，不保证马上执行 `update`，所以重置后必须隐藏旧内容。

1. **兼容分组**：两个 AndroidView 能否互换取决于它们在 Compose 中的分组结构，不只取决于业务 key。
2. **回调顺序**：`onReset` 后可能直接执行 `update`，也可能先停用再释放。清理逻辑应允许重复调用。复用与释放是不同阶段。
3. **列表项状态**：清理旧监听器、标签、选择/按压/激活态和无障碍文案。
4. **进行中任务**：取消动画、延迟任务、图片请求、协程和播放器任务。
5. **内嵌组件**：按组件 API 清理 RecyclerView、WebView、地图或视频控件自身的滚动与资源状态。
6. **外部订阅**：解除与旧 `LifecycleOwner`、`SavedStateRegistryOwner` 或业务宿主绑定的回调。
7. **资源型 View**：若组件明确支持，可在 `onReset` 停掉条目会话、在 `onRelease` 销毁可复用引擎。若 `onReset + update` 比新建更贵，应调整复用边界，并以 factory 调用数下降、绑定耗时稳定证明复用有效。

**Q10: ComposeView 放进 RecyclerView 与放进 Fragment 各该用什么 ViewCompositionStrategy，为什么不要在 onViewRecycled 里 disposeComposition()？**

RecyclerView 中的 `ComposeView` 应依赖池化容器感知的默认策略，Fragment 中则应让 Composition 跟随 Fragment 的 View 树生命周期销毁。

1. **RecyclerView 默认策略**：`DisposeOnDetachedFromWindowOrReleasedFromPool` 在普通 View 树分离时销毁 Composition。在 RecyclerView 等池化容器短暂分离时保留，直到 item 被池永久释放或容器随窗口分离。
2. **Fragment 策略**：Fragment 实例可能继续存在而 View 树已销毁，应设 `DisposeOnViewTreeLifecycleDestroyed`，并在下一次 attach 时沿 View 树 `LifecycleOwner` 的 `ON_DESTROY` 销毁。
3. **绑定方式**：ViewHolder 初始化时调用一次 `setContent`。数据绑定更新 ViewHolder 持有的 `mutableStateOf`，不要每次绑定重建 Composition。
4. **避免主动销毁**：在 `onViewRecycled()` 调 `disposeComposition()` 会丢掉对象池保留 Composition 的收益，下次复用时整棵 Composition 重建。
5. **状态恢复**：列表项含 `remember` 状态时提供稳定 key、将状态提升到列表模型或明确设计保存恢复。多个静态 ComposeView 需要唯一 View ID 才能恢复 `savedInstanceState`。
6. **宿主条件**：自定义宿主或手工窗口需确认 `ViewTreeLifecycleOwner` 与 `SavedStateRegistryOwner` 已设置。

**Q11: 同一窗口里多个 ComposeView 是共享一个 Recomposer 还是各有一个，"每个 ComposeView 都很轻"为什么不成立？**

同一窗口的多个 `ComposeView` 通常解析到同一个 Recomposer，但每个实例仍有自己的 Composition、槽位表、AndroidComposeView、LayoutNode 树、语义管理、状态注册和测量边界，因此不能视为“多个 View 都很轻”。

1. **解析顺序**：通常依次查找显式父级 `CompositionContext`、View 树中可用的 CompositionContext、有效缓存和窗口 Recomposer。
2. **共享上下文**：Compose UI 1.12.0 的 `ComposeViewContext` 可在兼容宿主边界内共享配置缓存、字体加载器、无障碍等宿主对象。共享范围受 Context 与 `LifecycleOwner` 约束，不会合并业务状态。
3. **预组合**：可从已附着 View 构造上下文，并为未附着 ComposeView 调 `createComposition(context)` 做预组合。预热后始终未附着的实例要由调用方 `disposeComposition()`。
4. **成本评估**：记录 Compose 根数量、堆内存和销毁时机。十个小根与一个十节点根没有跨项目通用的字节数结论，应在目标版本实测。

**Q12: Compose 外层动画请求高帧率后，内嵌 AndroidView 里的视频为什么不会跟着提帧，混合树里帧率请求按什么边界传播？**

帧率偏好不会自动沿 ViewGroup 或 Compose 互操作边界传给内部 View。独立 Surface 还可能自行表达帧率，最终显示模式由系统结合可见内容请求、面板能力与功耗策略决定。

1. **传播边界**：ViewGroup 的请求不会自动传播给子 View，Compose 的帧率偏好也不会自动设置 `AndroidView` 内部 View 的 `requestedFrameRate`。
2. **版本能力**：ARR 自 Android 15 QPR1 起提供。Android 17 可用 `Display.hasArrSupport()` 查询，Compose 1.9 起提供 `Modifier.preferredFrameRate`。AAOS 13 没有 ARR，只支持多刷新率模式切换，低版本调用要有降级。
3. **视频节奏**：宿主每帧重组不会让视频产生新缓冲区。SurfaceView 视频更新也不要求宿主窗口同步重画，两种内容节奏可不同。
4. **分析范围**：区分普通 View 承载与独立 Surface 承载的性能模型。后者属于出图分型机制，互操作侧应先确认其独立生产节奏。

**Q13: View 属性动画里每个 tick 用 updateLayoutParams 改宽度为什么会持续掉帧，scaleX 这类变换属性能替代到什么程度？**

`updateLayoutParams()` 每帧触发 `requestLayout()`，动画持续进入测量和布局。`scaleX` 等 transform 只改变绘制变换，不能替代布局尺寸、命中区域或无障碍边界变化。

1. **属性副作用**：`updateLayoutParams()` 设置新参数并触发布局请求。`ViewPropertyAnimator` 的 AAOS 13 实现由 UI 线程的 `ValueAnimator` 更新属性并合并失效请求，不会把回调移到 RenderThread。
2. **transform 限制**：scale、translation、rotation 和 alpha 不改变 measured width、布局边界、触摸命中区域或无障碍边界。只有产品允许布局占位与命中区域保持目标尺寸时，才可用 `scaleX` 表达视觉展开。
3. **动画属性设置**：`ObjectAnimator` 传入 `Property` 时直接调用 `Property.set()`。传入属性名时会解析并缓存 getter/setter。因此“每帧都用反射”不准确，性能需检查 setter 的实际副作用。
4. **交互一致性**：若周围内容必须随宽度移动，或收起后内容不可点击、无障碍边界也要同步，必须更新布局与交互状态，并用 trace 确认重排范围。
5. **图层和图片成本**：AAOS 13 的 `withLayer()` 会在动画期间临时切硬件层。内容不变的短时 alpha/transform 可能获益，动画同时改内容并 invalidate 时仍需重栅格化。逐帧图片按解码后像素估算，1920 × 1080 RGBA_8888 约 7.9 MiB，30 张约 237 MiB。density 缩放、复用与硬件位图会改变实际驻留。

**Q14: Compose 里 Animatable、Transition 与 animate*AsState 的动画值逐帧更新时，重组范围由什么决定，主线程卡一下动画会怎样？**

动画值在何处读取，失效就从何处开始。同一个值在多个阶段读取会建立多处观察。API 名称本身不决定每帧是否重组。

1. **Composition 读取**：在 Composable 函数体读取 `Animatable.value`、`Transition.animate*` 或 `animate*AsState` 会随帧触发相应重组。
2. **后置阶段读取**：在 `offset` lambda、绘制回调或 `graphicsLayer` 回调读取，可把失效限制到放置、绘制或图层更新阶段。
3. **帧时钟**：Compose UI 1.12.0 的 `AndroidUiFrameClock.withFrameNanos()` 注册 `Choreographer.FrameCallback`。主线程阻塞会推迟回调。下次执行看到较大的时间差，动画向前跳，Compose 不补画错过的中间帧。
4. **降低主线程工作**：透明度、位移、缩放等绘制属性在 `graphicsLayer { }` 或绘制回调中读取。动画 alpha 可能需要中间合成，因此主线程变轻不代表 GPU 负载也变轻。
5. **帧证据**：同时看 UI 线程、RenderThread 与 FrameTimeline。帧 deadline 应按设备和刷新率读取，不能固定为 16.6、11.1 或 8.3 毫秒。`Choreographer#doFrame` 只覆盖主线程回调，不含 GPU 完成和送显。

**Q15: 同一个 Animatable 被手势高频调用 snapTo 与 animateTo 有什么行为差异，Transition 在 1.12.0 的组合语义是什么？**

`Animatable` 适合协程控制的单值动画，`Transition` 协调一个离散状态下的多项动画，`animate*AsState` 适合单值自动过渡。手势频繁重定向时，要根据“跟手”还是“延续速度”选择操作。

1. **互斥与取消**：`Animatable` 通过 `MutatorMutex` 保证一个变更任务运行。新的 `animateTo`、`animateDecay`、`snapTo` 或 `stop` 会取消已有变更，取消以 `CancellationException` 沿协程父子关系传播，需释放的资源放在 `finally`。
2. **手势跟随**：`animateTo` 从当前值继续，弹簧可延续当前速度。每个触摸采样点都调用它会连续取消并追赶。拖动跟手用 `snapTo`，松手后用 `animateDecay` 或 `animateTo` 收尾。
3. **Transition 状态**：`updateTransition(targetState)` 是可组合函数，内部用 `remember` 保存 Transition，并以 `DisposableEffect` 清理。参数或被观察 State 变化仍会重组相关作用域，不提供“组合永不重启”的保证。
4. **中断语义**：目标变化会更新迁移段，不同动画参数处理连续性的方式不同，不能一概说每次切换都从头重启。按控制语义决定是否允许每个输入重定向，或由业务状态屏蔽输入。
5. **版本选型**：Compose Animation 1.12.0 已弃用接收 `MutableTransitionState` 的 `updateTransition` 重载，应使用 `rememberTransition(transitionState)`。`DeferredTransitionState` 与 `mutableTransform` 面向预测性返回，不是通用性能开关。

**Q16: AnimatedVisibility 退出动画期间旧内容还在组合里吗，AnimatedContent 快速切换为什么会同时保留多份内容？**

`AnimatedVisibility` 和 `AnimatedContent` 会在退出动画完成前保留离场内容。快速切换可能同时持有多个仍在退出的子树，其 Effect、图片和订阅也继续存活。

1. **保留时机**：`AnimatedVisibility` 等待内建进入/退出动画和注册到 `AnimatedVisibilityScope.transition` 的自定义动画全部完成后才移除内容。期间 `LaunchedEffect` 未取消，`DisposableEffect.onDispose` 未运行。
2. **多份内容**：`AnimatedContent` 让新内容进入、旧内容退出，旧子树完成退出后才从组合移除。连续快速切换可同时保留多份未完成退出的内容。
3. **布局成本**：默认 `SizeTransform` 会动画容器尺寸，父布局与同级可能反复测量。默认展开/收缩影响容器报告尺寸，淡入淡出与缩放主要更新图层属性，滑动只改放置偏移而不缩小报告尺寸。
4. **动画同步**：独立 `animate*AsState` 不属于 scope 的 transition，容器不知道其结束时机，可能与退出不同步。需要同步的自定义动画应注册到作用域 transition。
5. **成本控制**：父布局不需要跟随伸缩时给外层稳定约束，内部用淡入淡出或滑动。内容较重时上移昂贵订阅，或退出后停止数据更新。缩短时长只缩短资源保留窗口，没有适用于所有界面的固定时长。
6. **内容身份**：`contentKey` 定义内容身份。两个目标映射到同一个 key 时不触发切换动画，可过滤“状态对象变、视觉身份不变”的更新。

**Q17: 共享元素过渡里 sharedElement 与 sharedBounds 怎么选，scaleToBounds 与 RemeasureToBounds 的成本差在哪？**

视觉内容相同且应只绘制目标端内容时选 `sharedElement`。容器连续但内容不同、允许进出内容短暂共存时选 `sharedBounds`。选择 `scaleToBounds` 或 `RemeasureToBounds`，决定过渡期间是缩放既有布局还是逐帧重测布局。

1. **sharedElement**：适合跨页主图或相同图标。过渡中绘制进入端内容，子节点按动画 bounds 的约束重测和重排。
2. **sharedBounds**：适合卡片到详情等容器连续、内容变化的场景。进入与退出内容默认以 fadeIn/fadeOut 共存。
3. **resizeMode**：默认 `scaleToBounds()` 用 Lookahead 得到的稳定目标布局做图形缩放。`Text` 宽度变化会重新换行，官方建议优先用缩放避免 reflow。宽高比变化的图片若需持续改变裁剪，可实测 `RemeasureToBounds()` 的测量布局成本。
4. **身份匹配**：`SharedTransitionScope` 按 key 和 `AnimatedVisibilityScope` 可见性匹配两端。两端应从同一业务 ID 派生 key，例如 `article-image:42`，并用 `rememberSharedContentState(key)` 保持状态。
5. **父布局占位**：placeholder 默认 `ContentSize`，退出端报告初始尺寸、进入端报告目标尺寸，父布局不随动画 bounds 每帧变化。切为 `AnimatedSize` 会让周边内容跟随，并可能增加重排。

**Q18: 共享元素过渡的 Lookahead 布局是不是"第二次 GPU 渲染"，GraphicsLayer 是不是 Bitmap 缓存，元素数量有平台阈值吗？**

Lookahead/approach 是 Compose 布局阶段的节点树遍历，不是第二次 GPU 绘制。`GraphicsLayer` 记录绘制命令，也不是把源 Composable 截成 Bitmap。源码没有共享元素数量上限或“最多五个”的建议。

1. **两阶段布局**：先计算动画结束时的尺寸与坐标，再按当前动画 bounds 接近目标。该布局结果一帧仍进入绘制阶段一次，不能把两次布局遍历说成两次 GPU 绘制。
2. **图层用途**：需要 overlay 绘制时按需创建 GraphicsLayer，在原位置和 overlay 间复用。图片内容复用由图片库缓存决定，共享 key 不能代替图片缓存 key。
3. **生命周期**：活跃 overlay entry 持有图层。退出 overlay、detach 或 reset 时由运行时释放，不需要应用手动清理。
4. **SurfaceFlinger 边界**：overlay 在 `SharedTransitionScope` 根 draw pass 内绘制，仍进入宿主 App Window buffer。SurfaceFlinger 通常只看到宿主 layer，不会多出独立合成层。
5. **实际成本**：看额外布局遍历、按需图形层以及进入/退出内容同时绘制的复杂度、覆盖面积、resize mode 和目标设备，不看元素数量。
6. **版本与宿主**：SharedTransition 在 Compose Animation 1.7 为实验 API、1.10 起稳定，属于 Compose 库能力，没有最低 Android 平台版本，但不支持 View/Compose 互操作或跨独立窗口容器，也不替代 `ActivityOptions.makeSceneTransitionAnimation()`。
7. **overlay 效果**：overlay 绘制会脱离原父级的 clip、alpha 和 scale。需显式应用这些效果或使用 `clipInOverlayDuringTransition`。
8. **返回手势**：Navigation 或 `PredictiveBackHandler` 可把预测性返回进度送入 transition。`enableOnBackInvokedCallback` 在 AAOS 13 已有。系统动画默认启用是 Android 16+ 且 targetSdk 36+ 的行为。

**Q19: View.setRenderEffect 做模糊时 GPU 成本从哪来，它与窗口背景模糊是同一套机制吗？**

`View.setRenderEffect()` 只处理目标 View/RenderNode 自己的输出。窗口背景模糊则由系统模糊窗口后方画面，两者不是同一接口或同一内容范围。

1. **RenderEffect 成本**：AAOS 13 中效果安装到 RenderNode。模糊通常要先将内容绘制到离屏图层，再由 GPU 处理，中间纹理分配与读写、随半径增长的采样范围和内容失效后的重复处理都会产生成本。
2. **窗口模糊接口**：用 `Window.setBackgroundBlurRadius()` 模糊窗口后方内容，并监听 `WindowManager.addCrossWindowBlurEnabledListener()` 处理运行时被关闭。AAOS 13 两个 API 均存在。
3. **作用范围**：API 31+ 的 RenderEffect 只处理目标 RenderNode 输出，不能读取背后兄弟 View 或其他窗口。同窗口毛玻璃需要先准备背景内容或快照。
4. **纹理量级**：1080 × 2400 RGBA_8888 全屏中间纹理约 9.9 MiB，即约 10.4 MB。实际占用受 stride、tile buffer、驱动复用和压缩影响，该数值只表示量级。
5. **使用与释放**：效果适合区域可控、内容变化频率低且视觉收益覆盖 GPU 成本的场景。列表逐帧动态模糊或转场全程改半径通常收益低。转场结束、页面不可见或列表项离开可视区时调用 `setRenderEffect(null)`，并清掉业务对象持有的 RenderEffect 引用。清 View 属性不会自动释放应用持有的引用。
6. **效果链**：`createChainEffect(outer, inner)` 表示 `outer(inner(source))`。链长不等于临时纹理数量，需逐项开关对照 trace。

**Q20: RuntimeShader 更新 uniform 后画面不动是什么原因，AGSL 着色器的构造与使用有哪些硬约束？**

安装 RenderEffect 后只更新 RuntimeShader uniform 不会自动请求下一帧。动画需显式触发 `postInvalidateOnAnimation()` 或由动画框架驱动重绘。

1. **构造时机**：AAOS 13 / API 33+ 的 `RuntimeShader(source)` 在构造时编译 AGSL。源码或 uniform 声明不合法会抛 `IllegalArgumentException`，不要在动画回调或高频 Composable 路径构造。
2. **实例管理**：按效果场景懒创建并复用 Shader 实例，不要在页面加载时一次性创建所有着色器。
3. **坐标与采样**：AGSL 的 `coord` 是 Canvas 局部像素坐标，原点左上，不是 0 到 1 的归一化纹理坐标。`uniform shader` 经 `RenderEffect.createRuntimeShaderEffect(shader, uniformShaderName)` 绑定目标 RenderNode。每次 `input.eval(coord)` 都对输入求值，多点、循环或多输入采样会线性增加每像素工作量。
4. **颜色语义**：`main()` 返回预乘 alpha 颜色。透明度为 A 时 RGB 需先乘 A。输入有透明边缘时新增颜色也需乘 `src.a`，否则透明像素非零 RGB 会在合成后形成色边。
5. **版本降级**：默认在目标缓冲区颜色空间计算。API 33 以下使用静态效果或不加效果，并为低端机和省电模式准备关闭动态着色器的路径。

**Q21: 按 Android 13 设备开发时，RuntimeColorFilter、RuntimeXfermode、getGpuHeadroom、HardwareBufferRenderer 这些效果相关 API 的可用状态是什么？**

AAOS 13 / API 33 有 `RenderEffect` 与 `RuntimeShader`。题目中的 RuntimeColorFilter、RuntimeXfermode、GPU headroom 与 HardwareBufferRenderer 均晚于该版本，不能直接调用。

1. **较新版本 API**：`RuntimeColorFilter` 与 `RuntimeXfermode` 为 API 36，`RuntimeShader.setWorkingColorSpace()` 为 API 37，`SystemHealthManager.getGpuHeadroom()` 为 API 36，`HardwareBufferRenderer` 为 API 34。
2. **效果语义**：`RuntimeColorFilter` 改写当前绘制命令的 source color，可由 `Paint.setColorFilter()` 安装。`RuntimeXfermode.main(src, dst)` 决定新颜色与目标已有像素的混合，可由 `Paint.setXfermode()` 安装。Paint 级效果与处理已绘制 RenderNode 的 RenderEffect 作用位置不同。
3. **GPU 余量信号**：`getGpuHeadroom()` 估算可承受的额外负载，返回 0–100，`Float.NaN` 表示暂不可用。它是质量调节信号，不是逐帧 GPU 利用率。支持的版本上一次有效调用也含同步 Binder，不应放在 UI 线程或逐帧回调。
4. **降级方案**：Paint 级效果用传统 `ColorFilter`/`Xfermode`，颜色空间运算先按默认目标空间实现，GPU 余量用设备档位、温控和帧超期分布驱动远程开关，离屏 RenderNode 生产可评估 EGL/Vulkan 写 HardwareBuffer 或 AndroidX 封装。
5. **版本保护**：所有高版本调用点都需版本保护，包括清空效果的分支。

**Q22: Compose 的 Canvas 没有独立 Surface，一次 drawRect 从 Kotlin 调用到 GPU 经过了哪几段，哪些绘制对象需要应用自己管理？**

Compose Foundation 1.12.0 的 `Canvas` 是带绘制回调的布局参与者，不创建独立 Surface、BufferQueue 或 SurfaceFlinger layer。标准硬件窗口中一次绘制会经 Android Canvas、RenderNode 与 HWUI 管线提交给 GPU。

1. **Compose 实现**：`Canvas(modifier, onDraw)` 实现为 `Spacer(modifier.drawBehind(onDraw))`。
2. **硬件窗口路径**：`DrawScope` 将调用转为 Android Canvas 命令，经 framework `RecordingCanvas`/RenderNode 记录。`syncAndDrawFrame()` 后交给 RenderThread，再由 Skia OpenGL 或 Vulkan 管线生成 GPU 工作。AAOS 13 的 HWUI 源码路径与材料描述一致。
3. **复用对象**：`AndroidComposeView.dispatchDraw()` 用可复用 `CanvasHolder` 把 framework Canvas 交给 Compose 根。`drawIntoCanvas` 的 `nativeCanvas` 可取回 `android.graphics.Canvas`。`CanvasDrawScope` 延迟创建并复用 fill/stroke Paint，因此 `drawRect`、`drawCircle` 和 `drawPath` 不会每次都新建底层 Paint。
4. **应用管理对象**：每帧新建的复杂 Path、渐变 Brush/Shader、文本测量、大点列表与排序，以及 `drawIntoCanvas` 中新建的 Android Paint、Path、Drawable，需放入 `remember` 或按依赖使用 `drawWithCache`。
5. **软件目标边界**：画到 ImageBitmap 等软件目标时走 CPU 即时栅格路径。即使窗口开启硬件加速，也应通过 `canvas.isHardwareAccelerated()` 判断实际目标。
6. **性能比较**：“Compose Canvas 比 View Canvas 快”缺少前提。同几何和目标下后半段 GPU 成本接近，差异主要在录制和失效范围。迁移需用相同条件数据证明。

**Q23: drawWithCache 缓存什么、什么时候失效，drawWithContent 里不调用 drawContent 会发生什么？**

`drawWithCache` 将缓存对象构建与每次绘制分开，`drawWithContent` 则由调用方决定自定义绘制与内容的顺序。无可缓存对象时使用它会增加管理成本。

1. **缓存内容**：构建块可创建 Path、Brush、Shader、`TextLayoutResult`、Stroke 或受管 GraphicsLayer。
2. **失效条件**：size、density、layout direction、构建块身份或其读取的 Snapshot 状态变化时缓存失效。返回的绘制块里读取状态只请求重绘，不重建缓存。
3. **内容顺序**：`drawContent()` 前绘制自定义内容会让业务内容覆盖它，之后绘制会覆盖业务内容。不调用则业务内容不会被绘制，调用多次则重复绘制。
4. **选型**：静态几何和对象放构建块，高频进度、alpha、指针坐标留在绘制块。`Canvas` 本身实现为 `drawBehind`，给现有组件加背景可直接用它。
5. **重录成本**：没有缓存对象时直接用 `drawBehind`。只触发绘制仍会重录 DisplayList。不断增长的 Path 逐帧重录时，可按 segment 拆分、对过密输入做有界简化或把不变背景放独立图形层。
6. **混合模式**：`BlendMode.Clear`、`DstIn` 等会影响目标像素，需判断是否使用 `CompositingStrategy.Offscreen`，否则混合可能影响图形层外的窗口内容。

**Q24: 图表、粒子、手写这类持续绘制的场景什么时候该离开 Compose Canvas 转 SurfaceView，绘制回调的线程边界是什么？**

简单图表、装饰和有限动画适合 Compose Canvas。视频、相机、独立生产节奏或专业低延迟手写应评估 SurfaceView 或前缓冲方案。Compose 的 draw 阶段仍在 UI 线程执行，后台线程只能准备数据快照。

1. **Canvas 规模**：把大量图元放入一个布局节点，将排序、聚合与坐标索引放在数据层或缓存构建块，只生成当前 viewport 范围的点与标签。减少的是 Composable 节点数，不是 draw op、Path 或文字栅格成本。
2. **数据线程**：粒子状态可在后台计算为不可变帧快照，但绘制本身不能迁到后台线程。
3. **像素缓冲**：每帧在 CPU Bitmap 改像素再画到硬件窗口会产生纹理上传与同步。动态内容优先录制矢量命令。
4. **硬件位图**：`Bitmap.Config.HARDWARE`（API 26+）是不可变的只读绘制源，可包装为 ImageBitmap 绘制到硬件 Canvas。不能作为 `Canvas(bitmap)` 的可写目标，也不能绘制到软件 Canvas。需要读回或软件滤镜时用软件分配或显式复制。
5. **前缓冲手写**：`CanvasFrontBufferedRenderer`（androidx.graphics 外部库）基于 SurfaceView 与前缓冲，内部渲染线程回调。它增加独立 Surface、事务和画面撕裂权衡，不是普通 Canvas 的透明替换。
6. **无障碍**：Canvas 中的线、点、柱不会自动成为语义节点。表达业务信息或响应点击时还要补 `contentDescription`/`semantics`。
7. **过度绘制**：先确认 GPU 时长有可优化空间，再处理同帧重复绘制。优化前后用同一脚本和帧指标对照。
