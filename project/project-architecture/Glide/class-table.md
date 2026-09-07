# Glide 全类职责表（公共类全覆盖）

> 锚点 commit `f38a5e5`｜归属主文档 [ARCHITECTURE.md](./ARCHITECTURE.md) §7。
> 口径：library 的 255 个 public 顶层类型逐个入表（包私有核心类 EngineJob/DecodeJob/三 Generator/ActiveResources 等已进 modules-engine.md 深卡片，不入本表重复）；third_party 6 个 public 类并入对应模块节。
> 标注：无标记 = 正文读过（标注批逐行接触）；`[name-only]` = 仅签名级接触（前缀格式批未细读正文），职责基于契约接口 + 装配点 + 链路位置写成，交付时未清零、如实降档（分档见文尾）。
> EX 规范：类名#方法名（省略包前缀）；一行职责全部通过"换名测试"自查。

## 根包（12 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Glide | 进程唯一静态门面：with 入口分发、单例初始化（双检锁）、trimMemory 分级收缩、preFillBitmapPool 预热入口 | RequestManagerRetriever、GlideContext |
| GlideBuilder | 装配器：收全部可换设施（四池/缓存/Registry 工厂/日志/实验开关），build() 一次性产出 GlideContext+Glide | Glide、RegistryFactory |
| GlideContext | 进程级能力容器（Registry 惰性/Engine/默认选项/实验开关），强制 ApplicationContext 防泄漏 | Glide#initializeGlide 装配 |
| GlideExperiments | 实验开关只读视图：按 class 查 boolean，未开启零分配 | GlideExperiments.Builder#update |
| RequestBuilder | 一次加载的配置装配 + 执行触发（泛型定产物契约），load 纯配置 into 才解析 | SingleRequest、ImageViewTargetFactory |
| RequestManager | 生命周期感知请求总控：批量暂停/恢复/清除 + Target 登记 | RequestTracker、TargetTracker |
| ListPreloader | 列表滚动预加载器：挂 OnScrollListener 按滚动方向提前发起 preload | PreloadTarget、PreloadSizeProvider |
| Registry | 全库能力路由：七类组件三档注册 + LoadPath 组合穷举缓存（含空路径哨兵） | provider 七子注册表 |
| TransitionOptions | 过渡动画配置基类（crossFade 等），按 transcodeClass 分类持有默认值 | GlideContext、SingleRequest |
| GenericTransitionOptions | 类型无关的过渡薄壳：不关心产物类型时避免泛型推导冗余 | TransitionOptions |
| Priority | 请求优先级枚举（LOW/NORMAL/HIGH/IMMEDIATE），参与线程池排序 | DecodeJob#compareTo |
| MemoryCategory | 内存档位（0/0.5/1/1.5 倍缩放缓存与池上限） | Glide#setMemoryCategory |

## load 契约层（15 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Key | 缓存键最小契约：内存靠 equals/hashCode、磁盘靠 updateDiskCacheKey | EngineKey 族、SafeKeyGenerator |
| Option | 强类型扩展配置位（含 CacheKeyUpdater 参与磁盘键） | Options、BaseRequestOptions#set |
| Options | Option 值集合，参与 EngineKey 判同 | EngineKey |
| Encoder | 原始数据→磁盘字节流（SourceGenerator 落盘用） | DataCacheWriter |
| ResourceEncoder | 解码产物→磁盘，声明 EncodeStrategy（SOURCE/TRANSFORMED/NONE） | DeferredEncodeManager |
| EncodeStrategy | 写盘内容形态枚举：原始数据/变换成品/不写 | DiskCacheStrategy#isResourceCacheable |
| ResourceDecoder | 解码契约：handles 廉价探测 + decode 产出 Resource，null 表示数据不可用 | Registry 解码桶 |
| ResourceEncoder | 见上（写盘契约） | — |
| Transformation | 位图变换契约，参与两级缓存键（换名测试：只有它要求 updateDiskCacheKey 摘要变换参数） | MultiTransformation、EngineKey |
| MultiTransformation | 变换管道：逐个变换、中间 Resource 非"原始或最终"即回收还池 | BitmapPool |
| DataSource | 数据来源枚举（REMOTE/LOCAL/DATA_DISK_CACHE/RESOURCE_DISK_CACHE/MEMORY_CACHE），策略与展示决策依据 | DiskCacheStrategy |
| DecodeFormat | 解码颜色格式偏好（PREFER_ARGB_8888/PREFER_RGB_565） | Downsampler#calculateConfig |
| PreferredColorSpace | 色彩空间偏好（SRGB/DISPLAY_P3） | ImageDecoder 系 [name-only] |
| ImageHeaderParser | 头解析契约：getType/getOrientation/hasAlpha | DefaultImageHeaderParser、Downsampler |
| ImageHeaderParserUtils | 头解析静态编排：多 parser 逐个尝试直到拿到值 | ArrayPool 租缓冲 |
| HttpException | 非 2xx 状态码异常（含 statusCode） | HttpUrlFetcher |

