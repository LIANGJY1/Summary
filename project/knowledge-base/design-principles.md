# 设计思想（跨库总结）

> 各库源码标注中提炼的设计思想，按思想归并；由 source-annotator skill 同步维护。

## 边界

- **收**：核心矛盾、权衡、可迁移的思维方式（"怎么想"）
- **不收**：可执行做法清单与模式化代码实例（归宿：[sdk-design.md](./sdk-design.md)）；套话、叙事、与思想无关的背景
- **分工**：本文档收"怎么想"，sdk-design.md 收"怎么做"；同一条精妙设计两边都够格时各写各的角度，互相不复制

## 规则

- 一个思想一个条目：同名条目并入（可并列第二个代码来源），绝不重复开节
- 条目四段：**一句话** → **核心矛盾**（什么冲突逼出了这个思想，1-2 句）→ **代码实例**（精简片段 ≤ 10 行，标来源）→ **思想提炼**（可迁移的思考方式，1-2 条；每条带**适用条件**——什么场景下这样想/这样做，何时它不成立）
- 扁平 `## 思想名` 节 + 顶部目录索引，新条目 = 追加目录行与节

## 目录

- [资源生命周期分层收口](#资源生命周期分层收口)
- [配置期收集、执行期分发](#配置期收集、执行期分发)
- [执行器按任务耗时分区，短任务池不跑长任务](#执行器按任务耗时分区短任务池不跑长任务)
- [失败可归因：回退不丢证据](#失败可归因回退不丢证据)
- [收缩顺序遵循依赖方向](#收缩顺序遵循依赖方向)
- [昂贵解析一次摊销，不可变装配免加锁](#昂贵解析一次摊销不可变装配免加锁)
- [占位表达进行中，失败必须回滚占位](#占位表达进行中失败必须回滚占位)
- [输入与呈现解耦，位置按"事实/预期"双轨记账](#输入与呈现解耦位置按事实预期双轨记账)
- [扩展面先于扩展实现](#扩展面先于扩展实现)
- [可中断交互的中间态单独建账](#可中断交互的中间态单独建账)
- [增量布局先锚定视觉基准](#增量布局先锚定视觉基准)
- [子组件生命周期全序嵌套，独立时钟承载](#子组件生命周期全序嵌套独立时钟承载)
- [管理者寄生于被管理者的存活机制](#管理者寄生于被管理者的存活机制)
- [快路径先行，慢副作用停靠押后](#快路径先行慢副作用停靠押后)
- [双自治实体靠对称收编维持一致](#双自治实体靠对称收编维持一致)
- [可用性窗口用事件翻转的显式开关表达](#可用性窗口用事件翻转的显式开关表达)
- [迟加入者补课到当下](#迟加入者补课到当下)
- [挂起与取消是两种资源语义](#挂起与取消是两种资源语义)
- [资源启停收敛到活跃度翻转点](#资源启停收敛到活跃度翻转点)
- [真值即时记账，广播延迟撤销](#真值即时记账广播延迟撤销)
- [类型随模式走，负载归容器](#类型随模式走负载归容器)

<!-- 条目模板：

## 思想名

**一句话**：思想是什么（≤ 2 句）。

**核心矛盾**：什么冲突逼出了这个思想（1-2 句）。

**代码实例**（摘自 <库> `<文件路径>`）：

```java
// 精简到能体现思想的片段，≤ 10 行
```

**思想提炼**：
- 可迁移的思考方式 1
- 可迁移的思考方式 2

-->

## 资源生命周期分层收口

**一句话**：同一资源的"在用 / 备用 / 生成中"是三种生命周期状态，用三套机制分别收口（引用计数、LRU、任务表），而不是一套缓存硬扛。

**核心矛盾**：正在显示的 Bitmap 既是"要保住的"又是"内存紧张时该回收的"——单一 LRU 缓存无法同时表达这两种语义，要么在用时被驱逐（崩溃），要么永不驱逐（OOM）。

**代码实例**（摘自 Glide `Engine.java` loadFromMemory）：

```java
// ① 活跃表：在用资源，命中即 acquire 引用计数 +1（不可能被回收）
active = loadFromActiveResources(key);
// ② LRU：备用资源，命中即 remove 并晋升活跃（查一次少一次）
cached = loadFromCache(key);
// ③ 都未命中：查进行中任务表，同 key 并发请求合并回调
return waitForExistingOrStartNewJob(...);
```

**思想提炼**：
- 状态先于缓存：先想清楚资源有哪几种生命周期状态，每种状态配一种机制（计数=强保活，LRU=可牺牲，任务表=去重），缓存只是"备用"态的载体；
- 查找与晋升合并在一步（从 LRU 取出即转入活跃），中间态越少，不变量越牢。

## 配置期收集、执行期分发

**一句话**：配置 API 只收集事实、不做类型分发，把"根据输入决定怎么做"推迟到所有信息齐备的执行期。

**核心矛盾**：配置期调用 `load(url)` 时，尺寸、变换、缓存策略都还没定，ModelLoader 选路所需的信息不全；但直觉上"传了 URL 就该立刻找到加载器"，提前解析会大量白算且信息不足时只能瞎猜。

**代码实例**（摘自 Glide `RequestBuilder.java` loadGeneric）：

```java
private RequestBuilder<TranscodeType> loadGeneric(@Nullable Object model) {
  this.model = model;      // 只存事实，不解析
  isModelSet = true;       // 哨兵：调过 load()（load(null) 合法，不能用 null 判断）
  return selfOrThrowIfLocked();
}
// 执行期（into 之后）才按 model 实际类型从 Registry 查 ModelLoader
```

**思想提炼**：
- "不可再推"是分发的正确时机：信息一齐（target 尺寸、options、model 类型）就分发，早于它白算，晚于它浪费；
- 配置对象与分发表分离：builder 只收集，Registry 只裁决，两者独立演进——加类型不动配置 API，改配置不动分发逻辑。

## 执行器按任务耗时分区，短任务池不跑长任务

**一句话**：按"任务的预期耗时/可并行度"划分线程池，长耗时任务不得滞留在小容量专用池里——否则它会饿死整个专用池的服务对象。

**核心矛盾**：磁盘缓存读取用一个固定单线程池（磁盘 IO 并行无收益，多了反而添乱），但"读缓存未命中 → 继续取网络数据 → 解码"如果就地在这条线程上跑，长耗时的网络/解码任务会把单线程占满，全局所有磁盘缓存请求排队饿死。

**代码实例**（摘自 Glide `DecodeJob.java` runGenerators / reschedule）：

```java
// 缓存级 Generator 未命中，推进到 SOURCE 前必须换池：
if (stage == Stage.SOURCE) {
  reschedule(RunReason.SWITCH_TO_SOURCE_SERVICE);  // 重新入队到源线程池（CPU 核数，上限 4）
  return;
}
// diskCacheExecutor 固定单线程：只承接"读磁盘缓存"这类短耗时任务
```

**思想提炼**：
- 线程池分区不是按功能模块分，而是按"任务的耗时特征与并发收益"分：磁盘 IO 并行无收益→单线程；网络/解码长耗时→按核数扩容；瞬时爆发→无上限弹性池；
- 判断"该不该换池"的准则是：继续留在当前池会不会阻塞池的服务对象（其他同类任务）——会，就重新入队（换池必须重排队，状态用 RunReason 记着以便续跑）。

## 失败可归因：回退不丢证据

**一句话**：多候选回退体系里，失败不只是"终止信号"更是归因信息——逐个候选重试时保留每个候选各自失败的原因，最终一次性把完整失败链交给调用方。

**核心矛盾**：容错要求"单个候选失败就换下一个，别打扰调用方"；可观测性要求"最终失败时调用方要知道为什么"。静默 failover 用丢证据换不打扰，一失败就上抛用打扰换证据——看似只能二选一。

**代码实例**（摘自 Glide `load/engine/DecodePath.java`）：

```java
} catch (IOException | RuntimeException | OutOfMemoryError e) {
  exceptions.add(e);   // 换下一个候选前，先把这一票失败的原因存下来
}
...
throw new GlideException(failureMessage, new ArrayList<>(exceptions));  // 失败树整体上抛
```

**思想提炼**：
- 回退的每个岔路都是一个证据点：容错在"继续走"，证据在"留痕"，二者本不冲突——冲突的只是"当场打断调用方"；
- 最终失败的异常不是一条 message，而是一棵树：调用方按层展开就能定位到具体哪个候选、哪类输入失败，排查成本不转嫁给使用者。

## 收缩顺序遵循依赖方向

**一句话**：多级资源一起收缩时，先收缩"资源的消费者"，再收缩"资源池本身"——顺序颠倒则消费中释放的资源无处安放，收缩白做。

**核心矛盾**：内存收缩要连"缓存里的资源"和"资源上携带着的更底层内存"一起收，但后者只有在前者驱逐时才浮出水面；先收底层会让这批内存漏在池外。

**代码实例**（摘自 Glide `Glide.java`）：

```java
// trimMemory：先广播 RequestManager（请求暂停，活跃资源归还缓存），再收缩缓存池
for (RequestManager manager : managers) { manager.onTrimMemory(level); }
memoryCache.trimMemory(level); bitmapPool.trimMemory(level); arrayPool.trimMemory(level);
// clearMemory：先清 memoryCache（其驱逐 Bitmap 此刻归还 bitmapPool），再统一清空（#687）
memoryCache.clearMemory(); bitmapPool.clearMemory(); arrayPool.clearMemory();
```

**思想提炼**：
- 收缩/清理链的顺序按"谁持有谁"排：消费者先放手，被持有者才能进池，最后清池一网打尽；
- 释放顺序错误不报错、只漏收——这类顺序约束要靠注释钉死（上游以 issue 号佐证），不能靠运行时自愈。

## 昂贵解析一次摊销，不可变装配免加锁

**一句话**：反射等昂贵解析按复用粒度（每方法/每类）只做一次并缓存，解析产物字段全 final，读路径零锁。

**核心矛盾**：每次调用都反射解析，热路径付不起；解析结果要被多线程共享，又不能为读路径引入锁开销。

**代码实例**（摘自 retrofit `Retrofit.java` / `HttpServiceMethod.java`）：

```java
private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();
// HttpServiceMethod：解析产物全 final，解析一次后每次 invoke 只 new OkHttpCall 拼参数
private final RequestFactory requestFactory;
private final okhttp3.Call.Factory callFactory;
private final Converter<ResponseBody, ResponseT> responseConverter;
```

**思想提炼**：
- 解析成本的计量单位是复用粒度：按方法缓存，一次解析摊销到 N 次调用；适用条件：解析输入（注解/签名/配置）在对象生命周期内不变——配置可热更新的对象不能这样缓存，失效与一致性会吃掉收益。
- 不可变是并发的替代品：字段全 final 后，缓存只防"重复解析"不防"状态竞争"，读侧无需任何同步；适用条件：产物是纯数据装配、无惰性内部状态——有惰性字段时不可变前提失效，回到加锁或原子引用。

## 占位表达进行中，失败必须回滚占位

**一句话**：并发"只做一次"的协调不用全局锁，往共享结构里放一个锁对象占位表达"我在做"，失败时必须移除占位让后来者重试。

**核心矛盾**：多线程同时首次触发同一昂贵解析，既要保证只解析一次，又不能为读路径上全局锁；而"抢到权的线程失败了"若不处理，其他等待者会永远等不到结果。

**代码实例**（摘自 retrofit `Retrofit.java` loadServiceMethod）：

```java
Object lock = new Object();
synchronized (lock) {                                  // 先持锁再入 map（保证 happens-before）
  if (serviceMethodCache.putIfAbsent(method, lock) == null) {
    try { serviceMethodCache.put(method, parse()); }   // 成功：结果覆盖占位
    catch (Throwable e) { serviceMethodCache.remove(method); throw e; }  // 失败：回滚占位
  }
}
// 后来者 synchronized(占位锁) 后再查一次 map：有结果用结果，为 null 则重试
```

**思想提炼**：
- 共享结构里的值本身就是协调信号：null=没人做、占位锁=有人在做、结果=做完了，三态读免全局锁；适用条件：以" key 级"为竞争单位、解析为纯本地计算时——解析含阻塞 IO 会长时间持有占位锁，等待者被无限拖住。
- 失败路径的占位回滚与成功路径的结果写入同样重要：等待者拿到锁后发现占位没了，要能自觉重试（自旋循环）；适用条件：失败可重试且重试代价可接受——重试也很贵时改用失败缓存（见 sdk-design「确定性失败永久记忆」），两者取一。

## 输入与呈现解耦，位置按"事实/预期"双轨记账

**一句话**：外部输入（数据变更）永远不直接改写呈现状态，而是让同一事物持有两份坐标——"已呈现的事实"与"变更后的预期"，两者之差就是待消化的一致性缺口。

**核心矛盾**：数据每变一次就立刻重排视图，代价大且动画无从谈起；不重排，视图位置又马上失真。矛盾的关键在于"数据位置"和"视图位置"被默认成同一个数。

**代码实例**（摘自 androidx/recyclerview `ViewHolder.java` 概念，坐标记账在 `AdapterHelper`）：

```java
// notify* 只进 AdapterHelper 待处理队列，给每个在册 holder 记上预期偏移：
// - adapterPosition：数据世界的位置（变更立即生效，含未消费更新的预偏移）
// - layoutPosition：视图世界的位置（上一次布局的事实）
// 布局时一次性消费队列，两轨重新对齐；差值正是动画位移的来源
mAdapterHelper.onItemRangeInserted(positionStart, itemCount);
```

**思想提炼**：
- 当"事实"与"预期"被迫共享一个变量时，任何变更都会立刻污染事实；拆成双轨后，变更是廉价的记账，消化是昂贵的、可批量的、可推迟的；
- 缺口本身是资产：两轨之差不要急着抹平，动画、增量刷新、局部失效都从缺口推导。

## 扩展面先于扩展实现

**一句话**：平台核心只定义好稳定的挂点（绘制层、事件层、回调时机），具体能力交给伴生库/应用以可插拔组件实现——核心稳定后生态自然生长。

**核心矛盾**：核心若内置具体能力，要么越做越臃肿，要么能力之间互相纠缠（动画库与手势库抢状态）；但完全不内置又没人接得住。

**代码实例**（摘自 androidx/recyclerview `RecyclerView.java` 的挂点设计）：

```java
// RecyclerView 自身只留三类挂点：
// - 绘制层：ItemDecoration.onDraw / onDrawOver（子视图之下/之上）
// - 事件层：OnItemTouchListener（可拦截整条手势流）
// - 滚动层：OnFlingListener（fling 可被完全接管）
// ItemTouchHelper、SnapHelper、FastScroller 全部经这些挂点接入、互相正交
```

**思想提炼**：
- 先问"第三方能力需要从我这里拿走什么"，把答案固化成窄接口，而不是自己实现第一批能力；
- 挂点必须正交（绘制/事件/滚动各管一维），否则伴生库之间会打架。

## 可中断交互的中间态单独建账

**一句话**：拖拽、框选、多步向导这类可被随时打断的交互，其进行中的状态要单独一册账，与已确认状态隔离；确认时一次合并，打断时一次作废。

**核心矛盾**：把进行中的手势状态直接写进正式状态，打断（数据变化、返回键、手指移出）后就回不去了；合并时机又不能太晚，否则正式状态跟不上视觉。

**代码实例**（摘自 androidx/recyclerview-selection `DefaultSelectionTracker.java` 概念）：

```java
// 主选择（primary，已确认）与临时选择（provisional，框选中）分两个字段；
// 手势进行中只改 provisional；抬手 mergeProvisionalSelection() 一次并入；
// 数据变化/重置时 clearProvisionalSelection() 一次作废，主选择毫发无损
```

**思想提炼**：
- 中间态的生命周期绑定于"操作是否还在进行"，正式状态的生命周期绑定于数据——两者生命周期不同，就不能共用一个变量；
- "确认"与"作废"都应该是 O(1) 语义动作（一次 merge / 一次 clear），让打断处理变成一行代码。

## 增量布局先锚定视觉基准

**一句话**：可增量重算的界面（列表、虚拟滚动、编辑器视口）每次重排前先解析一个"视觉锚点"，以锚点为不动点向两端填充——锚点不变，视觉就不变。

**核心矛盾**：布局重建（数据更新、旋转、尺寸变化）会销毁全部视图，"刚才滚到哪"这个信息默认随视图一起丢失；从位置 0 全量重排再补偿滚动偏移，补偿逻辑要和动画、预渲染反复协商。

**代码实例**（摘自 androidx/recyclerview `LinearLayoutManager.java` onLayoutChildren）：

```java
// 锚点来源按可信度降级：
// 待滚动位置/恢复状态 > 焦点视图 > 现有子视图 > padding 全新布局；
// 锚点 = (adapterPosition, coordinate) 两个自由度，
// fill 以锚点为基准向两端填充，布局开销只与视口大小相关
```

**思想提炼**：
- 重算前先把"必须保持不变的视觉事实"物化成一个数据结构（锚点、焦点、滚动偏移），重算算法的全部自由度都围绕它展开；
- 锚点解析要有明确的可信度降级链，每级降级对应一种真实场景（恢复状态、焦点保持、滚动惯性、冷启动）。

## 子组件生命周期全序嵌套，独立时钟承载

**一句话**：容器承载子组件时，子组件的生命周期必须严格落在宿主状态区间内（父升后子升、父降前子降）——这种全序要求独立一套时钟来承载，不能直接共用宿主原生的生命周期。

**核心矛盾**：Activity 原生 lifecycle 的事件点只有"自身进入某状态"一个粒度，表达不了"子必须在父之后升、在父之前降"的先后序；硬塞进同一套回调会让子组件的时序取决于回调书写顺序，窗口期 bug（父 onPausing 中途子仍 RESUMED）无法根治。

**代码实例**（摘自 androidx/fragment `FragmentActivity.java`）：

```java
// fragment 共用独立注册表 mFragmentLifecycleRegistry，宿主回调里与 dispatchXxx 成对编排：
protected void onPause() {
    mFragments.dispatchPause();                                    // 下行：子先降
    mFragmentLifecycleRegistry.handleLifecycleEvent(ON_PAUSE);     //       宿主时钟后走
}
protected void onStart() {
    mFragmentLifecycleRegistry.handleLifecycleEvent(ON_START);     // 上行：宿主时钟先行
    mFragments.dispatchStart();                                    //       子随后升
}
```

第二来源（同库 Fragment 的双时钟）：fragment 级 mLifecycleRegistry 之外再为 view 单独建
FragmentViewLifecycleOwner——view 寿命更短（每次 onCreateView 重建），UI 观察者挂 view 时钟
才不会在 onDestroyView 后收僵尸更新；两条时钟共享 ViewModelStore，只是销毁时机不同。

**思想提炼**：
- 判断"谁先谁后"的方向规则：向高状态走时约束方（宿主）先行，向低状态走时被约束方（子组件）先行——保证任意时刻子状态都被夹在宿主区间内；
- 独立时钟不只是时序工具，还是降级总闸：保存状态时可以把整套子组件时钟一次性拨回低水位（markFragmentsCreated），与宿主真实状态解耦。适用条件：宿主与子组件生命周期必须全序嵌套的容器型设计（fragment、嵌套 manager、内嵌渲染器）；子组件与宿主生命周期本就独立无关时不要强行嵌套，多造时钟反而添乱。

## 可用性窗口用事件翻转的显式开关表达

**一句话**："什么时期允许做什么"不靠调用方自觉，也不靠查询时从当前状态推断，而是用生命周期事件维护一个显式开关，在 API 入口强制检查。

**核心矛盾**：状态保存类操作有严格的生命周期窗口（保存必须在 STOP 之后、登记重建类必须在 STOP 之前），但库无法假设调用方记得窗口；靠"当前处于什么状态"推断合法性，又分不清"还没到"与"已经错过"。

**代码实例**（摘自 androidx/savedstate `savedstate/src/commonMain/.../internal/SavedStateRegistryImpl.kt`）：

```kotlin
internal var isAllowingSavingState = true  // 初始放行，兼容未走生命周期的宿主

// performAttach 里注册监听，事件到达即翻转：
if (event == Lifecycle.Event.ON_START) { isAllowingSavingState = true }
else if (event == Lifecycle.Event.ON_STOP) { isAllowingSavingState = false }

// 消费端在 API 入口强制检查：
fun runOnNextRecreation(clazz: Class<out AutoRecreated>) {
    check(impl.isAllowingSavingState) { "Can not perform this action after onSaveInstanceState" }
```

**思想提炼**：
- 窗口约束由事件驱动翻转（ON_START/ON_STOP），而不是每次查询时从当前状态推断——开关记录的是"经历过什么"，状态查询只知道"现在在哪"，前者才能表达"已过保存时点"这类历史性结论。适用条件：操作合法性取决于历史时序而非瞬时状态；合法性只看当前状态时，直接查状态更简单。
- 开关初值选"放行"以兼容不经生命周期的宿主（测试、非标准宿主），严格性留给事件真正翻转之后；代价是初始化前的窗口约束失效——采用前需确认这段窗口内没有真实调用路径。

## 迟加入者补课到当下

**一句话**：事件系统的一致性不止"事件按序送达已注册者"，还包括"迟加入者能看到压缩过的完整历史"——新观察者注册时逐级补发从起点到当前状态的事件（addObserver 补课），而不是只从下一拍开始听。

**核心矛盾**：状态是连续的而订阅是突发的——观察者在页面 STARTED 后才注册，它关心的"页面已可见"事实没有对应事件可听；要么逼使用方注册后手动查一遍状态（每个观察者都要写一遍），要么让事件系统自己负责补齐。

**代码实例**（摘自 androidx/lifecycle `lifecycle-common/.../Lifecycle.kt` + `lifecycle-runtime/.../LifecycleRegistry.jvm.kt`）：

```kotlin
// 契约写明：在 STARTED 注册的观察者会依次收到 ON_CREATE、ON_START
// The given observer will be brought to the current state of the LifecycleOwner.
// 注册表兑现：从 INITIALIZED 起逐级 upFrom 补发到目标态
while (statefulObserver.state < targetState && observerMap.contains(observer)) {
    val event = Event.upFrom(statefulObserver.state) ?: throw ...
    statefulObserver.dispatchEvent(lifecycleOwner, event)
}
```

**思想提炼**：
- 设计事件/状态 API 时自问"第 N 个订阅者知道前 N-1 次发生了什么吗"：答案必须是"能补齐"，实现是注册时沿状态图逐级重放到当下；适用条件：历史可压缩为状态重放（生命周期、配置、连接状态这类单调状态机）——事件流本身携带不可重放的载荷（如消息推送）时只能补"最新快照"不能补全历史。
- 补课与移除的语义要成对设计：补课发"真实发生过的"事件，移除不发"没发生过的"事件（removeObserver 不补发 ON_DESTROY）——一致性建立在"事件=事实"上，不建立在"对称美"上。

## 挂起与取消是两种资源语义

**一句话**：后台化一个操作有"暂停待恢复"与"取消待重来"两种语义，前者保留资源现场、后者彻底释放资源——API 设计必须显式选边，并在废弃时把语义差异写进废弃消息。

**核心矛盾**：页面退到后台时，正在收集 Flow/持有连接的工作该怎么办？暂停能保留进度但资源（订阅、连接、缓冲）照常占用；取消彻底释放但回来要重新开始。没有一种语义对所有资源类型都正确，错误默认会让用户在退后台后继续耗流量、占连接。

**代码实例**（摘自 androidx/lifecycle `lifecycle-common/src/jvmMain/.../Lifecycle.jvm.kt` 的废弃消息）：

```kotlin
@Deprecated(
    message = "launchWhenStarted is deprecated as it can lead to wasted resources " +
        "in some cases. Replace with suspending repeatOnLifecycle to run the block ...",
)
// 废弃链：whenStateAtLeast（pausing dispatcher 挂起）→ withStateAtLeast（取消语义）
//         launchWhenStarted（挂起）          → repeatOnLifecycle（取消重来）
```

**思想提炼**：
- 资源型工作（订阅/连接/采集）默认选取消语义，纯 UI 现场（动画帧、输入防抖）才选挂起语义；适用条件：能判断工作"重来一次的代价"时——重来代价无限大的工作两种语义都不对，需要显式的检查点恢复机制。
- 库废弃 API 时，废弃消息写清"旧语义是什么、为什么错、新 API 语义是什么"（本库三组废弃消息是范本），把迁移决策变成读一条消息，而不是翻设计文档。

## 资源启停收敛到活跃度翻转点

**一句话**：当下游"有人用/没人用"是清晰的二元翻转时，把资源的启动与释放全部收敛到计数的 0↔1 过界两个时刻，整个生态只依赖这两个回调，而不是各自监听原始订阅/离开信号。

**核心矛盾**：资源的成本在"没人看还继续算/继续连"，但监听每个订阅者的来去又会让每个资源实现都写一遍计数与去重；订阅者活跃度还随外部生命周期抖动（旋转、后台），逐订阅者响应会疯狂启停。

**代码实例**（摘自 androidx/lifecycle `lifecycle-livedata-core/.../LiveData.java` + `lifecycle-livedata/.../MediatorLiveData.java`）：

```java
// 基座只暴露两个过界回调：活跃计数 0↔1
protected void onActive()  { }   // 0→1：开始有活跃订阅者
protected void onInactive() { }  // 1→0：最后一个活跃订阅者离开
// 整个下游生态全部只建在这两个钩子上：
//   MediatorLiveData.onActive → plug 全部源头（级联给上游）
//   CoroutineLiveData.onActive → 启动 block 协程；onInactive → 延迟 5s 取消
//   ComputableLiveData.onActive → 执行重算
```

**思想提炼**：
- 给生态定扩展面时，先找"最小稳定事件集"——翻转点比逐订阅者事件稳定得多（订阅者抖动被计数吸收，只剩净变化）；适用条件：存在明确的"被使用中"二元状态且翻转频率可控——资源启动代价极低时直接每次订阅都新建、不做过界收敛更简单。
- 抖动真实的场景（屏幕旋转）在过界回调外再补一层宽限窗（CoroutineLiveData 的 5s 延迟取消），而不是把宽限逻辑做进翻转判定——翻转点保持纯净，容错策略叠加在外。

## 真值即时记账，广播延迟撤销

**一句话**：状态快速抖动时，内部真值（计数）即时记账，对外通知延迟一个宽限窗才广播——窗口内回转就撤销挂起的通知，抖动合并为净零事件。

**核心矛盾**：旋转等场景让前后台状态以亚秒级速度 0→1→0→1 翻转，逐事件转发会向下游发出成对假信号（ON_PAUSE→ON_RESUME），下游每个订阅者都要自己防抖。

**代码实例**（摘自 androidx/lifecycle `lifecycle-process/src/main/java/androidx/lifecycle/ProcessLifecycleOwner.kt`）：

```kotlin
// 真值层：即时计数；广播层：sent 标志记录已发位置
private var startedCounter = 0; private var resumedCounter = 0
private var pauseSent = true;   private var stopSent = true
// 退场：不立即广播，挂 700ms 宽限
handler!!.postDelayed(delayedPauseRunnable, TIMEOUT_MS)
// 宽限窗内回场：撤销挂起的通知，净事件为零
handler!!.removeCallbacks(delayedPauseRunnable)
```

**思想提炼**：
- 可撤销的通知宁可通过"延迟后执行"实现，不要"先发后补偿"——逆向事件往往不存在（发出去的 ON_PAUSE 收不回来），而延迟的 runnable 天然可 cancel；适用条件：下游对毫秒级时点无要求且翻转可合并——精确时点需求（埋点、安全限制）不适用，直接即时广播。
- 真值层与广播层用显式标志对账（sent 标志），而不是让广播层倒查真值——两个时钟各自快走，对账点集中在少数几个 dispatch 方法里，次序约束（stop 等 pause）才放得住。

## 类型随模式走，负载归容器

**一句话**：序列化时负载里不带类型标签，窄化存储（Byte/Short/Enum 都存成 Int）；解释数据的类型信息由序列化模式（descriptor/schema）单方面携带，解码端照模式读。

**核心矛盾**：容器（Bundle/Map）的原生存储档有限，逐值携带类型标签既浪费存储又引入标签伪造风险；但丢掉类型信息后，同一串字节在恢复端如何解释就成了问题——除非有个独立于数据的模式在两端共享。

**代码实例**（摘自 androidx/savedstate `savedstate/src/commonMain/.../serialization/SavedStateEncoder.kt`）：

```kotlin
override fun encodeByte(value: Byte) {
    savedState.write { putInt(key, value.toInt()) }   // 窄化成 Int 存，负载无类型痕迹
}
// 解码端由 descriptor 决定调 getByte 还是 getShort——类型在 schema 里，不在数据里
override fun decodeByte(): Byte = savedState.read { getInt(key).toByte() }
// MutableStateFlowSerializer 的 descriptor 直接伪装成内部值的 descriptor，
// 连原始类型 kind 都对齐——容器里不留包装痕迹
```

**思想提炼**：
- 两端共享同一份 schema 的封闭系统里，类型/结构信息放模式不放数据——省空间、抗篡改；适用条件：编解码两端由同一份代码/同一版本 schema 驱动（进程内状态恢复、RPC 两端同仓库）。schema 可能各自漂移的开放场景（跨版本持久化、第三方互通）必须把判别信息写进数据。
- 该原则隐含一个测试要点：快路径/特化分支必须与通用路径共享同一套窄化规则（savedstate 的 IntList 快路径同样按 Int 存），否则同一个值走不同路径落盘形态不同，恢复端按模式读就出错。

## 管理者寄生于被管理者的存活机制

**一句话**：管理一类对象生命周期的"管理者"，不要自建存活判断，把自己的状态寄存到被管理者的生命周期载体里——被管理者的生死信号免费复用。

**核心矛盾**：FragmentManager 需要知道"宿主是配置变更还是彻底销毁"来决定 fragment 非配置状态的去留；自建判断（isChangingConfigurations 等）信号零散且各平台行为不一，注定漏。

**代码实例**（摘自 androidx/fragment `FragmentManagerViewModel.java`）：

```java
// 管理者本身是个 ViewModel，住进宿主的 ViewModelStore：
static FragmentManagerViewModel getInstance(ViewModelStore viewModelStore) {
    return new ViewModelProvider(viewModelStore, FACTORY)
            .get(FragmentManagerViewModel.class);
}
@Override protected void onCleared() { mHasBeenCleared = true; }
// 被管理者（宿主）被彻底销毁 → ViewModelStore.clear() → onCleared 回调 →
// shouldDestroy() 看到 mHasBeenCleared 才放行销毁 retained fragment
```

**思想提炼**：
- "谁的内容谁负责销毁"倒过来用：管理者的清理时机，往往就是被管理者某个已有销毁信号——找到它并挂靠，别再造一套；适用条件：管理者与被管理者生命周期强绑定（同生或随宿亡）——管理者需要跨宿主存活时寄生的载体不成立，须自建。
- 寄生载体天然获得"载体切换时幸存、载体销毁时收割"的语义，配置变更/进程死亡两类场景一次理顺。

## 快路径先行，慢副作用停靠押后

**一句话**：把一笔变更拆成"快路径"（状态账本，同步走完）与"慢路径"（动画、网络等副作用），快路径走到停靠点等慢路径，慢路径完成以回调把快路径推到终点——两边永不互相阻塞，也不互相越过。

**核心矛盾**：fragment 的生命周期状态必须立刻确定（宿主随时可能再派发事件），动画却可能要几百毫秒甚至被手势无限拖住；让状态机同步等动画，事件洪峰下必然卡死或漏帧。

**代码实例**（摘自 androidx/fragment `SpecialEffectsController.kt`）：

```java
// 状态机把带动画的 fragment 停靠在 AWAITING_EXIT/ENTER_EFFECTS（computeExpectedState 压制）；
// 特效控制器编排动画，全部完成时回调：
override fun complete() {
    super.complete()
    fragment.mTransitioning = false
    fragmentStateManager.moveToExpectedState()   // 回调驱动状态机走完剩余阶梯
}
```

**思想提炼**：
- 快慢路径的接缝要双向定义：停靠点（快路径停在哪）与完成回调（慢路径把快路径推去哪）必须成对出现，缺一个就是永久挂起或竞态；适用条件：副作用可取消/可强制完成（超时兜底）时才停靠等待——副作用无上界且不可取消时，改用"状态先到终点、副作用事后追赶"。
- 停靠点是天然的"可撤销区"：预测性返回手势能 seek 倒放，正是因为状态停在中间带、效果尚未 commit。

## 双自治实体靠对称收编维持一致

**一句话**：两个各有独立状态线、都能"自己决定消失"的实体（如 Dialog 窗口与 fragment），一致性靠对称的收编动作维持——对方消失我收编、我销毁先收编对方。

**核心矛盾**：Dialog 是自治窗口（自己收输入、自己响应返回键、自己消失），而 fragment 生命周期必须由 manager 驱动；两条状态线谁也不服谁，单方面同步必然漏（Dialog 关了 fragment 还 RESUMED，或反之）。

**代码实例**（摘自 androidx/fragment `DialogFragment.java`）：

```java
// 正向：Dialog 消失回调 → 移除 fragment（异步回调可能落在宿主 onPause 后，必须 allowStateLoss）
public void onDismiss(DialogInterface dialog) {
    if (!mViewDestroyed) { dismissInternal(true, true, false); }
}
// 反向：fragment 销毁 → 手动 dismiss Dialog 且摘掉监听器，防止其异步 onDismiss 回环
public void onDestroyView() {
    mDialog.setOnDismissListener(null);
    mDialog.dismiss();
}
```

**思想提炼**：
- 对称收编 + 回调时序约束（手动补发 onDismiss 保证先于 onDestroy）+ 幂等旗标（mDismissed）三件套缺一不可：只做单向同步会在"另一边先动"时翻车；适用条件：两个实体的状态机不能合并但必须最终一致——能合并成一个状态机的（如 View 与自身可见性）不要造两套。
- 收编时"先摘对方的回调钩子再动作"，是打断回环（A 的消失触发 B，B 的处理又触发 A）的标准手法。
