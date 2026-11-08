# Glide 图片加载库

**Q1: [learning] Glide 图片请求从创建到显示经过哪些阶段？**

Glide 4 的请求从目标尺寸和缓存查找开始，到资源交付才完成图库工作。图片是否可见还取决于后续绘制与显示：

1. **创建与绑定：**`Glide.with()` 取得与界面生命周期关联的 RequestManager，`load()` 指定来源，`into()` 绑定 Target 并启动请求。Target 提供目标尺寸，尚未布局时可能等待尺寸。
2. **查找与生成：**Engine 先查可复用资源，未命中才按策略读取来源、解码和变换。缓存命中会跳过部分工作。
3. **交付与显示：**Target 接收资源，ImageView 再参与绘制。`RequestListener.onResourceReady()` 早于 `Target.onResourceReady()`，成功回调不等于图片已出现在显示帧。

依据为 Glide 4《Targets》《Caching》和《RequestListener》。图片缓存、预取和可见时间分析见[加载链路与加载库](../13-performance/01-应用/06-图片加载/03-加载链路与加载库.md)。

**Q2: [learning] Glide.with() 的不同入口如何决定请求绑定到哪个生命周期？选错入口会有什么后果？**

每个 with() 重载都在选择“请求跟谁的生命周期走”。RequestManager 是把宿主生命周期事件翻译成请求暂停、恢复和清除动作的管理对象：传入 FragmentActivity 或 androidx Fragment 时，请求绑定该宿主的 Lifecycle，宿主停止就暂停、销毁就清除。传入 Application Context、在后台线程调用，或使用已废弃的 framework Activity 与 Fragment 重载时，请求退化为进程级的应用管理器，永不暂停也不随页面清理。选错入口不会报错，而是暂停与清理语义静默丢失，或请求被过早取消。

入口解析按传入对象的类型分为四类：

1. **with(FragmentActivity) 与 with(Fragment)：**按宿主 Lifecycle 取得或创建 RequestManager。同一宿主多次调用复用同一个管理器，保证请求集合与暂停状态一致。Fragment 入口可独立于 Activity 停止，列表页滑出屏幕即暂停。
2. **with(View)：**从 View 沿父链反查最近的 Fragment 或 Activity 宿主，反查不到时逐级退回 Activity 级或应用级。反查成本与 View 层级深度相关，自定义 View 内部应缓存结果或改传宿主。
3. **with(Context)：**运行时分发。主线程上传入 FragmentActivity 则转为 Activity 级，ContextWrapper 递归解包其 base，解不出宿主或在后台线程调用时一律应用级。
4. **with(Activity) 与 with(android.app.Fragment)：**已废弃，当前实现直接等价于 with(applicationContext)，不再绑定 framework 组件生命周期。

选错入口的后果按场景分为三种：

1. **页面用 Application Context：**切后台不暂停，销毁后资源不清理，用户离开列表页后仍在下载。
2. **通知或 Service 用 FragmentActivity：**Activity 销毁时请求被取消，通知缩略图加载失败。
3. **老代码升级后仍用 with(Activity)：**静默失去暂停与清理能力。排查内存泄漏或后台流量时，先查入口选择。

依据：本地 Glide 源码快照（Glide 4 主线）的 RequestManagerRetriever 各 get 重载与 LifecycleRequestManagerRetriever。

**Q3: [learning] Glide 的活跃资源、内存缓存与进行中任务三级查找如何协同？**

Engine.load 在同一把锁内依次查活跃资源表和内存缓存，命中就在锁外直接回调。两级都未命中时，再查是否已有相同键的任务在跑，有则把本次回调并入该任务，没有才新建解码任务。三者分别对应资源“正在使用”“闲置备用”“正在生成”三种状态，命中与状态晋升在同一步内完成，同一资源不会同时存在于两处。

按查找顺序，三级的职责与晋升规则如下：

