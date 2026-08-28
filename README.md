# Summary — 个人技术笔记库

以 Android 系统/车载开发为主的技术学习笔记、项目资料与自用工具。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 开发笔记：Framework 源码分析（`framework/`：渲染架构、addWindow、Vsync、多屏、电源、时间、硬按键）、Launcher3、MVVM 优化、OTA 生命周期、性能优化 |
| `language/` | 编程语言：Kotlin 系列、C++（含 Binder 源码笔记）、Java（反射、泛型） |
| `网络/` | 计算机网络与车载网络：车联网学习指南、SOME/IP 深入详解 |
| `文件管理系列文章/` | 操作系统文件管理系列（01~08） |
| `设计模式/` | 设计模式与几大原则 |
| `密码/` | 密码学：密码技术笔记、《图解密码技术》读书笔记 |
| `ai/` | AI 工具与工作流：OpenCode 使用手册、Agent/Harness、skills 管理器、车载 K 歌 SRS 工作流总结 |
| `path/` | 学习路径大纲：Binder/Framework 总纲（`总.md`）、AMS 大纲 |
| `project/` | 项目资料：`hc/`（车机 CloudOS/SDK 分析、问题解析流程框架）、`yadi/`（桌宠需求与设计）、`WMS Viewer/`（窗口树可视化工具） |
| `tools/` | 自用工具：`launcher_tool/`（ADB 脚本启动器 + 飞书签到）、`uicheck/`（UI 检查服务） |
| `skills/` | Agent skills 备份镜像（与 `~/.agents/skills` 保持一致） |

## 根目录文件

- `juejin-articles-index.md` — 掘金博客文章总索引（106 篇）

## 约定

- 编译产物（`__pycache__/`、`*.pyc`、`a.out` 等）与 AI 工具本地状态（`.qoder/`、`.sisyphus/`）不入库，见 `.gitignore`。
