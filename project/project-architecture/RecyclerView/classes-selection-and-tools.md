# selection 模块与工具模块 · 全类职责表

> 锚点：commit `f38a5e5` ｜ 覆盖：recyclerview-selection `src/main` 41 个顶层类型（public 22 + 包私有 19）+ lint / benchmark 模块清单。

## recyclerview-selection 顶层类型（41）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| SelectionTracker（EX: SelectionTracker.java:131，抽象） | 多选引擎门面契约：主选择/临时选择的增删查 + 范围选择 + 状态保存 + Builder 装配 | Builder(:683)、build() 总装(:887) |
| SelectionTracker.Builder（内部） | 五参装配器：键提供器/详情查找器/存储策略必填，谓词/框选/拖拽监听可选 | DefaultSelectionTracker |
| SelectionTracker.SelectionPredicate（内部，抽象） | 选择策略钩子：单选/多选与单条目能否选中的裁决 | SelectionPredicates 工厂 |
| SelectionTracker.SelectionObserver（内部，抽象） | 选择集变化通知（含 destroy 回调） | OperationMonitor 配合 |
| DefaultSelectionTracker（EX: DefaultSelectionTracker.java:72） | 选中状态的唯一权威：主选择 + 临时（provisional）双账本，merge/clear 成对收口 | Selection、Range、EventBridge、ResetManager |
| Selection（EX: Selection.java:76） | 选中键集合（可迭代），带"是否为空"的查询语义 | MutableSelection 继承 |
| MutableSelection（EX: MutableSelection.java:67） | 可变选择集：copySelection 快照的落点 | SelectionTracker.copySelection |
| ItemKeyProvider（EX: ItemKeyProvider.java:49，抽象） | 选择键 ↔ position 换算契约：两档能力（SCOPE_MAPPED 全量 / SCOPE_CACHED 仅缓存） | StableIdKeyProvider、框选可行性判定 |
| StableIdKeyProvider（EX: StableIdKeyProvider.java:76） | 基于稳定 id 的键提供器：ViewHolder attach/detach 时维护键↔位置映射 | ViewHost（测试替身隔离壳，:220） |
| ItemDetailsLookup（EX: ItemDetailsLookup.java:78，抽象） | 从 MotionEvent 位置取 ItemDetails 的查找契约（应用实现） | 手势选中判定 |
| StorageStrategy（EX: StorageStrategy.java:65，抽象） | 选择键在 Bundle 中的类型安全持久化策略（Long/String/Parcelable 三工厂） | onSavedInstanceState |
| SelectionPredicates（EX: SelectionPredicates.java:38） | 常用 SelectionPredicate 工厂（单选任意/单选必选/多选） | Builder.withSelectionPredicate |
| OperationMonitor（EX: OperationMonitor.java:66） | "选择操作进行中"的可观测标志（测试与 UI 防重入用） | GestureSelectionHelper 等 |
| Range（EX: Range.java:48，包私有） | 范围选择状态机：锚点 + 起终点，extendRange 的增量计算 | DefaultSelectionTracker、RangeCallbacks |
| BandSelectionHelper（EX: BandSelectionHelper.java:81，包私有） | 鼠标框选（lasso）控制器：GridModel 追踪覆盖区域并临时选中 | GridModel、DefaultBandHost、BandPredicate |
| DefaultBandHost（EX: DefaultBandHost.java:58，包私有） | GridModel.GridHost 的宿主适配：把网格坐标查询接到真实视图 | BandSelectionHelper |
| GridModel（EX: GridModel.java:79，包私有） | 框选几何模型：把指针位置映射成"最近条目/覆盖区间"（1100 行核心算法） | ItemKeyProvider、GridHost |
| GestureSelectionHelper（EX: GestureSelectionHelper.java:57，包私有） | 长按拖拽手势选择控制器：滑动轨迹 → 临时选中范围，抬手合并 | TouchInputHandler.mGestureStarter、AutoScroller |
| AutoScroller（EX: AutoScroller.java:42，抽象） | 边缘自动滚动策略接口（框选/拖拽到边缘时持续滚屏） | ViewAutoScroller |
| ViewAutoScroller（EX: ViewAutoScroller.java:61，包私有） | 上者的视图实现：按边缘距离算滚动速度 | GestureSelectionHelper、BandSelectionHelper |
| BandPredicate（EX: BandPredicate.java:52，抽象） | "此位置能否发起框选"的判定（如非拖动区/非空区域） | Builder.withBandPredicate |
| TouchInputHandler（EX: TouchInputHandler.java:52，包私有） | 触摸语义总管：tap/长按拖选/滑动手势到 tracker 动作的映射 | MotionInputHandler、GestureSelectionHelper |
| MouseInputHandler（EX: MouseInputHandler.java:51，包私有） | 鼠标语义总管：左键点选/Shift 区间/Ctrl 切换/右键框选 | MotionInputHandler、BandSelectionHelper |
| MotionInputHandler（EX: MotionInputHandler.java:51，包私有抽象） | 触摸/鼠标共用的手势监听基类（SimpleOnGestureListener） | ToolSourceHandlerRegistry 注册 |
| GestureDetectorWrapper（EX: GestureDetectorWrapper.java:47，包私有） | 框架 GestureDetector 的可测包装 | GestureRouter |
| GestureRouter（EX: GestureRouter.java:50，包私有） | 手势事件按 tool-type 路由到对应 handler | ToolSourceKey、ToolSourceHandlerRegistry |
| EventRouter（EX: EventRouter.java:51，包私有） | RecyclerView 触摸事件的首站路由：分发给 mouse/touch 语义线 | ResetManager、OnItemTouchListener 挂点 |
| EventBackstop（EX: EventBackstop.java:45，包私有） | 事件兜底监听：无人认领的 DOWN 用于复位状态 | ResetManager |
| EventBridge（EX: EventBridge.java:53） | Adapter 数据变化 → tracker 的桥：position 位移时同步选择集 | AdapterDataObserver、SelectionTracker |
| ResetManager（EX: ResetManager.java:58，包私有） | 统一复位收口：数据/结构变化时把全部 Resettable 组件拉回初始态 | 各 Resettable 组件 |
| Resettable（EX: Resettable.java:44） | 可复位契约（数据变化时强制清手势/临时态） | ResetManager |
| ResetSignal（ResetManager 内） | 复位原因枚举（数据变化/策略变化） | ResetManager |
| DisallowInterceptFilter（EX: DisallowInterceptFilter.java:47，包私有） | requestDisallowInterceptTouchEvent 的过滤决策 | EventRouter |
| MotionEvents（EX: MotionEvents.java:44，包私有） | MotionEvent 工具：按键判定/多指工具函数 | 各 InputHandler |
| PointerDragEventInterceptor（EX: PointerDragEventInterceptor.java:47，包私有） | 指针拖拽事件拦截（外接拖放场景） | EventRouter |
| ToolSourceHandlerRegistry（EX: ToolSourceHandlerRegistry.java:55，包私有） | 按 ToolSourceKey（工具类型×按键）注册/分发 handler | GestureRouter |
| ToolSourceKey（EX: ToolSourceKey.java:60） | "工具类型×按键"的路由键 | ToolSourceHandlerRegistry |
| OnItemActivatedListener（EX: OnItemActivatedListener.java:43） | 条目激活（点击/双击/回车）回调契约 | InputHandler 上报 |
| OnDragInitiatedListener（EX: OnDragInitiatedListener.java:49） | 拖放发起回调契约（选择库→应用拖放） | InputHandler 上报 |
| OnContextClickListener（EX: OnContextClickListener.java:42） | 右键/长按上下文点击回调 | MouseInputHandler |
| Shared（EX: Shared.java:33，包私有） | 模块内共享小工具（防抖/判定） | 各组件 |