1. **活跃资源（ActiveResources）：**以弱引用登记正在被某个 Target 显示的资源，并配合引用计数。弱引用只回答“是否已被 GC（Garbage Collection）回收”，表达不了“还有谁在用”，所以引用计数补位。后台线程 glide-active-resources 消费 ReferenceQueue，清理已被回收的表项。
2. **内存缓存（LruResourceCache）：**LRU（Least Recently Used）缓存存放没有任何 Target 持有的闲置资源。命中时资源从缓存中移除并晋升回活跃表，对应源码为 cache.remove 之后 activeResources.activate。
3. **进行中任务（EngineJob）：**查 jobs 表是否有相同 EngineKey 的任务。有则 addCallback 合并，避免重复下载与解码。无则创建 EngineJob 与 DecodeJob 并登记。
4. **释放回流：**Target 被清除或被新请求替换时引用计数减一。计数归零触发 Engine.onResourceReleased，把资源从活跃表移出，可缓存资源放回内存缓存，设置了 skipMemoryCache 的资源直接回收。

设置 skipMemoryCache(true) 会跳过前两级查找，资源释放后也不进内存缓存。各缓存层分别省掉哪些工作、命中率如何调优，见[加载链路与加载库](../13-performance/01-应用/06-图片加载/03-加载链路与加载库.md)。

依据：本地 Glide 源码快照（Glide 4 主线）的 Engine.loadFromMemory、Engine.getEngineResourceFromCache、Engine.onResourceReleased 与 ActiveResources。

**Q4: [learning] 内存缓存未命中后，DecodeJob 按什么顺序从磁盘缓存和原始来源取数据？为什么远程数据先写磁盘再解码？**

DecodeJob 把取数拆成三个按成本升序排列的生成器，前一个取不到才换下一个：变换后资源的磁盘缓存、原始数据的磁盘缓存、原始来源。远程数据取到后不直接解码，而是先写入磁盘缓存再从缓存文件读回解码，让“原始数据落盘”成为必经路径。

三个生成器按尝试顺序如下：

1. **ResourceCacheGenerator：**查磁盘中按“原始键、签名、宽高、变换、解码选项”组成的 ResourceCacheKey。命中即跳过下载、解码和变换，是最快通道。只有尺寸与变换完全相同的请求才能命中。
2. **DataCacheGenerator：**查按“原始键、签名”组成的 DataCacheKey 的原始数据。命中后仍需解码与变换，但省去下载，同一原图的不同尺寸请求都能从这里命中。
3. **SourceGenerator：**经 Registry 选出 ModelLoader 与 DataFetcher，从网络或本地读取。进入这一步前任务会从磁盘缓存线程池重新排队到源线程池：磁盘缓存线程池只有一个线程，若就地下载会饿死其他请求的缓存读取。

远程数据的写读回环分四步：

1. 策略允许缓存原始数据时，SourceGenerator 先把数据写入 DiskLruCache。
2. 写入后回读验证，确认文件存在。
3. 新建一个 DataCacheGenerator 从缓存文件读取并解码，解码入口因此收敛为 File 一种。
4. 写入失败或策略不允许缓存时，直接用内存中的数据解码。

先落盘的收益是弱网下载中断后重试不重复下载，且后续不同尺寸的请求可直接命中原始数据缓存。默认策略 DiskCacheStrategy.AUTOMATIC 的规则是：远程数据（DataSource.REMOTE）只写原始数据，本地数据只写变换后的资源，两种缓存都允许读取。各策略如何选择，见[加载链路与加载库](../13-performance/01-应用/06-图片加载/03-加载链路与加载库.md)。

依据：本地 Glide 源码快照（Glide 4 主线）的 DecodeJob、ResourceCacheGenerator、DataCacheGenerator、SourceGenerator 与 DiskCacheStrategy。

**Q5: [learning] 对同一个 ImageView 重复调用 into() 时，Glide 怎样决定复用旧请求还是替换？**

into() 会取出 View tag 中保存的旧请求，与按当前配置新建的请求比较是否等价（isEquivalentTo）。等价且不属于“skipMemoryCache 且旧请求已完成”的特例时直接复用旧请求，否则按“清旧、挂新、跟踪”三步替换。列表复用场景下重复 into() 是常态，这个判断保证既不重复下载也不串图。

复用路径按旧请求状态分三种处理：

1. **运行中：**不做任何操作，等待其完成。
2. **已结束或已失败：**调用 begin() 重新开始。
3. **已完成：**begin() 直接重投上次结果。

