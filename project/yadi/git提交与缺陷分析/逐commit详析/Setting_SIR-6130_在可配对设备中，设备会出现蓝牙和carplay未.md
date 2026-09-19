# SIR-6130 · 可配对设备列表中出现蓝牙和CarPlay均未连接图标的设备

- **提交**：`995187e2` | 2026-08-21 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
可配对设备列表中，某设备同时显示蓝牙和 CarPlay 均未连接的图标（设备记录残留但两个协议状态都不对）。

## 根因分析
缺陷库根因："carplay 还未断开时执行了删除操作失败"。`DeviceConnectManager` 中 CarPlay 会话状态回调里调用 `setDeviceConnectStatus(CARPLAY, status == SESSION_STATUS_ACTIVATED)` 后，非连接状态统一走 `handleDeviceDisconnect()`，而旧代码 `handleDeviceDisconnect` 对 `CARPLAY, CARLINK` 两种类型都无条件触发 `mDeleteDeviceListener?.onDeleteDevice()`（删除设备记录）。问题在于 CarPlay 的会话状态存在中间态（如 DEACTIVATING），断开尚未真正完成时就执行删除，删除操作在协议侧失败，设备记录与实际状态不一致，列表里留下一个"蓝牙+CarPlay 都未连接"的残留条目。修复把状态值透传进删除链路：`setDeviceConnectStatus`/`handleDeviceDisconnect` 新增 `status` 参数（默认 -1），CarPlay 类型只有在 `status == SESSION_STATUS_DEACTIVATED`（会话确实已去激活）时才触发 `onDeleteDevice()`；HiCar 断开分支也同步传 `state`；CARLINK 保持原无条件删除行为。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt（+12/-6）
```diff
@@ application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt @@
-    private fun handleDeviceDisconnect(deviceType: String) {
+    private fun handleDeviceDisconnect(deviceType: String, status: Int = -1) {
         mCurrentConnectType = 0
         SCurrentThirdDevice = null
         when (deviceType) {
-            CARPLAY, CARLINK -> {
+            CARPLAY -> {
+                if (status == CarPlayConstants.SessionStatus.SESSION_STATUS_DEACTIVATED) {
+                    mDeleteDeviceListener?.onDeleteDevice()
+                    mDeleteDeviceListener = null
+                }
+            }
+            CARLINK -> {
                 mDeleteDeviceListener?.onDeleteDevice()
                 mDeleteDeviceListener = null
             }
```
（配套：三处 `setDeviceConnectStatus(...)` 调用点均补传实时 status/state）

## 为什么能修复
删除设备记录的时机从"任何非连接状态"收紧为"会话确认 DEACTIVATED"，协议侧删除不再抢在断开完成前执行，删除成功后列表状态与真实连接状态一致，残留图标消失。隐患：若 CarPlay 断开流程异常终止、永远收不到 DEACTIVATED，删除会被跳过，设备记录可能滞留——属于把"错误删除"换成"保守不删"的取舍。

## 复盘与经验
- 协议状态机通常有多个非连接中间态，"非连接就当已断开处理"会抢跑；清理动作必须绑定到明确的终态（DEACTIVATED/DISCONNECTED）。
- 对外部系统（互联协议）的删除/注销操作可能失败，操作前校验前置状态、失败后保留现场，比删除失败留脏数据更好排查。
- 给状态处理函数透传原始 status 而不是只传布尔结果，是让下游能做精确判断的低成本改造。
