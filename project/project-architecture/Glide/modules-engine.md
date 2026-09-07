# Glide 模块解码：引擎调度与内存基础设施（engine / executor / cache / pool / prefill / third_party）

> 锚点 commit `f38a5e5`｜归属主文档 [ARCHITECTURE.md](./ARCHITECTURE.md) §5。

## 模块卡片

### load.engine

**职责**：Engine（三级内存查找 + 请求合并）、EngineJob（结果分发）、DecodeJob（双状态机后台执行）、三个 Generator（三环取数）、ActiveResources（弱引用活跃表）、缓存键族（EngineKey/ResourceCacheKey/DataCacheKey）、GlideException 失败树。
**对外接口**：`Engine#load` 唯一入口；`EngineJob#start`；`DecodeJob` 实现 Runnable；`EngineResource` 引用计数包装。
**关键协作**：上游 SingleRequest；下游 model（取数）、resource（解码）、cache（磁盘）、pool（解码内存）；`EngineJobListener`/`ResourceListener` 是三条回流通道。
**设计动机**：三级查找对应三种资源生命周期（在用=计数、备用=LRU、生成中=任务表）——单一 LRU 表达不了"正在显示不可驱逐"与"并发同 key 只解码一次"两条约束。
**雷区**：Engine 单锁全局；DecodeJob 池化复用（读字段前先看 init/release 配对）；Generator 的 startNext 是断点续跑语义（每次只推进一步）。

### load.engine.executor

**职责**：GlideExecutor——四类池（source 核数≤4 / disk-cache 单线程 / source-unlimited 弹性 / animation 1~2）+ 未捕获异常策略 + 网络误用 StrictMode 检测。
**对外接口**：`GlideExecutor#newSourceExecutor/newDiskCacheExecutor/newUnlimitedSourceExecutor/newAnimationExecutor`、`#calculateBestThreadCount`。
**设计动机**：按任务性质分池防队头阻塞（GIF 帧解码占满线程会饿死普通图片）；缓存/动画线程 penaltyDeath 检测网络（`GlideExecutor#newThread` 内 setThreadPolicy）。
**雷区**：线程优先级低于 BACKGROUND（`Process#setThreadPriority`）；`calculateBestThreadCount` API<17 需数 /sys cpu 文件兜底。

### load.engine.cache

**职责**：MemoryCache 接口（活跃表降级/内存 LRU）、DiskCache 接口与 DiskLruCacheFactory 族（内部/外部/自定义目录）、DiskLruCacheWrapper（默认实现：SafeKeyGenerator 文件名 + per-key 写锁 + 惰性打开）。
**关键协作**：`Engine#onResourceReleased → cache.put` 降级入 LRU；`DecodeHelper#getDiskCache` 供 Generator 查询；`DiskCacheWriteLocker` 按键串行化写。
**设计动机**：接口与实现分离——用户可换内存缓存实现（`MemoryCacheAdapter` 即"无缓存"）；磁盘目录三级工厂适配不同存储场景。
**雷区**：MemoryCache 接口签名引用 `engine.Resource` ⚠（契约类型住在实现包）；clearDiskCache 必须后台线程。

### load.engine.bitmap_recycle

**职责**：BitmapPool（按字节+config 分桶，reconfigure 复用）与 ArrayPool（16K 缓冲租借）；GroupedLinkedMap 组级 LRU；BaseKeyPool 键池化。
**关键协作**：解码层 45 处 import（Downsampler/TransformationUtils 全走池）；`LruBitmapPool` 是默认实现，`BitmapPoolAdapter` 是禁用池。
**设计动机**：复用判同维度随平台演进（API<19 宽高严格匹配 / 19+ 按字节分桶 + reconfigure）；按屏数计量预算（MemorySizeCalculator，见 Glide.md「内存复用池」章）。
**雷区**：put 进池内容即作废；`allowedConfigs` 排除 HARDWARE 位图。

### load.engine.prefill

**职责**：Bitmap 池预热——按 weight 折算预算，主线程分批分配，32ms 阈值探测 GC 指数退避。
**对外接口**：`BitmapPreFiller#preFill(PreFillType.Builder...)`（Glide#preFillBitmapPool 传入）。
**关键协作**：分配结果经 `UniqueKey` 寄存 MemoryCache，LRU 逐出时自然回流 Bitmap 池。
**设计动机**：见 Glide.md「Bitmap 池预热」章（GC 探针让路模式，知识库有条目）。
**雷区**：预填规格必须与实际解码产物一致否则白占内存。

