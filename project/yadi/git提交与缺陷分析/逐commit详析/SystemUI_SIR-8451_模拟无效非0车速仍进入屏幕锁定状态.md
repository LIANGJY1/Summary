# SIR-8451 · 无效非 0 车速信号仍触发屏幕锁定
- **提交**：`ff4c51c9` | 2026-09-15 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
整车信号模拟中，车速有效性标记为"无效"但数值非 0 时，SystemUI 页面状态机仍按非 0 车速处理，进入屏幕锁定状态（行车锁定误触发）。

## 根因分析
`application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt` 此前只订阅 `CarPropertyIds.PCU_VEHICLE_SPEED`（5905）并在回调里直接 `PageStateMachine.setSpeed(speed)`，**完全没有消费车速有效性信号 `PCU_VEHICLE_SPEED_VALID`（5706）**。车速有效性（0x0 有效 / 0x1 无效）与数值是两条独立信号：信号源异常/模拟注入时会出现"valid=无效但 speed=非0"的组合，服务不加甄别地把无效数值喂给 `PageStateMachine`，状态机按"车辆在行驶"判定，误入屏幕锁定。同文件对档位已有 `currentGearValid` 同款有效性缓存处理（注释"当前档位有效性缓存"），车速是漏掉的孪生场景。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
@@ +    /** 当前车速有效性缓存（0：有效，1：无效；开机默认有效） */
+    private var currentSpeedValid: Boolean = true
@@ (订阅列表)
         CarPropertyIds.PCU_VEHICLE_SPEED, //5905 车速
+        CarPropertyIds.PCU_VEHICLE_SPEED_VALID, //5706 车速有效性信号
@@ (信号分发)
                     val speed = (value as? Float) ?: return
                     LogUtils.i(TAG, "PCU_vehicle_speed: speed=${speed}")
-                    PageStateMachine.setSpeed(speed) //同步车速
+                    if (!currentSpeedValid) {
+                        LogUtils.i(TAG, "PCU_vehicle_speed: speed=${speed} but currentSpeedValid=false, treat as 0")
+                        PageStateMachine.setSpeed(0f)
+                    } else {
+                        PageStateMachine.setSpeed(speed)
+                    }
+                }
+                CarPropertyIds.PCU_VEHICLE_SPEED_VALID -> {
+                    val speedValid = (value as? Int) ?: return
+                    val wasValid = currentSpeedValid
+                    currentSpeedValid = (speedValid == 0)
+                    if (!currentSpeedValid) {
+                        LogUtils.i(TAG, "PCU_VEHICLE_SPEED_VALID: speed invalid, force speed to 0")
+                        PageStateMachine.setSpeed(0f)
+                    }
+                    if (!wasValid && currentSpeedValid) {
+                        val latestSpeed = mCarServiceManager?.getCachedFloatProperty(
+                            CarPropertyIds.PCU_VEHICLE_SPEED, defaultValue = 0f) ?: 0f
+                        PageStateMachine.setSpeed(latestSpeed)
+                    }
                 }
```

## 为什么能修复
三条路径全部封堵无效值进入状态机：无效期间收到 speed → 按 0 上报；有效性翻为无效 → 立即强制 `setSpeed(0f)`（不等下一条 speed）；由无效恢复有效 → 用 `getCachedFloatProperty` 取缓存的真实车速回填，避免状态机滞留 0。由此"无效非 0 车速"在任意到达顺序下都被净化为 0 或真实值，屏幕锁定不再误触发。隐患：恢复瞬间用缓存车速可能与随后到达的实时 speed 有一次微小跳变，已由状态机自身平滑；开机默认 `currentSpeedValid=true` 在信号未上线前与旧行为一致。

## 复盘与经验
- 车载 CAN 信号普遍"数值 + 有效性"成对出现，消费数值信号时必须同步订阅有效性信号并做净化，否则模拟器/信号丢失场景必出误判。
- 同一服务内同类信号的处理模式要复制齐全：本例档位已有 valid 缓存处理，车速漏配，评审时按清单逐信号核对可提前发现。
- 有效性翻转的两个方向都要处理：变无效时强制兜底值，恢复有效时要回填最新真实值，避免状态机停在中间态。
