# Atlas — AI 成长工作站（零模型 · 零网络）

> Linux 桌面应用：把一个 markdown 目录变成 **可检索的知识库 + 同源题库 + 缺口队列**。
> 本应用**不配置任何模型、没有任何网络代码**——一切 LLM 能力由你已有的编码代理（ZCode / Codex / Claude Code）通过**收发件箱文件协议**提供。产品需求见 `../PRD.md`（v0.10）。

## 快速开始

```bash
# 依赖：JDK 17+（构建与运行）
JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle run      # 开发运行
JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle packageReleaseDeb   # 产出 deb

# 一键更新（编译→测试→打包→安装本机）：./update.sh
# sudo 密码来源：环境变量 ATLAS_SUDO_PASS > ~/.atlas-sudo-pass > 交互式输入（均不写入仓库）
echo '你的sudo密码' > ~/.atlas-sudo-pass && chmod 600 ~/.atlas-sudo-pass
./update.sh
```

1. 启动后在向导里选择一个 markdown 目录（例如你的知识库）；
2. Atlas 会创建 `<库根>/atlas/` 协作目录并在**三档隐私边界**下建立全文索引（FTS5 trigram）；
3. 建议把 `<库根>/atlas/` 登记进你知识库的 AGENTS.md 知识地图。

## 功能（对应 PRD）

- **知识库**：目录树 + markdown 只读预览 + 检索工作台（≥3 字 trigram 全文检索，<3 字 LIKE 兜底）+ 来源面板 + 一键**上下文包**（检索结果组装后交给 agent）+ **Ctrl+K 全局搜索浮层**（↑↓ 选择、回车打开、Tab 转入检索工作台）
- **同源题库（首期）**：题库左侧递归映射 `knowledge-base` 下的 Markdown 文档，可切换源文档；当前只解析 `knowledge-base/language/kotlin/01-语法基础.md`，识别 `**Qn: 问题**`，下一个 Q 或文件结尾前的 Markdown 都是答案；点击题目展开答案，编辑后局部写回原文；文件外部修改后 ≤3s 自动重载
- **学习/闪卡**：本轮暂时隐藏学习入口、闪卡转化和 FSRS 复习 UI；旧 `atlas/cards.md` 保留但不再作为当前题库内容源
- **收件箱**：agent 写入 `atlas/inbox/` 的题目候选（每块仅含 `kind: question`、`q`、`answer`）在工作台逐条确认/编辑/丢弃，支持按文件**整批忽略**；旧闪卡候选兼容转换为题目
- **Git 源**：本地仓库提交流浏览 + diff + 一键写 outbox 解读任务（学别人的提交）
- **自动同步**：第三方进程（agent/编辑器）改动同源题目文档或 inbox 后 ≤3s 自动重载

## 与 agent 的协作协议

| 目录 | 方向 | 用途 |
|---|---|---|
| `atlas/inbox/` | agent → Atlas | 待确认题目（kind: question + q + answer，块间 `---` 分隔） |
| `atlas/outbox/` | Atlas → agent | 任务文件（type: feynman / interview / interpret / cardgen） |
| `knowledge-base/language/kotlin/01-语法基础.md` | 人/Atlas ↔ Atlas | 首期唯一题库源文档；题目和答案均在同一 Markdown 文件内 |
| 库目录本身 | 双向 | 任何工具写入的 md 会被增量索引自动收录 |

## 与 PRD 的已知偏差（ADR 简记）

1. PRD NFR-6 写 SQLDelight，MVP 改用 **xerial sqlite-jdbc 直连**（省代码生成层，后续可换回）；
2. PRD 写 mikepenz markdown 渲染库，MVP 改用**内置轻量渲染器**（零依赖，支持标题/列表/引用/代码块/行内标记；复杂 GFM 用「系统编辑器打开」）；
3. PRD FR-A3 语义型检索为 P2（引入内置嵌入时启用），当前 FTS 关键词型达标即放行；
4. 打包运行时须显式声明 `modules("java.sql", "java.sql.rowset", "jdk.unsupported")`——sqlite-jdbc 反射加载驱动，CMP 的 jdeps 分析会漏掉 java.sql（漏配症状：安装版点"打开这个库"报 `java/sql/DriverManager`）。

## License

AGPL-3.0
