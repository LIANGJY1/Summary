# Oh My OpenAgent 高阶使用与本地定制指南

**如何真正把 oh-my-openagent 用成生产力工具，以及如何安全地做本地定制。**

***

## 1. 本机配置

你本机当前安装位置：

- 插件包：`/home/liang/.opencode/node_modules/oh-my-openagent`
- OpenCode 主配置：`/home/liang/.config/opencode/opencode.json`
- Oh My OpenAgent 插件配置：`/home/liang/.config/opencode/oh-my-openagent.json`

当前版本：

- `oh-my-openagent@3.16.0`
- `@opencode-ai/plugin@1.4.6`

这个项目本质上不是“多几个 prompt”，而是 **OpenCode 上的一层多 agent 编排系统**：

- `Sisyphus` 负责理解意图、拆任务、调度专家
- `Prometheus` 负责先采访你、再产计划
- `Atlas` 负责按计划推进执行
- `Oracle` 负责架构/高难调试咨询
- `Explore` / `Librarian` 负责代码库与外部资料检索
- `task(category=...)` 是整个系统最关键的委派接口

如果你想成为最会用这个插件的人，核心不是背 prompt，而是掌握下面这套操作哲学：

> **先分辨任务类型，再决定是直接做、先规划、还是先并行搜索；用 category/skill 路由，而不是手搓超长提示词。**

***

## 2. 这个项目是用什么技术实现的

从你本机 `package.json` 可确认，这个项目主要技术栈是：

- **TypeScript**
- **Bun**（构建/测试/CLI 生态）
- **Zod**（配置 schema 校验）
- **jsonc-parser**（支持 JSONC 配置）
- **@opencode-ai/plugin / @opencode-ai/sdk**（OpenCode 插件机制）
- **AST-Grep**（语法树搜索/重写）
- **MCP SDK**（内置 MCP 能力）

本地证据：

- `/home/liang/.opencode/node_modules/oh-my-openagent/package.json`
- `/home/liang/.opencode/node_modules/oh-my-openagent/dist/oh-my-opencode.schema.json`

你应不应该学源码？答案是：

### 值得深入学的部分

- 多 agent 编排思路
- category 路由思路
- 配置 schema 设计
- hooks + skills + commands 的组合方式
- fallback / runtime fallback / capability normalization 这些“工程护栏”

### 不值得一开始死磕的部分

- node\_modules 内部具体 dist 实现细节
- 某个 agent prompt 的全部字句
- 每个内置 hook 的全部源码

如果你的目标是“成为高手用户”，你更应该学 **怎么配置、怎么路由、怎么提任务、怎么给上下文**。如果你的目标是“参与贡献或魔改框架”，再去读官方仓库 `src/`。

***

## 3. 你当前机器上的真实状态

当前状态只记 3 件事：

1. 你的 OpenCode 已经加载 `oh-my-openagent@latest`
2. 你之前确实踩过一次模型配置坑：后台 agent 指向了不可用模型，且 fallback 关闭，导致 background task 失败
3. 这个问题现在已经修复：核心相关 agent 已切到可用模型，`model_fallback` 与 `runtime_fallback.enabled` 已打开

你应把这一节理解为：**当前环境说明 + 一次真实配置修复案例**。

***

## 4. 真正高阶用户的使用心法

## 4.1 不要把它当聊天机器人，用它当“调度器”

错误用法：

- 给它一大坨目标，期望它一次就猜对
- 遇到复杂任务还只让一个 agent 硬做
- 不区分“解释 / 调研 / 规划 / 执行 / 审查”

正确用法：

- **简单任务**：直接说
- **复杂但懒得拆**：`ulw` / `ultrawork`
- **复杂且需要精确控制**：`@plan ...` → `/start-work`
- **只想调研**：显式要求 `explore` / `librarian` / `oracle`

***

## 4.2 先判断任务属于哪类

你以后发任务时，最好脑中先做这个分类：

- **解释类**：让它分析原理，不改代码
- **调研类**：先搜代码库/搜文档
- **实现类**：明确说 add / implement / fix / change
- **规划类**：先用 Prometheus 把边界问清楚
- **验证类**：让 Oracle/Momus/Review 类能力做复查

