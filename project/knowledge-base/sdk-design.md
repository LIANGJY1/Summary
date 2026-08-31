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
