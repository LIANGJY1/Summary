# Android 应用库与组件

本目录记录 Android 应用侧常用库和 UI 组件的使用机制与基础行为。每个主题独立成册；图片加载、列表滚动等性能诊断与优化归 [13-performance](../13-performance/README.md)，本目录不重复收录性能专题。

1. [Glide 图片加载库](./01-Glide.md)（11 题）：请求从创建到交付显示的阶段、with() 入口与生命周期绑定、活跃资源与内存缓存的三级查找、磁盘缓存与原始来源的取数顺序、into() 的复用与替换、TranscodeType 与 Target 分发、缩略图与备用请求树、占位图语义、模块化初始化、断网重连与默认内存预算。
2. [RecyclerView 列表组件](./02-RecyclerView.md)（9 题）：Adapter、ViewHolder、LayoutManager 与 item 复用的关系、回收路径与缓存容量、位置 API 契约、ItemDecoration 阶段、稳定 ID、DiffUtil 算法、ItemAnimator 四类动画、自定义 LayoutManager 契约与 ConcatAdapter 隔离。

知识内容遵守[知识库写作规范](../../WRITING-GUIDE.md)。
