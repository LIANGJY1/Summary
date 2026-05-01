# Usage guide for the local unified skills manager

This guide is the day 1 and day 2 operator reference for the local unified skills manager. It assumes you already have the repo checked out and that `chezmoi` and `just` are installed, which matches the current repo policy.

## What this system does

The repo keeps OpenCode and Trae skill files in one chezmoi source state, then uses `just` as the operator entrypoint. In V1, only skill files are deployable. `library/rules/` and `library/prompts/` stay source only, and Cursor has no active deployment path.

## Prerequisites

Before using any commands:

1. Install `chezmoi`.
2. Install `just`.
3. Clone this repo.
4. Work from the repo root, or let `just` locate the root through `justfile_directory()`.

If either tool is missing, stop there. The repo does not bootstrap those tools for you.

## Directory layout

Use these paths as the source of truth:

- `source-state/` is the chezmoi source root.
- `source-state/managed/opencode/skills/<skill-name>/SKILL.md` is the OpenCode contract path.
- `source-state/managed/trae/skills/<skill-name>/SKILL.md` is the Trae contract path.
- `source-state/dot_config/opencode/skills/` is the rendered OpenCode root used by chezmoi.
- `source-state/dot_trae/skills/` is the rendered Trae root used by chezmoi.
- `source-state/targets/` is reserved for future markers and ignored in V1.

Target mappings:

- OpenCode: `source-state/managed/opencode/skills/<skill-name>/SKILL.md` maps to `~/.config/opencode/skills/<skill-name>/SKILL.md`.
- Trae: `source-state/managed/trae/skills/<skill-name>/SKILL.md` maps to `~/.trae/skills/<skill-name>/SKILL.md`.

Tool roots versus managed roots:

- `~/.config/opencode` is the OpenCode tool root.
- `~/.config/opencode/skills` is the managed OpenCode skill root.
- `~/.opencode` is runtime or state, not the managed destination.
- `~/.trae` is the Trae tool root.
- `~/.trae/skills` is the managed Trae skill root.
- `~/.config/Trae/User` is config support space, not the managed destination.

## Command flow

Use this order for normal operations:

1. `just bootstrap`
2. `just doctor`
3. `just diff`
4. `just sync` or `just sync-opencode` / `just sync-trae`
5. `just verify`

That order matters. Bootstrap establishes the managed roots, doctor tells you what exists, diff shows what would change, sync applies the change, and verify proves the checked-in state matches what is on disk.

## `just bootstrap`

Use this on a fresh machine, after cloning the repo, or whenever you need the managed roots created from the checked-in source state.

Command:

```bash
just bootstrap
```

What it does:

- Applies the checked-in chezmoi source state.
- May create missing managed target roots for installed tools.

What success looks like:

- The command exits 0.
- Managed skill roots exist for tools that are installed.

What failure means:

- A conflict exists at a managed path.
- chezmoi hits a render error.
- The command exits non zero.

When to use it:

- First run on a machine.
- After adding a new managed skill tree.
- After restoring a machine where managed roots are missing but the tools are installed.

## `just doctor`

Use this to inspect the current state without changing files.

Command:

```bash
just doctor
```

What it does:

- Prints the current OpenCode and Trae state.
- Runs `chezmoi doctor`.

How to read it:

- Missing tool root means the tool is absent.
- Present tool root with missing managed skill root means the tool is installed, but the managed root still needs bootstrap or apply.
- If `chezmoi doctor` fails, the run fails.

What success looks like:

- Exit code 0.
- Installed tools are reported clearly.

What failure means:

- The shell command fails.
- `chezmoi doctor` fails.

## `just diff`

Use this before syncing, and any time you want to see the pending rendered change set.

Command:

```bash
just diff
```

What it does:

- Prints the non mutating chezmoi diff for managed paths.
- Does not change the filesystem.

How to read it:

- Added lines mean new managed content would be written.
- Removed lines mean existing target content would be replaced.
- No output means the rendered state already matches the checked in source state, or there is no applicable change.

What success looks like:

- Exit code 0.
- You can inspect the exact rendered delta before applying it.

What failure means:

- chezmoi cannot compute the diff.

## `just sync`

Use this when you want both tool families updated together.

Command:

```bash
just sync
```

What it does:

- Runs both tool sync recipes.
- Applies the managed roots through chezmoi.

Skip behavior:

- If a tool root is missing, that tool is skipped.
- The other tool can still sync.

What success looks like:

- Both installed tool trees are updated, or missing tools are skipped.

