# RecyclerView 列表组件

**Q1: [learning] RecyclerView 如何把数据、ViewHolder 与可见 item 连接起来，为什么创建和绑定不是一一对应？**

RecyclerView 只为当前布局需要的 item 请求 View，并在复用时把已有 ViewHolder 绑定到新数据；Adapter、ViewHolder 和 LayoutManager 分别提供数据绑定、item 视图和摆放策略。列表有很多数据项时，屏幕外的每一项不会因此都变成一棵常驻 View 子树。

1. **Adapter：**提供 item 数量与类型，并在需要时创建或绑定 ViewHolder。`onCreateViewHolder()` 创建 item View 结构，`onBindViewHolder()` 把当前数据完整写入结构；一个 Holder 可多次绑定不同数据，因此绑定次数不等于创建次数。
2. **ViewHolder：**持有一个 item 根 View 及常用子 View 引用，减少重复查找。它代表可复用的视图结构，不代表某条数据的永久对象；绑定时必须覆盖文本、可见性、选中态和异步请求等会随 item 改变的状态，避免旧 item 状态漏到新 item。
3. **LayoutManager：**决定 item 如何测量、摆放、滚动及何时需要可见项。LinearLayoutManager 组织一维列表，GridLayoutManager 组织网格；自定义布局策略也由它承担。
4. **RecyclerView 与回收：**RecyclerView 协调布局、Adapter 和回收器。离开屏幕的 ViewHolder 可能暂时保留、进入共享池，或被丢弃；之后是否能直接复用取决于 viewType 等条件，复用后通常仍要重新绑定数据。

因此，RecyclerView 复用的是 item View 结构，不是业务数据；一次创建的 ViewHolder 可以多次绑定，绑定代码必须完整设置当前 item 的 UI 状态。依据为 Android Developers《Create dynamic lists with RecyclerView》和 RecyclerView API reference。列表滚动、复用和更新的性能优化见 [View 与 Compose 渲染实战](../05-graphics/12-View与Compose实践.md)。

**Q2: [learning] ViewHolder 离开屏幕或在布局中被临时移除后进入哪一级缓存？容量与淘汰规则是什么？**

回收分两类。布局过程中被临时 detach 的视图进入 scrap（mAttachedScrap 与 mChangedScrap），只在本轮布局内中转，不占缓存容量。真正离屏的 ViewHolder 经 recycleViewHolderInternal 处理：“干净”的进一级缓存 mCachedViews（默认 2 个，按位置复用、无需重新绑定），放不下或不干净的按 viewType 进 RecycledViewPool（每个 viewType 默认 5 个，取出后必须重新绑定），池也满则丢弃等待 GC（Garbage Collection）回收。

按回收决策的顺序，规则有五条：

1. **干净的判定：**holder 不带 FLAG_INVALID、FLAG_REMOVED、FLAG_UPDATE、FLAG_ADAPTER_POSITION_UNKNOWN 中的任一标志，且一级缓存容量大于 0，才有资格进一级缓存。
2. **一级缓存已满：**先把索引 0 的最旧条目淘汰进池（recycleCachedViewAt(0)），再插入新条目。滚动中回收的非预取条目会插在预取条目之前，使预取视图更晚被淘汰。
3. **不可回收：**动画进行中的 holder 处于 setIsRecyclable(false) 状态，不进任何缓存，只从动画列表移除，等动画结束再回收。itemView 带瞬态状态（如正在跑 ViewPropertyAnimator）时，先询问 Adapter.onFailedToRecycleView，返回 true 才强制回收。
4. **调参入口：**setItemViewCacheSize(n) 调一级缓存容量。RecycledViewPool.setMaxRecycledViews(viewType, max) 调池容量。多个列表可通过 setRecycledViewPool 共享同一个池。
5. **非法回收：**对仍在 scrap 中或仍挂在父容器上的 holder 调用回收，会抛 IllegalArgumentException，提示 Scrapped or attached views may not be recycled。

获取视图时按位置、按稳定 ID、按 viewType 逐级放宽查找的顺序，以及哪些来源仍会触发绑定，见 [View 与 Compose 渲染实战](../05-graphics/12-View与Compose实践.md)。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 Recycler.recycleViewHolderInternal、Recycler.DEFAULT_CACHE_SIZE 与 RecycledViewPool.DEFAULT_MAX_SCRAP。

**Q3: [learning] onBindViewHolder 中为什么不能缓存 position 参数？getBindingAdapterPosition、getAbsoluteAdapterPosition 与 getLayoutPosition 有什么区别？**

