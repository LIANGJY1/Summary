# Matrix

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q11 -->

**Q1: [learning] Matrix 2.1.0 的 Gradle 插件为什么在 AGP 8+ 工程无法工作？评估 Matrix 时运行时模块和构建插件为什么要拆开？**

AGP 8.0 删除了整个 `com.android.build.api.transform` 包，而 Matrix 2.1.0 的构建插件仍继承 `Transform`、把 `android` extension 强转为旧 `AppExtension`，并依赖 `BaseVariant`、`DexArchiveBuilderTask` 等未承诺兼容的内部 API——切换到 task injection 也不足以支持 AGP 8，官方仓库没有迁移到新 Instrumentation API 的实现。官方 README 只声明插件可配合 AGP 3.5.0/4.0.0/4.1.0。Maven Central 的 `matrix-android-lib` 最新正式版停在 2.1.0（2023-03），公开维护明显放缓。

运行时模块与构建插件拆开评估的原因是它们的风险面不同：IO Canary、Resource Canary 等运行时模块不经过 Transform，可以在现代工程中单独引入，但要逐项核对——IO Canary 通过 PLT hook 代理指定 Java 运行库的 `open/read/write/close` 且只在主线程进入收集器，覆盖范围小于“全进程 I/O 审计”。Resource Canary 以 Activity 弱引用重检为中心，Fragment/View 引用要靠 LeakCanary 补足。所有带 native 代码的模块都要检查 2023 年预编译 `.so` 的 16 KB ELF 对齐。构建插件则要自行移植到 Instrumentation API 或采用持续维护的 fork，移植时必须保留类过滤、方法 ID 分配与 `methodMapping.txt` 的写入语义——方法 ID 不天然跨构建稳定，未正确使用 `baseMethodMapFile` 时同一方法下次构建可能换 ID。

运行时接入顺序本身简单但有序：`Matrix.Builder` 上 `plugin()` 注册插件、`pluginListener()` 接收 `Issue`，`Matrix.init()` 安装进程内单例后 `startAllPlugins()`。未注册的插件实例不会进入插件集合，`getPluginByClass()` 也找不到它。多进程应用不要在每个进程照搬同一配置，还要分别记录“未安装、安装失败、已停止”三种状态。Matrix 为第三方 SDK，未本地核对。
