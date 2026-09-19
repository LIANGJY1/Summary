# SIR-8551 · 黑夜模式下耳机蓝牙移除设备文言与 UI 不一致
- **提交**：`68901147` | 2026-09-17 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
黑夜模式下，在耳机（副蓝牙）设备弹窗里执行"移除设备"，出现的界面/文言与 UI 式样规定的移除确认弹窗不一致。

## 根因分析
`BluetoothAnwFragment`（`DialogBluetoothChildBinding` 耳机蓝牙弹窗）的外层 `HintDialog` 中，`cancel()` 分支（对应"移除设备"入口）原来直接在 IO 线程执行 `BtAnwManager.getInstance().startPairOrUnpair(false, device.macAddress)` 解绑设备，根本没有展示式样要求的移除确认弹窗；而外层 confirm 分支复用了 `"HintDialog"` 标签。用户看到的是"点移除直接生效/提示内容不符"，与设计稿中标准的"移除设备·设备移除后将不会自动连接"红色按钮确认框不符。缺陷库记"文言不对/修改文言"，diff 实际是补齐了整个标准移除确认弹窗并复用已有规范文案资源（`device_remove_title`、`device_remove_hint` 在 strings.xml 第 373-374 行已存在），并非仅改字符串。

## 关键代码修改
改动文件：BluetoothAnwFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
             override fun cancel() {
-                lifecycleScope.launch(ioDispatcher) {
-                    BtAnwManager.getInstance().startPairOrUnpair(false, device.macAddress)
-                }
+                TextDialog(
+                    ResourceUtils.getString(R.string.device_remove_title),
+                    ResourceUtils.getString(R.string.device_remove_hint),
+                    ResourceUtils.getString(R.string.remove),
+                    ResourceUtils.getString(R.string.cancel),
+                    R.drawable.selector_common_red_btn
+                ).setCallback(object : Callback {
+                    override fun confirm(content: Any?) {
+                        lifecycleScope.launch(ioDispatcher) {
+                            BtAnwManager.getInstance().startPairOrUnpair(false, device.macAddress)
+                        }
+                    }
+
+                    override fun cancel() {}
+                }).show(childFragmentManager, "AnwRemoveHintDialog")
             }
```
（confirm 分支的 Dialog tag 由 `"HintDialog"` 改为 `"DefaultDeviceDialog"`，避免两处弹窗标签混淆）

## 为什么能修复
"移除设备"现在先弹出式样规定的 `TextDialog`：标题"移除设备"、提示"设备移除后将不会自动连接"、红色确认按钮，文案与配色全部来自通用资源，黑夜/白天模式由资源系统自动适配；只有用户在该弹窗点确认才真正执行 `startPairOrUnpair(false, ...)` 解绑。弹窗行为、文言、样式三点同时对齐 UI 设计。隐患：多了一步确认会增加点击成本，但防止误移除，属于规范要求的取舍。

## 复盘与经验
- "文言与 UI 不一致"类缺陷不能只盯着字符串资源，很多时候是弹窗根本没走标准组件——先核对交互流程是否与设计稿一致。
- 破坏性操作（移除设备/解绑）不应藏在某个主弹窗的 cancel 分支里静默执行，应有独立的二次确认框，并用不同 Dialog tag 便于测试定位与防重复弹出。
- 复用全局通用文案资源（`device_remove_*`）与通用红色按钮 selector，天然保证昼夜模式、多语言一致性，比在各页面硬编码更可维护。
