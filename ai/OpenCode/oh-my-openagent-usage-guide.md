# Oh My OpenAgent 使用手册

本文是可复用的操作手册，重点放在编排、工作流、交接、循环执行和专家工具的使用方式。环境相关的配置快照放在文末附录，方便把这份文档直接迁移到别的环境。

`oh-my-opencode` 是安装包和安装命令名，`oh-my-openagent` 是 OpenCode 插件名。本文统一使用这两个官方名称，不混用别名。

## 1. 这套系统负责什么

Oh My OpenAgent 不是一个新模型，也不是单纯的聊天壳。它把 OpenCode 变成一套可协作的开发编排层，核心目标是把任务拆开、分派出去、并行推进、持续验证，直到收尾。

它最适合处理这些事情：

- 先规划，再执行的复杂任务
- 需要代码搜索、文档检索、架构判断的任务
- 需要多代理并行工作的任务
- 需要交接、续跑、长期维护的任务

## 2. 术语约定

为了避免混乱，本文统一这样写：

| 名称       | 写法                                     | 说明           |
| -------- | -------------------------------------- | ------------ |
| package  | `oh-my-opencode`                       | 安装包和安装命令名    |
| plugin   | `oh-my-openagent`                      | OpenCode 插件名 |
| agent    | `Sisyphus`、`Prometheus`、`Oracle`       | 代理名使用首字母大写   |
| category | `deep`、`quick`、`writing`               | 类别名使用原始代码名   |
| skill    | `playwright`、`git-master`              | 技能名使用原始代码名   |
| command  | `/start-work`、`/handoff`               | 命令名保留斜杠      |
| config   | `opencode.json`、`oh-my-openagent.json` | 配置文件名保持原样    |

## 3. 先选对入口

### 3.1 直接做

适合小改动、单文件修改、明确目标的任务。直接描述结果就行。

### 3.2 `ulw`

适合复杂但不想自己拆步骤的任务。它会更主动地探索、分派和验证。

### 3.3 `@plan` 加 `/start-work`

适合影响面大、正确性要求高、需要先把方案定稳的任务。

流程通常是：

1. 用 `@plan` 说明目标
2. 回答澄清问题
3. 让系统产出计划
4. 用 `/start-work` 进入执行

### 3.4 直接点名专家代理

适合已经知道该找谁的时候。

- `@oracle` 用来做判断、审查、根因分析
- `@explore` 用来找代码路径、入口、调用链
- `@librarian` 用来查官方文档和参考实现

### 3.5 `/handoff`

适合切会话、切窗口、切设备、把任务交给别人继续。

## 4. 核心工作循环

一套稳的工作循环是这样的：

1. 先确认目标、范围、限制、验收标准
2. 选对 agent、category、skill
3. 让搜索、文档、实现、验证并行跑
4. 把结果收拢成一个结论
5. 需要继续时交接，不要断在半路
6. 结束前再做一次验收，确认改动和目标一致

这套系统的关键不是“多叫几个名字”，而是把岗位分开：

- `Sisyphus` 负责总编排
- `Prometheus` 负责先问清楚和出计划
- `Atlas` 负责执行计划和推进闭环
- `Hephaestus` 负责深度自主执行
- `Oracle` 负责架构和调试判断
- `Explore` 负责找代码
- `Librarian` 负责找资料

## 5. 专家代理怎么分工

| 代理                  | 角色      | 适合场景              |
| ------------------- | ------- | ----------------- |
| `Sisyphus`          | 总编排代理   | 大多数复杂任务的入口        |
| `Prometheus`        | 规划代理    | 先问清楚，再开始干活        |
| `Atlas`             | 执行调度代理  | 按计划推进和收束任务        |
| `Hephaestus`        | 深度执行代理  | 复杂调试、跨文件修改、独立推进   |
| `Metis`             | 计划漏洞检查  | 检查计划是否漏边界和验收      |
| `Momus`             | 计划审查    | 高标准审核计划质量         |
| `Oracle`            | 架构和调试顾问 | 设计评审、根因分析、风险判断    |
| `Explore`           | 代码勘探员   | 快速定位入口、接口和调用链     |
| `Librarian`         | 资料研究员   | 官方文档、库用法、参考实现     |
| `Multimodal-Looker` | 视觉分析代理  | 截图、图片、PDF、UI 视觉问题 |
| `Sisyphus-Junior`   | 执行代理    | 接受委派后实际完成任务       |

## 6. 类别、技能、命令

### 6.1 常见类别

| 类别                   | 作用         |
| -------------------- | ---------- |
| `visual-engineering` | 前端、UI、视觉实现 |
| `ultrabrain`         | 高强度推理和复杂决策 |
| `deep`               | 深度自主执行     |
| `artistry`           | 创意型输出      |
| `quick`              | 快速小任务      |
| `unspecified-low`    | 通用低强度任务    |
| `unspecified-high`   | 通用高强度任务    |
| `writing`            | 文档和写作      |

### 6.2 常见技能

| 技能               | 作用           |
| ---------------- | ------------ |
| `git-master`     | 高质量 git 工作流  |
| `playwright`     | 浏览器自动化和回归验证  |
| `playwright-cli` | CLI 风格浏览器自动化 |
| `agent-browser`  | 另一套浏览器自动化方案  |
| `dev-browser`    | 有状态浏览器自动化    |
| `frontend-ui-ux` | 提升前端实现和视觉质量  |

### 6.3 常见命令

