# 无单号 · BTMusic 移除蓝牙音乐无用代码

- **提交**：`790ecf3d` | 2026-09-03 | caohongliang | BTMusic | **代码清理（非缺陷修复，单号占位 SIR-XXXXX，what/why/how 均为 NA）**
- **缺陷库**：未关联单号

## 类型说明
大规模死代码清理提交（17 文件，+55/-1654 行），与 `74bbf219` 同一 Change-Id（`Id651575…`）、同一父提交 `b979880a`，是同一清理的两个版本（本版少一个补文件），按特殊情况简述：

- **依赖清理**：`build.gradle` 移除 `CmdController-1.0.0.14_20251230.aar` 与 `commandcontroller.aar` 两个本地 aar 依赖（二进制依赖下线）。
- **Manifest 清理**：删除 `com.androidext.commandcontroller.permission.SEND/RECEIVER_CMD_CONTROLLER` 自定义权限声明与 `CommandControllerProvider`（`CommandControllerReceiver`）声明。
- **代码删除**：整文件删除 `MediaServiceCmdController.kt`（-237）、`MediaServiceCmdControllerService.kt`（-64）、`MusicCarService.kt`（-132）；`BluetoothPlayerService.kt`（-300 行级瘦身）、`SourceChangeManager.kt`、`PlayerControl.kt`、`BtMusicModel.kt`、`MediaForegroundService.kt`、`InitService.java` 等去除 CmdController 命令控制链路相关引用。

即把旧命令控制（CmdController）框架在蓝牙音乐应用中的全部残留一次性拆除，属于功能下线后的卫生提交，无行为变化（预期）。

## 经验提示
- 下线第三方/旧框架时，"gradle 依赖 + Manifest 权限/provider + 代码引用"三处要一次清干净，本提交是完整示范。
- 单号写 `SIR-XXXXX` 占位、what/why 全 NA，虽是清理类惯例，但会让缺陷/需求追溯链断档，仓库规范上宜用专门的 refactor 类型标记。
