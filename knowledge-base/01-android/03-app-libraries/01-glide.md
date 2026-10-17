# Glide 图片加载库

**Q1: [learning] Glide 图片请求从创建到显示经过哪些阶段？**

Glide 4 的请求从目标尺寸和缓存查找开始，到资源交付才完成图库工作。图片是否可见还取决于后续绘制与显示：

1. **创建与绑定：**`Glide.with()` 取得与界面生命周期关联的 RequestManager，`load()` 指定来源，`into()` 绑定 Target 并启动请求。Target 提供目标尺寸，尚未布局时可能等待尺寸。
2. **查找与生成：**Engine 先查可复用资源，未命中才按策略读取来源、解码和变换。缓存命中会跳过部分工作。
3. **交付与显示：**Target 接收资源，ImageView 再参与绘制。`RequestListener.onResourceReady()` 早于 `Target.onResourceReady()`，成功回调不等于图片已出现在显示帧。

依据为 Glide 4《Targets》《Caching》和《RequestListener》。图片缓存、预取和可见时间分析见[加载链路与加载库](../13-performance/01-应用/06-图片加载/03-加载链路与加载库.md)。