复用省去重设占位图、重新测量尺寸和 track 与 untrack 的开销。特例守卫是：options 设置了 skipMemoryCache(true) 而旧请求已完成时，复用会直接重投上次结果，使“跳过内存缓存”失效，因此强制换新请求。

替换路径的三步顺序不能调换：

1. **requestManager.clear(target)：**释放旧资源并取消旧加载，保证任何时刻 View 上只有一个有效请求。
2. **target.setRequest(request)：**把新请求写入 View tag，供之后复用与取消时取回。
3. **requestManager.track(target, request)：**交给生命周期调度，未暂停时立即 begin()。setRequest 必须在 track 之前，因为 begin() 依赖 tag 中的请求引用。

每次 into(ImageView) 都会新建 Target。Target 只是 View 的轻量包装，复用旧实例反而要处理状态残留。View tag 里存的是 Request 而不是 Target。

两条易错边界：

1. 在 RequestListener 或 Target 回调里直接调用 into() 或 clear() 会抛 IllegalStateException。需要级联加载时改用 error(RequestBuilder)，或 post 回主线程再调用。
2. View 尺寸变化后想重新加载必须先 clear()，否则已完成的旧请求只会重投旧结果。

依据：本地 Glide 源码快照（Glide 4 主线）的 RequestBuilder.into 与 SingleRequest.begin。

**Q6: [learning] asBitmap()、asDrawable() 等 as() 方法确定的 TranscodeType 如何影响 Target 类型与转码路径？**

TranscodeType 是最终交给 Target 的资源类型，由 RequestManager.as(Class) 在构建 RequestBuilder 时写死为 transcodeClass。它决定 into(ImageView) 生成哪种 Target、解码产物经过哪个 Transcoder，以及默认过渡动画。它不是网络原始数据的类型，也不是解码中间产物的类型。

transcodeClass 影响三处分发：

1. **Target 分发：**ImageViewTargetFactory 对 Bitmap.class 用精确相等判断，生成 BitmapImageViewTarget，最终调用 setImageBitmap。对 Drawable 及其子类用 isAssignableFrom 放行，生成 DrawableImageViewTarget，最终调用 setImageDrawable。其他类型抛 IllegalArgumentException。
2. **转码器选择：**DecodePath 解码出 Bitmap 后按 transcodeClass 从 Registry 选 Transcoder。默认 Drawable 路径经 BitmapDrawableTranscoder 包装为 BitmapDrawable，asBitmap() 不需要转码，asFile() 只把原始数据下载到磁盘缓存。
3. **动画默认值：**RequestManager.getDefaultTransitionOptions(transcodeClass) 按类型返回默认过渡配置。

两条使用边界：

1. as() 之后 RequestBuilder 的类型已固定，不能再通过 asBitmap() 改类型。要改类型需重新从 RequestManager 开始。
2. 默认 load().into(imageView) 的 TranscodeType 是 Drawable，GIF 资源以 GifDrawable 交付并可播放。asBitmap() 会把动图解码为单帧静态图。

依据：本地 Glide 源码快照（Glide 4 主线）的 RequestManager.as、ImageViewTargetFactory.buildTarget 与 DecodePath。

**Q7: [learning] thumbnail() 与 error() 如何与主请求组成请求树？缩略图与备用请求各在什么时机生效？**

RequestBuilder 不一定只生成一个 SingleRequest：配置了 thumbnail() 时，主请求与缩略图请求由 ThumbnailRequestCoordinator 并发驱动。配置了 error(RequestBuilder) 时，ErrorRequestCoordinator 在主请求失败后启动备用请求。两类协调器本身也实现 Request 接口，对 RequestTracker 和 Target 而言，整棵树只是“一个请求”，调度代码不需要特判。

缩略图分支的规则有三条：

1. **并发与优先级：**主请求与缩略图同时开始。未给缩略图单独设置优先级时，缩略图取比主请求高一档的优先级（LOW 升为 NORMAL，NORMAL 升为 HIGH，HIGH 与 IMMEDIATE 均为 IMMEDIATE），让小图先出。
2. **迟到不覆盖：**协调器的 canSetImage 只在主请求尚未成功时允许缩略图设图。主请求先完成则缩略图结果被丢弃，避免画质倒退。
3. **清理时机：**主请求成功后只在缩略图尚未完成时才清理它。已显示的缩略图可能正作为交叉淡入的图层，清理会闪断显示。

