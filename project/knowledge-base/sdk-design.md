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
- [共享内存代数，缓存校验零跨进程](#共享内存代数缓存校验零跨进程)
- [短锁快照、独立 IO 锁、防抖合并落盘](#短锁快照独立-io-锁防抖合并落盘)
- [前缀树登记处 + 死亡通知自清理](#前缀树登记处--死亡通知自清理)
- [通知按订阅方优先级分级派发](#通知按订阅方优先级分级派发)
- [接口探测式能力协商](#接口探测式能力协商)
- [早期调用入队，attach 时刻统一执行](#早期调用入队attach-时刻统一执行)
- [账本操作与状态迁移分离](#账本操作与状态迁移分离)
- [元操作双形态：执行时展开，持久化保持折叠](#元操作双形态执行时展开持久化保持折叠)
- [探测、裁决、惩罚三层拆分 lint 架构](#探测裁决惩罚三层拆分-lint-架构)
- [资源预算按相对单位计量](#资源预算按相对单位计量)
- [槽号即句柄：单点账本双端镜像](#槽号即句柄单点账本双端镜像)
- [锁内领票锁外兑现的回调保序](#锁内领票锁外兑现的回调保序)
- [世代号队列：过期消息一票否决](#世代号队列过期消息一票否决)
- [快慢双车道分离延迟需求](#快慢双车道分离延迟需求)
- [打分竞选替代分发表](#打分竞选替代分发表)
- [登记与生效分离，增删合并一次下发](#登记与生效分离增删合并一次下发)
- [申报-领取-路由三段式的域服务契约](#申报-领取-路由三段式的域服务契约)
- [多证据融合压低误杀率](#多证据融合压低误杀率)
- [配置即接口：能力目录先行](#配置即接口能力目录先行)
- [长多行锚点的行索引修复法](#长多行锚点的行索引修复法)
- [重配置前的在途资产排空配对](#重配置前的在途资产排空配对)
- [三张清单声明式治理](#三张清单声明式治理)
- [协商式流配置接口](#协商式流配置接口)
- [广播前快照，遍历全程放锁](#广播前快照遍历全程放锁)
- [日志先行做本地持久化的崩溃恢复](#日志先行做本地持久化的崩溃恢复)
- [流程拆步进状态机，多触发点分片续跑](#流程拆步进状态机多触发点分片续跑)
- [更新先入队，消费时机单一收口](#更新先入队消费时机单一收口)
- [测量即布局反推，产物与尺寸一次成型](#测量即布局反推产物与尺寸一次成型)
- [一次性强制标志堵轮询竞态窗口](#一次性强制标志堵轮询竞态窗口)
- [注册中心自举出固定句柄](#注册中心自举出固定句柄)
- [ABI 冻结接口配 AIDL 新接口加薄适配门面](#abi-冻结接口配-aidl-新接口加薄适配门面)
- [跨边界引用做镜像计数](#跨边界引用做镜像计数)
- [临时引用焊住跨边界记账窗口](#临时引用焊住跨边界记账窗口)
- [逻辑编号锚定易变资源](#逻辑编号锚定易变资源)
- [历史即兜底链](#历史即兜底链)
- [访客数据零落盘](#访客数据零落盘)
- [敏感输入按键按注册位图并集让渡](#敏感输入按键按注册位图并集让渡)
- [崩溃重试预算与遗忘期](#崩溃重试预算与遗忘期)
- [状态同步整表重发不做增量差](#状态同步整表重发不做增量差)
- [限制消费按自然重绑降级](#限制消费按自然重绑降级)
- [抑制凭证绑定调用进程生命周期](#抑制凭证绑定调用进程生命周期)
- [读写合并单调用，阻塞语义统一](#读写合并单调用阻塞语义统一)
- [包装层垫引用，两种归还路径都要定义](#包装层垫引用两种归还路径都要定义)
- [发布后配置冻结做成硬防线](#发布后配置冻结做成硬防线)
- [旁路账本做可信锚，数据区不可自证](#旁路账本做可信锚数据区不可自证)
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
- [多对一聚合订阅，一对多本地限流分发](#多对一聚合订阅一对多本地限流分发)
- [错误定向回传最后写入者](#错误定向回传最后写入者)
- [多源建议集中仲裁，来源只产信号不执行](#多源建议集中仲裁来源只产信号不执行)
- [三值谓词链组合过滤，全弃权默认放行](#三值谓词链组合过滤全弃权默认放行)
- [递归注册表构建期剪环，而非禁止委托](#递归注册表构建期剪环而非禁止委托)
- [平台行为缺陷在框架层收口](#平台行为缺陷在框架层收口)
- [诊断设施双实现，生产路径零开销](#诊断设施双实现生产路径零开销)
- [耗时阈值当负载探针，大批量自发工作指数退避让路](#耗时阈值当负载探针大批量自发工作指数退避让路)
- [多等待来源统一成 fd，单点多路复用收口](#多等待来源统一成-fd单点多路复用收口)
- [持锁登记命令，放锁统一执行](#持锁登记命令放锁统一执行)
- [双队列加回执做按连接背压](#双队列加回执做按连接背压)
- [机制做解释器，变化外化为剧本数据](#机制做解释器变化外化为剧本数据)
- [信任域切换用 exec 接力](#信任域切换用-exec-接力)
- [不可复制资源推迟到复制完成后初始化](#不可复制资源推迟到复制完成后初始化)
- [跨 fork 资源靠继承交接，顺序约束用管道握手](#跨-fork-资源靠继承交接顺序约束用管道握手)
- [看护主循环单命令分片](#看护主循环单命令分片)
- [强依赖排序装配，弱依赖阶段广播](#强依赖排序装配弱依赖阶段广播)
- [框架留时序骨架，业务装可更新容器](#框架留时序骨架业务装可更新容器)
- [定制点建在依赖图根，换根不换源](#定制点建在依赖图根换根不换源)
- [探针只管可达，超时由观察者计时](#探针只管可达超时由观察者计时)
- [判定方可错，执行必须稳](#判定方可错执行必须稳)
- [采集调度与数据加工用插件契约分离](#采集调度与数据加工用插件契约分离)
- [配额超限整数倍记账，处置后从原谅点续计](#配额超限整数倍记账处置后从原谅点续计)
- [缓解动作先询价后执行，次数带降级窗口](#缓解动作先询价后执行次数带降级窗口)

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

**为什么精妙**：靠使用方在 onDestroy 手动 cancel，Dialog/View 层等次级宿主极易漏接一处就泄漏；把取消挂进生命周期事件链后，宿主的终止信号天然全覆盖。同类做法还出现在 LifecycleController（销毁即取消 parentJob 并放行队列）。远端进程场景同理：AAOS13 CarPropertyService 的 Client 构造时 linkToDeath，binderDied 里复用人手退订的同一注销路径（unregisterListenerBinderForProps），死亡清理与正常清理共用一条代码路径，不漂移。

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

## 一次性强制标志堵轮询竞态窗口

**一句话**：轮询型监控系统里，"刚交付的资源"要用一次性强制标志钉住状态，不能信下一次采样——采样落后于事实。

**代码实例**（摘自 frameworks/native `cmds/servicemanager/ServiceManager.cpp`）：

```cpp
// tryGetService 把 binder 交给客户端时：
service->guaranteeClient = true;
// 下一次 5 秒轮询检查时（handleServiceClientCallback）：
if (service.guaranteeClient) {
    if (!service.hasClients && !hasClients) {
        sendClientCallbackNotifications(serviceName, true);  // 强制上报"有客户端"
    }
    service.guaranteeClient = false;   // 一次性，用完即清
}
```

**为什么精妙**：客户端拿到 binder 到驱动引用计数真正增加之间有时间差，恰好落进轮询窗口就误报"无客户端"，lazy 服务端会自杀。强制标志把"事实已发生、计数未跟上"显式建模。

**SDK 设计启示**：
- 引用计数、存活探测等采样型机制，在资源交付点置一次性标志钉住最小正确状态；适用条件：交付与可观测之间存在延迟窗口、误判代价是资源被回收——采样即时可达（同步记账）的系统不需要。
- 标志语义必须是"下一次采样生效一次"，置位点多（交付点）可以，复位点必须唯一（采样点），否则难以推理失效条件。

## 注册中心自举出固定句柄

**一句话**：注册中心自己也要能被找到——用协议层硬编码句柄（handle=0）+ 启动期自注册打破"找注册中心要先有注册中心"的递归。

**代码实例**（摘自 frameworks/native `cmds/servicemanager/main.cpp`）：

```cpp
// 1. 向驱动登记：从此全系统对 handle=0 的请求都路由到本进程
ps->becomeContextManager();
// 2. 自注册：把自己当作普通服务放进自己的表
manager->addService("manager", manager, false, IServiceManager::DUMP_FLAG_PRIORITY_DEFAULT);
// 客户端侧零成本获取：BpBinder(0) 即注册中心代理
```

**为什么精妙**：分布式系统里"引导问题"（bootstrap problem）通常靠广播或配置文件，Binder 把答案压进协议常量——句柄 0 天然存在，谁注册成功谁就是中心，无需发现协议。

**SDK 设计启示**：
- 构建注册/发现类组件时先回答"指向自己的引用是什么"；有协议层可用就把入口压成常量句柄或保留路径；适用条件：系统内组件共享同一套底层寻址空间——跨网络、跨信任域时固定句柄要配选举与容错，不能只靠先到先得。
- 自注册动作与普通服务走同一条 addService 路径（含权限检查），中心不给自己开后门，安全模型保持单一。

## 共享内存代数，缓存校验零跨进程

**一句话**：跨进程缓存的失效判定不放在服务端、也不靠通知，而是把一个"写计数"放进双方共享的内存，客户端读缓存前在本地读一下计数是否变了。

**代码实例**（摘自 frameworks/base/packages/SettingsProvider `GenerationRegistry.java`）：

```java
// 服务端写路径：每次变更先自增共享内存里的代数槽位
final int index = getKeyIndexLocked(key, mKeyToIndexMap, backingStore);
backingStore.set(index, backingStore.get(index) + 1);
// 读路径：把共享内存本体（ashmem 描述符）、槽位号、当前代数塞进返回 Bundle
bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY, backingStore);
bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, index);
// 客户端配对（Settings.java GenerationTracker/NameValueCache）：
// isGenerationChanged() 本地读共享内存，与快照不等则清整表缓存
```

**为什么精妙**：缓存省下的跨进程调用若被"每次校验都要跨进程问一句"花回去就白省了——共享内存把校验降为一次进程内内存读，跨进程通知退化为丢了也无大碍的辅助信号。（客户端视角补充：失效粒度是表级清空而非键级，写路径完全不更新缓存，一致性全靠服务端涨代数。）

**SDK 设计启示**：
- 跨进程缓存把"有效性判定数据"与"值"分离：判定依据浓缩成一个计数器放共享内存，值仍在服务端；适用条件：读多写少、判定可浓缩为单个计数、双方同机可共享内存——跨设备、或判定依赖复杂结构时不成立。
- 校验与通知解耦后，通知可以放心做"尽力而为"：丢了也只是推迟到下一次读时的本地校验发现失效，一致性不受损。

## 短锁快照、独立 IO 锁、防抖合并落盘

**一句话**：高频小状态落盘三件套——状态锁内只拍快照、IO 走独立的锁与线程、多次写按双阈值防抖合并成一次原子替换。

**代码实例**（摘自 frameworks/base/packages/SettingsProvider `SettingsState.java`）：

```java
// 1) mLock 短锁拍快照并清脏标记；之后的新写入重新排程，不丢
synchronized (mLock) {
    settings = new ArrayMap<>(mSettings);
    mDirty = false;
    mWriteScheduled = false;
}
// 2) mWriteLock 串行 IO：AtomicFile 写临时文件，成功改名、失败回滚
synchronized (mWriteLock) {
    out = destination.startWrite();
    // ... 序列化 ...
    destination.finishWrite(out);   // 失败走 destination.failWrite(out)
}
```

**为什么精妙**：状态变更与磁盘 IO 的耗时差几个数量级，同锁会拖死写路径、无锁会读到半成品——快照把两者解耦成"生产者短锁、消费者串行"。

**SDK 设计启示**：
- 持久化 API 只暴露"置脏 + 排程"（scheduleWriteIfNeededLocked），"何时真正写盘"收口在一个方法里，同步写、防抖、强制写都从它分叉；适用条件：写频高、单笔小、可容忍秒级延迟的键值型状态——事务性要求强的结构化数据应走数据库。
- 落盘文件用 AtomicFile 三段式（startWrite/finishWrite/failWrite），保证盘上任一时刻都有完整可读副本，自建持久化不要手写"临时文件 + rename"。

**并列实例·两种合并策略**（frameworks/base/core/java/android/app/SharedPreferencesImpl.java）：SharedPreferences 的 apply 用"双代数"合并——内存代数每次提交自增、盘代数随写盘推进，写盘前发现内存代数已被更新的提交推进就放弃本次写（合并发生在写前，不靠延迟排程）。两种策略按"写盘调度权在谁手里"选择：有专用落盘线程用延迟排程，写盘任务可能被并发提交则用代数判让。

## 多对一聚合订阅，一对多本地限流分发

**一句话**：进程内多个消费者共享一个昂贵的上游订阅时，用聚合器把 N 份订阅压缩成 1 份（按最苛刻需求取值），再把上游推来的数据流按各消费者自己的需求本地裁剪分发。

**代码实例**（摘自 AAOS13 `packages/services/Car/car-lib/src/com/android/car/internal/CarPropertyEventCallbackController.java`）：

```java
// 注册方向：重算所有回调的最高频率，与已注册值相同就跳过 IPC
newMaxUpdateRateHz = calculateMaxUpdateRateHzLocked();
if (Objects.equals(mMaxUpdateRateHz, newMaxUpdateRateHz)) {
    return true;   // 无实质变化，不打扰服务端
}
// 分发方向：按各回调声明的频率做本地限流
if (carPropertyValue.getTimestamp() >= nextUpdateTimeNanos) { /* 放行并顺延 */ }
```

**为什么精妙**：每个模块各自向远端注册会让服务端与 HAL 收到 N 份重叠订阅、按最高频重复推送；聚合器对上游只呈现一份订阅、频率无实质变化不通信，对下游用"事件时间戳 + 1/各自频率"的顺延记账把同一股数据流裁剪成每人要的密度。

**SDK 设计启示**：
- 同一进程内多个消费者共享同一远端数据源时，按主题键（这里是 propertyId）建聚合器：上游订阅强度 = 消费者最大需求，需求变化才重新协商。适用条件：上游订阅/传输成本高、消费者需求异构——消费者少且需求一致时聚合器是纯开销。
- 限流必须在本地分发层做且基于数据自带的时间戳，不能用本地定时器——时钟基准不同会造成限流漂移，事件稀疏时定时器还会空转。

## 错误定向回传最后写入者

**一句话**：异步写操作失败时，错误只回传给"最后一次写入的当事人"而非广播订阅者；为此在写入受理路径上顺手记账当事人。

**代码实例**（摘自 AAOS13 `packages/services/Car/service/src/com/android/car/CarPropertyService.java`）：

```java
// set 受理后记录最后写入者：propId -> areaId -> Client
updateSetOperationRecorderLocked(propId, prop.getAreaId(), client);
// 写失败上行时查表定向派发
lastOperatedClient = mSetOperationClientMap.get(property).get(areaId);
```

**为什么精妙**：写失败的错误只有发起者有上下文处理，广播给全部订阅者既浪费又让无辜者收到无法理解的错误；记账动作嵌在写入受理里，零额外协议，注销时随订阅表一并清理。

**SDK 设计启示**：
- 异步错误需要路由回发起者时，在受理点建"请求 → 当事人"映射，失败点查表定向派发。适用条件：错误语义与发起者强相关且存在多并发写入者——单写入者场景直接抛异常即可，硬件级故障类错误本该全员感知则应广播。

## ABI 冻结接口配 AIDL 新接口加薄适配门面

**一句话**：已发布接口的签名永久冻结，演进靠"新定义一套接口 + 一个薄适配器实现旧接口"，适配器只做类型翻译。

**代码实例**（摘自 frameworks/native `libs/binder/IServiceManager.cpp`）：

```cpp
// 老接口（String16 签名，无数预编译代码依赖，不能改）
class ServiceManagerShim : public IServiceManager {
    sp<android::os::IServiceManager> mTheRealServiceManager;  // AIDL 新接口
    sp<IBinder> checkService(const String16& name) const override {
        sp<IBinder> ret;
        // 适配器全部职责：String16 -> std::string 翻译 + 转发
        if (!mTheRealServiceManager->checkService(String8(name).c_str(), &ret).isOk())
            return nullptr;
        return ret;
    }
```

**为什么精妙**：接口迁移的最大阻力是存量二进制；双接口 + 薄适配让新旧世界各自干净——新接口可随意演进，老接口冻结不动，翻译成本集中在一个类里。

**SDK 设计启示**：
- 接口发布的瞬间就当它"冻结"，功能演进先想新接口而不是加重载；适用条件：接口有大量无法重新编译的使用方（系统库、跨语言绑定）——纯内部接口直接改签名成本更低。
- 适配器要放在**依赖注入点**上（defaultServiceManager 单例内部），让所有调用方无感切换，各调用点零改动。

## 跨边界引用做镜像计数

**一句话**：跨进程/跨机引用一个远端对象时，本地每种引用（强/弱）都与远端计数一一配对增减，而不是靠"对象不要了通知对端"。

**代码实例**（摘自 frameworks/native `libs/binder/BpBinder.cpp`）：

```cpp
BpBinder::BpBinder(...) { extendObjectLifetime(OBJECT_LIFETIME_WEAK); }
// 构造：驱动弱引用+1（incWeakHandle，配对点）
void BpBinder::onFirstRef()  { ipc->incStrongHandle(...); }   // 首个强引用配对
void BpBinder::onLastStrongRef(...) { ipc->decStrongHandle(...); } // 末个强引用配对
BpBinder::~BpBinder() { ipc->expungeHandle(...); ipc->decWeakHandle(...); }
```

**为什么精妙**：分布式对象生命周期的正确解法不是心跳或 GC，而是把本地引用计数的每次变化镜像到对象所属边界——本地进程随时崩溃，内核/远端靠镜像计数自动回收，无泄漏无悬挂。

**SDK 设计启示**：
- 资源跨边界时选"镜像计数"：每类本地引用各找一个确定性钩子配对，边界句柄的生命周期 = 弱镜像的生命周期；适用条件：引用方随时可能崩溃、远端无法主动探测引用方——同进程对象用 shared_ptr 即可，双计数是纯开销。
- 弱镜像先行、强镜像随后（先保证句柄存在再表达使用意图），升级（promote）以远端强计数为准，天然获得"死对象不可复活"语义。

## 多源建议集中仲裁，来源只产信号不执行

**一句话**：多个可能互相矛盾的数据来源都只提交"建议"（带可换算基准的自描述信号），执行权收口到唯一仲裁者——统一校验、按优先级择优、单一出口执行。

**代码实例**（摘自 Android 13 frameworks/base `services/core/java/com/android/server/timedetector/TimeDetectorStrategyImpl.java`）：

```java
// 五路来源（NITZ/NTP/GNSS/车端/手动）各自提交建议，仲裁者集中裁决：
int[] originPriorities = mEnvironment.autoOriginPriorities();
for (int origin : originPriorities) {          // 优先级短路：第一条有效即用
    TimestampedValue<Long> newUnixEpochTime = findValidSuggestion(origin);
    if (newUnixEpochTime != null) {
        setSystemClockIfRequired(origin, newUnixEpochTime, cause);
        return;
    }
}
```

**为什么精妙**：各来源若自行设钟，坏信号会直接污染系统状态且无从裁决；建议-仲裁结构把"信号质量"问题转化为仲裁器一处的排序与校验问题，任何单源出错都只是"一条建议"，影响被结构性限制。

**SDK 设计启示**：
- 多输入源汇聚到同一系统状态时，把接口设计成"suggestXxx + 内部策略"，来源按权限隔离、执行单点收口。适用条件：来源可信度不一且可能冲突、状态只能有一个真值——各来源天然互斥或代价相同时无需仲裁层。
- 每条建议必须自带"时效与基准"（如 elapsedRealtime 锚点 + 统一过期上限），仲裁器才不依赖对来源的信任即可裁决。

## 临时引用焊住跨边界记账窗口

**一句话**：向远端记账系统（内核/服务端）发起增减引用的命令后、确认送达前，用本地临时引用垫住对象，确认后再释放——手工实现跨边界记账的原子性。

**代码实例**（摘自 frameworks/native `libs/binder/IPCThreadState.cpp`）：

```cpp
void IPCThreadState::incStrongHandle(int32_t handle, BpBinder *proxy) {
    mOut.writeInt32(BC_ACQUIRE);      // 命令只是入缓冲，未送达驱动
    mOut.writeInt32(handle);
    if (!flushIfNeeded()) {
        // 立即送不到 → 用本地临时引用垫住，防对象在窗口期析构
        proxy->incStrong(mProcess.get());
        mPostWriteStrongDerefs.push(proxy);  // 写缓冲确认送达后再释放
    }
}
```

**为什么精妙**：本地引用计数与内核节点计数是两套独立账本，"发出命令"不等于"对方记账"，中间窗口的对象生死必须有人担保——临时引用 + 送达后释放把两个时刻焊死，无需任何分布式事务。

**SDK 设计启示**：
- 跨边界记账每步都要回答"发起生效到对方确认之间谁担保资源活着"；适用条件：远端无法回查、本地可能瞬间释放的计数系统——能同步等待确认的直接同步调用即可。
- 释放点必须唯一且挂在"确认送达"事件上（本例 write_consumed 后统一处理），垫与放的配对逻辑集中一处，别散在调用点。

## 读写合并单调用，阻塞语义统一

**一句话**：双向命令通道的收发合并成一个系统调用（一次同时"交出写缓冲 + 领取读缓冲"），空闲阻塞自然落在该调用上，无需单独的等待/poll 通道。

**代码实例**（摘自 frameworks/native `libs/binder/IPCThreadState.cpp` talkWithDriver）：

```cpp
binder_write_read bwr;
bwr.write_size = mOut.dataSize();      // 要写的命令
bwr.write_buffer = (uintptr_t)mOut.data();
if (doReceive && needRead) {           // 有容量才申请读
    bwr.read_size = mIn.dataCapacity();
    bwr.read_buffer = (uintptr_t)mIn.data();
}
ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr);  // 唯一收发点
```

**为什么精妙**：客户端线程和服务端线程用同一套交互原语——有写有读立即交换，无写有读则阻塞等待，空闲即睡眠在内核等待队列；不需要独立 poll 线程，也不需要自旋。

**SDK 设计启示**：
- 设计命令式 IPC/网络协议时优先"单调用交换读写"，配合"写缓冲攒批"（mOut 攒多条命令一次送）；适用条件：协议支持批量命令且对延迟不敏感到可以攒批——低延迟逐条确认的协议不适用。
- 读缓冲必须显式腾空后才申请读（needRead 判定），防止旧命令被覆盖；这是合并读写最容易漏的防御点。

## 前缀树登记处 + 死亡通知自清理

**一句话**：跨进程"发布-订阅"注册表用 Uri 前缀树组织订阅者（按段挂载、按需生长），并用 Binder 死亡通知做自清理——订阅方进程崩溃不会留下幽灵订阅。

**代码实例**（摘自 frameworks/base/services/core/java/com/android/server/content/ContentService.java）：

```java
// 注册：沿 Uri 路径段下钻，缺节点则创建，叶节点挂 ObserverEntry
node.addObserverLocked(uri, index + 1, observer, ...);
// ObserverEntry 实现 IBinder.DeathRecipient：订阅方进程死亡自动摘除
final int entries = sObserverDeathDispatcher.linkToDeath(observer, this);
@Override public void binderDied() {
    synchronized (observersLock) { removeObserverLocked(observer); }
}
// 摘除后空节点剪枝，树不被历史注册撑肥
if (mChildren.size() == 0 && mObservers.size() == 0) { return true; }
```

**为什么精妙**：中心登记处最怕两件事——匹配慢（线性扫全部订阅者）与幽灵订阅（订阅方死了没人摘）。前缀树让匹配只走订阅路径那一支，死亡监听让清理不依赖订阅方自觉。

**SDK 设计启示**：
- 跨进程注册表以 Binder 句柄为凭证时，必须 linkToDeath 兜底自清理，再加"同句柄重复注册超阈值打 wtf"的软防线；适用条件：注册方与登记处分属两进程——同进程注册表用弱引用/生命周期钩子即可，死亡监听是多余开销。
- 订阅键含层级语义（authority/路径/命名空间）时用前缀树组织，天然支持"监听子树"语义（notifyForDescendants = 非叶节点也收集）。
- 并列来源：frameworks/base/core/java/android/os/RemoteCallbackList.java——内部类 Callback 实现
  IBinder.DeathRecipient，binderDied 摘表后回调 onCallbackDied；身份键取 asBinder() 的 IBinder，
  同一 Binder 重复注册只算一个（注册不计数）。

## 广播前快照，遍历全程放锁

**一句话**：向一组回调广播时，先在锁内把注册表拷成快照，再在锁外逐个调用——遍历稳定性与注册吞吐解耦，回调体再慢、再重入都不炸表、不拖累注册方。

**代码实例**（摘自 frameworks/base/core/java/android/os/RemoteCallbackList.java beginBroadcast）：

```java
// 锁内：把注册表拷进 mActiveBroadcast 快照数组，随即出锁
synchronized (mCallbacks) {
    final int N = mBroadcastCount = mCallbacks.size();
    mActiveBroadcast = mCallbacks.keySet().toArray(mActiveBroadcast);
}
// getBroadcastItem 全程无锁读快照；finishBroadcast 清场
```

**为什么精妙**：回调体不可控（可能再触发注册/注销改表，锁内遍历直接撞并发修改），锁内回调又会让最慢的客户端卡死注册方——快照把"遍历稳定"与"注册并发"拆成两个互不拖累的临界区。

**SDK 设计启示**：遍历前拷贝、遍历中放锁；适用条件是回调体不可控、可能重入注册表的场景（广播中再注册/注销），回调短且可控时锁内直遍更简单——快照的拷贝与 GC 成本反成负担。


## 通知按订阅方优先级分级派发

**一句话**：事件分发方在派发前按订阅方的进程优先级分流——前台订阅者立即送达，后台订阅者延迟合并送达，用少量后台延迟换前台体验不被冲垮。

**代码实例**（摘自 frameworks/base/services/core/java/com/android/server/content/ContentService.java `ObserverCollector`）：

```java
// 同一订阅者的多个 Uri 先聚合成一次 onChangeEtc(Uri[]) 调用
value.add(uri);
// 前台立即发，后台 postDelayed 10 秒——onChangeEtc 是 oneway，写方永不阻塞
if (procState <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND || noDelay) {
    task.run();
} else {
    BackgroundThread.getHandler().postDelayed(task, BACKGROUND_OBSERVER_DELAY);
}
```

**为什么精妙**：通知风暴的伤害不对等——前台用户正在等结果，后台订阅者晚 10 秒无感。派发方拿得到订阅方进程状态（system_server 独有优势），就应该用它调度。

**SDK 设计启示**：
- 通知类 API 的"实时性"应该是分级的：前台即时、后台批量延迟，并给关键场景留 no-delay 逃生口（这里是 NOTIFY_NO_DELAY flag）；适用条件：派发方能廉价获取订阅方优先级、且订阅方对秒级延迟不敏感——硬实时链路不适用，要靠专用通道。
- 聚合先于派发：同订阅者的多个变更合并成一次调用（Uri[]），Binder 往返次数与变更次数解耦。

## 包装层垫引用，两种归还路径都要定义

**一句话**：包装对象（代理壳）替底层资源先垫住引用时，"被使用的归还路径"与"从未被使用的归还路径"必须同时定义，缺一即泄漏。

**代码实例**（摘自 frameworks/native `libs/binder/Binder.cpp` BpRefBase）：

```cpp
BpRefBase::BpRefBase(const sp<IBinder>& o) {
    extendObjectLifetime(OBJECT_LIFETIME_WEAK);
    mRemote->incStrong(this);           // 垫住：替"未来的主人"持有
    mRefs = mRemote->createWeak(this);  // 终生死引用，保 mRemote 检查不悬挂
}
void BpRefBase::onFirstRef() {          // 被 sp 接住：所有权转移给主人
    mState.fetch_or(kRemoteAcquired);
}
BpRefBase::~BpRefBase() {
    if (!(mState & kRemoteAcquired)) mRemote->decStrong(this);  // 从未被用：自己归还
    mRefs->decWeak(this);
}
```

**为什么精妙**：壳是弱生命周期对象——构造引用的"应还人"取决于壳后来有没有主人，kRemoteAcquired 一个标志位把两种命运分开，onLastStrongRef 与析构各走各的归还，零泄漏零重复释放。

**SDK 设计启示**：
- 包装层垫引用的每一步都要画"归还决策树"：谁垫、谁还、何时不还（已转移）；适用条件：包装对象生命周期与资源不同步（弱生命周期壳、懒加载持有者）——壳与资源严格同生共死时直接持有即可。
- 状态标志用一次性置位（fetch_or），语义是"所有权已转移、不可逆"，天然幂等。

## 发布后配置冻结做成硬防线

**一句话**：对象一旦"发布"（发给其他进程/子系统），配置类 setter 全部拒绝调用并 FATAL，把"发布后不可变"从文档约定变成代码防线。

**代码实例**（摘自 frameworks/native `libs/binder/Binder.cpp` BBinder）：

```cpp
void BBinder::setRequestingSid(bool requestingSid) {
    LOG_ALWAYS_FATAL_IF(mParceled,           // 发给远端后 mParceled=true
        "setRequestingSid() should not be called after a binder object "
        "is parceled/sent to another process");
    ...
}
```

**为什么精妙**：远端已按发布时的配置建立预期，事后改配置 = 跨进程行为不一致且极难复现——FATAL 让问题在开发期第一现场爆炸，而不是变成线上玄学。

**SDK 设计启示**：
- 有"发布/初始化完成"时刻的对象，所有影响行为的 setter 统一挂冻结断言；适用条件：配置会被远端缓存或依赖（跨进程服务、序列化后分发的对象）——纯本地对象不必，断言本身有成本。
- 冻结状态由资源流转自动置位（parceled 时驱动流程标记），不依赖使用者记得调 freeze()。

## 三值谓词链组合过滤，全弃权默认放行

**一句话**：多条过滤规则各自实现成"true 必须处理 / false 必须丢弃 / null 无意见"的三值谓词，链式执行、首个有态度者生效，全部弃权则走默认放行。

**代码实例**（摘自 Android 13 frameworks/opt/telephony `src/java/com/android/internal/telephony/nitz/NitzSignalInputFilterPredicateFactory.java`）：

```java
TrivalentPredicate[] components = new TrivalentPredicate[] {
        createIgnoreNitzPropertyCheck(deviceState),   // 总开关：false 或 null
        createBogusElapsedRealtimeCheck(context,..),  // 坏基准剔除：false 或 null
        createNoOldSignalCheck(),                     // 无历史必处理：true 或 null
        createRateLimitCheck(deviceState),            // 限频：true 或 false（末位裁决）
};
// 链式执行：首个非 null 结果即生效，全 null 默认 true
```

**为什么精妙**：把 N 条过滤规则写成一坨嵌套 if 时，规则间耦合"判断顺序 + 判断逻辑"，删改一条要通读全部；三值链让每条规则只回答自己有资格回答的问题，规则可独立测试、可任意增删排序。

**SDK 设计启示**：
- 过滤/校验规则集合会持续增长时，用三值谓词链替代布尔与或组合，并按"成本从低到高"排列（便宜的总开关在前、逐字段比较在后）。适用条件：规则各自独立、存在"无意见"空间、默认行为可明确——规则间必须全序裁决（先到先得）且无全局最优诉求时不适用。
- 默认值（全弃权时的行为）是链的契约的一部分，必须显式声明并写进接口文档，否则增删规则会静默改变兜底行为。

## 旁路账本做可信锚，数据区不可自证

**一句话**：反序列化不可信来源的数据时，"这段内容是合法对象"的判断依据放在独立维护的旁路账本里，数据区自身的结构再像也不算数。

**代码实例**（摘自 frameworks/native `libs/binder/Parcel.cpp`）：

```cpp
// 读特殊对象前必须验证：当前位置登记在偏移表里（驱动改写过的才可信）
const flat_binder_object* Parcel::readObject(bool nullMetaData) const {
    ...
    if (OBJS[opos] == DPOS) {       // 偏移表命中 → 驱动背书，可信
        mNextObjectHint = opos+1;
        return obj;
    }
    ALOGW("Attempt to read object ... not in the object list");  // 未登记 → 拒绝
```

**为什么精妙**：恶意对端可以在数据区伪造任意 flat_binder_object（塞 handle 骗代理、塞 fd 骗文件），数据自证清白不可能；偏移表由驱动按真实写入维护、随事务一起送达，伪造者碰不到它。

**SDK 设计启示**：
- 处理外部输入中的"指针/句柄/引用"类内容时，配一条独立通道的可信清单（伴随元数据、Merkle 证明、内核记账），消费前先对账；适用条件：内容可被伪造且误信后果是越权——纯展示数据或已有外层鉴权时不必双账本。
- 账本查询按消费顺序缓存游标（mNextObjectHint），顺序读摊薄为近似 O(1)。

## 多等待来源统一成 fd，单点多路复用收口

**一句话**：把异构的等待来源（设备数据、目录变化、外部唤醒）都抽象成"可读的 fd"，汇入同一个 epoll，由一个消费线程阻塞等齐。

**代码实例**（摘自 Android 13 `frameworks/native/services/inputflinger/reader/EventHub.cpp`）：

```cpp
// 三路 fd 注册进同一个 epoll 实例：设备数据、inotify 热插拔、唤醒管道
mEpollFd = epoll_create1(EPOLL_CLOEXEC);
inotify_add_watch(mINotifyFd, DEVICE_INPUT_PATH, IN_DELETE | IN_CREATE);
epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mINotifyFd, &eventItem);
pipe2(wakeFds, O_CLOEXEC);   // wake() 写一个字节即打断 epoll_wait
epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeReadPipeFd, &eventItem);
```

**为什么精妙**：单消费线程要同时等三种异构事件——轮询加时延与功耗，多线程要付锁与全局排序的代价；fd 抽象让一次 epoll_wait 全覆盖，空闲零 CPU、事件到达即时唤醒。

**SDK 设计启示**：
- 长驻消费线程的等待来源是个位数 fd 时，统一抽象成 fd 后单点收口，外部唤醒用非阻塞管道写一字节实现；适用条件：来源是 OS 可表达为 fd 的事件且时延敏感——来源成百上千或多为内存消息时改消息队列分层，硬套 fd 会造出假的文件描述符。

## 持锁登记命令，放锁统一执行

**一句话**：锁内需要调用"可能阻塞或重入"的外部回调时，只把回调登记进命令队列，回到持有者主循环再统一放锁执行。

**代码实例**（摘自 Android 13 `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`）：

```cpp
// 锁内：只登记，不执行
auto command = [this, connection, seq, handled]() REQUIRES(mLock) {
    doDispatchCycleFinishedCommand(...);   // 命令体内自行放锁后回调 policy
};
postCommandLocked(std::move(command));
// 主循环：清空命令队列，跑过命令则把下次唤醒提前到"立即"
if (runCommandsLockedInterruptable()) { nextWakeupTime = LONG_LONG_MIN; }
```

**为什么精妙**：policy 回调可能阻塞或反过来抢锁重入（类注释明言"持锁绝不调 policy"），这条纪律靠 review 守不住；命令队列把纪律变成数据结构——锁内天然只剩登记一种动作。

**SDK 设计启示**：
- 回调可能重入锁持有者时，把执行推迟到持锁方自己的主循环串行做，锁内只留登记；适用条件：调用方与回调方共享锁且回调方不可信（可能阻塞、可能回调回来）——确定轻量的只读回调不必绕队列，白白增加延迟。

## 双队列加回执做按连接背压

**一句话**：一对多投递系统给每条连接配 outbound（待发送）与 wait（已发未确认）两队列，对端缓冲写满就停在当前周期，慢消费者只堵自己不堵别人。

**代码实例**（摘自 Android 13 `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`）：

```cpp
status = connection->inputPublisher.publishKeyEvent(...);   // 写 socket
if (status == WOULD_BLOCK && !connection->waitQueue.empty()) {
    return;    // 对端没消费完：就地停下等 ack，不是错误
}
connection->waitQueue.push_back(dispatchEntry);
mAnrTracker.insert(dispatchEntry->timeoutTime, token);      // 超时按连接计时
// 应用回 ack → waitQueue 出队 → startDispatchCycleLocked 启动下一周期
```

**为什么精妙**：全局节流会让一个慢窗口卡死所有窗口；per-connection 双队列把阻塞面收窄到单条连接，超时（ANR）也按连接归因，对端 socket 缓冲（32KB）就是天然的背压边界。

**SDK 设计启示**：
- 对多消费者的扇出投递，流控与超时都按连接记账：写满即停视为正常背压而非故障，超时即归因到该连接；适用条件：消费者互不依赖、允许各自落后——消费者间有顺序约束时需另加同步层，双队列会掩盖失序。

## 机制做解释器，变化外化为剧本数据

**一句话**：平台代码只实现通用机制（解析/匹配/调度），把"何时做什么"全部外化为声明式配置数据，机制稳定、剧本多变的控制权分离。

**代码实例**（摘自 Android 13 `system/core/init/init.cpp` + `system/core/rootdir/init.rc`）：

```java
// init 核心不认识任何具体服务，只提供三节解析 + 事件匹配 + 命令执行
parser.AddSectionParser("service", std::make_unique<ServiceParser>(...));
parser.AddSectionParser("on", std::make_unique<ActionParser>(...));
parser.AddSectionParser("import", std::make_unique<ImportParser>(&parser));
// "启动什么、按什么顺序"全在 rc 里：on late-init → trigger post-fs / zygote-start / boot
```

**为什么精妙**：启动流程的演进（加服务、改顺序、分区分工）全落在 rc 文件里，vendor/odm 各分区自治声明服务，init 二进制数年不动——改剧本不用改演员。

**SDK 设计启示**：
- 变化频繁的"何时/做什么"组合外化成声明式配置，宿主只留稳定机制层；适用条件：变化集中在配置项组合、逻辑无复杂分支循环——需要条件分支/循环的编排表达力超出配置语言时，应上移回代码层（Android 后续让 Java 层接管复杂编排正是此因）。

## 信任域切换用 exec 接力

**一句话**：同一程序的不同阶段需要不同安全域时，用 exec 重新执行自己并带阶段参数完成切换，信任边界由内核在 exec 时保证，而非进程内代码自觉。

**代码实例**（摘自 Android 13 `system/core/init/main.cpp` + `first_stage_init.cpp`）：

```cpp
// main() 按参数分发阶段：FirstStageMain → SetupSelinux → SecondStageMain
if (!strcmp(argv[1], "selinux_setup")) { return SetupSelinux(argv); }
if (!strcmp(argv[1], "second_stage"))  { return SecondStageMain(argc, argv); }
// 每阶段末尾 execv 自己接力——SELinux 域转换只发生在 exec 时
execv("/system/bin/init", const_cast<char**>(args));  // args = {"init", "selinux_setup"}
```

**为什么精妙**：域内降级/升级都无法真正收窄已获得的权限，只有 exec 能让内核强制执行域切换；三次 exec 用同一个二进制实现了三个信任等级。

**SDK 设计启示**：
- 阶段间存在硬信任边界（域切换、能力丢弃）时用 exec 接力表达，边界由内核兜底；适用条件：确实需要内核保证的权限断崖——纯逻辑分阶段用函数调用即可，exec 接力徒增进程映像重建与调试成本。

## 不可复制资源推迟到复制完成后初始化

**一句话**：以"模板进程 fork 派生子进程"为架构的系统，线程/锁等 fork 无法正确复制的资源必须推迟到派生路径里初始化，模板进程本体绝不持有。

**代码实例**（摘自 Android 13 `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java`）：

```java
ZygoteHooks.startZygoteNoThreadCreation();  // preload 期间禁止建线程
preload(bootTimingsTraceLog);               // 只装载可安全复制的东西：类/资源/驱动
// ...
// Binder 线程池在子进程 specialize 通道里才起（ZygoteInit.zygoteInit → nativeZygoteInit）
```

**为什么精妙**：多线程进程 fork 只复制当前线程，其余线程在子进程里是"本该存在却不在"的幽灵——它们持有的锁永远无人释放。zygote 把这条物理约束变成了流程铁律。

**SDK 设计启示**：
- 模板进程 + 派生进程架构里，把资源按"可 fork 复制与否"分类：不可复制的（线程、锁、连接池）推迟到派生之后的 specialize 通道；适用条件：一个模板派生 N 个子进程——任何"顺手提前"的多线程初始化都会污染全部后代，且故障呈现为随机死锁，极难归因。

## 跨 fork 资源靠继承交接，顺序约束用管道握手

**一句话**：父子进程间的资源传递优先用 fd 继承（fork 即交付，零传输），父子初始化的顺序依赖用管道字节握手表达——父建好资源写 1 放行，子读到才继续。

**代码实例**（摘自 Android 13 `system/core/init/service.cpp` Service::Start/RunService）：

```cpp
pipe(pipefd->data());                    // fork 前建管道，双方天然持有
if (pid == 0) {                          // 子进程路径：
    RunService(..., std::move(pipefd));  //   RunService 内 read((*pipefd)[0],&byte,1) 阻塞
}                                        //   等"cgroup 已建好"通知，才继续 exec 二进制
errno = -createProcessGroup(proc_attr_.uid, pid_, use_memcg);
if (char byte = 1; write((*pipefd)[1], &byte, 1) < 0) { ... }  // 父进程建好 cgroup 后放行
// init 预建的监听 socket 同理：fork zygote 时 fd 继承，环境变量只传 fd 编号
```

**为什么精妙**：socket/cgroup 等资源若在子进程里创建，存在"父进程还不知道子 pid"的时序缺口；继承 + 握手把交接与顺序一次性解决，没有任何跨进程 fd 传输协议。

**SDK 设计启示**：
- 有亲缘关系的进程体系内，资源交接用继承、顺序用握手，不要发明传递协议；适用条件：存在明确 fork 顺序的父子体系——无亲缘进程仍需 SCM_RIGHTS，无真实顺序依赖时握手是多余同步。

## 看护主循环单命令分片

**一句话**：常驻看护进程把任务执行切成单片——每轮循环只执行一条命令，片与片之间固定处理事件源（信号/消息/定时），让任何长任务都拖不住事件响应。

**代码实例**（摘自 Android 13 `system/core/init/init.cpp` SecondStageMain 主循环）：

```cpp
while (true) {
    HandlePowerctlMessage(...);                    // 1. 关机请求先于一切命令
    am.ExecuteOneCommand();                        // 2. 只执行一条命令（队列驱动）
    HandleProcessActions();                        // 3. 服务超时/重启维护，算出下次唤醒点
    epoll.Wait(epoll_timeout);                     // 4. 有事件先收割子进程再执行回调
    if (am.HasMoreCommands()) epoll_timeout = 0ms; // 还有活 → 立即再来一轮
}
```

**为什么精妙**：成串执行命令会让 SIGCHLD 收割、ctl 控制消息排到整个队列之后——服务都死了还没人收割。单命令分片让响应延迟有确定上界，代价只是总吞吐略降。

**SDK 设计启示**：
- 管理型常驻进程的"任务执行"与"事件响应"分片交错，宁慢勿堵；适用条件：事件实时性（关机、崩溃收割）优先的看护者——吞吐型 worker 不适用，分片调度的开销是纯损耗。

## 强依赖排序装配，弱依赖阶段广播

**一句话**：系统初始化中真正的硬依赖用显式排序表达，其余服务不互相等待，而是订阅启动阶段（BootPhase）广播，在自己关心的阶段做自己的事。

**代码实例**（摘自 Android 13 `frameworks/base/services/java/com/android/server/SystemServer.java` + `SystemService.java`）：

```java
startBootstrapServices(t);   // 硬依赖波：AMS→PMS→WMS 的顺序手排，倒一个全盘倒
startCoreServices(t);
startOtherServices(t);       // 其余服务不互等，靠阶段对齐：
mSystemServiceManager.startBootPhase(t, SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE);
// PHASE 100→200→480→500→520→550→600→1000 逐级广播，服务在 onBootPhase(phase) 就位
```

**为什么精妙**：把"服务 A 必须在服务 B 前就绪"从网状依赖简化为"都等 PHASE_XXX"——新服务只需声明阶段，不需要知道谁先谁后，装配顺序从 O(n²) 关系降为一条时间线。

**SDK 设计启示**：
- 初始化能划分客观阶段的系统：强依赖用排序、弱依赖用阶段事件；适用条件：阶段边界有客观语义（显示就绪/传感器就绪/三方应用可启动）——无阶段可分的强网状依赖只能全序手排，硬造阶段只会把依赖关系藏进更难查的地方。

## 框架留时序骨架，业务装可更新容器

**一句话**：宿主框架只保留不可变的启动时序与安全骨架（谁在何时拉起、绑定、校验），演进频繁的领域业务装进可独立更新的容器（APK/插件），两者之间用稳定的 Binder 契约隔离。

**代码实例**（摘自 Android 13 `SystemServer.java` + `packages/services/Car/.../CarServiceImpl.java`）：

```java
// 框架侧只有时序骨架：systemReady 回调里按特性拉起宿主服务
private static final String CAR_SERVICE_HELPER_SERVICE_CLASS =
        "com.android.internal.car.CarServiceHelperService";
// 车机业务全在可更新 APK 里：onCreate 连 VHAL → init 子服务 → 注册 car_service
ServiceManagerHelper.addService("car_service", mICarImpl);
```

**为什么精妙**：车辆业务按 OEM/地区高速迭代，而框架升级要整机 OTA；把两者拆开后业务侧可独立灰度更新，框架侧的启动/绑定/安全时序保持代码级稳定。

**SDK 设计启示**：
- 领域逻辑的演进速度与宿主不一致时，拆成可更新容器 + 稳定契约；适用条件：领域边界清晰且 Binder/API 契约可长期稳定——契约本身频繁变化时，同步两边的成本会吞掉可更新性收益（需配套 API 版本纪律）。

## 定制点建在依赖图根，换根不换源

**一句话**：已依赖注入化的系统，把 OEM/场景定制收敛为"替换依赖图的根组件"——经组件工厂在进程创建时换掉整棵图，宿主源码零修改。

**代码实例**（摘自 Android 13 `packages/apps/Car/SystemUI`）：

```xml
<!-- AndroidManifest.xml：进程创建时的替换入口 -->
android:appComponentFactory="com.android.systemui.CarSystemUIAppComponentFactory"
```

```java
// CarSystemUIInitializer：把根组件换成车机版，原生启动编排原样复用
protected GlobalRootComponent.Builder getGlobalRootComponentBuilder() {
    return DaggerCarGlobalRootComponent.builder();
}
```

**为什么精妙**：SystemUI 与车机差异（多屏/仪表）巨大，但原生代码一行不改——所有差异收在 CarSystemUI 包内的新依赖图里，宿主升级与车机定制互不踩踏。

**SDK 设计启示**：
- 定制点优先建在依赖注入的根组件上（换根），而不是继承/复制宿主类；适用条件：目标已组件化（DI 图边界清晰）——未做依赖倒置的代码换不了根，硬造工厂层反而多一套并行维护的实现。

## 探针只管可达，超时由观察者计时

**一句话**：健康检查的探针只负责"能被目标线程执行到"，超时判定由观察者自己的时钟独立完成——"没消息"本身就是信号，不依赖被监控方的任何回调。

**代码实例**（摘自 Android 13 `frameworks/base/services/core/java/com/android/server/Watchdog.java`）：

```java
// 探针被 post 到目标线程队列前端；线程活着就会执行它并置 mCompleted
mHandler.postAtFrontOfQueue(this);
// watchdog 线程不等任何回调，只按自己记录的起点算迟到程度
long latency = SystemClock.uptimeMillis() - mStartTime;
if (latency < mWaitMax/2) {
    return WAITING;
}
```

**为什么精妙**：靠探针回调"我还活着"的方案，探针自身卡死等于监控失明；观察者计时让"沉默"成为可判定的信号，全部服务线程死锁时看门狗依然能醒。

**SDK 设计启示**：
- 监控/超时系统的判定信号优先取"预期事件的缺席"而非"主动报平安"；适用条件：观察者可单方计时或双方共享单调时钟——时钟不可信的分布式场景改用带序号的心跳。
- 探针路径上绝不持监控者自己的锁（本类 monitor() 回调全程锁外执行），否则目标线程的锁争用会反锁探针；适用条件：探针要调用被监控方代码的一切场合。

## 判定方可错，执行必须稳

**一句话**：超时杀类系统把"判定"与"执行"拆开后，执行侧要独立补三道防线——身份复核、环境豁免、最小缓刑；容忍误判重判，不容忍错杀。

**代码实例**（摘自 Android 13 `frameworks/opt/car/services/builtInServices/src/com/android/internal/car/CarServiceHelperService.java`）：

```java
// 杀前用 /proc/[pid]/stat 的真实启动时刻复核上报值，防 pid 复用杀错对象
if (!processInfo.doMatch(processIdentifier.pid,
        processIdentifier.startTimeMillis)) {
    return;
}
// dump 与 kill 之间保底 1s：trace 先落盘，给客户端收尾机会
if (dumpTime < ONE_SECOND_MS) { /* 延时补足 1s 再 kill */ }
```

**为什么精妙**：判定链（pid、超时结论）每一环都可能因并发而过期，执行是不可逆动作；三道防线让"判错"止步于多等一轮而不是造成事故。

**SDK 设计启示**：
- 不可逆动作执行前，用与判定通道独立的第二来源复核关键身份（本例 /proc 直读 vs 上报值，还含方向性校验"真实值 ≤ 上报值"）；适用条件：动作不可逆且判定与执行间存在时间差——同步紧邻调用不值得复核。
- 执行侧维护自己的环境豁免清单（如 sys.powerctl 关机中跳过杀进程），豁免条件属于执行语境、判定方不该越俎代庖；适用条件：豁免状态只有执行方可见时。

## 采集调度与数据加工用插件契约分离

**一句话**：采集器只按事件节奏采样（开机密采/周期/定制）并把数据交给 DataProcessor 插件，判定逻辑全部外置；插件还能经回调反向控制调度，请求提前插一轮采集。

**代码实例**（摘自 Android 13 `packages/services/Car/cpp/watchdog/server/src/WatchdogPerfService.cpp`）：

```cpp
// 消费插件回调带"请求器"：发现系统级写盘速率异常时，请求立刻插一轮采集
if (const auto result = processor->onPeriodicMonitor(now, mProcDiskStatsCollector,
        requestCollection);
    !result.ok()) {
    return Error() << processor->name() << " failed on " << ...;
}
```

**为什么精妙**：采集节奏稳定而判定逻辑多变（新增资源类型/阈值策略），插件化让两者独立演进；"发现异常的"与"负责采集的"解耦，却仍能经 requestCollection 协同加速响应。

**SDK 设计启示**：
- 生产者与消费者之间定义窄回调接口（一组 on* 事件），新增语义零改调度器；适用条件：采集/生产节奏稳定而加工逻辑预期多变——一次性管线硬拆插件只增间接层。
- 消费方需要影响生产节奏时，给回调传"请求器"而非暴露调度内部，并在调度侧限流（本例间隔小于 1s 的插队请求被忽略）；适用条件：消费方的请求可能高频或风暴时。

## 配额超限整数倍记账，处置后从原谅点续计

**一句话**：超限次数按"已用量 ÷ 阈值"整数记账，整数倍阈值记为"已原谅"字节，处置后从原谅点重新累计——同一份配额不被重复处置，也不一次超限终身追责。

**代码实例**（摘自 Android 13 `packages/services/Car/cpp/watchdog/server/src/IoOveruseMonitor.cpp`）：

```cpp
// 超限次数 = 已写量 ÷ 阈值（整数除法；零阈值记 1 次）
int32_t foregroundOveruses = div(writtenBytes.foregroundBytes, threshold.foregroundBytes);
// forgiven = 次数 × 阈值：处置后有效用量 = written - forgiven，从原谅点续计
forgivenWriteBytes.foregroundBytes = mul(foregroundOveruses, threshold.foregroundBytes);
```

**为什么精妙**：不设原谅点的累计口径会让"被禁用后恢复的应用"因历史用量仍在而立刻再次超限——原谅点让配额语义在处置之后依然成立，处罚与配额互不腐蚀。

**SDK 设计启示**：
- 配额/限流系统的处罚要定义"计费起点重置"语义（整数倍阈值或时间窗），并保证口径跨重启持久化一致（本例每日账本入库）；适用条件：处罚后允许继续使用同一资源——一次性封禁场景不需要。

## 缓解动作先询价后执行，次数带降级窗口

**一句话**：多种处置手段并存时，先让提供方报价（自评用户影响档位）再由账本持有者选最小执行；同一对象的执行次数在滑动窗口内计数并随执行传回，作为重试上限的依据。

**代码实例**（摘自 Android 13 `frameworks/base/services/core/java/com/android/server/PackageWatchdog.java`）：

```java
// 两阶段：onHealthCheckFailed 只报价（USER_IMPACT_*），execute 才真正动作
int impact = registeredObserver.onHealthCheckFailed(
        versionedPackage, failureReason, mitigationCount);
if (impact != PackageHealthObserverImpact.USER_IMPACT_NONE
        && impact < currentObserverImpact) { /* 记录最小者 */ }
currentObserverToNotify.execute(versionedPackage, failureReason, mitigationCount);
```

**为什么精妙**：选择逻辑不认识任何具体缓解手段，新增手段零改选择代码；mitigationCount（1 小时滑窗内的执行次数）让"回滚失败两次就别再试"这类重试上限成为观察者的自觉，机制无需硬编码。

**SDK 设计启示**：
- 处置手段以"报价-执行"插件接入，仲裁只看报价与历史次数；适用条件：手段的用户代价可比、且提供方比仲裁方更了解自己的代价——代价同质时直接定序即可。
- 重试上限不要硬编码在仲裁方，把计数传给执行方自行决定放弃；适用条件：不同手段的合理重试次数不同（回滚重试与清缓存重试的容忍度天然不同）。

## 逻辑编号锚定易变资源

**一句话**：为会重排/漂移的物理资源（屏、设备、用户绑定）配一个配置期固定、运行期不变的逻辑编号，跨进程 API 全部以编号为主键。

**代码实例**（摘自 packages/services/Car `car-lib/src/android/car/CarOccupantZoneManager.java` + `frameworks/base/core/java/android/companion/CompanionDeviceManager.java`）：

```java
// zoneId：跨用户切换/屏幕热插拔保持不变（javadoc 承诺），displayId/userId 都会漂移
/** This id will remain the same for the same zone across configuration changes ... */
public int zoneId;
// 伴随设备关联句柄从 MAC 地址演进为稳定 associationId，旧 MAC 句柄路径整体废弃
@Deprecated
public void disassociate(@NonNull String deviceMacAddress) { ... }
public void disassociate(int associationId) { ... }
```

**为什么精妙**：屏会热插拔、用户会切换、MAC 会随机化——若 API 以物理标识为主键，每次物理变化都迫使全部客户端迁移；编号不变，物理绑定关系集中在一个服务里维护更新。

**SDK 设计启示**：
- 多屏/多设备/多用户类 API 设计时先找"唯一不随环境变化的那一维"做主键，客户端缓存编号而非物理 id；适用条件：存在配置期即可枚举的稳定实体集合——实体本身动态生成（临时任务、临时会话）时编号反而成了需要回收的资源。
- 编号到物理资源的绑定关系收敛到唯一服务维护并对外只读，否则各客户端自持的映射会各自漂移；不适用：点对点拓扑没有中心服务时，改为周期广播全量映射。

## 历史即兜底链

**一句话**：把"最近使用历史"直接建成恢复时的回退链——读取时从头找第一个当前可用的项，链尾默认值兜底；换应用自动跟随、卸载自动回退共用同一条遍历路径。

**代码实例**（摘自 packages/services/Car `service/src/com/android/car/CarMediaService.java`）：

```java
// 保存：MRU 序列，先删再加队首
componentNames.remove(componentName);
componentNames.addFirst(componentName);
// 读取：从头找第一个仍装有 MediaBrowseService 的源，链尾兜底默认源
for (String name : getComponentNameList(serialized)) {
    ComponentName componentName = ComponentName.unflattenFromString(name);
    if (isMediaService(componentName)) { return componentName; }
}
return getDefaultMediaSource();
```

**为什么精妙**：历史（记录用户偏好）与恢复（容错回退）本是两个需求，通常做成两套数据；一份 MRU 列表同时服务两者，卸载、换源、默认值三种异常没有各自的恢复代码。

**SDK 设计启示**：
- 设计"记住用户选择"类状态时同时回答"首选不可用时退到哪"——让历史序本身承担回退优先序；适用条件：偏好项有廉价的可用性判定（组件在否、设备在否），且历史顺序与偏好顺序一致。
- 兜底默认值放链尾而不是独立配置，保证"全部失效"仍是同一条代码路径；不适用：回退需要不同策略（卸载后要提示而非自动换源）时，链条表达力不够。

## 访客数据零落盘

**一句话**：临时身份（访客/代客）的数据隔离不做专门子系统，而是让每个持久化点检查身份的 ephemeral 属性并跳过写入，配合"登出即销毁"的用户类型贯穿全链路。

**代码实例**（摘自 packages/services/Car `service/src/com/android/car/CarMediaService.java`）：

```java
// initUser：访客选默认源，不读历史
mPrimaryMediaComponents[MEDIA_SOURCE_MODE_PLAYBACK] = isCurrentUserEphemeral()
        ? getDefaultMediaSource() : getLastMediaSource(MEDIA_SOURCE_MODE_PLAYBACK);
// 换源不写历史
if (!isCurrentUserEphemeral()) { saveLastMediaSource(...); }
// 播放状态不落盘
if (isCurrentUserEphemeral()) { return; }
```

**为什么精妙**：访客模式的难点不在"建访客"，而在"访客痕迹泄漏在互不相关的持久化点"；判定收敛为同一个 isEphemeralUser 谓词后，各服务零协调地形成全链路无痕迹。

**SDK 设计启示**：
- 多身份系统的持久化写入点统一过身份谓词门，而不是建独立的访客存储；适用条件：身份属性可从进程上下文廉价查询——判定昂贵的系统宁可走独立存储空间。
- "零落盘"是三个以上写入点的系统属性，靠约定写不齐，要在持久化工具层或 review 清单里显式列点核对。

## 敏感输入按键按注册位图并集让渡

**一句话**：系统按键（如语音键）临时让渡给外部应用时，只拦截所有注册者声明位图的并集，无任何注册者时注销拦截句柄——平时不占用、让渡期不越权。

**代码实例**（摘自 packages/services/Car `service/src/com/android/car/CarProjectionService.java`）：

```java
// 并集：各投影应用注册自己要处理的按键位图
BitSet newEvents = computeHandledEventsLocked();
if (!newEvents.isEmpty()) {
    mCarInputService.setProjectionKeyEventHandler(this, newEvents);
} else {
    mCarInputService.setProjectionKeyEventHandler(null, null); // 无注册即注销
}
// 分发时再按各自位图过滤
if (eventHandler.canHandleEvent(keyEvent)) { ... }
```

**为什么精妙**：按"有投影应用"做粗粒度开关，投影应用没声明的按键也会被抢走；位图并集拦截 + 逐注册者过滤，两层都取最小集。

**SDK 设计启示**：
- 系统资源临时让渡采用申报制：拦截范围 = 注册者声明之和，注册清零即自动归还；适用条件：资源是可枚举的离散类型集合（按键/事件）——连续资源（麦克风、摄像头）只能整体让渡，需另配使用指示器。
- 让渡通道与普通通道保持同一拦截点，而不是为让渡方另开输入通路；不适用：让渡方语义与原通道差异过大时，同点拦截反而造成耦合。

## 崩溃重试预算与遗忘期

**一句话**：自动重启常驻/钉屏应用时给重试设三参数预算——重试间隔、连续上限、遗忘期（存活超过即计数清零）——应用"修好了"自动恢复，"一直崩"安静放弃。

**代码实例**（摘自 packages/services/Car `service/src/com/android/car/am/FixedActivityService.java`）：

```java
if (activityInfo.consecutiveRetries > 0
        && timeSinceLastLaunchMs < RECHECK_INTERVAL_MS) {
    continue;   // 未到重试间隔
}
if (timeSinceLastLaunchMs >= CRASH_FORGET_INTERVAL_MS) {
    activityInfo.consecutiveRetries = 0;   // 遗忘期清零，修好了自动恢复
}
if (activityInfo.consecutiveRetries >= MAX_NUMBER_OF_CONSECUTIVE_CRASH_RETRY) {
    continue;   // 达上限安静放弃，只记一次日志（failureLogged 防刷）
}
```

**为什么精妙**：无预算的自动重启会把仪表屏变成 crash 循环，硬放弃又让"修好的应用永远回不来"；遗忘期让"持续存活"本身成为恢复凭证，预算成为自愈的一部分而不是放弃宣告。

**SDK 设计启示**：
- 看护类组件的重启策略必须是三参数组（间隔/上限/遗忘期），无限重试或硬放弃单独都不可用；适用条件：崩溃可自愈（包更新、资源恢复）且重启代价可控——整机级重启代价时改为告警加人工介入。
- 被动退避（被顶到后台）不计入失败计数，预算只惩罚"真的没起来"；不适用：无法区分"没起来"与"被顶走"的系统只能保守全计。

## 状态同步整表重发不做增量差

**一句话**：跨进程的状态同步（如静音信息）每次变化都全量重算并整表下发，接收方以最后一次为准——不做增量 diff，一致性靠"重算加覆盖"而不是"记账"。

**代码实例**（摘自 packages/services/Car `service/src/com/android/car/audio/CarVolumeGroupMuting.java`）：

```java
public void carMuteChanged() {
    List<MutingInfo> mutingInfo = generateMutingInfo();      // 每次全量重算各音区
    setLastMutingInfo(mutingInfo);
    mAudioControlWrapper.onDevicesToMuteChange(mutingInfo);  // 整表下发
}
```

**为什么精妙**：静音有多个来源（用户、电源策略、HAL），增量同步要维护"谁改了哪条"的账，漏一条就永久漂移；全量重算把多源合并收敛在一次遍历里，任何时刻状态都等于"按当前事实重算的结果"。

**SDK 设计启示**：
- 多来源共同决定的状态，同步协议选整表重发而不是变更事件——来源合并逻辑只写一遍，不散落在每个来源的处理分支里；适用条件：全表体积小、重算廉价、下发频率低——大表或高频场景改版本号加增量。
- 接收方按"最后一次为准"的幂等覆盖设计，不假设收齐每种变更；不适用：状态本身有历史语义（事件流、审计）时不能覆盖。

## 槽号即句柄：单点账本双端镜像

- **一句话**：跨进程复用大块资源时，真正状态只存一份（账本），双方各自镜像一张"槽号→资源"表，每帧交互只传槽号。
- **代码实例**（AAOS13 `frameworks/native/libs/gui/BufferQueueCore.h`）：
```cpp
BufferQueueDefs::SlotsType mSlots;  // 64 槽单点账本
// 真缓冲仅在 requestBuffer 时跨进程传一次；之后每帧复用只传槽号与 fence
```
- **为什么精妙**：图形缓冲大且不可复制，按值传输不可行；把"传输物"降维成 int，配合代数校验（frame number）防旧句柄误用。
- **SDK 设计启示**：资源句柄化适用于"两端可各自维护可信镜像且有代数校验兜底"的场合；若镜像可能长期失同步且无校验位，句柄化会把 bug 变成错位渲染，此时应退回按值传输。

## 锁内领票锁外兑现的回调保序

- **一句话**：回调可能反抢主锁时，在锁内按序领票、释放锁后按票号排队兑现，保序与不持锁回调兼得。
- **代码实例**（AAOS13 `frameworks/native/libs/gui/BufferQueueProducer.cpp` queueBuffer）：
```cpp
callbackTicket = mNextCallbackTicket++;   // 锁内领票
// ... 释放 mCore->mMutex ...
while (callbackTicket != mCurrentCallbackTicket) mCallbackCondition.wait(lock);  // 锁外按票兑现
```
- **为什么精妙**：持锁回调会死锁（回调可能反手抢同一把锁），但先解锁又会让并发生产者的回调乱序；票据把"序"从锁上解耦出来。
- **SDK 设计启示**：适用条件是回调体内可能反抢主锁；若无锁竞争，直接锁内同步回调更简单，票据机制反而多一次唤醒往返。

## 世代号队列：过期消息一票否决

- **一句话**：可重建子组件的消息带世代号，每次重建自增，消费侧发现世代不符直接丢弃。
- **代码实例**（AAOS13 `frameworks/av/media/libmediaplayerservice/nuplayer/NuPlayer.cpp` kWhatScanSources）：
```cpp
int32_t generation;
CHECK(msg->findInt32("generation", &generation));
if (generation != mScanSourcesGeneration) break;  // 过期消息作废
```
- **为什么精妙**：异步组件树里旧组件的迟到消息无法撤回，世代号用一次比较完成"撤回"，比逐对象判活简单可靠。
- **SDK 设计启示**：适用条件是"子组件可重建、消息会跨重建周期滞留"；对不可重建组件无意义。

## 快慢双车道分离延迟需求

- **一句话**：同一硬件路径为低延迟与普通流量各建一条车道：快车道无锁固定周期直写硬件，慢车道功能齐全但延迟高。
- **代码实例**（AAOS13 `frameworks/av/services/audioflinger/FastMixer.cpp`）：
```cpp
// FastMixer：无锁状态队列交换快轨道数据，固定周期混音直写 HAL
// 规则：threadLoop 内除已知安全点外禁用库与系统调用
```
- **为什么精妙**：延迟是架构属性不是参数，单实现调参无法同时满足两种延迟预算；分车道让各自规则极端化。
- **SDK 设计启示**：当两条路径的延迟预算差一个量级且流量可静态分类时分离车道；流量不可预分类时不适用。

## 打分竞选替代分发表

- **一句话**：新增实现不加分发分支，而是注册工厂对输入自报匹配分，最高分者中标，零分落默认实现。
- **代码实例**（AAOS13 `frameworks/av/media/libmediaplayerservice/MediaPlayerFactory.cpp`）：
```cpp
thisScore = v->scoreFactory(a, bestScore);
if (thisScore > bestScore) { ret = sFactoryMap.keyAt(i); bestScore = thisScore; }
if (0.0 == bestScore) { ret = getDefaultPlayerType(); }
```
- **为什么精妙**：分发表的增删都改主干；竞选把"擅长什么"下放到各实现自述，主干只剩通用的比较循环。
- **SDK 设计启示**：适用条件是实现族会持续增长、匹配度可量化；分支少且稳定时直接 if-else 更直白。

## 登记与生效分离，增删合并一次下发

- **一句话**：重开销的"生效"操作（下发硬件/重配置）与轻量的"登记"分离，增删先记账，攒批后一次下发。
- **代码实例**（AAOS13 `frameworks/av/services/camera/libcameraservice/api2/CameraDeviceClient.cpp` createStream）：
```cpp
mStreamMap.add(binder, StreamSurfaceId(streamId, surfaceIds[i]));
mConfiguredOutputs.add(streamId, outputConfiguration);
// 流"已登记未生效"——真正下发 HAL 在下一轮 configureStreams
```
- **为什么精妙**：每次登记都触发硬件重配置会让成本爆炸；分离后多次增删合并成一次重配置，与事务攒批同构。
- **SDK 设计启示**：适用条件是"生效动作有高固定开销且可批量"；生效不可延迟（如强一致需求）时不可用。

## 申报-领取-路由三段式的域服务契约

- **一句话**：多域服务的域空间（如整车 HAL 上百个属性）按"子服务静态申报能力 → 中枢按设备实配下发子集 → 运行期按属性 ID 路由事件"三段协作。
- **代码实例**（AAOS13 `packages/services/Car/service/src/com/android/car/hal/HalServiceBase.java`）：
```java
public abstract int[] getAllSupportedProperties();          // 申报
public void takeProperties(Collection<HalPropConfig> p);    // 领取设备支持子集
public void onHalEvents(List<HalPropValue> values);         // 按 propId 路由
```
- **为什么精妙**：中枢零业务逻辑，新增域能力 = 新建子服务 + 注册，不改骨架；空申报即"万能兜底服务"。
- **SDK 设计启示**：适用于能力可静态枚举且按域正交切分的系统；能力无法静态枚举时，允许空申报 + isSupportedProperty 逐个问作为逃生口。

## 多证据融合压低误杀率

- **一句话**：高代价动作（如杀进程）的触发裁决融合多路独立证据（颠簸率/回收水位/swap 余量），各证据换算成同一条"允许烈度"分数线再取严。
- **代码实例**（AAOS13 `system/memory/lmkd/lmkd.cpp` mp_event_psi → find_and_kill_process）：
```cpp
// PSI 失速 + workingset refault 增长 + swap 余量 → min_score_adj
// 从 OOM_SCORE_ADJ_MAX 向 min_score_adj 逐档选杀，杀到释放量够即收手
```
- **为什么精妙**：单一信号在异构设备上噪声极大，多证据取严把"误杀"概率压到最低，代价只是偶尔晚杀。
- **SDK 设计启示**：破坏性自动动作（回收/降级/熔断）应有≥2 个独立证据且取最严值；证据源要覆盖不同失效模式（性能/容量/水位）。

## 配置即接口：能力目录先行

- **一句话**：设备能力用一张静态配置目录声明（读写性/上报模式/采样区间/分区配置），读取方先查目录再操作。
- **代码实例**（AAOS13 `hardware/interfaces/automotive/vehicle/aidl/impl/default_config/include/DefaultConfig.h`）：
```cpp
{.config = {.prop = toInt(VehicleProperty::PERF_VEHICLE_SPEED),
            .access = READ, .changeMode = CONTINUOUS,
            .minSampleRate = 1.0f, .maxSampleRate = 10.0f}, ...}
```
- **为什么精妙**：把"设备支持什么"从探测式编程变成目录查询，换硬件 = 换表，上层零改动。
- **SDK 设计启示**：能力目录要有"分区配置"维度（同一属性多实例各有取值域），并允许框架层包装复合能力（CompositeStream 式）；配置错误要在注册期校验报错而非运行期沉默。

## 长多行锚点的行索引修复法

- **一句话**：多行字符串锚点（含缩进/引号/断行差异）插入后造成的代码粘连，用"内容定位 + 行索引"修复，禁再用长多行匹配。
- **代码实例**（AAOS13 `frameworks/av/services/audioflinger/Threads.cpp` 修复现场）：
```python
i=[k for k,l in enumerate(lines) if '自愈循环' in l][0]  # 短内容定位
lines[i+1] = '        // （-EWOULDBLOCK，…）都会再次投递本消息重试；'
```
- **为什么精妙**：长锚点的失败是静默的（引号字形、缩进差一个空格即不匹配），行索引 + 短内容断言让失败显式且定位精确。
- **SDK 设计启示**：改代码的脚本优先用"单行锚点插入 + 立即 diff 复核"；多行匹配仅用于修复，修复时断言必须覆盖所有被影响行。

## 重配置前的在途资产排空配对

- **一句话**：整机重配置前，对持有在途资产的子系统先发"强制闲置"请求、等确认，重配置完成后调用配对的"恢复"方法。
- **代码实例**（AAOS13 `frameworks/av/services/camera/libcameraservice/device3/Camera3Device.cpp`）：
```cpp
mNextRequests[0].captureRequest->mInputStream->forceToIdle();
// ... reconfigureCamera ...
mNextRequests[0].captureRequest->mInputStream->restoreConfiguredState();
```
- **为什么精妙**：重配置会废弃在途缓冲的归属，先排空再重建避免资产悬空；force/restore 成对出现让"临时状态"有明确边界。
- **SDK 设计启示**：凡重配置可能波及他方持有的资产，必须设计成对的"请求闲置/恢复"接口且带确认；不适用场景是资产可无损丢弃（此时直接作废更简单）。

## 三张清单声明式治理

- **一句话**：域服务的能力治理拆成三张静态清单——支持什么（SUPPORTED）、何时启用（CORE 门槛）、订阅什么（SUBSCRIBABLE），中枢零逻辑组合。
- **代码实例**（AAOS13 `packages/services/Car/service/src/com/android/car/hal/ClusterHalService.java`）：
```java
private static final int[] SUPPORTED_PROPERTIES = {CLUSTER_SWITCH_UI, ...};
private static final int[] CORE_PROPERTIES = {CLUSTER_SWITCH_UI, ...};      // 四件套齐才启用
private static final int[] SUBSCRIBABLE_PROPERTIES = {CLUSTER_SWITCH_UI, ...};
```
- **为什么精妙**：能力、门槛、订阅三个正交决策各自成表，读代码即读治理规则，改行为改表不改逻辑。
- **SDK 设计启示**：子系统能力声明拆成多个正交清单（能力/启用/订阅）优于单一布尔开关；适用条件是三类决策确实独立，否则清单间隐式耦合会成为坑。

## 协商式流配置接口

- **一句话**：向硬件下发流配置的接口设计成"提交配置草案、收回最终配置"，调用方以返回值刷新本地视图。
- **代码实例**（AAOS13 `frameworks/av/services/camera/libcameraservice/device3/Camera3Device.cpp` configureStreamsLocked）：
```cpp
res = mInterface->configureStreams(sessionBuffer, &config, bufferSizes);
// HAL 可回改 usage/maxBuffers 等，框架以 config 返回为准回填各流
```
- **为什么精妙**：硬件约束（对齐/缓冲数/组合限制）只有设备自己知道，协商式接口让"框架认为"自动收敛到"设备实际"。
- **SDK 设计启示**：跨硬件边界的配置接口返回值必须携带最终参数并要求调用方回填；不适用场景是配置纯软件、返回值可预测时直接断言更早暴露问题。

## 限制消费按自然重绑降级

**一句话**：能力限制（如驾驶分心限制）的消费方把"限制位集合"折算成自己的布尔缓存，变化时不清 UI 不弹窗，界面在下一次自然重绑时静默降级。

**代码实例**（packages/apps/Car `Notification/.../CarHeadsUpNotificationManager.java`）：

```java
@Override
public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
    // 只把 NO_TEXT_MESSAGE 折算成布尔缓存
    mShouldRestrictMessagePreview =
            (restrictions.getActiveRestrictions()
                    & CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE) != 0;
}
// 真正的消费在 bind 时：MESSAGE 类型且限制中 → bindRestricted 隐藏消息内容
```

**为什么精妙**：限制生效的时机与 UI 重建的时机天然不同步；若在限制回调里强行改 UI，要处理"回调时 UI 不存在/正在动画"等所有边界——推迟到自然重绑，这些边界整体消失。

**SDK 设计启示**：
- 全局约束类状态（权限/限制/模式）的消费用"缓存谓词 + 消费点检查"而不是"变更即重排 UI"；适用条件：约束只影响"下次渲染的内容"而不影响"正在进行的交互"——进行中的事务（如正在播的视频）需要即时停止时必须另加强制路径。
- 回调只做"折算"，不做"执行"：让限制回调保持薄，执行点永远在常规渲染路径上。

## 抑制凭证绑定调用进程生命周期

**一句话**：临时性系统特权（抑制蓝牙路由、独占按键）以调用方传入的 IBinder token 为凭证并 linkToDeath——进程死亡特权自动解除，无泄漏路径。

**代码实例**（packages/services/Car `CarProjectionService.java` + `bluetooth/BluetoothProfileInhibitManager.java`）：

```java
// 请求抑制 A2DP（投影期间媒体让路），token 作身份
public boolean requestBluetoothProfileInhibit(
        BluetoothDevice device, int profile, IBinder token) { ... }
// 管理器侧：token 死亡即自动解除抑制
record.getToken().linkToDeath(record, 0);
```

**为什么精妙**：特权若靠调用方主动释放，崩溃/被杀即泄漏；binder 死亡通知是内核保证的——把"释放责任"从调用方约定升级为系统机制，SDK 的承诺不再依赖使用方自觉。

**SDK 设计启示**：
- 跨进程临时授权一律要求传 IBinder 身份并 linkToDeath，把生命周期钩子内置进授权本身；适用条件：特权语义就是"持有者活着才有效"——需要"死后仍生效"的持久授权（配对、订阅）不能这样设计。
- 主动释放 API 仍要提供：主动释放走优雅路径（可恢复状态），死亡释放是兜底，两者并存而不是二选一。
