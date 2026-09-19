# 无单号 · 更新build.gradle（EnergyManagement 输出包名调整）
- **提交**：`c61bc2ca` | 2026-09-07 | caohongliang | EnergyManagement | 构建配置调整（非运行时 bugfix）
- **缺陷库**：未关联单号

## 类型说明
构建脚本微调：`application/EnergyManagement/build.gradle` 中 debug/release 两个 buildType 的 `output(...)` 输出名由 `NsrEnergyManagement` 改为 `NsrEnergymanagement`（仅大写 M 改小写 m，两处同改）。这是 APK 产物文件名的命名统一，通常是为了与打包/升级脚本、OTA 包名清单中的既有命名（全小写 energymanagement）对齐，避免产物名不匹配导致打包系统找不到/多出文件。无任何运行时代码改动。

## 复盘与经验
- 产物命名属于"打包链路契约"，改动前应与 CI/打包平台核对匹配规则（大小写敏感的脚本环境尤其如此），并在提交信息中写明对齐目标，避免后续无人敢动。
