# 渲染实战：View 与 Compose 基础

> 学习资料（文章模式沉淀）。主线：应用侧渲染优化的第一层——把布局失效与绘制失效分开，用列表复用与细粒度更新控制滚动帧成本，把 Compose 的重组、测量与文本排版归因到责任阶段后再动手。源文档：android-internals-wiki §22.1《View 布局与自定义绘制优化》§22.2《RecyclerView 与 Compose LazyList 性能》§22.3《Compose 性能、Compiler 与 Modifier.Node 诊断》§22.4《Compose 布局、测量与文字渲染》（材料按 Android 17 撰写）；requestLayout 的传播合并与"两轮封顶"、measureChildren 跳过 GONE、invalidate(Rect) 脏区自 API 21 起忽略、LayoutInflater 的 Factory 链与 merge 约束、ViewStub 替换语义、Canvas.drawRenderNode 的软件画布限制、ViewTreeObserver 分发条件等平台机制已按 AAOS13 源码（Android 13）核对并标注版本差异，RecyclerView 1.4.0、Compose BOM 2026.08.00（Runtime/Foundation/UI 1.12.0）、AsyncLayoutInflater 与 ConstraintLayout 等 AndroidX 外部库实践按材料口径转写、未本地核对、不确定处已弱化；官方文档口径沿用源材料标注转写、本次未联网复核。View/HWUI 管线与出图分型的机制层见 [../performance/07-渲染管线-基础与图形API.md](../07-performance/07-渲染管线-基础与图形API.md)，本文专注应用侧优化实战视角；VSync/Choreographer 调度、硬件层与 View 文本引擎的机制层见 [../rendering/01-渲染管线与VSync调度.md](../02-rendering/01-渲染管线与VSync调度.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 列表 item 里的一个 TextView 改文字并调用 requestLayout()，为什么一次调用不等于一帧只做一轮完整测量布局，ViewRootImpl 是怎么合并和二次调度的？**

requestLayout() 先清空自身测量缓存并沿父链传播，已经排程的遍历会合并，但布局过程中再次提出的请求可能让同一帧补跑一轮 measure/layout，补轮中再来的请求则延后到下一帧——同帧最多两轮。机制（AAOS13 的 `View.java` 与 `ViewRootImpl.java` 已核对）：`View.requestLayout()` 清空 `mMeasureCache`，置 `PFLAG_FORCE_LAYOUT` 与 `PFLAG_INVALIDATED`，只向尚未请求布局的父级（`!mParent.isLayoutRequested()`）继续传播；到达 ViewRootImpl 后置 `mLayoutRequested` 并 `scheduleTraversals()`，未执行的 traversal 只排程一次，重复请求自然合并；若调用发生在布局过程中，`requestLayoutDuringLayout()` 把请求记入列表并在本轮收尾统一补一轮布局，`mHandlingLayoutInLayoutRequest` 期间再来的请求直接 post 到下一帧，防止无限递归。`performTraversals()` 按窗口与树状态决定是否执行 measure/layout/draw，三段并非每帧固定全跑。

做法：判断布局成本要看 trace 中 `measure`/`layout` 切片的次数与时长（`FrameMetrics.LAYOUT_MEASURE_DURATION` 合并报告本帧被标记需要更新的部分，AAOS13 已核对字段存在），不能拿调用次数当遍历次数；`onLayout()` 内再次 `requestLayout()` 是连续多轮的常见来源，优先消除。

**Q2: 压平一个首屏布局前应优先识别哪三类结构性成本，为什么 View.GONE 子节点被 measureChildren 跳过却仍建议延迟创建？**

三类成本是重复测量的容器、高频出现的列表项、提前创建的低频内容；GONE 跳过的只是测量与绘制，省不掉已完成的 inflate 和父容器每轮遍历，所以低频大块内容更适合 ViewStub 延迟创建。机制：标准 `ViewGroup.measureChildren()` 只对非 GONE 子节点调 `measureChild()`（AAOS13 已核对），但对象与子树在 inflate 时已经创建，父容器仍要遍历并检查状态，自定义容器还可能采用不同测量规则；`RelativeLayout`、带 `layout_weight` 的 `LinearLayout` 与复杂约束容器可能让子 View 被测量多次，层级一深沿树放大。处理顺序按收益稳定性排：先改首屏和高频列表 item，再改被多处复用的公共布局，低频深层布局按线上 trace 证据处理。

容器选型与证据边界：多个 View 需要对齐、基线、比例、Barrier 或 Chain 时 `ConstraintLayout` 通常比嵌套容器合适；2 到 3 个子 View 的简单线性排列继续用 `LinearLayout`/`FrameLayout`；列表 item 简单时约束求解的固定开销可能抵消层级收益，要实测。Google 2017 年注册表单实验（Nexus 5X、Android 8.0、ConstraintLayout 1.0.2）测得约 40% 平均测量布局耗时下降，这是特定页面、设备与版本的 100 帧平均值，不能当作所有项目的固定收益比例；验收应看 P50/P90/P95 分位数、调用次数与 FrameTimeline overrun，没有跨刷新率通用的毫秒阈值。

**Q3: ViewStub、merge、include 分别解决什么问题，哪些误用会直接抛异常或让优化失效？**

`ViewStub` 延迟创建低频子树，`<merge>` 消除组合 View 或被 include 布局里的多余根容器，`<include>` 只复用 XML 结构；典型误用是反复对同一个 ViewStub 调 inflate、给 `<merge>` 传 null root 或 `attachToRoot=false`（直接抛 `InflateException`）、指望 `<include>` 减少 inflate 成本。机制与边界（AAOS13 的 `ViewStub.java` 与 `LayoutInflater.java` 已核对）：

- **ViewStub**：首次 `inflate()` 经 `replaceSelfWithView()` 把自己从父容器移除并插入真实 View，stub 随即不存在，不能二次 inflate；应缓存替换后的 View，后续切换只改 `visibility`，引用放在 Fragment 字段时要在 `onDestroyView()` 清空。首次显示发生在调用点同步执行，不要放进动画关键帧。
- **merge**：没有可独立返回的根节点，`LayoutInflater` 要求 root 非空且 `attachToRoot=true`，否则抛 `InflateException`（AAOS13 源码原样报错），因此不适用于先构建、稍后 attach 的预加载方案。
- **include**：走 `parseInclude()` 仍要解析资源并创建 View，只是工程复用；降层级需要配合 `<merge>`，是否有收益取决于运行时少创建、少测量了什么。

**Q4: 把布局解析挪到 AsyncLayoutInflater 后台线程后，为什么主线程仍可能出现 inflate 时间，哪些布局会回退到 UI 线程？**

一次 inflate 由三段构成：读取编译后的 binary XML 并处理 include/merge/theme 等标签，经 `Factory2 → Factory → private factory` 链构造 View，递归组装子树并生成 LayoutParams；AsyncLayoutInflater 只把可后台构造的部分移走，构造条件不满足时整段回退 UI 线程。机制：进程级构造器缓存只省类查找，省不掉 `Constructor.newInstance()`、style、字体、Drawable 与自定义初始化，首次进入页面还有类加载，所以冷启动与预热后的 trace 要分开比。回退条件（AndroidX 1.1.0 口径，外部库未本地核对）：父容器的 `generateLayoutParams(AttributeSet)` 必须线程安全，被创建 View 的构造过程不能创建 Handler 或调 `Looper.myLooper()`；默认不支持 `LayoutInflater.Factory`/`Factory2` 与含 fragment 的布局，可用 `AsyncLayoutFactory`/`AsyncAppCompatFactory` 补 AppCompat 场景。

接入方式：默认重载的回调在 UI 线程执行，返回的 View 尚未加入 parent，要在回调里 `addView()`；1.1.0 起提供 callback executor 重载，回调不在 UI 线程时操作 View 必须先切回。验收看 trace：使用后主线程应只剩较短的 addView/bind 区间，若仍出现完整 inflate 说明发生了回退；同时记录预创建未命中率与取消数，避免用更多 CPU 和内存换很少的命中。数据绑定、图片解码与网络请求不在这条路径的收益范围内。

**Q5: 自定义 View 用缓存的期望尺寸跳过 onMeasure 计算，为什么只比较宽度 MeasureSpec 不够，onLayout 里只看 changed 参数会漏掉什么？**

测量结果由两个 `MeasureSpec`、内边距、数据版本、字体、布局方向、子 View 可见性等共同决定，任何一项变化都可能改变期望尺寸；`changed` 只表示当前 View 自身边界是否变化，子 View 尺寸、可见性、外边距或业务排序变化时，父 View 边界未变也需要重新摆放子节点。机制参照：`View.measure()` 自身的缓存键就是宽度与高度两个 spec 加强制布局标记（AAOS13 已核对：spec 未变且双 `EXACTLY` 且尺寸吻合时早退），自定义缓存至少要覆盖同样完整的输入，并且只保存纯计算的几何结果，不能绕过 `setMeasuredDimension()` 或破坏父容器约束。

```java
if (widthSpec != mLastWidthSpec || heightSpec != mLastHeightSpec
        || mContentVersion != mLastContentVersion || paddingChanged()) {
    computeGeometry(widthSpec, heightSpec);   // 只做 CPU 计算
    // ... 记录全部输入
}
setMeasuredDimension(resolveSizeAndState(mDesiredWidth, widthSpec, 0),
        resolveSizeAndState(mDesiredHeight, heightSpec, 0));
```

做法：给缓存加内容版本号，数据、字体或地区设置影响期望尺寸时递增版本并调用 `requestLayout()`，只改颜色不递增；`onLayout()` 读取与完整输入绑定的坐标缓存，不要在摆放过程中再次请求布局。反例：只比较宽度 spec 时，padding 变化后仍复用旧高度，得到错误布局；同一帧出现多轮测量时要查调用栈，常见原因就包括 `onLayout()` 里触发 `requestLayout()`、父子约束互相依赖和 `wrap_content` 协商。

**Q6: 波形图这类持续刷新的自绘 View 应怎样组织绘制对象与失效调用，invalidate()、requestLayout()、postInvalidateOnAnimation() 各承担什么？**

绘制对象在构造或尺寸变化阶段创建与更新，`onDraw()` 只提交绘制命令；只改像素用 `invalidate()`，尺寸契约变化才加 `requestLayout()`，自绘动画用 `postInvalidateOnAnimation()` 对齐显示帧节奏，并在 detach、不可见或终止条件到达时停止。机制与边界（AAOS13 已核对）：硬件加速时 UI 线程把绘制命令记录进 RenderNode 的 DisplayList，内容未失效的 View 复用已有记录，`onDraw()` 本身仍在 UI 线程执行；`invalidate(Rect)` 自 API 21 起传入的脏区被完全忽略（源码 `@Deprecated` 注释明确），缩小重录范围要靠拆分 View 或手动 RenderNode，不能靠传矩形；`postInvalidateOnAnimation()` 依赖 `AttachInfo`，未 attach 时调用不会安排任何刷新。热路径分配（临时 String、装箱、每帧 `new Path`/`new RectF`、格式化）增加 GC 负载，但少量分配是否有害要用 allocation recording 与帧数据证明，不用对象数量阈值代替测量。`withLayer()` 在动画期间经 setup/cleanup 临时切硬件层（AAOS13 已核对实现），内容保持不变的短时 alpha/transform 动画有收益；动画同时修改内容并 `invalidate()` 时图层仍要重栅格化，收益消失。采样持续高速到达时应在上游合并更新，避免每收到一个点就重建整条 Path。

**Q7: RecyclerView 稳定滑动中偶尔出现 onBindViewHolder 是否说明复用失效，ViewHolder 到底按什么顺序获取、哪些来源仍会 bind？**

不能说明；出现 bind 只代表当前 holder 需要绑定——scrap 取回重绑、缓存条目标记 update、或从 Pool 取出后重置状态，都与复用是否失效无关。查找顺序（RecyclerView 1.4.0，AndroidX 外部库，按材料口径）：`tryGetViewHolderForPositionByDeadline()` 依次查 changed scrap（pre-layout 按 position 或 stable ID 找变化前 holder）、attached scrap/hidden child/`mCachedViews`（先按 position 再校验 viewType 与 ID）、stable ID 二次查找、`ViewCacheExtension`、`RecycledViewPool`，最后才新建。机制：`mCachedViews` 默认请求上限是 2，启用预取时实际上限还会加上 LayoutManager 观察到的预取数量；Pool 默认每个 viewType 保留 5 个，可用 `setMaxRecycledViews()` 调整；attached scrap 只是布局期间暂时分离的 holder，不保证只复用不 bind。边界：口口相传的"四级缓存"不是固定顺序表，changed scrap 与 stable ID 二次查找最常被漏掉；`setHasStableIds(true)` 只适合每个 item 有唯一且稳定业务 ID 的列表，能帮更新和动画识别同一 item，但不能替代 DiffUtil，也修复不了错误的 viewType 设计。

**Q8: 多个嵌套横向列表共享同一个 RecycledViewPool 时，viewType 应按什么原则划分，共享池会引入什么新风险？**

viewType 按"ViewHolder 结构与绑定规则是否可安全交叉复用"划分，只差文案、图片或按钮状态的条目应共用同一类型；共享池只按整数 viewType 分桶、不认识 Adapter，两个 Adapter 用同一整数却创建不同布局时，交叉复用会导致类型转换异常、错误绑定或残留状态。机制（RecyclerView 1.4.0 材料口径）：Pool 命中后通常要 bind，因为 holder 的内部状态需要重置；`setMaxRecycledViews()` 只改上限、不预创建，容量按"同屏子列表数 × 每子列表可见卡片数"估算并用 trace 验证，过大会增加 View、图片引用与内存占用。做法：结构一致的子列表调用 `setRecycledViewPool(sharedPool)`，并给 `LinearLayoutManager` 设 `setRecycleChildrenOnDetach(true)` 让分离时回收 child（会改变图片、播放器等资源清理时机，要随页面生命周期验证）；共享池同时共享按 viewType 统计的 create/bind 历史均值，多个 Adapter 构造成本悬殊时会影响彼此的预取预算判断。按颜色、角标等业务状态拆类型会把回收池切成许多小桶，滑动中更容易重新 `onCreateViewHolder`。

**Q9: 高频局部刷新的列表为什么 notifyDataSetChanged 会卡，DiffUtil 的三个回调与 payload 各承担什么，ListAdapter 有哪些硬边界？**

`notifyDataSetChanged()` 不说明哪些条目变化，LayoutManager 必须重新绑定并布局全部可见 View；DiffUtil 把新旧列表差异拆成细粒度更新——`areItemsTheSame()` 定业务身份、`areContentsTheSame()` 定是否需要重绑、`getChangePayload()` 描述局部变化让 Adapter 只更新受影响的 View。机制：DiffUtil 用 Myers 差分算法求最短插入/删除序列，复杂度 O(N + D²)，开启 move detection 后增加扫描成本；`ListAdapter`/`AsyncListDiffer` 在后台 executor 计算 diff、结果回主线程分发，被新提交取代的旧计算不会应用。硬边界（材料口径）：payload 只优化局部绑定，目标 holder 未 attach 时 payload 可能被丢弃，完整 bind 必须能仅凭当前 item 恢复全部 UI 状态（清理旧图片、选中态、监听器）；`AsyncListDiffer` 对相同 List 实例直接返回，原地修改旧列表再 `submitList()` 可能不触发任何更新，提交的列表与参与比较的字段应视为不可变；`areContentsTheSame()` 不要带入与 UI 无关的字段（时间戳、调试标记）；数据源有序且 item 不交换位置时可自行 `calculateDiff(callback, false)` 省移动检测，但要自管提交批次与主线程分发。stable ID 能在整表刷新时为可见项推导部分结构变化维持动画，但不省这次完整重绑。

**Q10: RecyclerView 的 GapWorker 预取运行在哪个线程，willCreateInTime/willBindInTime 怎么决定做不做，为什么预取正常下一帧仍可能长？**

GapWorker 不是后台线程：它经 `RecyclerView.post()` 把预取任务投进主线程消息队列，用最近一次绘制时间加刷新周期估算下一帧 deadline，预取的 create 和 bind 都占主线程时间。机制（RecyclerView 1.4.0 材料口径）：滚动路径先 `postFromTraversal()` 记录方向与距离再投递；普通任务按 Pool 中同 viewType 的历史耗时判断预计能否在 deadline 前完成，预计下一帧就要用的任务标为 forced 跳过这两个估算，但工作仍在主线程执行；预取只覆盖 holder 获取、create 与 bind，不替下一帧做 item 的 measure/layout——预取切片正常而 `RV OnLayout` 或 framework `measure`/`layout` 仍长时，应转向 item 尺寸、布局结构与图片触发的 `requestLayout()`。配置边界：`setInitialPrefetchItemCount()` 只对嵌套 RecyclerView 首次进入视口前的初始预取生效，值取首次可见 item 数附近，设大不提高流畅度只增加 bind 与对象；`setItemViewCacheSize()` 增大 `mCachedViews` 可减少短距回滑 bind，代价是持有更多引用，应先看 create/bind、内存与 GC 数据。版本：RecyclerView 1.4.0 要求 `compileSdk >= 35` 并在 API 35+ 上把滚动速度经 `View.setFrameContentVelocity()` 交给平台刷新率策略，AAOS13 无该平台 API；Android 17 对 targetSdk 37 启用的无锁 MessageQueue（DeliQueue）只消除入队锁竞争，不改变预取的主线程属性，AAOS13 无此机制。

**Q11: 纵向 Feed 里嵌横向卡片列表时应怎样配置容器，setHasFixedSize(true) 的确切语义是什么？**

先避免无界测量（不要把 RecyclerView 放进纵向 `NestedScrollView` 后让它按全部内容高度测量，否则一次准备大量 item、回收优势消失），结构兼容时共享 `RecycledViewPool` 并按首次可见卡片数设置 initial prefetch，容器尺寸不受数据影响时才 `setHasFixedSize(true)`。机制：`setHasFixedSize(true)` 表示 Adapter 内容变化不会改变 RecyclerView 自身的测量宽高，不要求每个 item 等高、也不阻止 item 内部重新测量布局；RecyclerView 自身 `wrap_content` 且增删 item 会改变容器尺寸时不应开启。其他边界：纵向套纵向会增加触摸分发、NestedScrolling 协商与测量复杂度，页面能由单个列表的多种 viewType 表达时用 `ConcatAdapter` 合并；高频局部刷新页面可只关 `SimpleItemAnimator` 的 `supportsChangeAnimations`——payload 已缩小 bind 范围后，change animation 仍可能让新旧 holder 同时参与过渡，A/B 对比确认 `RV OnLayout` 与 bind 下降且视觉无损后按页面收窄，不要全局一刀切。

**Q12: LazyColumn 的 key 与 contentType 分别对应 RecyclerView 的什么概念，从 RecyclerView 迁移到 LazyColumn 应比较哪些指标？**

key 对应业务身份（承担 stable ID 的角色，要求稳定、唯一且可由 `Bundle` 保存以支持 `rememberSaveable`），contentType 对应 viewType 与 Pool 的结构兼容分组——默认 `null` 表示全部同型，Compose Foundation 1.12.0 内部按类型最多保留 7 个可复用槽位，这属于实现细节而非 API 契约（AndroidX 外部库，未本地核对）。迁移判断比较四组指标：慢帧率、P95 帧耗时、内存峰值、首屏可交互时间，用同一设备、同一数据集和同一交互脚本，不用固定结论。机制：未提供自定义 key 时按位置维护身份，显式 `key = { index -> index }` 与位置身份没有行为差别；头部插入后位置键仍在但对应业务对象已变，`remember` 状态可能跟错数据、内层滚动位置可能错行；项目滚出组合后普通 `remember` 值丢失。items 内容里反复排序、过滤或创建大对象会随重组重复发生，应移到列表外或 `remember` 缓存。边界：把业务 ID 当 contentType 会阻断跨项目复用，把结构差异大的条目都留 `null` 会让运行时在不相似内容间复用；预取（含 PausableComposition 可暂停预组合，Foundation 开关默认值随版本变化、1.12.0 源码为 true）与 GapWorker 一样都在主线程调度，不能照搬 RecyclerView 的缓存数量参数。

**Q13: Compose 状态在 Composable 函数体、placement lambda、draw lambda 与 graphicsLayer lambda 中被读取，变化后分别触发哪个阶段的工作？**

运行时按读取位置记录依赖：函数体读取走 Composition（结果变化可继续进入 Layout/Drawing），placement（`Modifier.offset { }`）读取只重新放置，draw lambda 只重新绘制，`graphicsLayer { }` 回调只更新图层属性；把高频值延后到满足 UI 语义的最晚阶段是控制重组范围的基本手段。机制（Compose 1.12.0，AndroidX 外部库材料口径）：三阶段是失效的起点，不代表每个显示帧都完整执行三遍——Composition 输出没变时 Layout/Drawing 可跳过；`LazyColumn`、`BoxWithConstraints`、`SubcomposeLayout` 在布局过程中才决定子内容，不能套用"组合总在布局前一次完成"的模型。

```kotlin
Modifier
    .offset { IntOffset(0, listState.firstVisibleItemScrollOffset / 2) }
    .graphicsLayer { this.alpha = alphaState.value }
```

两个高频读取都不再由 Composable 函数体执行。边界：`offset { }` 省掉的只是组合与测量，位置变化仍执行放置；视觉平移不能冒充布局位置——点击区域、父布局占位或无障碍边界需要随位置变化时必须用布局表达；状态影响节点数量、文本或语义属性时 Composition 无法省略，只能缩小读取范围（封装进 lambda、下移到更靠近使用的 Composable、拆小重组作用域）。

**Q14: Strong Skipping 默认开启后，List 这类 unstable 参数的 Composable 还能跳过吗，@Stable/@Immutable 注解还承担什么？**

能跳过——Strong Skipping 自 Kotlin 2.0.20 起默认启用（编译器行为，随 Kotlin 版本而非平台 API），所有可重启 Composable 都生成跳过逻辑，稳定参数用 `equals()` 比较、不稳定参数用引用 `===` 比较；注解不再是"能否跳过"的开关，而是开发者契约：公开属性变化必须能被 Compose 观察、同一对实例的 `equals` 结果保持一致。机制：传入内容相同但新建的 `List` 时引用不同，仍会重组；原地修改同一个可变集合并保持引用时可能被跳过，且普通集合不向 Snapshot 系统发通知，界面可能保留旧内容——修复方向是不可变 UI 模型、`SnapshotStateList` 或新实例流转，不是虚假注解。边界：`skippable` 只表示"允许跳过"，是否真跳过还取决于重启组是否失效、参数比较结果、组结构与内部状态读取，不能用编译器 CSV 统计运行时跳过率；lambda 会被自动记忆（稳定捕获按 equals、unstable 捕获按 `===`，`@DontMemoize` 可退出）；对象含不可观察的可变字段时不能标注 `@Immutable`，错误契约导致应执行的更新被跳过；非 `Unit` 返回值或 `@NonSkippableComposable` 的函数不生成跳过逻辑。

**Q15: Compose 编译器报告里 skippable=1 且 unstable 数量下降，能据此宣布重组性能改善吗？各报告文件分别能回答什么？**

不能；编译器报告只回答"编译器生成了什么"——类型稳定性推断、函数是否 restartable/skippable、模块统计与 featureFlags，运行时重组次数要 Layout Inspector 或 Composition Tracing，用户可见结果要 FrameTimeline 或 Macrobenchmark。文件分工（Kotlin 2.3.20/2.4.10 材料口径）：`reportsDestination` 输出 `*-classes.txt`（类型及属性稳定性）与 `*-composables.txt`/CSV（函数标签、参数稳定性）；`metricsDestination` 输出 `*-module.json`（模块统计与 `featureFlags`，是解释其他数字的必要条件）。解析细节：CSV 布尔列用 0/1、没有名为 `params` 的列，首列虽名为 package 写的是函数完全限定名。做法：报告应在 release 变体上生成，比较前固定 Kotlin 版本、变体与 featureFlags；Kotlin 升级可能改变稳定性判定（2.4.10 修正了部分 stable 判定为运行时稳定性或 Uncertain），升级后必须重新生成基线再解释标签变化。反例：unstable 数量下降但热点函数每次重组都新建 List 实例，运行时重组可能不降反升；优化结论至少包含一项静态证据加一项运行时证据，"全模块全部 skippable"的门禁不值得追求。

**Q16: remember(keys)、derivedStateOf、produceState 与 snapshotFlow 各适合什么场景，混用会出什么问题？**

`remember(keys)` 按显式 key 缓存一次计算，`derivedStateOf` 把高频输入收敛为低频 State，`produceState` 把外部数据转成 State（key 变化取消旧 producer、启动新 producer），`snapshotFlow` 把 Snapshot 状态读取转成冷流驱动副作用。各自边界（Compose 1.12.0 材料口径）：

- **remember**：key 是缓存身份，传入原地修改的普通列表时不会重新计算——问题在数据所有权与可观察性，不在多加一层 remember。
- **derivedStateOf**：适合"输入每次变、输出只在跨界变"（如滚动是否越过首项）；它维护依赖表与缓存，本身有成本，字符串拼接、数值乘法这类输出每次都变的表达式直接计算更省；每次重组新建派生状态同样错误，应配合 `remember` 保留实例。
- **produceState**：返回的 State 由 `remember` 保留，key 变化不会把它重置为 `initialValue`，切换用户要立即显示"加载中"须在新 producer 开头显式赋值；State 合并相等值，连续快速写入时观察者可能跳过中间值；回调式数据源用 `awaitDispose` 注销。
- **snapshotFlow**：代码块在只读 Snapshot 中执行、按 `equals` 过滤、可能跳过中间状态，适合观察"当前状态"，不适合统计每次点击或传感器样本。

Strong Skipping 不管理 producer 协程，协程的启动、取消与异常处理仍由 Effect key 与作用域生命周期决定。

**Q17: 自定义 Modifier.Node 时 NodeChain 按什么规则复用、更新或替换节点，Element 的 equals 契约写错会怎样？**

NodeChain 对新旧 Element 序列做差分：相等则复用 Node 且不调 `update()`，运行时类型相同但不相等则复用 Node 并调用新 Element 的 `update(node)`，类型不同则移除旧 Node 并创建新 Node；Element 的 `equals()`/`hashCode()` 必须覆盖所有会改变 Node 行为的输入——遗漏字段会让界面保留旧配置，错写相等会让运行时跳过本应执行的 `update()`。机制（Compose UI 1.12.0 材料口径）：Element 是重组时可重建的轻量配置，Node 是附着在 `LayoutNode` 上可跨重组复用的运行对象，配置放 Element（参数型用 data class 最稳）、状态放 Node；`update()` 只同步字段即可，`shouldAutoInvalidate` 默认 true 会按节点实现的接口自动失效（Draw 接口失效绘制、Layout 接口失效测量），再无条件手动调 `invalidateDraw()` 属于重复表达。边界：Node 复用不等于 Element 零分配，工厂函数仍可能创建轻量 Element；频繁变化的运行状态放进 Element 会放大分配；差分规则属内部实现，升级 Compose 后按目标版本复核。关闭自动失效（`shouldAutoInvalidate = false`）后，每类输入变化都要对应调用 `invalidateDraw()`/`invalidateMeasurement()`/`invalidatePlacement()`，遗漏即 UI 不更新——只有剖析证明无效阶段调用值得优化且测试覆盖每个分支时才用。

**Q18: Modifier.composed 与 @Composable Modifier 工厂相比 Modifier.Node 慢在哪，Node 的 onAttach/onDetach/onReset 与 coroutineScope 怎么用？**

`composed` 的 Element 应用到布局前要经 `Composer.materialize()` 展开、每个应用位置都进入组合生成实际 Modifier 链，状态生命周期绑组合槽位而非 Node 的附着期，组合工作与中间对象更多；Node 把配置与运行状态分开，附着期任务用自带的 `coroutineScope`，复用清理放 `onReset()`。生命周期语义（Compose UI 1.12.0 材料口径）：`onAttach()` 后可访问 owner 与 `coroutineScope`，作用域在 `onDetach()` 后取消，同一 Node 仍可能再次附着；`onReset()` 在节点即将进入复用池时调用（如 Lazy 列表项滚出视口），须清理焦点、按压、拖拽进度等数据项级状态；Node 内不能调 `LaunchedEffect`，`onAttach()` 时同轮其他节点未必处理完，观察整树最终状态用 `sideEffect` 延后。`@Composable` 工厂的额外限制：返回值非 `Unit` 的 Composable 不可被跳过，即使输入稳定也会随调用者重组执行；它读取的 `CompositionLocal` 来自调用位置，而 Node 实现 `CompositionLocalConsumerModifierNode` 后用 `currentValueOf()` 读取附着位置的值（阶段外读取用 `observeReads`，且通知后要重新读）。选型顺序：只组合现有 Modifier 用普通工厂；需要新绘制/测量/语义/输入行为才写 `ModifierNodeElement` + Node；官方已把 `composed` 标为不再推荐但未删除，旧代码按热点数据安排迁移，低频页面无须强制。

**Q19: 自定义 Compose Layout 里对同一个子节点用两组约束连续调用 measure() 会发生什么，单遍测量协议包含哪些约束？**

运行时会抛异常——单遍测量协议要求每个 `Measurable` 在一次布局过程中只被 `measure()` 一次：父节点传一组 `Constraints`，保存返回的 `Placeable`，再在 `layout(width, height) { }` 块中放置。机制（Compose 1.12.0 材料口径）：`Placeable` 保存测得宽高供放置阶段使用，为它建列表是实现的明确成本，不必为"零对象"删掉必要状态；宿主层面 `AndroidComposeView.onMeasure()` 把 View 的 `MeasureSpec` 转成 Compose `Constraints` 并触发根测量，`onLayout()` 与 `dispatchDraw()` 前完成待处理的测量放置，Compose 三阶段嵌在宿主 ViewRootImpl 遍历中，与平台各用一套 API 名称。做法与边界：需要两遍协商（先测主体再决定覆盖层、按内容定父尺寸）时用 `SubcomposeLayout` 或固有尺寸查询表达，不要绕过协议；测量 lambda 内不做业务取数、排序、字符串解析、图片解码与日志格式化；父节点读取子节点的 `AlignmentLine` 对齐线时会建立依赖，子节点对齐线变化会触发父级重新测量或放置，排查父布局频繁重测时要检查是否读取了对齐线，不能只看传入约束。

**Q20: Compose 节点上一帧测过的尺寸什么时候会被复用，@Stable 注解会影响布局缓存吗？**

`MeasurePassDelegate` 在节点没有 `measurePending` 标记、且本次 `Constraints` 与上次相同时直接复用已有结果，否则执行 `performMeasure()` 并通过 Snapshot 观察器记录测量代码读取的状态；这套机制跨帧生效，不是"同一帧缓存一个结果"。`@Stable` 属编译器稳定性契约，不参与约束比较、也不清除 `measurePending`，只可能通过减少重组间接减少布局失效；错误标注反而会让 UI 漏更新。机制与边界（Compose UI 1.12.0 材料口径）：标记分两组——`measurePending`（尺寸需重算）与 `layoutPending`（位置需重算），由 `MeasureAndLayoutDelegate` 按树深维护，父节点已处于待测状态时子节点通常不再重复登记；约束相同也不保证子树无工作，子节点因自身状态读取失效时由 `forceMeasureTheSubtree()` 处理；失效传播没有固定的逐级父链，取决于节点是否放置过、尺寸是否变化与对齐线、前瞻布局依赖。排查顺序：先确认状态读取阶段，再检查约束是否稳定（窗口、Insets、字体缩放），最后才考虑稳定性注解。

**Q21: 对 LazyColumn 或 BoxWithConstraints 查询 IntrinsicSize.Min 为什么会直接失败，固有尺寸查询的真实成本怎么评估？**

`SubcomposeLayout` 使用 `NoIntrinsicsMeasurePolicy`，父级经 `IntrinsicSize.Min/Max` 查询会直接失败——这类组件在测量时才决定组合哪些内容，组合之前不存在可靠的固有尺寸；普通自定义 Layout 未覆写固有尺寸方法时得到的是近似默认实现。机制：固有尺寸查询发生在正式测量之前，官方保证它不会把同一子节点正式测量两次，但查询本身要递归询问相关子树，文本固有尺寸会运行段落宽高计算，成本取决于布局实现、查询方向与节点数量，不能统一写成 O(depth) 或"一次完整子树测量"。做法与边界：需要"与父尺寸匹配"时改外层布局的测量顺序或给组件明确约束，对 `LazyColumn` 调 `height(IntrinsicSize.Min)` 得不到低成本的列表总高度；覆写固有尺寸方法要与正式测量语义一致、同输入同结果、不含 I/O 或可变集合访问，并注意字体缩放与布局方向造成的失效；把固有尺寸结果缓存在业务层要考虑 `Density`、`fontScale`、文本内容等失效输入。结构只在少数尺寸断点变化时，先归纳为紧凑/展开模式统一处理，避免每个列表项各自套一层 `BoxWithConstraints`。

**Q22: padding + background + clickable 组合会创建几个 LayoutNode，调整 Modifier 顺序为什么属于行为变更而不是纯性能优化？**

一个——Modifier 元素创建的是 `Modifier.Node`（布局、绘制、输入各归其类），不会为每项新建 `LayoutNode`，但长链仍增加节点更新与遍历成本；顺序决定约束传递与绘制范围的语义，`padding(16.dp).size(40.dp)` 与 `size(40.dp).padding(16.dp)` 接收和传递的约束不同、最终外部尺寸可能不同，`clip().background()` 与 `background().clip()` 的圆角覆盖范围也不同。机制（Compose UI 1.12.0 材料口径）：`LayoutNode` 持有 `NodeChain`，实现 `LayoutModifierNode` 的节点获得 `LayoutModifierNodeCoordinator`，在被包裹内容的外层参与约束转换、测量与放置；1.12.0 把差分用的临时列表改为 `MutableObjectList` 并复用遍历栈，这是减少分配的实现调整，不合并节点、也不改变链的顺序语义。做法与边界：`Modifier.then()` 只连接两段 Modifier，不合并相邻节点，不能当优化接口；调整顺序前先确认布局、绘制、输入与无障碍语义的变化再测性能；每个 Modifier 按能力类型分析成本，不能把每个节点都计成一层布局树。

**Q23: 同样的 Compose Text(String) 在什么条件下走简化实现 ParagraphLayoutCache，哪些能力会让它改走 AnnotatedString 路径，段落缓存什么时候复用？**

未启用文本选择、没有 `onTextLayout` 回调、未启用 autoSize 三个条件同时满足时，`BasicText(String)` 用 `TextStringSimpleNode` 加 `ParagraphLayoutCache`（只在无障碍请求布局结果时才创建完整的 `TextLayoutResult`）；需要选择、布局回调或自动字号就转 `AnnotatedString`，改走 `TextAnnotatedStringNode` 加 `MultiParagraphLayoutCache`（Compose Foundation 1.12.0，AndroidX 外部库）。机制：节点把布局属性与绘制属性分开比较——字号、字体、行高、maxLines、约束等布局属性变化重新测量，颜色、画刷、阴影等纯绘制变化保留排版只重绘；内容相等的新建 `AnnotatedString` 不会因对象身份不同就使布局失效。复用条件：宽度不变且新高度仍能容纳旧段落时断行不变、可复用已有段落，最大宽度变化必须重排，所以列表项宽度在滚动中保持稳定对文本成本最有利；字体异步解析返回目标 Typeface 后旧缓存过期重测。边界：Android 实现里文本满足简化条件且单行放得下时用 `BoringLayout`，否则用 `StaticLayout`（判定入口在 Compose 节点，与平台 TextView 的快慢路径同源）；`TextAutoSize.StepBased` 用二分查找选字号，每个候选都试排、收敛后还会再增大一步，长列表大面积启用要记录试排成本；给每个列表项长期加 `onTextLayout` 只为日志会放弃简化路径，回调里也不要用布局结果改影响本次布局的输入（如按 lineCount 调 maxLines 会形成反复测量）。

**Q24: 用 rememberTextMeasurer 在 drawWithCache 里绘制文本时，TextMeasurer 的缓存键包含哪些内容，容量怎么设置才合理？**

缓存键是全部布局输入：文字、布局样式、占位内容、行数、换行、溢出、密度、布局方向、字体解析器与约束；颜色、画刷、阴影等绘制属性不参与键比较，所以只改颜色能复用布局，动画字号或宽度会产生新键。机制（Compose UI 1.12.0 材料口径）：`TextMeasurer` 用 LRU 策略缓存 `TextLayoutInput` 到 `TextLayoutResult` 的映射；`drawWithCache` 的构建块在尺寸、density、layout direction、lambda 身份或其读取的 Snapshot 状态变化时重建，返回的绘制块读取状态只请求重绘——因此颜色高频变化时把颜色读取移进绘制 lambda、保持测量输入不变，可同时复用缓存。容量按"会重复出现的布局输入数量"设置：少量静态标签覆盖重复输入；每帧只测同一段文本容量 1 即可；输入几乎每次都不同时评估 `skipCache = true`；不要把 `TextMeasurer` 当全文缓存，长文分页由业务管理。边界：普通 `Text` 组件已有节点级段落缓存，无需再包一层 `TextMeasurer`；它只在 `Canvas`、`drawBehind`、`drawWithCache` 等自定义绘制场景使用；全屏逐帧重绘文本时，缓存省的是排版，逐像素栅格成本仍在。
