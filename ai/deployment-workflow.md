# Deployment workflow

This document explains the full path used to deploy the local unified skills manager, from planning through rollout. It is a summary of the working system, not a new design.

## 1. Planning and scope

The work started by fixing the V1 boundary. The repository only deploys OpenCode and Trae skill files, and it does so from one checked-in source state. `library/rules/` and `library/prompts/` stay source-only in V1, and Cursor has no active deployment path.

That scope matters because it keeps the rollout small enough to reason about. The README is the operator front door, while the deeper docs explain the path mapping and bootstrap mechanics.

## 2. Repository contract

The repository itself is the source of truth. Chezmoi reads from `source-state/` through the root `.chezmoiroot` file, so docs and planning files stay outside the rendered tree.

The deployable contract paths are:

- `source-state/managed/opencode/skills/<skill-name>/SKILL.md`
- `source-state/managed/trae/skills/<skill-name>/SKILL.md`

Those names are the stable repository API. The actual rendered roots are the chezmoi paths under `source-state/dot_config/...` and `source-state/dot_trae/...`.

## 3. Naming rules

The workflow depends on strict naming so the source tree stays machine-checkable.

- `<skill-name>` uses lowercase kebab-case.
- The filename is exactly `SKILL.md`.
- The file body stays plaintext.

Those rules prevent ambiguity between the source contract and the rendered target paths.

## 4. Chezmoi source-state bootstrap

The bootstrap layer is the substrate that turns the repo contract into real files. On a first-time machine, the documented assumption is that `chezmoi` is already installed. The sequence is:

1. Install `chezmoi`.
2. Clone the repo or initialize chezmoi from the remote URL.
3. Let chezmoi place the repo in its default source directory.
4. Let the root `.chezmoiroot` point chezmoi at `source-state/`.
5. Run `chezmoi diff` to inspect the rendered result.
6. Run `chezmoi apply` to write the managed files.

In V1, `just bootstrap` is the public entrypoint for that flow. It is orchestration only, and it may create missing managed target directories for installed tools during apply.

## 5. OpenCode target mapping

OpenCode is mapped from:

- `source-state/dot_config/opencode/skills/<skill-name>/SKILL.md`
- to `~/.config/opencode/skills/<skill-name>/SKILL.md`

The contract path `source-state/managed/opencode/skills/<skill-name>/SKILL.md` names the same content at the repository API level.

`~/.config/opencode` is the OpenCode tool root. `~/.config/opencode/skills` is the managed skill root. `~/.opencode` is runtime and state, not the managed destination.

## 6. Trae target mapping

Trae follows the same pattern:

- `source-state/dot_trae/skills/<skill-name>/SKILL.md`
- to `~/.trae/skills/<skill-name>/SKILL.md`

The matching contract path is `source-state/managed/trae/skills/<skill-name>/SKILL.md`.

`~/.trae` is the Trae tool root. `~/.trae/skills` is the managed skill root. `~/.config/Trae/User` is config-support space only and is not the managed destination.

## 7. Just command surface

`just` is the operator entrypoint that wraps the chezmoi workflow. The supported V1 commands are:

- `just bootstrap`, apply the checked-in source state.
- `just doctor`, report current tool state and then run `chezmoi doctor`.
- `just diff`, show the non-mutating rendered diff.
- `just sync`, run both tool sync recipes.
- `just sync-opencode`, apply only the OpenCode managed root when it exists.
- `just sync-trae`, apply only the Trae managed root when it exists.
- `just verify`, check installed tools with `chezmoi verify` and write evidence.

The repo uses `justfile_directory()` so these recipes can find `source-state/` even when `just` is run from elsewhere.

## 8. Conflict and partial-install policy

The workflow is strict about unmanaged files. If a managed path already contains a local file that is not under the source contract, chezmoi aborts instead of overwriting it.

Partial installs are handled separately from conflicts:

- Missing tool root means the tool is absent, so sync skips it.
- Installed tool with a missing managed skill root means bootstrap or sync may create that managed root.
- Missing managed root is not treated as a silent redirect.

