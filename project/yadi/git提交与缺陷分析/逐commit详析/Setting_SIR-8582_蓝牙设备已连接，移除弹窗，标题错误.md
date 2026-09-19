# SIR-8582 · 已连接蓝牙设备的"移除"弹窗标题错误

- **提交**：`e5d49e49` | 2026-09-16 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
对已连接的蓝牙设备点"移除"时，确认弹窗的标题显示错误（显示的是设备名/其他文案，而非"移除设备"）。

## 根因分析
`BluetoothUtil` 中构造移除确认框 `TextDialog` 时，第一个参数（标题）传的是 `device.name`（蓝牙设备名），而不是移除场景应有的标题文案；正文 `device_remove_hint`（"设备移除后将不会自动连接"）与按钮（移除/取消）都是移除语义，唯独标题错位。修复新增字符串 `device_remove_title`（"移除设备"）并把标题改为该资源。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt、application/Setting/src/main/res/values/strings.xml、application/Setting/src/main/res/values-en/strings.xml
```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
             TextDialog(
-                device.name,
+                getString(R.string.device_remove_title),
                 getString(R.string.device_remove_hint),
                 getString(R.string.remove),
                 getString(R.string.cancel),
--- application/Setting/src/main/res/values/strings.xml
+    <string name="device_remove_title">移除设备</string>
```

## 为什么能修复
标题从动态设备名改为固定的移除语义文案，弹窗"标题-正文-按钮"三者语义对齐，用户不再误读操作对象。风险极低；唯一可议之处是丢失了标题里的设备名信息，若 UX 希望标题含设备名，应改为 `getString(R.string.remove_xxx, device.name)` 式格式化文案。

## 复盘与经验
- 确认弹窗的标题/正文/按钮必须语义一致，复制粘贴复用 `TextDialog` 时最易错的是参数位（第 1 参标题 vs 正文）。
- 固定场景标题应走 string 资源而非动态值，既保文案可审（UX/翻译），也避免长设备名撑爆标题栏。
