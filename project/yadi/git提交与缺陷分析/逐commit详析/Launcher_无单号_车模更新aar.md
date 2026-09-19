# 无单号 · 车模更新aar（kanzi-release.aar）
- **提交**：`ed4b0946` | 2026-07-16 | liqingqing | Launcher | bugfix（aar 集成）
- **缺陷库**：未关联单号

## 类型说明
纯 aar 更新提交：`application/Launcher/libs/kanzi-release.aar` 二进制更新（242255690 → 246728888 字节），无源码 diff。提交消息 what/why/how 均为"车模更新aar"，Kanzi 引擎包例行替换，内部修复内容无法从本仓库 diff 判断，不强行剖析。这是本批次 07-09 至 07-16 期间第 4 次 kanzi aar 集成（9c1b2cea → fa8022eb → 本提交），包体持续增长（约 237MB → 246MB）。

## 改动文件清单
- application/Launcher/libs/kanzi-release.aar（二进制更新，约 +4.5MB）

## 复盘与经验
- 同类"更新aar"提交已出现 4 次且全部无单号、无 changelog，建议约定：aar 集成提交必须附上游版本号与修复要点，否则 Kanzi 侧的缺陷修复在宿主仓库视角完全不可见，主报告统计时应单列为"外部引擎黑盒变更"。
