# Android UI 与输入

> 界面体系全链路：生命周期与首帧、View 绘制、资源主题、窗口系统、Compose、输入系统与车机输入、排查方法。本目录收录 18 篇资料、368 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)Q 序列即结构，供 atlas 同源直读。

## 册

- [01-Activity生命周期.md](01-Activity生命周期.md)（16 题）— 生命周期两对正交维度、首帧时序、四个可观测时点、Configuration 变更、状态保存。
- [02-View绘制.md](02-View绘制.md)（27 题）— measure/layout/draw 三趟、MeasureSpec、自定义 View 契约、绘制原理。
- [03-资源与主题.md](03-资源与主题.md)（26 题）— 限定符匹配顺序、深色模式、RRO、dp/sp/fontScale、多屏 Configuration、多语言 RTL。
- [04-窗口系统.md](04-窗口系统.md)（57 题）— 窗口三副面孔、addWindow/token、relayout、Insets、IME、多窗口与 PiP、不显示排查。
- [05-Compose.md](05-Compose.md)（19 题）— 组合/布局/绘制三阶段、重组与状态订阅、稳定性与性能。
- [06-车机界面.md](06-车机界面.md)（19 题）— 车机分层改动归属、CarSystemUI、_cluster 仪表、车机桌面。
- [07-驾驶安全.md](07-驾驶安全.md)（18 题）— UX Restrictions 消费、驾驶状态映射、限制策略。
- [08-界面还原与主题缺陷.md](08-界面还原与主题缺陷.md)（32 题）— 还原偏差归因、主题属性链、缺陷模式。
- [09-界面排查.md](09-界面排查.md)（16 题）— 白屏黑屏卡顿分层归因、抓帧时机、证据链。
- [10-输入系统.md](10-输入系统.md)（27 题）— InputDispatcher 事件路由、输入窗口、ANR 计时。
- [11-应用事件分发.md](11-应用事件分发.md)（21 题）— View 层分发顺序、触摸/滚轮/按键处理、消费语义。
- [12-InputReader.md](12-InputReader.md)（17 题）— input 内核链路、RawEvent、设备分类与配置。
- [13-按键映射.md](13-按键映射.md)（17 题）— kl/kcm 三层翻译、KeyEvent 字段语义、长按与 repeat。
- [14-焦点与多屏输入.md](14-焦点与多屏输入.md)（14 题）— 焦点令牌记账、FocusResolver、多屏焦点。
- [15-车机输入.md](15-车机输入.md)（17 题）— VHAL 硬键链路、CarInputService 分发、多屏路由。
- [16-输入排查.md](16-输入排查.md)（16 题）— getevent/dumpsys 输入取证、断链定位。
- [17-界面辅助服务.md](17-界面辅助服务.md)（4 题）— Accessibility 事件上报与节点查询方向、缓存与成本。
- [18-AppCompat装配.md](18-AppCompat装配.md)（5 题）— AppCompatActivity 三层装配与 subDecor、控件调包与 tint 管线、夜间模式生效链、Toolbar/menu 体系。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
