# OpenCode 使用手册

本文以 OpenCode 官方文档为准，整理成中文手册流，供团队查阅；只保留可复用的通用说明。

OpenCode 保持为平台级能力的唯一入口；OpenAgent 只保留必要的交叉引用，不重复平台基础说明。若要继续维护这份手册，优先更新通用流程和配置项，再补充项目本地约定。

## 1. 概览

OpenCode 是面向代码工作的开源 AI 工具，常见入口包括终端 TUI、CLI、Web/Headless，以及桌面或 IDE 集成。

它主要覆盖这些能力：

1. 连接模型与 provider
2. 读写文件、执行命令、调用工具
3. 项目规则、权限、会话、分享
4. 内置代理、自定义命令、MCP 等扩展能力

## 2. 快速开始

```bash
curl -fsSL https://opencode.ai/install | bash
cd /path/to/project
opencode
```

第一次进入项目后，先连接 provider，再初始化项目规则：

```text
/connect
/init
```

## 3. 安装与连接

### 3.1 安装

```bash
npm install -g opencode-ai
pnpm install -g opencode-ai
bun install -g opencode-ai
yarn global add opencode-ai
brew install anomalyco/tap/opencode
choco install opencode
scoop install opencode
```

安装后可检查：

```bash
opencode --version
opencode --help
```

### 3.2 连接 provider

TUI 中用：

```text
/connect
```

CLI 登录相关命令：

```bash
opencode auth login
opencode auth list
opencode auth logout
```

模型 ID 一般写成 `provider/model`。

## 4. 核心工作流

### 4.1 直接提问

```text
帮我概括这个仓库的模块边界
```

### 4.2 引用文件

```text
解释一下 @src/main.ts 的启动流程
```

### 4.3 在会话里跑命令

```text
!git status
```

### 4.4 先计划，再执行

OpenCode 的主代理一般是 `build` 和 `plan`：前者偏执行，后者偏分析。

```text
先给我一个实现方案，不要改代码
```

确认后再让它执行：

```text
按刚才方案开始实现
```

### 4.5 撤销、重做、续接

```text
/undo
/redo
```

```bash
opencode --continue
opencode --session <session-id>
```

## 5. 常用命令

### 5.1 TUI 命令

| 命令 | 作用 |
| --- | --- |
| `/help` | 帮助 |
| `/connect` | 连接 provider |
| `/init` | 初始化项目规则 |
| `/models` | 查看模型 |
| `/new` | 新建会话 |
| `/sessions` | 会话管理 |
| `/compact` | 压缩上下文 |
| `/undo` | 撤销 |
| `/redo` | 重做 |
| `/share` | 分享会话 |
| `/unshare` | 取消分享 |
| `/export` | 导出会话 |
| `/thinking` | 显示/隐藏思考块 |
| `/exit` | 退出 |

### 5.2 CLI 命令

```bash
opencode run "Explain closures"
opencode models
opencode session list
opencode serve
opencode web
opencode attach http://localhost:4096
opencode upgrade
opencode uninstall
```

## 6. 配置

OpenCode 常见配置分两类：

1. `opencode.json` / `opencode.jsonc`：模型、代理、权限、命令、说明文件
2. `tui.json` / `tui.jsonc`：界面、主题、按键、滚动、鼠标

常见位置：

```text
~/.config/opencode/opencode.json
~/.config/opencode/tui.json
~/.local/share/opencode/auth.json
<project_root>/opencode.json
<project_root>/tui.json
```

配置是合并后的优先级覆盖，不是整文件替换。项目级配置通常高于全局配置；`OPENCODE_CONFIG`、`OPENCODE_CONFIG_CONTENT`、managed config 等再往上叠加。

一个最小项目配置示例：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "model": "openai/gpt-5",
  "default_agent": "build",
  "instructions": ["AGENTS.md"],
  "permission": {
    "bash": {"*": "ask"},
    "edit": "ask"
  }
}
```

## 7. 权限与规则

`AGENTS.md` 是项目规则文件，会进入上下文，用来约束 OpenCode 在当前仓库的行为。建议放 build/test/lint 命令、目录边界、仓库陷阱等信息。

规则查找通常从当前目录向上，再到全局规则文件。OpenCode 也兼容 `CLAUDE.md`，但本文只把它当作兼容入口，不作为主路径。

权限动作只有三种：`allow`、`ask`、`deny`。

常用控制点：

1. `bash`、`edit` 先收紧
2. `external_directory` 只在确实需要时放开
3. `share` 需要时再启用，敏感仓库可直接禁用

```json
{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "bash": {"*": "ask", "git status*": "allow", "git diff*": "allow"},
    "edit": {"*": "ask"}
  },
  "share": "manual"
}
```

## 8. Agents / MCP / LSP / AST

OpenCode 内置主代理一般是 `build` 与 `plan`，子代理常见有 `general` 和 `explore`。

自定义代理可以写在 `opencode.json` 或 `.opencode/agents/` 中；自定义命令则放在 `.opencode/commands/`。

MCP 主要用于接外部工具：

```bash
opencode mcp add
opencode mcp list
opencode mcp auth <name>
```

LSP / AST 相关能力按官方文档使用即可；这份手册只保留入口，不展开细节。

## 9. Troubleshooting

如果 `/undo` 或 `/redo` 不能工作，通常是因为当前目录不是 Git 仓库，或回滚功能未启用。

如果文件读不到，优先检查：

1. 是否触发了 `.env` 等默认拒绝规则
2. 是否超出了 `external_directory`
3. 是否被 `permission` 规则拒绝

分享会话会生成公开链接，并上传会话历史；不希望分享时，把 `share` 设为 `disabled`。

官方参考：

1. `https://opencode.ai/docs`
2. `https://opencode.ai/docs/config/`
3. `https://opencode.ai/docs/agents/`
4. `https://opencode.ai/docs/permissions/`
5. `https://opencode.ai/docs/mcp/`

