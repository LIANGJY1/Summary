# Compose 运行期与 View 互操作

> 本册覆盖组合、状态与 Snapshot、Lazy 列表、Modifier 与测量、View 双向嵌入和动画组件契约。AndroidX 运行时、界面和动画行为按题内版本说明理解。图层、Canvas、着色器和持续出图见 [应用绘制与图形效果](../06-graphics/12-应用绘制与图形效果.md)。重组、布局与绘制耗时的验证见 [界面渲染与交互响应](../14-performance/01-应用/02-渲染与响应.md)。

## 第一章 组合、状态与副作用

**Q1: [learning] 滚动列表时任意状态变化都让整页重组，Compose 的组合、布局、绘制各阶段怎样响应？**

组合、布局和绘制是 Compose 更新 UI 的三个阶段。重组只表示重新执行 composable 函数以更新组合结果，并不代表整页布局和绘制都重跑。

1. **组合**：执行 composable 函数并更新组合树。
2. **布局**：测量节点并确定位置。
3. **绘制**：发出绘制命令。
4. **状态订阅**：组合执行时读取的 Snapshot 状态会登记为对应组合范围的依赖。状态写入后，运行时标记读取它的范围失效并安排重组。因此影响范围取决于状态在哪里被读取，不取决于谁调用了谁。
5. **延迟读取**：把高频状态读取移至布局或绘制阶段，可避免不必要的前置阶段工作。是否值得需看实际 trace。

**Q2: [learning] 点击后 composable 持续重组甚至掉帧，为什么不能在组合函数体里直接写状态？**

在组合期间写入刚读取的状态会令组合范围再次失效，重组再次执行时又重复写入，可能形成不断失效的循环和掉帧。

1. **事件产生的新状态**：在点击等事件回调中更新状态，因为写入发生在组合执行之外。
2. **由当前状态计算的值**：改为纯派生计算或 derivedStateOf，不要在组合体里同步写回另一个状态。
3. **确需副作用的工作**：移至合适的 Effect，并让 Effect 键表达其输入和生命周期，避免重组时重复执行。

**Q3: [learning] 旋转屏幕后输入内容消失，remember 与 rememberSaveable 分别能保留什么？**

remember 把值保存在当前 Composition，只跨重组有效。配置变更或进程重建后旧 Composition 消失。rememberSaveable 将可保存值放入 saved instance state，因此在宿主正确保存状态、且状态可写入 Bundle 或提供 Saver 时可跨 Activity 重建与系统进程死亡恢复。

按值的用途和所需寿命选择 API：可重新创建的组合内对象使用 remember。需要在宿主重建后恢复的少量 UI 状态使用 rememberSaveable。

1. **remember**：保存缓存、组合内对象引用等值，只跨重组保留。Composable 离开组合或 remember 的 key 改变时会丢弃该值。Activity 配置重建和进程死亡后也不保证保留。
2. **rememberSaveable**：适合滚动位置、展开状态和输入内容等用户状态。它经 saved instance state 的 Bundle 保存，在宿主执行保存/恢复且值可序列化或提供 Saver 时可跨配置重建和系统进程死亡恢复。
3. **数据约束**：Bundle 容量有限，不要保存大型对象。自定义类型应可写入 Bundle、使用 Parcelize 或提供 Saver。用户主动结束任务后，系统不承诺恢复该临时状态。

**Q4: [learning] 滚动偏移每帧变化却只关心是否到达顶部，derivedStateOf 如何减少无用更新？**

当输入高频变化而 UI 只需要观察低频派生结果时，derivedStateOf 可缓存计算并帮助控制观察者失效。默认不带 SnapshotMutationPolicy 时，依赖变化仍会触发失效，即使派生值相等。

1. **适合场景**：滚动偏移每帧变化，但 UI 只关心首项是否越过阈值。
2. **默认策略**：没有显式策略时，每次依赖变化都会使观察者失效。
3. **结果比较**：需要按结果相等性过滤时，使用带 structuralEqualityPolicy() 的重载。
4. **使用成本**：依赖跟踪和派生计算也有成本。输入低频或结果同样高频变化时不一定获益。

**Q5: [learning] 某个状态更新让大量 composable 重跑，不稳定参数会怎样影响跳过？**

Compose Compiler 根据参数类型的稳定性决定 composable 是否可跳过。参数不稳定时，默认跳过规则下 composable 通常不可跳过，父级重组可能令它再次执行，即使参数实例没有改变。

1. **常见稳定类型**：基本类型和不可变数据类型通常可稳定比较。
2. **常见不稳定类型**：有未受观察的可变属性的类型，以及 Kotlin List、Set、Map 等接口类型通常不稳定。val 只限制属性重新赋值，不保证集合元素不可变。
3. **排查和选择**：先用 Layout Inspector 或编译器稳定性报告确认，再考虑不可变集合、显式可观察状态或 Strong Skipping。不要只为消除报告而滥用 @Stable/@Immutable。

**Q6: [learning] 启用 strong skipping 后不稳定参数仍会重组吗？它改变了哪些跳过规则？**

Strong Skipping 放宽可跳过条件，并自动记忆捕获变量的 lambda。它改变编译器的跳过策略，不会令不稳定类型本身变稳定。

1. **可跳过范围**：启用后，restartable composable 即使带不稳定参数也可跳过。non-restartable composable 仍不可跳过。
2. **参数比较**：不稳定参数按实例相等（===）比较，稳定参数按 equals 比较。
3. **Lambda**：捕获变量的 lambda 会自动记忆化，减少每次重组创建新 lambda 的情况。
4. **版本**：Compose Compiler 1.5.4 引入此模式，Kotlin 2.0.20 起默认启用。这是编译器版本边界，不是 Android API level。
5. **集合更新**：内容相同但新建的普通 List 仍使用引用比较。在原地修改同一个不可观察的集合时，引用未变，Snapshot 也收不到写入，界面可能保持旧内容。使用不可变 UI 模型、SnapshotStateList 或新实例流转。
6. **稳定性契约**：@Stable 要求公开属性变化可被 Compose 观察，且同一对实例的 equals 结果一致。含不可观察可变字段的对象不符合 @Immutable 契约。错误标注可能使应发生的更新被跳过。
7. **跳过与记忆的边界**：返回非 Unit 值或标记了 @NonSkippableComposable 的函数没有跳过逻辑。捕获值按其稳定性决定记忆比较方式，@DontMemoize 可关闭自动记忆。编译器报告中的 skippable 表示可跳过，实际跳过还取决于范围失效与参数比较。

