# G. 架构图生成能力调研（为 project-decoder 增补 diagram-guide）

> 调研日期：2026-09-06。**网络受限说明**：本调研期间主会话 Bash 沙箱与 WebFetch 均无法访问 GitHub（api.github.com 返回空、github.com 页面超时），子代理通道触发并发限制；实际可用的通道为 WebSearch 与 web_reader（GitHub 网页与 c4model.com 可读，api.github.com 不可读）。因此：**各仓库 star 数未能核实，本笔记不作 star 断言**；机制描述来自实际抓取的 README/页面原文。

## 1. 社区架构图类 skill（核实存在）

| 仓库 | 来源核实 | 定位与可抄点 |
|---|---|---|
| [WH-2099/mermaid-skill](https://github.com/WH-2099/mermaid-skill) | WebSearch + web_reader 抓取 README | 支持官方全部 **23 种图型**；结构为 `.claude/skills/mermaid/SKILL.md` + `references/` 按图型分文件，**GitHub Action 每周从官方 mermaid-js/mermaid 仓库同步语法文档**——"语法细节做成按需加载的 reference、且自动跟官方演进保持同步"的机制最值得抄（正合渐进披露 + 单一事实源） |
| [SpillwaveSolutions/design-doc-mermaid](https://github.com/SpillwaveSolutions/design-doc-mermaid) | WebSearch + web_reader 抓取 README | 面向设计文档场景：**on-demand guide loading**（按需加载画法指南）、**code-to-diagram**（从代码生成图）、Python 工具辅助（提取既有文档中的 mermaid 块、mmdc 渲染校验）——"生成前先跑工具校验语法"与我们的确定性脚本原则同源 |
| [veelenga/claude-mermaid](https://github.com/veelenga/claude-mermaid) | WebSearch | MCP server：浏览器实时预览 mermaid（live reload）+ 内置专家 skill——交互式预览路线，落盘 Markdown 场景用不上，记录备查 |
| [Agents365-ai/mermaid-skill](https://github.com/Agents365-ai/mermaid-skill) | WebSearch | 自然语言→`.mmd` 源，**先校验语法再导出**，可渲染 PNG/SVG/PDF——"先校验后落盘"顺序与我们一致 |
| [alexanderop/walkthrough](https://github.com/alexanderop/walkthrough) | 已在 E 笔记深读（130★） | 可点击 Mermaid 的 HTML 导览：5-12 概念节点硬上限、每节点带真实代码片段防编造、产出前 checklist——已抄入 SKILL.md |

## 2. 图即代码工具对比（star 未核实，成熟度为生态常识）

| 工具 | 一句话 | 对"落盘 Markdown"场景的结论 |
|---|---|---|
| Mermaid | Markdown 原生 ```mermaid 代码块 | **默认选择**：GitHub/GitLab/VSCode/Typora 均原生渲染，零额外工具链，diff 友好；语法坑有解（见 diagram-guide） |
| [plantuml-stdlib/C4-PlantUML](https://github.com/plantuml-stdlib/C4-PlantUML) | C4 模型的 PlantUML 标准库 | C4 图形最正宗，但要 PlantUML 渲染链（Java/graphviz 或服务端），Markdown 落盘不能直接渲染——不选，**借其"人物/外部系统/存储/队列固定形状"的语义形状约定** |
| [mingrammer/diagrams](https://github.com/mingrammer/diagrams) | Python 图即代码（云厂商图标） | 部署图/云拓扑可用，产出 PNG 需 graphviz——图进 md 不可渲染，不选 |
| [terrastruct/d2](https://github.com/terrastruct/d2) | 声明式图语言，布局器强 | 语法美但生态渲染链独立，md 不原生支持——不选 |
| [structurizr/dsl](https://github.com/structurizr/dsl) | Simon Brown 官方 C4 工具链 | model-as-code + 多视图派生是最正统的 C4 工程，但同样脱离 md 生态——**借其"一个模型派生多张图"的思想**：正文模块表是模型，图是从模型派生的视图 |

**Mermaid 的 C4 图（C4Context 等）官方标注 experimental**（mermaid.js.org/syntax/c4.html；本次未能抓取核实，训练知识、置信中等），且渲染支持参差——diagram-guide 规定：C4 思想照用（每图回答一个问题、分层画法），形式用 flowchart/sequenceDiagram。

## 3. C4 画法指导（c4model.com/diagrams，web_reader 实抓）

- C4 得名于四个静态结构图层级：**system context、container、component、code**（code 层通常不画）。
- 原文要点：不必画全部层级，**"context + container 对大多数团队就够起步"**；每张图服务明确的读者与问题，不要试图一张图回答所有问题。
- 对 diagram-guide 的转译：一图流（容器/组件级）+ 上下文图按需 + 时序图讲主链；每图开头写"回答什么问题"。

## 4. 综合进 diagram-guide 的 8 条规则（含出处）

1. **图与正文同源**：先写模块/类表（模型），图是从模型派生的视图；每节点必须在正文找得到对应（structurizr 思想 + walkthrough checklist）。
2. **每图回答一个问题**、开头写明（c4model.com）；节点 5-12、边 ≤16，超了拆图或 subgraph 上卷（walkthrough 硬上限）。
3. **默认 Mermaid**，GitHub 原生渲染；不用 mermaid C4 实验语法（§2）；不 ASCII/Mermaid 双写（两份图必漂移）。
4. **图类型按问题选**：分层组件图（graph TB）、主链时序图（sequenceDiagram）、上下文图（graph LR）、状态机（stateDiagram-v2）、部署拓扑（有 docker/k8s 事实源才画）（C4 分层 + c4model.com）。
5. **语义形状/样式约定**：入口/存储/外部系统/核心模块固定形状或 classDef，只服务语义不装饰（C4-PlantUML 约定）。
6. **语法军规防翻车**：nodeId 全 ASCII；中文与特殊字符标签一律 `["..."]` 引号包裹；文本内禁裸括号；subgraph id ASCII（mermaid 语法通识，见官方 docs syntax/flowchart）。
7. **生成前机械校验**：`scripts/check_mermaid.py` lint（确定性脚本干脏活，Understand-Anything/E 笔记原则）；环境有 mmdc 则渲染验证（design-doc-mermaid / Agents365 的先校验后落盘）。
8. **部署图只认构建/部署文件为事实源**（compose/k8s/构建脚本），README 不算（铁律 2 的图版）。
