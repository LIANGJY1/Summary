# Android Bench 代码代理评测

> 学习资料（文章模式沉淀）。本文解释 Android Bench 的任务构造、复现实验与分数解读；它评估代码代理处理 Android 开发任务的能力，不是应用运行时性能基准。原始版本和资料锚点保留在各题中。Q 序列即结构。

**Q1: [learning] Google Android Bench 的任务是怎么构建出来的？它的分数能证明什么、不能证明什么？**

Android Bench 评估代码代理在给定 Android 仓库、issue 和验收条件下生成 patch 的成功率。每个任务固定 base commit，verifier 应用 patch、构建并运行测试。当前 100 个任务的构造按顺序经过：

1. **候选筛选：**从 38,989 个 PR 的候选池寻找含 Android app/library 代码、至少 500 stars、已合并且修复 issue、带单元或 instrumentation 测试、近三年变更的仓库任务。
2. **人工补足与复核：**QA 检查构建、测试和描述是否足够，Android 专家检查复杂度与相关性。官方组成约 88% 来自 GitHub PR、12% 为专家补充。
3. **可验收条件：**测试在 base 上失败、在 canonical patch 上通过，且不依赖易抖动的 UI timing。

分数只支持“给定这些输入与测试时 patch 通过”的结论，不自动证明从零建产品、长期维护、架构取舍、代码可读性、安全性、功耗或运行性能。通过测试也可能命中盲区，仍需审计成功 trajectory。数据集偏向 library 仓库，不代表 Android 项目自然分布。

**Q2: [learning] 复现一次 Android Bench 实验要记录什么？为什么换了执行框架，同样的模型分数会变？**

公开分数对应"model + endpoint + agent + prompt + tools + budget + dataset + verifier"一整套配置，换任何一环都可能改变结果。执行框架已经换过：2026 年 3 月首版用 mini-swe-agent v1，模型以 Markdown code block 输出 shell 命令、由正则提取执行。当前 methodology 用 Harbor 加 mini-swe-agent v2，走 provider API 的 native tool calling 并加入 Android 领域的系统指令——v2 只执行通过工具 API 提交的 bash 调用，沿用 v1 的 prompt 会让命令根本不执行，因此 system instruction 与 tool schema 都随版本更新。旧仓库已归档、数据集迁移到 Harbor Hub，跨版本数字不能直接比较。

复现记录按执行链保存：

1. **数据：**dataset 名称、版本或 digest。
2. **代理框架：**harness commit、mini-swe-agent 版本、system prompt 与 tool schema。
3. **模型与端点：**provider、完整 model ID、endpoint 和运行日期。
4. **预算与随机性：**temperature/seed、轮数、时间与费用预算。
5. **验证环境：**Docker image digest、JDK、Android SDK、AGP、Gradle 与 KVM 配置。

只写模型名称无法复现，框架版本、prompt、工具调用和预算都会改变得分。

**Q3: [learning] Android Bench 的 verifier 状态有哪些？读 pass@1 和公开排行榜时最容易犯什么错？**

归档版的 `PatchScore` 只有 0.0 与 1.0，`Status` 需另行保留：

1. **通过：**`PASSED` 与重试后通过的 `PASSED_FLAKY` 均记 1.0。
2. **代理失败：**`AGENT_NO_PATCH`、`AGENT_FAILED_TO_APPLY_PATCH`、`AGENT_FAILED_BUILD`、`AGENT_FAILED_TEST`、`AGENT_MISSING_REQUIRED_TEST_RESULTS`、`AGENT_FAILED_VALIDATION` 记 0.0。
3. **基础设施失败：**`INFRA_FAILURE*` 覆盖初始化、模拟器、provider API、输出格式、超时与预算等执行问题，不应暗中混入代理失败分母。

汇总程序不自动定义“可评任务”分母。报告要给 scheduled、attempted、evaluable、passed、infra failure 与 excluded 数量；是否重跑基础设施错误须预先写进协议。

读公开结果时避免四种误读：

1. **单次通过率当稳定能力：**pass@1 的分母要公开，重复运行可按任务成功比例估计。首版用分层 bootstrap 算 95% 置信区间，约只能检测 10 个百分点的绝对差异。
2. **直接比较资源数：**cost 随定价、token 随 provider、latency 含网络且失败早的任务消耗少；只在通过率接近的配置间比较，并按成功/失败拆延迟。
3. **小分类排名：**100 题再按 Compose 或 library 分组后样本很少，几分差距不足以证明专项能力。
4. **旧榜当长期结论：**首版 2026-03 的完成率约 16%–72%，模型、框架与数据集均会变化，引用须绑定版本。