**Q7: [learning] Compose 中同一状态被 composable 函数体、placement lambda 与 draw lambda 读取，状态变化分别触发什么？Recomposition 一定带来重绘吗？**

Snapshot 按读取所处阶段建立观察关系。失效从被读取的最早阶段开始传播，因此延迟读取可跳过不需要重跑的前置阶段。Recomposition、Layout 和 Drawing 不是绑定在一起的单一步骤。

1. **Composable 函数体**：读取状态会使相应组合范围进入 recomposition。变更结果可能进一步请求 Layout 或 Drawing。
2. **测量 lambda**：只让依赖状态的节点重新测量，通常可跳过 recomposition。
3. **放置 lambda**：只让受影响节点重新定位，通常可跳过测量。
4. **draw 与 graphicsLayer lambda**：使对应绘制范围或 layer 属性失效，可跳过 recomposition 和 layout。内容未变化时绘制记录可能继续复用。
5. **阶段边界**：recomposition 不必然导致重绘，drawing 也可以在没有 recomposition 时发生。把动画或滚动值从函数体移到 offset {}、graphicsLayer {} 等阶段 lambda，可令失效从较晚阶段开始，但要用 trace 验证收益。

Compose 1.12.0 的 AndroidX 材料还给出两个边界：

1. **组合时机**：LazyColumn、BoxWithConstraints 和 SubcomposeLayout 可在布局时决定子内容，三阶段描述的是失效起点。
2. **布局语义**：图层变换用于改变视觉呈现。父级占位或无障碍语义需要随状态改变时，应更新相应布局或语义。状态决定节点数量、文本或语义属性时，将读取缩小到实际使用该状态的组合范围。

下面将滚动偏移交给放置阶段，将透明度交给图层属性更新。两个值均在各自的 lambda 内读取。

```kotlin
Modifier
    .offset { IntOffset(0, listState.firstVisibleItemScrollOffset / 2) }
    .graphicsLayer { this.alpha = alphaState.value }
```

**Q8: [learning] 协程因界面重组反复启动或资源未释放，三个 Effect 的生命周期边界是什么？**

三个 Effect 的共同边界是组合生命周期：进入组合启动、离开组合取消，差异在 key 变化时的重启语义。

1. **LaunchedEffect**：进入组合后启动协程，任一 key 改变时取消旧协程并启动新协程，离开组合时取消。
2. **SideEffect**：每次成功 apply 一次重组后执行，用于把 Compose 状态同步到外部对象。不要用它启动应受生命周期管理的长任务。
3. **DisposableEffect**：进入组合或 key 改变时建立资源，key 改变或离开组合时调用 onDispose 清理，适合注册与反注册监听器。

常见误用可以按触发时机区分：

1. **点击事件**：直接在点击回调里处理，不要把用户事件建模成 LaunchedEffect。
2. **每帧变化的 Effect 键**：会反复取消并重启协程。键应表达协程需要跟随的输入。
3. **需要长期保留的资源**：不要绑定到会频繁离开组合的 DisposableEffect，应选择与资源真实生命周期一致的 owner。

**Q9: [learning] remember(keys)、derivedStateOf、produceState 与 snapshotFlow 各适合什么场景，混用会出什么问题？**

`remember(keys)` 按显式 key 缓存一次计算，`derivedStateOf` 把高频输入收敛为低频 State，`produceState` 把外部数据转成 State（key 变化取消旧 producer、启动新 producer），`snapshotFlow` 把 Snapshot 状态读取转成冷流驱动副作用。各自边界（Compose 1.12.0 材料口径）：

1. **remember**：key 是缓存身份，传入原地修改的普通列表时不会重新计算——问题在数据所有权与可观察性，不在多加一层 remember。
2. **derivedStateOf**：适合“输入每次变、输出只在跨界变”（如滚动是否越过首项）。它维护依赖表与缓存，本身有成本，字符串拼接、数值乘法这类输出每次都变的表达式直接计算更省。每次重组新建派生状态同样错误，应配合 `remember` 保留实例。
3. **produceState**：返回的 State 由 `remember` 保留，key 变化不会把它重置为 `initialValue`，切换用户要立即显示“加载中”须在新 producer 开头显式赋值。State 合并相等值，连续快速写入时观察者可能跳过中间值。回调式数据源用 `awaitDispose` 注销。
4. **snapshotFlow**：代码块在只读 Snapshot 中执行、按 `equals` 过滤、可能跳过中间状态，适合观察“当前状态”，不适合统计每次点击或传感器样本。

Strong Skipping 不管理 producer 协程，协程的启动、取消与异常处理仍由 Effect key 与作用域生命周期决定。

## 第二章 Snapshot 版本与并发写入

**Q10: [learning] 在后台线程读取 mutableStateOf 时读到的可能是哪个版本，Snapshot 按什么条件从状态记录链里选值？**

后台线程在某个 Snapshot 中读取 StateObject 时，会按该 Snapshot 的版本视图选择一条 StateRecord。它读到的不是“当前最新值”的无条件保证。

1. **可见条件**：记录的 Snapshot ID 不是 `INVALID_SNAPSHOT`，不大于当前 Snapshot ID，并且不属于当前 Snapshot 的 `invalid` 集合。
2. **选择规则**：从可见记录中选 Snapshot ID 最大的一条。
3. **线程范围**：Compose Runtime 1.12.0 中 `Snapshot.current` 是线程局部状态。`snapshot.enter { }` 只在当前线程临时替换它，不会更改其他线程的当前 Snapshot。
4. **快照时点**：快照创建后才产生的记录不会进入其视图。创建快照时尚未 apply 的其他 Snapshot 会进入 `invalid` 集合。
5. **并发边界**：读取通常无锁遍历记录链，但全局 Snapshot 刚推进时快路径可能失败，Runtime 会在 `sync` 临界区按新的 `Snapshot.current` 重试。因此“State 读取永远无锁”不成立。可见性只决定读哪个版本，不会让 `state.value++` 这类复合操作原子化。