## load.engine（7 类 public；EngineJob/DecodeJob 等包私有见 modules-engine.md）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Engine | 唯一加载入口：三级内存查找 + 同 key 合并 + 资源生命周期裁决 | EngineJob、ActiveResources |
| Resource | 资源抽象契约（⚠ 物理住 engine 包）：getResourceClass/get/getSize/recycle | EngineResource 包装 |
| DiskCacheStrategy | 磁盘策略四谓词（写原始/写成品/读成品/读原始），5 单例 | SourceGenerator、DecodeJob |
| GlideException | 失败树异常：causes 聚合多路径原因，printStackTrace 缩进树展示 | LoadPath/DecodePath 逐层塞入 |
| LoadPath | 按 dataClass 聚合的解码路径集：逐条 DecodePath failover | Registry#getLoadPath 缓存 |
| DecodePath | 单条解码路径三段：decoder→DecodeCallback 变换→transcoder | Downsampler、TransformationUtils |
| Initializable | 后台预热契约（Bitmap.prepareToDraw），回调主线程前调用 | DecodeJob#notifyEncodeAndRelease |

## load.engine.bitmap_recycle（8 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| BitmapPool | Bitmap 池契约：get 清空/getDirty 不清空/put 校验后入池或 recycle | LruBitmapPool |
| ArrayPool | 基础数组池契约：get 长度≥请求/getExact 等长 | LruArrayPool |
| LruBitmapPool | Bitmap 池默认实现：分桶 + 总量控制 + 拒绝规则（不可变/超大/非白名单直接回收） | SizeConfigStrategy、BitmapTracker |
| BitmapPoolAdapter | 禁用池：put 直接 recycle、get 恒新建 | — |
| SizeConfigStrategy | API19+ 默认分桶：按总字节+config，ceilingKey 放宽 ≤8 倍超配 + reconfigure | GroupedLinkedMap、KeyPool |
| LruArrayPool | 数组池默认实现：单数组 ≤ 总量 1/4、池空疏放宽超配/紧张限 8 倍 | GroupedLinkedMap、ArrayAdapter |
| ByteArrayAdapter | byte[] 元信息（1 字节/元素） | LruArrayPool |
| IntegerArrayAdapter | int[] 元信息（4 字节/元素） | LruArrayPool |

## load.engine.cache（12 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| MemoryCache | 内存缓存契约（put/get/remove + ResourceRemovedListener 驱逐回调） | LruResourceCache、Engine |
| LruResourceCache | 内存缓存实现：util.LruCache 子类， getSize 按字节计费 | Engine#onResourceRemoved |
| MemoryCacheAdapter | "无内存缓存"实现（put 直接回调移除） | — |
| DiskCache | 磁盘缓存契约（get/put/clear/delete） | DiskLruCacheWrapper |
| DiskCacheAdapter | "无磁盘缓存"实现 | LazyDiskCacheProvider 兜底 |
| DiskLruCacheWrapper | 默认磁盘实现：SafeKey 文件名 + per-key 写锁 + 双检跳过重复写 + 惰性打开 | DiskLruCache、SafeKeyGenerator |
| DiskLruCacheFactory | 自定义目录工厂（CacheDirectoryGetter 契约），目录不可得返回 null | DiskLruCacheWrapper#create |
| InternalCacheDiskCacheFactory | 内部存储目录（context cache dir） | DiskLruCacheFactory |
| ExternalCacheDiskCacheFactory | 外部存储目录（无 SD 时不建） | DiskLruCacheFactory |
| ExternalPreferredCacheDiskCacheFactory | 优先外部、回退内部且回退粘性（避免缓存拆两处） | DiskLruCacheFactory |
| SafeKeyGenerator | Key→SHA-256 文件名安全串：LRU 缓存 1000 条 + MessageDigest 池化 | DiskLruCacheWrapper |
| MemorySizeCalculator | 内存预算计算：屏数计量（缓存 2 屏 + 池 4 屏 O+ 降 1 屏），钳制 memoryClass | GlideBuilder |

## load.engine.executor / prefill（3 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| GlideExecutor | 四类线程池门面：分池防队头阻塞 + 网络误用 StrictMode 击杀 + 未捕获异常策略 | DecodeJob、EngineJob |
| BitmapPreFiller | 预热预算折算：剩余缓存+池上限按 weight 加权分配各规格数量 | BitmapPreFillRunner |
| PreFillType | 预填规格（宽高/config/weight）+ Builder | BitmapPreFiller#generateAllocationOrder |

