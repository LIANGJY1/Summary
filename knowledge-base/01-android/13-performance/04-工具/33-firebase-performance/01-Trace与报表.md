# Firebase Performance

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q2 -->

**Q1: [learning] Firebase Performance 的 _app_start 从什么时刻计到什么时刻？为什么它不能当进程启动或首屏完整耗时用？**

`_app_start` 的起点是 Firebase 首个类的早期 class-load（类加载）近似时间，终点是第一个 Activity 的 `onResume()`。SDK 内部把这条 trace 记为 `_as`，计时使用单调时钟 `elapsedRealtime`（按设备启动后经过时间递增，不受修改系统时间影响）。它是“SDK 可见的启动区间”，不是进程启动或首屏耗时。

它漏掉的时间有两段：Firebase 初始化之前的全部进程时间（从 Zygote fork 到 SDK 早期初始化之间的工作）没有进入起点。第一帧和首屏内容可用也不在终点里——`onResume()` 之后页面可能还在加载数据。两个版本相关的边界：API 24 起源码会读取 `Process.getStartElapsedRealtime()`（AAOS13 的 `Process.java` 中存在该 API），但它只用于实验性 TTID trace 和 `process start → class load` 子区间，不是稳定 `_app_start` 的起点。SDK 会过滤后台触发的进程启动，`22.0.6` 起在 Android 14+ 通过 `ActivityManager.getMyMemoryState()`（AAOS13 同样存在）判断只有前台重要性才生成 `_app_start`。

因此 `_app_start` 适合比较版本趋势，不能回答“首屏何时可交互”。若启动完成的定义是骨架绘制、首批数据展示或可交互，需要另建 custom trace 覆盖该区间，并用 Macrobenchmark 或 Perfetto 校验各阶段。


<!-- 来源：05-observability/03-apm-platforms.md · Q3 -->

**Q2: [learning] Firebase 自动 screen trace 的 slow/frozen frame 阈值和汇总口径是什么？为什么 120 Hz 设备上的卡顿可能被低估？**

自动 screen trace 的单帧分类是固定 60 Hz 阈值：slow frame 耗时大于 16 ms，frozen frame 大于 700 ms（SDK `22.0.6` 的常量）。控制台的汇总百分比也不是“慢帧数除以总帧数”，而是 screen instance 占比——slow frame 超过该 screen instance 总帧数 50% 的 screen instance 占比，frozen frame 超过 0.1% 的占比。screen instance 指一次 Activity 或 Fragment 展示区间，不是一帧。

高刷新率设备被低估的原因在阈值本身：60 Hz 的刷新周期约 16.67 ms，90/120 Hz 缩短为约 11.11/8.33 ms。一帧即使错过了设备的真实刷新周期，也可能没有超过 16 ms，于是不被计入 slow frame。官方文档明确这是固定 60 Hz 口径，发布前应在真实 90/120 Hz 设备上评估偏差。

采集方式上还有两个边界：Activity 的窗口是 `onActivityStarted()` 到 `onActivityStopped()`，Fragment（SDK 20.1.0+）是 `onFragmentResumed()` 到 `onFragmentPaused()`，Fragment 不独立读取一条 FrameMetrics 流，而是从宿主 Activity 的 `Window` 收集后按生命周期区间取差值——Fragment 重叠或单 Activity + Compose 导航会让页面级结果难解释。自动 screen trace 不能附加 custom metric 与 attribute，需要按 route、滚动阶段归因时应改用 JankStats 记录状态。


<!-- 来源：05-observability/03-apm-platforms.md · Q4 -->

**Q3: [learning] 接入 Firebase Performance 时，为什么关掉运行时采集仍可能继续执行构建期插桩？两套开关分别怎么配？**

构建期与运行期是两套独立开关：Gradle 插件在构建期对受支持的网络库和 `@AddTrace` 做字节码插桩，运行时 SDK 在进程中记录、采样、暂存并上传——只关闭其中一套，另一套照常工作。经常出现的误会是用 Manifest 元数据关了采集，却让 debug 构建继续插桩。