**Q11: [learning] takeSnapshot() 创建的只读视图为什么必须显式 dispose，不 dispose 会怎样？**

`takeSnapshot()` 创建的只读视图必须由调用方 `dispose()`。否则它会继续固定仍可能读取的旧记录，增加记录链长度、遍历和内存成本。GC 不能替代显式结束。

1. **普通只读快照**：用 `try/finally` 保证 `takeSnapshot()` 的结果最终 dispose。
2. **可变快照**：`takeMutableSnapshot()` 在 apply 或放弃后仍须 dispose。未 apply 且被明确结束的 MutableSnapshot 才走内部 `abandon()` 路径，将自己的记录标成 `INVALID_SNAPSHOT` 供后续写入复用。
3. **嵌套快照**：每层快照分别结束和释放，不能只处理最外层。
4. **协程上下文**：`asContextElement()` 只在协程恢复时进入或离开 Snapshot，不负责 dispose。
5. **只读边界**：在只读 Snapshot 中写 State 会抛 `IllegalStateException`。`dispose()` 不修改全局状态，只解除该视图对旧记录的保留。Compose Runtime 1.12.0 实现中不存在应用可依赖的 `AbandonedSnapshot` 类型。

**Q12: [learning] 两个并发 MutableSnapshot 修改同一个 StateObject，apply 冲突按什么规则合并，内置变更策略有什么区别？**

并发 `MutableSnapshot` 应用修改时，Runtime 用同一 StateObject 的 previous、current、applied 三条记录尝试合并。能生成合并记录就继续 apply，返回 null 则报告冲突。

1. **记录含义**：`previous` 是待应用 Snapshot 开始修改前读取的记录，`current` 是父或全局状态当前记录，`applied` 是待应用 Snapshot 写出的记录。
2. **策略委托**：`MutableState` 将合并委托给 `SnapshotMutationPolicy.merge()`。`structuralEqualityPolicy()` 用 `==` 判等，`referentialEqualityPolicy()` 用 `===` 判等，`neverEqualPolicy()` 永不判等。
3. **默认冲突**：结构相等和引用相等策略都可在两支没有产生不同有效更新时选择可用版本。若双方把同一字段改成彼此不等且都不同于 previous 的值，就不能自动合并。`neverEqualPolicy()` 更容易将并发赋值视为冲突，只适用于每次赋值都必须算变化的语义。
4. **领域合并**：计数器可自定义 `current + (applied - previous)` 合并增量。合并函数必须是纯函数、结果确定、执行快速且没有副作用。
5. **复杂度边界**：apply 从已修改对象集合出发，不扫描进程中全部 StateObject。但仍可能遍历单个对象的记录链、调用合并函数并通知观察器，不能简化成严格 O(changed states)。相等只解决无效写和冲突判定，不定义增量相加、集合并集或字段级合并。

**Q13: [learning] 两个 Snapshot 各改不同 State 都 apply 成功，跨对象业务约束仍可能被破坏——这是为什么，怎么防？**

Snapshot apply 只检查写入记录冲突，不提供可串行化保证。两个 Snapshot 修改不同对象仍可能破坏跨对象不变量，这种现象叫写偏差（write skew）。

1. **形成条件**：两个快照基于同一组旧状态读取，各自修改不同 StateObject。因为修改集合没有交集，记录级冲突检查都可能通过。
2. **业务结果**：例如两名值班者同时判断“至少一人在线”，各自下线不同的人，两个更新应用后却无人在线。
3. **原子可见性**：`Snapshot.withMutableSnapshot { }` 封装 `takeMutableSnapshot()`、`enter`、`apply().check()` 和 `dispose()`。代码块成功返回才尝试 apply，异常不会 apply，冲突抛 `SnapshotApplyConflictException`。成功后这批修改一起可见，但它不会保证一次重组或一帧，也不会修复写偏差。
4. **不变量保护**：把共同变化字段放进一份不可变状态，或使用 `Mutex`、Actor、`StateFlow` 原子更新或数据库事务。多字段共同变化时，单一不可变 `UiState` 通常比多个 State 更直接。
5. **副作用边界**：代码块不能挂起，也不应执行外部副作用。该 API 不自动重试，调用方若因冲突重跑，代码块必须允许重复计算。I/O、Binder 和文件写入不会随 Snapshot 回滚。

**Q14: [learning] mutableStateOf 可以从工作线程读写吗，state.value++ 为什么仍可能丢更新，enter 为什么不能跨协程挂起点保留？**

全局 Snapshot State 可跨线程读写，但这只保证记录访问可用，不保证 `state.value++` 这样的读改写原子化。`Snapshot.enter` 也不能跨协程挂起点手工保持。

1. **丢更新原因**：`state.value++` 包含读取、计算、写入三步。多个生产者可能读到同一旧值再写入，彼此覆盖。用 `MutableStateFlow.update`、`Mutex`、Actor 或数据库事务为复合操作提供原子性与排序。
2. **enter 线程范围**：`Snapshot.enter` 替换当前线程的 Snapshot。协程挂起后可能在另一线程恢复，因此手工跨挂起点保留 enter 区间会破坏进入/退出配对。
3. **协程传递**：单个协程需要在恢复后进入同一只读视图时用 `asContextElement()`。它不负责 dispose，也不允许多个协程并行修改同一个 MutableSnapshot。该对象按多读者、单写者使用，并发多写属于未定义行为。
4. **UI 线程边界**：State 可跨线程不代表 View、ComposeView、Canvas 或依赖 Looper 的回调能跨线程操作。`SnapshotStateObserver` 对同一 Owner 的测量、布局、绘制观察也要求在同一线程。
5. **发布时机**：未 apply Snapshot 中创建的 State 若提前泄露，较早 Snapshot 可能读不到合法记录并报错。长期状态容器应在全局上下文创建，事务内初始化的引用要等 apply 成功后再发布。

**Q15: [learning] 一次 state.value = x 写入后重组是怎么被安排的，为什么多次写入可能合并成一次重组？**

一次 State 写入不会对应一次重组。Snapshot 先记录有效变化，再由观察关系标记受影响的 Composition，重组循环按帧时钟调度并可合并失效。