## lint / benchmark 模块清单

| 文件 | 一行职责 | 关键协作 |
|---|---|---|
| RecyclerViewIssueRegistry.kt（EX: :29） | lint 规则注册入口：issues 列表仅含 InvalidSetHasFixedSize 一条，附 vendor 反馈地址 | IssueRegistry |
| InvalidSetHasFixedSizeDetector.kt（EX: :45） | 双阶段探测：先扫 XML 布局收集"宽/高为 wrap_content 的 RecyclerView id"（:66），再拦 setHasFixedSize(true) 调用并沿 receiver PSI 树回溯 findViewById，id 命中名单才报错 | XmlScanner + SourceCodeScanner |
| DiffBenchmark.kt（EX: :31） | diff 耗时基准：Input 参数化列表规模，measureRepeated 度量 | androidx.benchmark |
| ScrollBenchmark.kt（EX: :37） | 滚动帧耗时基准：5 个场景 measureRepeatedOnMainThread，用 ZeroSizePool（:143）隔离回收池影响 | androidx.benchmark |
| RecyclerViewActivity.kt（EX: :25） | 基准宿主 Activity | — |

## 跳过清单（附理由）

- selection `src/test`、lint `src/test`（Stubs.kt 等）全部测试类：测试作行为规格参考，不进类表。
- res/ 下的框选 overlay 等资源：非类型。

## 覆盖率

selection `src/main` 顶层类型 41/41 入表（100%）；lint 2 文件 + benchmark 3 文件全列入清单（100%）。