同一个 ViewHolder 会被先后绑定到不同位置，而且数据更新后布局位置要等到下一次布局才与 Adapter 位置同步，所以绑定回调里缓存的 position 在点击时可能已经过期，事件发生时应现取。三个 API 分别回答“在绑定它的 Adapter 里排第几”“在 RecyclerView 顶层 Adapter 里排第几”“上一次布局时排第几”。

三个位置 API 的语义如下：

1. **getBindingAdapterPosition()：**相对于绑定该 holder 的 Adapter 的位置，已消化待处理的 Adapter 更新。条目已被删除、holder 未绑定或 RecyclerView 已脱离时返回 NO_POSITION（-1），使用前必须判断。
2. **getAbsoluteAdapterPosition()：**相对于 RecyclerView 顶层 Adapter 的位置。只有 ConcatAdapter 这类组合场景下它才与 Binding 版本不同。
3. **getLayoutPosition()：**上一次布局计算时的位置，数据变化后到下一帧布局前可能落后一拍。它与屏幕上当前看到的内容对应，适合动画或装饰绘制。

getAdapterPosition() 自 recyclerview 1.2.0 起废弃，语义等同 getBindingAdapterPosition()，迁移时按是否在 ConcatAdapter 内选用 Binding 或 Absolute 版本。

推荐写法是在 onCreateViewHolder 内注册一次点击监听，回调中用 holder.getBindingAdapterPosition() 现取并判断 NO_POSITION：

```kotlin
override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
    val holder = ItemHolder(inflater.inflate(R.layout.item, parent, false))
    holder.itemView.setOnClickListener {
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) onItemClick(items[pos])
    }
    return holder
}
```

在 onBindViewHolder 内每次新建监听并捕获 position 的写法，既在每次绑定时分配对象，又会在该条目被删除后用过期下标访问数据而越界。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 ViewHolder.getBindingAdapterPosition、getAbsoluteAdapterPosition 与 getLayoutPosition。

**Q4: [learning] ItemDecoration 的 getItemOffsets、onDraw 与 onDrawOver 分别在什么阶段生效？多个装饰如何叠加？**

ItemDecoration 通过三个回调介入布局与绘制：getItemOffsets 在测量子视图时把装饰占位累加进子视图的 insets，从而影响条目的位置与尺寸。onDraw 在 RecyclerView.onDraw 中、子视图绘制之前执行，画在条目之下。onDrawOver 在 RecyclerView.draw 中、子视图绘制之后执行，画在条目之上。多个装饰按添加顺序依次执行，各自的 offsets 累加。

三个回调的生效阶段与适用场景如下：

1. **getItemOffsets：**RecyclerView 为每个子视图依次调用全部装饰的 getItemOffsets，把四边结果累加成 decor insets。LayoutManager 的 measureChildWithMargins 与 layoutDecoratedWithMargins 会计入这些 insets，所以自定义 LayoutManager 必须用 Decorated 系列方法，分割线与间距才会生效。
2. **onDraw：**适合分割线和背景块。条目有不透明背景时会盖住这里画的内容。
3. **onDrawOver：**适合吸顶头部、遮罩和角标，始终位于条目之上。

叠加与刷新规则有三条：

1. addItemDecoration(decor, index) 可指定执行顺序，不传索引则追加到末尾。
2. 装饰的参数变化后调用 invalidateItemDecorations()，让所有子视图重新计算 offsets。
3. 回调内取条目位置用 getChildAdapterPosition(child) 现算，不要缓存。回调拿到的 RecyclerView.State 可用 isPreLayout 区分预布局阶段。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 RecyclerView.draw、RecyclerView.onDraw、getItemDecorInsetsForChild 与 LayoutManager.layoutDecoratedWithMargins。

**Q5: [learning] hasStableIds 为 true 时复用与动画行为有什么变化？ID 不唯一会怎样？**

开启稳定 ID 后，RecyclerView 在按位置查找失败时还能按 getItemId 在 scrap 与一级缓存中找回同一条目的 ViewHolder，notifyDataSetChanged 不再清空一级缓存，变更动画也能用 ID 配对新旧 holder。代价是 ID 必须真实稳定且唯一：两个 holder 共享同一个 ID 会在变更动画处理中抛 IllegalStateException。

稳定 ID 改变三处行为：

