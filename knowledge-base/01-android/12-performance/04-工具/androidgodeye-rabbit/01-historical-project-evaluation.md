# AndroidGodEye 与 Rabbit

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q24 -->

**Q1: [learning] 评估 AndroidGodEye、Rabbit 这类历史开源 APM 要看哪四个维度？把它们的旧能力迁到现代 Android 时怎么映射？**

四个维度决定“仓库里有这个功能”是否等于“能在现代工程里用”：信号语义（采到的是系统定义指标还是 SDK 自推近似值）、运行开销（是否在主线程反射、抓栈、主动 GC）、构建兼容性（Gradle 插件是否依赖已删除的 Transform API 或 AGP 内部类）、维护证据（固定 commit、compileSdk/targetSdk 与发布记录）。AndroidGodEye 的分层与浏览器看板值得借鉴但其构建链停在 AGP 3.2.1。Rabbit 的统一研发入口有价值，但它的插件直接调用 `registerTransform()` 与 `com.android.build.api.transform.*`——AGP 8.0 删除该 API 后没有兼容入口。

迁移映射按能力对号入座：

1. **固定 16 ms 的 FPS/掉帧** → JankStats 或 FrameMetrics，处理可变刷新率与回调复用。
2. **window focus 启动耗时** → `ApplicationStartInfo`（API 35+，AAOS13 无此 API）与应用主动调用 `reportFullyDrawn()`，区分 TTID 与 TTFD。
3. **5 秒 Looper "ANR"** → 命名为主线程 stall 并配 `ApplicationExitInfo`（API 30+，AAOS13 已核对该 API 与 reason 常量。API 34 新增 `REASON_PACKAGE_STATE_CHANGE/UPDATED` 不在 AAOS13，API 37 的 ANR warning 需用 `SDK_INT_FULL` 区分次版本）。
4. **UID RX 流量** → 网络层 interceptor，补 TX、阶段耗时与脱敏。
5. **主动 GC 的泄漏提示** → LeakCanary 对象可达性分析或受控 heap dump。
6. **旧 Transform 插桩** → Instrumentation API（逐类改写）或 ScopedArtifacts（需读全工程 class 时），回归插桩范围、增量构建与 R8 mapping 关联。

默认动作是阅读和移植需要的设计，而不是把旧 artifact 一次接入——旧项目没有现代验证报告时，接入决定必须由自己的回归测试支撑。以上项目均为第三方 SDK，未本地核对。
