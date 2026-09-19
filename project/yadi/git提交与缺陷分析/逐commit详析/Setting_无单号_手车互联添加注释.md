# 无单号 · 手车互联添加注释

- **提交**：`0d415270` | 2026-07-23 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
为手车互联核心工具类 `BluetoothUtil` 与 `ConnectChildDialogActivity` 补充中文注释，说明各静态标志位与公开方法的语义（纯文档性提交，无逻辑变化）。

## 实现结构
2 个文件（+40/-5，全部为注释）：
- `ConnectChildDialogActivity.kt`：`SIsNeedFinish`（是否需要 finish）、`SIsFromCarConnect`（是否来自车机连接，连接后需关闭界面）两个 companion 标志补行注释；
- `BluetoothUtil.kt`：4 个静态变量补语义（`SIsFromUserClick`——流量共享开关是否用户点击、非用户点击时防回调触发；`SIsStartNetWorkShare`；`SCurrentThirdDevice`——当前手车互联连接的设备），并为 `connectOperationDevice/showConnectHintDialog/operationPhoneCar/showTrafficSharingDialog/showClickDeviceDialog/showRemoveDeviceDialog/showPairedDialog` 补 KDoc（含参数说明，如 `operationPhoneCar` 的 `isNeedDelete/isForceDisconnect`）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
-        var SIsFromUserClick = true
-        var SIsStartNetWorkShare = false
+        var SIsFromUserClick = true//手机流量共享开关，判读是否为用户点击，非用户点击时防止触发回调
+        var SIsStartNetWorkShare = false//是否开起来手机流量共享
```
```diff
+        /**
+         * 手车互联操作，已开启调用则关闭，反之则开启
+         * @param device 设备
+         * @param isNeedDelete 是否删除设备
+         * @param isForceDisconnect 是否强制断开连接
+         */
         fun operationPhoneCar(
```
实现讲解：这一批注释正好记录了 `3f19a717`/`13ba9d1d` 系列重构后各标志位的准确语义，特别是 `SIsFromUserClick` 的"防回调回环"用途和 `showPairedDialog` 的"苹果设备首连选择连接方式"场景，属于事后补写的领域知识固化。

## 复盘与要点
- 注释提交紧跟行为提交一天，时机正确——趁语义还热补充文档，成本最低。
- `SIsFromUserClick` 的注释揭示了一个重要设计：程序性 UI 回写（`isChecked = x`）会再次触发回调，用"是否用户点击"标志区分来源；这类防回环标志建议统一封装进开关组件而非靠约定。
