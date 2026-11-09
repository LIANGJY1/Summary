# Retrofit

> Retrofit（3.0 系源码锚点，AndroidLibs/retrofit 工作区 HEAD f38a5e5）机制学习资料。主线：动态代理主链与解析期/运行期分离、注解到 Request 的构造、suspend 复用 CallAdapter 体系、回调线程装配、OkHttpCall 状态机与工厂链注册顺序。依赖它发请求的 OkHttp 细节与网络策略归 [../08-network/](../08-network/)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] retrofit.create() 返回的对象是怎么把接口方法调用翻译成 HTTP 请求的？为什么设计成“一次解析、终身复用”？**

create() 用 JDK 动态代理生成接口实现：代理拦截方法调用后，从该方法签名一次性解析出发请求所需的全部产物——请求怎么拼（RequestFactory）、返回值适配成什么形态（CallAdapter）、响应体转成什么类型（Converter）——缓存进 ConcurrentHashMap 后，每次调用只付“拼参数、建 Call”的成本。

主链按执行顺序：

1. **代理入口**：create() 先校验目标必须是接口且不带泛型参数，再 Proxy.newProxyInstance 生成代理。代理 invoke 三分流：Object 方法原样转发、接口 default 方法走平台反射调用、其余方法查缓存后执行 ServiceMethod。
2. **解析期**：ServiceMethod.parseAnnotations 先建 RequestFactory（解决“请求怎么拼”），再由 HttpServiceMethod.parseAnnotations 选 CallAdapter、按其 responseType() 反推响应体类型并选 Converter。解析产物字段全部 final，不可变装配天然线程安全。
3. **运行期**：invoke() 创建惰性的 OkHttpCall（execute/enqueue 才真正发网络）并交给 callAdapter.adapt()，得到 Call/Observable/suspend 等调用形态。

分离的原因是成本结构：反射解析与全量交叉校验昂贵，按“方法”粒度做一次即可；而运行期热路径只剩参数校验与拼装，解析结果不可变化又让缓存读无需加锁。由此推出使用纪律：接口方法数不影响单次请求开销，但运行期反射失败是确定性错误（创建失败会被缓存并重复抛出），重试调用只会再失败一次。

依据：AndroidLibs/retrofit（3.0 系，HEAD f38a5e5）的 Retrofit.create、ServiceMethod、HttpServiceMethod 与沉淀文档 §1。

**Q2: [learning] 方法注解和参数注解是怎么一步步变成一个合法的 okhttp3.Request 的？**

解析期把注解翻译成一排 ParameterHandler（每参数一个处理器）并完成全部交叉校验，运行期把实参逐个喂给处理器写入 RequestBuilder，最后收口出 Request。“会不会拼出非法请求”的判断全部前移到解析期，热路径只剩拼装。

解析期三个阶段：

1. **方法级注解**：确定 HTTP 方法与相对 URL、静态 @Headers。@Multipart 与 @FormUrlEncoded 互斥。
2. **逐参数解析**：每种注解产出对应 handler，支持单值、Iterable、数组三形态（后两者是单值 handler 的包装）。一个参数只允许一个 Retrofit 注解。
3. **交叉校验**：有 @Url 或方法注解带 URL、无 body 的方法禁 @Body、表单必须有 @Field、multipart 必须有 @Part。

运行期与两条配套语义：

1. **URL 两态**：RequestBuilder 起始为字符串态（支持 @Path 替换），第一个查询参数出现时一次性切到 builder 态并永久失效字符串替换——解析期强制 @Path/@Url 排在 @Query 之前，正是这条状态机在解析期的投影。
2. **null 两档**：单值参数为 null 静默跳过（可选参数语义）；Map 系、@Path、@Url、@Body 为 null 直接抛错——无法决定“跳过还是保留空”时宁可显式失败。

安全上，@Path 值先按白名单百分号编码，替换后再用正则拒绝 "." 与 ".."（含 %2e）路径穿越：编码解决合法性，正则解决语义性，即便 encoded=true 也不放行穿越。运行期还会把 Invocation 挂到请求 tag 上，拦截器可反查“哪个方法、什么参数”。

依据：AndroidLibs/retrofit 的 RequestFactory、ParameterHandler、RequestBuilder 与沉淀文档 §2、§5。

**Q3: [learning] suspend 函数没有独立的请求体系吗？它怎么复用 CallAdapter 与回调线程装配？**

没有独立体系。Retrofit 把 suspend 函数翻译成 `Call<T>` 伪类型以复用同一套 CallAdapter 工厂链，并强制注入 @SkipCallbackExecutor 让协程接管调度——新增一种调用形态只需在 ServiceMethod 层加一个内部子类，解析骨架不变。

识别与翻译规则：

1. **识别**：解析时检查最后一个参数是否为 Continuation，是则视为 suspend，并从 Continuation 的泛型参数剥出真正的响应类型。
2. **三种形态**：按返回值实例化三个内部子类之一——普通 suspend 返回裸 body（SuspendForBody，区分 Unit、可空、非空三种 await 语义）、返回 Response\<T\>（SuspendForResponse）、非 suspend（CallAdapted）。
3. **线程**：suspend 被强制注入 @SkipCallbackExecutor，因为协程有自己的调度器，不需要回调切主线程；普通方法的回调默认切 Android 主线程（见回调线程装配），切线程发生在 ExecutorCallbackCall 包装层，OkHttpCall.enqueue 本身的回调仍在 OkHttp 工作线程。
4. **挂起投递**：SuspendForBody 的 try/catch 加 suspendAndThrow 不是冗余——OkHttp enqueue 可能在当前栈帧返回前就异常回调，而 Java 代理无法抛出未声明的受检异常（会被包成 UndeclaredThrowableException），必须强制挂起后再投递给续体。

