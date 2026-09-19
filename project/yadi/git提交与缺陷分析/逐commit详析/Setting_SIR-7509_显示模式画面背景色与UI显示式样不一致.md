# SIR-7509 · 显示模式画面背景色与UI显示式样不一致
- **提交**：`0a779ab0` | 2026-09-07 | sgh | Setting | bugfix（资源补齐类）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
仪表"显示模式"设置页的预览图（显示模式全部/强制、仪表尺寸小/标准四张示意图）在黑夜模式下背景色与 UI 式样不符。

## 根因分析
需求遗漏导致的资源缺失：页面原本只有 `drawable-mdpi/` 下的四张白天版位图（`dashboard_mode_all.png`、`dashboard_mode_force.png`、`dashboard_size_small.png`、`dashboard_size_standard.png`），没有提供 `-night` 资源限定符版本。项目采用 Android resource qualifier 的昼夜适配机制（`drawable-night/`），黑夜模式下系统找不到 night 版本就回落到白天图——其浅色背景在深色主题页面中与式样书规定的深色背景明显不一致。修复即"下载 UI 图"：从设计侧获取四张深色背景的切图，分别放入新建的 `application/Setting/src/main/res/drawable-night/`（二进制更新，无法从 diff 考证像素内容），文件名与白天版一一对应。

## 关键代码修改
改动文件（均为新增二进制资源，二进制更新）：
- application/Setting/src/main/res/drawable-night/dashboard_mode_all.png（约 132KB）
- application/Setting/src/main/res/drawable-night/dashboard_mode_force.png（约 57KB）
- application/Setting/src/main/res/drawable-night/dashboard_size_small.png（约 130KB）
- application/Setting/src/main/res/drawable-night/dashboard_size_standard.png（约 121KB）

## 为什么能修复
`-night` 限定符使黑夜模式下系统自动选用深色背景切图，与式样一致；白天路径不受影响，无代码改动、无行为风险。注意点是四张图均为 mdpi 语义的单密度资源（白天版在 `drawable-mdpi`，夜晚版直接放 `drawable-night` 未带密度限定），若高密度车机屏幕对位图缩放敏感，需与 UI 确认切图密度规格。

## 复盘与经验
- 昼/夜双主题项目新增图片资源时，`drawable` 与 `drawable-night` 应成对交付，漏 night 版是 UI 走查高频缺陷；可在资源清单/切图交接模板中把"night 版"设为必填项。
- 纯资源补齐类修复虽然简单，但要核对文件名、限定符与密度三要素完全对应，任何一项不匹配都会静默回落到默认资源。
- 位图类资源 diff 无法审阅内容，提交信息中的"下载UI图"应尽量附设计稿编号或切图来源，便于后续追溯版本。
