# 开发工具（devtools）

> 收录语言与平台之外、跨项目复用的开发工具知识：工具操作语义、场景选择与协作流程。

## 文档

1. [版本控制与 Git](./01-version-control-git.md)（8 题）— Git 整体原理、快进语义与产生条件、--no-ff 与 --squash 的取舍、cherry-pick 语义与冲突流程、pull 协作、Gerrit 评审推送。
2. [Android Bench 代码代理评测](./02-android-bench.md)（3 题）— Android 代码代理基准的任务构造、复现协议、verifier 状态与分数解读；不属于应用运行时性能基准。
3. [clangd 语言服务器](./03-clangd.md)（1 题）— clangd 的定位与 LSP 链路、编译参数三级来源（compile_commands.json、.clangd、fallback）、能力边界与 cpptools/ccls 取舍。

## 内容边界

1. 收跨项目复用的工具操作语义与场景选择；项目特有的提交治理与流程纪律归 `../08-exp/`。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../WRITING-GUIDE.md)。
