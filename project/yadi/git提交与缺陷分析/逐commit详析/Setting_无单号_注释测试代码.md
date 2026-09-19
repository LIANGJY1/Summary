# 无单号 · 注释测试代码（debugSimulateResponse 熔断）

- **提交**：`004dd2c7` | 2026-07-23 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
提交上线前把座椅联调用的"模拟对手件回复"入口注释掉，防止 `debugSimulateResponse` 在量产环境伪造车控信号。

## 实现结构
单文件 1 行：`init/SettingVehicleService.kt` 中 `debugSimulateResponse` 方法体的唯一一行 `mCarServiceManager?.debugSimulateResponse(propertyId, value)` 被注释，方法保留为空壳。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
     fun debugSimulateResponse(propertyId: Int, value: Int) {
-        mCarServiceManager?.debugSimulateResponse(propertyId, value)
+//        mCarServiceManager?.debugSimulateResponse(propertyId, value)
     }
```
实现讲解：此前 `d2257e2c` 在 VehicleControlFragment/SettingVehicleService 里散布了多处 400ms `postDelayed { debugSimulateResponse(...) }` 模拟硬件回复；本提交切断唯一出口，使所有调用点静默无效。注意 `f9f95112`（同日更早）曾把这行恢复，本提交再次注释——来回切换反映该开关完全靠手工管理。

## 复盘与要点
- 注释一行作为"总开关"极脆弱：任何人重新格式化、合并冲突恢复都可能把它带回来；应改为 `BuildConfig.DEBUG`/系统属性门控，或在 release 构建用 Proguard 剔除。
- 同类风险点：Fragment 里那些 400ms `postDelayed { debugSimulateResponse }` 调用代码仍在（只是出口空转），后续清理应连同调用点一起删。
