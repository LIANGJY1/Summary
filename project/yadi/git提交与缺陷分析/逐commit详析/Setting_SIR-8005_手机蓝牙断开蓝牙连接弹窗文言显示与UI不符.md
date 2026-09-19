# SIR-8005 · 蓝牙断开连接弹窗按钮文言与 UI 不符
- **提交**：`b226733f` | 2026-09-09 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：文言不对）

## 问题
手机蓝牙断开连接相关弹窗的确认按钮文言与 UI 稿不符（"确定"vs"确认"用词混用）。

## 根因分析
Setting 模块存在两条语义重复的确认文案资源：`sure`（值"确定"）与 `confirm`（值"确认"）。开发时各弹窗随机引用其一——`BluetoothFragment`/`BluetoothAnwFragment`（关闭蓝牙弹窗）与 `LightFragment`（自动远光弹窗）用了 `sure`，而 `BluetoothUtil` 的设备断开 `TextDialog` 用了 `confirm`，同一模块按钮文案不统一，与 UI 稿定的"确认/取消"不符。本次修复统一资源引用方向，并规范资源值。

## 关键代码修改
改动文件：application/Setting/src/main/res/values/strings.xml；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt；application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt；application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
```diff
--- application/Setting/src/main/res/values/strings.xml
@@ -18,7 +18,7 @@
     <!--公共的-->
-    <string name="sure">确定</string>
+    <string name="sure">确认</string>
```
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -202,7 +202,7 @@
             showTipDialog(
                 title = getString(R.string.close_bluetooth_dialog_title),
                 content = getString(R.string.close_bluetooth_dialog_content),
-                confirmText = getString(R.string.sure),
+                confirmText = getString(R.string.confirm),
                 cancelText = getString(R.string.cancel),
```
`BluetoothAnwFragment`、`LightFragment` 同步 `sure`→`confirm`；`BluetoothUtil` 的断开设备 TextDialog 反向 `confirm`→`sure`；`BluetoothAnwFragment` 顺带删除一处语句末多余分号。

## 为什么能修复
`sure` 资源值统一改为"确认"，两处反向引用后各弹窗按钮文案与 UI 稿一致且全模块统一。值得注意的隐患：`sure`/`confirm` 两条资源语义完全重复，本次只是把引用"对齐"，双资源并存的混乱根源仍在——后续新增弹窗仍可能引错，建议合并为单一资源。

## 复盘与经验
- "确定/确认"类同义文案资源必须在模块级收敛为一条，否则 UI 稿一旦定稿用词，所有引用点都要回扫一遍（本提交正是回扫式修复）。
- 弹窗按钮文案统一走 `showTipDialog`/`TextDialog` 的默认值而非逐处传参，可从机制上避免文案漂移。
- 文案统一类修复要全局 grep 资源引用面，一次改全，避免"修一个单号冒出三个同类单号"。
