# Android 性能知识分类重构设计

## 目标

修正七篇长文合并造成的主题过载。保留 549 道知识题，按问题域建立 7 个分类目录和 22 篇专题文章；用户可先按症状找到大类，再进入边界明确的文章。

分类依据与官方来源见 [`research/android-performance-taxonomy.md`](../../../research/android-performance-taxonomy.md)。Android 官方按启动、渲染、稳定性、内存、电量与后台工作、应用体积等问题域组织性能知识；测量、Profiler、Perfetto 和线上 APM 是横切方法或治理能力。

## 目录结构与边界

| 分类目录 | 篇数 | 题数 | 内容边界 |
| --- | ---: | ---: | --- |
| `00-methods-and-tools/` | 2 | 85 | 排查闭环、实验测量、Profiler 与常用工具 |
| `01-user-experience/` | 2 | 76 | 帧流畅度、交互响应、应用启动 |
| `02-tracing/` | 3 | 81 | Perfetto 采集与分析、应用内运行时诊断 |
| `03-resource-domains/` | 7 | 123 | 内存、网络、平台性能、功耗测量、CPU 调度、应用能效和功耗治理 |
| `04-reliability/` | 2 | 61 | ANR、崩溃、进程退出和稳定性治理 |
| `05-observability/` | 4 | 97 | 线上质量信号、诊断、APM 产品能力与 SDK 架构 |
| `06-app-optimization/` | 2 | 26 | 应用 CPU 热点、包体与交付优化 |

Perfetto 独立成组是因为现有材料覆盖采集、SQL、调度、内存、帧和应用内 profile 多类分析；各问题域保留自己的症状和优化文章，通过 README 与必要的内部链接相连。资源域是导航大类，不代表各资源主题可合成一篇。

## 验收结果

1. 七个分类目录、22 篇专题文章，总题数 549。
2. 每篇 Q 从 Q1 连续编号；跨文档索引已更新。
3. 旧的七篇长文和被替换源文件不再保留重复内容。
4. 性能 README 和 Android 总 README 展示阅读路径、分类边界和题数；高频面试索引使用新的文件名与 Q 编号。
5. 相对链接校验通过，`git diff --check` 通过。
