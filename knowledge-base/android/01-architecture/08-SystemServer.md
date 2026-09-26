# SystemServer

> 学习资料（文章模式沉淀）。主线：system_server 的进程构成与单点语义、WMS 与 SurfaceFlinger 的分工。创建与启动链细节见 [02-Android系统启动流程.md](./02-Android系统启动流程.md)（Q5/Q6/Q15–Q18）。Q 序列即结构，供 atlas 同源直读。

**Q1: `system_server` 进程都包含哪些层级的代码？了解这些有什么用？**

`system_server` 是"应用框架层服务端"的宿主进程：里面运行着几百个 Java 系统服务（AMS/ATMS、WMS、PMS 等，按 Bootstrap/Core/Other/Apex 四组启动）、Framework 的 JNI 库、ART 运行时和 Binder 原生库；它由 Zygote fork 出来，继承预加载的类与资源，接收 Binder 事务的线程池在进入 `SystemServer.main()` 之前就已启动。

它**不包含**的东西同样重要：SurfaceFlinger 是独立原生服务进程，HAL 或是独立进程、或是加载进调用方的共享库——"应用框架"这个层名不等于"某一个进程"。

了解这些的用处有三点：

1. **框架单点**：`system_server` 崩溃意味着整个框架重启——Zygote 检测到其死亡后自杀，由 init 重启 Zygote 再重新 fork；各应用的日常 Binder 调用大量落在这里，它的卡顿是全局性卡顿；
2. **慢的归属**：`system_server` 内的排队和锁竞争是系统服务的开销，不要算到应用头上；
3. **进程归属**：定位问题前先确认进程，别把层名当进程名用。

**Q2: WindowManagerService 和 SurfaceFlinger 各自负责什么？为什么说它们是"层≠进程"的典型实例？**

WMS 与 SurfaceFlinger 分管显示链路的"策略世界"和"像素世界"，策略与合成解耦，两个角色互不隶属、不能合并进同一个"应用框架进程"标签：

1. **WMS**：运行在 `system_server`，管窗口容器、层级、焦点、配置，产出图层描述；
2. **SurfaceFlinger**：init 启动的独立原生服务进程，收集各应用的图层与缓冲区，借助 CompositionEngine、RenderEngine 和 Composer HAL 在每个 vsync 周期合成上屏。

对排查的意义：判断"界面没动"时两侧都要查——可能是 WMS 侧没有产生布局/层级变化，也可能是 SF 侧没有合成新帧或提交被栅栏卡住；`system_server` 与 SF 通过明确接口协作，一方的卡顿与崩溃不会自动等同于另一方。这也是显示链路"策略端与合成端"分工的直接例证：二者协作却分属两层（WMS 在应用框架层，SF 在原生库与 ART 层，见 Q1）、两进程、两种语言、两个崩溃域。
