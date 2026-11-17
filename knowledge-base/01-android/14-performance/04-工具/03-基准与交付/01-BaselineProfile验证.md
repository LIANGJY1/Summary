# Baseline Profile 与 Startup Profile 工具验证

本组聚焦 Profile 生成、构建产物检查和设备编译状态验证。

<!-- 来源：01-user-experience/02-app-startup.md · Q29 -->

**Q1: [learning] 怎样验证 Profile 的启动收益，为什么不能相加不同案例的百分比？**

Profile 收益必须在相同设备、构建、启动类型与采集协议下实测；不同产品、不同优化阶段的百分比不能相加或相乘。Baseline Profile 的 ART AOT 编译、Startup Profile 的构建期 DEX 布局和 Cloud Profile 的 Play 分发改变的环节不同，官方经验值不能替代目标应用的测量。

验证用 Macrobenchmark 同机对照 `CompilationMode.None()` 与 `CompilationMode.Partial(baselineProfileMode = Require)`（Require 会在 profile 缺失时暴露配置错误），固定 startup mode、构建与数据。R8 优化后 profile 需被正确重写（Gmail Wear OS 案例中该能力要求 AGP 8.2+）。

案例方法教训：Reddit 用页面级 CUJ 生成 Profile 并接入 CI（首个 Feed 启动中位数降 51% 是特定路径口径，全局冷启动 20% 是 Profile、R8 与 UI 演进的整体结果，两者不能相加）。Gmail Wear OS 用 Perfetto 区分主线程 Running/Runnable、DEX 读取、Binder reply 与 JIT（把加载动画换成静态图改善 50% 与 profile 重写改善 20% 来自不同实验，不能相乘相加）。

Disney+ 把 `proguard-android.txt`（含 `-dontoptimize`）换成 `proguard-android-optimize.txt` 后启动加快 30%、user-perceived ANR 减少 25%，但相关性与机制证明要分开。Profile 只优化代码执行与 DEX 布局，同步网络、慢 Binder、布局与 GPU 瓶颈仍需单独处理。

补充边界：

生成只产出热点方法与类的清单，文件存在不证明它在该 APK 与设备上生效——收益要靠成对的编译状态对照实验证明，两者环境要求也不同。

生成侧：`BaselineProfileRule.collect()` 在非 root 设备需要 API 33+、rooted 设备需要 API 28+，这只是 profile 收集门槛。`includeInStartupProfile = true` 把启动入口到 fully drawn 的路径纳入启动 profile，普通滚动或二级页面旅程应另建 `collect()` 并保持 `false`。生成后不是一劳永逸：关键用户旅程变化、R8 mapping 变化、启动依赖调整和大版本升级都要求重新生成。

验证侧要求成对运行两个 `CompilationMode`：`None()` 清除预编译作为对照，`Partial(BaselineProfileMode.Require)` 要求 APK 内 Baseline Profile 可安装并以 `speed-profile` 编译、装不上直接失败——不能用 `DEFAULT`（等价于 `Partial(UseIfAvailable)`，profile 缺失时不失败）代替严格验证。两组必须使用相同 APK、fixture、设备与迭代数。`Partial(Require)` 需要 API 24+（API 23 只有 `Full()`），其编译动作在平台侧走 `pm compile` 的 `speed-profile` 路径（AAOS13 已核对）。补充证据可用 `ArtMetric`（API 24+）观察启动期 JIT、类加载与校验工作是否减少，再配合启动/滚动指标确认端到端改善。

<!-- 来源：01-user-experience/02-app-startup.md · Q30 -->

**Q2: [learning] 请求了 speed-profile 就一定得到 speed-profile 产物吗，怎么确认设备实际编译状态？**

不一定。`speed-profile` 表示“只 AOT 编译有效 profile 选中的方法”，不代表整个应用已 AOT。找不到有效 profile 时，实际 filter 会退为 `verify`，所以排查必须同时记录 requested filter（请求值）与 actual filter（实际值）。

