# 无单号 · 手车互联（Launcher）添加连接后互斥逻辑

- **提交**：`9012fffa` | 2026-07-29 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
CarPlay / HiCar / CarLink 三种手机互联通道互斥：任一通道已连接时，新通道发起连接要先主动断开已连接通道（且本次新连接放弃），空闲状态才允许直接接入。

## 实现结构
单文件 `application/Launcher/.../control/DeviceConnectManager.kt`（+77/-35）：
- `setDeviceConnectStatus()` 重写互斥门卫：deviceType 归一化为 1/2/3
  - 当前无连接（`mCurrentConnectType==0`）且 isConnected → 放行走更新流程
  - 已有连接且新类型要连 → 遍历对应设备列表找到 CONNECTED 设备，调 `disconnectCarPlay/disconnectHiCar/disconnectCarLink` 断开后 `return`（本次不上位）
  - 其他情况（同类型断开、未知类型）直接 return
- HICAR/CARLINK 分支的状态更新逻辑从 `isCo()/isConnect()` 两个私有方法内联回 when 分支，消除重复
- 新增 `getHiCarDeviceListManager()`、`getCarLinkDeviceListManager()` 访问器供互斥断开用

数据流：各通道状态监听回调 → `setDeviceConnectStatus(type, isConnected)` → 互斥判定 →（断开旧通道 | 更新 `mCurrentConnectType` + `CONNECT_DEVICE_NAME`）→ 发事件刷新 UI。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
@@ -811,22 +821,79 @@ class DeviceConnectManager
     private fun setDeviceConnectStatus(deviceType: String, isConnected: Boolean) {
-        LogUtils.d(TAG, "setDeviceConnectStatus:  $deviceType    $isConnected")
+        val type = when (deviceType) {
+            CARPLAY -> 1
+            HICAR -> 2
+            CARLINK -> 3
+            else -> return
+        }
+        if (mCurrentConnectType != type) {
+            if (isConnected) {
+                if (mCurrentConnectType != 0) {
+                    when (type) {
+                        1 -> getCarPlayDeviceManager()?.apply {
+                            carPlayDeviceList.forEach {
+                                if (it.deviceStatus == CarPlayConstants.DeviceStatus.CONNECTED) {
+                                    disconnectCarPlay(it.btAddr)
+                                    return@forEach
+                                }
+                            }
+                        }
+                        2 -> getHiCarDeviceListManager()?.apply { ... disconnectHiCar(it.btAddr) ... }
+                        3 -> getCarLinkDeviceListManager()?.apply { ... disconnectCarLink(it.deviceId) ... }
+                    }
+                    return
+                }
+            } else {
+                return
+            }
+        }
```
实现讲解：互斥的裁决点收敛到单一函数入口，先做类型归一化再判断"占用→踢旧放新失败、空闲→放行"；被踢通道走各自 SDK 断开接口，状态由其自身回调回流，避免在这里直接改两份状态造成不一致。

## 复盘与要点
- 三通道互斥的核心设计：**状态机只有一个写入口**（setDeviceConnectStatus），谁先连上谁占坑，后来者先踢再等，逻辑集中可测。
- 原先 `isCo()/isConnect()` 这类语义不明的方法被内联重构掉，diff 同时承载"加互斥 + 去重"，评审时需分别看。
- 遗留风险：`return@forEach` 只跳出当前元素处理，实为"只断第一台 CONNECTED 设备"；多设备同时连接的边界依赖 SDK 保证。同日 Setting 侧 `d08aa955` 做同款互斥但只是"拒绝新连接"，两端策略差异（Launcher 踢旧、Setting 拒新）需对齐。
