# Glide 模块解码：数据与解码层（load 契约 / model / data / resource）

> 锚点 commit `f38a5e5`｜归属主文档 [ARCHITECTURE.md](./ARCHITECTURE.md) §5。

## 模块卡片

### load（契约根包）

**职责**：全库接口契约——`Key`（缓存键）、`Option/Options`（强类型扩展位）、`Encoder/ResourceEncoder`（写盘）、`ResourceDecoder`（解码）、`ResourceTranscoder`（转码）、`Transformation`（变换）、`ImageHeaderParser`（头解析）、`DiskCacheStrategy`（四谓词策略）。
**关键协作**：被 engine/resource/model/data/request 五层依赖；⚠ 方法签名引用 `engine.Resource` 与 `bitmap_recycle.ArrayPool`（契约类型住在实现包，主文档 §3 意外边 #1）。
**设计动机**：契约先行让"加一种数据类型只注册"成立；`DiskCacheStrategy` 四谓词而非开关（Glide.md DiskCacheStrategy【设计思想】）。
**雷区**：`Option` 带 CacheKeyUpdater 参与磁盘键——值变更缓存自动失效，自定义 Option 忘了实现会导致脏缓存。

### load.model

**职责**：ModelLoader 双向契约 + 注册表 + 类型委托链（String/Uri/File/Integer/byte[]/URL/Drawable 等入口 loader → 底层 loader）+ GlideUrl/Headers（网络请求封装）+ ModelCache 记忆化。
**对外接口**：`ModelLoader#handles/buildLoadData`；`ModelLoaderFactory#build(multiFactory)/teardown`；`MultiModelLoaderFactory#build(dataClass)`。
**关键协作**：`MultiModelLoader` 运行期 failover；`BaseGlideUrlLoader` 给"列表模型→URL"族共用；⚠ `model → signature`（MediaStoreFileLoader 等用签名键隔离缓存）。
**设计动机**：入口 loader 只做类型识别、取数统一压给底层 loader——每种来源实现一次（主文档 §5 model 卡片）。
**雷区**：`HttpGlideUrlLoader` 的 ModelCache 默认 500 条进程级缓存（URL 解析复用）；Headers 默认含 User-Agent，鉴权头走 Headers 接口注入（URL 会成为磁盘键被持久化）。

### load.data

**职责**：DataFetcher 取数契约（loadData/cancel/cleanup + DataCallback）与实现族：HttpUrlFetcher（HttpURLConnection + 重定向递归）、本地 Uri 族（Stream/FileDescriptor/AssetFileDescriptor）、ByteBuffer/ByteArray/Asset/MediaStore 缩略图族、DataRewinder 回卷注册表。
**关键协作**：`ModelLoader.LoadData` 携带 fetcher；`SourceGenerator#startNextLoad` 触发；`ExifOrientationStream` 织入方向段。
**设计动机**：取消只置 volatile 标志（主线程触发 cancel 不做阻塞操作，连接关闭在工作线程检查点）；`DataRewinder` 解决"流只能读一次"（`InputStreamRewinder` 内置 RecyclableBufferedInputStream）。
**雷区**：`HttpUrlFetcher#loadDataWithRedirects` 5 跳上限 + 重定向环检测（#2352 泄漏修复）；清理路径 cleanup 必须幂等。

### load.resource

**职责**：解码/变换/转码实现——bitmap（Downsampler 两遍解码、ImageHeaderParser、变换工具与子类、VideoDecoder）、drawable（DrawableDecoder/ResourceDrawableDecoder/Animated 系）、gif（GifDrawable/GifFrameLoader/ByteBufferGifDecoder）、transcode（Bitmap→BitmapDrawable/byte[] 转码器）、bytes/file（字节与文件资源）。
**关键协作**：45 处依赖 pool（解码内存全部池化）；⚠ `→ request`（过渡工厂 + Target 哨兵，主文档 §3 意外边 #2）；`GifFrameLoader → Glide` 发起帧解码请求。
**设计动机**：Downsampler 收口全部 BitmapFactory 调用（两遍解码 + 采样 + 复用 + EXIF 单点维护）；变换画布池化零净分配。
**雷区**：`getResources` 解码产物类型必须与 Registry 注册的 resourceClass 对齐，错配即 NoResultEncoderAvailableException。

## 核心类深卡片

### Registry（根包，路由本体）

**职责**：七类组件注册（append/prepend/replace 三档）+ LoadPath 组合穷举缓存。
**协作者**：provider 七子注册表；`RegistryFactory#createAndInitRegistry` 装配顺序；`DecodeHelper#getLoadPath` 查询。
**设计动机**：解码桶两级路由（Animation/Bitmap/BitmapDrawable + PREPEND_ALL/APPEND_ALL 通配桶 #4309）；空 LoadPath 也缓存防重复穷举（`Registry#getLoadPath` isEmptyLoadPath 分支）。
**不变量**：组件参与缓存键——自定义组件必须正确实现 equals/hashCode。

### ModelLoader 契约（load.model）

**职责**：`#handles` 廉价探测 + `#buildLoadData` 产出 LoadData(fetcher, sourceKey, alternateKeys)。
**协作者**：`DecodeHelper#getLoadData` 惰性枚举全部候选；`MultiModelLoader` 打包 failover。
**设计动机**：alternateKeys 支撑"主键失效备用键兜底"（AUTOMATIC 策略按它决定变换结果是否落盘）。
**不变量**：buildLoadData 可返回 null（不支持时）；handles 不得有重 IO。

### Downsampler（load.resource.bitmap）

**职责**：全部 BitmapFactory 调用收口：两遍解码（inJustDecodeBounds 读头 → 算采样/格式/inBitmap → 真解码）+ EXIF 旋转。
**协作者**：`ImageReader` 五实现统一数据源；`RecyclableBufferedInputStream` 撑 mark/reset；`BitmapPool` 租还内存；`TransformationUtils#rotateImage` 旋转。
**设计动机**：采样两级（inSampleSize 2 的幂 + inDensity/inTargetDensity 补足）；内存可控三件套（RGB_565/采样/池复用）。
**不变量**：核心七步顺序（`Downsampler#decodeFromWrappedStreams`）；硬件位图经 `HardwareConfigState` 门闸（Glide.md「硬件位图安全闸」章）。

### GifDrawable + GifFrameLoader（load.resource.gif）

**职责**：GIF 动画门面（绘制/状态机/循环计数）+ 帧调度（把帧解码伪装成普通加载请求）。
**协作者**：`GifFrameLoader#loadNextFrame → requestBuilder.into(DelayTarget)`；`GifDrawable#onFrameReady` 主线程换帧；`GifState(ConstantState)` 共享 frameLoader。
**设计动机**：复用引擎线程池/变换/池而不自建解码线程；isStarted×isVisible×isRunning 三布尔合成播放判定（页面不可见停、回前台续）。
**不变量**：单帧在途（isLoadPending）——解码慢时动画放慢而非跳帧（源码验证，纠正"跳帧"流行说法）；callback 链断即自停。

### HttpGlideUrlLoader + HttpUrlFetcher（model.stream / data）

**职责**：网络加载链：GlideUrl 封装（原始串/转义串/缓存键三形态）+ ModelCache 记忆化；HttpURLConnection 取数 + 重定向递归 + ContentLengthInputStream 防截断。
**设计动机**：URL 转义惰性化（构造只存原始串，知识库条目"多形态惰性规范化"）；重定向是同一请求的延续故就地递归（5 跳上限）。
**不变量**：cancel 只置标志位，连接关闭在工作线程；每请求超时/头全部可配（Headers 接口）。
