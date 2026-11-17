# Trace Canary

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q12 -->

**Q1: [learning] Trace Canary 的方法调用树报告依赖哪两份映射文件？插桩失效时报告会出现什么“假象”？**

依赖两份职责不同的文件：构建期 `MethodCollector` 为选中方法分配整数 ID 并写出 `methodMapping.txt`（方法 ID 到方法签名的映射），服务端还需要 R8 mapping（混淆前后名称映射）才能还原最终符号。缺任一份，整数方法栈就还原不成可读调用树。mapping 与 APK 构建不匹配时会还原成错误的方法名。

运行时机制是：修改后的字节码在方法进入和退出处调用 `AppMethodBeat.i(id)` / `AppMethodBeat.o(id)`，`AppMethodBeat` 用 ring buffer 记录 ID、进出标志与相对时间。主线程 Looper 每次 dispatch 消息的开始与结束界定一条消息的分析窗口，超时窗口内整理出调用树、耗时与场景再经 `PluginListener.onReportIssue(Issue)` 上报。这里有两个必须知道的假象：其一是插桩器失效后，Looper/FPS 信号可能仍然存在而方法树缺失——不能据此下结论“主线程没有执行过业务方法”。其二是没有保存与该构建一一对应的 `methodMapping.txt` 时，服务端会把整数栈还原成不可靠的名称。

使用边界：阈值应按场景配置（卡顿窗口、冷启动、FPS 分桶使用不同信号），没有通用固定毫秒数。方法插桩无法替代逐帧指标——jank 判定与界面状态交给 JankStats/FrameMetrics，调度、锁、Binder 等系统原因转到 Perfetto。CI 中应让插桩失败显式使构建失败，而不是静默发布空方法栈。Trace Canary 为第三方 SDK，未本地核对。