- **构建期插桩**：`FirebasePerfExtension.setInstrumentationEnabled(false)` 关闭指定 variant（如 debug）的自动网络与 `@AddTrace` 插桩。Gradle property `firebasePerformanceInstrumentationEnabled=false` 对整次构建全局关闭，适合 CI 参数。
- **运行期采集**：Manifest 元数据 `firebase_performance_collection_enabled=false` 使应用默认不采集，之后可调 `setPerformanceCollectionEnabled(true)` 动态开启（例如用户同意隐私政策后）。`firebase_performance_collection_deactivated=true` 强制停用并覆盖 enabled，只有删除该元数据并重新发版才能恢复。

若只想停止采集与上传，无需关闭插桩。若 debug variant 不想携带插桩产物，才需要前者。多进程应用还有一条官方边界：只支持主进程中的 Performance Monitoring，独立 `:remote`、`:push` 等远程进程不会自动获得同等采集能力，需要观察这些进程时仍要保留应用自己的监控。接入清单还应覆盖插件分工——`com.google.gms.google-services` 处理 `google-services.json`，BoM 只管理 Firebase 库版本、不管理 Gradle plugin 版本。


<!-- 来源：05-observability/03-apm-platforms.md · Q5 -->

**Q4: [learning] Firebase Performance 记录到的事件为什么不一定出现在控制台？采样、限流、时效与告警门槛各怎么影响解读？**

端侧采样、设备限流、批量上传和服务端处理都会减少最终样本，控制台分布只代表“被捕获并被接受的事件”。官方 troubleshooting 的限流口径是：code trace 与 network request trace 合计每台设备每 10 分钟最多发送 300 个事件。SDK 还会通过 Remote Config 获取动态采样率随机选择设备。启用 BigQuery 集成会提高 network trace 上限，但仍不是全量。

各环节的口径：

1. **时效：**SDK `19.0.10+`（BoM `26.1.0+`）约每 30 秒批量发送，控制台通常几分钟内可见——“几分钟”不是严格实时 SLA，离线、初始化失败和服务端处理都会继续推迟。旧 SDK 约 36 小时延迟。
2. **告警门槛：**App start、custom trace、network 与 screen rendering 告警需要过去一小时至少 100 个样本，低流量版本“没有告警”不等于“没有问题”。
3. **BigQuery：**导出对象是 captured events（已被采集并接受的事件），首次启用传播最多 48 小时。它补分析能力，不取消端侧采样。

落地判断：高频轮询和图片请求不会保证逐条保留。小流量分阶段发布可能因样本不足看不出变化。看板结论要同时携带采样率与限流规则。排查采集是否生效时，可在测试构建临时设置 `firebase_performance_logcat_enabled=true` 看 Logcat 的 `FirebasePerformance` 日志，验证后移除。


<!-- 来源：04-reliability/02-stability-and-crash.md · Q4 -->

**Q5: [learning] Crashlytics 的 Crash-Free Users 与 Crash-Free Sessions 怎么定义？解读时有哪些陷阱？**

Crashlytics 官方《Understand crash-free metrics》把 user 定义为设备上的单个应用安装，把 session 定义为连续使用时段；两个指标只纳入 fatal 事件。Crash-Free Users 为观察期内未发生 fatal 的安装实例比例，Crash-Free Sessions 为未以 fatal 结束的会话比例。它们分别回答“用户观察期内是否遇到崩溃”与“一次会话能否完成”。

解读陷阱：

1. **实体定义**：一个人在两台设备安装应用计为两个 user。按业务账号去重的自建系统与 Crashlytics 的“用户”不是同一实体，不能互相比对。
2. **观察期**：观察期越长，同一实例遇到至少一次崩溃的机会越大，1 天与 28 天的数值不能直接比较。
3. **事件范围**：non-fatal（已捕获并主动上报的错误）与 ANR 过滤条件不进入 Crash-Free 图表，只看这张图会低估整体故障。
4. **会话边界**：Crashlytics 当前把冷启动视为新会话、应用进入后台至少 30 分钟后回前台也开始新会话。这是产品规则，自建指标可以采用别的边界，但要在名字中标明内部口径。
5. **公式不能改写**：Crash-Free Sessions 不能写成 `1 - 崩溃事件数 ÷ 会话数`——同一受影响会话内多条 fatal 记录只计一次。
6. **不能由 Play 阈值反推**：Play 全机型 user-perceived crash rate 阈值为 1.09%，但两边故障范围、用户定义与聚合方式均不同，`100% − 1.09%` 推不出 Crashlytics 的发布线。
