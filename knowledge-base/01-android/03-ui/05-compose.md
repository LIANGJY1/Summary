# Compose 运行期与 View 互操作

> 学习资料（文章模式沉淀）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：组合/布局/绘制三阶段与"读状态即订阅"、组合期写状态的无限重组、remember 与 rememberSaveable 的持有边界、derivedStateOf 与延迟读取、稳定性推断与 strong skipping、列表键与重组范围、LaunchedEffect/SideEffect/DisposableEffect 分工、ComposeView 嵌入与组合策略、AndroidView 的代价、自定义宿主与帧时钟、重组与掉帧的归因工具、View 迁移的高频坑。渲染侧的帧调度与掉帧度量见 [../04-graphics/01-render-pipeline-vsync.md](../04-graphics/01-render-pipeline-vsync.md)，View 侧绘制机制见 [02-view.md](02-view.md)，应用实践见 [Compose 渲染实战](../04-graphics/13-app-compose-advanced-practice.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 滚动列表时任意状态变化都让整页重组，Compose 的组合、布局、绘制各阶段怎样响应？**

组合、布局和绘制是 Compose 更新 UI 的三个阶段。重组只表示重新执行 composable 函数以更新组合结果，并不代表整页布局和绘制都重跑。

1. **组合**：执行 composable 函数并更新组合树。
2. **布局**：测量节点并确定位置。
3. **绘制**：发出绘制命令。
4. **状态订阅**：组合执行时读取的 Snapshot 状态会登记为对应组合范围的依赖。状态写入后，运行时标记读取它的范围失效并安排重组。因此影响范围取决于状态在哪里被读取，不取决于谁调用了谁。
5. **延迟读取**：把高频状态读取移至布局或绘制阶段，可避免不必要的前置阶段工作。是否值得需看实际 trace。

**Q2: 点击后 composable 持续重组甚至掉帧，为什么不能在组合函数体里直接写状态？**

在组合期间写入刚读取的状态会令组合范围再次失效，重组再次执行时又重复写入，可能形成不断失效的循环和掉帧。

1. **事件产生的新状态**：在点击等事件回调中更新状态，因为写入发生在组合执行之外。
2. **由当前状态计算的值**：改为纯派生计算或 derivedStateOf，不要在组合体里同步写回另一个状态。
3. **确需副作用的工作**：移至合适的 Effect，并让 Effect 键表达其输入和生命周期，避免重组时重复执行。

**Q3: 旋转屏幕后输入内容消失，remember 与 rememberSaveable 分别能保留什么？**

remember 把值保存在当前 Composition，只跨重组有效。配置变更或进程重建后旧 Composition 消失。rememberSaveable 将可保存值放入 saved instance state，因此在宿主正确保存状态、且状态可写入 Bundle 或提供 Saver 时可跨 Activity 重建与系统进程死亡恢复。

按值的用途和所需寿命选择 API：可重新创建的组合内对象使用 remember。需要在宿主重建后恢复的少量 UI 状态使用 rememberSaveable。

1. **remember**：保存缓存、组合内对象引用等值，只跨重组保留。Composable 离开组合或 remember 的 key 改变时会丢弃该值。Activity 配置重建和进程死亡后也不保证保留。
2. **rememberSaveable**：适合滚动位置、展开状态和输入内容等用户状态。它经 saved instance state 的 Bundle 保存，在宿主执行保存/恢复且值可序列化或提供 Saver 时可跨配置重建和系统进程死亡恢复。
3. **数据约束**：Bundle 容量有限，不要保存大型对象。自定义类型应可写入 Bundle、使用 Parcelize 或提供 Saver。用户主动结束任务后，系统不承诺恢复该临时状态。

**Q4: 滚动偏移每帧变化却只关心是否到达顶部，derivedStateOf 如何减少无用更新？**

当输入高频变化而 UI 只需要观察低频派生结果时，derivedStateOf 可缓存计算并帮助控制观察者失效。默认不带 SnapshotMutationPolicy 时，依赖变化仍会触发失效，即使派生值相等。

1. **适合场景**：滚动偏移每帧变化，但 UI 只关心首项是否越过阈值。
2. **默认策略**：没有显式策略时，每次依赖变化都会使观察者失效。
3. **结果比较**：需要按结果相等性过滤时，使用带 structuralEqualityPolicy() 的重载。
4. **使用成本**：依赖跟踪和派生计算也有成本。输入低频或结果同样高频变化时不一定获益。

**Q5: 某个状态更新让大量 composable 重跑，不稳定参数会怎样影响跳过？**

Compose Compiler 根据参数类型的稳定性决定 composable 是否可跳过。参数不稳定时，默认跳过规则下 composable 通常不可跳过，父级重组可能令它再次执行，即使参数实例没有改变。

1. **常见稳定类型**：基本类型和不可变数据类型通常可稳定比较。
2. **常见不稳定类型**：有未受观察的可变属性的类型，以及 Kotlin List、Set、Map 等接口类型通常不稳定。val 只限制属性重新赋值，不保证集合元素不可变。
3. **排查和选择**：先用 Layout Inspector 或编译器稳定性报告确认，再考虑不可变集合、显式可观察状态或 Strong Skipping。不要只为消除报告而滥用 @Stable/@Immutable。

**Q6: 启用 strong skipping 后不稳定参数仍会重组吗？它改变了哪些跳过规则？**

Strong Skipping 放宽可跳过条件，并自动记忆捕获变量的 lambda。它改变编译器的跳过策略，不会令不稳定类型本身变稳定。

1. **可跳过范围**：启用后，restartable composable 即使带不稳定参数也可跳过。non-restartable composable 仍不可跳过。
2. **参数比较**：不稳定参数按实例相等（===）比较，稳定参数按 equals 比较。
3. **Lambda**：捕获变量的 lambda 会自动记忆化，减少每次重组创建新 lambda 的情况。
4. **版本**：Compose Compiler 1.5.4 引入此模式，Kotlin 2.0.20 起默认启用。这是编译器版本边界，不是 Android API level。
5. **剩余责任**：稳定性治理、状态所有权和实际性能问题仍需单独分析。

**Q7: 高频状态只影响位移却反复触发组合，怎样把状态读取推迟到布局或绘制阶段？**

高频状态只影响位置或绘制时，可在布局/绘制阶段的 lambda 中读取它，让失效从较晚阶段开始。这会增加调试复杂度，应由性能证据驱动。

1. **位移**：使用 offset { } 等布局 lambda 延迟读取状态。
2. **绘制属性**：使用 graphicsLayer { } 等绘制/layer lambda 延迟读取。
3. **决策依据**：先用 trace 确认重组是显著耗时且状态高频变化，再采用延迟读取。低频状态或计算轻的界面可能没有收益。

**Q8: 列表插入一行后输入状态跑到别的行，Lazy 列表的 key 应如何选择？**

Lazy 列表的 key 为每个 item 提供稳定身份，使数据插入、删除或移动后 remembered 状态与动画仍跟随同一条数据。没有稳定 key 时通常按位置匹配，状态可能错位。

1. **选择来源**：使用数据库主键或业务唯一 ID。
2. **唯一性要求**：同一懒布局中的 key 必须唯一且在 item 位置变化后保持不变。
3. **避免位置键**：数组下标会随插入删除改变或被复用，不能代表数据身份。

**Q9: [learning] 协程因界面重组反复启动或资源未释放，三个 Effect 的生命周期边界是什么？**

三个 Effect 的共同边界是组合生命周期：进入组合启动、离开组合取消，差异在 key 变化时的重启语义。

1. **LaunchedEffect**：进入组合后启动协程，任一 key 改变时取消旧协程并启动新协程，离开组合时取消。
2. **SideEffect**：每次成功 apply 一次重组后执行，用于把 Compose 状态同步到外部对象。不要用它启动应受生命周期管理的长任务。
3. **DisposableEffect**：进入组合或 key 改变时建立资源，key 改变或离开组合时调用 onDispose 清理，适合注册与反注册监听器。

常见误用可以按触发时机区分：

1. **点击事件**：直接在点击回调里处理，不要把用户事件建模成 LaunchedEffect。
2. **每帧变化的 Effect 键**：会反复取消并重启协程。键应表达协程需要跟随的输入。
3. **需要长期保留的资源**：不要绑定到会频繁离开组合的 DisposableEffect，应选择与资源真实生命周期一致的 owner。

**Q10: Fragment 页面销毁后 Compose 内容仍占内存，ComposeView 的组合策略该怎样选？**

ViewCompositionStrategy 决定 ComposeView 何时 dispose Composition。选项应匹配 View 的生命周期和是否复用。错误选择会造成泄漏，或在暂时 detach 时丢失状态。

1. **Fragment View**：通常在关联的 ViewTreeLifecycleOwner 销毁时 dispose，避免 Fragment 的 View 销毁后组合仍持有旧视图。
2. **不复用的窗口内容**：View detach 表示永久移除时可在 detach 时 dispose。
3. **池化容器**：RecyclerView 等容器可能临时 detach 后复用 View，应使用识别 pooling 的策略，不能把每次 detach 都当永久销毁。
4. **Default 策略**：当前默认策略按 detach 与 pooling 状态处理。需要跨临时 detach 保留时，应明确选取匹配宿主生命周期的策略。

**Q11: Compose 页面嵌入旧 View 控件后变卡，AndroidView 的 update 应避免做什么？**

AndroidView 将一棵传统 View 子树纳入 Compose 的布局与绘制，因此需要明确 View 创建、复用和状态同步职责。

1. **factory**：负责创建 View，避免在 update 中反复构造同一套控件。
2. **update**：Compose 状态更新时可再次执行，应只同步必要属性，不要重复注册 listener、启动昂贵计算或触发不必要的请求布局。
3. **高频跨栈同步**：View 与 Compose 之间频繁双向传值会增加桥接与布局成本，应明确单一状态源。
4. **可复用 View**：懒布局等复用场景应提供恰当的 onReset/onRelease 行为。若没有 reset 回调，AndroidView 不会按可复用 View 的方式重置实例。

优先让 View 承担它擅长的部分，例如成熟控件或复杂自绘，并把更新块限制为必要的属性同步。

**Q12: 自定义 Compose 宿主中的动画与界面更新不同步，Recomposer 为什么需要帧时钟？**

自定义宿主必须让 Recomposer 的帧等待与平台帧时钟相连，并为 Composition 设定明确的销毁生命周期。否则状态变化可能无法按帧调度，或 Composition 在宿主销毁后仍存活。

1. **帧时钟**：提供 MonotonicFrameClock，使 withFrameNanos 等等待者由宿主的帧回调恢复，重组能与帧更新节奏协调。
2. **重组职责**：Recomposer 处理 invalidation、recomposition 与 applyChanges。它不替代 ViewRootImpl 的窗口测量、布局、绘制和 HWUI 提交。
3. **生命周期**：Composition 离开窗口或宿主生命周期结束时应 dispose。选择 detach 或 ViewTreeLifecycleOwner 销毁取决于宿主是否会复用。

**Q13: [learning] Compose 页面滚动掉帧，应先用哪些工具区分重组、布局和绘制瓶颈？**

按工具证据区分重组、布局和绘制阶段，再选择优化方向：

1. **观察范围**：Layout Inspector 查看重组次数和具体 composable。Compose trace API 标出自定义慢逻辑耗时。帧时间线/帧指标判断是否超过目标刷新率对应的预算，度量口径见 [流畅性度量](../12-performance/05-smoothness.md)。
2. **重组次数高**：检查不稳定参数、状态读取位置和无稳定 key 的列表。
3. **单次组合耗时高**：检查组合期重计算、列表筛选和排序。
4. **布局或绘制耗时高**：布局检查测量复杂度和深层结构。绘制检查重复记录、绘制期分配和大面积渐变/模糊。
5. **优化顺序**：先确认瓶颈阶段，不要未经测量就加缓存或更换渲染后端。

**Q14: 把旧页面从 View 迁到 Compose 后出现主题、状态或返回键问题，迁移时要检查哪些边界？**

1. **主题映射**：View 主题属性与 Material 主题并非一一对应。逐屏核对颜色、字号和组件默认值。
2. **状态寿命**：remember 只跨重组。需要跨 Activity 重建恢复的少量 UI 状态用 rememberSaveable，业务状态由合适的状态持有层恢复。
3. **回调生命周期**：View 绘制/可见性回调与 composable 生命周期不一一对应。依赖 OnDrawListener 等代码需要按 Compose 的状态和 Effect 模型重设计。
4. **返回与焦点**：在 Compose 中明确处理返回行为（如 BackHandler）和键盘焦点顺序，不能假设旧 View 的 onBackPressed 或焦点遍历规则会自动迁移。
5. **互操作边界**：AndroidView 内的 View 应接收 Compose 状态并通过事件回调上报用户动作，避免两边各自持有真值形成更新环。

**Q15: 同一页面混用 View 和 Compose 后尺寸与状态互相打架，怎样确定约束和状态的唯一来源？**

混用 View 与 Compose 时，布局双方必须共享明确尺寸约束，状态也只能有一个事实来源。否则尺寸误差会层层传递，重复状态会互相覆盖。

1. **约束传递**：父容器提供一致的可用边界，分别检查 View 的 MeasureSpec 和 Compose 的 Constraints。不要让一侧写死尺寸、另一侧又按另一套边界自适应。
2. **单向状态流**：指定唯一状态持有方。Compose 状态通过 AndroidView update 更新 View，用户事件从 View 回调回到状态持有方。
3. **性能归因**：混合页面经过两套布局/绘制桥接和 Android 渲染链路，应按 Compose 阶段、View 阶段及帧提交分别查证，不能预设瓶颈一定在某一侧。

**Q16: [learning] 普通 Compose 页面有自己的渲染引擎吗？AndroidUiFrameClock 的 Choreographer 回调与 ViewRootImpl 的 traversal 回调各做什么？**

Compose 有独立 UI runtime 与节点系统，但常规 Android 硬件加速窗口仍由 HWUI 完成图形渲染和提交。AndroidUiFrameClock 的 Choreographer 回调负责恢复帧等待者，ViewRootImpl 的 traversal 才驱动窗口布局、绘制和 HWUI 提交。

1. **AndroidUiFrameClock**：为 withFrameNanos 等待者注册帧回调，恢复 Recomposer 执行 recomposition 与 applyChanges。
2. **ViewRootImpl traversal**：运行窗口 measure、layout、draw。AndroidComposeView 在对应 View 回调中完成 Compose 布局绘制。
3. **图形提交**：ThreadedRenderer/RenderThread、BLAST BufferQueue、SurfaceFlinger 与 HWC 负责后续绘制提交和合成，Compose runtime 不独立替代这条 Android 图形链路。
4. **空闲调度**：Compose 1.11.4 的 AndroidUiFrameClock 回调不会在 doFrame 内永久自我重挂。是否继续申请帧取决于新的 withFrameNanos 等待者。因此不能把它当作持续空转的帧循环估算功耗。

**Q17: Compose 中同一状态被 composable 函数体、placement lambda 与 draw lambda 读取，状态变化分别触发什么？Recomposition 一定带来重绘吗？**

Snapshot 按读取所处阶段建立观察关系。失效从被读取的最早阶段开始传播，因此延迟读取可跳过不需要重跑的前置阶段。Recomposition、Layout 和 Drawing 不是绑定在一起的单一步骤。

1. **Composable 函数体**：读取状态会使相应组合范围进入 recomposition。变更结果可能进一步请求 Layout 或 Drawing。
2. **测量 lambda**：只让依赖状态的节点重新测量，通常可跳过 recomposition。
3. **放置 lambda**：只让受影响节点重新定位，通常可跳过测量。
4. **draw 与 graphicsLayer lambda**：使对应绘制范围或 layer 属性失效，可跳过 recomposition 和 layout。内容未变化时绘制记录可能继续复用。
5. **阶段边界**：recomposition 不必然导致重绘，drawing 也可以在没有 recomposition 时发生。把动画或滚动值从函数体移到 offset {}、graphicsLayer {} 等阶段 lambda，可令失效从较晚阶段开始，但要用 trace 验证收益。
6. **编译器规则**：Strong Skipping 是 Compose Compiler 模式，Kotlin 2.0.20 起默认启用。不稳定参数的比较和 lambda 自动记忆受编译器规则影响，不是 Android 平台 API 版本边界。
7. **派生结果**：derivedStateOf 适用于输入频繁变化但观察结果较少变化的场景。它仍有依赖追踪与计算成本。

**Q18: [learning] Compose 的每个 LayoutNode 都对应一个 Android RenderNode 吗？Modifier.graphicsLayer 一定创建离屏纹理吗？**

LayoutNode 与 RenderNode/GraphicsLayer 是不同层次的对象，不是一一对应。graphicsLayer 建立绘制属性和合成边界，也不必然创建离屏缓冲区。离屏需求由内容效果与 CompositingStrategy 决定，会增加中间纹理、像素填充和 GPU 内存带宽成本。

1. **节点与绘制记录**：LayoutNode 表示 Compose UI 布局节点。很多 Text、Row、Column 不各自持有 RenderNode，绘制操作记录到所属 layer。OwnedLayer/GraphicsLayer 是可复用绘制记录的边界，子节点变化可能令所属 layer 重录。
2. **直接 layer 属性**：平移、缩放和旋转通常由 RenderNode/layer 属性处理，不需离屏纹理。
3. **Auto 合成策略**：CompositingStrategy.Auto 是默认策略。alpha 小于 1 且内容有重叠风险时可能离屏。RenderEffect、特定 BlendMode/ColorFilter 效果也可能要求 Offscreen。Compose 1.11.4 对相关效果采用强制 Offscreen 路径。
4. **显式策略**：CompositingStrategy.Offscreen 强制中间缓冲区。ModulateAlpha 将 alpha 施加到各绘制指令以避免离屏，但重叠内容的视觉结果可能不同。
5. **性能判断**：离屏会增加中间纹理、像素填充、带宽与 GPU 内存压力。应结合实际 layer 大小和效果测量，不能仅凭存在 graphicsLayer 就推断离屏。

**Q19: Compose 的 PausableComposition 分段执行的是什么工作？为什么它不能用来降低 GPU 负担？**

PausableComposition 把尚未投入使用的子 composition 分段执行，主要用于 Lazy 容器预取即将进入视口的 item。它调整预取工作落在哪些空闲帧，不会拆分可见页面的普通 recomposition，也不直接减少绘制或 GPU 工作量。

1. **暂停与继续**：空闲预算不足时暂停分段 composition，之后调用 resume() 继续。工作完成并 apply() 后，结果才加入布局树。
2. **版本引入**：PausableComposition 从 Compose Runtime 1.8.0-alpha02 引入，不是 Compose 1.7 稳定能力。
3. **预取开关**：Foundation 1.10.6 出于稳定性曾将 isPausableCompositionInPrefetchEnabled 设为 false。1.11.4 源码中为 true。需按实际 Foundation 版本核对该默认值。
4. **空闲估计**：预取调度器按 View.display.refreshRate 估算帧间隔，并以距上次 draw 超过两个帧间隔判断 idle。availableTimeNanos() 是库内估算值，不是 Choreographer 提供的精确 frame deadline。
5. **工作边界**：它改变预取 composition 的调度，不降低需要完成的组合总量，也不减少 draw、GPU 或 SurfaceFlinger 工作。