## load.model（25 类 + stream 7 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| ModelLoader | 双向契约：handles 廉价探测 + buildLoadData 产出 fetcher+键 | ModelLoaderRegistry |
| ModelLoaderFactory | 工厂契约：build(multiFactory) 递归取依赖 loader；teardown 释放 | MultiModelLoaderFactory |
| ModelLoaderRegistry | loader 注册表：按 model 类查全部候选（含子类匹配） | Registry |
| MultiModelLoaderFactory | 工厂集合管理 + alreadyUsedEntries 剪环 + 多候选打包 | MultiModelLoader |
| Model | 应用可实现的等价性接口（isEquivalentTo），库内模型未实现 | Request 等价复用 |
| ModelCache | (model,w,h)→结果记忆化缓存，键对象池化 | HttpGlideUrlLoader、BaseGlideUrlLoader |
| GlideUrl | URL 三形态（原始/转义/缓存键）：转义惰性生成，headers 携带 | HttpGlideUrlLoader、HttpUrlFetcher |
| Headers | 请求头集合契约（含 Builder 合成 UA） | LazyHeaders |
| LazyHeaders | Headers 默认实现：工厂列表惰性求值 + 双检锁缓存 | LazyHeaderFactory |
| LazyHeaderFactory | 惰性头工厂契约（取值时才计算，可能 IO） | LazyHeaders |
| StringLoader | String 入口：parseUri 分发 http/文件/content 等 Uri | UrlUriLoader、UriLoader |
| UrlUriLoader | http/https Uri → GlideUrl 委托 | HttpGlideUrlLoader |
| UriLoader | file/content/android_resource 兜底：经 ContentResolver 打开 | LocalUriFetcher 族 |
| AssetUriLoader | file:///android_asset/ 识别 + AssetManager 读取 | AssetFetcherFactory |
| FileLoader | File model 直读（含 MediaType 分型） | FileDecoder |
| ByteArrayLoader | byte[] 直通转换（无 IO，Converter 即取数） | Converter |
| ByteBufferLoader [name-only] | ByteBuffer 直通读取 | DataFetcher |
| ByteBufferEncoder | ByteBuffer→磁盘 Encoder（原始数据落盘） | SourceGenerator |
| StreamEncoder | InputStream→磁盘 Encoder（字节拷贝落盘） | SourceGenerator |
| DataUrlLoader | data: URI 解码（base64 内嵌数据） | DataFetcher [name-only] |
| DirectResourceLoader | 资源 id 直接解码本包资源（无 Uri 中转） | ResourceDecoder |
| ResourceLoader | 资源 id → android.resource:// Uri 委托（覆盖他包资源） | UriLoader |
| ResourceUriLoader [name-only] | Uri 形式资源 id 的加载 | ResourceLoader |
| UnitModelLoader | 恒等 loader：model 本身即数据（如 ByteBuffer） | — |
| MediaStoreFileLoader | MediaStore Uri→文件路径/File 委托（Q 双通道） | QMediaStoreUriLoader、FileLoader |
| stream/BaseGlideUrlLoader | "列表模型→URL"族的抽象基类：子类出 URL 与 headers，带 ModelCache | HttpGlideUrlLoader |
| stream/HttpGlideUrlLoader | GlideUrl→HttpUrlFetcher + ModelCache 记忆化（默认 500 条） | HttpUrlFetcher |
| stream/UrlLoader | URL/GlideUrl model 统一入口 | HttpGlideUrlLoader |
| stream/HttpUriLoader | 已废弃兼容壳（功能上移 UrlUriLoader） | UrlUriLoader |
| stream/MediaStoreImageThumbLoader [name-only] | MediaStore 图片缩略图（ThumbnailQuery 路径） | ThumbFetcher |
| stream/MediaStoreVideoThumbLoader [name-only] | MediaStore 视频缩略图 | ThumbFetcher |
| stream/QMediaStoreUriLoader | Q 存储兼容双通道：legacy 走 File、分区存储走 Uri+requireOriginal | FileLoader、UrlUriLoader |