That split keeps the skip path benign and the conflict path explicit.

## 9. Verification and evidence

Verification happens after sync. `just verify` checks each installed tool with `chezmoi verify` and exits 0 only when the checked-in source state matches the managed target roots.

The documented vocabulary is:

- `skip`, the tool root is missing and the tool is left alone.
- `fail`, the command hit a conflict, render error, or verification mismatch.
- `drift`, verify found local state that no longer matches the source state.

Evidence is written to `.sisyphus/evidence/task-6-verify.txt`, and failing runs also mirror to `.sisyphus/evidence/task-6-verify-error.txt`.

## 10. Operator docs

The README stays the front door for operators. It explains the command surface, the skip and fail behavior, the evidence path, and the day 2 add-skill flow.

This document is the deeper rollout story. It ties the README commands back to the source-state layout, the target mappings, and the reason each step exists.

## 11. Final review gate

The final wave is simple: bootstrap, inspect the diff, sync the installed tool roots, run verify, and keep the evidence artifact. If verify passes, the deployed state matches the checked-in source state. If it fails, the error artifact and the command output are the handoff record.

## Known limitations

- The bootstrap flow assumes chezmoi is already installed.
- The sandbox used for this work does not include runtime verification of `just` or chezmoi commands.
- V1 covers skills only. Rules, prompts, and Cursor remain out of scope.

---

# 中文文档 (Chinese Translation)

# 部署工作流

本文档解释了部署本地统一技能管理器的完整路径，涵盖从计划到上线的全过程。它是对现有工作系统的总结，而非新的设计方案。

## 1. 计划与范围

这项工作从确定 V1 版本的边界开始。该仓库仅部署 OpenCode 和 Trae 的技能 (Skill) 文件，并且通过统一的签入源状态 (Source State) 进行部署。`library/rules/` 和 `library/prompts/` 在 V1 版本中仅保留源码，Cursor 目前没有主动的部署路径。

范围界定非常重要，因为它使部署规模足够小，便于推理。`README` 是操作人员的入口，而更深层次的文档则解释了路径映射和引导 (Bootstrap) 机制。

## 2. 仓库契约

仓库本身就是事实来源。`chezmoi` 通过根目录的 `.chezmoiroot` 文件从 `source-state/` 读取数据，因此文档和计划文件都保留在渲染树之外。

可部署的契约路径包括：

- `source-state/managed/opencode/skills/<skill-name>/SKILL.md`
- `source-state/managed/trae/skills/<skill-name>/SKILL.md`

这些名称是稳定的仓库 API。实际的渲染根目录是位于 `source-state/dot_config/...` 和 `source-state/dot_trae/...` 下的 `chezmoi` 路径。

## 3. 命名规则

工作流依赖于严格的命名规范，以便源文件树保持机器可检查的状态。

- `<skill-name>` 必须使用小写连字符命名法 (Kebab-case)。
- 文件名必须完全是 `SKILL.md`。
- 文件正文保持纯文本 (Plaintext) 格式。

这些规则防止了源契约与渲染目标路径之间产生歧义。

## 4. Chezmoi 源状态引导 (Bootstrap)

引导层是将仓库契约转化为实际文件的底层基础。在一台全新的机器上，文档默认假设 `chezmoi` 已经安装。其操作顺序如下：

1. 安装 `chezmoi`。
2. 克隆仓库或从远程 URL 初始化 `chezmoi`。
3. 让 `chezmoi` 将仓库放置在其默认的源目录中。
4. 让根目录下的 `.chezmoiroot` 将 `chezmoi` 指向 `source-state/`。
5. 运行 `chezmoi diff` 检查渲染结果。
6. 运行 `chezmoi apply` 写入托管文件。

在 V1 版本中，`just bootstrap` 是此流程的公开入口点。它仅负责编排，并可能在应用 (Apply) 期间为已安装的工具创建缺失的托管目标目录。

## 5. OpenCode 目标映射