### third_party

**职责**：disklrucache（磁盘缓存引擎：journal 重放 + rename 原子提交）、gif_decoder（GIF 帧解码：LZW + 帧间合成）、gif_encoder（零引用的历史移植件）。
**关键协作**：`DiskLruCacheWrapper` 是 disklrucache 唯一调用方；gif_decoder 经 `GifBitmapProvider` 桥接 BitmapPool；`GifFrameLoader` 驱动 gif_decoder。
**设计动机**：journal 先行 + .tmp rename 解决"随时可被杀"的一致性（Glide.md「磁盘缓存引擎」章）；gif_decoder 只保留"解码下一帧"的最小状态省内存。
**雷区**：DiskLruCache 同目录单实例；StrictLineReader 只放行 US-ASCII（上游 Javadoc 与代码不一致的现场）。

## 核心类深卡片

### EngineJob（engine）

**职责**：一个进行中加载的结果分发枢纽：包装 EngineResource、pending 计数保活、按回调自带 executor 投递。
**协作者**：`Engine#waitForExistingOrStartNewJob` 创建并 start；`DecodeJob#onResourceReady` 上报；`CallResourceReady#run` 双锁序执行。
**设计动机**：回调线程策略由请求自带（`#addCallback(cb, executor)` 成对注册）——分发层不决策"在哪回调"；pending 计数从 0 变正即 `engineResource.acquire()`，防同步 release 在通知中途回收资源。
**不变量**：先请求锁后 EngineJob 锁（b/136032534）；`#release` 前全部回调已执行（decrementPendingCallbacks 归零）。

### ActiveResources（engine）

**职责**：活跃资源的弱引用登记簿 + ReferenceQueue 后台清理线程。
**协作者**：`Engine#loadFromActiveResources` 查询；`#activate/#deactivate` 晋升降级；`ResourceListener` 回调 Engine。
**设计动机**：弱引用只答"是否被 GC"不答"谁在用"——强可达由业务链（View 持 Drawable）保证，登记簿纯观察（知识库条目"弱引用登记簿"）。
**不变量**：单后台线程阻塞消费 ReferenceQueue；isActiveResourceRetentionAllowed 开启时死资源暂存防抖。

### ResourceCacheGenerator / DataCacheGenerator / SourceGenerator（engine）

**职责**：三环取数——变换后缓存 → 原始数据缓存 → 原始来源；每环 startNext 断点续跑，false 换下一环。
**协作者**：`DecodeJob#runGenerators` 换道；SourceGenerator 写-读回环（cacheData 落盘后转 DataCacheGenerator 读回）；`DecodeHelper` 提供键集与 loader。
**设计动机**：顺序即成本序（命中跳下载/解码/变换）；写-读回环让解码入口收敛为 File 一种（Glide.md「三环取数调度」章）。
**不变量**：进度索引存字段跨调用保留；`isCurrentRequest` 引用相等丢弃旧请求迟到回调。

### DiskLruCacheWrapper + DiskLruCache（cache / third_party）

**职责**：磁盘缓存默认实现；journal 先行 + .tmp→rename 原子提交 + 重放恢复。
**协作者**：`DecodeJob.DiskCacheProvider` 惰性打开；`SafeKeyGenerator` 键转文件名；`DiskCacheWriteLocker` per-key 写锁。
**设计动机**：读-改-写竞态（等锁期间同键已被写入）用双检跳过；delete 后 finally 重置实例允许重开（#2465）。
**不变量**：同目录单实例；CLEAN 行只有完整写入后才出现。

### GlideExecutor（executor）

**职责**：四类池统一门面；线程命名 glide-<name>-thread-N 便于定位。
**设计动机**：见模块卡片；`uncaughtThrowableStrategy` 三档（忽略/记录/抛出）——线程静默死亡是加载挂起的根源。
**不变量**：任务队列 `PriorityBlockingQueue`，DecodeJob 按 priority+order 排序（同优先级 FIFO 防饿死）。