## load.data（15 类 + mediastore 2 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| DataFetcher | 取数契约：loadData(priority, callback)/cancel/cleanup/getDataSource | 全部 fetcher |
| HttpUrlFetcher | HttpURLConnection 取数：重定向递归（≤5 跳）+ 状态码分流 + gzip 长度语义 | GlideUrl |
| LocalUriFetcher | ContentResolver 打开 Uri 的模板（loadResource/cleanup 子类落地） | Stream/FileDescriptor 实现 |
| StreamLocalUriFetcher | Uri→InputStream（含 MediaStore openFile 实验 API 分支） | UriLoader |
| FileDescriptorLocalUriFetcher | Uri→FileDescriptor | UriLoader |
| AssetPathFetcher | assets 数据模板：open/close 模板方法 | Stream/FileDescriptor 实现 |
| StreamAssetPathFetcher | assets→InputStream | AssetManager |
| FileDescriptorAssetPathFetcher | assets→AssetFileDescriptor | AssetManager |
| AssetFileDescriptorLocalUriFetcher [name-only] | 本地 Uri→AssetFileDescriptor | — |
| BufferedOutputStream | 保证 close 时 flush 完整的OutputStream 装饰 [name-only] | — |
| ExifOrientationStream | 向流头织入 28 字节 EXIF 方向段的过滤流 | 图片解码链 |
| DataRewinder | 回卷契约：rewindAndGet 让数据可重复读 | DataRewinderRegistry |
| DataRewinderRegistry | rewinder 注册表：按数据类型取 | GlideContext#getRegistry |
| InputStreamRewinder | InputStream 回卷（内置 RecyclableBufferedInputStream + ArrayPool） | RecyclableBufferedInputStream |
| ParcelFileDescriptorRewinder | PFD 回卷（Os.lseek 复位；catch 内层类防 VerifyError） | — |
| mediastore/MediaStoreUtil | 缩略图尺寸/权限可行性判定 | ThumbFetcher |
| mediastore/ThumbFetcher | MediaStore 缩略图 fetcher：ThumbnailQuery 查路径 → 池化打开 | ThumbnailStreamOpener |

## load.resource（3 类）+ transcode（7 类）+ bytes/file（4 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| SimpleResource | 恒等 Resource 包装（get 返回原对象，size 由构造给定） | — |
| UnitTransformation | 恒等变换（不参与键摘要的空实现语义） | DecodeHelper#getTransformation 兜底 |
| DefaultOnHeaderDecodedListener | ImageDecoder 回调适配：按 DecodeFormat/采样设 targetSize/allocator | ImageDecoder 系 |
| transcode/ResourceTranscoder | 转码契约：transcode(Resource<R>)→Resource<T> | TranscoderRegistry |
| transcode/TranscoderRegistry | 转码器注册表（含子类匹配） | Registry#getTranscodeClasses |
| transcode/BitmapDrawableTranscoder | Bitmap→BitmapDrawable（Lazy 包装零拷贝） | LazyBitmapDrawableResource |
| transcode/BitmapBytesTranscoder | Bitmap→byte[]（JPEG/PNG 压缩） [name-only] | — |
| transcode/DrawableBytesTranscoder | Drawable→byte[]（分型委托 Bitmap/Gif） [name-only] | — |
| transcode/GifDrawableBytesTranscoder | GifDrawable→byte[]（原始数据回写） [name-only] | — |
| transcode/UnitTranscoder | 恒等转码（resourceClass==transcodeClass 时） | — |
| bytes/BytesResource | byte[] 资源包装 [name-only] | — |
| bytes/ByteBufferRewinder | ByteBuffer 回卷（position 复位） | DataRewinderRegistry |
| file/FileDecoder | File→File 数据直通解码 [name-only] | — |
| file/FileResource | File 资源包装 [name-only] | — |

