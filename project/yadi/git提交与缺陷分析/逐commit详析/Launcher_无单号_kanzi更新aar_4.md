# 无单号 · [3D车模] kanzi 更新 aar

- **提交**：`7ccb609d` | 2026-09-07 | liqingqing | Launcher | feature（纯二进制资源更新）
- **关联单**：无

## 需求/目标
更新 Launcher 内 3D 车模（Kanzi 引擎）组件包 `kanzi-release.aar`（257,272,333 字节 → 299,667,844 字节），随包修复三个智能化缺陷：萌宠关闭后仍显示、胎压颜色不正确，并删除昼夜切换过渡动画。

## 实现结构
单文件二进制替换：`application/Launcher/libs/kanzi-release.aar`。无任何源码、配置或构建脚本改动；修复内容封装在 aar 内部（Kanzi/3D 渲染工程），本仓库仅承载集成。

类型标注：纯 aar 资源更新提交，stat 即全部信息，一句话记录——3D 车模能力包版本推进，缺陷修复明细依赖 aar 侧变更记录。

## 复盘与要点
- 260+MB 的 aar 进 git 使仓库体积显著增长，且二进制无法 diff 评审——建议大资产走 LFS 或制品库，commit message 中记录 aar 版本号/来源构建号以便追溯。
- aar 更新类提交的 message 把修复点写清（本提交做得不错：萌宠/胎压/过渡动画三点），是二进制提交可追溯性的最低要求。
