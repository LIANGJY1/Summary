# 无单号 · 3D车模解决bug更新aar（二进制aar替换）

- **提交**：`ef124543` | 2026-08-04 | liqingqing | Launcher | **二进制 aar 更新**
- **缺陷库**：未关联单号

## 说明
`application/Launcher/libs/kanzi-release.aar` 二进制更新：246,728,888 字节 → 109,290,524 字节（约 247MB 瘦身到 109MB）。提交说明为"3D车模解决bug，更新aar"，即 Kanzi 3D 车模库由上游（kanzi 工程侧）修复缺陷后重新出包替换，缺陷细节在 aar 内部源码中，仓库无对应源码 diff，不深入剖析。结合前一提交 38a12086 看，kanzi-release.aar 三天内在 537MB → 247MB → 109MB 间连续替换，上游出包流程与版本锁定（当时"重新提交716版本"）明显不稳定，属于供应链管理风险点。