## load.resource.bitmap（35 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Downsampler | BitmapFactory 收口：两遍解码 + 采样 + inBitmap 复用 + EXIF | ImageReader、BitmapPool |
| DefaultImageHeaderParser | 魔数头解析（JPEG/PNG/GIF/WebP/AVIF/HEIF + EXIF TIFF 结构） | ImageHeaderParserUtils |
| ExifInterfaceImageHeaderParser | 用 androidx ExifInterface 的兜底方向解析 [name-only] | — |
| RecyclableBufferedInputStream | mark/reset 可靠性优先于内存的缓冲流（marklimit 自动扩容 + fixMarkLimit 锁定） | ArrayPool、Downsampler |
| TransformationUtils | 变换工具集：矩阵变换 + 池化画布 + EXIF 旋转 + 圆角遮罩 | BitmapPool、BITMAP_DRAWABLE_LOCK |
| BitmapTransformation | 变换基类：统一封装"变换后先 recycle 原图"的模板 | Transformation |
| 变换七子类（CenterCrop/FitCenter/CenterInside/CircleCrop/Rotate/RoundedCorners/GranularRoundedCorners） | 家族行：CenterCrop 居中裁剪、FitCenter 完整缩放、CenterInside 不放大、CircleCrop 圆形、Rotate 按角度旋转（参与磁盘键）、RoundedCorners 统一圆角、GranularRoundedCorners 四角独立圆角——全部经 TransformationUtils 画进池位图 | TransformationUtils、BitmapPool |
| DownsampleStrategy | 采样策略族（CENTER_OUTSIDE/FIT_CENTER/CENTER_INSIDE/AT_MOST/AT_LEAST/NONE/TOTAL）+ 算 sampleSize | Downsampler#calculateScaling |
| HardwareConfigState | 硬件位图全局门闸：首帧前阻塞 + 尺寸/内存上限判定 | FrameWaiter、Downsampler |
| BitmapResource | Bitmap→Resource（size=getBitmapByteSize，recycle 归还 BitmapPool） | MemoryCache/BitmapPool |
| BitmapDrawableResource | BitmapDrawable→Resource（size 委托内含 Bitmap） | Drawable 系缓存 |
| LazyBitmapDrawableResource | 惰性 drawable 包装：get() 才新建 BitmapDrawable（防共享状态污染） | BitmapDrawableTranscoder |
| BitmapDrawableDecoder | 任意数据→BitmapDrawable 复合解码（内包真实 decoder+transcoder） | Downsampler、BitmapDrawableTranscoder |
| BitmapDrawableEncoder | BitmapDrawable→磁盘（提取内含 Bitmap 编码） | BitmapEncoder |
| BitmapEncoder | Bitmap→磁盘 JPEG/PNG 编码器 | ResourceEncoder |
| BitmapTransitionOptions | Bitmap 产物过渡配置（crossFade/过渡工厂） | request.transition |
| DrawableTransformation | Drawable 请求的变换适配（内包 Bitmap 变换） | DrawableTransformation（同名单独类） |
| BitmapDrawableTransformation | 对 BitmapDrawable 应用变换（拆包变换再包回） | TransformationUtils |
| StreamBitmapDecoder | InputStream→Bitmap（Downsampler + 头解析回卷） | Downsampler、ImageHeaderParser |
| ByteBufferBitmapDecoder | ByteBuffer→Bitmap（直连 Downsampler） | Downsampler |
| ParcelFileDescriptorBitmapDecoder | PFD→Bitmap（BitmapFactory.decodeFileDescriptor） [name-only] | — |
| ResourceBitmapDecoder | 资源 id→Bitmap（decodeResource，消费 Target.SIZE_ORIGINAL） | Target |
| UnitBitmapDecoder | 数据已是 Bitmap 的恒等解码 | — |
| VideoDecoder | 视频帧解码（MediaMetadataRetriever，按 frameMicros 取帧） | MediaMetadataRetriever |
| VideoBitmapDecoder | 视频帧→Bitmap 的 decoder 适配 | VideoDecoder |
| BitmapImageDecoderResourceDecoder | ImageDecoder（P/高性能路径）统一解码 [name-only] | ImageDecoder |
| InputStreamBitmapImageDecoderResourceDecoder [name-only] | InputStream→ImageDecoder 包装 | — |
| ByteBufferBitmapImageDecoderResourceDecoder [name-only] | ByteBuffer→ImageDecoder 包装 | — |
| UriBitmapImageDecoderResourceDecoder [name-only] | Uri→ImageDecoder 包装 | — |
| DrawableToBitmapConverter | Drawable→Bitmap（画进池位图，Target.SIZE_ORIGINAL 语义） | BitmapPool |

## load.resource.drawable / gif（7 + 9 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| DrawableResource | Drawable→Resource：size 用常数估算，recycle 置空 | — |
| ResourceDrawableDecoder | 资源 id→Drawable（主题感知） | Resources.Theme |
| DrawableDecoderCompat | API 分版选解码路径（ImageDecoder vs ResourceCompat） [name-only] | — |
| AnimatedImageDecoder | ImageDecoder 动图（P+ 的 GIF/WebP 动画） [name-only] | — |
| AnimatedWebpDecoder | 动 WebP 解码（低版本路径） [name-only] | — |
| UnitDrawableDecoder | 数据已是 Drawable 的恒等解码 | — |
| DrawableTransitionOptions | Drawable 产物过渡配置 | request.transition |
| GifDrawable | GIF 动画门面：三布尔播放状态机 + 帧重绘 + 循环计数 | GifFrameLoader |
| GifFrameLoader | 帧调度：向 Glide 发起 Bitmap 请求预解码下一帧，Handler 到点换帧 | DelayTarget、GifDecoder |
| ByteBufferGifDecoder | ByteBuffer→GifDrawable（头解析 + 帧解码器装配） | GifHeaderParser、StandardGifDecoder |
| StreamGifDecoder | InputStream→GifDrawable（先读全字节） | ByteBufferGifDecoder |
| GifFrameResourceDecoder | 帧 Bitmap→Resource（池化包装） | BitmapPool |
| GifBitmapProvider | GifDecoder.BitmapProvider 实现：桥接 BitmapPool/ArrayPool | StandardGifDecoder |
| GifDrawableResource | GifDrawable→Resource（size 按帧内存估算） | — |
| GifDrawableTransformation | 对 GifDrawable 首帧应用变换（保持动画语义） | TransformationUtils |
| GifDrawableEncoder | GifDrawable→磁盘（原始 GIF 字节回写） | ResourceEncoder |
| GifOptions | GIF 解码选项（禁解码首帧的探测模式） | StreamGifDecoder |

