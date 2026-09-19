# [SRS_BT_LinkSetting_012] · 修改 CarPlay 连接传参

- **提交**：`4c235251` | 2026-08-07 | dufan | Setting | feature
- **关联单**：SRS_BT_LinkSetting_012

## 需求/目标
Setting 侧发起 CarPlay 连接时补齐 `ConnectRequest` 关键参数：标记用户主动请求 + 指定无线连接类型，纠正 SDK 默认行为导致的连接方式偏差。

## 实现结构
单文件 2 行新增：`application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt` 中 `connectCarPlay(ConnectRequest)` 的 apply 块内补两个字段。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ -20
                     connectCarPlay(
                         ConnectRequest().apply {
                             deviceId = address
+                            isUserRequest = true
+                            connectType = CarPlayConstants.ConnectionType.CONNECTION_TYPE_WIRELESS
                         })
```
实现讲解：`isUserRequest=true` 向 SDK 声明这是用户显式发起（区别于自动重连类后台请求），`connectType` 显式锁无线通道，避免 SDK 按默认策略走 USB/未知类型。与 8 月 4 日 Setting 侧的互斥逻辑同一文件，属于手车互联联调期的参数级修正。

## 复盘与要点
- 三方 SDK 的请求对象若带"来源/类型"语义字段，必须显式赋值，不依赖默认值——默认值行为会随 SDK 版本漂移。
- 参数级小改也挂 SRS 单号，需求追溯链保持完整，值得保持。
