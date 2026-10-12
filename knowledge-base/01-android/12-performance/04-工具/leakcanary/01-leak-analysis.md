# LeakCanary

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q11 -->

**Q1: [learning] LeakCanary 判定一个对象“泄漏”的流程是什么？为什么“等待期和 GC 后仍被保留”不等于“永远无法释放”？**

流程四步（按 LeakCanary 2.14 材料口径）：

1. 生命周期观察器把应结束生命周期的对象交给 `ObjectWatcher`，只保留弱引用。
2. 默认等待五秒并触发 GC；弱引用仍未清除则对象进入保留集合。
3. 保留对象数量达到阈值（应用可见时默认累计 5 个才转储；不可见时等待一个 `retainedDelayMillis`）后调用 Android 堆转储接口。
4. Shark 堆分析器解析 HPROF，从 GC Root 搜索到保留对象的引用路径并按泄漏签名聚类。

它判断的是“对象在等待期和 GC 后仍被保留”，不是“已被证明永远无法释放”——异步任务、动画、消息队列和测试操作尚未结束时对象都可能暂时存活。

默认只跟踪已销毁的 Activity、Fragment、Fragment View、Service 和已执行 `onCleared()` 的 ViewModel，业务对象要在生命周期结束处显式调用 `AppWatcher.objectWatcher.expectWeaklyReachable(instance, reason)`。修复依据是引用链上第一条生命周期不合理的强引用，而不是路径中出现过的熟悉类名。基础依赖应使用 `debugImplementation`，避免把堆转储与分析代码带进 release APK。


<!-- 来源：05-observability/03-apm-platforms.md · Q13 -->

**Q2: [learning] LeakCanary 2.14 默认自动观察哪几类对象？为什么 Service watcher 被列为高版本验证重点？**

`AppWatcher.appDefaultWatchers()` 默认安装四组 watcher：Activity（`onDestroy()` 完成）、Fragment 与 ViewModel（`onDestroy()`、fragment view 的 `onDestroyView()`、`onCleared()`）、root view（从 WindowManager 脱离）、Service（`onDestroy()` 后 system_server 收到 `serviceDoneExecuting()`）。“默认观察 View”不能推广成“所有 detached View 都会被扫一遍”——Fragment view 来自 Fragment 生命周期，root view 来自对 WindowManager 根 View 的监听，普通子 View 没有统一生命周期，业务仍要在明确终点自行调用 `expectWeaklyReachable()`。

Service watcher 是重点，因为它依赖 framework 私有实现：反射 `ActivityThread.mH`、`mServices`、`Handler.mCallback`、`IActivityManagerSingleton` 并识别消息号 `STOP_SERVICE = 116`。AAOS13 源码中这些成员与消息号同样存在（`ActivityThread.java` 的 `mServices` 与 `H.STOP_SERVICE`、`Handler.java` 的 `mCallback`），但它们不属于公开 SDK 兼容契约——hidden API 策略、OEM 修改或平台演进都可能让反射失败，而 watcher 失败是静默的（记日志并放弃 Service 自动观察）。做法：测试包启动后检查 Logcat 是否出现 `Could not watch destroyed services`，关键 Service 在 `onDestroy()` 末尾再做一次业务侧观察。自定义观察放在不可逆的资源清理终点，不观察 Application、全局 cache 等设计上常驻的对象。LeakCanary 为第三方 SDK，未本地核对。反射目标存在性按 AAOS13 核对。


<!-- 来源：05-observability/03-apm-platforms.md · Q14 -->

**Q3: [learning] LeakCanary 把泄漏分为 Application Leak 与 Library Leak 的依据是什么？给自家问题挂 matcher 会造成什么后果？**

依据是 `ReferenceMatcher`：命中已知系统或第三方依赖引用模式的是 Library Leak（应用按正常 API 使用时仍受系统/依赖 bug 影响，修复权可能不在应用侧，但内存仍被占用），没有命中的按 Application Leak 报告——多数 suspect reference 可由应用控制。matcher 是“某条引用在指定版本与设备条件下如何分类”的规则，不删除 heap 中的对象，但会改变分类结果和路径搜索。

后果来自默认断言行为：instrumentation 测试的默认 reporter 只对 Application Leak 抛 `NoLeakAssertionFailedError`——如果把自家 singleton、listener 或 adapter 的泄漏挂成 Library Leak matcher，CI 会误判为通过，而泄漏继续占内存。例外规则因此要守纪律：matcher 精确到 class、field、依赖版本，不用宽泛包名前缀。description 关联上游 issue、内部 owner、加入日期与删除条件。升级 SDK 或 Android 版本后重跑一遍不带自定义 matcher 的对照测试，删除不再命中的规则。`@SkipLeakDetection` 也要写明原因并设到期检查。

对 Library Leak 本身的处置是按影响管理：记录 signature、系统版本、依赖版本与 retained size，查上游修复版本并用升级/降级对照验证。高流量页面或大 retained size 即使来自系统，也要评估生命周期顺序或隔离方案。CI 可以暂不为团队无法修复的问题失败，但要保留报告和趋势。LeakCanary 为第三方 SDK，未本地核对。


<!-- 来源：05-observability/03-apm-platforms.md · Q15 -->

**Q4: [learning] 为什么 LeakCanary 的完整能力（自动 dump、分析、通知）只能进 debug 变体？线上要用 heap 分析时要处理哪些问题？**

官方要求 `leakcanary-android` 用于 debuggable 构建，非 debuggable 进程初始化默认抛错，以便尽早暴露误接入。完整能力的成本是结构性的：heap dump 会暂停被测进程并把当时 Java 堆写入文件，文件可能包含字符串、URL、token、业务对象，体积可达数十到数百 MB。Shark 分析还要消耗 CPU、内存、I/O 与电量。`stripHeapDump` 把 primitive array 内容归零，能降低字符串暴露，但不等于文件已匿名化。

线上路径的三个 artifact 边界不同：`leakcanary-object-watcher-android` 只提供候选计数（`retainedObjectCount`），没有引用链，5 秒观察窗口与生命周期噪声不能换算成线上泄漏发生率。`leakcanary-android-release` 提供生产环境 heap analysis API，但官方标为 experimental，触发、取消、脱敏、存储与上报都要自行设计。若确要上线，至少要有：服务端开关、低比例采样与单设备上限。只在后台或用户同意时触发。使用应用私有且不备份的临时目录、分析后删除 Hprof。优先上传分析结果而非原始 Hprof。dump 时长、失败率、文件大小超限时自动熔断停用。

因此默认策略应是分工：线上信号交给 KOOM、自研 APM 或 Android Vitals 回答“影响多大、在哪里发生”，LeakCanary 在可控的 Debug/QA 包里回答“哪条 Java 强引用需要修改”。LeakCanary 为第三方 SDK，未本地核对。
