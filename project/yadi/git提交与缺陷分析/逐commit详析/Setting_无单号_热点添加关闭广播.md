# 无单号 · [SIR-XXX] 热点添加关闭广播
- **提交**：`ef1eccce` | 2026-09-10 | dufan | Setting | feature
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
车机热点关闭时，向互联服务（carlink）发送自定义"热点即将关闭"广播，让互联侧在 AP 真正关闭前感知并做清理/断连处理，而不是等系统回调事后通知。

## 实现结构
仅改动 `ui/fragment/diologfragment/HotspotDialogFragment.kt`（+9）：新增 `sendCloseBroadcast()`，并在两个关闭热点的路径上各插一行调用——①用户确认断开所有连接设备后的 `closeAp()` 前；②无连接设备时延迟 200ms 自动关热点路径的 `closeAp()` 前。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
+private fun sendCloseBroadcast() {
+    val intent = Intent("com.ts.action.HOTSPOT_USER_STOPPING")
+    intent.setPackage("com.ts.car.carlink")
+    requireContext().sendBroadcast(intent, "com.ts.permission.CONTROL_CARLINK")
+}
```
（两处调用均为 `sendCloseBroadcast()` 紧贴 `mWxApManagerI.closeAp()` 之前）

实现讲解：广播用 `setPackage()` 点名发给 carlink 进程，避免全局广播骚扰其他接收者；同时带权限参数 `sendBroadcast(intent, permission)`，要求接收方持有 `com.ts.permission.CONTROL_CARLINK` 才能收到，双重点名+权限是跨应用私有协议广播的标准写法。动作名 `HOTSPOT_USER_STOPPING` 语义为"用户正在关闭热点"（预通知），与后续 `closeAp()` 的实际关闭动作构成"先通知、后执行"。

## 复盘与要点
- **先广播后关闭的时序设计**：互联链路需要在 AP 关闭前主动断开，事后回调来不及；"预通知广播 + 紧跟实际操作"是处理此类跨进程时序依赖的实用手法。
- **两个路径都要覆盖**：手动确认关闭与超时自动关闭两条路径都补了广播，改协议类逻辑时记得枚举全部触发路径，漏一条就是偶现问题。
- **可复用**：`setPackage` + 权限校验广播模板可直接套用到其他车控跨应用通知场景。
