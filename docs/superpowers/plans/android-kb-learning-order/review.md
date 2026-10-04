# Android 题库学习顺序重排审查记录

## 范围与基线

2026-10-04 执行前重新统计：15 个主题目录、134 篇题目文档、2291 道题。逐篇审阅标题、导语和全部 Q 标题后，仅按现存 01–15 主题册建立前置关系；此前 16–18 归并台账保留原样。本轮旧→新目录、文档与逐题 Q 映射分别见 `directory-map.tsv`、`document-map.tsv`、`mapping.tsv`。

## 目录与册序的关键决策

- 主干 01–05 与系统能力 06–09 连续保留；10–13 改为系统服务、原生层、构建系统、AAOS。CarService/VHAL 的理解依赖 Binder、系统服务接口、HAL、网络和音频；车载集成因而在平台主题之后。系统服务与原生层相邻。构建系统放在 AAOS 之前，让产品配置、模块及镜像对接有先备语境。
- 10-platform-services/05 aconfig 运行时需要 12-build-system/07 aconfig 声明和代码生成；这是跨专题的局部前置，README 明示跳读。10-platform-services/06 AVF 的 pKVM 部分可先读 11-platform-native/01 内核与 GKI。两处不以单题依赖打散主题目录。
- 01-architecture：Binder 移到 SystemServer 前，先掌握服务注册/事务模型再读服务装配。02-app-framework：Parcel 移到 ContentProvider 深入调用链前。
- 03-ui：07 驾驶安全之后为 08 View/Compose 基础实践 → 09 Compose 进阶实践 → 10 主题还原缺陷 → 11 UI 综合排查。08-network：传输协议前于应用网络约束，多 APN 前于依赖它的 VPN 共存题；应用实践前于网络诊断。
- 09-audio 的 06–12 点击音连续链不拆分、不交换：声音决策 → 应用焦点契约 → 焦点调用链 → AAOS 焦点矩阵 → 路由音量 → 播放/HAL → 实验排障。
- 13-aaos：车机端到端链路、CarService 和 VHAL 契约先于应用/Launcher；信号语义先于 Kanzi 同步；综合状态与启动缺陷最后。
- 15-performance：01 学习方法 → 02 性能方法论 → 03 工具 → 04 Perfetto 基础；05–14 为流畅性、响应、启动、ANR、内存、功耗、网络、平台优化、稳定性和崩溃专题；15 Perfetto 进阶 → 16–17 可观测性 → 18–19 APM。Perfetto 基础支撑专题中的 trace 证据，进阶分析放在案例经验之后；APM 以可观测性口径和采集架构为前置。

## 确需调整的题内前置

下表 Q 号均为**迁移前**编号；未列出的题保持相对顺序，答案、标题、状态、标签、证据和版本限定不改。

| 原册 | 前移题 | 前置理由 |
| --- | --- | --- |
| 01-architecture/02-system-boot | Q22 到 Q19 前 | 先说明 Zygote/fork 的角色，再读启动与预加载细节。 |
| 01-architecture/06-art-runtime | Q2–Q5 到末尾 | 编译、类加载与 GC 机制先于属性、oatdump 和诊断。 |
| 03-ui/01-activity | Q4 到 Q1 前；Q25 到 Q3 前 | 生命周期与实例选择先于首帧、启动标志案例。 |
| 03-ui/02-view | Q2 到 Q1 前 | View/ViewGroup 概念先于遍历链。 |
| 03-ui/03-resources | Q4 到 Q2 前；Q19 到 Q16 前 | Drawable 和 ViewBinding 的定义先于属性与跨变体字段问题。 |
| 03-ui/09-app-view-compose-practice | Q26 到 Q2 前 | 布局容器选型先于压平与优化。 |
| 05-rendering/01-render-pipeline-vsync | Q13 到 Q2 前 | VSync 语义先于 VSync→上屏全链路。 |
| 05-rendering/02-gpu-composition-display | Q12 到 Q7 前 | fence 语义先于 BufferQueue 的 release/dequeue 等待。 |
| 05-rendering/11-gpu-diagnostics-tools | Q8 到 Q6 前 | 构建类型与捕获前提先于 AGI 注入细节。 |
| 06-storage/02-storage-io | Q22–Q23 到 Q12 前 | SharedPreferences 读写/监听先于 apply、commit 与 QueuedWork。 |
| 06-storage/03-app-io-storage-practice | Q33–Q34、Q38–Q39 到 Q7 前；Q35–Q37 到 Q12 前 | SQLite 基础、事务先于 WAL/Room；greenDAO 接在数据库机制后、网络实践前。 |
| 08-network/06-multi-apn-veth | Q8 到 Q1 前 | 先界定车机联网物理形态，再读 APN/veth 拓扑。 |
| 12-platform-native/05-bionic-linker | Q10 到 Q3 前 | 先定义 soinfo，再读多处使用该对象的链接器流程。 |
| 13-build-system/02-soong-modules | Q10 到 Q2 前 | Soong/Make/Kati/Ninja 总图先于模块与 init 案例。 |
| 15-performance/01-smoothness | Q30 到 Q4 前 | 渲染 trace 的四个坐标先于路径归因。 |
| 15-performance/03-anr | Q29 到 Q1 前 | 多套独立计时器总览先于各类 ANR 超时契约。 |

## 归属与内容问题（本轮未迁题）

- `06-storage/03-app-io-storage-practice.md` 的原 Q12–Q14 和 Q23–Q32 涉及 JSON、HTTP、OkHttp、网络能力、gRPC、ECH 等，横跨网络实践；本轮只将迟到的 SQLite 基础提前。这些题的归属迁移并非建立本次阅读顺序的必要条件，因此记录供后续专题拆册审查。
- `15-performance/01-performance-learning-path.md` 的导语原有一处“性能方法论”标签指回本篇的自链接；本轮改成指向实际方法论册，并同步校正旧路径样式的链接标签。
- 高频索引在本轮前已有若干旧册号与内容摘要不一致（如启动、稳定性和 SELinux 分段）；本轮同步修正路径、题号和明显失配的索引描述。

## 核对结果

迁移以题块为单位。`mapping.tsv` 为 2291 条一对一关系；`verify.py` 对每条旧题从 Git 基线读取原块，和映射后的新块对比，除编号及路径引用外正文逐字一致。检查结果：15 个连续目录、134 篇连续前缀文档、2291 道连续编号题；逐题标题/正文差异 0；本范围 511 个相对链接均可达；面试索引 97 个显式 Q 引用在册内有效；现行资料中的旧文档路径残留 0；Android README 章节顺序和实际目录一致，音频 06–12 链完整；`git diff --check` 通过。