高手和普通用户最大的差别，不在于写更长 prompt，而在于：

> **你是否知道现在该让谁做、先做什么、后做什么。**

***

## 4.3 最常用的 4 种高级工作流

你实际只需要记住 4 种：

- **直接做**：简单任务直接说
- **先规划**：复杂任务先 `@plan`，再 `/start-work`
- **先调研**：只想理解问题时，明确说“不改代码，只分析”
- **点专家**：已知问题类型时，显式调用 `@oracle` / `@librarian` / `@explore`

一个最实用的经验是：

> **任务越复杂，越不要一上来让一个 agent 硬做到底。**

***

## 5. 哪些本地文件值得改，哪些不要直接改

## 5.1 推荐你改的

### 1) OpenCode 主配置

- `~/.config/opencode/opencode.json`

作用：

- 注册插件
- 配 provider / model catalog
- 配其他 OpenCode 插件

### 2) Oh My OpenAgent 插件配置

- `~/.config/opencode/oh-my-openagent.json`
- 或项目级：`.opencode/oh-my-openagent.json`

作用：

- 改 agent 模型
- 改 categories
- 改 hooks 开关
- 改 commands / mcp / runtime fallback / background task 策略
- 加 prompt\_append
- 配 skills sources

### 3) 自定义 skills 目录

官方 README 明确给出的技能路径：

- `.opencode/skills/*/SKILL.md`
- `~/.config/opencode/skills/*/SKILL.md`

在最初分析时，这两个目录还不存在；现在已经可以正常创建并使用：

- `/home/liang/.opencode/skills`
- `/home/liang/.config/opencode/skills`

这意味着：**skills 目录本来就是给用户自己创建和维护的。**

### 4) 项目级 `.opencode/` 目录

适合做：

- 项目特有 skills
- 项目特有 agent prompt\_append 文件
- 项目特有规则与说明文档
- 避免把个性化配置污染全局用户配置

***

## 5.2 不推荐你直接改的

### 1) `node_modules/oh-my-openagent/dist/*`

原因：

- 升级会被覆盖
- 这是编译产物，不适合长期维护
- 改了也不利于迁移和复用

### 2) 插件内置 schema / d.ts 产物

例如：

- `dist/config/schema/skills.d.ts`
- `dist/config/schema/hooks.d.ts`
- `dist/config/schema/categories.d.ts`

这些文件适合你**读来理解能力边界**，不适合直接魔改。

***

## 6. 如何做你自己的自定义 skill

这是你最应该学会的定制方式。

根据本地 README 与 schema，可以确认 skills 支持：

- 自定义 `SKILL.md`
- `skills.sources`
- `enable` / `disable`
- 单 skill 元数据：`description`、`template`、`model`、`agent`、`subtask`、`argument-hint`、`allowed-tools` 等

本地证据：

- README: `Add your own: .opencode/skills/*/SKILL.md or ~/.config/opencode/skills/*/SKILL.md`
- `dist/config/schema/skills.d.ts`

### 推荐做法

如果是“只对某个项目生效”的技能：

- 放在：`.opencode/skills/<skill-name>/SKILL.md`

如果是“所有项目都能复用”的技能：

- 放在：`~/.config/opencode/skills/<skill-name>/SKILL.md`

### 一个推荐结构

```text
.opencode/
  skills/
    learning-roadmap/
      SKILL.md
```

### 示例：做一个“源码学习助手” skill

`SKILL.md` 可以写成这种风格：

```md
---
name: learning-roadmap
description: 为当前代码库生成分层学习路径，优先理解架构、数据流和关键扩展点
argument-hint: 想学习的主题或模块名
allowed-tools:
  - read
  - grep
  - glob
  - lsp_symbols
  - lsp_goto_definition
---

你的任务不是直接实现功能，而是帮助用户高效掌握代码库。

必须：
- 先给出模块地图
- 再给出推荐阅读顺序
- 区分“必须掌握”“按需理解”“可跳过”
- 尽量结合当前仓库真实文件路径

不要：
- 没有依据地推测
- 一上来输出长篇空话
```

这种 skill 特别适合你，因为你现在的诉求就是：**让 agent 帮你更快学会项目，而不是只帮你写代码。**

