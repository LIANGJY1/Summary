# retrofit 桥接模块：adapters / converters / mock 类职责

> 锚点同 ARCHITECTURE.md。桥接类结构高度同构，以"家族行"覆盖（一行一个结构模式），
> 例外类单列——覆盖率实数见文末。

## adapters（37 类）

| 家族/类 | 一行职责 | 关键协作 |
|---|---|---|
| rxjava3/rxjava2 的 XxxCallAdapterFactory | 剥泛型定三形态（body/Response/Result），Completable 无泛型特判 | CallAdapter.Factory |
| rxjava3/rxjava2 的 XxxCallAdapter | adapt 四段装配线（执行→语义→调度→收敛） | 下述 Observable 族 |
| CallExecuteObservable / CallExecuteOnSubscribe | 订阅线程阻塞 execute（Disposable 桥接 cancel） | originalCall.clone() |
| CallEnqueueObservable / CallEnqueueOnSubscribe / CallCallback / CallArbiter(rx1) | 订阅时 clone+enqueue；Disposable 兼任 Callback；dispose→cancel 静默 | OkHttp 回调线程 |
| BodyObservable/BodyObserver/BodyOnSubscribe | 2xx 发 body、非 2xx 转 HttpException 进 onError | 状态码分流在此层 |
| ResultObservable/ResultObserver/ResultOnSubscribe | 全部结局（含网络错误）数据化为 onNext(Result) | 错误双通道 |
| Result | response/error 互斥不可变载体 | 私有构造+静态工厂 |
| HttpException（三份模块副本） | 各生态自己的非 2xx 异常 | Response |
| guava: GuavaCallAdapterFactory + CallCancelListenableFuture | 适配 ListenableFuture；AbstractFuture.interruptTask 联动取消 | future.set/setException |
| java8: Java8CallAdapterFactory | 适配 CompletableFuture（已 @Deprecated：core 已内置） | 匿名 Future |
| scala: ScalaCallAdapterFactory + Body/ResponseCallAdapter | Promise 桥接；回调线程随 ExecutionContext | promise.complete |
| rxjava1: RxJavaCallAdapter + 上述 OnSubscribe 族 | 同构适配（仅 Observable/Single/Completable；create 默认同步） | CallArbiter 状态机 |

## converters（40 类，12 模块）

| 家族/类 | 一行职责 | 关键协作 |
|---|---|---|
| 各模块 XxxConverterFactory | Factory 实现：通吃型（gson/moshi/jackson/kotlinx）不返回 null；专精型（protobuf/wire/scalars/simplexml/jaxb/jaxb3）精确匹配 | Converter.Factory |
| 各模块 XxxRequestBodyConverter | 对象→RequestBody（JSON/protobuf 二进制/XML/text） | 序列化库 API |
| 各模块 XxxResponseBodyConverter | ResponseBody→对象；gson 版带 END_DOCUMENT 完整性防线 | 反序列化 API |
| 各模块 XxxStreamingRequestBody | 发送时才在 IO 线程序列化（省内存开关 withStreaming） | writeTo(sink) |
| gson: GsonConverterFactory(+lenient 等变体) | TypeAdapter 缓存于 converter 实例（线程安全复用） | Gson.getAdapter |
| moshi: MoshiConverterFactory(+JsonQualifier 收集) | 注解限定符作为 adapter 查找过滤 | Moshi.adapter |
| scalars: ScalarsConverterFactory + ScalarResponseBodyConverters + ScalarRequestBodyConverter | String+8 基本类型 ↔ text/plain | String.valueOf/parse |
| protobuf/wire: +ProtoStreamingRequestBody 等 | 只认 MessageLite/WireMessage 子类；反射取 Parser | MessageLite.Parser |
| kotlinx-serialization(4 个 .kt)：Factory/Serializer/两 StrategyConverter | 显式序列化器入参（非反射） | kotlinx.serialization |
| jaxb/jaxb3 | JAXB Context 池化（创建昂贵） | JAXBContext |
| guava/java8 converter: +OptionalConverter | delegate 外包 Optional.of/nullOf | Call<Optional<T>> |

## mock（7 类，1345 行）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| MockRetrofit(+Builder) | 包住真 Retrofit，create 出 BehaviorDelegate | 复用其 CallAdapter 体系 |
| BehaviorDelegate(+DelegateFactory 等内部类) | 再织一层动态代理：劫持方法调用→returning() 的 mock Call | JDK Proxy、CallAdapter |
| BehaviorCall | 把 mock 响应按 NetworkBehavior 噪声（延迟/失败率）投递 | 真实 Call 语义复刻 |
| NetworkBehavior(+Builder) | 可编程网络噪声模型（延迟分布/失败率/错误种类） | BehaviorCall |
| Calls | 静态工厂：response/error/deferred 造各种 mock Call | CompletableFuture 工具 |
| MockRetrofitIOException | 行为失败的可识别异常类型 | NetworkBehavior |
| package-info | 用法文档 | — |

## 覆盖率

主源码公共类 129：核心 45（core.md 全列）+ adapters 37 + converters 40 + mock 7，本文以 25 个家族行覆盖 84 类（同构家族成员列名于行内），单列例外 12 个；跳过：package-info ×20、samples 13 例（官方用法示范，本身即文档）、bom（无代码）、keeper 为 Kotlin 构建期处理器（1 类，见 ARCHITECTURE.md §5 卡片）。家族行未展开的纯同构变体（如 jaxb 与 jaxb3 全同构）在职责列已注明。
