# SDK 设计启示（跨库总结）

> 各库源码标注中发现的 SDK 设计模式，按模式归并；由 source-annotator skill 同步维护。

## 边界

- **收**：与设计/构建 SDK 直接相关的模式与可执行做法，含真实代码实例（"怎么做"）
- **不收**：库用法技巧、业务知识、无迁移价值的实现细节、叙事与铺垫（归宿：库沉淀文档）；权衡与思维方式（归宿：[design-principles.md](./design-principles.md)）
- **分工**：本文档收"怎么做"，design-principles.md 收"怎么想"；同一条精妙设计两边都够格时各写各的角度，互相不复制

## 规则

- 一个模式一个条目：同名条目并入（可并列第二个代码来源），绝不重复开节
- 条目四段：**一句话**（≤ 2 句）→ **代码实例**（摘自真实源码 ≤ 10 行，标库与文件路径）→ **为什么精妙**（矛盾 → 解法，1-2 句）→ **SDK 设计启示**（1-2 条可执行做法；每条必须带**适用条件**——什么信号/环境下用它、怎么用、不适用会怎样，无适用条件的启示是口号）
- 扁平 `## 模式名` 节 + 顶部目录索引，新条目 = 追加目录行与节

## 目录

- [等价请求幂等复用](#等价请求幂等复用)
- [回调执行器随请求注入](#回调执行器随请求注入)
- [编译期重载分流，运行时单一收口](#编译期重载分流运行时单一收口)
- [多形态惰性规范化](#多形态惰性规范化)
- [两级判定与软失败回退](#两级判定与软失败回退)
- [平台内置优先 + 工厂替换口](#平台内置优先--工厂替换口)
- [生命周期桥接挂宿主自带观察机制](#生命周期桥接挂宿主自带观察机制)
- [对象级单锁贯穿状态机](#对象级单锁贯穿状态机)
- [多候选逐条尝试，子异常聚合上抛](#多候选逐条尝试子异常聚合上抛)
- [缓存键编码全部结果维度](#缓存键编码全部结果维度)
- [弱引用登记簿，强可达由业务链保证](#弱引用登记簿强可达由业务链保证)
- [Supplier 惰性化打破构造期循环依赖](#supplier-惰性化打破构造期循环依赖)
- [experiment 门控功能，未开启零分配](#experiment-门控功能未开启零分配)
- [门面无状态，可变点外移](#门面无状态可变点外移)
- [先默认后模块的装配顺序](#先默认后模块的装配顺序)
- [共享资源延迟回收，多路径完成信号协调](#共享资源延迟回收多路径完成信号协调)
- [性能开关按请求上下文收口裁决](#性能开关按请求上下文收口裁决)
- [单值处理器装饰出集合语义](#单值处理器装饰出集合语义)
- [错误信息自带定位与尝试清单](#错误信息自带定位与尝试清单)
- [确定性失败永久记忆，致命错误放行](#确定性失败永久记忆致命错误放行)
- [错误数据化，成功失败同通道](#错误数据化成功失败同通道)
- [通吃实现排链尾](#通吃实现排链尾)
- [接口探测式能力协商](#接口探测式能力协商)
- [早期调用入队，attach 时刻统一执行](#早期调用入队attach-时刻统一执行)
- [账本操作与状态迁移分离](#账本操作与状态迁移分离)
- [元操作双形态：执行时展开，持久化保持折叠](#元操作双形态执行时展开持久化保持折叠)
- [探测、裁决、惩罚三层拆分 lint 架构](#探测裁决惩罚三层拆分-lint-架构)
- [资源预算按相对单位计量](#资源预算按相对单位计量)
- [日志先行做本地持久化的崩溃恢复](#日志先行做本地持久化的崩溃恢复)
- [流程拆步进状态机，多触发点分片续跑](#流程拆步进状态机多触发点分片续跑)
- [更新先入队，消费时机单一收口](#更新先入队消费时机单一收口)
- [测量即布局反推，产物与尺寸一次成型](#测量即布局反推产物与尺寸一次成型)
- [缓存按匹配键放宽分级，查找沿代价升序](#缓存按匹配键放宽分级查找沿代价升序)
- [投机工作带 deadline 与放弃语义](#投机工作带-deadline-与放弃语义)
- [多源合并先建命名空间隔离](#多源合并先建命名空间隔离)
- [整包状态按 key 分发，消费即删自动续传](#整包状态按-key-分发消费即删自动续传)
- [错误校验前移到登记入口](#错误校验前移到登记入口)
- [资源自清挂宿主终态信号](#资源自清挂宿主终态信号)
- [有序登记表容忍回调期增删](#有序登记表容忍回调期增删)
- [版本号对账出粘性分发](#版本号对账出粘性分发)
- [创建参数随请求传递，工厂无状态化](#创建参数随请求传递工厂无状态化)
- [恢复状态消费即删，保存时真值优先合流](#恢复状态消费即删保存时真值优先合流)
- [ERROR 级遮蔽重载，把必须项变成编译错误](#error-级遮蔽重载把必须项变成编译错误)
- [描述符指纹分流，整值快路径绕过逐元素遍历](#描述符指纹分流整值快路径绕过逐元素遍历)
- [双读默认值消歧，缺失与存值分开](#双读默认值消歧缺失与存值分开)
- [注解当版本探针，反射兜底旧依赖](#注解当版本探针反射兜底旧依赖)

<!-- 条目模板：

## 模式名

**一句话**：模式是什么（≤ 2 句）。

**代码实例**（摘自 <库> `<文件路径>`）：

```java
// 精简到能独立说明模式的片段，≤ 10 行
```

**为什么精妙**：矛盾 → 解法（1-2 句）。

**SDK 设计启示**：
- 可执行做法 1
- 可执行做法 2

-->

## 等价请求幂等复用

**一句话**：入口方法对"重复调用且参数等价"的场景做幂等短路——复用既有对象而不是重建，把 UI 复用的不可控调用次数转化为内部去重。

**代码实例**（摘自 Glide `RequestBuilder.java` 私有 into()）：

```java
Request previous = target.getRequest();
// 配置完全一致（model/options/transition/listener…）→ 直接复用旧请求
if (request.isEquivalentTo(previous)
    && !isSkipMemoryCacheWithCompletePreviousRequest(options, previous)) {
  if (!previous.isRunning()) {
    previous.begin(); // 已结束/失败/未开始 → 重新触发；已完成 → 重投结果
  }
  return target; // 复用路径：不清旧资源、不重设 tag、不重新登记
}
requestManager.clear(target);      // 不等价：清旧 → 挂新 → 交生命周期驱动
target.setRequest(request);
requestManager.track(target, request);
```

**为什么精妙**：RecyclerView 复用让 into() 的调用次数与时机完全不可控（同一 View 可能被反复 into 相同内容）。等价检测把"外部随意调"变成"内部只做必要的事"——不重建设请求、不重设占位图、不重新测量尺寸。

**SDK 设计启示**：
- 入口 API 的幂等性靠 SDK 自己兜底（等价短路），不依赖调用方"保证只调一次"；
- 等价判定要覆盖全部影响行为的配置项（Glide 把它实现在 Request.isEquivalentTo，含 model/options/过渡/监听器），漏一项就是隐性 bug；
- 幂等短路要有逃生口：特例（skipMemoryCache + 已完成）必须强制走重建路径，防止幂等破坏语义。

## 回调执行器随请求注入

**一句话**：多播分发层在注册回调时把回调与它的执行线程成对存储，分发时只投递、不感知线程策略。

**代码实例**（摘自 Glide `EngineJob.java`）：

```java
// 注册：cb 与 executor 成对入列（into 默认主线程 / experimentalIntoFront 队首 / submit 直通）
cbs.add(cb, callbackExecutor);

// 分发：对每个 entry 用它自己的 executor 投递，EngineJob 不判断策略
for (final ResourceCallbackAndExecutor entry : copy) {
  entry.executor.execute(new CallResourceReady(entry.cb));
}
```

**为什么精妙**：同一个解码任务可能被多个回调方共享，而各方的回调线程语义不同。若在分发层硬编码"切主线程"，每种新策略都变成核心类的 if-else 特判；把策略作为注册项后，分发层永久稳定。

**SDK 设计启示**：
- 分发/广播层"只投递、不决策"：把"在哪执行"作为回调注册项的一部分，随订阅方注入；
- 新增线程策略 = 新增一个 Executor 参数，核心分发代码零改动（开闭原则的线程版）。

## 编译期重载分流，运行时单一收口

**一句话**：入口 API 按参数类型开出多个编译期重载表达"意图差异"，内部全部收口到一条统一链路——意图由静态签名声明，机制只有一份实现。

**代码实例**（摘自 Glide `Glide.java` 与 `manager/RequestManagerRetriever.java`）：

```java
// 6 个 with 重载：签名即"请求跟谁的生命周期走"的意图声明
public static RequestManager with(@NonNull FragmentActivity a) {
  return getRetriever(a).get(a);              // Activity 级 lifecycle
}
public static RequestManager with(@NonNull Fragment f) {
  return getRetriever(f.getContext()).get(f); // Fragment 级 lifecycle
}
public static RequestManager with(@NonNull Context c) {
  return getRetriever(c).get(c);              // 宽类型兜底：运行时 instanceof 细化
}
// 全部重载最终都经 RequestManagerRetriever 按 Lifecycle key 收口创建/缓存
```

**为什么精妙**：请求必须绑定生命周期，而"绑谁的"是调用方意图，静态语言只能在签名上表达。只留 `with(Context)` 全靠运行时分发会让意图隐式化、传错类型静默错绑；每个重载各写一套创建逻辑则会六份重复代码各自腐化。重载表达意图、单一 retriever 按 Lifecycle 收口，两头都不妥协。

**SDK 设计启示**：
- 重载是意图声明的载体：参数类型选语义最具体的类型（Fragment 而非 Context），把选型时机从运行期提到编译期；同时保留宽类型入口做运行时兜底分发（instanceof 细化 + ContextWrapper 递归解包），两层互为补位；
- 废弃 API 做"语义降级"而非删除要极度谨慎：Glide 把废弃的 `with(android.app.Activity)` 实现改为等价 `with(applicationContext)`——编译兼容，但行为静默变化（失去生命周期绑定）。迁移引导可以靠 Javadoc，但老代码的静默降级必须有显式文档警告。

## 多形态惰性规范化

**一句话**：同一份数据的多种表示（原始/规范化/派生），构造时只存最原始的，昂贵形态按需惰性生成并缓存，廉价形态永不规范化。

**代码实例**（摘自 Glide `load/model/GlideUrl.java`）：

```java
private String getSafeStringUrl() {
  // 首次调用才转义（URL 非法字符 → 百分号编码），结果缓存
  if (TextUtils.isEmpty(safeStringUrl)) {
    ...
    safeStringUrl = Uri.encode(unsafeStringUrl, ALLOWED_URI_CHARS);
  }
  return safeStringUrl;
}
public String getCacheKey() {
  // 缓存键直接用原始串，不转义、不含 headers
  return stringUrl != null ? stringUrl : Preconditions.checkNotNull(url).toString();
}
```

**为什么精妙**：缓存键是高频路径（每次加载都要算），转义是昂贵操作——若键用转义串，高频路径被迫支付昂贵成本。三种表示分离让各路径各走各的最低成本。

**SDK 设计启示**：
- 高频廉价路径（缓存键、equals、hashCode）绝不混入昂贵规范化（转义/解析/格式化）；
- 惰性生成字段可容忍无同步——幂等结果重复计算无害，不加锁。

## 两级判定与软失败回退

**一句话**：SDK 能力分发用两级判定——廉价的 handles() 粗筛（无 IO）+ 昂贵的 buildLoadData() 精筛；精筛失败以 null 软表达，交由统一回退机制处理。

**代码实例**（摘自 Glide `load/model/ModelLoader.java`）：

```java
// 第一级：快速粗筛，只看 scheme/类型等元信息，不做 IO
boolean handles(@NonNull Model model);
// 第二级：构造取数方案；返回 null = "类型认识但取不到数据"（软失败）
@Nullable LoadData<Data> buildLoadData(
    @NonNull Model model, int width, int height, @NonNull Options options);
```

**为什么精妙**：同一 model 类型常注册多个 loader（Uri→InputStream 有七八个），若只有一阶段，每个 loader 都要构造 fetcher 后自行失败——浪费分配且失败路径难统一。两级判定让不匹配者零成本出局，失败表达统一。

**SDK 设计启示**：
- 分发场景把"能不能处理"拆成廉价预判 + 昂贵构造两级；
- 失败要区分"硬错误（抛异常）"与"软不匹配（返回 null）"，后者交给回退协调者（MultiModelLoader）统一处理。

## 平台内置优先 + 工厂替换口

**一句话**：默认实现用平台/运行时内置能力保证零依赖开箱可跑，同时以单方法小工厂接口留出替换口，高级用户可无痛换第三方实现。

**代码实例**（摘自 Glide `load/data/HttpUrlFetcher.java`）：

```java
interface HttpUrlConnectionFactory {
  HttpURLConnection build(URL url) throws IOException;   // 替换口
}
private static class DefaultHttpUrlConnectionFactory
    implements HttpUrlConnectionFactory {
  @Override
  public HttpURLConnection build(URL url) throws IOException {
    return (HttpURLConnection) url.openConnection();      // 平台内置
  }
}
```

**为什么精妙**：零依赖让 SDK 开箱即用；单方法工厂让 OkHttp 等第三方网络栈替换不需要改任何核心类——"默认能跑"与"可深度替换"互不牺牲。

**SDK 设计启示**：
- 默认路径永远零依赖；集成第三方做成可选集成包或工厂注入，核心类不 import 任何第三方；
- 替换口收敛为单方法小接口，替换成本最小化。

## 生命周期桥接挂宿主自带观察机制

**一句话**：SDK 需要随宿主生命周期启停时，桥接宿主已有的观察机制（如 androidx Lifecycle），而不是向宿主内部塞自造的代理组件。

**代码实例**（摘自 Glide `manager/LifecycleRequestManagerRetriever.java` + `manager/LifecycleLifecycle.java`）：

```java
// 构造桥：observe 宿主 androidx Lifecycle，翻译成 Glide 内部小接口的事件
LifecycleLifecycle glideLifecycle = new LifecycleLifecycle(lifecycle); // 内部 lifecycle.addObserver(this)
result = factory.build(glide, glideLifecycle, ...);  // RequestManager 只依赖内部 Lifecycle 接口
lifecycleToRequestManager.put(lifecycle, result);    // 同一宿主复用同一 manager
```

**为什么精妙**：早期 Glide 用无头 Fragment 转发生命周期，宿主机制一变（Fragment → androidx lifecycle）整套方案报废，还留下 FRAGMENT_TAG 常量与空壳 Fragment 类作为永久兼容包袱；挂在宿主自带观察机制上后，宿主演进只需替换桥接层（LifecycleLifecycle），SDK 其余部分不动。

**SDK 设计启示**：
- 绑定外部宿主的生命周期/事件，优先订阅宿主官方观察机制，并翻译成 SDK 内部的小接口；SDK 内部只依赖自己的接口，不直接依赖宿主机制；
- 自造代理组件（空 Fragment 等）混进宿主内部是技术债：机制迁移后它会变成必须永久保留的二进制兼容空壳。

## 对象级单锁贯穿状态机

**一句话**：一个被多线程驱动状态的逻辑对象，用一把对象级锁收口全部状态转移，并暴露 getLock() 让外部协作方（如结果分发层）在回调前参与同一把锁。

**代码实例**（摘自 Glide `request/SingleRequest.java` + `load/engine/EngineJob.java`）：

```java
// 请求树顶层 new 一把锁，主/缩略图/error 请求共享（RequestBuilder）
SingleRequest.obtain(..., /* requestLock= */ new Object(), ...);
// EngineJob 回调前先拿请求锁，再拿自己的锁——固定顺序防死锁（b/136032534）
synchronized (cb.getLock()) {
  synchronized (EngineJob.this) {
    if (cbs.contains(cb)) { callCallbackOnResourceReady(cb); }  // "回调仍在 → 才执行"与 clear() 串行化
  }
}
```

**为什么精妙**：status/resource/loadStatus 是组合状态（status=COMPLETE 与 resource 非空必须同时成立），逐字段 volatile 给不了组合原子性；"检查回调存在 → 执行回调"与请求方 clear()/begin() 若不串行，就会出现"请求已取消、资源已回收、回调还拿旧资源去显示"的竞态。

**SDK 设计启示**：
- "一个逻辑对象被多线程驱动状态"时用对象级单锁 + 统一锁顺序收口全部转移，正确性优先于并发度（请求级锁天然低竞争，粗粒度代价可忽略）；
- 需要外部协作方参与互斥时暴露 getLock() 让对方锁同一把，而不是各自加锁再指望时序凑巧。

## 多候选逐条尝试，子异常聚合上抛

**一句话**：同一输入有多个候选处理器时按序逐个尝试，单个候选的硬失败（异常）只记录不终止；全部失败后把各候选的子异常聚合成一棵异常树一次性上抛。

**代码实例**（摘自 Glide `load/engine/DecodePath.java`）：

```java
for (int i = 0; i < decoders.size(); i++) {
  try {
    result = decoder.decode(data, width, height, options);  // 单解码器失败不终局
  } catch (IOException | RuntimeException | OutOfMemoryError e) {
    exceptions.add(e);                                       // 记录后换下一个
  }
}
if (result == null) {
  throw new GlideException(failureMessage, new ArrayList<>(exceptions));  // 异常树上抛
}
```

**为什么精妙**：多候选注册表里个别实现对特定输入抛异常是常态（#2406），一票否决会让本可成功的候选陪葬；但静默 failover 又让调用方只见"失败"不知"为何失败"——异常树把"换下一个重试"的容错与"完整归因"的可观测性同时拿到。

**SDK 设计启示**：
- 多候选 + failover 的分发层，子异常必须聚合上抛而非丢弃或只留最后一个；
- 与"两级判定与软失败回退"分层使用：软不匹配零成本出局（null 走协调者），硬失败记录归因后重试。

## 缓存键编码全部结果维度

**一句话**：缓存键的 equals/hashCode 必须覆盖所有影响输出的输入维度，维度缺一即错误命中；内存判同与磁盘存储语义不同，键也分家。

**代码实例**（摘自 Glide `load/engine/EngineKey.java`）：

```java
return model.equals(other.model)
    && signature.equals(other.signature)
    && height == other.height && width == other.width
    && transformations.equals(other.transformations)
    && resourceClass.equals(other.resourceClass)
    && transcodeClass.equals(other.transcodeClass)
    && options.equals(other.options);   // 任一维度不同 → 不同资源
```

**为什么精妙**：宽高、变换、解码选项任一不同都必须判为不同键，否则 A 配置的缓存结果会错发给 B 配置的请求（错图/错裁剪/错降质）；内存判同需要全部维度而磁盘键只需磁盘语义子集，EngineKey.updateDiskCacheKey 直接抛异常，把误用变成显式失败。

**SDK 设计启示**：
- 设计缓存键先列全"哪些输入会影响输出"，逐维编码，一个都不能少；
- 同一资源的不同缓存层用不同键类型，误用在运行时显式失败好过静默串缓存。

## 弱引用登记簿，强可达由业务链保证

**一句话**：进程级管理器登记"活跃对象"用弱引用集合（GC 后自动出表，无需显式反注册），对象本身的强可达性由业务引用链保证，登记簿只观察不挽留。

**代码实例**（摘自 Glide `manager/TargetTracker.java`）：

```java
private final Set<Target<?>> targets =
    Collections.newSetFromMap(new WeakHashMap<Target<?>, Boolean>());
// 强可达性由请求链保证：RequestTracker 强引用 Request，Request 持 target 字段引用 Target
// Target 被 GC 后自动出表，不依赖显式 untrack
```

**为什么精妙**：用强引用集合，宿主忘记 untrack 就是泄漏（Target 持 View 引用）；Glide 把强引用职责交给请求链——那些引用本来就必须存在——登记簿退化为纯观察者，泄漏风险归零。RequestTracker（第二来源，`manager/RequestTracker.java`）展示了弱引用方案的边界：对象"尚未被业务链持有"的窗口期弱引用保不住（未开始/暂停中的请求会被 GC，#346），另设一份强引用集合 `pendingRequests` 把这类请求硬持到 begin 为止——有明确摘除时机的精确补位，而不是退回全量强持。

**SDK 设计启示**：
- 管理器持有"别人的对象"时先问：强引用该归谁？让本就持有它的业务链保证可达，注册表用弱引用兜底；
- 弱引用集合的迭代要拍快照并过滤 null（WeakHashMap 迭代器可能吐 null，#322/#2262）；
- 弱引用登记要识别"保活窗口缺口"：对象还没进入业务引用链之前，用带明确摘除时机的强引用集合补位。

## Supplier 惰性化打破构造期循环依赖

**一句话**：两个互相需要的对象在构造期解不开引用环时，把其一包成 Supplier 闭包传出去，被引用方存引用不解引用，首次使用才真正构建。

**代码实例**（摘自 Glide `Glide.java` 构造器）：

```java
// RegistryFactory 回调模块时要传本 glide 实例（此刻 Glide 还没 new 完），
// 而 GlideContext 又要持有 registry——三角循环
GlideSupplier<Registry> registry =
    RegistryFactory.lazilyCreateAndInitializeRegistry(
        this, manifestModules, annotationGeneratedModule);
glideContext = new GlideContext(context, arrayPool, registry, ...); // 存 supplier 不解引用
// 首次 getRegistry()（通常在首次请求解析 ModelLoader 时）才构建 Registry 并回调 registerComponents
```

**为什么精妙**：直接构建 Registry 要把未构造完的 this 传给回调（this 逸出，线程不安全且半初始化），先建 Registry 又过不了它需要 glide 的签名矛盾；Supplier 把"什么时候建"从构造期推到首次使用点，环自然解开。

**SDK 设计启示**：
- 初始化顺序解不开的依赖环，优先用惰性 Supplier 断环，而不是拆模块签名或允许 this 逸出；
- 惰性构建点选在"首次真实消费"处（如首次请求），把构建成本从启动期摊到使用期。

## experiment 门控功能，未开启零分配

**一句话**：可选功能用 experiment 标志类门控，未开启时连监听器对象都不创建（惰性 Supplier + 注册短路），开启时才付出全部成本。

**代码实例**（摘自 Glide `Glide.java` / `GlideBuilder.MemoryCategoryInBackground`）：

```java
GlideBuilder.MemoryCategoryInBackground exp = experiments.get(MemoryCategoryInBackground.class);
if (exp != null) { this.memoryCategoryInBackground = exp.value(); }        // null = 未开启，全部短路
private final GlideSupplier<SetMemoryCategoryOnLifecycleCallbacks> cb =
    GlideSuppliers.memorize(SetMemoryCategoryOnLifecycleCallbacks::new);   // 回调对象也惰性
// registerActivityLifecycleCallbacks()：memoryCategoryInBackground == null 直接不注册
```

**为什么精妙**：可选功能若在构造期无条件创建监听器/状态字段，未开启的用户也在付内存与注册成本；门控字段 null 本身就是状态机开关，注册、注销、切换逻辑共用一个判空短路点。

**SDK 设计启示**：
- 给实验性/可选功能加显式 experiment 入口（builder 方法 + 标志类），让开启与否可测试、可回退；
- 门控的下游成本（监听器对象、注册、周期回调）全部惰性化，未开启路径保持零分配。

## 门面无状态，可变点外移

**一句话**：静态门面 API 只做路由，一切"可能要换"的东西（初始化参数、定制点）经 Builder 与回调注入，门面自身不存请求级状态。

**代码实例**（摘自 glide `library/src/main/java/com/bumptech/glide/Glide.java`）：

```java
public class Glide implements ComponentCallbacks2 {
  private final Engine engine;
  private final BitmapPool bitmapPool;
  // ... 基础设施全部由 GlideBuilder.build() 装配后经构造器注入
  public static RequestManager with(@NonNull Context context) {
    return getRetriever(context).get(context);   // 门面只路由，无请求状态
  }
}
```

**为什么精妙**：重型共享设施（线程池/缓存/注册表）要求进程级唯一共享，业务却要一个无状态的一行式入口——把可变点外移到 Builder 与模块回调后，门面永远稳定，扩展永不改门面，调用方零持有。

**SDK 设计启示**：
- 门面类不存请求级状态，一切可变配置走 Builder setter 或回调注入，运维类状态（如内存档位）独立成小状态机；适用条件：设施需进程级共享且定制点有限可枚举——定制点会膨胀时 Builder 参数列表失控，改收拢为配置对象。
- 单例门面默认"一套设施服务全进程"；若设施需按作用域隔离（多账号各自缓存），单例不成立—— Glide 的解法是把作用域化前端（RequestManager）架在共享单例之上，可借鉴。

## 先默认后模块的装配顺序

**一句话**：扩展回调排在默认装配之后执行，让扩展对默认值拥有 append（补充）/prepend（插队）/replace（覆盖）三档接管能力。

**代码实例**（摘自 glide `library/src/main/java/com/bumptech/glide/RegistryFactory.java`）：

```java
Registry registry = new Registry();
// 先默认后模块：解码器等默认组件就位后，模块才能 prepend/replace 改写默认行为。
initializeDefaults(context, registry, bitmapPool, arrayPool, experiments);
initializeModules(context, glide, registry, manifestModules, annotationGeneratedModule);
```

**为什么精妙**：扩展的典型诉求是"覆盖默认网络栈、补充新格式、插入更高优先级解码器"，而 prepend/replace 的语义都要求目标已存在——先默认后模块让三档语义自然成立，扩展无需感知默认清单。

**SDK 设计启示**：
- 装配器把"默认装配"与"扩展回调"编排成两个有序阶段，扩展 API 提供三档语义而非单一 add；适用条件：存在一组稳定默认值且扩展点按优先级竞争时——扩展之间也要排序时，还需定义扩展相互顺序（Glide 用"manifest 模块先、应用模块最后"）。
- 同一类型对可同时注册实验主路径与稳定 fallback，按注册顺序依次尝试：实验失败自动落回稳定路径，实验不必为鲁棒性买单。

## 共享资源延迟回收，多路径完成信号协调

**一句话**：异步任务产出的共享资源既要立刻交出去、又可能有后台收尾（编码/清理），且回收请求可随时插入——用"拦截式包装器"延迟回收，用"完成信号协调器"裁决多路径齐备后才真正回收。

**代码实例**（摘自 glide `load/engine/LockedResource.java` 与 `DecodeJob.java` 内部 ReleaseManager）：

```java
// LockedResource：锁定期间拦截 recycle，unlock 时补执行被拦截的回收
synchronized void recycle() {
  this.isRecycled = true;
  if (!isLocked) { toWrap.recycle(); release(); }
}
synchronized void unlock() {
  this.isLocked = false;
  if (isRecycled) { recycle(); }   // 补执行
}
// ReleaseManager：正常完成/失败/外部取消三路径异步交错，齐备才回池
private boolean isComplete(boolean isRemovedFromQueue) {
  return (isFailed || isRemovedFromQueue || isEncodeComplete) && isReleased;
}
```

**为什么精妙**：显示回调想尽早返回（不能等编码 IO），编码想安全写文件（不能被回收踩空），取消可能从任意线程随时插入——任何一方单方面回收都会踩空或泄漏，串行等待又把 IO 延迟转嫁给显示。

**SDK 设计启示**：
- 共享资源交出后仍有后台收尾时，用"包装器拦截危险操作 + 解锁时补执行"让使用方无感知；适用条件：使用方的操作可延迟执行且回收不急迫——操作本身必须立刻生效时（如关闭网络连接）不能这么包。
- 对象池复用的高频对象用小型状态机（几个布尔 + synchronized）合并"正常/失败/取消"的完成信号，全部齐备才复位回池；适用条件：路径数少且固定——路径多时布尔组合爆炸，改用显式状态枚举或计数器。

## 性能开关按请求上下文收口裁决

**一句话**：影响运行安全/稳定性的性能开关不能裸信任调用方配置，在执行前唯一收口点按"本次请求真实会做什么"裁决，冲突时强制降级到安全路径。

**代码实例**（摘自 glide `load/engine/DecodeJob.java` getOptionsWithHardwareConfig）：

```java
// 硬件位图只能被 GPU 绘制、CPU 改不了像素：仅在"无需再变换"时放行
boolean isHardwareConfigSafe =
    dataSource == DataSource.RESOURCE_DISK_CACHE || decodeHelper.isScaleOnlyOrNoTransform();
// 调用方未显式设置且不安全 → 复制 options 强制降级，防运行时变换抛异常
options = new Options();
options.putAll(this.options);
options.set(Downsampler.ALLOW_HARDWARE_CONFIG, isHardwareConfigSafe);
```

**为什么精妙**：配置期无法枚举全部开关组合（调用方可任意顺序链任何开关），若照单全收，错误在运行时变换阶段才爆且难归因到配置组合。

**SDK 设计启示**：
- 把安全裁决放在"错误的必经点"（该功能执行前最后一次改参数的时机），按请求上下文强制降级并在注释/日志说明原因；适用条件：开关影响正确性/稳定性（如 OOM、崩溃）——纯性能偏好类开关别拦，尊重调用方选择。

## 单值处理器装饰出集合语义

**一句话**：声明式 API 的参数处理器只实现单值语义，集合/数组形态用装饰器包在单值处理器外，避免"注解种类 × 形态"的组合爆炸。

**代码实例**（摘自 retrofit `retrofit2/ParameterHandler.java` iterable()）：

```java
final ParameterHandler<Iterable<T>> iterable() {
  return new ParameterHandler<Iterable<T>>() {
    @Override void apply(RequestBuilder builder, @Nullable Iterable<T> values) throws IOException {
      if (values == null) return;                    // 集合整体为 null 仍按"可选参数"跳过
      for (T value : values) {
        ParameterHandler.this.apply(builder, value); // 逐元素复用单值处理器
      }
    }
  };
}
// array() 同理：反射 Array.getLength/Array.get 遍历后复用同一单值 handler
```

**为什么精妙**：@Query/@Header/@Field/@Part 都要支持单值/Iterable/数组三形态，逐个展开要写 4×3 套处理器；把"形态"做成单值处理器的外包装后，每种注解只有一个核心实现，包装在解析期一次完成、运行期零判断地逐元素 apply。

**SDK 设计启示**：
- 处理器按"最小语义单元"（单值）实现，形态差异用装饰器在装配期叠加；适用条件：各元素处理逻辑一致且处理器无状态——元素间有整体语义（如必须一次写完的 body）或处理器有累计状态时不适用。

## 错误信息自带定位与尝试清单

**一句话**：声明式配置的校验失败统一拼入"方法 + 参数"定位，工厂链查找失败列出全部尝试过的候选与被跳过项，让用户不进调试器就能修配置。

**代码实例**（摘自 retrofit `retrofit2/Utils.java` 与 `Retrofit.java`）：

```java
// Utils.methodError：所有方法级错误统一追加定位
return new IllegalArgumentException(
    message + "\n    for method " + method.getDeclaringClass().getSimpleName()
        + "." + method.getName(), cause);
// Retrofit.nextCallAdapter：工厂链全失败时列出 Skipped/Tried 工厂清单（三个 nextXxx 同构）
builder.append("  Skipped:");  // 递归查找时被跳过的前缀工厂
builder.append("  Tried:");    // 本次实际尝试过的全部工厂类名
```

**为什么精妙**：声明式接口的错误在运行期首次调用才爆，用户手里只有异常文本；"for method X.y() (parameter #2)" + "Tried: …" 把哪个方法、哪个参数、试过哪些工厂一次给全，错误消息本身就是排错文档。

**SDK 设计启示**：
- 工厂链/注册表查找失败时把全部候选类名与跳过原因写进异常；适用条件：候选列表对用户可配置（插件、converter）时——内部固定逻辑不需要，噪音大于价值。
- 错误构造走统一入口（methodError/parameterError）而非散落拼串，定位格式才能全局一致；适用条件：同一错误形态（带定位的 IllegalArgumentException）被多处抛出时。
- 校验失败直接给出修复动作而不是只描述问题；适用条件：修复方案唯一且机械可执行时（如 androidx/savedstate `SavedStateEncoder.encodeElement` 对属性名撞保留 key "type" 的处理，异常文本直接指路 @SerialName 改名）；修复路径不唯一时只列事实让用户决策。

## 资源预算按相对单位计量

**一句话**：内存/容量类默认预算不用固定字节数，而以"用户实际会看到多少内容"为相对单位（如屏幕数），让预算随设备能力自动伸缩。

**代码实例**（摘自 glide `load/engine/cache/MemorySizeCalculator.java`）：

```java
// 1 屏 = 屏像素 × 4 字节（ARGB_8888）；内存缓存 2 屏 + Bitmap 池 4 屏
int screenSize = widthPixels * heightPixels * BYTES_PER_ARGB_8888_PIXEL;
int targetBitmapPoolSize = Math.round(screenSize * builder.bitmapPoolScreens);
// O+ HARDWARE 位图不走 Java 堆，池目标自动降为 1 屏——预算模型跟随平台演进
static final int BITMAP_POOL_TARGET_SCREENS =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ? 4 : 1;
```

**为什么精妙**：固定 MB 在旗舰机上浪费、低端机上 OOM，且"该给多少"会随平台能力（如显存位图）漂移；屏数与真实使用强度天然对齐，总预算再钳制在 memoryClass 内兜底。

**SDK 设计启示**：
- 缓存/池的默认预算用相对单位（屏数、核数、时长）表达，再乘以设备能力上限钳制；适用条件：预算与"用户可见内容量"正相关时——固定协议缓冲区这类硬尺寸别这么算。
- 平台能力变化让某结构价值下降时，默认值按版本谓词自动降档（O+ 池 4 屏→1 屏），不留给用户手工调；适用条件：降档判据可由 SDK 版本/设备属性确定性判定时。

## 日志先行做本地持久化的崩溃恢复

**一句话**：随时可能被杀的本地缓存/存储，用追加日志作为崩溃恢复的唯一事实源：操作前先落日志行、结果文件用 rename 原子替换，重启时重放日志还原可信状态。

**代码实例**（摘自 glide `third_party/disklrucache/DiskLruCache.java`）：

```java
// edit：先追加并 flush DIRTY 行，编辑器才拿到 .tmp 文件
journalWriter.append(DIRTY); journalWriter.flush();
// commit：completeEdit 校验"每个下标都写过值" → .tmp rename 成正本 → CLEAN 行
entry.dirtyFile.renameTo(entry.cleanFile); journalWriter.append(CLEAN);
// open：逐行重放日志；末行截断可修补，对不上账 rebuildJournal 整体重建
```

**为什么精妙**：进程随时可能死在"文件写了一半"的瞬间；rename 原子性保证读者只见完整正本，日志先行保证任何崩溃后重放都能区分"可信文件/残局 .tmp"，恢复策略分级（就地修补/整体重建）保住热缓存。

**SDK 设计启示**：
- 本地持久化的写路径走"日志行 flush → 写临时文件 → rename 正本 → 结果日志行"四步，恢复按日志重放分级处理；适用条件：单写者、可容忍日志膨胀（定期压缩）的场景——多进程并发写需要额外文件锁，此模式不直接适用。
- 只影响性能、不影响正确性的记录（如 LRU 顺序的 READ 行）允许延迟批量 flush，崩溃丢了也不破坏一致性；适用条件：记录仅作启发式排序/统计时。

## 确定性失败永久记忆，致命错误放行

**一句话**：昂贵的懒初始化一旦失败，把异常缓存起来永久重抛（不再重试）；但 JVM 致命错误（VirtualMachineError/ThreadDeath/LinkageError）不缓存、直接上抛。

**代码实例**（摘自 retrofit `retrofit2/OkHttpCall.java` getRawCall）：

```java
try {
  return rawCall = createRawCall();   // 成功：缓存复用
} catch (RuntimeException | Error | IOException e) {
  throwIfFatal(e);                    // 致命错误直接上抛，不缓存
  creationFailure = e;                // 非致命失败：记住，之后每次都重抛它
  throw e;
}
```

**为什么精妙**：注解解析/请求构造的失败是确定性的（注解写错了，重试一百次也是同一个错），重试只浪费 CPU 还会把同一错误重复打给上层；而缓存失败让"先调 request() 再调 execute()"看到同一个异常，行为一致。

**SDK 设计启示**：
- 惰性初始化的失败按确定性分类：确定性失败缓存重抛、瞬时性失败（网络 IO 的执行阶段）不缓存；适用条件：失败发生在"构造/解析"这类纯本地计算时——真正发网络后的失败每次都可能不同，绝不能这样缓存。
- 致命错误永远放行不吞：捕获链里先过 throwIfFatal 再决定缓存或包装；适用条件：所有宽 catch（Throwable/Error）场景，漏放行会把 OOM/栈溢出伪装成业务异常。

## 流程拆步进状态机，多触发点分片续跑

**一句话**：把一个完整流程拆成带编号的步骤，用位标志记录"走到哪一步"，让测量、布局、更新等不同触发点各自从当前步骤续跑，而不是每个触发点复制一份完整流程。

**代码实例**（摘自 androidx/recyclerview `RecyclerView.java` dispatchLayout）：

```java
if (mState.mLayoutStep == State.STEP_START) {
    dispatchLayoutStep1();          // 预布局：记账动画前状态
    dispatchLayoutStep2();          // 真布局
} else if (mAdapterHelper.hasUpdates() || needsRemeasure...) {
    dispatchLayoutStep2();          // onMeasure 已跑过 1/2，只续跑第 2 步
}
dispatchLayoutStep3();              // 收尾，状态回 STEP_START 闭环
```

**为什么精妙**：RecyclerView 的布局要被 onMeasure（自适应测量布一半）、onLayout（续完）、数据更新（消费积压后重布）三个入口以不同完整度触发；写成一个原子大方法，测量场景就得整跑一遍白费，拆成状态机后每个入口只补自己欠的步骤。

**SDK 设计启示**：
- 同一流程被多个触发点以不同完整度调用时，拆成状态机步骤、入口只续跑缺的步骤；适用条件：步骤间有明确数据依赖、状态可用少量字段表达（一个 int 位标志 + 请求记账）；反例是步骤共享大量易失临时状态，状态机会把不变量打散得更难维护。
- 每个步骤入口先断言当前状态合法（`mState.assertLayoutStep(...)`），把调用次序错误变成显式崩溃而非静默错乱；适用条件：状态机对外暴露多个入口、次序由外部触发时。

## 更新先入队，消费时机单一收口

**一句话**：外部的高频状态变更不直接生效，先进待处理队列，在少数几个明确时机一次性批量消费，让"每次消费"拿到的是一致的快照。

**代码实例**（摘自 androidx/recyclerview `RecyclerView.java` consumePendingUpdateOperations）：

```java
void consumePendingUpdateOperations() {
    if (!mFirstLayoutComplete || mDataSetHasChangedAfterLayout) {
        dispatchLayout(); return;           // 全量变化 → 直接布局
    }
    if (/* 仅 UPDATE 型 */) {
        mAdapterHelper.preProcess();        // 预处理拆分移动等复杂操作
        if (hasUpdatedView()) dispatchLayout();
        else mAdapterHelper.consumePostponedUpdates();  // 无可见影响 → 免布局
    } else dispatchLayout();                // 结构性更新 → 布局
}
// 调用点全部收口：滚动前 scrollByInternal、惯性帧 ViewFlinger.run、
// 布局 runnable mUpdateChildViewsRunnable、焦点搜索前
```

**为什么精妙**：notify* 可能在一帧内连发十几次，逐条应用到视图既慢又会把中间态暴露给布局；入队批处理后，一帧多个更新合成一次布局，且布局看到的永远是消费后的完整数据。

**SDK 设计启示**：
- 高频变更 API 只做入队 + 打标，真正的应用集中在明确的消费点（下一帧、下一次访问前）；适用条件：调用方不依赖逐条即时生效、变更可合并；反例是消费延迟会破坏调用方对即时性的预期时（如同步 getter），要提供显式 flush。
- 消费点按"轻重"分流：无可见影响时免掉整轮工作（上面 hasUpdatedView 分支）；适用条件：影响面可廉价探测时。

## 测量即布局反推，产物与尺寸一次成型

**一句话**：当"内容尺寸"只有产出产物后才知道时，允许在测量阶段直接跑真布局、再从产物反推自身尺寸，而不是要求组件实现"算尺寸"与"布局"两套逻辑。

**代码实例**（摘自 androidx/recyclerview `RecyclerView.java` onMeasure）：

```java
if (mLayout.isAutoMeasureEnabled()) {
    if (mState.mLayoutStep == State.STEP_START) dispatchLayoutStep1();
    mLayout.setMeasureSpecs(widthSpec, heightSpec);
    dispatchLayoutStep2();                              // 测量阶段直接真布局
    mLayout.setMeasuredDimensionFromChildren(w, h);     // 从子视图反推内容尺寸
    if (mLayout.shouldMeasureTwice()) { /* WRAP_CONTENT 再布一轮 */ }
}
```

**为什么精妙**：列表内容高度取决于布出来的条目数——"先算尺寸再布局"对列表是伪命题。与其让每个 LayoutManager 写两遍逻辑（预演算 + 真布局），不如官方 LM 全部声明 isAutoMeasureEnabled=true，统一走"布完反推"。

**SDK 设计启示**：
- "计算结果"与"产出产物"强耦合时，提供"直接产出再回收尺寸"的统一捷径比双份 API 更不易出错；适用条件：产物可廉价重建或可复用（RecyclerView 布出的子视图直接留用，零浪费）；反例是产物昂贵且用不上时（纯算尺寸场景），要保留轻量估算路径。
- 双路径并存时用能力开关声明走哪条（isAutoMeasureEnabled），默认值选新路径、老路径保留兼容；适用条件：存在存量自定义实现时。

## 错误数据化，成功失败同通道

**一句话**：为"失败也是合法结果"的调用方提供 Result 包装形态，把错误从异常通道搬进数据通道，让下游只需订阅一个 onNext。

**代码实例**（摘自 retrofit-adapters `rxjava3/ResultObservable.java`）：

```java
// Observable<Result<T>>：响应(无论状态码)与网络错误统一变成 onNext(Result)
@Override public void onNext(Response<R> response) {
  observer.onNext(Result.response(response));          // 2xx 与非 2xx 都是数据
}
@Override public void onError(Throwable throwable) {
  // 网络异常不再炸向 onError，包成 Result.error 继续发
  ...
}
```

**为什么精妙**：Reactive 流里 onError 是终止性的——错过它就没有"然后"了；而很多调用方（上报、重试队列、聚合展示）要的是"每个请求都有结局"。Result 形态把终止事件降级为普通数据，下游用一个订阅就能收集全部结局，还天然规避了"异常后链条死掉"的心智负担。

**SDK 设计启示**：
- 异步 API 提供错误双形态（异常通道 + 数据包装通道）让调用方按场景选；适用条件：调用方需要"逐个处理结局"（批量/容错/埋点）时——只需要 fail-fast 的场景别用，错误被数据化后容易忘记处理。
- 包装类型要保真：Result.error 存原始异常而不是字符串，调用方才能按异常类型分流；适用条件：包装层位于错误源头附近——隔了多层再包会丢失上下文，不如让它终止。

## 通吃实现排链尾

**一句话**：按序尝试的工厂链里，"几乎什么都能处理"的实现必须排在最后，"精确匹配自家类型"的实现排在前面。

**代码实例**（摘自 retrofit-converters `gson/GsonConverterFactory.java` 类头契约）：

```java
// gson 工厂：对几乎所有类型返回 converter，绝不返回 null（通吃型）
TypeAdapter<?> adapter = gson.getAdapter(TypeToken.get(type));
return new GsonResponseBodyConverter<>(gson, adapter);
// proto 工厂：只认 MessageLite 子类，其余返回 null（专精型）
// 官方文档：混用时 gson 必须放在 addConverterFactory 的最后
```

**为什么精妙**：链式查找是"第一个认领者生效"，通吃者在前会截走本该由专精者处理的消息类/标量类型，且报错表现为"JSON 解析protobuf 二进制失败"这类难以归因的错。

**SDK 设计启示**：
- 设计插件链时把"匹配专度"作为隐式排序约定并写进注册 API 文档，或干脆内建优先级（专用类型内置工厂放最前，见 retrofit 的 BuiltInConverters）；适用条件：存在能力域重叠的多个实现时——各实现能力域完全正交则顺序无关。

## 递归注册表构建期剪环，而非禁止委托

**一句话**：允许注册表里的处理器把输入转写成另一种类型后互相委托时，递归构建用"登记-跳过-移除"剪断环路，且剪断粒度是当前构建链路而非全局禁用。

**代码实例**（摘自 glide `load/model/MultiModelLoaderFactory.java`）：

```java
// 构建某 loader 期间登记其 Entry；递归再次碰到直接跳过；构建完移除
private final Set<Entry<?, ?>> alreadyUsedEntries = new HashSet<>();
// build(class) → factory.build(multiFactory) → multiFactory.build(委托类型) 递归
// String→Uri→GlideUrl 委托链成环时，环上的 Entry 被跳过，栈不溢出，
// 环外同类型 loader 仍可正常入选
```

**为什么精妙**：委托是类型分派的核心自由度（每种数据来源只实现一次取数），禁止委托会迫使每个入口类型重复实现整套分派；成环的风险用链路粒度的剪断解决，自由度与安全兼得。

**SDK 设计启示**：
- 可插拔注册表 + 处理器互委托的架构，构建期递归必须带"当前链路已用集合"剪环；适用条件：委托关系在配置期不可静态判定（运行期按注册表递归解析）时——编译期可枚举的依赖图用不上。
- 剪断后要保证行为可解释（跳过而非报错），否则用户配置出环时只看到莫名的空结果；Glide 的选择是跳过并继续，环外候选照常参与。

## 平台行为缺陷在框架层收口

**一句话**：特定 OS 版本的存储/权限行为缺陷（改坏文件、脱敏、拒绝 API），用"运行期裁决 + 委托降级 + 权限前置检查"封装成单一组件，应用零成本拿到正确结果。

**代码实例**（摘自 glide `load/model/stream/QMediaStoreUriLoader.java`）：

```java
// Q+ HEIC 经 EXIF 脱敏会损坏：按存储模式二选一
if (Environment.isExternalStorageLegacy()) {
  // legacy：查 DATA 列拿真实路径，绕开 MediaStore 读原文件
  return fileDelegate.buildLoadData(queryForFilePath(uri), ...);
}
// 分区存储：Uri 委托 + 有 ACCESS_MEDIA_LOCATION 才追加 requireOriginal
// （无权限时追加会 SecurityException，拿脱敏图好过崩溃）
```

**为什么精妙**：缺陷散落在每个应用里就是无数重复的 workaround；框架把裁决条件（存储模式、权限、Uri 来源）集中一处，随下一代系统演进只需改这一个类。

**SDK 设计启示**：
- 封装平台缺陷时，权限敏感 API 先查权限再调用并给降级路径（功能降级而非崩溃）；适用条件：降级结果仍可用（如脱敏图可显示）时——降级即错误的结果（如安全判断）不能这么做。
- 裁决条件全部收在单一组件，向上暴露正常接口；适用条件：缺陷可用运行期信号确定性判定时——需要用户意图才能判定的别替用户做主。

## 缓存按匹配键放宽分级，查找沿代价升序

**一句话**：当对象的"可复用程度"存在天然档次时，把缓存按匹配键从窄到宽分级（精确键 → 稳定键 → 类型键），查找从窄到宽逐级尝试，让重用代价随匹配键一起放宽。

**代码实例**（摘自 androidx/recyclerview `RecyclerView.java` tryGetViewHolderForPositionByDeadline）：

```java
holder = getChangedScrapViewForPosition(position);      // ① 按位置（免 bind）
holder = getScrapOrHiddenOrCachedHolderForPosition(...); // ② 按位置
if (mAdapter.hasStableIds()) {
    holder = getScrapOrCachedViewForId(...);             // ③ 按稳定 ID（只改位置）
}
holder = getRecycledViewPool().getRecycledView(type)     // ⑤ 按 viewType（须 rebind）
// ⑥ 全部未命中 → createViewHolder + bindViewHolder
```

**为什么精妙**：复用的三个代价档位是真实存在的——位置命中零成本、ID 命中改个字段、类型命中要重新 bind；单级缓存（如单一 LRU）无法区分，会把精确命中也降级成 rebind，白白浪费最便宜的那档。

**SDK 设计启示**：
- 设计缓存前先列全"重用所需的匹配信息"，每一档信息量对应一级缓存；适用条件：各档代价差异显著且高频路径集中在窄键（RecyclerView 滚动时绝大多数命中位置档）；反例是各档代价接近时分级只增复杂度。
- 回收路径按"保留价值降序"放回（价值最高的进窄键缓存，放不下的逐级降级），与获取路径形成对偶；适用条件：缓存容量有限、存在淘汰竞争时。

## 投机工作带 deadline 与放弃语义

**一句话**：利用空闲窗口提前做的投机工作，每项都带截止时间与放弃路径——来不及就不做或只做一半，绝不让半成品挤占下一个关键路径。

**代码实例**（摘自 androidx/recyclerview `GapWorker.java` prefetchPositionWithDeadline）：

```java
holder = recycler.tryGetViewHolderForPositionByDeadline(
        position, false, deadlineNs);        // 预估来不及就返回 null/未绑定 holder
if (holder.isBound() && !holder.isInvalid()) {
    recycler.recycleView(holder.itemView);   // 绑定成功才进缓存
} else {
    recycler.addViewHolderToRecycledViewPool(holder, false);  // 半成品降级进池
}
```

**为什么精妙**：帧间隙的空闲时长是确定的且随时会结束；没有放弃语义的投机工作（预取一个复杂条目）一旦超时，反而把下一帧拖卡——优化变成负优化。RecyclerView 用 Pool 里存的 create/bind 滑动平均耗时来预估"做不做得完"，预估依据是真实测量值。

**SDK 设计启示**：
- 投机优化必须自带三件套：截止时间、可截断的执行单元、基于历史耗时的预估；适用条件：工作可拆小、错过时机收益即消失；反例是原子型大工作无法按预算截断，只能整体挪到后台线程。
- 半成品要有降级去处（未绑定的 holder 降级进池而不是丢弃），让已投入的部分成本可回收；适用条件：产物存在中间可用形态时。


## 诊断设施双实现，生产路径零开销

**一句话**：排障类设施（回收状态校验、systrace 埋点）用"生产实现 + 调试实现"两套：生产路径只剩一个 volatile 布尔或编译期常量短路，调试态才记录调用栈等昂贵信息。

**代码实例**（摘自 glide `util/pool/StateVerifier.java` 与 `GlideTrace.java`）：

```java
// StateVerifier.newInstance() 按 DEBUG 常量返回两种实现：
// DefaultStateVerifier 仅存 volatile recycled 标志，throwIfRecycled 一判即抛
// DebugStateVerifier（手动开启）入池时抓调用栈，抛错时附带"谁把对象还进池的"
// GlideTrace：TRACING_ENABLED 编译期常量，关闭时方法体空到被 JIT 内联成 no-op
public static StateVerifier newInstance() {
  if (!DEBUG) { return new DefaultStateVerifier(); }
  return new DebugStateVerifier();
}
```

**为什么精妙**：池化误用（复用后被旧引用访问）是最难排查的崩溃类别，生产加全量校验嫌贵、完全不加又无从定位——双实现让"默认零成本、排障时一键变可观测"同时成立。

**SDK 设计启示**：
- 校验/追踪类设施抽成"按开关选择实现"的接口，生产实现不携带任何调试数据结构；适用条件：校验本身极轻（一个布尔判）但归因信息极贵（调用栈、快照）时——校验本身重的（全量断言）别默认开。
- 开关级别分层：编译期常量（GlideTrace，连参数求值都省）> 运行期静态布尔（StateVerifier）> 运行期注册回调；诊断粒度需求越不确定，越往后选。

## 多源合并先建命名空间隔离

**一句话**：把多个来源的对象合并成一个逻辑对象对外服务时，先为各来源的内部标识（viewType、id、索引）建立互不冲突的全局命名空间映射，而不是要求各来源自行避让。

**代码实例**（摘自 androidx/recyclerview `ConcatAdapter.java` / `ViewTypeStorage.java` 概念）：

```java
// 子 Adapter 各自返回自己的 viewType（如都是 0），
// controller 在全局层为每个子 Adapter 分配互不重叠的全局 viewType；
// 取视图时按全局 viewType 反查出"属于哪个子 Adapter + 原始 viewType"，
// 创建/绑定全部转发回原 Adapter——两道映射表就是隔离墙
```

**为什么精妙**：合并多个 Adapter 最直观的方案是"约定各子 Adapter 的 viewType 不冲突"，但约定无法执行、冲突在运行期才爆；命名空间映射把正确性从"调用方自律"变成"结构保证"。

**SDK 设计启示**：
- 聚合多个独立来源时，在聚合层为各来源的局部标识建立双向映射（局部→全局→局部），来源之间零感知；适用条件：来源不可修改或不可信、标识空间小时（int viewType）冲突概率高；反例是来源本来就全局唯一时映射层是纯开销，可提供旁路（ConcatAdapter 的 SharedViewType 模式）。


## 耗时阈值当负载探针，大批量自发工作指数退避让路

**一句话**：框架自发的大批量工作（预热、预建、后台整理）无法注册 GC/负载回调时，用"单批耗时阈值"当负载探针——单步极快的工作明显超时即推断遇到停顿（GC），立即停手并指数退避。

**代码实例**（摘自 glide `load/engine/prefill/BitmapPreFillRunner.java`）：

```java
// 单批限时 32ms（对齐非并发 GC 典型时长）；分配单次微秒级，明显超时的最大嫌疑就是 GC
while (!toPrefill.isEmpty() && !isGcDetected(start)) { ...allocate... }
// 时钟用线程 CPU 时间：主线程被别的消息堵塞不会误判为 GC
long now() { return SystemClock.currentThreadTimeMillis(); }
// 命中即停手：退避 40ms 起步、×4 指数增长、上限 1s 后继续
```

**为什么精妙**：预热本身就在批量分配 Bitmap，最容易引发停顿型 GC——预热变成自我拒绝服务；注册 GC 回调不可移植且粒度太粗，耗时阈值是零依赖的负载信号。

**SDK 设计启示**：
- 自发批量工作按"预算限时 + 超时退避"调度，把连续工作切成可中断的小批；适用条件：单步极快、可随时暂停续跑的工作——单步本身耗时的任务测不出 GC，阈值探针失效。
- 计时基准按"想测什么"选：测自身被拖慢用线程 CPU 时间，测墙钟延迟才用 uptimeMillis——用错基准会把无关堵塞全算进探针。

## 接口探测式能力协商

**一句话**：扩展能力不通过构造参数逐项开关，而是让组件实现一组可选接口，框架在装配期逐一 `instanceof` 探测——实现了哪个接口就自动解锁哪条行为链路。

**代码实例**（摘自 androidx/fragment `FragmentActivity.HostCallbacks` / `FragmentManager.attachController`）：

```java
// HostCallbacks 一口气实现全部可选接口：
class HostCallbacks extends FragmentHostCallback<FragmentActivity> implements
        ViewModelStoreOwner, OnBackPressedDispatcherOwner,
        ActivityResultRegistryOwner, SavedStateRegistryOwner, MenuHost { ... }
// attachController 里逐一探测，实现哪个就注册哪条链路：
if (host instanceof OnBackPressedDispatcherOwner) { ... 注册返回键回调 ... }
if (host instanceof ViewModelStoreOwner) { ... 自动留存非配置状态 ... }
if (host instanceof SavedStateRegistryOwner && parent == null) { ... 自动保存/恢复 ... }
```

**为什么精妙**：fragment 要求能寄宿在任意对象（Activity、浮层面板、测试环境）上，各宿主能力千差万别；若用构造参数表达"你有哪些能力"，参数表会随能力增长无限膨胀，且每加一种能力所有宿主都要改构造调用。接口探测把"能力声明"还给类型系统，宿主按需 implements 即可。

**SDK 设计启示**：
- 可选能力 ≥ 3 项且宿主类型开放时，用"可选接口 + instanceof 探测"代替布尔参数/配置对象；适用条件：能力彼此正交、探测点集中在单一装配方法内——能力间有强耦合依赖时探测顺序会变成隐式契约，需在装配处显式注释先后要求。
- 为降低接入成本，SDK 应同时提供一个"全量实现"参考宿主（如 FragmentActivity.HostCallbacks），让普通接入方零配置，让自定义宿主有对照物。

## 整包状态按 key 分发，消费即删自动续传

**一句话**：多方共享一份持久化数据时，用注册表按 key 切分所有权；消费是"取走即删"，无人消费的数据自动续传。

**代码实例**（摘自 androidx/savedstate `savedstate/src/commonMain/.../internal/SavedStateRegistryImpl.kt`）：

```kotlin
fun consumeRestoredStateForKey(key: String): SavedState? {
    val state = restoredState ?: return null
    val consumed = state.read { if (contains(key)) getSavedState(key) else null }
    state.write { remove(key) }            // 取走即删
    if (state.read { isEmpty() }) { restoredState = null }
    return consumed
}

fun performSave(outBundle: SavedState) {
    val inState = savedState {
        restoredState?.let { putAll(it) }  // 未消费的自动续传
        synchronized(lock) {
            keyToProviders.forEach { key, p -> putSavedState(key, p.saveState()) }
        }
    }
    // 非空才写回整包的 SAVED_COMPONENTS_KEY
}
```

**为什么精妙**：系统只给一个整包 Bundle，N 个组件却要各自存取——直接共享会 key 冲突、时序混乱。注册表把"读写谁的数据"收敛到 key 一点上；take 语义进一步把"读"与"存"解耦：某组件升级后不再消费旧 key，旧数据也不会丢，在多次进程死亡间继续存活。

**SDK 设计启示**：
- 一份共享数据要分发给多个互不相识的模块时，提供"注册产出 + 按 key 消费"的注册表，而不是让各方直接操作共享容器；适用条件是各方数据彼此独立、能用稳定 key 标识——key 无法稳定约定（如动态实例）时，注册表会把冲突从数据层推到命名层，反而更乱。
- 消费语义选"取走即删"而非"只读"，当且仅当数据的使命是"送达一次"（状态恢复、消息分发）；需要多方重复读取的配置类数据用只读语义，否则第二个读者拿不到数据。

## 错误校验前移到登记入口

**一句话**：延迟执行的操作，把登记时就能廉价校验的约束放在登记时验证并抛明确异常，而不是等执行时在反射/远端再炸。

**代码实例**（摘自 androidx/savedstate `savedstate/src/androidMain/.../SavedStateRegistry.android.kt` `runOnNextRecreation`）：

```kotlin
try {
    clazz.getDeclaredConstructor()   // 登记时即校验默认构造器存在
} catch (e: NoSuchMethodException) {
    throw IllegalArgumentException(
        "Class ${clazz.simpleName} must have default constructor ...", e)
}
recreatorProvider?.add(clazz.name)   // 校验通过才登记
```

**为什么精妙**：类重建发生在下一次进程死亡后的 ON_CREATE——离登记可能隔着一个发布周期；构造器缺失若拖到那时才暴露，现场只剩一个反射堆栈。登记时校验把错误从"线上重建时"提前到"开发接线时"。

**SDK 设计启示**：接受延迟回调/反射实例化的 API，在注册入口对可静态判断的约束（构造器、类型、命名）做 fail-fast 校验；适用条件是校验成本远低于执行成本、且登记点离开发者更近。运行期才能确定的条件（如静态块是否抛错、父类类型是否匹配）无法前移，靠执行期异常兜底即可。

## 资源自清挂宿主终态信号

**一句话**：资源有明确宿主且有可靠终止信号时，让资源自己以观察者身份挂在宿主状态机上，在终态事件里完成自取消/自摘除，而不是要求使用方在清理回调里逐处记得释放。

**代码实例**（摘自 androidx/lifecycle `lifecycle-common/src/commonMain/.../Lifecycle.kt`）：

```kotlin
// lifecycleScope：作用域实现类同时是事件观察者
internal class LifecycleCoroutineScopeImpl(...) : LifecycleCoroutineScope(), LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (lifecycle.currentState <= Lifecycle.State.DESTROYED) {
            lifecycle.removeObserver(this)   // 自摘除
            coroutineContext.cancel()        // 自取消
        }
    }
}
```

**为什么精妙**：靠使用方在 onDestroy 手动 cancel，Dialog/View 层等次级宿主极易漏接一处就泄漏；把取消挂进生命周期事件链后，宿主的终止信号天然全覆盖。同类做法还出现在 LifecycleController（销毁即取消 parentJob 并放行队列）。

**SDK 设计启示**：
- 资源生命周期有宿主可依时，让资源实现观察者接口自行注册、自行清理，使用方只拿到一个"即用即走"的入口；适用条件：宿主有唯一可靠的终态事件且资源与宿主一一对应——全局单例资源没有终态信号，得另配显式关闭 API。
- 自清逻辑里先摘注册再释放资源（先 removeObserver 后 cancel），避免释放过程中再次触发事件造成重入。

## 有序登记表容忍回调期增删

**一句话**：事件分发遍历登记表时，回调可能再增删表项——用"哈希索引 + 双向链表"的复合结构（FastSafeIterableMap）配合三处配合动作，让遍历容忍重入修改且保持登记序不变量。

**代码实例**（摘自 androidx/lifecycle `lifecycle-runtime/src/jvmCommonMain/.../LifecycleRegistry.jvm.kt`）：

```kotlin
// 遍历循环里防御"回调刚移除了后面的观察者"
while (ascendingIterator.hasNext() && !newEventOccurred) {
    while (observer.state < state && !newEventOccurred && observerMap.contains(key)) { ... }
}
// 新观察者目标态取三元 min，保证"先加者状态 ≥ 后加者"
return min(min(state, siblingState), parentState)
// 派发前把自身登记态压低到目标态，回调内重入时不会被虚高值误导
state = min(state, newState); lifecycleObserver.onStateChanged(owner!!, event); state = newState
```

**为什么精妙**：普通 HashMap 迭代中增删直接 ConcurrentModificationException；而生命周期回调恰恰天然要增删观察者（自取消、条件注册）。登记序不变量再保证中途注册者收到的事件不越过它前面的观察者。

**SDK 设计启示**：
- 回调式 API 的内部登记表按"遍历中可增删"设计：查找走哈希、遍历走链表，循环条件里每次重查 contains；适用条件：注册项数量中等且回调确实会改表——纯只读遍历用普通 Map 更简单。
- 有序性需求写成显式不变量并注明维持它的全部配合点（LifecycleRegistry 在类注释里写明不变量，目标态计算/派发压低/降序撤回三处各自维护）——只写不变量不写维持手段，后人一改就破。

## 版本号对账出粘性分发

**一句话**：订阅系统给每次更新发单调递增的版本号，每个订阅者记自己"最近送达的版本"，派发前对账——落后才发、齐平即跳过，粘性重放与去重由同一个机制兑现。

**代码实例**（摘自 androidx/lifecycle `lifecycle-livedata-core/src/main/java/androidx/lifecycle/LiveData.java`）：

```java
// 全局版本：每次 setValue 自增；观察者各记 mLastVersion（新观察者从 START_VERSION 起步）
if (observer.mLastVersion >= mVersion) {
    return;                      // 已送达，去重
}
observer.mLastVersion = mVersion;
observer.mObserver.onChanged((T) mData);   // 首次必然落后 → 粘性重放当前值
```

**为什么精妙**：一个计数器同时解决三个问题——新订阅者补发当前值（粘性）、重复激活不重发（去重）、两次分发之间来了多个值也不乱（单调可对账）。相比"脏标志位"方案：标志位分不清"哪个订阅者已读过"，也无法表达"补到最新"。

**SDK 设计启示**：
- 语义是"最新值"的订阅系统用版本对账而不是清标志位；适用条件：更新源单调递增、消费方只关心最新快照——需要全量事件的场景该用队列/Channel，版本对账会吞事件。
- 对账闸门要放在"实时活跃判定"之后（considerNotify 先查 shouldBeActive 再对账版本），否则不可见期间的无效派发会污染 lastVersion 记账。

## 早期调用入队，attach 时刻统一执行

**一句话**：依赖运行时身份/宿主能力的注册类 API，对"环境未就绪时就到达"的调用先入队暂存，等装配点统一执行，而不是拒绝或硬注册。

**代码实例**（摘自 androidx/fragment `Fragment.java`）：

```java
private abstract static class OnPreAttachedListener {
    abstract void onPreAttached();
}
// registerForActivityResult 在字段初始化器里就能调用（远早于 attach）：
// key 依赖 mWho（重建实例要从保存状态恢复），宿主能力也要 attach 后才知道，
// 于是把注册包成 listener 入队；performAttach 统一执行后清空
void performAttach() {
    for (OnPreAttachedListener listener : mOnPreAttachedListeners) { listener.onPreAttached(); }
    mOnPreAttachedListeners.clear();
}
```

**为什么精妙**：用户习惯在字段初始化器写 `registerForActivityResult(...)`，但正确的 key 和 registry 都要等 attach——提前拒绝会废掉最自然的用法，硬注册会拿错身份。入队把"就绪检查"从调用方挪到框架，双方都不用关心时序。

**SDK 设计启示**：
- 初始化/注册类 API 收到过早调用时存任务而非抛异常，在装配点统一冲账；适用条件：依赖的运行时上下文有明确的单一就绪时刻（attach/连接/启动完成），且任务执行体幂等、短小——任务可能失败或依赖调用方后续交互时，入队会把错误也推迟，改成立即校验。
- 就绪前的调用可以立即返回一个"委托壳"（Fragment 的 ActivityResultLauncher 代理对象），让调用方持有句柄先行，launch 时才要求就绪。

## 创建参数随请求传递，工厂无状态化

**一句话**：创建型扩展点的依赖不走工厂构造器，而是打进一个键控类型安全的参数袋随 create 请求传递——工厂因此可全局单例，新增参数只加 Key 不改签名。

**代码实例**（摘自 androidx/lifecycle `lifecycle-viewmodel/src/commonMain/.../CreationExtras.kt` + `ViewModelProvider.android.kt`）：

```kotlin
// 泛型 Key 把键与值类型绑定，get 直接得到目标类型
public interface Key<T>
public operator fun <T> get(key: Key<T>): T?
// 标准键：宿主在 defaultCreationExtras 塞入，工厂侧按需取
@JvmField public val APPLICATION_KEY: Key<Application> = CreationExtras.Key()
// AndroidViewModelFactory.create 里：
val application = extras[APPLICATION_KEY]   // 类型直接是 Application?
```

**为什么精妙**：工厂若构造期收依赖（`AndroidViewModelFactory(application)`），每个宿主都要持一个工厂实例，DI 注入与复用都变重；改为 `create(modelClass, extras)` 请求侧输入后，工厂无状态可共享，测试也不用造工厂实例。`viewModelFactory { initializer { ... } }` DSL 之所以能一行建工厂，前提正是"无状态 + 参数随请求"。

**SDK 设计启示**：
- 扩展参数集会增长、由调用方组合时，用"键控上下文对象"随请求传递而不是扩构造参数表（本库同一袋子里已并 APPLICATION_KEY/VIEW_MODEL_KEY/SAVED_STATE 键）；适用条件：参数确实随场景变化且提供方与消费方解耦——参数固定 ≤2 个时直接做 create 形参更直白，套参数袋是过度设计。
- Key 定义放消费方（工厂或常量伴生对象）而非使用方，避免使用方互相发明私钥导致同一语义多键并存。

## 描述符指纹分流，整值快路径绕过逐元素遍历

**一句话**：序列化格式后端按"descriptor 对象同一性"识别已知类型，命中即整值直写原生存储，绕过通用的逐元素结构遍历。

**代码实例**（摘自 androidx/savedstate `savedstate/src/commonMain/.../serialization/SavedStateEncoder.kt`）：

```kotlin
override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
    // 三层：平台特化 → 描述符指纹快路径 → super 通用兜底
    when (serializer.descriptor) {
        intListDescriptor -> savedState.write { putIntList(key, value as List<Int>) }
        stringListDescriptor -> savedState.write { putStringList(key, value as List<String>) }
        // ... 其余 List/Array 指纹同理，整值直写
        else -> super.encodeSerializableValue(serializer, value)  // 通用逐元素遍历
    }
}
```

**为什么精妙**：`List<Int>` 走通用协议要 beginStructure + N 次 encodeElement/encodeInt，而 Bundle 本来就有 `putIntList` 原生存档——按 descriptor 身份匹配一发命中，性能关键路径（基础类型集合）与完备性（任意 @Serializable 类型）各走各的门，互不拖累。

**SDK 设计启示**：
- 容器有原生集合/数组存储档时，为高频基础类型开"整值直写"快路径，匹配键用序列化器的 descriptor 身份而非运行时类型（泛型擦除下后者不可靠）；适用条件：两端共享同一 descriptor 且指纹可静态枚举——descriptor 随用户自定义类型开放时指纹表失效，只能靠注册机制。
- 分发链尾必须留通用兜底（super 调用），快路径只是优化不是门槛；漏兜底会让新类型直接抛"不支持"。

## 双读默认值消歧，缺失与存值分开

**一句话**：带默认值返回的 getter（Bundle/Map 风格）区分不了"key 缺失"与"存的恰好是默认值"，用两个相反默认值各读一次即可消歧。

**代码实例**（摘自 androidx/savedstate `savedstate/src/androidMain/.../SavedStateReader.android.kt` getBoolean）：

```kotlin
public actual fun getBoolean(key: String): Boolean {
    val result = source.getBoolean(key, false)   // 缺失也返回 false，存 false 也返回 false
    if (result == false) {
        val reference = source.getBoolean(key, true)
        if (reference == true) {                 // 第二读拿到默认值 ⇒ key 必缺失
            keyOrValueNotFoundError(key)
        }
    }
    return result                                // 真存了 false：两读都是 false，正常返回
}
```

**为什么精妙**：`getBoolean(key, false)` 的返回值对"缺失"与"存了 false"是同一观察结果；换一个相反的默认值复读，默认值露馅即缺失。 Char/Int/Long 同理（取一个不可能存的哨兵默认值更省一次读，但布尔只有两态，双读是唯一解）。

**SDK 设计启示**：
- 包装"缺失敏感"的读取 API（缺失要报错或走兜底）时，在 API 内部做双读/哨兵消歧，把 Bundle 默认值语义挡在门面之后；适用条件：底层 getter 支持自定义默认值、且读取次数成本可忽略——高频路径（每帧调用）要改用 containsKey 预判。
- 若默认值可被用户合法存储，哨兵值方案（读到一个"不可能的值"即缺失）不可用，双读的"相反默认值"方案仍成立。

## 账本操作与状态迁移分离

**一句话**：写操作只改内存账本并打标志位，状态迁移（副作用：回调、动画、IO）由独立环节统一批量执行——账本快而纯，迁移慢而集中。

**代码实例**（摘自 androidx/fragment `FragmentManager.addFragment` / `executeOpsTogether`）：

```java
// 六兄弟（addFragment/removeFragment/hideFragment/...）只做账本操作：
void addFragment(Fragment fragment) {
    fragment.mFragmentManager = this;
    mFragmentStore.makeActive(fragmentStateManager);   // 改 active 册
    mFragmentStore.addFragment(fragment);              // 改 added 册，打标志
}                                                       // 不触发任何生命周期回调
// 真正迁移统一在管线第 4 步：受影响 fragment 逐 op moveToExpectedState，
// 之后 moveToState(mCurState, true) 全量收敛剩余 fragment
```

**为什么精妙**：迁移过程中随时可能再触发事务（回调里 commit）或需要动画介入；若边记账边迁移，每步都要处理重入与半成品状态。分离后账本操作幂等廉价，迁移在受控点集中发生，还能先 expandOps 去重优化再一次性执行。

**SDK 设计启示**：
- 对象集合的增删改查拆成"改账本"与"应用状态"两阶段，中间留一个批量执行点；适用条件：迁移有昂贵副作用（回调/动画/持久化）且需要优化窗口或重入保护——单对象、无副作用的场景直接改直接生效即可，拆开反而添复杂度。
- 账本设计成可重放的命令序列（Op 列表），撤销（pop）就是逆序执行逆命令。

## 元操作双形态：执行时展开，持久化保持折叠

**一句话**：用户语义级的复合指令（meta-op）执行前展开为原子操作序列，落盘/撤销时用标记位把展开产物收回折叠形态——对外永远是用户写的原始形状。

**代码实例**（摘自 androidx/fragment `BackStackRecord.expandOps/collapseOps`）：

```java
// OP_REPLACE 是复合指令（同容器 remove 多个 + add 一个），
// 批量执行需要逐 op 追踪 mAdded，故先展开：
case OP_REPLACE: {
    // ...同容器旧 fragment 全部生成 OP_REMOVE（标 mFromExpandedOp = true）
    op.mCmd = OP_ADD;
    op.mFromExpandedOp = true;
}
// 保存 back stack 前收回折叠形态，持久化的永远是原始 op 序列：
void collapseOps() {
    // 倒序扫 mFromExpandedOp == true 的 op，合回一个 OP_REPLACE
}
```

**为什么精妙**：两层消费者要的形状不同——执行器要原子粒度才能精确追踪与逆放，持久化/用户视角要语义粒度才紧凑可读。双形态 + 标记位让两者共存于同一列表，避免维护两份账本失同步。

**SDK 设计启示**：
- 复合指令类 API（replace/批量更新/组合事务）内部用"标记位展开-折叠"对，而非两套数据结构；适用条件：展开规则确定性可逆（折叠能精确还原）——含不可逆副作用或依赖外部状态的展开无法折叠，只能显式复制。
- 逆放/撤销操作跑在折叠形态上，天然与用户意图一一对应。

## 注解当版本探针，反射兜底旧依赖

**一句话**：SDK 依赖方使用的库版本不可控且 API 归属包在版本间变动时，运行时反射检查旧 API 上的注解（如 @Deprecated）来判版本，按结果在"直接引用"与"反射取旧路径"间切换。

**代码实例**（摘自 androidx/savedstate `savedstate-compose/src/androidMain/.../LocalSavedStateRegistryOwner.android.kt`）：

```kotlin
val methodRef = classLoader.loadClass("androidx.compose.ui.platform." +
    "AndroidCompositionLocals_androidKt").getMethod("getLocalSavedStateRegistryOwner")
if (methodRef.annotations.any { it is Deprecated }) {
    null                     // 旧方法已标废弃 ⇒ Compose 1.7+，用自己的 CompositionLocal
} else {
    methodRef.invoke(null)   // 未标废弃 ⇒ Compose 1.6，反射取旧 CompositionLocal
}
// 协商失败兜底：无默认值的 CompositionLocal（读取即抛错，不静默给空实现）
```

**为什么精妙**：注解是库作者在发版时打的"版本指纹"，比猜版本号、比try-catch类加载都可靠——废弃必然发生在新路径可用之后，用注解判断新旧两不误。

**SDK 设计启示**：
- 兼容多版本依赖时优先找"版本必然伴随的源码级信号"（注解、方法签名、常量）做运行时探针，而不是解析版本号字符串；适用条件：信号与版本严格单调对应、且探测成本只在初始化期付一次。信号不存在时退回 try-loadClass 降级链。
- 反射兼容路径必须配套：自定义 Proguard/keep 规则防混淆、缓存反射产物（本例在 CompositionLocal 初始化时一次算完）、公开移除计划（如依赖约束把反射路径绑定到旧版稳定为止）。

## 恢复状态消费即删，保存时真值优先合流

**一句话**：恢复型数据的账本三原则——恢复时整包一次性消费（读走即删）、未消费的余量在下次保存时垫底、在场属主的真值覆盖余量；属主缺席时数据续命、在场时不被旧值污染。

**代码实例**（摘自 androidx/lifecycle `lifecycle-viewmodel-savedstate/src/commonMain/.../SavedStateHandleSupport.kt`）：

```kotlin
// 恢复：整包消费一次，留作本地余量；按 key 消费即删
val newState = savedStateRegistry.consumeRestoredStateForKey(SAVED_STATE_KEY)
state.write { remove(key) }          // consumeRestoredStateForKey 内
// 保存：余量先垫底（VM 可能已全部不在场），在场 handle 的真值再覆盖
restoredState?.let { putAll(it) }
viewModel.handles.forEach { (key, handle) -> putSavedState(key, handle.savedStateProvider().saveState()) }
```

**为什么精妙**：进程死亡与重建之间没有任何活对象可依赖，恢复数据若"每次保存都全量重放"，已被属主抛弃的旧状态会永久复活；若"消费后即焚"，属主还没来得及重建时数据又丢了。垫底+覆盖的合流策略同时满足两头。

**SDK 设计启示**：
- 恢复/同步型数据设计成"账本"而非"快照重放"：读走即删、保存合流、余量兜底；适用条件：数据有明确属主周期且属主缺席时需续命——无属主概念的纯缓存直接 LRU 淘汰即可，不必引入消费语义。
- 真值与余量冲突时的覆盖方向必须单一且显式（本库：在场真值永远赢），双向合并是数据错乱的温床。

## ERROR 级遮蔽重载，把必须项变成编译错误

**一句话**：vararg 重载无法在类型上表达"至少一个参数"，就补一个同签名的无参重载并标 `@Deprecated(level = ERROR)` 遮蔽它——漏传在编译期报错，而不是运行时 error()。

**代码实例**（摘自 androidx/lifecycle `lifecycle-runtime-compose/src/commonMain/.../LifecycleEffect.kt`）：

```kotlin
// vararg 版：keys 可以为空，但语义上 key 必须有（身份变化驱动效果重启）
public fun LifecycleResumeEffect(vararg keys: Any?, lifecycleOwner: ..., effects: ...) { ... }
// 遮蔽版：无 key 调用命中它，直接编译失败
@Deprecated(LifecycleResumeEffectNoParamError, level = DeprecationLevel.ERROR)
public fun LifecycleResumeEffect(lifecycleOwner: ..., effects: ...): Unit = error(...)
```

**为什么精妙**：Kotlin vararg 允许空数组，"必须传 key"只能靠运行时校验兜底；利用重载解析"精确签名优先于 vararg"的规则，用一个 ERROR 级废弃函数把非法调用挡在编译期，且报错消息即迁移指引（废弃消息就是错误文案）。

**SDK 设计启示**：
- API 有"必填但类型系统表达不了"的参数时（vararg、可空透传、DSL 隐式参数），用 ERROR 级遮蔽重载替代运行时异常；适用条件：非法形态可被一个更窄签名枚举出来——非法形态发散（任意组合都非法）时只能靠 require 校验。
- 遮蔽重载的废弃消息写成人话指南（本库常量 LifecycleResumeEffectNoParamError 同时充当运行时 error 文案），编译错误即文档。

## 探测、裁决、惩罚三层拆分 lint 架构

**一句话**：运行时 lint（StrictMode 类）把"发现问题"与"处置问题"拆成三层——埋点探测只报告、策略层只裁决、惩罚层只执行，新增检测项不动架构。

**代码实例**（摘自 androidx/fragment `strictmode/FragmentStrictMode.kt`）：

```kotlin
// 探测点：主库在可疑调用处埋静态方法，只负责构造 Violation 并上交
fun onFragmentReuse(fragment: Fragment, previousFragmentId: String) {
    val violation = FragmentReuseViolation(fragment, previousFragmentId)
    val policy = getNearestPolicy(fragment)          // 裁决：就近继承策略 + Flag + 白名单
    if (policy.flags.contains(DETECT_FRAGMENT_REUSE) && shouldHandle(policy, ...)) {
        handlePolicyViolation(policy, violation)      // 执行：log / listener / death 依次
    }
}
```

**为什么精妙**：误用检测的最大成本是"误报打搅正常用户"——三层拆分后，探测点全量埋、是否生效完全交给策略（默认 LAX 零打扰），惩罚从日志到崩溃渐进升级；allowViolation 按类名豁免让迁移期代码能渐进清理。

**SDK 设计启示**：
- 运行时诊断体系按"埋点全量、裁决集中、惩罚分层"组织；适用条件：检测项会持续增加且误报容忍度因接入方而异——一次性检查脚本或无策略需求的校验直接抛异常即可，不必三层。
- 策略就近继承（最近祖先的 FragmentManager 策略优先，否则全局）让嵌套结构能局部收紧，是"作用域化配置"的轻量实现。