### 6.1 skill 到底是怎么触发的

很多人做了 skill 之后，最困惑的不是怎么写，而是：

- 为什么有时候会触发
- 为什么有时候不触发
- 为什么我明明写了 skill，但 agent 没用它

你要先建立一个正确认知：

> **skill 不是 shell 命令，也不是“你写了文件就一定会自动执行”的钩子。它更像是一个可被 agent 选用的专业能力包。**

也就是说，skill 是否被使用，取决于三件事：

1. skill 是否被正确发现（目录位置对不对、文件名对不对）
2. 当前任务是否和这个 skill 的 `name` / `description` / 说明内容高度相关
3. 你有没有在提示词里明确引导 agent 去使用它

### 6.2 什么情况下更容易触发

最稳妥的触发方式，不是“等它猜”，而是**显式点名**。

例如你已经有一个：

- `learning-roadmap`

那你应该优先这样说：

```text
Use learning-roadmap to help me understand this repository.
```

或者：

```text
Use learning-roadmap for the auth module and give me a 3-step study plan.
```

或者中文直接说：

```text
请使用 learning-roadmap，帮我学习这个仓库，先告诉我第一批必须看的文件。
```

这类说法之所以更容易触发，是因为它同时具备了：

- 明确 skill 名字
- 明确任务目标
- 明确输出预期

### 6.3 什么情况下不容易触发

下面这些说法，就算你已经写了 skill，也**不一定**会触发：

```text
帮我看看这个项目
```

```text
分析一下
```

```text
给我说说这个仓库
```

它们的问题是：

- 太泛
- 没点名 skill
- 没说清楚想要“学习路线”还是“架构分析”还是“功能实现”

另外，下面这种情况也常常不触发：

- 你的 skill 明明是“学习型”，但你发的是“帮我修 bug”
- 你的 skill 名字太抽象，`description` 又写得不清楚
- 你的 skill 和当前任务没有明显相关性

所以不要把 skill 理解成“写完就会被无条件自动调用”，而应该理解成：

> **你给系统增加了一个更容易被选中的专业模式，但你仍然要学会把任务说得像是在调用这个模式。**

### 6.4 怎样让 skill 更稳定地触发

有 5 个实战技巧。

#### 技巧 1：skill 名字要直白，不要文艺

好名字：

- `learning-roadmap`
- `architecture-audit`
- `doc-summarizer-cn`
- `bug-repro-checklist`

不好的名字：

- `deep-thinker`
- `my-helper`
- `assistant-plus`

原因很简单：名字越贴近任务意图，agent 越容易判断“该用它”。

#### 技巧 2：`description` 要写任务，不要写梦想

好的 `description` 应该告诉系统：

- 这是干什么的
- 适合什么任务
- 产出是什么

例如：

```yaml
description: Analyze a codebase or topic and produce a practical learning roadmap.
```

就比“帮助用户更高效成长”这种描述好得多。

#### 技巧 3：提示词里同时说“用 skill + 做什么”

不要只写：

```text
Use learning-roadmap
```

更好的是：

```text
Use learning-roadmap to analyze the payment module and tell me which files I should read first.
```

这样系统既知道要用哪个 skill，也知道具体目标。

#### 技巧 4：让 skill 的输出结构固定

你在 `SKILL.md` 里给定固定输出结构，触发后体验会明显更稳定。

例如你现在这个 `learning-roadmap`，就已经约束了：

- Goal
- First read
- Second read
- Can skip for now
- 3-step practice plan

这会让 skill 更像“工具”而不是“随机发挥的 prompt”。

#### 技巧 5：按场景分 skill，不要做一个超级 skill

新手最容易犯的错是：

- 想把“学习源码、做文档、查风险、改代码、写测试”全塞进一个 skill

结果就是 skill 的边界模糊，系统更难判断什么时候该用它。

更好的做法是拆分：

- `learning-roadmap`：学习路线
- `architecture-audit`：架构审查
- `doc-summarizer-cn`：中文文档总结
- `bug-repro-checklist`：复现与排查步骤

### 6.5 一个简单判断：为什么这次没触发

如果你发现“我明明有这个 skill，但这次没触发”，可以用下面四问快速定位：

