# SIR-8797 · 断开 Carlink 连接的二次确认弹窗文案不对

- **提交**：`94810c60` | 2026-09-18 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 解决方案 · 域 手车互联

## 问题
切换/断开手机互联（CarPlay/HiCar/Carlink）时的二次确认弹窗中，提示文案里出现的连接名称不对，与实际要断开的互联协议不符。

## 根因分析
该弹窗由 `BluetoothUtil` 的连接切换逻辑触发：`needShowSwitchDialog(current, target)` 判定当前互联协议切换到目标协议需要确认时，用 `String.format(getString(R.string.connect_new_device_hint), deviceName)` 生成提示并 `showConnectHintDialog`。旧代码的 `deviceName` 取自 `SettingsUtils.getGSetting("connect_device_name")`——这是持久化的"上次连接设备名"，内容是设备的自定义名称（可能是手机蓝牙名），且会残留上一次连接的旧值；为空时还一律兜底成 "Apple CarPlay"。于是当当前连接实际是 HUAWEI HiCar 或 ICCOA Carlink 时，弹窗可能显示错误的设备名或错误地写成 Apple CarPlay。缺陷库 `[why]文言不对` 与 diff 一致：弹窗需要的是"当前连接的互联协议产品名"，而非设备存储名。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt（+7/-2）
```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ 切换确认弹窗文案
-                    val deviceName = SettingsUtils.getGSetting("connect_device_name")
+                    val deviceName = when(SCurrentThirdDevice!!.phoneCarConnectionType){
+                        1 -> "Apple CarPlay"
+                        2 -> "HUAWEI HiCar"
+                        3 -> "ICCOA Carlink"
+                        else -> ""
+                    }
                     val hint = String.format(
                         getString(R.string.connect_new_device_hint),
-                        if (TextUtils.isEmpty(deviceName)) "Apple CarPlay" else deviceName
+                        deviceName
                     )
```

## 为什么能修复
名称来源从"持久化设备名（可能过期、与协议无关）"改为直接由当前连接的 `SCurrentThirdDevice.phoneCarConnectionType` 映射协议产品名（1=无线CarPlay、2=无线HiCar、3=无线Carlink，与 `needShowSwitchDialog` 注释的类型定义一致），弹窗永远如实告知正在断开的互联协议，兜底 "Apple CarPlay" 的误导也一并移除。注意点：`SCurrentThirdDevice!!` 的强解包沿用了上文既有写法，若当前无第三方设备连接理论上存在 NPE 风险，但该路径仅在已有连接需要切换确认时进入，实际风险与旧代码相当。

## 复盘与经验
- 提示文案中的"名称"必须取自与语义匹配的数据源：协议弹窗显示协议名（枚举映射），设备名单独有用途；从持久化存储里顺手拿一个"名字"来拼文案，是文案类 bug 的常见根源。
- 枚举到用户可读名称的映射应集中在一处（或资源表），`when(type)` 就地映射虽小，但比"存储值 + 拍脑袋兜底"可靠得多；兜底值尤其要避免指向某个具体产品（如默认 Apple CarPlay）。
- 弹窗确认类缺陷的验证只需枚举三种互联类型各触发一次切换，成本低，应在测试范围里显式列出。
