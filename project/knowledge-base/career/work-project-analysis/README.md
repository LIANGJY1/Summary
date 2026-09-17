# Work Project Analysis

本目录只记录真实工作项目的架构、模块、问题、决策和个人经验。禁止放入通用 Android 知识、AOSP 学习笔记、个人 Demo 或纯第三方库分析。

## 项目规则

- 外部源码默认只读：不修改源码、配置、构建脚本、生成物或提交历史；学习验证和实验代码写入 `AAOS13_study` 或独立 Demo。
- 每条结论标明证据类型：实际参与 / 代码观察 / 源码推断。
- 不记录密钥、内网地址、客户信息和不可公开的业务细节。
- 每个项目一个 `<项目名>.md`，只写"项目背景 → 模块地图 → 主链路 → 关键问题 → 个人职责 → 面试表达"。

## 项目索引

| 项目 | 源码路径 | 权限 | 分析入口 |
|---|---|---|---|
| HC 27M | `/home/liang/Project/Reachauto/HC/27M` | 只读 | [hc-27m.md](./hc-27m.md) |
| YaDi Android | `/home/liang/Project/Reachauto/YaDi/yadi_android` | 只读 | [yadi-android.md](./yadi-android.md) |
| Yadea Master | `/home/liang/Project/Reachauto/YaDi/yadea_master` | 只读 | [yadea-master.md](./yadea-master.md) |

## 与其他目录的边界

- `AAOS13_study`：可修改的 AAOS 13 学习副本，不属于本目录。
- `project/knowledge-base/career/`：求职计划和面试材料，只引用本目录的项目事实；计划管理见 `career/plans/`。
- `project/project-architecture`：保留既有通用库和历史架构文档；新工作项目分析优先写入本目录。