1. 路径对不对？是不是放在 `.opencode/skills/<name>/SKILL.md` 或 `~/.config/opencode/skills/<name>/SKILL.md`
2. 名字和描述清不清楚？是否真的对应当前任务
3. 我有没有显式提 skill 名称
4. 当前任务是不是其实更像另一个 skill / agent / category

通常前两项是“没被发现”，后两项是“没被选中”。

### 6.6 你这个 `learning-roadmap` 的推荐使用方式

你现在本地已经有这个 skill：

- `~/.config/opencode/skills/learning-roadmap/SKILL.md`

最推荐的用法不是一句空话，而是下面这种结构：

#### 用法 1：学整个仓库

```text
Use learning-roadmap to help me learn this repository. Focus on the first files I must read.
```

#### 用法 2：学某个模块

```text
Use learning-roadmap for the auth module. Give me a first-read and second-read list.
```

#### 用法 3：学技术专题

```text
Use learning-roadmap to help me understand how task delegation works in this project.
```

#### 用法 4：输出中文版本

```text
请使用 learning-roadmap，帮我学习这个仓库，并用中文输出最小必要阅读路径。
```

如果你想再稳一点，可以把“输出格式”也说死：

```text
请使用 learning-roadmap，分析当前仓库，并按 Goal / First read / Second read / Can skip for now / 3-step practice plan 输出。
```

***

## 6.7 除了 skill，还能配置什么

可以，而且很多场景下**比 skill 更底层、更稳定**。

如果你问“能不能像 rules 一样定制行为”，答案是：**可以，但不一定叫 rules 这个名字。**

在 oh-my-openagent / opencode 这一套里，常见的“规则化定制入口”主要有 8 类。

### 1) `prompt_append`：最直接的软规则

这是最像“rules”的方式。

你可以给 agent 或 category 追加长期规则，例如：

- 默认中文
- 先给结论再给证据
- 遇到复杂任务优先先搜再答
- 文档统一按固定标题结构输出

示例：

```jsonc
{
  "agents": {
    "sisyphus": {
      "prompt_append": "默认用中文；复杂问题先总结结论，再列证据。"
    }
  },
  "categories": {
    "writing": {
      "prompt_append": "输出文档时优先使用中文标题，并给出可执行建议。"
    }
  }
}
```

如果你的规则很多，推荐用 `file://` 指向外部文件，而不是把一大段文字直接塞进 JSON。

### 2) `prompt`：直接替换系统提示

这个比 `prompt_append` 更猛，因为它会直接替换掉原有系统 prompt。

适合：

- 你非常清楚要重写哪个 agent 的行为

不适合：

- 只是想加一点偏好

对大多数用户来说：

> **优先用** **`prompt_append`，不到必要不要直接覆盖** **`prompt`。**

### 3) 项目级 `AGENTS.md` / `README.md`：项目上下文规则

从功能描述和 hook 名称可以看出，系统支持目录级上下文注入，例如：

- `directory-agents-injector`
- `directory-readme-injector`
- `rules-injector`

这说明项目目录里的规则性文档，本身就是一类长期上下文。

你可以把下面这类内容写进项目文档：

- 本项目禁止直接改数据库 schema
- 所有接口错误必须返回统一格式
- 文档统一中文
- 前端样式必须复用既有 design token

这类方式的优点是：

- 跟项目绑定
- 团队可共享
- 比临时聊天里一遍遍重复更稳定

### 4) Hooks：行为护栏级定制

如果说 skill 是“专业能力包”，那 hook 更像“系统行为开关”。

你可以配置：

- `disabled_hooks`
- 打开或关闭某些内置护栏行为

常见场景：

- 不想看某些提醒 → 关 `startup-toast`
- 不想自动做某类检查 → 关对应 hook
- 想保留稳定性 → 不要乱关 `runtime-fallback`、`session-recovery`、`todo-continuation-enforcer`

所以从“rules”视角看，hook 更像是：

> **不是告诉模型怎么说，而是告诉系统什么时候插手、什么时候兜底。**

### 5) Commands：工作流规则入口

像这些命令：

- `@plan`
- `/start-work`
- `/init-deep`
- `refactor`

本质上不是“配置项”，但它们定义了你如何进入某套工作流。

