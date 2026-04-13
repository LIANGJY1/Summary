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
