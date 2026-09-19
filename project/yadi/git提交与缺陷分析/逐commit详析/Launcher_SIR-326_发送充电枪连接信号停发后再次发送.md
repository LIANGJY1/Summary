# SIR-326 · 充电枪超时信号恢复后3D车模未恢复连接状态（超时清零后无恢复路径）

- **提交**：`3bcae00f` | 2026-07-24 | liqingqing | Launcher | bugfix
- **缺陷库**：未关联单号（单号 SIR-326，缺陷库无记录）

## 问题
发送充电枪连接信号停发（触发超时信号）后再次发送，3D 车模未进入充电枪连接状态，一直显示未连接。

## 根因分析
`KanziSignalMapping.java` 处理充电枪连接超时信号 `ENERGY_MCU_SEND_MPU_TIMEOUT_REPORT_0C12FF04` 时，只有 `超时=1` 的分支：把缓存 `mAcChargingGunStatus/mDcChargingGunStatus` 清零并下发 `Charging_Gun_State=0`（未连接）。而**超时恢复（=0）没有任何处理**——超时期间即使 AC/DC 插枪状态信号（4010/4011）已经恢复为"已连接"，本地缓存仍是清零后的 0，后续也没有人再推一次状态，Kanzi 侧就永远停在"未连接"。这是一个典型的"故障进入分支有、故障恢复分支无"的单向状态机缺陷。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ ENERGY_MCU_SEND_MPU_TIMEOUT_REPORT_0C12FF04 处理
                 mAcChargingGunStatus = 0;
                 mDcChargingGunStatus = 0;
                 updateChargingGunStateToKanzi();
+            } else {
+                refreshChargingGunStateFromVehicle();
             }
         });
@@ 新增恢复方法
+    private void refreshChargingGunStateFromVehicle() {
+        if (mVehicleService == null) {
+            LogUtils.w(TAG, "Charging gun timeout recovered but VehicleService is null");
+            updateChargingGunStateToKanzi();
+            return;
+        }
+        mAcChargingGunStatus = mVehicleService.getIntProperty(
+                CarPropertyIds.ENERGY_EEM_AC_CHARGING_GUN_CONNECTION_STATUS, 0);
+        mDcChargingGunStatus = mVehicleService.getIntProperty(
+                CarPropertyIds.ENERGY_EEM_DC_CHARGING_GUN_CONNECTION_STATUS, 0);
+        LogUtils.d(TAG, "Charging gun timeout recovered, refresh status: acStatus="
+                + mAcChargingGunStatus + ", dcStatus=" + mDcChargingGunStatus);
+        updateChargingGunStateToKanzi();
+    }
```

## 为什么能修复
超时恢复（信号=0）时新增 `refreshChargingGunStateFromVehicle()`：通过 `mVehicleService.getIntProperty` 重新读取 4010（AC）/4011（DC）充电枪连接状态，刷新本地缓存并调 `updateChargingGunStateToKanzi()` 重下 Kanzi，车模随即恢复连接显示；进入分支保持原逻辑不变，行为对超时场景完全兼容。`VehicleService` 为 null 时降级为按当前缓存刷新并有告警日志，不崩。隐患：恢复读的是 VHAL 当前值，若超时恢复与插枪信号恢复之间存在时序差（先恢复超时、后恢复插枪），仍需依赖后续插枪信号事件再刷新一次。

## 复盘与经验
- 处理"故障/超时/降级"信号时，状态机必须对称：`置位分支`写了什么，`复位分支`就要恢复什么，缺一半就是单向锁死。
- 超时清零属于"以缓存标记异常"，恢复时不能相信缓存，必须回到数据源（VHAL getIntProperty）重读真实值。
- 3D 车模是纯被动渲染端，凡 Launcher 侧"主动下发"过的清零，就必须有对应的"再下发"时机，Kanzi 自己不会恢复。
