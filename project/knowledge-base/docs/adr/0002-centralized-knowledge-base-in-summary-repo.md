# 知识库集中固定在 Summary 仓库，而非随项目走

默认做法是把知识文档写进产生它的项目仓库（随代码走）。决定反其道：skill 全局安装、可在任何 workspace 运行，但沉淀一律写入固定路径 `/home/liang/Project/MyProject/Summary/project/knowledge-base/`。理由：Summary 是集中检索点（主题目录、git 管理、掘金文章索引都在此），知识分散到各项目仓库反而找不到。

## Consequences

- skill 运行时与"当前项目"解耦，条目中的代码实例必须自带来源语境（库名 + 文件路径），否则脱离项目后不可考。
- 跨机器/他人 clone 项目时知识不随之走；可接受——该知识库是个人知识资产。
