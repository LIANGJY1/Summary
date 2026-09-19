# [SRS_BT_LinkSetting_012] · 修改蓝牙设备判断（配对弹窗设备分类器）

- **提交**：`68d34489` | 2026-08-11 | dufan | SystemUI | feature（**实为蓝牙配对判断缺陷修复 + 分类器下沉**）
- **关联单**：SRS_BT_LinkSetting_012

## 问题
SystemUI 配对请求监听原判断条件是 `btClass.majorDeviceClass == 512`（PHONE 硬编码魔数），配对请求阶段部分设备 `bluetoothClass` 缺失或分类不准，导致手机配对弹窗漏弹或耳机/音箱误弹。

## 根因分析
单一依赖 `majorDeviceClass==512` 的脆弱判断：a) 512 是魔法数字；b) 配对请求时设备信息不完整（无 Class）直接不弹窗；c) 音视频大类下无法区分耳机/音箱与手机。

## 关键代码修改
```diff
--- a/.../systemui/util/DevicePairingClassifier.kt（新增 159 行）
+    fun isPhoneDeviceForPairing(device: BluetoothDevice): Boolean {
+        // 1. 通过 BluetoothClass 判断
+        val btClass = device.bluetoothClass
+        if (btClass != null) {
+            val result = classifyByBluetoothClass(btClass)
+            if (result != null) {
+                return result
+            }
+        }
+        // 2. BluetoothClass 为空或无法判断，通过设备名称 fallback
+        val nameResult = classifyByName(device.name)
+        if (nameResult != null) {
+            return nameResult
+        }
+        // 3. 无法判断时的默认策略：车机场景默认按手机处理，弹出配对框让用户确认，
+        // 即使判断不出来，也不会漏掉手机的配对请求
+        return true
+    }
```
```diff
--- a/.../systemui/SystemSettingsControllerService.kt
-            if (btClass != null && btClass.getMajorDeviceClass() == 512) {
+            if (btClass != null) {
+                if (btClass.majorDeviceClass == 512 || DevicePairingClassifier.isPhoneDeviceForPairing(device)) {
                     val intent = Intent()
                     intent.setClassName("com.yadea.setting",
                         "com.yadea.setting.ui.activity.PairDialogActivity")
```

## 为什么能修复
新分类器按"BluetoothClass 大类 → 音视频子类排除耳机/音箱/车载 → 设备名关键词（耳机/音箱/品牌词中英文表）→ 默认按手机"四级降级，覆盖 Class 缺失场景；判断不出时默认弹窗交给用户确认，把"漏掉手机配对"的严重方向错误转为"多弹一次由人裁决"，符合车机安全性诉求。原 512 条件保留兼容已确认手机。

## 复盘与经验
- 平台枚举值（512=PHONE）应一律用 `BluetoothClass.Device.Major.PHONE` 常量，魔法数字既难读又难搜。
- 设备识别类逻辑"分类器单例 + 可空三级返回（true/false/unknown）+ 保守默认值"是可复用范式；unknown 与明确 false 的区分是关键设计。
- 名称关键词表（手机品牌/耳机音箱词）是开放式集合，需持续维护，长期应配合 BLE SDP 服务信息判断。