1. **查找：**tryGetViewHolderForPositionByDeadline 在位置查找失败后、ViewCacheExtension 与回收池之前，按 ID 查 scrap 与 mCachedViews。
2. **全量刷新：**markKnownViewsInvalid 把一级缓存全部标记为 UPDATE 与 INVALID，只有在 Adapter 没有稳定 ID 时才整体清空。有稳定 ID 的 holder 被找回后重新绑定即可复用。
3. **动画配对：**变更动画用 ID 作为 change key 配对预布局与真布局的 holder。ID 重复时 handleMissingPreInfoForChangeError 抛出 Two different ViewHolders have the same stable ID。

何时开启取决于条目是否有稳定主键：有主键且会频繁整表刷新时收益最大。拿不准唯一性就改用 DiffUtil 按内容比较，ListAdapter 与 AsyncListDiffer 都不要求稳定 ID。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 Recycler.markKnownViewsInvalid、Recycler.tryGetViewHolderForPositionByDeadline 与 RecyclerView.handleMissingPreInfoForChangeError。

**Q6: [learning] DiffUtil 如何计算最小变更集并识别移动？areItemsTheSame 与 areContentsTheSame 的判据怎样分工？**

DiffUtil 用 Myers 差分算法求出把旧列表变成新列表所需的最少增删操作，占 O(N) 空间、期望 O(N + D²) 时间，D 为编辑脚本长度。Myers 算法不识别移动，detectMoves 为 true 时再做一遍 O(M × N) 的配对，把“删除加新增”的同一条目合并为移动，M 与 N 分别是新增总数和删除总数。两个回调的分工是：areItemsTheSame 按身份（主键）判断是否同一条目，areContentsTheSame 按内容判断是否需要重新绑定。

按计算到分发的顺序，机制有五点：

1. **算法：**calculateDiff 分治求出全部匹配对角线，得到增删操作。detectMoves 默认为 true。
2. **移动检测：**列表天然按同一约束排序（如时间戳）时可关闭移动检测，省掉额外开销。
3. **判据分工：**areItemsTheSame 返回 false 视为不同条目，产生删除加新增或移动。返回 true 时再比较 areContentsTheSame，为 false 则产生变更，并调用 getChangePayload 生成局部刷新载荷。两者写反会把移动算成变更，动画全乱。
4. **分发：**DiffResult.dispatchUpdatesTo(adapter) 按顺序调用 notifyItemRangeInserted、Removed、Moved 与 Changed。调用前 Adapter 的数据源必须已替换为新列表，否则位置对不上。
5. **线程：**计算是纯函数、不碰 View，可在后台线程执行。大列表在主线程计算会卡帧，ListAdapter 与 AsyncListDiffer 正是把计算放到后台、在主线程分发的封装。提交给 submitList 的 List 不得再原地修改，每次传新实例。

三种接入方式按控制力递增分别是 ListAdapter、AsyncListDiffer 和直接调用 DiffUtil.calculateDiff。更新策略的性能取舍见 [View 与 Compose 渲染实战](../05-graphics/12-View与Compose实践.md)。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 DiffUtil 类注释、DiffUtil.calculateDiff 与 DiffUtil.DiffResult。

**Q7: [learning] ItemAnimator 的四类动画分别在何时触发？为什么 notifyItemChanged 默认会出现闪烁？**

布局第三步对比预布局与真布局两轮记录的视图信息，把差异分成四类交给 ItemAnimator：消失（animateDisappearance）、出现（animateAppearance）、持续（animatePersistence）和变更（animateChange）。notifyItemChanged 触发变更动画时，默认的 DefaultItemAnimator 为变更条目创建新 ViewHolder，旧 holder 淡出、新 holder 淡入，视觉上就是一次闪烁。

四类动画的来源如下：

1. **消失：**预布局中存在、真布局中不存在的条目。
2. **出现：**预布局中不存在、真布局中存在的条目。
3. **持续：**两轮都存在且位置或尺寸变化的条目。
4. **变更：**被标记为更新的条目。

变更动画出现两个 holder 的原因与消除闪烁的方法有四条：

1. **默认判定：**SimpleItemAnimator.canReuseUpdatedViewHolder 在支持变更动画且 holder 有效时返回 false，RecyclerView 因此为变更条目重新获取 holder，旧 holder 进入 mChangedScrap 供动画使用。DefaultItemAnimator 在 payload 非空时返回 true。
2. **交叉淡入：**DefaultItemAnimator.animateChange 在新旧 holder 不同时让旧视图透明度降到 0、新视图从 0 升到 1。新旧 holder 相同时退化为移动动画。
3. **消除闪烁：**使用带 payload 的 notifyItemChanged(position, payload) 走复用路径。或者对 SimpleItemAnimator 调用 setSupportsChangeAnimations(false)。或者自定义 ItemAnimator 覆写 canReuseUpdatedViewHolder 返回 true。
4. **动画期间的回收：**动画中的 holder 处于 setIsRecyclable(false) 状态，dispatchAnimationFinished 后才回收。动画未结束时 RecyclerView 持续请求下一帧。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 RecyclerView.dispatchLayoutStep3、ItemAnimator 与 SimpleItemAnimator 的 canReuseUpdatedViewHolder、DefaultItemAnimator.animateChange。