备用请求分支的规则有两条：

1. **启动时机：**主请求失败时 ErrorRequestCoordinator 调用备用请求的 begin()。备用请求由独立的 RequestBuilder 构建，不自动继承主请求的 thumbnail，优先级与过渡动画等选项要单独配置。
2. **监听器边界：**主请求上的 RequestListener 不会自动通知缩略图请求，备用请求有自己的成功与失败回调。

RequestBuilder 用 isThumbnailBuilt 标志防止同一个 builder 同时充当主请求与缩略图造成无限递归。

依据：本地 Glide 源码快照（Glide 4 主线）的 RequestBuilder.buildThumbnailRequestRecursive、RequestBuilder.getThumbnailPriority、ThumbnailRequestCoordinator 与 ErrorRequestCoordinator。

**Q8: [learning] placeholder()、error() 与 fallback() 三种占位图分别在什么时机显示？RequestListener 返回 true 会改变什么？**

三者对应三种互斥场景：placeholder 在加载进行中显示，error 在加载失败后显示，fallback 只在 load() 传入的 model 为 null 时优先显示。失败路径按“model 为空先取 fallback，否则取 error，都没有再退回 placeholder”取第一张非空图。RequestListener 的 onResourceReady 或 onLoadFailed 返回 true 表示接管显示，Glide 不再设置图片、占位图和动画。

按请求状态，占位图的显示时机如下：

1. **开始：**SingleRequest.begin() 在等待尺寸或启动引擎前调用 target.onLoadStarted(placeholder)。
2. **失败回退：**setErrorPlaceholder 按 fallback（仅 model 为 null）、error、placeholder 的顺序取第一张非空 Drawable，再调用 target.onLoadFailed。
3. **监听器接管：**任一 listener 返回 true 则跳过 target 的设图与占位图，返回 false 走默认展示。

load(null) 是合法调用，它走失败路径并优先显示 fallback，用于“业务上预期可能没有图片”的场景。失败不是终态：页面恢复（resumeRequests）、网络恢复（restartRequests）和列表重绑定都会对失败请求重新 begin()，因此失败请求不要持有需要手动清理的资源引用。

依据：本地 Glide 源码快照（Glide 4 主线）的 SingleRequest.begin、SingleRequest.setErrorPlaceholder 与 SingleRequest.onLoadFailed。

**Q9: [learning] AppGlideModule、注解处理器与 GlideApp 如何参与 Glide 初始化？不写任何模块会怎样？**

Glide.get() 首次调用时通过 Class.forName 加载编译期生成的 com.bumptech.glide.GeneratedAppGlideModuleImpl，由它汇总应用的 AppGlideModule 与各库的 LibraryGlideModule：先回调 applyOptions 收集全局配置，再在 GlideBuilder.build() 内回调 registerComponents 注册组件。不写任何模块时 Glide 用全部默认值正常工作，包括 LRU 内存缓存、内部存储磁盘缓存、HttpURLConnection 网络栈和按设备内存计算的池大小。

按初始化顺序，各参与者的职责如下：

1. **发现：**找不到 GeneratedAppGlideModuleImpl 只打印 WARN 日志并降级为默认配置。接入 annotationProcessor（Kotlin 项目用 kapt，Glide 4.14 起也提供 ksp 处理器）后由注解处理器生成该类，固定类名避免扫描 Manifest 或 ClassPath。
2. **AppGlideModule：**一个应用最多一个。applyOptions 可改缓存大小、默认 RequestOptions 和日志级别。registerComponents 可对 Registry 做 append、prepend 或 replace，例如 replace(GlideUrl.class, InputStream.class, OkHttpUrlLoader.Factory) 把网络栈换成 OkHttp。
3. **LibraryGlideModule：**供第三方库注册组件，可以有多个，不能修改应用级选项。调用顺序为库模块先、应用模块后，应用配置优先级最高。
4. **Registry 装配：**首次 getRegistry() 时惰性构建，先装配全部内置组件再回调模块。顺序不能反，否则模块的 replace 没有目标、prepend 没有锚点。
5. **GlideApp：**注解处理器生成的入口类，可用 @GlideModule(glideName) 改名。它返回 RequestManager 的子类 GlideRequests，支持编译期生成的选项链式调用。普通 Glide.with() 不经过生成 API。