1. **写入记录**：变更策略先判断新值是否等价。等价则提前结束，否则修改状态记录，并将 StateObject 加入当前 Snapshot 的修改集合。
2. **观察关系**：Compose Runtime 1.12.0 在组合时用 `recordReadOf` 登记“值 → RecomposeScope”，编译器生成的 `ScopeUpdateScope` 支持重启函数。布局和绘制阶段则由 `SnapshotStateObserver.observeReads()` 建立各自观察关系。
3. **失效阶段**：状态读取发生在哪一阶段，失效就可局限在哪一阶段。失效对象集合只有对象身份，没有业务语义，也不等于待执行的重组函数列表。
4. **合并时机**：帧回调开始前的多次写入可以合并。一次回调处理中也可能继续处理新失效，因此既不是每次写入重组一次，也不是每帧恰好重组一次。
5. **观测边界**：`SnapshotStateObserver` 不是通用线程安全对象，内部原子队列不表示可由多个布局线程共享。Perfetto 默认没有逐 State 轨道。`Compose:applyObservers` 只覆盖观察器调用阶段，不能代表完整 apply 耗时。

## 第三章 Lazy 布局、Modifier 与文本测量

**Q16: [learning] LazyColumn 的 key 和 contentType 分别决定什么？**

key 决定条目的身份，contentType 决定不同条目的组合是否可以按结构类别复用。两者分别接近 RecyclerView 的稳定 ID 与 viewType。

1. **身份**：在同一列表内使用唯一且稳定的业务键。key 可由 Bundle 保存时，才能支持条目中的 rememberSaveable 状态恢复。默认按位置识别，使用索引作 key 在头部插入或移动条目后仍会让状态跟错业务对象。条目离开组合后，普通 remember 的值会丢失。
2. **结构分组**：将结构相近的条目标为同一 contentType。默认 null 表示全部同型，以业务 ID 作 contentType 会妨碍跨条目复用。Compose Foundation 1.12.0 按类型最多保留 7 个可复用槽位，这是题内 AndroidX 版本材料中的实现细节。
3. **预取与工作量**：Lazy 布局的预取调度仍在主线程，可用 PausableComposition 预组合，Foundation 1.12.0 对应开关默认为 true。避免在条目组合里反复排序、过滤或创建大对象，常量工作可外提或用 remember 缓存。
4. **迁移对照**：用同一设备、数据集和交互脚本对比两种实现的慢帧率、P95 帧耗时、内存峰值和首屏可交互时间。

**Q17: [learning] Compose 的 PausableComposition 分段执行的是什么工作？为什么它不能用来降低 GPU 负担？**

PausableComposition 把尚未投入使用的子 composition 分段执行，主要用于 Lazy 容器预取即将进入视口的 item。它调整预取工作落在哪些空闲帧，不会拆分可见页面的普通 recomposition，也不直接减少绘制或 GPU 工作量。

1. **暂停与继续**：空闲预算不足时暂停分段 composition，之后调用 resume() 继续。工作完成并 apply() 后，结果才加入布局树。
2. **版本引入**：PausableComposition 从 Compose Runtime 1.8.0-alpha02 引入，不是 Compose 1.7 稳定能力。
3. **预取开关**：Foundation 1.10.6 出于稳定性曾将 isPausableCompositionInPrefetchEnabled 设为 false。1.11.4 源码中为 true。需按实际 Foundation 版本核对该默认值。
4. **空闲估计**：预取调度器按 View.display.refreshRate 估算帧间隔，并以距上次 draw 超过两个帧间隔判断 idle。availableTimeNanos() 是库内估算值，不是 Choreographer 提供的精确 frame deadline。
5. **工作边界**：它改变预取 composition 的调度，不降低需要完成的组合总量，也不减少 draw、GPU 或 SurfaceFlinger 工作。

**Q18: [learning] padding + background + clickable 组合会创建几个 LayoutNode，调整 Modifier 顺序为什么属于行为变更而不是纯性能优化？**

一个——Modifier 元素创建的是 `Modifier.Node`（布局、绘制、输入各归其类），不会为每项新建 `LayoutNode`，但长链仍增加节点更新与遍历成本。顺序决定约束传递与绘制范围的语义，`padding(16.dp).size(40.dp)` 与 `size(40.dp).padding(16.dp)` 接收和传递的约束不同、最终外部尺寸可能不同，`clip().background()` 与 `background().clip()` 的圆角覆盖范围也不同。机制（Compose UI 1.12.0 材料口径）：`LayoutNode` 持有 `NodeChain`，实现 `LayoutModifierNode` 的节点获得 `LayoutModifierNodeCoordinator`，在被包裹内容的外层参与约束转换、测量与放置。1.12.0 把差分用的临时列表改为 `MutableObjectList` 并复用遍历栈，这是减少分配的实现调整，不合并节点、也不改变链的顺序语义。做法与边界：`Modifier.then()` 只连接两段 Modifier，不合并相邻节点，不能当优化接口。调整顺序前先确认布局、绘制、输入与无障碍语义的变化再测性能。每个 Modifier 按能力类型分析成本，不能把每个节点都计成一层布局树。

**Q19: [learning] 自定义 Modifier.Node 时 NodeChain 按什么规则复用、更新或替换节点，Element 的 equals 契约写错会怎样？**

NodeChain 对新旧 Element 序列做差分：相等则复用 Node 且不调 `update()`，运行时类型相同但不相等则复用 Node 并调用新 Element 的 `update(node)`，类型不同则移除旧 Node 并创建新 Node。Element 的 `equals()`/`hashCode()` 必须覆盖所有会改变 Node 行为的输入——遗漏字段会让界面保留旧配置，错写相等会让运行时跳过本应执行的 `update()`。机制（Compose UI 1.12.0 材料口径）：Element 是重组时可重建的轻量配置，Node 是附着在 `LayoutNode` 上可跨重组复用的运行对象，配置放 Element（参数型用 data class 最稳）、状态放 Node。`update()` 只同步字段即可，`shouldAutoInvalidate` 默认 true 会按节点实现的接口自动失效（Draw 接口失效绘制、Layout 接口失效测量），再无条件手动调 `invalidateDraw()` 属于重复表达。边界：Node 复用不等于 Element 零分配，工厂函数仍可能创建轻量 Element。频繁变化的运行状态放进 Element 会放大分配。差分规则属内部实现，升级 Compose 后按目标版本复核。关闭自动失效（`shouldAutoInvalidate = false`）后，每类输入变化都要对应调用 `invalidateDraw()`/`invalidateMeasurement()`/`invalidatePlacement()`，遗漏即 UI 不更新——只有剖析证明无效阶段调用值得优化且测试覆盖每个分支时才用。

