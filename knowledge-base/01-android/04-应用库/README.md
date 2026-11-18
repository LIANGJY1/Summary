# Android 应用库与组件

本目录记录 Android 应用侧常用库和 UI 组件的契约与实现，共 4 册、35 题。图片加载、列表滚动等性能诊断与优化见 [Android 性能](../14-performance/README.md)。

1. [Glide 图片加载库](01-Glide.md)（11 题）：请求从创建到交付显示的阶段、with() 入口与生命周期绑定、活跃资源与内存缓存的三级查找、磁盘缓存与原始来源的取数顺序、into() 的复用与替换、TranscodeType 与 Target 分发、缩略图与备用请求树、占位图语义、模块化初始化、断网重连与默认内存预算。
2. [RecyclerView 列表组件](02-RecyclerView.md)（13 题）：组件职责、ViewHolder 的回收与查找、位置和稳定 ID、DiffUtil 与局部更新、装饰与变更动画、LayoutManager、共享池和嵌套列表预取。
3. [Retrofit 网络请求库](03-Retrofit.md)（6 题）：动态代理主链与解析期/运行期分离、注解到 Request 的构造与安全语义、suspend 复用 CallAdapter、回调线程装配、OkHttpCall 状态机、工厂链注册顺序。
4. [AppCompat 装配](04-AppCompat装配.md)（5 题）：AppCompatActivity 三层装配、subDecor、XML 控件替换与 tint、夜间模式、Toolbar 与菜单。

知识内容遵守[知识库写作规范](../../WRITING-GUIDE.md)。