易错点：suspend 方法不能再返回 Call（异步套异步，解析期直接报错）。continuationBodyNullable 在当前实现恒为 false（源码注明尚无法从字节码可靠判断可空性），可空 suspend 函数实际仍走非空分支的 await 语义。

依据：AndroidLibs/retrofit 的 HttpServiceMethod、KotlinExtensions 与沉淀文档 §1、§7。

**Q4: [learning] 异步回调默认切回主线程是谁决定的？哪些环节可以改变这个行为？**

默认行为由 Platform 在静态初始化时按运行环境决定：Android 上 callbackExecutor 是 Handler(Looper.getMainLooper()) 的主线程执行器，JVM 等其他平台为 null（回调留在 OkHttp 线程）。切线程的动作发生在 DefaultCallAdapterFactory 的 ExecutorCallbackCall 装饰器层，而不是执行层写死。

可改变行为的环节：

1. **按方法跳过**：@SkipCallbackExecutor 让单个方法退出切线程，回调留在 OkHttp 线程；suspend 函数被框架自动注入该标记。
2. **按调用形态**：只有返回 Call\<T\> 的方法走 DefaultCallAdapterFactory 的包装；返回 Observable、CompletableFuture 等形态时由对应 CallAdapter 决定回调线程（如 RxJava 默认在 OkHttp 线程发射）。
3. **取消语义**：已取消的调用，ExecutorCallbackCall 会模拟 OkHttp 行为向回调投递 IOException("Canceled")，而不是静默不回调。

排查回调线程问题时先问两个问题：该方法最终由哪个 CallAdapter 适配、是否带 @SkipCallbackExecutor——两问的答案完全决定回调线程，不需要读框架源码逐层验证。

依据：AndroidLibs/retrofit 的 Platform、DefaultCallAdapterFactory、ExecutorCallbackCall 与沉淀文档 §7。

**Q5: [learning] 一个 Call 只能执行一次是怎么回事？非 2xx 响应为什么 onResponse 里收不到异常？**

OkHttpCall 用 executed 标志保证单次执行：execute 与 enqueue 先抢占该标志，重复执行抛 IllegalStateException；需要重试或轮询时用 clone()——它创建全新实例、不复制任何状态。非 2xx 不抛异常是设计：parseResponse 把错误体读进内存后以 Response.error 返回，调用方必须在 onResponse 里先 isSuccessful() 分流。

状态机与转换分支：

1. **创建与失败记忆**：rawCall 在锁内创建、锁外发网络（避免持锁做 IO）。创建失败按原类型缓存进 creationFailure 并重复抛出——反射失败是确定性的，重试只会再失败。
2. **parseResponse 三分支**：

    1. **非 2xx**：走 Utils.buffer 把错误体整包读进内存（finally 关闭原始流，连接可复用），返回 Response.error。
    2. **204/205**：关闭流返回 success(null)。
    3. **2xx**：用 ExceptionCatchingRequestBody 包装后交 Converter，若转换器抛的 RuntimeException 实体是底层 IOException，throwIfCaught 还原根因。
3. **body 占位**：解析前原始响应体被换成 NoContentResponseBody（只留元信息、读 source 即抛异常）——source 是唯一有状态对象，换掉后 Response 才能安全传递、防止二次读取。
4. **取消**：cancel() 置 volatile 标志并取消已创建的 rawCall；enqueue/execute 发出前补查该标志。

与适配器形态的交界：非 2xx 的 HttpException 只在 Call 被适配成 CompletableFuture/RxJava 等形态时由适配器抛出。@Streaming 声明后响应体不再预缓冲，必须及时读完并关闭，否则连接无法复用。

依据：AndroidLibs/retrofit 的 OkHttpCall 与沉淀文档 §6。

**Q6: [learning] Converter 与 CallAdapter 两条工厂链的顺序为什么重要？混用 Gson 与 Protobuf 转换器时要注意什么？**

工厂链按注册顺序逐个尝试，先命中先得：converterFactories 的顺序是“内置工厂在最前 + 用户工厂居中 + 平台默认在最后”，callAdapterFactories 则是“用户工厂在前、平台默认兜底在后”。顺序错配的直接后果是通吃型工厂抢走专用类型的解析，报错却指向看似无关的注解。

两条链的设计约束：

1. **内置工厂占 [0] 位**：BuiltInConverters 排最前，防止用户注册“吞掉所有类型”的工厂后覆盖框架必需的内置行为（如responseType 校验相关的内置转换）。
2. **两种姿态**：通吃型（gson、moshi、jackson、kotlinx-serialization）对几乎所有类型返回 converter、绝不返回 null；专精型（protobuf、wire、scalars）只精确匹配自己的类型，其余返回 null 让链继续。
3. **注册规则**：混用时通吃型必须最后注册，否则 protobuf Message 等专用类型被 JSON 工厂截胡；专精型放前面永远安全。查找失败时框架抛出带 Skipped/Tried 全清单的异常——这是“错误信息即 API”的设计，注册问题通常不用断点就能定位。

扩展方式与代价：自定义扩展继承 Converter.Factory 或 CallAdapter.Factory，覆写需要的 get 方法（默认返回 null 表示不处理），在 Builder 注册即可。SDK 设计的通用启示是：按序尝试的插件链里，每个实现要么声明“我只处理 X”，要么自认“兜底”，兜底者必须在链尾。

依据：AndroidLibs/retrofit 的 Retrofit.Builder、BuiltInFactories、converters 各模块与沉淀文档 §8、§10。