**Q20: [learning] 自定义 Modifier 只组合现有节点时，Modifier.composed 与 Modifier.Node 的运行成本和迁移边界是什么？**

`Modifier.composed` 在物化时要运行组合逻辑并生成应用位置专属的 Modifier 链。需要自定义绘制、测量、语义或输入行为时，`Modifier.Node` 可避免为该行为额外建立组合槽位，通常更适合高频节点。

1. **成本来源**：`composed` 会执行组合并创建实际 Modifier 链，状态生命周期跟随组合槽位。Node 将轻量配置放在 Element、运行状态放在附着到 `LayoutNode` 的 Node 上。
2. **Composable 工厂限制**：非 `Unit` 返回值的 Composable 不可被跳过，即使输入稳定也会随调用者重组。Composable 工厂读取的 `CompositionLocal` 来自调用位置。
3. **选型**：只组合现有 Modifier 时用普通工厂。需要新增绘制、测量、语义或输入行为时用 `ModifierNodeElement` 与 Node。Android 官方迁移指南建议自定义行为改用 `Modifier.Node`，但现有 `composed` API 仍然可用。迁移优先处理有剖析证据的热点。

**Q21: [learning] 自定义 Modifier.Node 的 onAttach、onDetach、onReset 与 coroutineScope 分别该承担什么生命周期工作？**

Node 的附着生命周期管理 owner 访问和协程，复用回调则负责清除与旧列表项绑定的状态。不要把两者混为一次性销毁。

1. **附着与协程**：`onAttach()` 后可访问 owner 和 Node 的 `coroutineScope`。作用域在 `onDetach()` 返回后取消，同一 Node 之后仍可能重新附着。
2. **复用清理**：`onReset()` 在节点仍附着时、即将进入复用池前调用，例如 Lazy 列表项离开视口时。随后 Node 会 detach。清除焦点、按压和拖拽进度等条目级状态，避免新数据项继承旧状态。
3. **状态观察**：Node 中不能使用 `LaunchedEffect`。`onAttach()` 同轮其他节点未必已完成处理，需要观察整棵树最终状态时用 Node 的 `sideEffect` 延后。
4. **CompositionLocal**：实现 `CompositionLocalConsumerModifierNode` 后，通过 `currentValueOf()` 读取附着位置的值。阶段外读取可用 `observeReads` 订阅变化，收到通知后重新读取并应用新值。

**Q22: [learning] 自定义 Compose Layout 里对同一个子节点用两组约束连续调用 measure() 会发生什么，单遍测量协议包含哪些约束？**

运行时会抛异常——单遍测量协议要求每个 `Measurable` 在一次布局过程中只被 `measure()` 一次：父节点传一组 `Constraints`，保存返回的 `Placeable`，再在 `layout(width, height) { }` 块中放置。机制（Compose 1.12.0 材料口径）：`Placeable` 保存测得宽高供放置阶段使用，为它建列表是实现的明确成本，不必为“零对象”删掉必要状态。宿主层面 `AndroidComposeView.onMeasure()` 把 View 的 `MeasureSpec` 转成 Compose `Constraints` 并触发根测量，`onLayout()` 与 `dispatchDraw()` 前完成待处理的测量放置，Compose 三阶段嵌在宿主 ViewRootImpl 遍历中，与平台各用一套 API 名称。做法与边界：需要两遍协商（先测主体再决定覆盖层、按内容定父尺寸）时用 `SubcomposeLayout` 或固有尺寸查询表达，不要绕过协议。测量 lambda 内不做业务取数、排序、字符串解析、图片解码与日志格式化。父节点读取子节点的 `AlignmentLine` 对齐线时会建立依赖，子节点对齐线变化会触发父级重新测量或放置，排查父布局频繁重测时要检查是否读取了对齐线，不能只看传入约束。

**Q23: [learning] Compose 节点上一帧测过的尺寸什么时候会被复用，@Stable 注解会影响布局缓存吗？**

`MeasurePassDelegate` 在节点没有 `measurePending` 标记、且本次 `Constraints` 与上次相同时直接复用已有结果，否则执行 `performMeasure()` 并通过 Snapshot 观察器记录测量代码读取的状态。这套机制跨帧生效，不是“同一帧缓存一个结果”。`@Stable` 属编译器稳定性契约，不参与约束比较、也不清除 `measurePending`，只可能通过减少重组间接减少布局失效。错误标注反而会让 UI 漏更新。机制与边界（Compose UI 1.12.0 材料口径）：标记分两组——`measurePending`（尺寸需重算）与 `layoutPending`（位置需重算），由 `MeasureAndLayoutDelegate` 按树深维护，父节点已处于待测状态时子节点通常不再重复登记。约束相同也不保证子树无工作，子节点因自身状态读取失效时由 `forceMeasureTheSubtree()` 处理。失效传播没有固定的逐级父链，取决于节点是否放置过、尺寸是否变化与对齐线、前瞻布局依赖。排查顺序：先确认状态读取阶段，再检查约束是否稳定（窗口、Insets、字体缩放），最后才考虑稳定性注解。

**Q24: [learning] 对 LazyColumn 或 BoxWithConstraints 查询 IntrinsicSize.Min 为什么会直接失败，固有尺寸查询的真实成本怎么评估？**

