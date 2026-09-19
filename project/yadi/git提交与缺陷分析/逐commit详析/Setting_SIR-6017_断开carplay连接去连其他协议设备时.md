# SIR-6017 · 断开CarPlay连接其他协议设备时弹窗提示不完整

- **提交**：`12ff922d` | 2026-08-19 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
断开 CarPlay 连接去连其他协议设备时，切换确认弹窗中的设备名占位为空，提示语句残缺（如"是否连接‌"后面没有设备名）。

## 根因分析
缺陷库根因为"文言不对"。`BluetoothUtil.kt` 拼接弹窗文案时，用 `String.format(getString(R.string.connect_new_device_hint), SettingsUtils.getGSetting("connect_device_name"))` 填充设备名。CarPlay 连接场景下全局配置里的 `connect_device_name` 可能为空（该键记录的是蓝牙连接设备名，CarPlay 设备不必然写入），format 后占位符落空，弹窗提示不完整。修复在格式化前读取该值并判空，为空时兜底使用 `"Apple CarPlay"`，保证提示语完整且语义正确。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt（+2/-1）
```diff
@@ application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt @@
+                    val deviceName = SettingsUtils.getGSetting("connect_device_name")
                     val hint = String.format(
                         getString(R.string.connect_new_device_hint),
-                        SettingsUtils.getGSetting("connect_device_name")
+                        if (TextUtils.isEmpty(deviceName)) "Apple CarPlay" else deviceName
                     )
```

## 为什么能修复
占位符永远有非空值可填，弹窗不再出现残缺文案；CarPlay 场景下兜底为 "Apple CarPlay"，与用户实际操作对象一致。隐患很小：若未来支持其他互联协议（如 Android Auto），硬编码的 "Apple CarPlay" 兜底需要跟随协议类型调整。

## 复盘与经验
- `String.format` 填充用户可见文案前必须对所有动态占位做判空兜底，"文言不对"类 C 级 bug 很多只是少了一个 `if (isEmpty)`。
- 全局配置键（connect_device_name）的写入方和读取方往往在不同模块，读侧不能假设它已被写入；跨协议场景更要明确该键覆盖哪些协议。
