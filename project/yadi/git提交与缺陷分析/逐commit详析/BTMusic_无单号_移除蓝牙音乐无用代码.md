# 无单号 · BTMusic 移除蓝牙音乐无用代码（含 BluetoothServices 补齐版）

- **提交**：`74bbf219` | 2026-09-03 | caohongliang | BTMusic | **代码清理（非缺陷修复，单号占位 SIR-XXXXX，what/why/how 均为 NA）**
- **缺陷库**：未关联单号

## 类型说明
与 `790ecf3d` 完全同源（同一 Change-Id `Id651575…`、同一父提交 `b979880a`、同一作者与提交时间、同为 +/−1654 行级删除），是同一清理动作的第二个提交版本，区别仅在于多改 1 个文件、多 11 行新增：

- 包含 `790ecf3d` 的全部内容：移除 CmdController 两个 aar 依赖、Manifest 权限与 provider、删除 `MediaServiceCmdController / MediaServiceCmdControllerService / MusicCarService` 三个整文件及各处引用。
- **额外新增** `application/BTMusic/src/main/java/com/yadea/btmusic/extension/BluetoothServices.kt`（11 行）：

```kotlin
val mBluetoothController: BluetoothController by lazy { BluetoothController }
val mBluetoothPlayerService = BaseManager.getInstance(BluetoothPlayerService::class.java)!!
```

推断其目的：大删除后部分调用点仍引用 `mBluetoothController / mBluetoothPlayerService` 旧入口，此文件以扩展属性形式补齐编译缺口（[推断]，提交信息未说明）。

## 经验提示
- 同一 Change-Id 出现两个不同树的同父提交，通常是 Gerrit amend/cherry-pick 流程操作痕迹；合入侧应确保只保留一个版本，否则审计时会出现"同一改动两份 diff"的困惑。
- 清理类提交必须保证编译通过——本版本补的 `BluetoothServices.kt` 说明前一版本可能未过编译就提交了，清理提交同样需要构建验证。