## request（12 类）+ target（17 类）+ transition（11 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| Request | 单次加载契约：begin/clear + 状态三查 + isEquivalentTo | SingleRequest、协调器族 |
| SingleRequest | 单请求状态机（一把请求锁 + 重入护栏） | Engine、Target |
| RequestCoordinator | 多请求裁决契约：三 can* 门卫 + getRoot | 协调器族 |
| ThumbnailRequestCoordinator | 全尺寸与缩略图并行竞速协调 | 主/缩略图 Request |
| ErrorRequestCoordinator | 主失败才启动 error 的兜底协调 | 主/error Request |
| BaseRequestOptions | 20+ 配置项载体：fields 位标记 + lock 冻结 + autoClone | RequestOptions |
| RequestOptions | 具体配置类（静态工厂族 + 链式 setter） | RequestBuilder |
| FutureTarget | 阻塞式 Target 契约（get/cancel） | RequestFutureTarget |
| RequestFutureTarget | Future 桥接：get() 锁内 wait，解码回调 notifyAll（submit().get() 路径） | Waiter |
| RequestListener | 请求级监听器：先于 Target 回调，返回 true 拦截 | SingleRequest |
| ExperimentalRequestListener | 已废弃扩展：额外携带 isAlternateCacheKey | — |
| ResourceCallback | 引擎回调契约（实现者是 Request/协调器而非用户 Target） | EngineJob |
| target/Target | 显示契约：五生命周期回调 + 尺寸协商 + Request 挂取；extends LifecycleListener ⚠ | RequestManager |
| target/SizeReadyCallback | 尺寸就绪回调 | SizeDeterminer |
| target/CustomTarget | 现代非 View Target 基类：必须实现 onResourceReady/onLoadCleared | — |
| target/BaseTarget | 旧骨架：Request 字段 + 生命周期 no-op（已被 CustomTarget 取代） | — |
| target/ViewTarget | 旧 View 包装基类（已废弃）：Request 存 tag + SizeDeterminer | CustomViewTarget 迁移目标 |
| target/CustomViewTarget | 现代 View 基类：SizeDeterminer + 专属 tag id + onResourceCleared 释放契约 | SizeDeterminer |
| target/ImageViewTarget | ImageView 包装：占位图/Animatable 启停/Transition.ViewAdapter | DrawableImageViewTarget |
| target/ImageViewTargetFactory | 按 transcodeClass 产出 Bitmap/Drawable 两个实现 | GlideContext#buildImageViewTarget |
| target/BitmapImageViewTarget | setImageBitmap 落点 | ImageViewTarget |
| target/DrawableImageViewTarget | setImageDrawable 落点（into(ImageView) 默认终点） | ImageViewTarget |
| target/ThumbnailImageViewTarget | 缩略图专用：包 FixedSizeDrawable 防重排 [name-only] | FixedSizeDrawable |
| target/BitmapThumbnailImageViewTarget [name-only] | Bitmap 版缩略图 Target | — |
| target/DrawableThumbnailImageViewTarget [name-only] | Drawable 版缩略图 Target | — |
| target/FixedSizeDrawable | 方形缩略图拉伸铺满：转发型装饰器 + 矩阵变换 | BitmapPool（画布） |
| target/PreloadTarget | 预载专用一次性 Target：就位即 release 进缓存后自清 | RequestManager |
| target/AppWidgetTarget | Bitmap→RemoteViews（AppWidget）：跨进程整体推送 | AppWidgetManager |
| target/NotificationTarget | Bitmap→通知栏 RemoteViews；Android 13+ 需通知权限 | NotificationManager |
| transition/Transition | 过渡契约：返回 true 表示动画已自行放置资源 | SingleRequest |
| transition/TransitionFactory | 按 (dataSource, isFirstResource) 产 Transition | 各 Factory |
| transition/NoTransition | 恒 false 过渡（缓存命中/无动画） | — |
| transition/DrawableCrossFadeFactory | 默认交叉淡入工厂：复用无状态 transition 实例 | DrawableCrossFadeTransition |
| transition/DrawableCrossFadeTransition | TransitionDrawable alpha 过渡；无旧图垫透明层 | TransitionDrawable |
| transition/BitmapTransitionFactory [name-only] | Bitmap 产物过渡工厂基类 | — |
| transition/BitmapContainerTransitionFactory | 让内含 Bitmap 的复合资源复用 Drawable 过渡 | realFactory |
| transition/ViewAnimationFactory | View 补间动画工厂（Animation 对象或 XML id） | ViewTransition |
| transition/ViewPropertyAnimationFactory | View 属性动画工厂 [name-only] | ViewPropertyTransition |
| transition/ViewTransition | 动画应用到 View 的封装（含 animate 接口适配） [name-only] | — |
| transition/ViewPropertyTransition | 属性动画参数封装（Animator 供应商） [name-only] | — |

