# 无单号 · 移除LinkApi依赖及无用代码（模块删除清理）

- **提交**：`10c5e009` | 2026-08-05 | liujinfeng | SystemUI/component | **代码清理（死代码移除）**
- **缺陷库**：未关联单号

## 说明
整模块删除类清理提交：整体移除 `component/LinkApi`（HiCar 互联封装库，含 `HiCarManager`、`HiCar`、`LocalSettingManager`、AIDL 接口及数百个 png/布局/字符串资源），共 338 个文件变更、约 7656 行删除；`settings.gradle` 去掉 `include ':component:LinkApi'`，`SystemUI/build.gradle` 去掉 `api project(':component:LinkApi')`。SystemUI 侧同步清理调用点：`SystemUIApplication` 删除 `HiCarManager.getInstance().onCreate(this)` 初始化，`BasicServicesTile` 删除 `isHiCarConnected()` 判断与 `showHiCarSwitchDialog()`/`HiCarOffDialog` 逻辑，`CarHeadsUpNotificationManager`/`NotificationDataManager` 删除相关分支，仅 import 排序级改动。

**复盘要点**：
- 删除公共模块的标准动作顺序：先清理所有调用点并编译通过 → 移除 build.gradle 依赖 → settings.gradle 摘除模块 → 最后删目录，本提交顺序执行得当。
- 该模块体量（数千行 + 多车型/昼夜资源变体）长期无人使用却持续参与编译，说明缺乏定期的依赖健康检查（如 lint unused module / 依赖图审计）。
- 删除型提交也应跑全量编译与冒烟（尤其 HiCar 相关入口），`HiCarOffDialog` 这类被删 UI 若仍有触发路径会直接 NoClassDefFoundError。