What failure means:

- A chezmoi conflict exists.
- A render error occurs.
- An apply step fails.

## `just sync-opencode`

Use this when only OpenCode should be updated.

Command:

```bash
just sync-opencode
```

What it does:

- Applies only the OpenCode managed root.

Skip behavior:

- If `~/.config/opencode` is missing, the command prints a skip message and does nothing.

What success looks like:

- The OpenCode managed root is applied when the tool root exists.

What failure means:

- A conflict exists.
- `chezmoi apply` fails.

## `just sync-trae`

Use this when only Trae should be updated.

Command:

```bash
just sync-trae
```

What it does:

- Applies only the Trae managed root.

Skip behavior:

- If `~/.trae` is missing, the command prints a skip message and does nothing.

What success looks like:

- The Trae managed root is applied when the tool root exists.

What failure means:

- A conflict exists.
- `chezmoi apply` fails.

## `just verify`

Use this as the final proof step after syncing.

Command:

```bash
just verify
```

What it does:

- Checks each installed tool with `chezmoi verify`.
- Exits 0 only when every installed tool matches the checked in source state.
- Writes evidence for the run.

Evidence files:

- Primary log: `.sisyphus/evidence/task-6-verify.txt`
- Failure mirror: `.sisyphus/evidence/task-6-verify-error.txt`

How to read the evidence:

- The main evidence file is the canonical record of the run.
- The error file is only expected when the run fails.
- A clean verify should leave the main log with matching state and no drift.

Skip behavior:

- A missing tool root is reported as skipped.
- A skip does not fail the run.

Failure behavior:

- Missing managed roots on an installed tool cause failure.
- Drift causes failure.
- Any `chezmoi verify` mismatch causes failure.

## Failure vocabulary

Use these terms consistently when reading command output:

- `skip` means the tool root is missing, so that tool is left alone.
- `fail` means the command hit a conflict, a render problem, or a verification mismatch.
- `drift` means `verify` found local state that no longer matches the checked in source state.

## Add skill workflow

There is no `just add-skill` command in V1. Adding a skill is a file edit workflow.

1. Create the skill under one of these paths:
   - `source-state/managed/opencode/skills/<skill-name>/SKILL.md`
   - `source-state/managed/trae/skills/<skill-name>/SKILL.md`
2. Use lowercase kebab case for `<skill-name>`.
3. Use `SKILL.md` exactly, with that casing.
4. Keep the body plaintext.
5. If the managed root does not exist yet, run `just bootstrap`.
6. Run `just diff` and inspect the rendered change set.
7. Run `just sync` or the tool specific sync command.
8. Run `just verify` and save the evidence if you need to hand the result off.

Example naming:

- Good: `source-state/managed/opencode/skills/sql-debugger/SKILL.md`
- Bad: `source-state/managed/opencode/skills/SQLDebugger/SKILL.md`
- Bad: `source-state/managed/opencode/skills/sql_debugger/SKILL.md`

## Troubleshooting

### Absent tool root

If `doctor` says the tool is absent:

- The tool itself is not installed or not present at the expected root.
- `sync` skips it.
- `verify` skips it.

Fix:

- Install the tool, then rerun `just bootstrap` and `just verify`.

### Missing managed root

If the tool root exists but the managed skill root is missing:

- The tool is installed.
- Bootstrap or apply may create the managed root.
- This is not the same as a missing tool.

Fix:

- Run `just bootstrap`.
- Then run `just diff` and `just verify`.

### Conflict at a managed path

If chezmoi reports a conflict:

- An unmanaged local file already occupies a managed path.
- chezmoi aborts instead of overwriting it.

Fix:

- Inspect the conflicting file.
- Decide whether to move it aside, delete it, or reconcile it into source state.
- Rerun `just bootstrap` or the relevant sync command.

### Drift in verify

If `just verify` reports drift:

- The source state and the installed managed files no longer match.
- Someone changed the target files locally, or the source state changed and was not synced.

Fix:

- Run `just diff` to see the expected change.
- Run the relevant sync command.
- Rerun `just verify`.

### Missing managed roots during verify

If verify fails because a managed root is missing:

- The tool root exists, but the managed skill tree has not been created.

Fix:

- Run `just bootstrap`.
- Then rerun `just verify`.

## Common scenarios

### Fresh machine

1. Install `chezmoi` and `just`.
2. Clone the repo.
3. Run `just bootstrap`.
4. Run `just doctor`.
5. Run `just diff`.
6. Run `just verify`.

