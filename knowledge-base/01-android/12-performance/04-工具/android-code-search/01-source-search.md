# Android Code Search

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q37 -->

**Q1: [learning] Android Code Search 怎么用才有效？为什么“搜索命中”不等于“执行证据”？**

先固定版本再打开文件：引用时复制“当前 revision + 行号”的链接（行号会随提交漂移，tag 和路径才是长期复核凭证），`HEAD` 链接只用于跟进开发中代码。搜索用官方过滤器：

- `file:` 限定文件；
- `content:` 搜内容（日志反查入口，双引号表示逐字匹配）；
- `function:` 与 `symbol:` 缩小到函数与符号；
- `usage:` 排除注释与字符串；
- `lang:` 限定语言。

快捷键 `x` 打开交叉引用查看定义与引用。

交叉引用只提供候选调用关系，需要人工判断：虚方法调用指向静态类型，运行时对象可能是子类。反射、JNI、Binder、消息分发和回调会中断普通调用层次。宏展开、生成代码、条件编译和产品变体可能改变入口。引用列表表达语法关系，不代表设备运行时一定执行。文件存在也不等于编进产品：`Android.bp`（Soong 模块配置）根据模块、依赖、产品变量和 feature flag 选择源文件，要确认模块编进哪个分区、APK、JAR 或 APEX，以及 AIDL、proto、aconfig、sysprop 等生成代码的来源。遇到公开 API 只剩转发逻辑时，按 import 与 Binder 接口 → 模块的 `Android.bp` → `packages/modules/`（Mainline 模块源码）→ APEX 或 APK 的进程归属继续追。分工是：Code Search 与文本搜索定位候选，构建图确认“编了谁”，trace 与调试确认“跑了谁”。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q2 -->

**Q2: [learning] Android 性能分析为什么需要分别固定平台和内核源码版本？**

平台与内核分属不同仓库、演进节奏不同；只记录“Android 版本”不足以复核调度、Binder、回收或文件系统路径。分析时分别固定 AOSP tag（例如 `android-17.0.0_r1`）与 ACK（Android Common Kernel）tag，进入相应机制后只引用对应 tag 的实现。旧文章的操作步骤、阈值和工具界面需先与目标版本对照，长期有效的观测思路才可迁移。

商用设备还要记录 build fingerprint、API level、内核版本、SoC、刷新率、电源模式与温控状态。厂商可能修改 Framework、HAL、内核配置和性能策略，因此 AOSP 是公共机制参照系，目标设备上的 trace、配置和符号才是执行证据。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q36 -->

**Q3: [learning] 阅读 AOSP 源码前要固定哪三个坐标？为什么“我看了 AOSP 最新代码”无法复核？**

三个坐标是：

1. **平台版本：**发布 tag，如材料使用的 `android-17.0.0_r1`。
2. **Git 项目：**如 `platform/frameworks/base` 或 `platform/frameworks/native`。
3. **项目内路径：**如 `core/java/android/view/Choreographer.java`。

AOSP checkout 由 Repo 管理的多个 Git 项目组成，manifest 记录项目、路径和 revision。`main`、`android-latest-release`、发布 tag 和厂商分支早已分叉，只写“最新代码”无法定位到任何一份可复核的源码。

阅读时还要保留一份运行现场：build fingerprint、`ro.build.version.sdk` 与厂商版本、进程线程与时间窗、logcat 或 Perfetto trace、复现条件、源码坐标。这份记录防止两类误判：用新版源码解释旧设备行为，或用 AOSP 行为代替厂商实现——厂商可以修改 Framework、SurfaceFlinger、HAL 和内核，AOSP 只是参照系，设备证据决定现场是否走了同一条路径。源码阅读由具体运行证据驱动（日志、trace 或 Binder 调用先于代码搜索），一次分析的完成条件是证据能解释当前行为并明确仍未确认的边界，不要求预先通读整个 AOSP。