Glide 3 时代的 Manifest 模块用 meta-data 声明，由 ManifestParser 运行时反射解析，仍会被调用。迁移期可在 AppGlideModule 上用 @Excludes 排除冲突的旧模块，或覆写 isManifestParsingEnabled() 返回 false 关闭解析。

依据：本地 Glide 源码快照（Glide 4 主线）的 Glide.get、Glide.initializeGlide、RegistryFactory 与 GeneratedAppGlideModule。

**Q10: [learning] 网络断开又恢复后，Glide 如何自动重试失败请求？该功能依赖什么条件？**

RequestManager 构造时经 ConnectivityMonitorFactory 创建网络监听，监听随宿主 onStart 注册、onStop 注销。网络从断开变为连通时调用 requestTracker.restartRequests()，对所有未完成且未清除的请求执行 clear 后 begin，暂停中的请求则放入待处理集合等页面恢复。前提是应用声明了 ACCESS_NETWORK_STATE 权限，否则工厂返回空实现 NullConnectivityMonitor，功能静默缺失。

按实现层次，机制有四点：

1. **监听实现：**SingletonConnectivityReceiver 是进程内单例，被所有页面共享。首个注册时才向系统注册，最后一个注销时解除。API 24 及以上用 registerDefaultNetworkCallback，低版本用 CONNECTIVITY_ACTION 广播。
2. **去重：**只在连通状态真实变化时回调，避免广播风暴反复重启请求。断开本身不触发任何动作，请求自然失败。
3. **重试范围：**restartRequests 只处理 isComplete() 与 isCleared() 都为 false 的请求，已完成的不动。重试从缓存查找开始。
4. **降级：**无权限时不崩溃也不生效。排查“恢复网络后不重试”先查 Manifest 是否声明该权限。

依据：本地 Glide 源码快照（Glide 4 主线）的 DefaultConnectivityMonitorFactory、SingletonConnectivityReceiver、RequestManager 与 RequestTracker.restartRequests。

**Q11: [learning] Glide 的内存缓存与 Bitmap 池默认预算如何按设备计算？**

GlideBuilder 构造时由 MemorySizeCalculator 按“屏数”计量：一屏等于屏幕像素数乘以 4 字节（ARGB_8888），内存缓存目标 2 屏，Bitmap 池目标 4 屏，Android 8.0 及以上降为 1 屏。两者之和不得超过 memoryClass 的 0.4 倍，低内存设备为 0.33 倍，超出时按屏数占比等比缩水。数组池固定 4 MB，低内存设备减半。

按预算规则，四个要点如下：

1. **按屏数计量：**“几屏图”与真实使用强度对齐，低端机自动缩水、平板自动放大，无需按机型调参。
2. **Android 8.0 降档原因：**从 8.0 起多数合理尺寸的图片用 Bitmap.Config.HARDWARE 解码，像素不走 Java 堆，Bitmap 池的价值大减。
3. **总预算封顶：**memoryClass 取自 ActivityManager.getMemoryClass()，isLowRamDevice() 为 true 时乘数降为 0.33。
4. **运行时缩放：**Glide.setMemoryCategory 按 MemoryCategory 的倍率整体缩放内存缓存与 Bitmap 池，ZERO、LOW、NORMAL、HIGH 分别为 0、0.5、1、1.5 倍，不影响数组池。该档位是进程级的，一个页面抬档会影响所有页面，临时抬档应用返回值恢复。

需要覆盖默认值时，在 AppGlideModule.applyOptions 中调用 setMemorySizeCalculator，或直接 setMemoryCache 与 setBitmapPool。

依据：本地 Glide 源码快照（Glide 4 主线）的 MemorySizeCalculator、GlideBuilder 与 MemoryCategory。