`SubcomposeLayout` 使用 `NoIntrinsicsMeasurePolicy`，父级经 `IntrinsicSize.Min/Max` 查询会直接失败——这类组件在测量时才决定组合哪些内容，组合之前不存在可靠的固有尺寸。普通自定义 Layout 未覆写固有尺寸方法时得到的是近似默认实现。机制：固有尺寸查询发生在正式测量之前，官方保证它不会把同一子节点正式测量两次，但查询本身要递归询问相关子树，文本固有尺寸会运行段落宽高计算，成本取决于布局实现、查询方向与节点数量，不能统一写成 O(depth) 或“一次完整子树测量”。做法与边界：需要“与父尺寸匹配”时改外层布局的测量顺序或给组件明确约束，对 `LazyColumn` 调 `height(IntrinsicSize.Min)` 得不到低成本的列表总高度。覆写固有尺寸方法要与正式测量语义一致、同输入同结果、不含 I/O 或可变集合访问，并注意字体缩放与布局方向造成的失效。把固有尺寸结果缓存在业务层要考虑 `Density`、`fontScale`、文本内容等失效输入。结构只在少数尺寸断点变化时，先归纳为紧凑/展开模式统一处理，避免每个列表项各自套一层 `BoxWithConstraints`。

**Q25: [learning] 同样的 Compose Text(String) 在什么条件下走简化实现 ParagraphLayoutCache，哪些能力会让它改走 AnnotatedString 路径，段落缓存什么时候复用？**

未启用文本选择、没有 `onTextLayout` 回调、未启用 autoSize 三个条件同时满足时，`BasicText(String)` 用 `TextStringSimpleNode` 加 `ParagraphLayoutCache`（只在无障碍请求布局结果时才创建完整的 `TextLayoutResult`）。需要选择、布局回调或自动字号就转 `AnnotatedString`，改走 `TextAnnotatedStringNode` 加 `MultiParagraphLayoutCache`（Compose Foundation 1.12.0，AndroidX 外部库）。机制：节点把布局属性与绘制属性分开比较——字号、字体、行高、maxLines、约束等布局属性变化重新测量，颜色、画刷、阴影等纯绘制变化保留排版只重绘。内容相等的新建 `AnnotatedString` 不会因对象身份不同就使布局失效。复用条件：宽度不变且新高度仍能容纳旧段落时断行不变、可复用已有段落，最大宽度变化必须重排，所以列表项宽度在滚动中保持稳定对文本成本最有利。字体异步解析返回目标 Typeface 后旧缓存过期重测。边界：Android 实现里文本满足简化条件且单行放得下时用 `BoringLayout`，否则用 `StaticLayout`（判定入口在 Compose 节点，与平台 TextView 的快慢路径同源）。`TextAutoSize.StepBased` 用二分查找选字号，每个候选都试排、收敛后还会再增大一步，长列表大面积启用要记录试排成本。给每个列表项长期加 `onTextLayout` 只为日志会放弃简化路径，回调里也不要用布局结果改影响本次布局的输入（如按 lineCount 调 maxLines 会形成反复测量）。

## 第四章 View 与 Compose 的双向嵌入

**Q26: [learning] AndroidView 把一个 View 接进 Compose 后由谁测量谁，Compose 状态变化什么时候会让这个 View 重新布局或重绘？**

`AndroidView` 创建 `ViewFactoryHolder`（父类是 `AndroidViewHolder`），将传统 View 与 Compose 的 LayoutNode 测量、放置和失效流程桥接起来。View 本身仍使用原有的测量、布局、绘制和事件模型。

1. **测量与放置**：Compose 将 `Constraints` 和 View 的 `LayoutParams` 合成为 MeasureSpec 并调用 `View.measure()`，再用 `measuredWidth/Height` 确定 LayoutNode 尺寸。放置阶段调用 `View.layout()`。
2. **模型变化**：`update` 中读取的 Snapshot 状态变化会通过承载器观察器再次执行 `update`。只读取 `model.title` 时，`model.image` 变化不会自动触发该更新。
3. **尺寸失效**：View 调用 `requestLayout()` 后，承载器找到对应 LayoutNode 请求重新测量，尺寸变化后再放置受影响节点。
4. **绘制失效**：View 调用 `invalidate()` 后最终使 LayoutNode 图层失效。View 报告的局部脏区不会原样传给 Compose 图层。绘制过程中产生的失效会通过 `postOnAnimation` 延到下一帧。
5. **性能分析**：分别统计 `update`、View `requestLayout()`、View `invalidate()` 和帧数，不能用重组计数替代这些桥接指标。
6. **滚动边界**：`AndroidViewHolder` 实现 `NestedScrollingParent3`，可传递普通嵌套滚动增量。相同方向的无限嵌套，以及 `requestDisallowInterceptTouchEvent` 切换手势控制方时，需要专项验证。

混合容器的可用边界应由父容器统一提供，分别核对 View 的 MeasureSpec 与 Compose 的 Constraints。出现卡顿时按桥接两端的测量、绘制和后续帧提交分别定位。

**Q27: [learning] AndroidView 的 factory/update/onReset/onRelease 各自的调用时机与职责是什么，怎样避免 View 与 Compose 状态互相回写的反馈循环？**

`factory` 创建 View，`update` 把当前模型同步到 View，`onReset` 清理将复用的瞬时状态，`onRelease` 释放永久离开的实例。避免反馈循环的关键是明确唯一状态所有者，并让两个方向的同步具备来源区分和等值保护。

1. **factory**：每个 View 实例调用一次，在 UI 线程创建控件、设置一次性属性并注册长期监听器。
2. **update**：factory 后至少调用一次，读取的 State 变化时再次运行。它应幂等地把当前模型写入 View，不执行重操作。
3. **用户事件**：Compose 参数作为唯一状态源，View 监听器只上报用户操作，可用 `fromUser` 区分来源。更新 View 属性前比较现值，避免重复赋值。不要在 `update` 中无条件回写 Compose 状态。
4. **清理与释放**：复用时由 `onReset` 清理瞬时状态，实例永久离开时由 `onRelease` 终止并释放资源。
5. **可变模型**：模型原地变化时，引用比较无法识别内容变化。改用不可变 UI 模型或版本号。把 View 预先放入 `remember` 不能绕过 factory 成本，还会破坏宿主、附着和复用语义。
6. **AndroidViewBinding**：沿用相同桥接与复用语义，只减少 `findViewById` 与类型转换，不会省掉 XML 加载和 View 测量、布局、绘制。

**Q28: [learning] Lazy 列表里的 AndroidView 为什么必须提供非空 onReset 才能复用实例，onReset 之后 View 处于什么状态？**

