# 无单号 · 3D 车模更新 kanzi-release.aar

- **提交**：`d5e91418` | 2026-08-07 | liqingqing | Launcher | **二进制依赖更新（非代码剖析类）**
- **缺陷库**：未关联单号

## 问题与说明
纯 aar 更新提交：`application/Launcher/libs/kanzi-release.aar` 从 109,290,524 字节更新为 249,108,629 字节（体积翻倍以上），无任何源码改动。提交信息 what/why/how 均为"3D车模更新aar"，未给出具体缺陷描述，关联单号为内部任务 `SIR-kanzi` 而非缺陷单。

## 变更内容
```diff
// application/Launcher/libs/kanzi-release.aar（二进制更新）
-Binary files a/application/Launcher/libs/kanzi-release.aar and b/application/Launcher/libs/kanzi-release.aar differ
```
Kanzi 为 3D 车模渲染引擎（与 `9d4b7144`、`29dd455d` 中 `KanziDataSourceManager`/`kanziManager.setValue` 链路对应），本次由三维引擎提供方整体替换 SDK 包，具体修复点封装在二进制内，无法从 diff 得知。

## 复盘与经验
- 二进制依赖更新的提交信息应注明版本号与修复内容清单（如"kanzi 2.x → 2.y，修复车模黑屏/内存泄漏"），仅写"更新aar"会造成溯源困难。
- aar 体积从 104MB 涨到 237MB，对车机包体和 OTA 升级包影响显著，依赖更新需评估包体预算。
- 该类提交的回归范围（提交标注"3D车模显示"）应与三维引擎团队提供的变更说明对齐，不能只测表面显隐。
