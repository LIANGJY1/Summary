# SIR-8501 · 偶现设备列表 HiCar 连接状态显示错误

- **提交**：`e7718dd5` | 2026-09-16 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 待测试验证 · 域 手车互联

## 问题
偶现场景下设备列表中 HiCar 的连接状态显示错误（例如已断开仍显示连接，或新互联类型接入时旧状态残留）。

## 根因分析
`DeviceConnectManager`（Setting 侧）处理互联类型切换的核心判断是：`mCurrentConnectType != type` 且 `isConnected` 为真时，若 `mCurrentConnectType != 0` 就直接 `return`（认为"上一个类型还在连接中"，拒绝切换/重置）。问题在于 `mCurrentConnectType != 0` 只说明"记录过一个类型"，并不代表该类型的设备**真实还在连接**——carlink 断开时状态未被重置，`mCurrentConnectType` 仍是旧值（如 2=HiCar），于是新设备（如 CarPlay/CarLink）接入时被这行守卫拦下，列表沿用旧类型的连接状态渲染，HiCar 状态显示错误。修复引入 `isConnectDeviceByType()`：按上一个类型的设备管理器真实查询列表中是否存在 `DeviceStatus.CONNECTED` 的设备，只有真在连才 return。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt、application/Setting/src/main/res/values/strings.xml、application/Setting/src/main/res/values-en/strings.xml
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ 类型切换守卫
         if (mCurrentConnectType != type) {
             if (isConnected) {
-                if (mCurrentConnectType != 0) {
+                if (isConnectDeviceByType()) {
                     return
                 }
@@ 新增真实连接态查询
+    private fun isConnectDeviceByType(): Boolean {
+        return when (mCurrentConnectType) {
+            1 -> getCarPlayDeviceManager()
+                ?.carPlayDeviceList
+                ?.any { it.deviceStatus == CarPlayConstants.DeviceStatus.CONNECTED }
+                ?: false
+            2 -> getHiCarDeviceListManager()
+                ?.hiCarDeviceList
+                ?.any { it.deviceStatus == HiCarConstants.DeviceStatus.CONNECTED }
+                ?: false
+            3 -> getCarLinkDeviceListManager()
+                ?.carLinkDeviceList
+                ?.any { it.deviceState == ts.car.service.carlink.data.CarLinkConstants.DeviceState.CONNECTED }
+                ?: false
+            else -> false
+        }
+    }
```
（strings.xml 同提交还修正了 `set_default_device_hint` 文案中的",，"双逗号笔误，与本单逻辑无关。）

## 为什么能修复
守卫条件从"记录值非零"升级为"上个类型真实存在 CONNECTED 设备"，carlink 断开未重置的场景下查询结果为 false，不再拦截新类型的连接流程，状态得以按新设备刷新，HiCar 显示错误消除。副作用：该查询依赖各设备列表管理器及时维护 `deviceStatus`，若某类型管理器自身状态滞后（断开事件晚到），守卫仍可能短暂误拦——但已从"永久残留"收敛为"事件窗口内短暂"，且三种类型全覆盖、未识别类型按 false 放行。

## 复盘与经验
- "记录的连接类型"是意图态，"设备列表里的 CONNECTED"才是事实态——跨类型互斥逻辑必须查事实态，否则断开事件丢失一次就永久卡死。
- 多互联协议（CarPlay/HiCar/CarLink）并存的模块，类型切换处应统一走"查真实连接"的守卫函数，避免每种协议各写一套判断。
- 偶现显示类问题优先怀疑状态机缺 reset 路径，本例"断开时未重置状态"即元数据点名的根因。