### Add one new OpenCode skill

1. Edit `source-state/managed/opencode/skills/<skill-name>/SKILL.md`.
2. Run `just diff`.
3. Run `just sync-opencode`.
4. Run `just verify`.

### Add one new Trae skill

1. Edit `source-state/managed/trae/skills/<skill-name>/SKILL.md`.
2. Run `just diff`.
3. Run `just sync-trae`.
4. Run `just verify`.

### Check whether anything changed before applying

1. Run `just doctor`.
2. Run `just diff`.
3. Review the rendered changes.

### Recover from a conflict

1. Read the conflict message.
2. Find the unmanaged file at the managed path.
3. Move it aside or fold it into source state.
4. Run `just bootstrap` again.
5. Run `just verify`.

### Hand off proof that the tree matches source state

1. Run `just verify`.
2. Keep `.sisyphus/evidence/task-6-verify.txt`.
3. If the run failed, inspect `.sisyphus/evidence/task-6-verify-error.txt`.

## Quick reference

```bash
just bootstrap
just doctor
just diff
just sync
just sync-opencode
just sync-trae
just verify
```

If you only remember one thing, remember the flow: bootstrap, doctor, diff, sync, verify.

---

# 中文文档 (Chinese Translation)

# 本地统一技能管理器使用指南

本指南是本地统一技能管理器 (Local Unified Skills Manager) 的日常运维参考。前提是您已检出该仓库，并且已安装 `chezmoi` 和 `just`，这符合当前仓库的策略。

## 本系统功能

该仓库将 OpenCode 和 Trae 的技能 (Skill) 文件保存在同一个 `chezmoi` 源状态 (Source State) 中，并使用 `just` 作为操作入口。在 V1 版本中，仅支持部署技能文件。`library/rules/` 和 `library/prompts/` 仅保留源码，Cursor 目前没有主动部署路径。

## 前置条件

在使用任何命令之前：

1. 安装 `chezmoi`。
2. 安装 `just`。
3. 克隆本仓库。
4. 在仓库根目录下工作，或者让 `just` 通过 `justfile_directory()` 自动定位根目录。

如果缺少任何一个工具，请先停止操作。本仓库不会为您自动安装这些基础工具。

## 目录结构

请以以下路径作为事实来源：

- `source-state/` 是 `chezmoi` 的源根目录。
- `source-state/managed/opencode/skills/<skill-name>/SKILL.md` 是 OpenCode 的契约路径。
- `source-state/managed/trae/skills/<skill-name>/SKILL.md` 是 Trae 的契约路径。
- `source-state/dot_config/opencode/skills/` 是 `chezmoi` 使用的 OpenCode 渲染根目录。
- `source-state/dot_trae/skills/` 是 `chezmoi` 使用的 Trae 渲染根目录。
- `source-state/targets/` 预留给未来的标记，在 V1 中被忽略。

目标映射关系：

- OpenCode：`source-state/managed/opencode/skills/<skill-name>/SKILL.md` 映射到 `~/.config/opencode/skills/<skill-name>/SKILL.md`。
- Trae：`source-state/managed/trae/skills/<skill-name>/SKILL.md` 映射到 `~/.trae/skills/<skill-name>/SKILL.md`。

工具根目录与托管根目录的区别：

- `~/.config/opencode` 是 OpenCode 工具根目录。
- `~/.config/opencode/skills` 是受托管的 OpenCode 技能根目录。
- `~/.opencode` 是运行时或状态目录，不是托管目标。
- `~/.trae` 是 Trae 工具根目录。
- `~/.trae/skills` 是受托管的 Trae 技能根目录。
- `~/.config/Trae/User` 是配置支持空间，不是托管目标。

## 命令流程

正常操作请遵循以下顺序：

1. `just bootstrap`
2. `just doctor`
3. `just diff`
4. `just sync` 或 `just sync-opencode` / `just sync-trae`
5. `just verify`

这个顺序非常重要。Bootstrap 建立托管根目录，doctor 告知当前存在的内容，diff 显示即将发生的变化，sync 应用这些变化，而 verify 证明签入 (Checked-in) 的状态与磁盘上的实际状态一致。

## `just bootstrap`

在全新机器上、克隆仓库后，或者需要从签入的源状态创建托管根目录时使用此命令。

命令：

```bash
just bootstrap
```

它的作用：

- 应用已签入的 `chezmoi` 源状态。
- 可能会为已安装的工具创建缺失的托管目标根目录。