如果你的目标是把协作方式规则化，命令本身就是规则的一部分。

例如你可以给自己定一条操作规则：

- 小任务直接做
- 中大任务必须先 `@plan`
- 跨模块任务必须走 `/start-work`

这类规则未必写进 JSON，但它非常影响你能不能玩转这个系统。

### 6) MCP：能力来源层的定制

你还可以控制哪些外部能力被打开或关闭，例如：

- `websearch`
- `context7`
- `grep_app`

对应配置是：

```jsonc
{
  "disabled_mcps": ["websearch"]
}
```

如果你不想让系统查外部网络、或者想减少检索来源，这类配置就很有用。

### 7) LSP / Browser / Tmux：执行环境级定制

这也是很多人容易忽略的部分。

你不仅能改“提示”，还可以改“执行环境”：

- `lsp`：控制语言服务
- `browser_automation_engine`：控制浏览器自动化提供者
- `tmux`：控制后台 subagent 是否在 tmux pane 里运行

这类配置不是“rules”，但它会直接改变 agent 能做什么、看见什么、验证到什么程度。

### 8) Permissions / Tools：边界规则

配置文档里还能看到 agent 级权限控制，例如：

- `permission.edit`
- `permission.bash`
- `permission.webfetch`

这其实就是最硬的规则：

- 允许什么
- 禁止什么
- 某些命令是否必须询问

如果你未来想把某些 agent 变成“只读顾问”或“禁止乱跑 bash”的模式，这一层比 skill 更硬。

### 6.8 如果你想要“rules”，最推荐的组合方式

如果你的真实目标是“让系统长期遵守我的偏好”，我最推荐的不是只做一种，而是下面这个组合：

#### 个人长期偏好

- 放在 `~/.config/opencode/oh-my-openagent.json`
- 用 `prompt_append`

适合放：

- 默认中文
- 输出风格
- 结论先行

#### 项目规则

- 放在项目目录文档里，例如 `AGENTS.md` / `README.md` / `.opencode` 相关文件

适合放：

- 代码规范
- 模块边界
- 禁止事项
- 提交流程

#### 特定任务能力

- 放在 `skills`

适合放：

- 学习路线
- 架构审查
- 文档总结
- bug 复现流程

#### 系统护栏

- 放在 hooks / permissions / fallback 配置

适合放：

- 稳定性
- 风险控制
- 权限边界

你可以把它理解成：

- `skill` = 某个专业角色
- `prompt_append` = 说话和做事风格
- `AGENTS.md / README` = 项目背景规则
- `hooks / permissions` = 系统护栏

这四层叠起来，才是最接近“rules system”的玩法。

***

## 6.9 OpenCode 配置技巧总览：只记住这张地图就够了

想真正玩转 oh-my-openagent，你只需要记住 4 层：

- **OpenCode 层**：provider、model catalog、LSP、MCP、browser、tmux、background concurrency、notification
- **插件层**：agents、categories、fallback、prompt\_append、hooks、disabled\_\*、skills
- **项目层**：`AGENTS.md`、`README`、项目规则、目录上下文
- **skill 层**：某类任务的固定套路与输出结构

最重要的不是字段，而是“这类问题该在哪一层解决”：

- **个人长期偏好** → `prompt_append` / 用户级配置
- **项目规则** → `AGENTS.md` / README / 项目级配置
- **任务套路** → `skill`
- **稳定性与权限** → fallback / hooks / permissions / background\_task

把常见配置再压缩一下：

### 1) Agents

控制“谁在干活”：模型、variant、权限、工具、prompt\_append、是否禁用。

### 2) Categories

控制“这类任务怎么做”：例如 `quick`、`deep`、`writing`、`visual-engineering`。高手先路由任务，再选模型。

### 3) Fallback

控制“出事时怎么办”：`fallback_models` 解决主模型失败后的退路，`runtime_fallback` 解决运行时自动切换。

### 4) `prompt_append` / `prompt` / `file://`

这是最像 rules 的入口。一般优先用 `prompt_append`，大段规则用 `file://` 外部文件，少直接覆盖 `prompt`。

### 5) 项目文档注入

`AGENTS.md` / `README` 更适合放项目知识与团队规则，不适合放个人偏好。