OpenCode 映射规则：

- 从 `source-state/dot_config/opencode/skills/<skill-name>/SKILL.md`
- 映射到 `~/.config/opencode/skills/<skill-name>/SKILL.md`

契约路径 `source-state/managed/opencode/skills/<skill-name>/SKILL.md` 在仓库 API 级别指定了相同的内容。

`~/.config/opencode` 是 OpenCode 工具根目录。`~/.config/opencode/skills` 是受托管的技能根目录。`~/.opencode` 是运行时及状态目录，不是托管目标。

## 6. Trae 目标映射

Trae 遵循相同的模式：

- 从 `source-state/dot_trae/skills/<skill-name>/SKILL.md`
- 映射到 `~/.trae/skills/<skill-name>/SKILL.md`

匹配的契约路径为 `source-state/managed/trae/skills/<skill-name>/SKILL.md`。

`~/.trae` 是 Trae 工具根目录。`~/.trae/skills` 是受托管的技能根目录。`~/.config/Trae/User` 仅作为配置支持空间，不是托管目标。

## 7. Just 命令接口

`just` 是封装 `chezmoi` 工作流的操作员入口。V1 版本支持的命令有：

- `just bootstrap`：应用已签入的源状态。
- `just doctor`：报告当前工具状态，然后运行 `chezmoi doctor`。
- `just diff`：显示非变异的渲染差异。
- `just sync`：运行两个工具的同步配方。
- `just sync-opencode`：当 OpenCode 托管根目录存在时，仅应用它。
- `just sync-trae`：当 Trae 托管根目录存在时，仅应用它。
- `just verify`：使用 `chezmoi verify` 检查已安装的工具并写入证据。

仓库使用了 `justfile_directory()`，因此即使从其他地方运行 `just`，这些配方也能找到 `source-state/`。

## 8. 冲突与部分安装策略

工作流对未托管的文件要求非常严格。如果托管路径中已经包含一个不在源契约下的本地文件，`chezmoi` 将中止操作而不是覆盖它。

部分安装与冲突会分开处理：

- 缺失工具根目录意味着该工具不存在，因此同步 (Sync) 会跳过它。
- 已安装工具但缺少托管技能根目录，意味着 Bootstrap 或 Sync 可能会创建该托管根目录。
- 缺失托管根目录不会被视为静默重定向。

这种分离机制确保了跳过操作是安全的，而冲突则是显式的。

## 9. 验证与证据

验证在同步之后进行。`just verify` 使用 `chezmoi verify` 检查每个已安装的工具，并且只有当签入的源状态与托管的目标根目录完全匹配时，才以 0 退出。

文档化的词汇包括：

- `skip`（跳过）：工具根目录缺失，工具不被处理。
- `fail`（失败）：命令遇到冲突、渲染错误或验证不匹配。
- `drift`（漂移）：验证发现本地状态不再与源状态匹配。

证据会被写入 `.sisyphus/evidence/task-6-verify.txt`，如果运行失败，还会将错误镜像保存到 `.sisyphus/evidence/task-6-verify-error.txt`。

## 10. 操作员文档

`README` 始终是操作员的首要入口。它解释了命令接口、跳过和失败行为、证据路径，以及后续添加技能的流程。

本文档则讲述了更深层的上线故事。它将 `README` 中的命令与源状态布局、目标映射以及每个步骤存在的理由联系起来。

## 11. 最终审查关卡

最后的步骤很简单：引导 (Bootstrap)、检查差异 (Diff)、同步已安装的工具根目录、运行验证 (Verify)，并保留证据工件。如果验证通过，说明部署的状态与签入的源状态一致。如果失败，错误工件和命令输出就是交接的记录。

## 已知限制

- Bootstrap 流程假设 `chezmoi` 已经安装。
- 本次工作使用的沙盒不包括 `just` 或 `chezmoi` 命令的运行时验证。
- V1 版本仅涵盖技能 (Skills)。规则 (Rules)、提示词 (Prompts) 和 Cursor 仍不在范围内。