## 10. 实践场景

- **快速看仓库**：先 `/connect`、`/init`，再让它概括模块边界、入口文件和关键命令。
- **修一个小问题**：直接给出文件路径和现象，让它先定位再改，避免一上来大范围重构。
- **做评审前准备**：让它先整理改动摘要、风险点和验证清单，再进入 review。
- **长会话续跑**：上下文快满时先 `/compact`，必要时用 `/session` 续接。

## 11. 使用技巧

- 先说目标，再补限制，最后给验收标准。
- 需要它“先想再改”时，明确写“不要改代码”。
- 引用文件时尽量写相对路径，减少歧义。
- 让它执行命令时，把命令写成 `!` 前缀，避免口头描述。
- 共享会话前先确认内容里有没有敏感信息。

## 12. `.sisyphus` 目录机制分析

这部分要和 OpenCode 官方配置目录分开看。

- OpenCode 官方文档公开强调的是 `opencode.json`、`AGENTS.md`、`.opencode/agents/`、`.opencode/commands/` 等配置/规则入口。
- 目前没有在官方 `Config` / `Agents` 文档里看到把 `.sisyphus/` 定义为 OpenCode 通用标准配置目录。
- 但在 Sisyphus / oh-my-openagent 相关公开 issue 与本地样例里，`.sisyphus/` 明确被当作计划、证据、草稿、续跑状态的隐藏工作区。

### 12.1 为什么计划等文件会落在 `.sisyphus/`

基于现有证据，`.sisyphus/` 更像 **Sisyphus 工作产物目录**，而不是 OpenCode 官方公开配置层的主目录。

可以确认的事实：

1. 本地样例目录 `/home/liang/Project/MyProject/Summary/project/hc/.sisyphus/` 下真实存在：
   - `plans/`
   - `evidence/`
   - `notepads/`
   - `drafts/`
   - `run-continuation/`
   - `boulder.json`
2. `boulder.json` 记录了：
   - `active_plan`
   - `plan_name`
   - `session_ids`
   - `task_sessions`
3. 计划文件本身会把产物路径写成 `.sisyphus/evidence/...`，说明这不是随手命名，而是有一套稳定约定。
4. 公开 issue `oh-my-openagent#631` 直接提到 `.sisyphus/plans/`、`.sisyphus/notepads/`、`.sisyphus/drafts/`、`.sisyphus/evidence/`，并指出 agent 会去这些隐藏目录里找文件。

因此，更合理的理解是：

- **OpenCode 官方层** 提供通用 agent / config / rules / tool 框架；
- **Sisyphus / oh-my-openagent 编排层** 在项目根下额外约定了 `.sisyphus/` 作为工作区，用来存放计划、证据、草稿、续跑状态等中间产物；
- 这样做的目的，是把 AI 编排产物集中在隐藏目录里，避免污染业务源码主目录，同时便于续跑和状态恢复。

### 12.2 该目录下的命名有没有规则

从现有样例看，命名分成两层：

#### 第一层：目录槽位名

当前能确认的稳定目录名有：

```text
.sisyphus/
  plans/
  evidence/
  notepads/
  drafts/
  run-continuation/
  boulder.json
```

这些名字看起来是“固定槽位”，用于区分不同类型的产物：

- `plans/`：计划正文
- `evidence/`：执行证据、阶段验收、任务输出
- `notepads/`：围绕某个计划的分主题笔记
- `drafts/`：草稿
- `run-continuation/`：续跑相关状态
- `boulder.json`：工作区当前状态索引

#### 第二层：文件或子目录 slug

具体文件名通常是**语义化 slug**，不是随机串。例如：

- `plans/android-java-review-architecture-optimization.md`
- `notepads/android-java-review-architecture-optimization/decisions.md`
- `evidence/task-11-cross-module-roadmap.md`

可以看出常见规则是：