成功的标志：

- 命令退出码为 0。
- 已安装工具的托管技能根目录存在。

失败的含义：

- 托管路径存在冲突。
- `chezmoi` 遇到渲染错误。
- 命令返回非零退出码。

何时使用：

- 首次在机器上运行。
- 添加新的托管技能树后。
- 恢复一台缺少托管根目录但已安装工具的机器后。

## `just doctor`

使用此命令检查当前状态而不更改任何文件。

命令：

```bash
just doctor
```

它的作用：

- 打印当前的 OpenCode 和 Trae 状态。
- 运行 `chezmoi doctor`。

如何解读：

- 缺少工具根目录意味着该工具未安装。
- 工具根目录存在但缺少托管技能根目录，意味着工具已安装，但托管根目录仍需要 bootstrap 或 apply。
- 如果 `chezmoi doctor` 失败，则运行失败。

成功的标志：

- 退出码为 0。
- 清晰报告已安装的工具。

失败的含义：

- Shell 命令失败。
- `chezmoi doctor` 失败。

## `just diff`

在同步 (Sync) 之前，以及任何您想查看待处理的渲染变更集时使用。

命令：

```bash
just diff
```

它的作用：

- 打印托管路径的非变异 (Non-mutating) `chezmoi` 差异。
- 不会更改文件系统。

如何解读：

- 增加的行表示将写入新的托管内容。
- 删除的行表示现有的目标内容将被替换。
- 没有输出表示渲染状态已经与签入的源状态匹配，或者没有适用的变更。

成功的标志：

- 退出码为 0。
- 您可以在应用之前检查精确的渲染差异 (Delta)。

失败的含义：

- `chezmoi` 无法计算差异。

## `just sync`

当您希望同时更新两个工具系列时使用。

命令：

```bash
just sync
```

它的作用：

- 运行两个工具的同步配方 (Recipes)。
- 通过 `chezmoi` 应用托管根目录。

跳过 (Skip) 行为：

- 如果某个工具根目录缺失，则跳过该工具。
- 另一个工具仍然可以同步。

成功的标志：

- 两个已安装的工具树都得到更新，或缺失的工具被跳过。

失败的含义：

- 存在 `chezmoi` 冲突。
- 发生渲染错误。
- 应用 (Apply) 步骤失败。

## `just sync-opencode`

当只需要更新 OpenCode 时使用。

命令：

```bash
just sync-opencode
```

它的作用：

- 仅应用 OpenCode 托管根目录。

跳过行为：

- 如果缺少 `~/.config/opencode`，命令会打印跳过消息且不执行任何操作。

成功的标志：

- 当工具根目录存在时，应用 OpenCode 托管根目录。

失败的含义：

- 存在冲突。
- `chezmoi apply` 失败。

## `just sync-trae`

当只需要更新 Trae 时使用。

命令：

```bash
just sync-trae
```

它的作用：

- 仅应用 Trae 托管根目录。

跳过行为：

- 如果缺少 `~/.trae`，命令会打印跳过消息且不执行任何操作。

成功的标志：

- 当工具根目录存在时，应用 Trae 托管根目录。

失败的含义：

- 存在冲突。
- `chezmoi apply` 失败。

## `just verify`

作为同步后的最终验证步骤使用。

命令：

```bash
just verify
```

它的作用：

- 使用 `chezmoi verify` 检查每个已安装的工具。
- 仅当每个已安装工具都匹配签入的源状态时才以 0 退出。
- 为运行写入证据 (Evidence)。

证据文件：

- 主日志：`.sisyphus/evidence/task-6-verify.txt`
- 失败镜像：`.sisyphus/evidence/task-6-verify-error.txt`

如何阅读证据：

- 主证据文件是运行的权威记录。
- 仅当运行失败时才会生成错误文件。
- 干净的验证应留下匹配状态且无漂移 (Drift) 的主日志。

跳过行为：

- 缺失的工具根目录将被报告为跳过。
- 跳过不会导致运行失败。

失败行为：

- 已安装工具上缺少托管根目录会导致失败。
- 发生漂移会导致失败。
- 任何 `chezmoi verify` 不匹配都会导致失败。

## 故障词汇表

在阅读命令输出时，请一致地使用这些术语：

- `skip`（跳过）：表示工具根目录缺失，因此不处理该工具。
- `fail`（失败）：表示命令遇到冲突、渲染问题或验证不匹配。
- `drift`（漂移）：表示 `verify` 发现本地状态不再匹配签入的源状态。