### 6) Permissions

真正的硬规则。想限制 agent，不要只说“别这么做”，而要用权限层限制 `edit`、`bash`、`webfetch` 等。

### 7) MCP / LSP / Browser / Tmux / Background Task

这些属于执行环境层：

- **MCP** 决定信息来源和外部能力
- **LSP** 决定它更像文本搜索器还是 IDE 协作者
- **Browser** 决定它能不能真实操作网页
- **Tmux / Background Task** 决定多 agent 的并行体验与资源控制

一句话总记忆：

> **OpenCode 负责底座，oh-my-openagent 负责编排，项目文档负责上下文，skill 负责专业模式。**

***

## 6.10 应用实例：只看这 6 个就够了

### 场景 1：长期默认中文输出

- 放在用户级 `prompt_append`
- 如果文档任务很多，再给 `writing` category 单独加规则
- 这是个人偏好，不要写进项目规则

### 场景 2：某个项目有团队硬规则

- 规则写进 `AGENTS.md` / `README`
- 输出风格再补到项目级 `prompt_append`
- 项目规则优先跟仓库走，不要只放你本地

### 场景 3：把“学习新项目”流程化

- 做 `learning-roadmap` skill
- 放到 `~/.config/opencode/skills/...`
- 提问时显式写 `Use learning-roadmap ...`

### 场景 4：模型失败时别崩掉

- 主模型之外，给关键 agent 配 `fallback_models`
- 打开 `model_fallback` 和 `runtime_fallback`
- 这是稳定性配置，不是文风配置

### 场景 5：让某个 agent 只读不乱改

- 用 `permission` 限制 `edit` / `bash`
- 不要只靠一句“请不要修改文件”

### 场景 6：写文档、写代码、做调研分开处理

- 文档任务 → `writing`
- 深度分析 → `deep` / `ultrabrain`
- 小修改 → `quick`
- 需要浏览器验证 → Browser Automation
- 多文件重构 → LSP

最后只记住一个判断框架：

- **个人偏好** → 用户级配置 / `prompt_append`
- **项目规则** → `AGENTS.md` / README / 项目级配置
- **任务套路** → `skill`
- **稳定性与边界** → fallback / hooks / permissions / background\_task

***

## 7. 如何修改本地配置来实现定制需求

## 7.1 最常用的四种定制手段

### 手段 1：改 agent 模型

适合：你想让某个 agent 更稳、更快、或更便宜。

例如：

```jsonc
{
  "agents": {
    "oracle": { "model": "openai/gpt-5.4", "variant": "high" },
    "explore": { "model": "google/gemini-3-flash" }
  }
}
```

### 手段 2：改 category 路由

适合：你想控制 `task(category=...)` 背后到底落到什么模型。

例如：

```jsonc
{
  "categories": {
    "quick": { "model": "openai/gpt-5.4-mini" },
    "writing": { "model": "google/gemini-3-flash" },
    "visual-engineering": {
      "model": "google/antigravity-gemini-3.1-pro",
      "variant": "high"
    }
  }
}
```

### 手段 3：给 agent 或 category 加 `prompt_append`

适合：你想增加自己的风格规则，而不是完全重写系统 prompt。

例如：

```jsonc
{
  "agents": {
    "sisyphus": {
      "prompt_append": "所有回复优先中文；做方案时先给结论，再给证据。"
    }
  },
  "categories": {
    "writing": {
      "prompt_append": "文档默认输出为中文，强调结构清晰和可执行建议。"
    }
  }
}
```

官方配置文档还支持 `file://` 方式从外部文件加载 prompt / prompt\_append，这比直接把长文本塞进 JSON 更好维护。

### 手段 4：控制 hooks / commands / skills

适合：你想简化系统或打开某些能力。

例如：

```jsonc
{
  "disabled_hooks": ["startup-toast"],
  "disabled_commands": ["cancel-ralph"],
  "disabled_skills": ["playwright"]
}
```

但我建议你：

- **先少禁用，先观察行为**
- 真遇到明确痛点再关
- 特别是 `todo`、fallback、recovery、notification 相关能力，不要一开始就砍

***

## 8. 你现在最值得立刻修改的地方

