# 渲染实战：View 与 Compose 基础

> 学习资料（文章模式沉淀）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：应用侧渲染优化的第一层——把布局失效与绘制失效分开，用列表复用与细粒度更新控制滚动帧成本，把 Compose 的重组、测量与文本排版归因到责任阶段后再动手。官方文档口径沿用源材料标注转写、本次。View/HWUI 管线与出图分型的机制层见 [../04-graphics/04-graphics-api.md](./04-graphics-api.md)，本文专注应用侧优化实战视角。VSync/Choreographer 调度、硬件层与 View 文本引擎的机制层见 [../04-graphics/01-render-pipeline-vsync.md](./01-render-pipeline-vsync.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 列表 item 里的一个 TextView 改文字并调用 requestLayout()，为什么一次调用不等于一帧只做一轮完整测量布局，ViewRootImpl 是怎么合并和二次调度的？**

`requestLayout()` 会合并尚未执行的 traversal。一次调用不保证一帧只测量、布局一次。布局中再次请求可能补跑一轮，同一帧的补轮再次请求则延后。

1. **请求传播**：`View.requestLayout()` 清空本节点 `mMeasureCache`，设置 `PFLAG_FORCE_LAYOUT` 与 `PFLAG_INVALIDATED`。只有父节点尚未请求布局时，才继续沿父链传播。
2. **合并调度**：请求到达 `ViewRootImpl` 后设置 `mLayoutRequested` 并调用 `scheduleTraversals()`。已排程但未执行的 traversal 只保留一次，因此此前的重复请求会合并。
3. **布局中再次请求**：布局阶段的请求由 `requestLayoutDuringLayout()` 记录，并在本轮收尾统一补跑一轮 measure/layout。补轮处理期间再次请求会 post 到下一帧，避免无限递归。同帧最多两轮。
4. **遍历阶段**：`performTraversals()` 根据窗口与 View 树状态决定是否执行 measure、layout 和 draw，因此这三阶段不是每帧固定全部运行。
5. **性能判定**：看 trace 中 `measure`/`layout` 切片次数与时长，不能用 `requestLayout()` 调用次数代替遍历次数。AAOS 13 的 `FrameMetrics.LAYOUT_MEASURE_DURATION` 汇总本帧标记需要更新的布局工作。`onLayout()` 再次请求布局是多轮遍历的常见来源，应先检查调用栈。

**Q2: [learning] 首屏布局应如何选择 LinearLayout、FrameLayout 与 ConstraintLayout，怎样验证压平层级确有收益？**

按真实对齐关系选择容器，再用目标设备的 trace 验证测量和布局成本。层级更浅本身不保证更快。

1. **复杂关系**：多个 View 需要基线、比例、Barrier 或 Chain 等约束时，可评估 `ConstraintLayout` 替代嵌套容器。`RelativeLayout`、带 `layout_weight` 的 `LinearLayout` 和复杂约束可能多次测量子 View，需按实际布局 trace 确认。
2. **简单关系**：少量子 View 的简单线性排列继续用 `LinearLayout` 或 `FrameLayout`。简单列表 item 使用约束求解可能抵消层级减少带来的收益。
3. **历史实验边界**：Google 2017 年在 Nexus 5X、Android 8.0、ConstraintLayout 1.0.2 的注册表单实验报告约 40% 平均测量布局耗时下降。它是特定页面、设备、版本和 100 帧数据，不是通用收益承诺。
4. **验收指标**：比较测量/布局调用次数、FrameTimeline overrun 和 P50/P90/P95 帧耗时，并固定设备、刷新率、数据集与交互脚本。不存在适用于所有刷新率的统一毫秒阈值。
**Q3: [learning] 压平一个首屏布局前应优先识别哪三类结构性成本，为什么 View.GONE 子节点被 measureChildren 跳过却仍建议延迟创建？**

三类结构性成本是重复测量的容器、高频出现的列表项和提前创建的低频内容。`GONE` 跳过标准测量与绘制，但不撤销已完成的 inflate，也不免除父容器每轮遍历。

1. **测量成本**：标准 `ViewGroup.measureChildren()` 只对非 GONE 子节点调用 `measureChild()`（AAOS 13 源码核对）。自定义容器可有不同规则，复杂容器也可能重复测量子 View。
2. **重复创建与绑定**：高频列表 item 会在滚动中反复创建或绑定，成本应结合列表缓存、复用和 trace 分析。
3. **创建成本**：View 对象与子树已在 inflate 时创建，设成 GONE 不会回收这部分启动成本。低频大块内容可用 `ViewStub` 延迟创建。
4. **优化顺序**：先处理首屏和高频列表 item，再处理多处复用的公共布局。低频深层布局按线上 trace 证据决定是否优化。

**Q4: [learning] ViewStub、merge、include 分别解决什么问题，哪些误用会直接抛异常或让优化失效？**

`ViewStub` 延迟创建低频子树，`<merge>` 消除组合 View 或被 include 布局里的多余根容器，`<include>` 只复用 XML 结构。典型误用是反复对同一个 ViewStub 调 inflate、给 `<merge>` 传 null root 或 `attachToRoot=false`（直接抛 `InflateException`）、指望 `<include>` 减少 inflate 成本。机制与边界（AAOS 13 的 `ViewStub.java` 与 `LayoutInflater.java` 已核对）：

1. **ViewStub**：首次 `inflate()` 经 `replaceSelfWithView()` 把自己从父容器移除并插入真实 View，stub 随即不存在，不能二次 inflate。应缓存替换后的 View，后续切换只改 `visibility`，引用放在 Fragment 字段时要在 `onDestroyView()` 清空。首次显示发生在调用点同步执行，不要放进动画关键帧。
2. **merge**：没有可独立返回的根节点，`LayoutInflater` 要求 root 非空且 `attachToRoot=true`，否则抛 `InflateException`（AAOS 13 源码原样报错），因此不适用于先构建、稍后 attach 的预加载方案。
3. **include**：走 `parseInclude()` 仍要解析资源并创建 View，只是工程复用。降层级需要配合 `<merge>`，是否有收益取决于运行时少创建、少测量了什么。

**Q5: [learning] 把布局解析挪到 AsyncLayoutInflater 后台线程后，为什么主线程仍可能出现 inflate 时间，哪些布局会回退到 UI 线程？**

`AsyncLayoutInflater` 只把满足后台构造条件的布局工作移出 UI 线程。不满足条件时会回退到 UI 线程，因此 trace 里仍可能出现完整 inflate。

一次 inflate 包括编译后 binary XML 解析、include/merge/theme 标签处理，经 `Factory2 → Factory → private factory` 链构造 View，以及递归建立子树和 LayoutParams。构造器缓存只省类查找，省不掉 `Constructor.newInstance()`、style、字体、Drawable、自定义初始化或冷启动类加载。

1. **后台线程前提**：父容器的 `generateLayoutParams(AttributeSet)` 必须线程安全，被创建 View 的构造函数不能创建 Handler 或调用 `Looper.myLooper()`。
2. **默认不支持的布局**：AndroidX AsyncLayoutInflater 1.1.0 默认不支持 `LayoutInflater.Factory`/`Factory2` 和含 Fragment 的布局。AppCompat 场景可用 `AsyncAppCompatFactory` 等对应工厂。
3. **回调线程**：默认回调在 UI 线程执行，返回的 View 还未加入 parent，须在回调中 `addView()`。1.1.0 起的 callback executor 重载可改变回调线程。若回调不在 UI 线程，操作 View 前必须切回 UI 线程。
4. **验收**：对比冷启动与预热后的 trace，使用后 UI 线程应只剩短的 addView/bind 工作。仍出现完整 inflate 表示回退。同时记录预创建未命中率和取消数，避免用额外 CPU、内存换取很少命中。数据绑定、图片解码和网络请求不属于该 API 自动转移的工作。

**Q6: [learning] 自定义 View 用缓存的期望尺寸跳过 onMeasure 计算，为什么只比较宽度 MeasureSpec 不够，onLayout 里只看 changed 参数会漏掉什么？**

自定义尺寸缓存必须覆盖宽、高两个 `MeasureSpec` 以及所有会改变期望尺寸的输入。只比宽度会在高度约束、内边距或内容改变后复用错误结果。`onLayout(changed)` 中的 `changed` 只表示当前 View 自身边界变化。它不能代表子 View 的尺寸、可见性、外边距或业务顺序未变。

1. **缓存输入**：除两个 MeasureSpec 外，还要纳入内容版本、内边距、字体、布局方向和子 View 可见性等可能改变几何结果的输入。
2. **框架边界**：AAOS 13 的 `View.measure()` 使用宽、高 spec 和强制布局标记参与缓存判定。spec 未变且满足特定 `EXACTLY` 条件时可提前返回。自定义缓存不能绕过 `setMeasuredDimension()` 或破坏父容器约束。
3. **缓存用途**：只缓存纯计算得到的几何结果。数据、字体或地区设置改变尺寸时递增内容版本并调用 `requestLayout()`。只改颜色则无需使尺寸缓存失效。

下面是示意伪代码，缓存写回和辅助方法实现已省略，不能直接复制编译：

```java
if (widthSpec != mLastWidthSpec || heightSpec != mLastHeightSpec
        || mContentVersion != mLastContentVersion || paddingChanged()) {
    computeGeometry(widthSpec, heightSpec);   // 只做 CPU 计算
    // ... 记录本次参与计算的完整输入
}
setMeasuredDimension(resolveSizeAndState(mDesiredWidth, widthSpec, 0),
        resolveSizeAndState(mDesiredHeight, heightSpec, 0));
```

只比较宽度 spec 会让 padding 改变后仍复用旧高度。`onLayout()` 应读取与完整输入绑定的坐标缓存，不要在摆放阶段再次调用 `requestLayout()`。同一帧发生多轮测量时应查调用栈，常见原因包括 `onLayout()` 触发布局请求、父子约束互相依赖和 `wrap_content` 协商。

**Q7: [learning] 波形图这类持续刷新的自绘 View 应怎样组织绘制对象与失效调用，invalidate()、requestLayout()、postInvalidateOnAnimation() 各承担什么？**

持续刷新的自绘 View 应复用绘制对象，`onDraw()` 只提交命令。像素变化触发 `invalidate()`，尺寸变化触发 `requestLayout()`，动画刷新用 `postInvalidateOnAnimation()`。

1. **硬件绘制**：硬件加速时 UI 线程把命令记录到 RenderNode 的 DisplayList。内容未失效的 View 可复用记录，但 `onDraw()` 仍在 UI 线程执行。
2. **失效范围**：AAOS 13 中 `invalidate(Rect)` 传入的脏矩形不再缩小失效范围，API 21 起标记为 deprecated。需隔离重绘区域时拆分 View，或按需维护独立 RenderNode。
3. **动画调度**：`postInvalidateOnAnimation()` 依赖 `AttachInfo`。View 尚未 attach 时调用不会安排刷新。detach、不可见或动画结束后应停止持续刷新。
4. **分配与图层**：临时字符串、装箱、每帧创建 Path/RectF 或格式化会增加分配，应以 allocation recording 和帧数据判断影响。AAOS 13 的 `withLayer()` 会在动画期创建并清理硬件层。内容不变的短时 alpha/transform 动画可能获益，动画同时改内容并失效时仍需重栅格化。
5. **高频输入**：采样持续高速到达时，在上游合并更新，避免每个采样点都重建整条 Path。

**Q8: [learning] RecyclerView 稳定滑动中偶尔出现 onBindViewHolder 是否说明复用失效，ViewHolder 到底按什么顺序获取、哪些来源仍会 bind？**

滑动中出现 `onBindViewHolder()` 不代表复用失效。它只说明本次 holder 需要重新绑定。RecyclerView 1.4.0 的 holder 获取路径大体按下列顺序查找：

1. **Changed scrap**：预布局时按 position 或 stable ID 查找变化前的 holder。
2. **已附着来源**：检查 attached scrap、hidden child 和 `mCachedViews`。缓存先按 position 查找，再校验 viewType 与 ID。
3. **稳定 ID 查找**：配置 stable ID 时可按 ID 再找 holder。
4. **扩展与池**：再查 `ViewCacheExtension` 和 `RecycledViewPool`。两者都没有可用 holder 时才新建。
5. **绑定与容量**：scrap 取回时可能重绑，Pool 命中后通常也要 bind。`mCachedViews` 默认请求上限为 2，预取观察到的 item 会提高实际保留数。Pool 默认每个 viewType 保留 5 个，可由 `setMaxRecycledViews()` 调整。
6. **稳定 ID 边界**：attached scrap 是布局期间暂时分离的 holder，不保证免 bind。“四级缓存”不是固定顺序表。`setHasStableIds(true)` 只用于唯一且稳定的业务 ID，能帮助更新和动画识别同一 item，但不能替代 DiffUtil 或修复错误的 viewType。

**Q9: [learning] 多个嵌套横向列表共享同一个 RecycledViewPool 时，viewType 应按什么原则划分，共享池会引入什么新风险？**

共享 `RecycledViewPool` 按整数 viewType 复用 holder，不知道 holder 所属 Adapter。因此只有 ViewHolder 结构和绑定规则能够交叉复用时，多个横向列表才应共享池。

1. **类型划分**：只差文案、图片或按钮状态的条目可共用一个 viewType。结构不同或绑定契约不同的条目必须区分。
2. **绑定与容量**：Pool 命中后通常仍需 bind 以清理和设置 holder 状态。`setMaxRecycledViews()` 只增加上限，不预先创建 holder。按同屏子列表数与每个子列表的可见卡片数估算容量，再用 trace 和内存验证。
3. **分离回收**：结构一致的 Adapter 可共用 `setRecycledViewPool(sharedPool)`。`LinearLayoutManager.setRecycleChildrenOnDetach(true)` 会在 detach 时回收 child，也会改变图片或播放器资源清理时机，需结合页面生命周期验证。
4. **共享副作用**：多个 Adapter 会共用按 viewType 统计的 create/bind 历史耗时。这些值影响预取预算。Adapter 创建成本相差很大时会相互影响。按颜色或角标等业务状态拆成许多类型会使回收池分散、增加重新创建。

**Q10: [learning] 高频局部刷新的列表为什么 notifyDataSetChanged 会卡，DiffUtil 的三个回调与 payload 各承担什么，ListAdapter 有哪些硬边界？**

`notifyDataSetChanged()` 不提供变化范围，通常要重绑可见条目。DiffUtil 用业务身份与内容比较生成更细的更新，payload 可将可见变化限制到部分字段。

1. **身份比较**：`areItemsTheSame()` 判断新旧项是否代表同一业务实体。
2. **内容比较**：`areContentsTheSame()` 判断同一实体的 UI 内容是否变化。
3. **局部变化**：`getChangePayload()` 描述发生变化的字段，让 Adapter 只更新对应 View。目标 holder 未 attach 时 payload 可能丢弃，因此完整 bind 必须可从当前 item 恢复全部 UI，并清理旧图片、选择状态和监听器。
4. **异步差分**：DiffUtil 以 Myers 算法求最短插入/删除序列，复杂度为 O(N + D²)。Move detection 会增加扫描开销。ListAdapter/AsyncListDiffer 在后台计算差异，再回主线程分发。被后续提交取代的旧结果不会应用。
5. **不可变输入**：AsyncListDiffer 对同一 List 实例直接返回。原地修改旧列表再 `submitList()` 可能没有更新，因此提交列表及比较字段应视为不可变。`areContentsTheSame()` 不要比较与 UI 无关的时间戳或调试字段。
6. **移动检测与 stable ID**：确定数据顺序不会交换时，可用 `calculateDiff(callback, false)` 关闭 move detection，但批次提交和主线程分发由调用方负责。Stable ID 可在整表刷新时帮助可见项维持动画，不能省去完整重绑。

**Q11: [learning] RecyclerView 的 GapWorker 预取运行在哪个线程，willCreateInTime/willBindInTime 怎么决定做不做，为什么预取正常下一帧仍可能长？**

GapWorker 不是后台线程。它把预取任务投进主线程队列，并根据近期绘制时间和刷新周期估算下一帧 deadline。它可预取 holder 创建与绑定，但不能替下一帧执行 item 的 measure/layout。

1. **任务产生**：滚动时 RecyclerView 用方向和距离调用 `postFromTraversal()`，随后将任务放入主线程消息队列。
2. **时间预算**：普通任务用同 viewType 的 Pool 历史耗时估计 create/bind 是否能赶上 deadline。预计下一帧立即需要的任务标记为 forced，可跳过耗时估算，但实际工作仍占主线程。
3. **定位剩余卡顿**：预取切片正常但 `RV OnLayout` 或 framework measure/layout 仍长时，检查 item 尺寸、布局结构和图片是否触发 `requestLayout()`。
4. **嵌套列表预取**：`setInitialPrefetchItemCount()` 只影响嵌套 RecyclerView 首次进入视口前的初始预取，数量应接近初始可见 item 数。设大只会增加 bind 和对象，不会直接提高流畅度。
5. **缓存容量**：增大 `setItemViewCacheSize()` 可减少短距离回滑的 bind，但会持有更多对象引用。先看 create/bind、内存与 GC 数据再决定。
6. **版本限制**：RecyclerView 1.4.0 要求 `compileSdk >= 35`，在 API 35+ 上滚动时会调用 `View.setFrameContentVelocity()` 参与平台刷新率策略，AAOS 13 无该平台 API。Android 17 的 DeliQueue 只改变消息入队锁竞争，不改变 GapWorker 使用主线程的事实。

**Q12: [learning] 纵向 Feed 里嵌横向卡片列表时应怎样配置容器，setHasFixedSize(true) 的确切语义是什么？**

先避免无界测量（不要把 RecyclerView 放进纵向 `NestedScrollView` 后让它按全部内容高度测量，否则一次准备大量 item、回收优势消失），结构兼容时共享 `RecycledViewPool` 并按首次可见卡片数设置 initial prefetch，容器尺寸不受数据影响时才 `setHasFixedSize(true)`。机制：`setHasFixedSize(true)` 表示 Adapter 内容变化不会改变 RecyclerView 自身的测量宽高，不要求每个 item 等高、也不阻止 item 内部重新测量布局。RecyclerView 自身 `wrap_content` 且增删 item 会改变容器尺寸时不应开启。其他边界：纵向套纵向会增加触摸分发、NestedScrolling 协商与测量复杂度，页面能由单个列表的多种 viewType 表达时用 `ConcatAdapter` 合并。高频局部刷新页面可只关 `SimpleItemAnimator` 的 `supportsChangeAnimations`——payload 已缩小 bind 范围后，change animation 仍可能让新旧 holder 同时参与过渡，A/B 对比确认 `RV OnLayout` 与 bind 下降且视觉无损后按页面收窄，不要全局一刀切。

**Q13: [learning] LazyColumn 的 key 与 contentType 分别对应 RecyclerView 的什么概念，从 RecyclerView 迁移到 LazyColumn 应比较哪些指标？**

key 对应业务身份（承担 stable ID 的角色，要求稳定、唯一且可由 `Bundle` 保存以支持 `rememberSaveable`），contentType 对应 viewType 与 Pool 的结构兼容分组——默认 `null` 表示全部同型，Compose Foundation 1.12.0 内部按类型最多保留 7 个可复用槽位，这属于实现细节而非 API 契约（AndroidX 外部库，未本地核对）。迁移判断比较四组指标：慢帧率、P95 帧耗时、内存峰值、首屏可交互时间，用同一设备、同一数据集和同一交互脚本，不用固定结论。机制：未提供自定义 key 时按位置维护身份，显式 `key = { index -> index }` 与位置身份没有行为差别。头部插入后位置键仍在但对应业务对象已变，`remember` 状态可能跟错数据、内层滚动位置可能错行。项目滚出组合后普通 `remember` 值丢失。items 内容里反复排序、过滤或创建大对象会随重组重复发生，应移到列表外或 `remember` 缓存。边界：把业务 ID 当 contentType 会阻断跨项目复用，把结构差异大的条目都留 `null` 会让运行时在不相似内容间复用。预取（含 PausableComposition 可暂停预组合，Foundation 开关默认值随版本变化、1.12.0 源码为 true）与 GapWorker 一样都在主线程调度，不能照搬 RecyclerView 的缓存数量参数。

**Q14: [learning] Compose 状态在 Composable 函数体、placement lambda、draw lambda 与 graphicsLayer lambda 中被读取，变化后分别触发哪个阶段的工作？**

运行时按读取位置记录依赖：函数体读取走 Composition（结果变化可继续进入 Layout/Drawing），placement（`Modifier.offset { }`）读取只重新放置，draw lambda 只重新绘制，`graphicsLayer { }` 回调只更新图层属性。把高频值延后到满足 UI 语义的最晚阶段是控制重组范围的基本手段。机制（Compose 1.12.0，AndroidX 外部库材料口径）：三阶段是失效的起点，不代表每个显示帧都完整执行三遍——Composition 输出没变时 Layout/Drawing 可跳过。`LazyColumn`、`BoxWithConstraints`、`SubcomposeLayout` 在布局过程中才决定子内容，不能套用“组合总在布局前一次完成”的模型。

```kotlin
Modifier
    .offset { IntOffset(0, listState.firstVisibleItemScrollOffset / 2) }
    .graphicsLayer { this.alpha = alphaState.value }
```

两个高频读取都不再由 Composable 函数体执行。边界：`offset { }` 省掉的只是组合与测量，位置变化仍执行放置。视觉平移不能冒充布局位置——点击区域、父布局占位或无障碍边界需要随位置变化时必须用布局表达。状态影响节点数量、文本或语义属性时 Composition 无法省略，只能缩小读取范围（封装进 lambda、下移到更靠近使用的 Composable、拆小重组作用域）。

**Q15: [learning] Strong Skipping 默认开启后，List 这类 unstable 参数的 Composable 还能跳过吗，@Stable/@Immutable 注解还承担什么？**

能跳过——Strong Skipping 自 Kotlin 2.0.20 起默认启用（编译器行为，随 Kotlin 版本而非平台 API），所有可重启 Composable 都生成跳过逻辑，稳定参数用 `equals()` 比较、不稳定参数用引用 `===` 比较。注解不再是“能否跳过”的开关，而是开发者契约：公开属性变化必须能被 Compose 观察、同一对实例的 `equals` 结果保持一致。机制：传入内容相同但新建的 `List` 时引用不同，仍会重组。原地修改同一个可变集合并保持引用时可能被跳过，且普通集合不向 Snapshot 系统发通知，界面可能保留旧内容——修复方向是不可变 UI 模型、`SnapshotStateList` 或新实例流转，不是虚假注解。边界：`skippable` 只表示“允许跳过”，是否真跳过还取决于重启组是否失效、参数比较结果、组结构与内部状态读取，不能用编译器 CSV 统计运行时跳过率。lambda 会被自动记忆（稳定捕获按 equals、unstable 捕获按 `===`，`@DontMemoize` 可退出）。对象含不可观察的可变字段时不能标注 `@Immutable`，错误契约导致应执行的更新被跳过。非 `Unit` 返回值或 `@NonSkippableComposable` 的函数不生成跳过逻辑。

**Q16: [learning] Compose 编译器报告里 skippable=1 且 unstable 数量下降，能据此宣布重组性能改善吗？各报告文件分别能回答什么？**

不能。编译器报告只回答“编译器生成了什么”——类型稳定性推断、函数是否 restartable/skippable、模块统计与 featureFlags，运行时重组次数要 Layout Inspector 或 Composition Tracing，用户可见结果要 FrameTimeline 或 Macrobenchmark。文件分工（Kotlin 2.3.20/2.4.10 材料口径）：`reportsDestination` 输出 `*-classes.txt`（类型及属性稳定性）与 `*-composables.txt`/CSV（函数标签、参数稳定性）。`metricsDestination` 输出 `*-module.json`（模块统计与 `featureFlags`，是解释其他数字的必要条件）。解析细节：CSV 布尔列用 0/1、没有名为 `params` 的列，首列虽名为 package 写的是函数完全限定名。做法：报告应在 release 变体上生成，比较前固定 Kotlin 版本、变体与 featureFlags。Kotlin 升级可能改变稳定性判定（2.4.10 修正了部分 stable 判定为运行时稳定性或 Uncertain），升级后必须重新生成基线再解释标签变化。反例：unstable 数量下降但热点函数每次重组都新建 List 实例，运行时重组可能不降反升。优化结论至少包含一项静态证据加一项运行时证据，“全模块全部 skippable”的门禁不值得追求。

**Q17: [learning] remember(keys)、derivedStateOf、produceState 与 snapshotFlow 各适合什么场景，混用会出什么问题？**

`remember(keys)` 按显式 key 缓存一次计算，`derivedStateOf` 把高频输入收敛为低频 State，`produceState` 把外部数据转成 State（key 变化取消旧 producer、启动新 producer），`snapshotFlow` 把 Snapshot 状态读取转成冷流驱动副作用。各自边界（Compose 1.12.0 材料口径）：

1. **remember**：key 是缓存身份，传入原地修改的普通列表时不会重新计算——问题在数据所有权与可观察性，不在多加一层 remember。
2. **derivedStateOf**：适合“输入每次变、输出只在跨界变”（如滚动是否越过首项）。它维护依赖表与缓存，本身有成本，字符串拼接、数值乘法这类输出每次都变的表达式直接计算更省。每次重组新建派生状态同样错误，应配合 `remember` 保留实例。
3. **produceState**：返回的 State 由 `remember` 保留，key 变化不会把它重置为 `initialValue`，切换用户要立即显示“加载中”须在新 producer 开头显式赋值。State 合并相等值，连续快速写入时观察者可能跳过中间值。回调式数据源用 `awaitDispose` 注销。
4. **snapshotFlow**：代码块在只读 Snapshot 中执行、按 `equals` 过滤、可能跳过中间状态，适合观察“当前状态”，不适合统计每次点击或传感器样本。

Strong Skipping 不管理 producer 协程，协程的启动、取消与异常处理仍由 Effect key 与作用域生命周期决定。

**Q18: [learning] 自定义 Modifier.Node 时 NodeChain 按什么规则复用、更新或替换节点，Element 的 equals 契约写错会怎样？**

NodeChain 对新旧 Element 序列做差分：相等则复用 Node 且不调 `update()`，运行时类型相同但不相等则复用 Node 并调用新 Element 的 `update(node)`，类型不同则移除旧 Node 并创建新 Node。Element 的 `equals()`/`hashCode()` 必须覆盖所有会改变 Node 行为的输入——遗漏字段会让界面保留旧配置，错写相等会让运行时跳过本应执行的 `update()`。机制（Compose UI 1.12.0 材料口径）：Element 是重组时可重建的轻量配置，Node 是附着在 `LayoutNode` 上可跨重组复用的运行对象，配置放 Element（参数型用 data class 最稳）、状态放 Node。`update()` 只同步字段即可，`shouldAutoInvalidate` 默认 true 会按节点实现的接口自动失效（Draw 接口失效绘制、Layout 接口失效测量），再无条件手动调 `invalidateDraw()` 属于重复表达。边界：Node 复用不等于 Element 零分配，工厂函数仍可能创建轻量 Element。频繁变化的运行状态放进 Element 会放大分配。差分规则属内部实现，升级 Compose 后按目标版本复核。关闭自动失效（`shouldAutoInvalidate = false`）后，每类输入变化都要对应调用 `invalidateDraw()`/`invalidateMeasurement()`/`invalidatePlacement()`，遗漏即 UI 不更新——只有剖析证明无效阶段调用值得优化且测试覆盖每个分支时才用。

**Q19: [learning] 自定义 Modifier 只组合现有节点时，Modifier.composed 与 Modifier.Node 的运行成本和迁移边界是什么？**

`Modifier.composed` 在物化时要运行组合逻辑并生成应用位置专属的 Modifier 链。需要自定义绘制、测量、语义或输入行为时，`Modifier.Node` 可避免为该行为额外建立组合槽位，通常更适合高频节点。

1. **成本来源**：`composed` 会执行组合并创建实际 Modifier 链，状态生命周期跟随组合槽位。Node 将轻量配置放在 Element、运行状态放在附着到 `LayoutNode` 的 Node 上。
2. **Composable 工厂限制**：非 `Unit` 返回值的 Composable 不可被跳过，即使输入稳定也会随调用者重组。Composable 工厂读取的 `CompositionLocal` 来自调用位置。
3. **选型**：只组合现有 Modifier 时用普通工厂。需要新增绘制、测量、语义或输入行为时用 `ModifierNodeElement` 与 Node。Android 官方迁移指南建议自定义行为改用 `Modifier.Node`，但现有 `composed` API 仍然可用。迁移优先处理有剖析证据的热点。

**Q20: [learning] 自定义 Compose Layout 里对同一个子节点用两组约束连续调用 measure() 会发生什么，单遍测量协议包含哪些约束？**

运行时会抛异常——单遍测量协议要求每个 `Measurable` 在一次布局过程中只被 `measure()` 一次：父节点传一组 `Constraints`，保存返回的 `Placeable`，再在 `layout(width, height) { }` 块中放置。机制（Compose 1.12.0 材料口径）：`Placeable` 保存测得宽高供放置阶段使用，为它建列表是实现的明确成本，不必为“零对象”删掉必要状态。宿主层面 `AndroidComposeView.onMeasure()` 把 View 的 `MeasureSpec` 转成 Compose `Constraints` 并触发根测量，`onLayout()` 与 `dispatchDraw()` 前完成待处理的测量放置，Compose 三阶段嵌在宿主 ViewRootImpl 遍历中，与平台各用一套 API 名称。做法与边界：需要两遍协商（先测主体再决定覆盖层、按内容定父尺寸）时用 `SubcomposeLayout` 或固有尺寸查询表达，不要绕过协议。测量 lambda 内不做业务取数、排序、字符串解析、图片解码与日志格式化。父节点读取子节点的 `AlignmentLine` 对齐线时会建立依赖，子节点对齐线变化会触发父级重新测量或放置，排查父布局频繁重测时要检查是否读取了对齐线，不能只看传入约束。

**Q21: [learning] Compose 节点上一帧测过的尺寸什么时候会被复用，@Stable 注解会影响布局缓存吗？**

`MeasurePassDelegate` 在节点没有 `measurePending` 标记、且本次 `Constraints` 与上次相同时直接复用已有结果，否则执行 `performMeasure()` 并通过 Snapshot 观察器记录测量代码读取的状态。这套机制跨帧生效，不是“同一帧缓存一个结果”。`@Stable` 属编译器稳定性契约，不参与约束比较、也不清除 `measurePending`，只可能通过减少重组间接减少布局失效。错误标注反而会让 UI 漏更新。机制与边界（Compose UI 1.12.0 材料口径）：标记分两组——`measurePending`（尺寸需重算）与 `layoutPending`（位置需重算），由 `MeasureAndLayoutDelegate` 按树深维护，父节点已处于待测状态时子节点通常不再重复登记。约束相同也不保证子树无工作，子节点因自身状态读取失效时由 `forceMeasureTheSubtree()` 处理。失效传播没有固定的逐级父链，取决于节点是否放置过、尺寸是否变化与对齐线、前瞻布局依赖。排查顺序：先确认状态读取阶段，再检查约束是否稳定（窗口、Insets、字体缩放），最后才考虑稳定性注解。

**Q22: [learning] 对 LazyColumn 或 BoxWithConstraints 查询 IntrinsicSize.Min 为什么会直接失败，固有尺寸查询的真实成本怎么评估？**

`SubcomposeLayout` 使用 `NoIntrinsicsMeasurePolicy`，父级经 `IntrinsicSize.Min/Max` 查询会直接失败——这类组件在测量时才决定组合哪些内容，组合之前不存在可靠的固有尺寸。普通自定义 Layout 未覆写固有尺寸方法时得到的是近似默认实现。机制：固有尺寸查询发生在正式测量之前，官方保证它不会把同一子节点正式测量两次，但查询本身要递归询问相关子树，文本固有尺寸会运行段落宽高计算，成本取决于布局实现、查询方向与节点数量，不能统一写成 O(depth) 或“一次完整子树测量”。做法与边界：需要“与父尺寸匹配”时改外层布局的测量顺序或给组件明确约束，对 `LazyColumn` 调 `height(IntrinsicSize.Min)` 得不到低成本的列表总高度。覆写固有尺寸方法要与正式测量语义一致、同输入同结果、不含 I/O 或可变集合访问，并注意字体缩放与布局方向造成的失效。把固有尺寸结果缓存在业务层要考虑 `Density`、`fontScale`、文本内容等失效输入。结构只在少数尺寸断点变化时，先归纳为紧凑/展开模式统一处理，避免每个列表项各自套一层 `BoxWithConstraints`。

**Q23: [learning] padding + background + clickable 组合会创建几个 LayoutNode，调整 Modifier 顺序为什么属于行为变更而不是纯性能优化？**

一个——Modifier 元素创建的是 `Modifier.Node`（布局、绘制、输入各归其类），不会为每项新建 `LayoutNode`，但长链仍增加节点更新与遍历成本。顺序决定约束传递与绘制范围的语义，`padding(16.dp).size(40.dp)` 与 `size(40.dp).padding(16.dp)` 接收和传递的约束不同、最终外部尺寸可能不同，`clip().background()` 与 `background().clip()` 的圆角覆盖范围也不同。机制（Compose UI 1.12.0 材料口径）：`LayoutNode` 持有 `NodeChain`，实现 `LayoutModifierNode` 的节点获得 `LayoutModifierNodeCoordinator`，在被包裹内容的外层参与约束转换、测量与放置。1.12.0 把差分用的临时列表改为 `MutableObjectList` 并复用遍历栈，这是减少分配的实现调整，不合并节点、也不改变链的顺序语义。做法与边界：`Modifier.then()` 只连接两段 Modifier，不合并相邻节点，不能当优化接口。调整顺序前先确认布局、绘制、输入与无障碍语义的变化再测性能。每个 Modifier 按能力类型分析成本，不能把每个节点都计成一层布局树。

**Q24: [learning] 同样的 Compose Text(String) 在什么条件下走简化实现 ParagraphLayoutCache，哪些能力会让它改走 AnnotatedString 路径，段落缓存什么时候复用？**

未启用文本选择、没有 `onTextLayout` 回调、未启用 autoSize 三个条件同时满足时，`BasicText(String)` 用 `TextStringSimpleNode` 加 `ParagraphLayoutCache`（只在无障碍请求布局结果时才创建完整的 `TextLayoutResult`）。需要选择、布局回调或自动字号就转 `AnnotatedString`，改走 `TextAnnotatedStringNode` 加 `MultiParagraphLayoutCache`（Compose Foundation 1.12.0，AndroidX 外部库）。机制：节点把布局属性与绘制属性分开比较——字号、字体、行高、maxLines、约束等布局属性变化重新测量，颜色、画刷、阴影等纯绘制变化保留排版只重绘。内容相等的新建 `AnnotatedString` 不会因对象身份不同就使布局失效。复用条件：宽度不变且新高度仍能容纳旧段落时断行不变、可复用已有段落，最大宽度变化必须重排，所以列表项宽度在滚动中保持稳定对文本成本最有利。字体异步解析返回目标 Typeface 后旧缓存过期重测。边界：Android 实现里文本满足简化条件且单行放得下时用 `BoringLayout`，否则用 `StaticLayout`（判定入口在 Compose 节点，与平台 TextView 的快慢路径同源）。`TextAutoSize.StepBased` 用二分查找选字号，每个候选都试排、收敛后还会再增大一步，长列表大面积启用要记录试排成本。给每个列表项长期加 `onTextLayout` 只为日志会放弃简化路径，回调里也不要用布局结果改影响本次布局的输入（如按 lineCount 调 maxLines 会形成反复测量）。

**Q25: [learning] 用 rememberTextMeasurer 在 drawWithCache 里绘制文本时，TextMeasurer 的缓存键包含哪些内容，容量怎么设置才合理？**

缓存键是全部布局输入：文字、布局样式、占位内容、行数、换行、溢出、密度、布局方向、字体解析器与约束。颜色、画刷、阴影等绘制属性不参与键比较，所以只改颜色能复用布局，动画字号或宽度会产生新键。机制（Compose UI 1.12.0 材料口径）：`TextMeasurer` 用 LRU 策略缓存 `TextLayoutInput` 到 `TextLayoutResult` 的映射。`drawWithCache` 的构建块在尺寸、density、layout direction、lambda 身份或其读取的 Snapshot 状态变化时重建，返回的绘制块读取状态只请求重绘——因此颜色高频变化时把颜色读取移进绘制 lambda、保持测量输入不变，可同时复用缓存。容量按“会重复出现的布局输入数量”设置：少量静态标签覆盖重复输入。每帧只测同一段文本容量 1 即可。输入几乎每次都不同时评估 `skipCache = true`。不要把 `TextMeasurer` 当全文缓存，长文分页由业务管理。边界：普通 `Text` 组件已有节点级段落缓存，无需再包一层 `TextMeasurer`。它只在 `Canvas`、`drawBehind`、`drawWithCache` 等自定义绘制场景使用。全屏逐帧重绘文本时，缓存省的是排版，逐像素栅格成本仍在。

**Q26: [learning] 自定义 Modifier.Node 的 onAttach、onDetach、onReset 与 coroutineScope 分别该承担什么生命周期工作？**

Node 的附着生命周期管理 owner 访问和协程，复用回调则负责清除与旧列表项绑定的状态。不要把两者混为一次性销毁。

1. **附着与协程**：`onAttach()` 后可访问 owner 和 Node 的 `coroutineScope`。作用域在 `onDetach()` 返回后取消，同一 Node 之后仍可能重新附着。
2. **复用清理**：`onReset()` 在节点仍附着时、即将进入复用池前调用，例如 Lazy 列表项离开视口时。随后 Node 会 detach。清除焦点、按压和拖拽进度等条目级状态，避免新数据项继承旧状态。
3. **状态观察**：Node 中不能使用 `LaunchedEffect`。`onAttach()` 同轮其他节点未必已完成处理，需要观察整棵树最终状态时用 Node 的 `sideEffect` 延后。
4. **CompositionLocal**：实现 `CompositionLocalConsumerModifierNode` 后，通过 `currentValueOf()` 读取附着位置的值。阶段外读取可用 `observeReads` 订阅变化，收到通知后重新读取并应用新值。

