# SIR-5707 · HiCar 通话中关闭蓝牙无二次确认提示通话影响

- **提交**：`a18dd394` | 2026-09-11 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
HiCar 通话进行中，在车机上关闭蓝牙时弹出的二次确认框只提示"胎压监测将无法使用"，完全没有提示会中断当前通话，用户可能误关导致通话掉线。

## 根因分析
`BluetoothFragment` 的关闭确认逻辑（`setOnOverlayClickListener` → `showTipDialog`）只写死使用通用文案 `close_bluetooth_dialog_content`（"关闭蓝牙后，胎压监测显示将无法使用……"），当初设计时只考虑了胎压监测这一影响面，后续接入 HiCar / ICCOA Carlink 通话链路后没有同步补充对应分支——缺陷库根因"未添加此逻辑"属实。判断通话状态所需的数据其实已具备：全局设置 `callingStateBtphone`（1 表示通话中）和 `DeviceConnectManager.getCurrentConnectType()`（2=HiCar、3=Carlink，位于 2..3 区间），只是从未被使用。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`、`application/Setting/src/main/res/values/strings.xml`、`application/Setting/src/main/res/values-en/strings.xml`
```diff
         mBindingHeader.sw.setOnOverlayClickListener {
             if (!mBindingHeader.sw.isChecked) return@setOnOverlayClickListener
+            val isCalling = SettingsUtils.getGSetting("callingStateBtphone", 0)
+            val currentConnectType = DeviceConnectManager.getInstance().getCurrentConnectType()
+            val content = if (isCalling == 1 && currentConnectType in 2..3) {
+                if (currentConnectType == 2) {
+                    getString(R.string.close_bluetooth_dialog_hicar_content)
+                } else {
+                    getString(R.string.close_bluetooth_dialog_carlink_content)
+                }
+            } else {
+                getString(R.string.close_bluetooth_dialog_content)
+            }
             showTipDialog(
                 title = getString(R.string.close_bluetooth_dialog_title),
-                content = getString(R.string.close_bluetooth_dialog_content),
+                content = content,
```
新增文案：`close_bluetooth_dialog_hicar_content`（"关闭蓝牙连接会影响HUAWEI HiCar通话，是否确定关闭蓝牙?"）与 `close_bluetooth_dialog_carlink_content`（ICCOA Carlink 版本），中英双语资源同步添加。

## 为什么能修复
在弹确认框前先读取通话状态与互联类型，通话中且走 HiCar/Carlink 通道时切换为"影响通话"的定向文案，让用户明确感知后果再决定是否关闭，原有胎压提示与关闭流程本身不受影响。注意点：英文资源里未带 `\n` 而中文带换行符，两语言排版略有不一致；`currentConnectType` 魔法数字 2/3 依赖 `DeviceConnectManager` 常量语义，可读性可再改进（本提交同时删掉了未用的 `CARPLAY` 导入）。

## 复盘与经验
- 新互联通道（HiCar/Carlink）接入时，要系统性排查所有引用"蓝牙/连接"语义的既有提示文案，否则出现本例"功能变了、提示没跟上"。
- 二次确认文案按"当前上下文风险"动态选择，是低成本高收益的安全设计。
- 全局状态（如 `callingStateBtphone`）在关键操作入口统一查询一次即可做上下文感知，无需各处监听。
- 多语言字符串新增必须中英（以及其它 locale）同步，换行符等排版差异也要对齐。