这一节更适合当成“优先级建议清单”来读。

## 8.1 修复不可用模型问题

你之前的 `explore` / `librarian` / `atlas` / `sisyphus-junior` 曾使用 `openai/gpt-5-nano`，并实际出现过 `model not found`。

### 建议方向

如果你现在主要可稳定使用 OpenAI 与 Antigravity Google，建议把这些 utility / orchestration agent 调整到更稳妥的可用模型上。

如果你以后再次遇到类似问题，一个更稳的方向是：

```jsonc
{
  "model_fallback": true,
  "runtime_fallback": {
    "enabled": true
  },
  "agents": {
    "explore": { "model": "openai/gpt-5.4-mini" },
    "librarian": { "model": "openai/gpt-5.4-mini" },
    "atlas": { "model": "openai/gpt-5.4-mini" },
    "sisyphus-junior": { "model": "openai/gpt-5.4-mini" }
  },
  "categories": {
    "quick": { "model": "openai/gpt-5.4-mini" },
    "deep": { "model": "openai/gpt-5.4-mini" }
  }
}
```

这不一定是最优性能配置，但比“直接指定不存在的模型再把 fallback 全关掉”稳得多。

***

## 8.2 用项目级 `.opencode/skills` 做你的学习型工具箱

你想“成为最会使用这个插件的人”，最该做的不是只问一次，而是构建自己的 skill 资产。

我建议你至少做 3 类技能：

### 1) `learning-roadmap`

作用：针对任何仓库，生成学习顺序。

### 2) `architecture-audit`

作用：快速看清模块关系、边界、数据流、风险点。

### 3) `doc-summarizer-cn`

作用：把官方文档/README/变更说明压缩成中文高信号摘要。

这些 skill 一旦做好，你以后学任何项目都会更快。

***

## 8.3 把“全局配置”和“项目配置”分层

推荐原则：

### 全局配置放这里

- `~/.config/opencode/opencode.json`
- `~/.config/opencode/oh-my-openagent.json`

适合放：

- provider 认证与模型目录
- 你所有项目都通用的 agent 习惯
- 全局 skill

### 项目配置放这里

- `.opencode/oh-my-openagent.json`
- `.opencode/skills/...`

适合放：

- 某个项目专属的学习 skill
- 该项目的 prompt\_append
- 项目语境约束

这样做的好处是：

- 不污染其他项目
- 更容易版本管理
- 更方便团队共享

***

## 9. Hooks、Categories、Commands 到底怎么理解

## 9.1 Hooks：行为护栏

从本地 schema 可以确认，内置 hook 很多，重点包括：

- `todo-continuation-enforcer`
- `start-work`
- `session-recovery`
- `runtime-fallback`
- `keyword-detector`
- `background-notification`
- `comment-checker`
- `rules-injector`
- `directory-readme-injector`

建议理解方式：

- **Commands** 是主动触发
- **Agents** 是执行者
- **Hooks** 是系统级行为修正器/护栏

你要成为高手，必须知道：**很多“它为什么会这样做”的答案，不在 prompt，而在 hook。**

## 9.2 Categories：比模型名更重要

本地 schema 里内置 category 包括：

- `deep`
- `unspecified-high`
- `visual-engineering`
- `ultrabrain`
- `artistry`
- `quick`
- `unspecified-low`
- `writing`

高手思维应该是：

- 不要先想“用哪个模型”
- 先想“这是什么类型的任务”

例如：

- UI / CSS / 交互 → `visual-engineering`
- 难逻辑 / 架构 → `ultrabrain`
- 深度研究执行 → `deep`
- 文档输出 → `writing`
- 小修改 → `quick`

***

## 9.3 Commands：工作流入口

本地 schema 里能确认的内置命令有：

- `init-deep`
- `ralph-loop`
- `ulw-loop`
- `cancel-ralph`
- `refactor`
- `start-work`
- `stop-continuation`
- `remove-ai-slops`

其中最值得你反复练熟的只有三个：

### `/init-deep`

适合：刚接触一个新仓库时，建立层级化 `AGENTS.md` 语境。

### `@plan` / `Prometheus`

适合：复杂任务先访谈，再出计划。

### `/start-work`

适合：让 Atlas 基于计划持续推进。