## manager（10 类）+ module（4 类）+ provider（6 类）+ signature（5 类）+ util（19 类）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| manager/Lifecycle | 生命周期源契约（addListener/removeListener） | LifecycleLifecycle/Fragment 实现 |
| manager/LifecycleListener | 生命周期回调三件套（onStart/onStop/onDestroyed） | Target、RequestManager |
| manager/LifecycleLifecycle | androidx Lifecycle → Glide Lifecycle 桥 | LifecycleRequestManagerRetriever |
| manager/RequestManagerRetriever | Context→RequestManager 翻译与宿主挂载 | SupportRequestManagerFragment |
| manager/RequestManagerFragment | framework Activity 宿主透明 Fragment（androidx 时代已退化） | — |
| manager/SupportRequestManagerFragment | androidx 宿主 Fragment：持 RequestManager + 帧等待挂载 | RequestManager |
| manager/RequestManagerTreeNode | 按宿主树取祖先 manager 的契约 [name-only] | — |
| manager/EmptyRequestManagerTreeNode [name-only] | application 作用域的空树节点 | — |
| manager/RequestTracker | 请求集合：登记/暂停/恢复/清除 + pendingRequests 强引用补位（#346） | RequestManager |
| manager/TargetTracker | 活跃 Target 弱引用登记簿 | RequestManager#track |
| manager/ConnectivityMonitor | 连通性监控契约（onConnectivityChanged） | DefaultConnectivityMonitor |
| manager/ConnectivityMonitorFactory | 工厂：无 ACCESS_NETWORK_STATE 产 NullConnectivityMonitor | DefaultConnectivityMonitorFactory |
| manager/DefaultConnectivityMonitorFactory [name-only] | 真实监控工厂 | — |
| manager/DefaultConnectivityMonitor [name-only] | 广播版连通性监控 | — |
| manager/FirstFrameWaiter | 首帧等待真实实现：DecorView OnDrawListener 解除硬件位图阻塞 | HardwareConfigState |
| manager/DoNothingFirstFrameWaiter | 空实现（不支持硬件位图/开关关闭零开销） | — |
| manager/FrameWaiter | 首帧等待钩子契约 | RequestManagerRetriever#buildFrameWaiter |
| module/AppGlideModule | 应用模块 API：applyOptions/registerComponents + isManifestParsingEnabled | Glide#initializeGlide |
| module/LibraryGlideModule | 库模块 API：只 registerComponents | RegistryFactory |
| module/ManifestParser | 扫 meta-data 清单值→GlideModule 反射实例化 | — |
| module/GlideModule | 已废弃旧双能力模块接口 | — |
| module/AppliesOptions [name-only] | applyOptions 能力标记接口 | — |
| module/RegistersComponents [name-only] | registerComponents 能力标记接口 | — |
| provider/ResourceDecoderRegistry | 解码器按桶注册表（桶优先级链 + 通配桶） | Registry |
| provider/EncoderRegistry | 原始数据 Encoder 有序注册表 | Registry |
| provider/ResourceEncoderRegistry | 成品 Encoder 注册表 [name-only] | — |
| provider/ImageHeaderParserRegistry [name-only] | 头解析器注册表 | — |
| provider/LoadPathCache | LoadPath 缓存（含空路径哨兵 isEmptyLoadPath） | Registry#getLoadPath |
| provider/ModelToResourceClassCache | (model,resource,transcode)→资源子类列表缓存 | Registry#getResourceClasses |
| signature/ObjectKey | 通用键包装：equals/hashCode 委托被包对象，磁盘摘要吃 toString 字节 | signature() |
| signature/EmptySignature | 空签名单例（默认值） | — |
| signature/ApplicationVersionSignature | 应用版本签名：升级缓存自动失效；包信息缺失降级随机 UUID | GlideContext 装配 |
| signature/AndroidResourceSignature | 应用版本+夜间模式：内部资源加载专用 | — |
| signature/MediaStoreSignature | 媒体签名（dateModified/mimeType/orientation） [name-only] | — |
| util/Util | 尺寸校验/字节计算/线程池创建/缓存键辅助 | 全库 |
| util/Preconditions | 参数断言（checkNotNull/checkArgument/checkNotEmpty） | 全库 |
| util/LruCache | 通用 LRU（accessOrder=true，synchronized），LruResourceCache 基类 | 子类 getSize 定尺寸单位 |
| util/MultiClassKey | 二/三元 Class 组合键（可变设计 + AtomicReference 复用） | LoadPathCache 等 |
| util/ByteBufferUtil | ByteBuffer 与 File/Stream/byte[] 互转（16K 单槽无锁缓冲 + ArrayPool） | — |
| util/ContentLengthInputStream | 声明长度感知流：提前 EOF 防半截数据 | HttpUrlFetcher |
| util/ExceptionCatchingInputStream | 已废弃吞异常流（-1 死循环问题 #4438） | — |
| util/ExceptionPassthroughInputStream | 记录并重抛版：事后 getException 可取原因 | BitmapFactory 兼容 |
| util/MarkEnforcingInputStream | mark/readLimit 守护流：读取不越过 limit | Downsampler |
| util/Executors | 线程池 shutdown 工具 | Glide#tearDown |
| util/LogTime | 单调时钟耗时打点 | 全库日志 |
| util/GlideSuppliers | Supplier 惰性化（memorize 记忆化） | GlideContext |
| util/Synthetic | 标记注解（缩小访问器用途） | — |
| util/FactoryPools | 对象池工厂：Factory 兜底 + Resetter 清理 + StateVerifier 翻转 | EngineJob/DecodeJob 池 |
| util/StateVerifier | 回收状态哨兵：双实现（生产 volatile 布尔/调试带栈） | 池化对象 |
| util/GlideTrace | systrace 埋点（编译期常量零开销） | DecodeJob |
| util/CachedHashCodeArrayMap [name-only] | hashCode 缓存的 ArrayMap（键参与高频判同） | EngineKey |
| util/FixedPreloadSizeProvider | 固定尺寸预载提供器（ListPreloader 配套） | ListPreloader |
| util/ViewPreloadSizeProvider | View 实测尺寸预载提供器（SizeViewTarget 测量） | ListPreloader |

