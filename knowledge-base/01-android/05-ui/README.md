# Android UI

> 本目录覆盖 Activity 生命周期、View 与 Compose 组件契约、资源与主题、窗口系统、车机界面与驾驶限制、无障碍和界面诊断，共 10 册、253 题。AndroidX AppCompat 与 RecyclerView 的库机制见 [应用库与组件](../04-应用库/README.md)，像素绘制、合成与显示见 [Android 图形](../06-graphics/README.md)。

## 册

- [01-Activity生命周期.md](01-Activity生命周期.md)（16 题）— 生命周期两对正交维度、首帧时序、四个可观测时点、Configuration 变更、状态保存。
- [02-View绘制.md](02-View绘制.md)（31 题）— View 树创建、MeasureSpec、自定义控件尺寸契约、失效与重调度、绘制及 Surface 宿主。
- [03-资源与主题.md](03-资源与主题.md)（26 题）— 限定符匹配顺序、深色模式、RRO、dp/sp/fontScale、多屏 Configuration、多语言 RTL。
- [04-窗口系统.md](04-窗口系统.md)（57 题）— 窗口遮挡与转场、addWindow 与 token、层级和 relayout、Insets、IME、多窗口、画中画和 TaskSnapshot。
- [05-Compose.md](05-Compose.md)（36 题）— 组合与副作用、Snapshot 版本和并发、Lazy 布局、Modifier、View 双向嵌入和动画组件。
- [06-车机界面.md](06-车机界面.md)（18 题）— 车机分层改动归属、CarSystemUI、_cluster 仪表、车机桌面。
- [07-驾驶安全.md](07-驾驶安全.md)（17 题）— UX Restrictions 消费、驾驶状态映射、限制策略。
- [08-界面还原与主题缺陷.md](08-界面还原与主题缺陷.md)（32 题）— 还原偏差归因、主题属性链、缺陷模式。
- [09-界面排查.md](09-界面排查.md)（16 题）— 白屏黑屏卡顿分层归因、抓帧时机、证据链。
- [17-界面辅助服务.md](17-界面辅助服务.md)（4 题）— Accessibility 事件上报与节点查询方向、缓存与成本。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