## 添加技能 (Skill) 工作流

在 V1 版本中没有 `just add-skill` 命令。添加技能属于文件编辑工作流。

1. 在以下路径之一创建技能：
   - `source-state/managed/opencode/skills/<skill-name>/SKILL.md`
   - `source-state/managed/trae/skills/<skill-name>/SKILL.md`
2. 对于 `<skill-name>`，请使用小写连字符命名法 (Kebab-case)。
3. 文件名必须严格为 `SKILL.md`（注意大小写）。
4. 保持正文为纯文本 (Plaintext)。
5. 如果托管根目录尚不存在，请运行 `just bootstrap`。
6. 运行 `just diff` 并检查渲染后的变更集。
7. 运行 `just sync` 或特定工具的同步命令。
8. 运行 `just verify`，如果需要移交结果，请保存证据。

命名示例：

- 规范：`source-state/managed/opencode/skills/sql-debugger/SKILL.md`
- 错误：`source-state/managed/opencode/skills/SQLDebugger/SKILL.md`
- 错误：`source-state/managed/opencode/skills/sql_debugger/SKILL.md`

## 常见问题排查 (Troubleshooting)

### 工具根目录不存在 (Absent tool root)

如果 `doctor` 显示工具不存在：

- 工具本身未安装或不在预期的根目录下。
- `sync` 会跳过它。
- `verify` 会跳过它。

修复方法：

- 安装该工具，然后重新运行 `just bootstrap` 和 `just verify`。

### 缺少托管根目录 (Missing managed root)

如果工具根目录存在但托管技能根目录缺失：

- 工具已安装。
- Bootstrap 或 Apply 可能会创建托管根目录。
- 这与工具缺失不同。

修复方法：

- 运行 `just bootstrap`。
- 然后运行 `just diff` 和 `just verify`。

### 托管路径发生冲突 (Conflict at a managed path)

如果 `chezmoi` 报告冲突：

- 一个未托管的本地文件已占据了托管路径。
- `chezmoi` 会中止操作而不是覆盖它。

修复方法：

- 检查冲突的文件。
- 决定将其移开、删除还是合并到源状态中。
- 重新运行 `just bootstrap` 或相关的同步命令。

### 验证时发生漂移 (Drift in verify)

如果 `just verify` 报告漂移：

- 源状态与已安装的托管文件不再匹配。
- 有人在本地更改了目标文件，或者源状态发生了更改但尚未同步。

修复方法：

- 运行 `just diff` 查看预期的变更。
- 运行相关的同步命令。
- 重新运行 `just verify`。

### 验证期间缺少托管根目录 (Missing managed roots during verify)

如果验证因托管根目录缺失而失败：

- 工具根目录存在，但托管技能树尚未创建。

修复方法：

- 运行 `just bootstrap`。
- 然后重新运行 `just verify`。

## 常见场景

### 全新机器

1. 安装 `chezmoi` 和 `just`。
2. 克隆仓库。
3. 运行 `just bootstrap`。
4. 运行 `just doctor`。
5. 运行 `just diff`。
6. 运行 `just verify`。

### 添加一个全新的 OpenCode 技能

1. 编辑 `source-state/managed/opencode/skills/<skill-name>/SKILL.md`。
2. 运行 `just diff`。
3. 运行 `just sync-opencode`。
4. 运行 `just verify`。

### 添加一个全新的 Trae 技能

1. 编辑 `source-state/managed/trae/skills/<skill-name>/SKILL.md`。
2. 运行 `just diff`。
3. 运行 `just sync-trae`。
4. 运行 `just verify`。

### 在应用之前检查是否有任何变更

1. 运行 `just doctor`。
2. 运行 `just diff`。
3. 查看渲染后的变更。

### 从冲突中恢复

1. 阅读冲突消息。
2. 找到位于托管路径的未托管文件。
3. 将其移开或合并到源状态中。
4. 再次运行 `just bootstrap`。
5. 运行 `just verify`。

### 移交文件树与源状态匹配的证明

1. 运行 `just verify`。
2. 保留 `.sisyphus/evidence/task-6-verify.txt`。
3. 如果运行失败，请检查 `.sisyphus/evidence/task-6-verify-error.txt`。

## 快速参考

```bash
just bootstrap
just doctor
just diff
just sync
just sync-opencode
just sync-trae
just verify
```

如果您只记得一件事，请记住这个流程：bootstrap, doctor, diff, sync, verify。