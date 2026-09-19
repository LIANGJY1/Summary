# SRS_VehSetting_062 · 龙头锁功能

- **提交**：`8f59a234` | 2026-08-11 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_062（Change-Id 与重构链相同，为该需求下单项功能）

## 需求/目标
在"座椅&手把"页新增龙头锁（车把方向锁）控制：显示锁止/解锁状态，点击发送锁/解锁命令，支持故障态与非 P 档禁用；同时清理废弃的座椅复位信号。

## 实现结构
11 个文件、+252/-84：`CarPropertyIds.kt` 新增 `CCU_HANDLELOCKSTATUS=5212`、`CCU_HANDLELOCKSTATUS_STS=5213`（龙头锁状态/故障），删除 `SCU_SEATADJUSTRECOVERYSWITCH=5217`（座椅复位，注释"下发和上报命名反了"的废弃信号）与 `PCU_ACTUALGEARFEED=6108`（改用 `ENERGY_PCU_ACTUALGEAR` 统一档位信号）；`CarPropertyMapping.kt` 注册两个龙头锁映射（映射体暂注释，占位待 HAL 对齐）；`SettingVehicleService` 增加 `handleLockStatus`/`handleLockFaultStatus` LiveData 并入监听表；`VehicleControlFragment` 新增 `initHandleLock`/`sendHandleLockCommand`/`updateHandleLockUI`（四态 UI：0 锁止/1 解锁/2 故障/3 非P档限制）；布局加龙头锁卡片与开关背景 drawable；Constants 定义 setting key。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
+    private fun sendHandleLockCommand() {
+        val value = if (settingVehicleService.handleLockStatus.value == 0) 1 else 0
+        log("Send handle lock command: $value")
+         settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_HANDLELOCKSTATUS, value)
+    }
+
+        settingVehicleService.pcuActualGear.observe(
+            viewLifecycleOwner, Observer { state ->
+                if(state!=0){
+                    updateHandleLockUI(3)
+                }
+            }
+        )
```
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
-    const val PCU_ACTUALGEARFEED: Int = 6108
+    /**
+     *龙头锁
+     */
+    const val CCU_HANDLELOCKSTATUS : Int = 5212
+    const val CCU_HANDLELOCKSTATUS_STS : Int = 5213
```
实现讲解：典型"信号三件套 + 四态 UI"模式——状态信号与故障信号双 LiveData 驱动同一 UI 状态机，档位信号复用（非 0 即非 P 档）叠加为限制态；点击时按当前状态取反值下发命令，即"读状态、算反值、发命令"的乐观控制。故障信号语义写反了逻辑（faultState==0 才显示故障 2），疑为该信号取值定义为 0=故障，需与 DBC 核对。

## 复盘与要点
- 四态状态机把"故障""档位限制"编码进同一个 int（0~3），可读性尚可但来源有三路信号，建议在注释/枚举中固化映射防误改。
- CarPropertyMapping 中龙头锁映射体整段注释（"占位信号ID，需要替换为实际的"），代码先行、HAL 未对齐——合入主干前必须补齐，否则收不到状态。
- 顺带统一档位信号（PCU_ACTUALGEARFEED→ENERGY_PCU_ACTUALGEAR）减少重复定义，是好清理，但需确认所有消费方同步切换（本提交已同步改映射）。