Lazy 列表中的 `AndroidView` 只有在使用带非空 `onReset` 的重载时才参与 View 实例复用。`onReset` 后 View 可能进入 deactivated 状态，不保证马上执行 `update`，所以重置后必须隐藏旧内容。

1. **兼容分组**：两个 AndroidView 能否互换取决于它们在 Compose 中的分组结构，不只取决于业务 key。
2. **回调顺序**：`onReset` 后可能直接执行 `update`，也可能先停用再释放。清理逻辑应允许重复调用。复用与释放是不同阶段。
3. **列表项状态**：清理旧监听器、标签、选择/按压/激活态和无障碍文案。
4. **进行中任务**：取消动画、延迟任务、图片请求、协程和播放器任务。
5. **内嵌组件**：按组件 API 清理 RecyclerView、WebView、地图或视频控件自身的滚动与资源状态。
6. **外部订阅**：解除与旧 `LifecycleOwner`、`SavedStateRegistryOwner` 或业务宿主绑定的回调。
7. **资源型 View**：若组件明确支持，可在 `onReset` 停掉条目会话、在 `onRelease` 销毁可复用引擎。若 `onReset + update` 比新建更贵，应调整复用边界，并以 factory 调用数下降、绑定耗时稳定证明复用有效。

**Q29: [learning] ComposeView 放进 RecyclerView 与放进 Fragment 各该用什么 ViewCompositionStrategy，为什么不要在 onViewRecycled 里 disposeComposition()？**

RecyclerView 中的 `ComposeView` 应依赖池化容器感知的默认策略，Fragment 中则应让 Composition 跟随 Fragment 的 View 树生命周期销毁。

1. **RecyclerView 默认策略**：`DisposeOnDetachedFromWindowOrReleasedFromPool` 在普通 View 树分离时销毁 Composition。在 RecyclerView 等池化容器短暂分离时保留，直到 item 被池永久释放或容器随窗口分离。
2. **Fragment 策略**：Fragment 实例可能继续存在而 View 树已销毁，应设 `DisposeOnViewTreeLifecycleDestroyed`，并在下一次 attach 时沿 View 树 `LifecycleOwner` 的 `ON_DESTROY` 销毁。
3. **绑定方式**：ViewHolder 初始化时调用一次 `setContent`。数据绑定更新 ViewHolder 持有的 `mutableStateOf`，不要每次绑定重建 Composition。
4. **避免主动销毁**：在 `onViewRecycled()` 调 `disposeComposition()` 会丢掉对象池保留 Composition 的收益，下次复用时整棵 Composition 重建。
5. **状态恢复**：列表项含 `remember` 状态时提供稳定 key、将状态提升到列表模型或明确设计保存恢复。多个静态 ComposeView 需要唯一 View ID 才能恢复 `savedInstanceState`。
6. **宿主条件**：自定义宿主或手工窗口需确认 `ViewTreeLifecycleOwner` 与 `SavedStateRegistryOwner` 已设置。

**Q30: [learning] 同一窗口里多个 ComposeView 是共享一个 Recomposer 还是各有一个，“每个 ComposeView 都很轻”为什么不成立？**

同一窗口的多个 `ComposeView` 通常解析到同一个 Recomposer，但每个实例仍有自己的 Composition、槽位表、AndroidComposeView、LayoutNode 树、语义管理、状态注册和测量边界，因此不能视为“多个 View 都很轻”。

1. **解析顺序**：通常依次查找显式父级 `CompositionContext`、View 树中可用的 CompositionContext、有效缓存和窗口 Recomposer。
2. **共享上下文**：Compose UI 1.12.0 的 `ComposeViewContext` 可在兼容宿主边界内共享配置缓存、字体加载器、无障碍等宿主对象。共享范围受 Context 与 `LifecycleOwner` 约束，不会合并业务状态。
3. **预组合**：可从已附着 View 构造上下文，并为未附着 ComposeView 调 `createComposition(context)` 做预组合。预热后始终未附着的实例要由调用方 `disposeComposition()`。
4. **成本评估**：记录 Compose 根数量、堆内存和销毁时机。十个小根与一个十节点根没有跨项目通用的字节数结论，应在目标版本实测。

**Q31: [learning] 自定义 Compose 宿主中的动画与界面更新不同步，Recomposer 为什么需要帧时钟？**

自定义宿主必须让 Recomposer 的帧等待与平台帧时钟相连，并为 Composition 设定明确的销毁生命周期。否则状态变化可能无法按帧调度，或 Composition 在宿主销毁后仍存活。

1. **帧时钟**：提供 MonotonicFrameClock，使 withFrameNanos 等等待者由宿主的帧回调恢复，重组能与帧更新节奏协调。
2. **重组职责**：Recomposer 处理 invalidation、recomposition 与 applyChanges。它不替代 ViewRootImpl 的窗口测量、布局、绘制和 HWUI 提交。
3. **生命周期**：Composition 离开窗口或宿主生命周期结束时应 dispose。选择 detach 或 ViewTreeLifecycleOwner 销毁取决于宿主是否会复用。

**Q32: [learning] 普通 Compose 页面有自己的渲染引擎吗？AndroidUiFrameClock 的 Choreographer 回调与 ViewRootImpl 的 traversal 回调各做什么？**

Compose 有独立 UI runtime 与节点系统，但常规 Android 硬件加速窗口仍由 HWUI 完成图形渲染和提交。AndroidUiFrameClock 的 Choreographer 回调负责恢复帧等待者，ViewRootImpl 的 traversal 才驱动窗口布局、绘制和 HWUI 提交。

1. **AndroidUiFrameClock**：为 withFrameNanos 等待者注册帧回调，恢复 Recomposer 执行 recomposition 与 applyChanges。
2. **ViewRootImpl traversal**：运行窗口 measure、layout、draw。AndroidComposeView 在对应 View 回调中完成 Compose 布局绘制。
3. **图形提交**：ThreadedRenderer/RenderThread、BLAST BufferQueue、SurfaceFlinger 与 HWC 负责后续绘制提交和合成，Compose runtime 不独立替代这条 Android 图形链路。
4. **空闲调度**：Compose 1.11.4 的 AndroidUiFrameClock 回调不会在 doFrame 内永久自我重挂。是否继续申请帧取决于新的 withFrameNanos 等待者。因此不能把它当作持续空转的帧循环估算功耗。

