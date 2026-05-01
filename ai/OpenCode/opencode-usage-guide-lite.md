# OpenCode 极简使用手册

> 本手册为 OpenCode 核心功能的极简参考副本，适合团队快速查阅与上手。

---

## 1. 快速开始

```bash
# 1. 全局安装
npm install -g opencode-ai

# 2. 进入目标项目并启动终端 TUI
cd /path/to/project
opencode

# 3. 首次启动时的必备操作 (在 TUI 界面内输入)
/connect  # 连接大模型 Provider
/init     # 初始化项目规则
```

---

## 2. 核心工作流与交互

- **直接对话与提问**：直接输入需求（例如：“概括这个仓库的模块边界”）。
- **精准引用文件**：使用 `@` 引用特定文件（例如：“解释 `@src/main.ts` 的启动流程”）。
- **执行终端命令**：使用 `!` 前缀在会话中执行命令（例如：`!git status`）。
- **先计划后执行（推荐）**：对于复杂任务，先要求“给出一个实现方案，不要改代码”，确认无误后再下令“按方案开始实现”。

---

## 3. 高频控制命令（TUI 与 CLI）

### 3.1 TUI 交互命令

| 命令 | 作用 | 场景说明 |
| :--- | :--- | :--- |
| `/help` | 查看帮助 | 忘记指令时查阅 |
| `/compact` | 压缩上下文 | 发现长会话 Token 快满时必须执行 |
| `/undo` / `/redo` | 撤销 / 重做 | 撤销上一步错误的代码修改或命令 |
| `/session` | 会话管理 | 切换或恢复之前的长会话记录 |
| `/share` / `/unshare` | 会话分享 | 将当前会话生成链接分享给同事 |
| `/exit` | 退出 | 安全退出 OpenCode |

### 3.2 CLI 快捷命令

在不进入终端交互界面 (TUI, Terminal User Interface) 的情况下，也可以直接通过命令行快速调用：

```bash
opencode run "解释一下闭包"   # 单次运行
opencode models            # 查看可用模型
opencode session list      # 列出历史会话
opencode --continue        # 续接上一次会话
```

---

## 4. 核心配置与权限约束

OpenCode 在项目中的行为主要受以下两个核心文件约束：

1. **`opencode.json`**：项目级底层配置。用于定义默认模型、代理 (Agent) 以及各种高危操作的权限控制。
2. **`AGENTS.md`**：项目业务规则说明。文件内容会自动进入 AI 的上下文，用于约束编码规范、测试边界及项目陷阱。

**最简 `opencode.json` 配置示例**：

```json
{
  "model": "openai/gpt-4o",
  "default_agent": "build",
  "instructions": ["AGENTS.md"],
  "permission": {
    "bash": {"*": "ask", "git status*": "allow"},
    "edit": {"*": "ask"}
  },
  "share": "manual"
}
```

> **权限建议**：`bash` 和 `edit` 默认保持 `ask`（询问），只有对无风险命令（如 `git status`）才设置为 `allow`。

---

## 5. 常见实战场景

- **快速看仓库**：先 `/connect`、`/init`，再让它概括模块边界、入口文件和关键命令。
- **修一个小问题**：直接给出文件路径和现象，让它先定位再改，避免一上来大范围重构。
- **做代码评审 (Code Review) 前准备**：让它先整理改动摘要、风险点和验证清单，再进入 Review。
- **长会话续跑**：上下文快满时先 `/compact`，必要时退出后用 `opencode --continue` 续接。

---

## 6. 常见排错 (Troubleshooting)

- **`/undo` 或 `/redo` 无法工作**：通常是因为当前目录不是 Git 仓库，或者未初始化 Git 提交（回滚依赖于本地 Git 记录）。
- **AI 提示无法读取文件**：
  1. 检查是否触发了 `.env` 等敏感文件的默认拒绝规则。
  2. 检查是否超出了允许的外部目录 (External Directory)。
  3. 检查 `opencode.json` 中的 `permission` 规则是否拒绝。
- **分享会话隐私问题**：分享会话会上传历史记录到云端。如果不希望被分享，在配置中将 `share` 设为 `disabled`。

---

## 7. 极简实战技巧

- **高效 Prompt 公式**：**明确目标 + 补充限制条件 + 给出验收标准**。
- **避免大范围破坏**：从小处着手，直接给出文件路径和具体现象，让它先定位再修改。
- **明确指令意图**：需要它“先想再改”时，明确写出“不要改代码”。
- **状态持久化**：长任务（如系统重构）建议配合 `.sisyphus/` 隐藏工作区存放计划 (Plans) 和证据 (Evidence)，进行按阶段验收与续跑。