Android 13 用 `dumpsys package dexopt` 查看（本地核对 PMS 侧存在 `dumpDexoptState`），输出里 `status=speed-profile` 表示存在 profile 指导的编译产物，`reason=install-dm`/`bg-dexopt`/`cmdline` 说明触发原因。Android 14+ 首选 `pm art dump`（AAOS13 的 `pm` 无 `art` 子命令，本地核对）。验证外部 profile 能否被接受：先清理本地采集的 profile；Android 14+ 用 `pm art clear-app-profiles`，Android 13 使用对应 `cmd package` 能力。然后运行 `pm compile -m speed-profile -f -v`：输出 `actualCompilerFilter=speed-profile` 说明找到有效 profile，`=verify` 说明没有（文件名错误、格式错误或 DEX checksum 不匹配）。

版本差异：Android 13 的安装期 dexopt 仍由 PMS 经 installd 执行（本地核对 `frameworks/native/cmds/installd/dexopt.cpp`），PackageManager → ART Service → artd → dex2oat 的链路自 Android 14 起。边界：Play 的 Baseline Profile 编译可能发生在后台设备更新，安装命令返回不代表已完成。`pm compile --reset` 与“卸载重装”不等价，实验记录要写清用了哪条命令。

<!-- 来源：01-user-experience/02-app-startup.md · Q31 -->

**Q3: [learning] .dm 文件是什么、不等同于什么，ART 接受一个外部 profile 要满足什么条件？**

`.dm` 是 Dex Metadata 容器，与某个 APK 一一对应的 ZIP 文件，可以携带 profile、VDEX 与配置——它不等同于 Cloud Profile，是否来自 Play、包含什么要结合安装来源与文件内容判断。ART 接受它要过三道关：文件名与 APK 配对（`base.apk` 配同目录 `base.dm`，split APK 各自对应，base 的 `.dm` 不能替代 split 的）。能作为 ZIP 归档打开。profile 格式正确且 DEX checksum 与当前 APK 匹配，否则拒绝。

安装期与后续 dexopt 是两个时机：`PackageManager.INSTALL_IGNORE_DEXOPT_PROFILE` 只让安装期忽略 `.dm` 与 APK 内嵌 profile，之后后台 dexopt 或 `pm compile` 仍可使用 profile——profile 缺失通常只损失优化，不改变代码语义。版本差异：Android 13 的安装期 profile 消费在 PMS/installd 链路。从 Android 14 起 ART Service 统一选择 profile 与 filter。

边界：APK 内嵌 Baseline Profile、相邻的 `<apk-name>.prof` 与 `.dm` 都属于 ART 可选的外部输入，`actualCompilerFilter` 区分不了来源，要区分必须控制安装产物。“设备能消费外部 profile”与"这个 `.dm` 是否来自 Play"是两个问题。

<!-- 来源：01-user-experience/02-app-startup.md · Q32 -->

**Q4: [learning] Startup Profile 的 DEX layout 收益怎么验证，“多了一个 classes.dex”是回归吗？**

Startup Profile 在构建期被 R8/D8 消费，APK/AAB 中没有供 ART 使用的独立 `startup.prof`，验证必须沿构建产物逐层走：AGP 8.8+ 检查 AAB 的 `r8.json` 构建元数据中至少一个 DEX 带 `"startup": true`，并把其中 checksum 与包内对应 DEX 的 SHA-256 对齐——不一致说明 R8 之后有加固、插桩或重打包悄悄改写了 DEX，要先修复链路再谈收益。用 APK Analyzer 确认主要启动类实际位于哪个 DEX。再用同编译模式 Macrobenchmark 比较 TTID/TTFD 与缺页。

单 DEX 应用因布局优化变成两个 DEX 不一定是回归：构建工具可能把启动代码集中到主 DEX、把非启动代码移到后续 DEX，判断依据是启动类分布、R8 诊断与测量结果。两个边界：规则过宽会让非首屏 CUJ 挤占主 DEX 容量、必要启动代码反而溢出，应缩小到初始显示路径。A/B 必须单变量——只改变是否提供 Startup Profile，Baseline Profile、R8 规则、签名、安装步骤与设备端 `CompilationMode` 保持一致，否则 ART 编译与 DEX 布局的贡献无法区分。杀进程冷启动不代表 page cache 冷态，首轮结果不能直接解释成稳定的 DEX I/O 收益。