## third_party（6 类 public，并入本表）

| 类 | 一行职责 | 关键协作 |
|---|---|---|
| disklrucache/DiskLruCache | 磁盘缓存引擎：journal 重放恢复 + .tmp rename 原子提交 + LRU 逐出 | DiskLruCacheWrapper |
| disklrucache/StrictLineReader | journal 专用行读取：EOFException 显式 EOF + 末行截断检测 | DiskLruCache#readJournal |
| disklrucache/Util | IO/删目录/字符流杂项工具（AOSP 拷贝） | DiskLruCache |
| gifdecoder/GifDecoder | GIF 解码契约：状态码 + advance/getNextFrame + BitmapProvider 内存上缴 | StandardGifDecoder |
| gifdecoder/GifHeaderParser | GIF89a 块结构解析器（只解析元数据） | GifHeader |
| gifdecoder/StandardGifDecoder | 帧解码核心：LZW + disposal 帧间合成 + 色表映射；只存下一帧最小状态 | GifFrameLoader 经 GifBitmapProvider |

（gifdecoder 包内 GifHeader/GifFrame 为包私有数据类，已在 StandardGifDecoder 行覆盖；gif_encoder 3 类进跳过清单。）

## 跳过清单（附理由）

- `third_party/gif_encoder`（AnimatedGifEncoder/LZWEncoder/NeuQuant，3 类）：全库零引用的历史移植件（GIF 生成器），不在运行时链上；类头已在标注批给出。
- 编译期生成物（GeneratedAppGlideModule/GeneratedRequestBuilder 等）：运行时不存在于本仓，属 annotation/compiler 产物。
- `annotation` + `annotation/compiler` 22 类：编译期旁路（process 三路分发见主文档 §5），非公共运行时 API；深读列入开放问题。
- 测试代码：本检出为零。

## 覆盖率分档（实数）

- 实证（正文读过/标注批逐行接触）：**213 类**
- `[name-only]`（仅签名级接触，职责基于契约+装配点写成）：**42 类**
- 跳过：gif_encoder 3 类 + 编译期生成物 + annotation 22 类（理由见上）
- 公共类型总数 255 = 实证 213 + name-only 42；`grep -c '\[name-only\]'` 本文件 = 42，与分档一致
