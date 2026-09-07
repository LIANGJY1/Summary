# retrofit 核心模块：类职责与深卡片

> 锚点同 ARCHITECTURE.md（commit d66a696）。锚点为 类名#方法名（2026-09-06 迁移，行号已去除）。

## 核心类深卡片

**HttpServiceMethod**（`HttpServiceMethod.parseAnnotations`）
- 职责：解析期终点——为方法选定 CallAdapter/Converter 并以三个 final 字段封装"装配产物"；运行期 `invoke` 只做 new OkHttpCall + adapt。
- 协作：上游 ServiceMethod.parseAnnotations；下游 OkHttpCall（执行）、CallAdapter（返回形态）。
- 设计动机：模板方法 + 三子类（CallAdapted/SuspendForResponse/SuspendForBody）——"返回值如何适配"这一变化点收敛在 adapt 一处；suspend 强制注入 @SkipCallbackExecutor 是因为协程有自己的调度器（SkipCallbackExecutorImpl.ensurePresent）。
- 不变量：字段全 final，invoke 可并发；callAdapter.responseType() 必须 ≠ okhttp3.Response 且 HEAD 只配 Void/Unit（:89-190 校验链）。
- 雷区：SuspendForBody 的 try/catch + suspendAndThrow 不是防御性废话——enqueue 可能先于栈帧返回回调，Java 代理无法抛未声明受检异常，必须强制挂起后经续体投递。

**RequestFactory**（`RequestFactory.parseAnnotations/create`）
- 职责：注解→ParameterHandler[] 的解析期产物（不可变）；运行期 create 把实参逐个 apply 成 okhttp3.Request。
- 协作：HttpServiceMethod（上游）、ParameterHandler（策略族）、RequestBuilder（累积器）、Invocation（tag 观测钩子，create 尾部挂载）。
- 设计动机：解析期/运行期分离的极致——所有注解交叉校验（gotField/gotBody 状态位、@Path 占位符存在性、参数顺序）一次性做完，热路径只剩参数计数校验。参数顺序约束（@Path/@Url 先于 @Query）与 RequestBuilder 的 URL 两态字符串（relativeUrl↔urlBuilder）是同一事实在两个时期的投影。
- 雷区：@Path 值被 PATH_TRAVERSAL 正则拒绝 "."/".."（含 %2e）；query 里写 {name} 解析期即报错。

**OkHttpCall**（`retrofit2/OkHttpCall.enqueue/execute/parseResponse`）
- 职责：Call<T> 的默认实现；懒建 rawCall、单次执行、取消传播、响应三分流。
- 协作：RequestFactory.create（拼请求）、callFactory（真网络）、responseConverter（反序列化）、DefaultCallAdapterFactory.ExecutorCallbackCall（线程装饰）。
- 设计动机：确定性失败永久记忆（creationFailure 缓存重抛）+ 致命错误放行（throwIfFatal）；锁内置 call 锁外 IO。
- 不变量：canceled 为 volatile；rawCall/creationFailure/executed 全部 @GuardedBy(this)。
- 雷区：非 2xx 不抛异常（走 Response.error）；NoContentResponseBody 占位防二次读取 source。

**Platform**（`retrofit2/Platform 静态块`）
- 职责：类加载时按 java.vm.name 一次性选定 callbackExecutor/Reflection/BuiltInFactories 三份平台配置。
- 动机：Android 不支持 MR-JAR，无法用 multi-release 方案，于是"静态块探测 + 子类分叉"（Reflection.Java8/Android24、BuiltInFactories.Java8）。