**Q33: [learning] 把旧页面从 View 迁到 Compose 后出现主题、状态或返回键问题，迁移时要检查哪些边界？**

1. **主题映射**：View 主题属性与 Material 主题并非一一对应。逐屏核对颜色、字号和组件默认值。
2. **状态寿命**：remember 只跨重组。需要跨 Activity 重建恢复的少量 UI 状态用 rememberSaveable，业务状态由合适的状态持有层恢复。
3. **回调生命周期**：View 绘制/可见性回调与 composable 生命周期不一一对应。依赖 OnDrawListener 等代码需要按 Compose 的状态和 Effect 模型重设计。
4. **返回与焦点**：在 Compose 中明确处理返回行为（如 BackHandler）和键盘焦点顺序，不能假设旧 View 的 onBackPressed 或焦点遍历规则会自动迁移。
5. **互操作边界**：AndroidView 内的 View 应接收 Compose 状态并通过事件回调上报用户动作，避免两边各自持有真值形成更新环。

## 第五章 动画组件与共享元素

**Q34: [learning] 同一个 Animatable 被手势高频调用 snapTo 与 animateTo 有什么行为差异，Transition 在 1.12.0 的组合语义是什么？**

`Animatable` 适合协程控制的单值动画，`Transition` 协调一个离散状态下的多项动画，`animate*AsState` 适合单值自动过渡。手势频繁重定向时，要根据“跟手”还是“延续速度”选择操作。

1. **互斥与取消**：`Animatable` 通过 `MutatorMutex` 保证一个变更任务运行。新的 `animateTo`、`animateDecay`、`snapTo` 或 `stop` 会取消已有变更，取消以 `CancellationException` 沿协程父子关系传播，需释放的资源放在 `finally`。
2. **手势跟随**：`animateTo` 从当前值继续，弹簧可延续当前速度。每个触摸采样点都调用它会连续取消并追赶。拖动跟手用 `snapTo`，松手后用 `animateDecay` 或 `animateTo` 收尾。
3. **Transition 状态**：`updateTransition(targetState)` 是可组合函数，内部用 `remember` 保存 Transition，并以 `DisposableEffect` 清理。参数或被观察 State 变化仍会重组相关作用域，不提供“组合永不重启”的保证。
4. **中断语义**：目标变化会更新迁移段，不同动画参数处理连续性的方式不同，不能一概说每次切换都从头重启。按控制语义决定是否允许每个输入重定向，或由业务状态屏蔽输入。
5. **版本选型**：Compose Animation 1.12.0 已弃用接收 `MutableTransitionState` 的 `updateTransition` 重载，应使用 `rememberTransition(transitionState)`。`DeferredTransitionState` 与 `mutableTransform` 面向预测性返回，不是通用性能开关。

**Q35: [learning] AnimatedVisibility 退出动画期间旧内容还在组合里吗，AnimatedContent 快速切换为什么会同时保留多份内容？**

`AnimatedVisibility` 和 `AnimatedContent` 会在退出动画完成前保留离场内容。快速切换可能同时持有多个仍在退出的子树，其 Effect、图片和订阅也继续存活。

1. **保留时机**：`AnimatedVisibility` 等待内建进入/退出动画和注册到 `AnimatedVisibilityScope.transition` 的自定义动画全部完成后才移除内容。期间 `LaunchedEffect` 未取消，`DisposableEffect.onDispose` 未运行。
2. **多份内容**：`AnimatedContent` 让新内容进入、旧内容退出，旧子树完成退出后才从组合移除。连续快速切换可同时保留多份未完成退出的内容。
3. **布局成本**：默认 `SizeTransform` 会动画容器尺寸，父布局与同级可能反复测量。默认展开/收缩影响容器报告尺寸，淡入淡出与缩放主要更新图层属性，滑动只改放置偏移而不缩小报告尺寸。
4. **动画同步**：独立 `animate*AsState` 不属于 scope 的 transition，容器不知道其结束时机，可能与退出不同步。需要同步的自定义动画应注册到作用域 transition。
5. **成本控制**：父布局不需要跟随伸缩时给外层稳定约束，内部用淡入淡出或滑动。内容较重时上移昂贵订阅，或退出后停止数据更新。缩短时长只缩短资源保留窗口，没有适用于所有界面的固定时长。
6. **内容身份**：`contentKey` 定义内容身份。两个目标映射到同一个 key 时不触发切换动画，可过滤“状态对象变、视觉身份不变”的更新。

**Q36: [learning] 共享元素过渡里 sharedElement 与 sharedBounds 怎么选，scaleToBounds 与 RemeasureToBounds 的成本差在哪？**

视觉内容相同且应只绘制目标端内容时选 `sharedElement`。容器连续但内容不同、允许进出内容短暂共存时选 `sharedBounds`。选择 `scaleToBounds` 或 `RemeasureToBounds`，决定过渡期间是缩放既有布局还是逐帧重测布局。

1. **sharedElement**：适合跨页主图或相同图标。过渡中绘制进入端内容，子节点按动画 bounds 的约束重测和重排。
2. **sharedBounds**：适合卡片到详情等容器连续、内容变化的场景。进入与退出内容默认以 fadeIn/fadeOut 共存。
3. **resizeMode**：默认 `scaleToBounds()` 用 Lookahead 得到的稳定目标布局做图形缩放。`Text` 宽度变化会重新换行，官方建议优先用缩放避免 reflow。宽高比变化的图片若需持续改变裁剪，可实测 `RemeasureToBounds()` 的测量布局成本。
4. **身份匹配**：`SharedTransitionScope` 按 key 和 `AnimatedVisibilityScope` 可见性匹配两端。两端应从同一业务 ID 派生 key，例如 `article-image:42`，并用 `rememberSharedContentState(key)` 保持状态。
5. **父布局占位**：placeholder 默认 `ContentSize`，退出端报告初始尺寸、进入端报告目标尺寸，父布局不随动画 bounds 每帧变化。切为 `AnimatedSize` 会让周边内容跟随，并可能增加重排。
