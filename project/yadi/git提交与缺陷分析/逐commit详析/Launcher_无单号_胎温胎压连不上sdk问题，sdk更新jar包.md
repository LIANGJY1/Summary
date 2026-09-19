# 无单号 · 胎温胎压连不上sdk问题，sdk更新jar包
- **提交**：`b8009bda` | 2026-07-10 | liqingqing | Launcher | bugfix（jar 集成）
- **缺陷库**：未关联单号

## 类型说明
纯 jar 更新提交：`component/commonlibs/VehicleSDK.jar` 二进制更新（85157 → 86995 字节），无源码 diff。提交消息描述问题为"胎温胎压连不上 sdk"，修复在 VehicleSDK 内部完成（本仓库不可见），Launcher 侧仅集成新包，不强行剖析。

## 改动文件清单
- component/commonlibs/VehicleSDK.jar（二进制更新，约 +1.8KB）

## 复盘与经验
- 修复发生在二进制依赖内部时，宿主仓库至少应在提交消息记录上游修复的版本号/变更点；本提交 only 写了现象重复四遍，追溯价值为零。
- "连不上 sdk"类问题建议在宿主侧保留连接失败的重试与日志埋点，便于区分宿主时序问题与 SDK 内部问题。
