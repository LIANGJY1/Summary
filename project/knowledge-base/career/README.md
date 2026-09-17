# Android Automotive Career Sprint

> 半年目标：以面试为牵引，补齐 Android Framework、AAOS 和车载系统工程能力，争取 Android 车机高级/资深开发岗位。

## 地图与使用顺序

| 文件/目录 | 用途 |
|---|---|
| [plans/](./plans/roadmaps/) | 总路线（方向、Gate、周主题、优先级、时间基线） |
| [rules.md](./weekly/rules.md) | 作答与批改规则（唯一落点，在 weekly/ 内） |
| [plans/market/](./plans/market/research.md) | 岗位研究：逐条 JD 明细、聚合页速览、横向结论、官方来源 |
| [weekly/](./weekly/) | 每日练习（`<周>/<日期>/qa.md`，含原题、原答案与批改）与每周记录（`<周>/record.md`） |
| [hands-on/](./hands-on/) | 限时手写题代码与追问 |
| [work-project-analysis/](./work-project-analysis/README.md) | 三个真实项目的事实分析（只读源码，入口在此） |

当前进度：**W1 启动周（W1-2026-0917：09-17 ~ 09-27，11 天）**。本周动作只看总路线的 W1 节；练习在 [weekly/W1-2026-0917/](./weekly/W1-2026-0917/)，不要从头通读本目录。

## 规则

- 五条轨道并行：Java/Kotlin、Framework、AAOS、源码/实践、面试/复习。优先级、阶段 Gate、每周配额和时间基线的唯一落点在[总路线](./plans/roadmaps/six-month-roadmap.md)，本文件不重复。
- 通用知识一律查父目录（见下表）；本目录只放面试化表达、项目证据、进度和复盘，不复制知识正文。
- 源码边界：`AAOS13_study` 是可修改的学习副本；三个真实项目目录只读。路径与读写规则的唯一落点在 [work-project-analysis/README.md](./work-project-analysis/README.md)。
- 证据措辞：区分"实际参与 / 代码观察 / 学习验证"，不能确认的一律降级，不包装成量产经验。

## Knowledge Base 对照

| 本计划主题 | 知识库入口 | 用法 |
|---|---|---|
| Java/Kotlin/C++ | [`language/`](../language/) | 查语言机制；面试卡只写自己的回答和源码锚点 |
| 设计权衡 | [`design-principles.md`](../design-principles.md) | 查可迁移的取舍，不复制到项目文档 |
| SDK/组件做法 | [`sdk-design.md`](../sdk-design.md) | 查可执行设计模式 |
| Android UI/Provider/Audio | [`android-ui.md`](../android-ui.md)、[`android-provider.md`](../android-provider.md)、[`android-audio.md`](../android-audio.md) | 查机制坑和边界 |
| 源码批注方法 | [`annotation-techniques.md`](../annotation-techniques.md) | 查如何做源码学习记录 |

新增可迁移结论前，先按 [`ROUTING.md`](../ROUTING.md) 选唯一落点；只对当前求职过程有用的内容留在 `career/`。
