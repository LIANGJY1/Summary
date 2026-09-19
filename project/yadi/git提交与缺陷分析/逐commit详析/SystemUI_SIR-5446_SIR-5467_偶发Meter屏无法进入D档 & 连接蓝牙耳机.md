# SIR-5446/SIR-5467 · 偶发Meter屏无法进D档 & 头盔连接时音量0不显静音图标

- **提交**：`2d21bbde` | 2026-08-03 | ljl | SystemUI | bugfix（双单号合并提交）
- **缺陷库**：SIR-5446：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 仪表信息；SIR-5467：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
① SIR-5446：偶发 Meter 屏无法进入 D 档（车控信号失效）。② SIR-5467：连接蓝牙头盔（BT 主设备）后把多媒体音量拉到 0，OSD 不显示静音图标。

## 根因分析
SIR-5467：`VolumeDialogActor.getIconResId()` 在头盔作为输出设备时只返回 `vector_volume_bt_head` / `vector_volume_bt_head_unactive` 两个图标，根本没有静音态图标分支，`isMute` 参数被丢弃，音量 0 时自然无静音图标（与 Setting `SoundFragment` 的 `ic_helmet_off` 行为不一致）。
SIR-5446：Carlib 订阅链路存在"静默断连"——`CarConnectionManager` 只建一次 Car 连接，框架若不派发 ready=false 回调，订阅就永久失效且无日志，档位等属性收不到更新导致无法进 D 档；`PropertyManager` 异步 `getCarManager` 期间若断连，过期 post 还会把 `isServiceConnected` 错误置回 true。缺陷库结论：怀疑 Carlib 回调监听死掉，增加重连规避及看门狗措施。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt、application/SystemUI/src/main/res/drawable/vector_volume_bt_head_mute.xml、application/SystemUI/src/main/res/drawable/vector_volume_bt_head_mute_unactive.xml、component/Carlib/src/main/java/com/neusoft/libcar/manager/CarConnectionManager.kt、component/Carlib/src/main/java/com/neusoft/libcar/manager/PropertyManager.kt
```diff
// application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt
-            return if (isActive) {
-                R.drawable.vector_volume_bt_head
-            } else {
-                R.drawable.vector_volume_bt_head_unactive
-            }
+            return when {
+                isMute && !isActive -> R.drawable.vector_volume_bt_head_mute_unactive
+                isMute -> R.drawable.vector_volume_bt_head_mute
+                !isActive -> R.drawable.vector_volume_bt_head_unactive
+                else -> R.drawable.vector_volume_bt_head
+            }
```
```diff
// component/Carlib/src/main/java/com/neusoft/libcar/manager/CarConnectionManager.kt（看门狗核心）
+    private val mWatchdog = object : Runnable {
+        override fun run() {
+            checkConnectionHealth()
+            mHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
+        }
+    }
+    // checkConnectionHealth: isConnected=false 且 isConnecting=false 持续
+    // SILENT_DISCONNECT_THRESHOLD(3) 次巡检 → forceReconnect()
```
```diff
// component/Carlib/src/main/java/com/neusoft/libcar/manager/PropertyManager.kt（代际令牌防过期回填）
+        val generation = ++connectionGeneration
         Thread{
-            carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
-            recPropertyManager.initManager(carPropertyManager)
-            sendPropertyManager.initManager(carPropertyManager)
+            val manager = try {
+                car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
+            } catch (e: Exception) { null }
             mHandler.post {
+                if (generation != connectionGeneration) { return@post }
+                carPropertyManager = manager
+                if (manager == null) {
+                    CarConnectionManager.requestReconnect()
+                    return@post
+                }
+                reregisterAllCallbacks()
```
新增两个 vector 静音图标（36dp，引用 `icon_default_press`/`icon_default_disabled`）。

## 为什么能修复
SIR-5467 通过补全 `isMute × isActive` 四态图标矩阵恢复静音提示，与设置页行为对齐；判断依据沿用 `isBtMasterDeviceEnabled()`（CarAudioManager 输出设备 `AUDIO_OUTPUT_EXT_BT_MASTER_DEVICE` 位，不再用不准确的 MCU 头盔信号）。SIR-5446 是防御性修复：20s 看门狗检测"未连接且未重连"的静默断连，连续 3 次后 `forceReconnect()` 重建 Car 并补走断连清理；`connectionGeneration` 令牌丢弃过期异步初始化；`getCarManager` 移到子线程避免主线程阻塞；`reregisterAllCallbacks()` 在重连后按注册表补注册所有属性回调，恢复档位订阅。由于缺陷偶发（<10%）且缺陷库注明 HAL 层疑点未排除，此修复属于规避+兜底，非确定性根治。

## 复盘与经验
- 同一提交修两个独立缺陷时要分层描述：UI 态矩阵缺失（确定性）与订阅链路死亡（偶发性）的修复验证方式完全不同。
- 长驻进程对 CarService/系统服务订阅必须有"看门狗 + 代际令牌 + 重连补注册"三件套，否则静默断连只能靠重启恢复。
- 跨应用图标行为（SystemUI OSD vs Setting）应对照实现，同一头盔/耳机状态在两处的静音表现不一致会直接被报为缺陷。
