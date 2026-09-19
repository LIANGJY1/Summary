# YD-393028 · 断开当前蓝牙设备去连接carplay时toast提示不对

- **提交**：`b15054aa` | 2026-08-04 | dufan | Setting | bugfix
- **缺陷库**：未关联单号（标题含 YD-393028）

## 问题
断开当前蓝牙设备改连 CarPlay 时，切换确认弹窗文案错误（复用了"切换至新设备连接"的通用文案，语义不对）。

## 根因分析
`BluetoothUtil` 的切换确认分支在 `needShowSwitchDialog(4, device.phoneCarConnectionType)` 命中（目标为手机互联/CarPlay 场景）时，仍把 `getString(R.string.bluetooth)` 作为名称传给 `showConnectHintDialog()`，最终套用通用模板 `connect_new_device_hint`（"确定断开已连接的%s，并切换至新设备连接"），渲染成"确定断开已连接的蓝牙，并切换至新设备连接"——既没说切到 CarPlay，主宾语也不通顺（文案模板与场景不匹配）。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt、application/Setting/src/main/res/values/strings.xml、application/Setting/src/main/res/values-en/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                             if (needShowSwitchDialog(4, device.phoneCarConnectionType)) {
+                                val hint = if (device.phoneCarConnectionType == 1) String.format(
+                                    getString(R.string.connect_phone_car_new_device_hint),
+                                    "Apple CarPlay"
+                                ) else String.format(
+                                    getString(R.string.connect_new_device_hint),
+                                    getString(R.string.bluetooth)
+                                )
                                 showConnectHintDialog(
                                     childFragmentManager,
-                                    getString(R.string.bluetooth),
+                                    hint,
                                     callback
                                 )
                                 return
```
```diff
// application/Setting/src/main/res/values/strings.xml（values-en 同步新增）
+    <string name="connect_phone_car_new_device_hint">确定断开当前蓝牙设备，并切换至%s连接？</string>
```
配套：`showConnectHintDialog()` 参数由 `name` 改为组装好的 `hint`，弹窗内不再二次 format；另一分支文案改为携带 `connect_device_name` 完整模板；弹窗补 `setOnDismissListener` 兜底关闭，倒计时从 10s 调整为 11s 并增加 `countDownTimer?.cancel()` 防重复。

## 为什么能修复
CarPlay 场景（`phoneCarConnectionType == 1`）改用新模板 `connect_phone_car_new_device_hint` 并填入 "Apple CarPlay"，文案与实际动作一致；其余场景维持原模板但由调用方统一组装。附带修复了倒计时器未取消导致的重复弹窗/计时叠加隐患。

## 复盘与经验
- 带占位符的提示文案要按"场景 → 模板"映射管理，多个场景共用一个模板时，至少要保证填入变量语义自洽。
- 弹窗工具方法收"成品文案"（hint）比收"名称"再内部拼接更清晰，避免调用方误传资源名当设备名。
- 倒计时弹窗每次 show 前必须 cancel 旧 CountDownTimer，并配 setOnDismissListener 兜底释放，否则快速重复触发会串台。