***

## 10. 你要不要直接改 node\_modules 里的 prompt / dist 文件？

我的建议很明确：**默认不要。**

只有在下面场景才考虑：

1. 你准备长期 fork 这个项目
2. 你愿意维护升级差异
3. 你明确知道你在修改哪类 agent prompt 机制

否则，99% 的定制需求都应该优先用下面方式解决：

- `oh-my-openagent.json` 配置
- 项目级 `.opencode/oh-my-openagent.json`
- `.opencode/skills/*/SKILL.md`
- `prompt_append`
- `skills.sources`
- `disabled_*` 和 `fallback_*` 配置

***

## 11. 我对你当前配置的实战建议

如果我是站在“高阶用户、但不是插件维护者”的角度，我会建议你按这个顺序升级：

### 第一步：先修稳定性

- 打开 `model_fallback`
- 打开 `runtime_fallback.enabled`
- 把 `gpt-5-nano` 改成你当前环境确定可用的模型

### 第二步：建立自己的 skills 目录

- 先建 `~/.config/opencode/skills`
- 再为当前项目建 `.opencode/skills`

### 第三步：把“学习项目”也 skill 化

别只用它写代码，也让它：

- 画模块地图
- 给学习路线
- 提炼仓库规则
- 输出中文总结

### 第四步：形成固定工作流

以后复杂任务统一这样做：

1. 先 `@plan`
2. 再 `/start-work`
3. 必要时显式加 `oracle` / `librarian` / `explore`
4. 输出文档时走 `writing` 风格约束

***

## 12. 你要成为“最会用这个插件的人”，应该训练什么能力

最后给你一个高手能力清单。

### 必须掌握

- 知道什么时候直接 prompt，什么时候 `ulw`
- 知道什么时候先 `@plan`
- 知道什么时候该调 `oracle` / `librarian` / `explore`
- 知道 category 比 model name 更关键
- 知道本地定制优先改配置与 skills，不要乱改 dist

### 进阶掌握

- 为不同项目维护不同 `.opencode/skills`
- 用 `prompt_append` 建立个人工作风格
- 调整 fallback / concurrency / hooks，做稳定性优化
- 让 agent 帮你“学习代码库”，而不是只“生成代码”

### 顶级掌握

- 你已经形成自己的 skill 套件
- 你知道如何把任务建模成：研究 / 规划 / 执行 / 验证
- 你知道如何通过 category + skill 组合来提升产出质量
- 你知道什么时候该依赖框架默认，什么时候该显式约束

***

## 13. 本次分析使用到的本地与官方依据

### 本地文件

- `/home/liang/.opencode/node_modules/oh-my-openagent/package.json`
- `/home/liang/.opencode/node_modules/oh-my-openagent/README.md`
- `/home/liang/.opencode/node_modules/oh-my-openagent/README.zh-cn.md`
- `/home/liang/.opencode/node_modules/oh-my-openagent/dist/oh-my-opencode.schema.json`
- `/home/liang/.opencode/node_modules/oh-my-openagent/dist/config/schema/skills.d.ts`
- `/home/liang/.opencode/node_modules/oh-my-openagent/dist/config/schema/hooks.d.ts`
- `/home/liang/.opencode/node_modules/oh-my-openagent/dist/config/schema/categories.d.ts`
- `/home/liang/.opencode/node_modules/oh-my-openagent/dist/config/schema/commands.d.ts`
- `/home/liang/.config/opencode/opencode.json`
- `/home/liang/.config/opencode/oh-my-openagent.json`

### 官方文档

- `docs/guide/overview.md`
- `docs/guide/orchestration.md`
- `docs/guide/installation.md`
- `docs/guide/agent-model-matching.md`
- `docs/reference/configuration.md`

***

## 14. 给你的直接行动建议

如果你现在只做三件事，我建议是：

1. **先修掉** **`gpt-5-nano`** **与 fallback 关闭导致的后台 agent 不稳定问题**
2. **创建** **`~/.config/opencode/skills`** **与项目级** **`.opencode/skills`**
3. **先做一个你自己的** **`learning-roadmap`** **skill**

这样你就不只是“会用 oh-my-openagent”，而是开始拥有自己的 agent operating system。
