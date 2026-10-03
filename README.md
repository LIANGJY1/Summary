# Summary — 个人技术笔记库

以 Android 系统/车载开发为主的技术学习笔记、项目资料与自用工具。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `knowledge-base/` | 统一知识与学习内容入口：Android、测试、语言、项目经验、操作系统、设计模式、通用网络与求职项目分析；各主题见目录内 README 和 `ROUTING.md` |
| `ai/` | AI 工具与工作流：OpenCode 使用手册、Agent/Harness、skills 管理器、车载 K 歌 SRS 工作流总结 |
| `path/` | 学习路径大纲：Binder/Framework 总纲（`总.md`）、AMS 大纲 |
| `project/` | 项目资料：`hc/`（车机 CloudOS/SDK 分析、问题解析流程框架）、`yadi/`（桌宠需求与设计）、`WMS Viewer/`（窗口树可视化工具）、`honda27m-appstore-tools/`（Honda 27M AppStore UI 走查工具链） |
| `tools/` | 自用工具：`launcher_tool/`（ADB 脚本启动器 + 飞书签到）、`uicheck/`（UI 检查服务） |
| `skills/` | Agent skills 备份镜像（与 `~/.agents/skills` 保持一致） |

## 根目录文件

- `juejin-articles-index.md` — 掘金博客文章总索引（106 篇）

## 约定

- 编译产物（`__pycache__/`、`*.pyc`、`a.out` 等）与 AI 工具本地状态（`.qoder/`、`.sisyphus/`）不入库，见 `.gitignore`。