1. **计划名 slug**：
   - 用在 `plans/<plan-name>.md`
   - 同时复用到 `notepads/<plan-name>/`
2. **任务证据文件**：
   - 用 `task-<序号>-<slug>.md`
3. **阶段/检查文件**：
   - 用 `f<序号>-<slug>.md`

这说明命名不是随意的，而是“**固定目录类别 + 人类可读 slug**”的组合。

但要注意：

- 这些规则在当前证据下可视为**稳定约定**；
- 还不能把它写成“OpenCode 官方公开 schema 已强制规定的全局标准”。

### 12.3 agent 运行过程中怎么找到这些文件

现有证据支持两类定位方式。

#### 方式一：按约定路径查找

公开 issue 中能看到 Sisyphus/agent 会直接尝试使用：

- `.sisyphus/plans/*.md`
- `.sisyphus/notepads/...`
- `.sisyphus/evidence/...`

这说明 agent 至少在一部分流程里依赖“**约定目录 + 约定模式**”去找文件。

一个重要细节是：该 issue 讨论的是 **隐藏目录 glob 失效** 会导致 agent 读不到 `.sisyphus/` 下文件，这反过来也证明了 agent 原本就会把 `.sisyphus/` 当作工作目录去搜索。

#### 方式二：按状态文件中的显式路径查找

本地样例 `.sisyphus/boulder.json` 中有：

```json
{
  "active_plan": "/home/liang/Project/Reachauto/HC/27M/Honda27M/AppStore/.sisyphus/plans/android-java-review-architecture-optimization.md",
  "plan_name": "android-java-review-architecture-optimization"
}
```

这说明系统不只是“模糊搜索”，还会把当前活动计划的**绝对路径**写到状态文件里。这样后续 agent 或 continuation 流程就可以直接定位当前 plan，而不必重新猜。

结合两类证据，更稳妥的结论是：

1. **约定路径**用于发现默认工作产物；
2. **状态文件**用于记录当前活跃对象并加速精确定位。

### 12.4 这些路径是谁指定的，什么时候指定

这里要区分“目录约定”和“具体文件路径”。

#### 可以较确定地说

1. `.sisyphus/` 作为目录前缀，不像是用户临时随手命名，因为：
   - 公开 issue 直接把它写进 agent 查找路径；
   - 本地样例里的计划、证据、notepads、状态文件都一致使用它。
2. 具体 plan 文件名和 evidence 文件名，是在**实际任务运行过程中**生成的，因为它们带有明确的计划 slug、任务序号、阶段序号。
3. `boulder.json` 中的 `active_plan` 说明，至少在“进入某个计划工作流之后”，系统会把当前活跃计划路径写入本地状态。

#### 更合理的推断

- **谁指定目录前缀**：大概率是 Sisyphus / oh-my-openagent 这一层的工作流约定或实现逻辑指定。
- **谁指定具体文件名**：大概率是触发该工作流的命令、计划生成器或编排器在运行时决定。
- **什么时候指定**：通常发生在这些时机：
  1. 初始化某个 plan / roadmap / 审查任务时；
  2. 进入带 continuation / boulder 状态管理的长流程时；
  3. 生成 task evidence、notepads、阶段 QA 文件时。

#### 目前仍未知的部分

当前证据还不能严格证明：

- 是哪个具体命令最先创建 `.sisyphus/`
- 是哪个具体 agent 负责第一次写入 `boulder.json`
- 所有子目录是否都由框架自动创建，还是一部分由项目模板/人工先建好

因此文档里不应写成“OpenCode 启动时一定自动创建 `.sisyphus/`”，只能写成“在 Sisyphus 工作流下通常会使用该目录”。

### 12.5 实际使用建议

如果你在项目里采用 Sisyphus 风格的长流程治理，可以把 `.sisyphus/` 当作“AI 工作区”，但要注意：

1. **不要把它当官方 OpenCode 主配置目录**
   - 官方配置目录仍然是：
     - `opencode.json`
     - `AGENTS.md`
     - `.opencode/agents/`
     - `.opencode/commands/`
2. **把 `.sisyphus/` 当项目级隐藏产物区**
   - 适合存：计划、证据、草稿、过程笔记、续跑状态。
3. **文件名尽量保持可读 slug**
   - 便于 agent 与人共同检索，也便于后续 grep / glob /状态恢复。
4. **如果 agent 找不到 `.sisyphus/` 下文件，先检查 hidden 目录搜索问题**
   - 公开 issue 已证明：隐藏目录 glob 配置不对，会直接导致计划/笔记发现失败。

### 12.6 一句话结论

`.sisyphus/` 不是当前 OpenCode 官方配置文档公开定义的主目录；它更像是 Sisyphus / oh-my-openagent 工作流约定的隐藏工作区。目录类别通常是稳定槽位名，具体文件名则在任务运行时按计划名、任务号、阶段号生成，并通过约定路径和状态文件两种方式被 agent 发现。
