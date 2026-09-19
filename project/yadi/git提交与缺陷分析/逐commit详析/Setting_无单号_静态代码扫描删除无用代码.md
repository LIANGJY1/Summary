# 无单号 · 静态代码扫描删除无用代码（SettingVehicleService）

- **提交**：`1616013f` | 2026-07-17 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
静态扫描整改的延续：删除 `SettingVehicleService.kt` 中全部被注释掉的死代码（103 行），仅 1 行为把被注释的 `Log.d` 属性变更日志恢复启用。

## 实现结构
单文件改动（+1/-103）：
- 删除注释掉的方法：`setScreen`、`operationScreenState`（旧亮度控制通道）、`registerSeatPropCallback`（旧座椅 SDK 属性监听）及注释掉的成员 `seatSetManager`、`vehiclePropertyEventListener`；
- 删除注释掉的 LiveData 声明 `driverSeatHeightAdjustSetting` 与若干空 `else` 注释分支；
- 唯一的功能性行为：`onChangeEvent` 里把被注释的 `Log.d(TAG, "Car property change: ...")` 恢复，便于车控属性联调。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
         override fun onChangeEvent(event: AppCarPropertyValue<*>) {
             val propertyId = event.propertyId
             val value = event.value
-//            Log.d(TAG, "Car property change: propId=$propertyId, value=$value")
+            Log.d(TAG, "Car property change: propId=$propertyId, value=$value")
 
             propertyHandlerMap[propertyId]?.invoke(value)
         }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
-    /**
-     * 设置屏幕亮度
-     */
-//    fun setScreen(screen: String?, value: Int) {
-//        if (mL2AIsReady) {
-//            ...
-//            IviCommManager.getInstance()
-//                ?.sendCommBundle(Constants.ID_BACKLIGHT_CONTROL, "Set", bundle)
-//        }
-//    }
```
实现讲解：这是典型的"注释代码先注释、验证稳定后删除"生命周期。`registerSeatPropCallback` 等旧 VehicleSDK 座椅回调路径已被 `propertyHandlerMap` 事件路由机制取代，注释体失去参考价值后整块清除。

## 复盘与要点
- 死代码集中清理降低了 `SettingVehicleService` 的阅读噪音，该文件是 Setting 车控信号的核心枢纽，保持干净很关键。
- 唯一恢复的一行日志提示：上一轮整改可能"误杀"了联调期有用的属性日志，删除注释代码前应区分"确认废弃"与"临时禁用"。
- 后续同日提交 `d2257e2c`、`f9f95112` 会继续改这个文件的多账户逻辑，可见该模块处于活跃迭代期。
