# 无单号 [SRS_WIFI_LinkSetting_008] 本机蓝牙热点名称
- **提交**：`2e6350d2` | 2026-08-24 | sgh | Setting | feature
- **关联单**：SRS_WIFI_LinkSetting_008

## 需求/目标
设备名称（蓝牙/热点共用）默认值改为按"品牌名-VIN 后 6 位"规则自动生成（Gray Whale_XXXXXX），首次生成后持久化，用户手动改名后不再覆盖；存储从 `Settings.Global.DEVICE_NAME` 迁移到自建 key。

## 实现结构
- `common/utils/DeviceUtils.kt`：新增 `brandName`、`handleDeviceName(vin)`（为空才生成"品牌_VIN后6位"并写入）、`setDeviceName`；`getDeviceName` 改读新 key。
- `common/Constants.kt`：新增 `DEVICE_NAME = "yedea_device_name"`（注意拼写 yedea，疑似 Yamaha/Yadea 手误，作为持久 key 一旦上线不可改）。
- `ConnectFragment.kt`：`initView` 先取 VIN 属性调 `handleDeviceName` 再显示/编辑；`setDeviceName` 改走 DeviceUtils 统一入口（顺带去掉 `@RequiresPermission(BLUETOOTH_CONNECT)` 注解）。
- 数据流：VIN 信号 → handleDeviceName 生成默认名（本地 KV）→ 蓝牙名/热点名跟随 setDeviceName 同步。

## 关键代码
```kotlin
// component/CommonTools/src/main/java/com/yadea/common/utils/DeviceUtils.kt
+    fun handleDeviceName(vin: String) {
+        val deviceName = getGSetting(Constants.VehicleConfig.DEVICE_NAME)
+        if (deviceName.isEmpty()) {
+            val deviceName = brandName + "_" + vin.isNotEmpty()
+                .let {
+                    if (vin.length < 6) "1234567890" else vin.substring(vin.length - 6)
+                }
+            setGSetting(Constants.VehicleConfig.DEVICE_NAME, deviceName)
+        }
+    }
```
默认名生成逻辑有兜底（VIN 长度不足 6 时用 "1234567890" 占位后 6 位），保证任何车况下都有可用设备名；"已存储则跳过"保证用户自定义名不被 VIN 异步到达冲掉。

## 复盘与要点
- 代码有一处风格怪味：`vin.isNotEmpty().let { ... }` 把 `let` 挂在 Boolean 上、lambda 里并不使用 `it`，功能上等价于 `if (vin.isNotEmpty()) ... else ...`，输出正确但极易让后来者误读成拼接了 true/false——链式调用应挂在实际使用的接收者上。
- 持久化 key "yedea_device_name" 拼写错误一旦发版即成事实标准，配置常量合入前应有人工拼写检查。
- 设备名三处同步（本地 KV、蓝牙、热点）收敛到 DeviceUtils 单一入口是正确方向，后续新增同步目标只改一处。
