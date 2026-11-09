# AAOS 车机系统

> 车载 Android 专项：端到端链路、CarService 全景、VHAL、电源用户、车控信号与整车边界。本目录收录 10 篇资料、67 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)Q 序列即结构，供 atlas 同源直读。

## 册

- [01-车机端到端链路.md](01-车机端到端链路.md)（3 题）— 触摸/按键/显示的端到端链路分段。
- [02-CarService.md](02-CarService.md)（12 题）— 服务全景与 CarFeatureController 分类、按职责查找。
- [03-VHAL集成.md](03-VHAL集成.md)（6 题）— 属性访问校验链、VHAL 死亡恢复、订阅、HIDL→AIDL 差异。
- [04-车辆电源与用户.md](04-车辆电源与用户.md)（4 题）— CPMS 状态转换、completion future 约束、电源策略。
- [05-车机应用开发.md](05-车机应用开发.md)（5 题）— Android Auto 与 AAOS 运行位置差异、模板应用。
- [06-CarLauncher.md](06-CarLauncher.md)（4 题）— 与 Launcher3 的异同、TaskView 嵌入地图。
- [07-车控信号语义.md](07-车控信号语义.md)（8 题）— VehiclePropValue 的值/状态/时间/区域、属性语义。
- [08-Kanzi状态同步.md](08-Kanzi状态同步.md)（9 题）— 渲染端重连状态快照、增量同步顺序。
- [09-启动与缓存缺陷.md](09-启动与缓存缺陷.md)（8 题）— 静态初始化与 CE 存储时机、缓存缺陷模式。
- [10-整车系统边界.md](10-整车系统边界.md)（8 题）— ECU/域控/区域/中央计算、Android 座舱边界。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
