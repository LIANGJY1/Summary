# UI 辅助服务与视图结构采集

> 学习资料（文章模式沉淀）。本文解释 Accessibility、ContentCapture 与 Autofill 对应用视图结构的请求方向、线程路径和性能边界。Q 序列即结构。

**Q1: [learning] Accessibility 的事件上报与节点查询为什么方向相反，oneway 为什么不等于零成本？**

事件上报由应用推给系统，节点查询由辅助服务向应用拉取。区分方向后才能判断哪条线程承担构造成本：

1. **事件上报：**AAOS13 的 `IAccessibilityManager.sendAccessibilityEvent()` 是 oneway，应用不等待服务处理，但事件构造、字符串复制、Parcel 写入和 Binder 入队仍消耗应用与 system_server 资源。高频事件并非零成本。
2. **节点查询：**辅助服务调用 `getRootInActiveWindow()` 等接口时，客户端先查 AccessibilityCache；未命中才向目标应用的 AccessibilityInteractionController 请求，在 ViewRoot looper 上构造节点。查询可由别的进程的 AccessibilityService 或 UiAutomation 发起，应用无主动 UI 更新也可能出现节点构造。

AAOS13 的 View 侧还有内容变化事件节流，涉及 `ViewRootImpl.SendWindowContentChangedAccessibilityEvent` 和 `ViewConfiguration.getSendRecurringAccessibilityEventsInterval()`。对象复用减少分配，节流减少发送次数，不能把事件上报的 oneway 语义套到节点查询上。

**Q2: [learning] 无障碍节点查询会遍历整棵 View 树吗，prefetch 有哪些边界？**

不会固定遍历整棵树。AAOS13 的查询范围受请求、缓存和预取条件共同约束：

1. **数量：**单次预取上限 `MAX_NUMBER_OF_PREFETCHED_NODES = 50`（`AccessibilityNodeInfo.java`）。
2. **中断：**ViewRoot handler 中已有用户交互消息等待时，可中断的 prefetch 会停止。
3. **滚动：**窗口正在滚动时客户端会清除 prefetch flags。
4. **节点提供者：**普通 View 走 `onInitializeAccessibilityNodeInfo()`；自绘、WebView 等虚拟节点走 AccessibilityNodeProvider，不能从 View 数量推断实际返回节点数。

辅助服务侧 `AccessibilityInteractionClient` 最多等待 5000 ms。目标应用的风险不是这段服务线程等待本身，而是 ViewRoot looper 上新增的节点创建工作可能延后输入或 traversal。定位时同时记录请求 API、prefetch flags、缓存命中、provider 和实际返回节点数，并先查发起查询的服务。

**Q3: [learning] 怎样降低无障碍节点成本而不破坏可访问语义？**

先保留用户必需的语义，再减少重复节点与高频工作：

1. **删除重复语义：**纯装饰图片、分隔线或已由父控件完整表达的子元素可用 `importantForAccessibility="no"`。`NO_HIDE_DESCENDANTS` 隐藏整个子树，只有父节点提供等价信息时才可用；按钮图标、错误提示、金额与开关状态不能无条件隐藏。
2. **缩短查询回调：**`onInitializeAccessibilityNodeInfo()` 与 provider 查询处在 UI 路径，应只读取已准备好的状态。比如行情控件在 `updatePrice()` 更新 contentDescription，查询时直接读取，不在回调里格式化或访问数据库。
3. **控制变化频率：**API 34 起 `setMinDurationBetweenContentChanges()` 可声明高频内容的最小通知间隔，适合进度、计时器与行情；焦点、错误和关键结果仍须及时上报。RecyclerView 用 DiffUtil 精确更新，避免全量刷新扩大语义变化。
4. **验证取舍：**做无服务、目标服务、多服务共存和精简订阅的受控 A/B；产品不能为帧率自动关闭用户的辅助服务，也不能在动画每帧无意义地改语义属性。

**Q4: [learning] ContentCapture 与 Autofill 的触发与线程路径有什么区别？**

两者都可读取视图结构，但发起方、时机和传输方式不同，不能统称“全树扫描”：

1. **ContentCapture：**AAOS13 的 ViewRoot 在窗口首帧成功 draw 后执行初始结构报告，这段遍历在应用 UI 线程。后续出现、消失和文本变化先记在 AttachInfo，traversal 后经应用 BackgroundThread 合并、缓冲，再通过 direct oneway Binder 送到 ContentCaptureService，不必每批经 system_server。
2. **Autofill：**焦点进入或显式 `requestAutofill()` 触发会话。system_server 反向请求应用，`ActivityThread.handleRequestAssistContextExtras()` 在主线程构建 AssistStructure 快照；超过 `IBinder.MAX_IPC_SIZE` 时由 ParcelTransferWriter 分段续传，然后交给密码管理器等 provider。

两条路径均按 AAOS13 源码核对。Autofill 的 `autofillHints` 帮助 provider 识别字段，但不跳过结构采集。结构回调保持纯内存操作，避免解码图片、访问数据库或解析大 JSON 延长主线程任务。
