# 无单号 · 3D 车模充电枪已连接信号不显示"已连接"
- **提交**：`3bed565c` | 2026-07-17 | liqingqing | Launcher | bugfix
- **缺陷库**：未关联单号

## 问题
充电枪物理已连接，但 3D 车模上充电枪未显示"已连接"状态。

## 根因分析
提交消息自述"少传了 Charging_State=1"。`KanziSignalMapping.updateChargingGunStateToKanzi()` 此前只把组合后的枪连接状态写给 Kanzi 的 `CHARGING_GUN_STATE`（枪连接显示），没有同步写 `CHARGING_STATE`（充电状态页依赖的信号），导致依赖 `Charging_State` 的界面拿不到"已连接=1"，充电枪已连接的展示不生效——两个状态量语义相邻却只更新了一个。diff 同时顺带修复了枪连接信号超时的联动：新增监听 `ENERGY_MCU_SEND_MPU_TIMEOUT_REPORT_0C12FF04`，超时时强制 AC/DC 枪状态清零并回刷，且超时期间丢弃 AC/DC 枪状态回调；反之充电结束（Charging_State=0）时同步清零枪连接状态，避免另一方向的残留显示。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java (updateChargingGunStateToKanzi)
         kanziManager.setValue("", KanziType.CarModel.CHARGING_GUN_STATE, kanziState);
+        // 充电枪连接/断开时同步更新 Charging_State，确保充电页面状态刷新
+        kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, kanziState);
```

```diff
--- 同文件（新增超时信号处理）
+        signalHandlers.put(CarPropertyIds.ENERGY_MCU_SEND_MPU_TIMEOUT_REPORT_0C12FF04, event -> {
+            mChargingGunTimeoutFlag = (int) event.getValue();
+            if (mChargingGunTimeoutFlag == 1) {
+                mAcChargingGunStatus = 0;
+                mDcChargingGunStatus = 0;
+                updateChargingGunStateToKanzi();   // 超时视为断开并回刷
+            }
+        });
```

## 为什么能修复
根因"少传 Charging_State"由第一处 hunk 直接消除：枪状态每次变化都会同步把 `CHARGING_STATE` 写给 Kanzi，连接时即为 1。配套的超时信号联动保证信号链路异常时不会把"已连接"卡在屏幕上（超时清零 + 丢弃陈旧回调），充电结束清零枪状态则消除双向的状态残留。`VehicleService` 订阅清单同步注册新信号 ID，保证回调能收到。

## 复盘与经验
- 强相关的一组状态量（枪连接/充电状态）必须成对同步下发，只更新其一就会造成"页面 A 已连接、页面 B 无反应"的半更新 bug。
- 车体信号要配套监听对应的超时/无效标志（如 0x0C12FF04），并在超时时主动降级为安全默认值，否则最后一份有效值会被无限期展示。
- 新增信号监听别忘同步登记到 VehicleService 的订阅 ID 列表——订阅清单与 handler 注册分离是这类框架的常见漏点。
