# RecyclerView 主模块 · 全类职责表

> 锚点：commit `f38a5e5` ｜ 覆盖：`recyclerview/src/main` 下 42 个顶层类型（26 public + 16 包私有引擎类）+ RecyclerView 的 26 个内部类型。每个"一行职责"锚定类声明行（EX: file:line）。
> 跳过清单见文末。行号随上游演进会漂移，以类名检索为准。

## 顶层类型（42）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| RecyclerView（EX: RecyclerView.java:267） | 门面+引擎宿主：把 Adapter 数据与 LayoutManager 策略编排成"测量→布局→绘制→滚动"管线，布局按 mLayoutStep 三步状态机走 | LayoutManager、Recycler、AdapterHelper、ViewInfoStore |
| AdapterHelper（EX: AdapterHelper.java:66，包私有） | notify* 更新的暂存队列与位置换算器：布局/滚动前批量消费，消费前提供 pre-layout 偏移预演（findPositionOffset） | OpReorderer（重排 op）、RecyclerViewDataObserver |
| AdapterListUpdateCallback（EX: AdapterListUpdateCallback.java:45） | 把 ListUpdateCallback 的增/删/改/移事件翻译成 adapter.notify* 的胶水 | DiffResult.dispatchUpdatesTo 的默认出口 |
| AsyncDifferConfig（EX: AsyncDifferConfig.java:54） | AsyncListDiffer 的不可变配置包：主线程执行器 + 后台执行器 + diff 回调 | AsyncListDiffer、ListAdapter |
| AsyncListDiffer（EX: AsyncListDiffer.java:137） | 后台 diff 的列表托管者：持有当前列表，submitList 后台算差异，主线程原子切换并分发 | DiffUtil、Executor、ListUpdateCallback |
| AsyncListUtil（EX: AsyncListUtil.java:71） | 分页数据加载器：把大数据集按 Tile 异步加载进内存窗口，内容回调驱动刷新 | ThreadUtil、TileList、Callback |
| BatchingListUpdateCallback（EX: BatchingListUpdateCallback.java:56） | 合并相邻同类型的更新事件（连续 insert 合成区间），减少 notify 次数 | DiffResult 分发链的包装层 |
| ChildHelper（EX: ChildHelper.java:54，包私有） | 子视图管理封装：在 ViewGroup 之上多一层"隐藏视图"账本，对 LayoutManager 呈现过滤后的子视图列表 | Callback（由 RecyclerView 实现，直通 ViewGroup） |
| ConcatAdapter（EX: ConcatAdapter.java:92） | 多子 Adapter 拼接的门面：自身零逻辑，全部转发 controller | ConcatAdapterController、Config |
| ConcatAdapterController（EX: ConcatAdapterController.java:68，包私有） | 拼接引擎：为各子 Adapter 建 viewType/stableId 全局命名空间映射并做位置换算转发 | NestedAdapterWrapper、ViewTypeStorage、StableIdStorage |
| DefaultItemAnimator（EX: DefaultItemAnimator.java:62） | 默认条目动画器：位移 + 淡入淡出，实现 SimpleItemAnimator 的五个语义动画 | SimpleItemAnimator、ViewHolder |
| DiffUtil（EX: DiffUtil.java:102） | Myers 差分静态工具：算两列表最小编辑脚本（O(N+D²)），并把结果翻译成更新事件流 | Callback/ItemCallback、DiffResult、ListUpdateCallback |
| DividerItemDecoration（EX: DividerItemDecoration.java:59） | 按 LinearLayout 方向画条目分割线的装饰 | ItemDecoration 挂点 |
| FastScroller（EX: FastScroller.java:58，包私有） | 滚动条拖拽交互：拇指拖动映射为 scrollToPosition | ItemDecoration + OnItemTouchListener 双挂点 |
| GapWorker（EX: GapWorker.java:58，包私有） | 帧间隙预取调度器（ThreadLocal 单例）：收集全部列表的预取任务，按 deadline 排序执行 | Recycler.tryGet（带 deadline）、LayoutManager 预取钩子 |
| GridLayoutManager（EX: GridLayoutManager.java:68） | 等宽网格策略：继承 LLM，把 fill 的"一块"定义为一行/列并做 span 分配 | LinearLayoutManager、SpanSizeLookup |
| ItemTouchHelper（EX: ItemTouchHelper.java:97） | 拖拽排序 + 滑动删除引擎：ItemDecoration（绘制）+ OnItemTouchListener（手势）双身份，select() 为唯一状态转移点 | Callback（应用实现）、RecoverAnimation、ItemAnimator |
| ItemTouchUIUtil（EX: ItemTouchUIUtil.java:49） | 拖拽/滑动视觉效果的版本分层绘制接口（API21 前后不同实现） | ItemTouchHelper.Callback.onChildDraw |
| ItemTouchUIUtilImpl（EX: ItemTouchUIUtilImpl.java:46，包私有） | 上一行的实现：API21+ 用 elevation/translationY，旧版本降级 | Canvas、View |
| LayoutState（EX: LayoutState.java:40，包私有） | SGLM 专用的填充状态：方向、可用像素、当前位置、回收策略 | StaggeredGridLayoutManager.fill |
| LinearLayoutManager（EX: LinearLayoutManager.java:73） | 线性布局策略：先解析锚点再向两端 fill，滚动 = 平移 + 补边 + 回收 | OrientationHelper、LayoutState（内部版）、AnchorInfo |
| LinearSmoothScroller（EX: LinearSmoothScroller.java:58） | 像素速度可控的平滑滚动实现：每帧按目标速度加速/减速 | SmoothScroller.Action、DecelerateInterpolator |
| LinearSnapHelper（EX: LinearSnapHelper.java:58） | fling 后吸附到最近条目中心 | SnapHelper 框架、LinearSmoothScroller |
| ListAdapter（EX: ListAdapter.java:112） | 内置异步 diff 的 Adapter 基类：submitList 一行刷新， getItem 时按"提交中/已生效"两份列表分发 | AsyncListDiffer、AsyncDifferConfig |
| ListUpdateCallback（EX: ListUpdateCallback.java:42） | 增/删/改/移四事件的纯数据层接口（零 android 依赖） | DiffUtil、Batching、AdapterListUpdateCallback |
| MessageThreadUtil（EX: MessageThreadUtil.java:45，包私有） | ThreadUtil 的 Handler 实现：后台↔主线程的消息化双端 | AsyncListUtil |
| NestedAdapterWrapper（EX: NestedAdapterWrapper.java:48，包私有） | 单个子 Adapter 的包装：位置/viewType 全局换算、观察者隔离 | ConcatAdapterController、ViewTypeStorage |
| OpReorderer（EX: OpReorderer.java:37，包私有） | 把 update op 序列重排成可正确应用的顺序（move 与 add/remove 的相对次序修正） | AdapterHelper 内部消费 |
| OrientationHelper（EX: OrientationHelper.java:46） | 横/纵方向抽象：同一套布局代码跑两个方向 + RTL | LayoutManager、View 系坐标 |
| PagerSnapHelper（EX: PagerSnapHelper.java:63） | fling 后一次一页的吸附（每次只跨一页） | SnapHelper 框架 |
| RecyclerViewAccessibilityDelegate（EX: RecyclerViewAccessibilityDelegate.java:58） | 无障碍桥：把子条目角色/动作接到 AccessibilityNodeInfo | RecyclerView、ItemDelegate |
| ScrollbarHelper（EX: ScrollbarHelper.java:35，包私有） | 滚动条拇指尺寸/偏移的数学计算 | FastScroller |
| SimpleItemAnimator（EX: SimpleItemAnimator.java:55） | 把 ItemAnimator 四类回调收敛成 add/remove/move/change 五个语义方法 | DefaultItemAnimator 等实现 |
| SnapHelper（EX: SnapHelper.java:59） | fling 接管式吸附框架：挂 OnFlingListener/OnScrollListener，算目标吸附点并补滚动 | OnFlingListener 挂点、SmoothScroller |
| SortedList（EX: SortedList.java:62） | 自动排序 + 增量通知的数据容器：单副本内存维护有序列表 | Callback（排序/相等判定）、BatchingListUpdateCallback |
| SortedListAdapterCallback（EX: SortedListAdapterCallback.java:39） | SortedList 回调到 adapter.notify* 的桥 | SortedList、Adapter |
| StableIdStorage（EX: StableIdStorage.java:42，包私有接口） | ConcatAdapter 的 stableId 命名空间策略（隔离式/共享式） | ConcatAdapterController |
| StaggeredGridLayoutManager（EX: StaggeredGridLayoutManager.java:71） | 瀑布流策略：多列独立偏移、full span、间隙修补（LaymanManager 无此能力） | Span（内部类）、ViewBoundsCheck、LayoutState |
| ThreadUtil（EX: ThreadUtil.java:36，包私有接口） | 后台/主线程双端消息接口（loadTile、回收 Tile 的异步协议） | MessageThreadUtil、AsyncListUtil |
| TileList（EX: TileList.java:44，包私有） | 按 tile 位置有序的平铺集合（获得/清除按位置对齐） | AsyncListUtil |
| ViewBoundsCheck（EX: ViewBoundsCheck.java:41，包私有） | 子视图越界检测通用算法（方向无关的边界回调） | SGLM 找可见条目 |
| ViewInfoStore（EX: ViewInfoStore.java:57，包私有） | pre/post-layout 信息记账本：推导 ItemAnimator 四类动画的差异源 | InfoRecord、RecyclerView Step1/Step3 |
| ViewTypeStorage（EX: ViewTypeStorage.java:48，包私有接口） | ConcatAdapter 的 viewType 命名空间策略（隔离式/共享式） | ConcatAdapterController、NestedAdapterWrapper |