| 命令                   | 作用               |
| -------------------- | ---------------- |
| `/init-deep`         | 生成分层 `AGENTS.md` |
| `/start-work`        | 从计划进入执行          |
| `/refactor`          | 智能重构工作流          |
| `/handoff`           | 生成交接内容           |
| `/ulw-loop`          | 持续推进复杂任务         |
| `/ralph-loop`        | 自我循环执行直到完成       |
| `/cancel-ralph`      | 取消循环             |
| `/stop-continuation` | 停止继续推进机制         |

## 7. 背景代理和工具链

当任务被拆成多个子问题时，优先让它们并行：

- 一个代理查代码
- 一个代理查文档
- 一个代理做实现
- 一个代理做验证

常见工具链包括：

- `background_output` 和 `background_cancel`，用来管理后台任务
- `call_omo_agent`，用来派出专门代理
- `task` 系列，适合可持久化的任务管理
- `lsp_*`，适合 rename、跳定义、找引用和诊断
- `ast_grep_*`，适合结构化搜索和批量替换
- `skill_mcp`，适合调用技能绑定的 MCP 能力

## 8. 推荐工作流

### 8.1 简单任务

直接描述目标、限制和验收标准，别先堆细节。

### 8.2 复杂任务

优先顺序通常是：

1. `ulw`
2. `@plan` + `/start-work`
3. `Hephaestus`

### 8.3 架构和根因分析

优先用 `@oracle`。需要更深的独立推理时，再切到 `Hephaestus`。

### 8.4 前端和视觉任务

建议组合：`visual-engineering` + `frontend-ui-ux` + `playwright`。

### 8.5 文档任务

建议组合：`writing` + `@explore` 或 `@librarian`。

### 8.6 长期项目

长期项目先 `/init-deep`，把项目约定写进 `AGENTS.md`，再用 `/handoff` 保持上下文可续跑。

## 9. 实用准则

- 先说目标，再说范围
- 先找，再查，再改
- 把验证写进提示词
- 大任务先计划，再执行
- 不确定时优先让专家代理做判断
- 需要交接时及时收口，不要把半成品丢给下一轮

## 10. 实践场景

- **改一个前端问题**：用 `visual-engineering` + `frontend-ui-ux` + `playwright`，先确认现象，再让代理实现并回归验证。
- **查一个陌生库**：先 `@librarian` 找官方文档和示例，再让执行代理落地。
- **复杂 bug 修复**：先 `@oracle` 判断根因和风险，再交给 `Hephaestus` 做最小修复。
- **跨文件重构**：先 `@plan` 产出拆分方案，再用 `/start-work` 逐步推进，避免一次改太多。
- **长期任务续跑**：先 `/init-deep` 固化仓库约定，再用 `/handoff` 或 `task` 保持上下文连续。

## 11. 使用技巧

- 复杂任务尽量显式写出“目标、约束、验收标准、不要做什么”。
- 让代理并行工作时，提前说明哪些结果可以独立产出。
- 需要质量更稳时，把验证方式写进提示词，比如测试命令或截图标准。
- 交接前先收口：把已完成、未完成、风险点写清楚。
- 环境快照只做参考，真正执行时以当前仓库配置为准。

## 12. 常见排查

```bash
bunx oh-my-opencode doctor
opencode auth list
opencode models
```

如果 `/start-work` 没有反应，通常是计划还没准备好，或者当前计划状态和执行链不一致；先回到 `@plan` 补齐上下文。

如果重构和 rename 不够准，优先检查语言服务是否可用。

## 附录 A. 本机快照

这一节只保留本机相关信息，方便对照，但不作为通用指南正文。

### A.1 配置位置

- OpenCode 主配置：`~/.config/opencode/opencode.json`
- Oh My OpenAgent 插件配置：`~/.config/opencode/oh-my-openagent.json`

### A.2 当前模型分配快照

| 对象                                     | 当前模型                                            |
| -------------------------------------- | ----------------------------------------------- |
| `sisyphus`                             | `openai/gpt-5.4` `medium`                       |
| `hephaestus`                           | `openai/gpt-5.4` `medium`                       |
| `oracle`                               | `openai/gpt-5.4` `high`                         |
| `prometheus`                           | `openai/gpt-5.4` `high`                         |
| `metis`                                | `openai/gpt-5.4` `high`                         |
| `momus`                                | `openai/gpt-5.4` `xhigh`                        |
| `atlas`                                | `openai/gpt-5.4` `medium`                       |
| `librarian`                            | `openai/gpt-5.4` `medium`                       |
| `explore`                              | `openai/gpt-5.4` `medium`                       |
| `multimodal-looker`                    | `openai/gpt-5.4` `medium`，fallback `gpt-5-nano` |
| `quick`                                | `openai/gpt-5.4-mini`                           |
| `unspecified-low` / `unspecified-high` | `openai/gpt-5.3-codex` `medium`                 |

### A.3 本机观察

- 这是 OpenAI-only 快照
- 当前环境里未检测到 LSP server
- `hashline_edit`、`runtime_fallback`、`task_system` 等配置适合按需启用

### A.4 本机相关配置片段

```jsonc
{
  "runtime_fallback": {
    "enabled": true,
    "max_fallback_attempts": 3,
    "cooldown_seconds": 60,
    "timeout_seconds": 30,
    "notify_on_fallback": true
  },
  "experimental": {
    "task_system": true,
    "aggressive_truncation": true
  },
  "hashline_edit": true,
  "tmux": {
    "enabled": false
  }
}
```

