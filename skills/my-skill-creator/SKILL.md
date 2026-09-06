---
name: my-skill-creator
description: >
  写/改 skill 的统一入口：先完整执行官方 skill-creator 的闭环（意图→草稿→实测→迭代），
  再按《全网高质量 Skills 调研与写作指南》的军规、模板与检查清单把关。
  凡用户要求写新 skill、新建 SKILL.md、优化或改进既有 skill、讨论"这该不该做成 skill"、
  或提到 skill creator / my-skill-creator 时使用。
  不用本 skill 的情形：把当前会话蒸馏成可安装 skill 走 session-to-skill；
  会话经验入库走 session-to-knowledge；改 AGENTS.md/项目文档但不含 skill 文件时不走。
---

# My Skill Creator（路由壳：编排 + 把关，不含独立流程）

本 skill 是壳：**流程原语是官方 skill-creator，写作规范的单一事实源是指南文件**。壳只做两件事——按正确顺序调起原语、把指南对应章节挂到正确阶段。绝不复制二者内容（复制必漂移）。

## 第 1 步（必须先做）：调起官方流程

**REQUIRED SUB-SKILL:** Call the Skill tool with `skill-creator:skill-creator`——它的完整闭环（Capture intent → Draft → Test prompts → Review → Improve）就是本次的主流程，一步不减。本壳后续步骤叠加在其阶段之上，不替代它。

## 第 2 步：按阶段挂指南（条件加载，一次只读当前阶段要的节）

指南固定路径：`/home/liang/.zcode/workspace/default/skill-research/全网高质量Skills调研与写作指南.md`

- **动笔前**（Draft 之前）：读指南 **§0 十条军规** + **附录 A 起步模板**——先用模板立骨架，再往里填 skill-creator 访谈得到的意图。
- **写 description 时**：读指南 **1.3/1.4 节**（frontmatter 硬约束）——第三人称、Use when 具体触发、负向边界、≤1024 字符；要工程化调触发再读 **附录 C**（20 条评测法）。
- **立项存疑时**（用户想法可能不该做成 skill）：读 **附录 D**（资格三问 + 该做成什么/移走什么）。
- **交付前**（宣布完成之前）：逐项过 **附录 B 自检清单**，任一条不过先改再交。

## 红线

- 绝不在本壳或 skill 产物里复制 skill-creator 正文或指南正文——原语更新、指南迭代时，复制体会静默过时
- 指南路径失效（换机/移动）→ 问用户新位置，绝不凭记忆重写军规
- 两层冲突时：流程听 skill-creator，写法听指南；冲突点如实告知用户，不静默取舍