**Q8: [learning] 自定义 LayoutManager 的最小契约是什么？onLayoutChildren 为什么必须幂等？**

LayoutManager 唯一的抽象方法是 generateDefaultLayoutParams。最小闭环还要实现 onLayoutChildren（从 Recycler 取视图并摆放），以及 canScrollVertically 或 canScrollHorizontally 与对应的 scrollVerticallyBy 或 scrollHorizontallyBy（返回实际消耗的像素）。onLayoutChildren 在预布局、真布局和自适应测量时会被多次调用，所以必须每次从锚点重算，不能在方法内累积状态。

按职责，契约有六条：

1. **取视图：**一律经 recycler.getViewForPosition(position) 获取，由 Recycler 决定复用还是创建。自己 inflate 会绕过缓存与绑定。
2. **摆放：**用 measureChildWithMargins 测量、layoutDecoratedWithMargins 摆放，让 ItemDecoration 的 offsets 与 margin 参与排版。
3. **回收：**布局开始时 detachAndScrapAttachedViews(recycler) 把现有子视图暂存到 scrap。布局结束后 RecyclerView 自动回收未被重新使用的 scrap。滚动时对滚出屏幕的视图调用 removeAndRecycleView。
4. **滚动返回值：**scrollVerticallyBy 返回实际消耗的 dy，到达边界时小于请求值。RecyclerView 据此触发过滚效果与嵌套滚动分发。
5. **测量：**isAutoMeasureEnabled 在基类默认返回 false。返回 true 时 RecyclerView 在 onMeasure 中直接布局子视图再反推内容尺寸，LinearLayoutManager 与 StaggeredGridLayoutManager 都返回 true，wrap_content 场景可能布局两轮。
6. **预取：**实现 collectAdjacentPrefetchPositions 等钩子后才有 GapWorker 预取收益。

幂等的原因是预布局用旧位置真跑一轮 onLayoutChildren 以记录动画前的状态，真布局再跑一轮应用更新。若方法内累积偏移量，第二轮会在第一轮结果上继续偏移，布局错位。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 LayoutManager.generateDefaultLayoutParams、LayoutManager.isAutoMeasureEnabled、LinearLayoutManager.isAutoMeasureEnabled 与 RecyclerView.dispatchLayoutStep1、dispatchLayoutStep2。

**Q9: [learning] ConcatAdapter 如何让多个 Adapter 的 viewType 与稳定 ID 互不冲突？什么时候应关闭隔离？**

ConcatAdapter 的默认配置 Config.DEFAULT 是 isolateViewTypes 为 true、stableIdMode 为 NO_STABLE_IDS：每个子 Adapter 的本地 viewType 被映射到全局唯一值，位置换算与通知转发由 ConcatAdapterController 完成，子 Adapter 各自独立调用 notify 方法。只有各子 Adapter 的相同 viewType 确实代表相同布局并希望共享回收池时，才把 isolateViewTypes 设为 false。

按隔离对象，规则有四条：

1. **viewType 隔离：**ViewTypeStorage 为每个子 Adapter 的每个本地 viewType 分配全局 viewType。关闭隔离后本地值直接当全局值使用，两个子 Adapter 同值不同布局会在回收池串台。
2. **稳定 ID 模式：**NO_STABLE_IDS 忽略子 Adapter 的 ID。ISOLATED_STABLE_IDS 把各子 Adapter 的 ID 映射为全局唯一。SHARED_STABLE_IDS 要求子 Adapter 自行保证全局唯一。
3. **位置换算：**holder.getBindingAdapterPosition() 返回子 Adapter 内的位置，holder.getAbsoluteAdapterPosition() 返回拼接后的全局位置。
4. **通知转发：**子 Adapter 的 notify 调用经 controller 换算为全局位置后转发给 RecyclerView。子 Adapter 改数据不通知会造成全局位置错位。

典型用法是头部 Adapter、内容 Adapter 与底部加载态 Adapter 各自独立，用 addAdapter 依次拼接后再 setAdapter。

依据：本地 androidx recyclerview 源码快照（1.4 主线）的 ConcatAdapter.Config、ConcatAdapterController 与 ViewTypeStorage。
