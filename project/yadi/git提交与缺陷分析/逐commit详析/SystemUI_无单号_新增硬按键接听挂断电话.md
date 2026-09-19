# 无单号（SIR-XXXX）· 新增硬按键（滚轮左右键）接听/挂断电话
- **提交**：`de20214f` | 2026-09-08 | caohongliang | SystemUI/BTPhone/Carlib | 功能新增（非 bugfix，未关联单号）
- **缺陷库**：未关联单号

## 问题
车辆新增专用电话硬按键（滚轮左键/右键），需要在来话/通话中实现按键接听、挂断；此前 `SteeringWheelKeyManager` 只把方控"上一曲/下一曲"复用为接听/挂断。

## 根因分析（功能实现剖析）
这是一条跨三层的信号链路，本提交把它打通：
1. **Carlib 层**：`CarPropertyIds.kt` 新增 CAN 信号 `ROLLER_LEFT_SWITCH(6126)`、`ROLLER_RIGHT_SWITCH(6127)`（0=松开，1=按下），`CarPropertyMapping.kt` 补充到 `VehiclePropertyIds` 的映射。
2. **SystemUI 层**：`DigitalKeyVehicleService` 在属性回调里监听两个滚轮信号，经 `updatePhoneKeySetting()` 校验（非 Int 拒绝、非 0/1 值告警忽略）后，转换为 `Settings.Global.putInt("ivi_swt_key_code", 0/2/3)` 写入全局设置——用 Settings 作为跨进程事件通道（BTPhone 只监听这个键值）。关键细节：滚轮按键注册为**瞬时信号**，`registerPropertyCallbacks(phoneRollerPropertyIds, callback, immediateCallback=false)`，注释明确"瞬时按键信号禁止立即回传，避免重连时误触发"——防止服务重连后回放缓存的 1（按下）值造成 phantom 接听/挂断；注销时也把两个 id 拼进 unregister 列表。
3. **BTPhone 层**：`SteeringWheelKeyManager.handleKeyCode()` 新增 `KEY_CODE_RELEASED = 0` 的松开忽略分支，接听=左键(2)、挂断=右键(3)，仍按原逻辑"有活动通话才生效"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt、component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt、component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt、application/BTPhone/src/main/java/com/yadea/btphone/manager/SteeringWheelKeyManager.java
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
+    /** 电话硬按键属于瞬时信号，注册时不读取缓存值。 */
+    private val phoneRollerPropertyIds = listOf(
+        CarPropertyIds.ROLLER_LEFT_SWITCH,
+        CarPropertyIds.ROLLER_RIGHT_SWITCH
+    )
+    private fun updatePhoneKeySetting(signalName: String, value: Any?, pressedKeyCode: Int) {
+        val switchValue = value as? Int ?: run { ... return }
+        if (switchValue != 0 && switchValue != 1) { ... return }
+        val keyCode = if (switchValue == 1) pressedKeyCode else PHONE_KEY_CODE_RELEASED
+        val success = Settings.Global.putInt(mAppContext.contentResolver, PHONE_KEY_CODE_SETTING, keyCode)
+    }
@@ (注册)
+            mCarServiceManager?.registerPropertyCallbacks(
+                phoneRollerPropertyIds, carPropertyEventCallback,
+                false // 瞬时按键信号禁止立即回传，避免重连时误触发
+            )
--- application/BTPhone/src/main/java/com/yadea/btphone/manager/SteeringWheelKeyManager.java
+        if (keyCode == KEY_CODE_RELEASED) {
+            LogUtils.d(TAG, "Steering wheel key released");
+            return;
+        }
```

## 为什么能（这样）实现
信号经 Carlib 属性系统→SystemUI 归一化→Settings 键值→BTPhone 消费，复用了既有的方控按键监听通路（原键值 2/3 语义顺延），改动量最小。瞬时信号禁用立即回传 + 松开值(0)显式转发，两端共同保证不会因注册回放误触接听/挂断。隐患：Settings.Global 是粘性通道，BTPhone 错过"按下"事件（进程刚启动）会丢按键；多来源同时写 `ivi_swt_key_code`（方控与滚轮）时键值语义靠约定区分，后续维护需谨慎。

## 复盘经验
- 硬按键这类瞬时信号注册 CarProperty 监听时必须 immediateCallback=false，否则重连回放缓存值会"凭空按键"——这是车机信号订阅的通用陷阱。
- 跨进程事件用 Settings.Global 传键值实现简单，但粘性/丢事件/多写者问题要在设计期想清楚，事件量大时应换 Messenger/广播。
- 输入侧做值域校验（非 0/1 拒绝）并打日志，联调时能快速分辨信号源问题还是消费端问题。
