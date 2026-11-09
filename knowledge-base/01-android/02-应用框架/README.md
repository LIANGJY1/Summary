# Android 应用框架

> 应用侧框架契约：四大组件、Handler 消息机制、Parcel、线程与 IPC 稳定性、主线程异步缺陷与修复范式、任务栈与导航。本目录收录 16 篇资料、136 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)Q 序列即结构，供 atlas 同源直读。

## 册

- [01-四大组件.md](01-四大组件.md)（6 题）— 四类组件的调度契约、启动链路、生命周期与线程边界。
- [02-Handler消息机制.md](02-Handler消息机制.md)（12 题）— Looper/MessageQueue 协作、泄漏与线程规则、Android 17 DeliQueue 结构与迁移。
- [03-Parcel.md](03-Parcel.md)（9 题）— Parcel 读写契约、Blob 分流与类型边界。
- [04-ContentProvider.md](04-ContentProvider.md)（6 题）— Provider 初始化时序、CursorWindow 传输、超时语义、applyBatch、stable/unstable 引用、包可见性。
- [05-集合与注解.md](05-集合与注解.md)（3 题）— SparseArray 取舍、资源注解与 @IntDef 的静态检查边界。
- [06-私密空间.md](06-私密空间.md)（3 题）— Private Space、厂商应用锁、AAOS App Lock 与应用内鉴权的保护边界。
- [07-MVP架构.md](07-MVP架构.md)（7 题）— MVP 职责边界、接口解耦、异步协调与测试性。
- [08-线程与IPC稳定性.md](08-线程与IPC稳定性.md)（23 题）— 线程/协程泄漏治理、Binder IPC 故障判断、Keystore 配额资源治理。
- [09-主线程与异步缺陷.md](09-主线程与异步缺陷.md)（27 题）— 主线程同步 Binder 病灶、异步时序修复范式与真机案例（SIR 系列）。
- [10-Activity启动与任务栈.md](10-Activity启动与任务栈.md)（14 题）— 任务选择与实例复用、launchMode 与 flag 叠加、Intent 匹配、taskAffinity、场景推演。
- [11-Fragment与ViewModel.md](11-Fragment与ViewModel.md)（5 题）— 实例与视图生命周期两段式、viewLifecycleOwner、页面切换方式、commit 三方法、作用域。
- [12-异步调度.md](12-异步调度.md)（4 题）— 协程切换事件拆解、Dispatcher 选型、结构化并发、Flow 背压操作符。
- [13-异步与Flow测试.md](13-异步与Flow测试.md)（5 题）— 虚拟时间与测试 Dispatcher、runCurrent/advanceTimeBy、三种流测试、重连状态机验证。
- [14-WorkManager.md](14-WorkManager.md)（4 题）— 保证执行的三层接力、约束与重试的检测位置、expedited 与 setForeground、Doze 维护窗口。
- [15-ComponentActivity与ActivityResult.md](15-ComponentActivity与ActivityResult.md)（4 题）— 能力接口化装配、ViewModel 跨旋转机制点、回调栈与预测式返回、存档-恢复闭环。
- [16-LifecycleRegistry.md](16-LifecycleRegistry.md)（4 题）— 事件驱动源与 API 29 双通道、多观察者收敛状态机、补课粘性语义、removeObserver 不对称设计。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