## 全类职责表（45 类，公共类全覆盖，覆盖率 100%）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Retrofit | 配置容器+代理工厂；唯一用户入口 | Builder、loadServiceMethod |
| Retrofit.Builder | 链式配置+默认装配（内置工厂排序在此定） | BuiltInFactories、Platform |
| ServiceMethod | 方法解析骨架（薄壳工厂） | RequestFactory、HttpServiceMethod |
| HttpServiceMethod | 装配产物+invoke 骨架 | OkHttpCall、CallAdapter/Converter |
| HttpServiceMethod.CallAdapted | 普通调用适配（交 CallAdapter） | CallAdapter |
| HttpServiceMethod.SuspendForResponse | suspend 返回 Response<T> 的续体适配 | KotlinExtensions.awaitResponse |
| HttpServiceMethod.SuspendForBody | suspend 返回裸 body 的续体适配（Unit/可空/非空） | KotlinExtensions.await* |
| RequestFactory | 注解解析产物+create 拼装入口 | ParameterHandler、RequestBuilder、Invocation |
| RequestFactory.Builder | 三阶段解析+交叉校验（一次性） | parseParameterAnnotation |
| ParameterHandler | 参数→请求组件的策略接口（apply） | RequestBuilder |
| ParameterHandler.RelativeUrl/Path/Query/QueryName/Header/Field/Part/Body/Tag/Headers | 单注解语义的十个处理器 | 各自的 addXxx |
| ParameterHandler.QueryMap/HeaderMap/FieldMap/PartMap | Map 批量变体（严格 null 校验） | 同上 |
| ParameterHandler.RawPart | MultipartBody.Part 透传（无状态单例） | multipartBuilder |
| RequestBuilder | 单次请求累积器（URL 两态+body 三优先级） | okhttp3 各 Builder |
| RequestBuilder.ContentTypeOverridingRequestBody | 覆盖 body Content-Type 的装饰器 | delegate body |
| OkHttpCall | Call 默认实现（懒建/单次/取消/三分流） | OkHttp、responseConverter |
| OkHttpCall.NoContentResponseBody | body 占位（防 source 二次读取） | parseResponse |
| OkHttpCall.ExceptionCatchingResponseBody | 捕获底层 IO 异常还原根因 | ForwardingSource |
| Call | 一次请求的抽象（同步/异步/取消/clone） | 实现：OkHttpCall |
| Callback | 异步结果二选一回调 | onResponse/onFailure |
| Response | HTTP 结果封装（success/error 二选一不可变） | okhttp3.Response |
| HttpException | 非 2xx 的异常形态（供 adapter 抛） | Response |
| Invocation | 方法+实参的观测快照（Request tag） | 拦截器 |
| Converter | F→T 转换接口 + Factory 扩展点 | 三类 nextXxx 查找 |
| CallAdapter | Call→T 适配接口 + Factory 扩展点 | nextCallAdapter 查找 |
| BuiltInConverters | 内置转换工厂（流式/缓冲/Void/Unit/ToString/ErrorCode） | @Streaming 探测 |
| BuiltInFactories(+Java8) | 平台默认工厂清单组装 | Platform、CompletableFuture/Optional 工厂 |
| DefaultCallAdapterFactory(+ExecutorCallbackCall) | Call 返回类型兜底 + 回调切线程装饰 | callbackExecutor、@SkipCallbackExecutor |
| CompletableFutureCallAdapterFactory(+Body/ResponseCallAdapter) | CompletableFuture 适配（Java8 平台默认） | CallCancelFuture |
| OptionalConverterFactory(+OptionalConverter) | Optional 包装适配（Java8 平台默认） | delegate converter |
| Platform | 平台探测与三份静态配置 | Dalvik/RoboVM/JVM 分叉 |
| Reflection(+Java8/Android24) | default 方法识别/调用/参数描述的平台契约 | MethodHandle |
| DefaultMethodSupport(+java14/java16 变体) | 可信 Lookup 调用 default 方法（MR-JAR） | Lookup.unreflectSpecial |
| SkipCallbackExecutor / SkipCallbackExecutorImpl | 方法级"回调不切线程"标记+运行期合成实例 | HttpServiceMethod、DefaultCallAdapterFactory |
| AndroidMainExecutor | 主线程 Handler 适配器 | Looper.getMainLooper |
| Utils(+三种 TypeImpl) | 泛型反射/错误构造/杂项 | 全模块 |
| Invocation | 见上 | — |

跳过清单：package-info.java ×2（包文档）、`internal/EverythingIsNonNull`（元注解标记）。