## RecyclerView 内部类型（26）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Adapter（EX: RecyclerView.java:9492，抽象） | "数据 → ViewHolder"翻译契约：三必须方法 + notify* 事件源 | RecyclerViewDataObserver、Recycler |
| ViewHolder（EX: :14273，抽象） | 复用最小单元：视图 + 位置双轨（adapterPosition/layoutPosition）+ 标志位状态 | Recycler、AdapterHelper |
| LayoutManager（EX: :10351，抽象） | 布局策略契约：测量/摆放/滚动/焦点/预取钩子；反向持有宿主内部（:10359-10360） | mRecyclerView、mChildHelper、Recycler |
| Recycler（EX: :8078） | 五级缓存获取与回收引擎：按"代价升序"逐级查找，miss 才 create+bind | tryGetViewHolderForPositionByDeadline（:8388）、Pool |
| RecycledViewPool（EX: :7672） | 按 viewType 分池的跨列表共享回收池（每类型默认 5） | recycleViewHolderInternal（:8737）降级端 |
| ViewCacheExtension（EX: :9436，抽象） | 用户自定义缓存扩展点：只"取"不"存"，插在 mCachedViews 与池之间 | tryGet 第三级查找 |
| ItemAnimator（EX: :16693，抽象） | 条目动画契约：pre/post 两段信息记录 + 四类动画回调 + 完成必须回执 | ViewInfoStore、dispatchLayoutStep3 |
| ItemDecoration（EX: :13921，抽象） | 绘制挂件：onDraw（子视图下）/onDrawOver（子视图上）夹心两段 | RecyclerView.draw |
| State（EX: :16225） | 布局状态容器：mLayoutStep 状态机（:16283）+ 条目数 + 剩余滚动量 + 任意数据总线 | 布局三步、assertLayoutStep |
| SavedState（EX: :16143） | LM 状态序列化外壳（ClassLoader 感知） | onSaveInstanceState |
| LayoutParams（EX: :15207） | 携带 viewType 与装饰 insets 的布局参数 | measureChild、InsetsDirty |
| AdapterDataObserver（EX: :15378，抽象） | notify* 观察者契约（数据集变/增删改移/状态恢复策略） | RecyclerViewDataObserver 实现 |
| AdapterDataObservable（包私有） | notify* 广播的实现（观察者注册与遍历） | Adapter.registerAdapterDataObserver |
| SmoothScroller（EX: :15451，抽象） | 平滑滚动框架：每帧由 LM 的 scrollBy 驱动，算停点并下指令 | Action、LayoutManager |
| SmoothScroller.Action（EX: :15802） | 单帧滚动指令包（位移 + 跳转 + 插值器） | LinearSmoothScroller |
| SmoothScroller.ScrollVectorProvider（EX: :16020，接口） | 提供目标点方向向量的 LM 钩子 | LinearSmoothScroller 对齐用 |
| EdgeEffectFactory（EX: :7586） | 边缘光效工厂：四个方向各自创建 EdgeEffect | draw 阶段光效 |
| LayoutPrefetchRegistry（EX: :10536，接口） | LM 上报预取位置（position, distance）的接口 | GapWorker、LayoutPrefetchRegistryImpl |
| Properties（EX: :13886，接口） | 状态保存用的属性键常量集 | onSaveInstanceState |
| OnItemTouchListener（EX: :14039，接口） | 手势拦截挂件契约：可在 RecyclerView 之前抢走整条触摸流 | ItemTouchHelper、FastScroller |
| SimpleOnItemTouchListener（EX: :14102） | 上者的空实现适配器 | 应用快捷继承 |
| OnScrollListener（EX: :14142，抽象） | 滚动状态与偏移变化监听 | dispatchOnScrolled |
| RecyclerListener（EX: :14189，接口） | 视图被回收时的通知钩子 | recycleView |
| OnChildAttachStateChangeListener（EX: :14221，接口） | 子视图 attach/detach 通知（ItemTouchHelper 靠它清理） | onChildAttachedToWindow |
| OnFlingListener（EX: :16592，接口） | fling 接管点：返回 true 表示惯性已被处理 | SnapHelper 挂载点 |
| ItemAnimatorFinishedListener（EX: :17454，接口） | "全部动画是否结束"的异步查询回调 | ItemAnimator.isRunning |

## 跳过清单（附理由）

- `GET.java`（recyclerview/src/main/java/GET.java）：**误植文件**——内容是 Square Retrofit 2 的 `retrofit2.http.GET` 注解（2013 Square 版权头），与 recyclerview 无任何引用关系，不属本项目架构。建议后续从源码树移除（本 skill 只读不改，留此记录）。
- `androidTest/`、`test/` 源集全部测试类：测试作行为规格使用（正文 §2/§6 已引用），不进类表。
- 各顶层类型内部的私有辅助类（如 LLM 的 AnchorInfo/LayoutState 内部版、SGLM 的 Span、DiffUtil 的 Snake/Diagonal/Range、ITH 的 RecoverAnimation）：已在其宿主深卡片/正文提及，不单列。

## 覆盖率

主模块 `src/main` 顶层类型 42/42 入表（100%）+ RecyclerView 内部类型 26/26 入表（100%）+ 跳过清单 1 项（GET.java，非本库）。
