# VIR-182 · 近光灯故障时 3D 车模近光灯未同步熄灭
- **提交**：`29101276` | 2026-07-21 | liqingqing | Launcher | bugfix
- **缺陷库**：未关联单号（VIR-182 无缺陷库记录）

## 问题
人为触发近光灯故障后，3D 车模上对应的近光灯没有同步熄灭，与实际车况故障状态不一致。

## 根因分析
提交消息自述"没有监听近光灯故障信号"。`KanziSignalMapping` 原来只订阅 `THREE_D_MODEL_LCM_LOWBEAMSTS`（近光灯开关状态），handler 直接 `kanziManager.setValue(NEAR_BEAM, lowBeam)` 透传给 Kanzi；近光灯故障信号 `LCM_LOWBEAMFLTSTS`（0=正常，1=故障）完全没有接入。故障发生时车端开关状态可能仍是"开"，Kanzi 收到的还是 1，模型上灯继续亮。本质是渲染值 = f(状态，故障) 的组合逻辑缺少了第二个输入，且没有缓存两个输入做联动计算。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`、`component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt`、`component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt`

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+    private int mLowBeamStatus = 0;        // 近光灯状态：0=关闭, 1=开启
+    private int mLowBeamFaultStatus = 0;   // 近光灯故障状态：0=正常, 1=故障
@@ handler 改造
         signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_LOWBEAMSTS, event -> {
-            int lowBeam = (int) event.getValue();
-            kanziManager.setValue("", KanziType.Light.NEAR_BEAM, lowBeam);
+            mLowBeamStatus = ((Number) event.getValue()).intValue();
+            updateLowBeamToKanzi();
         });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_LOWBEAMFLTSTS, event -> {
+            mLowBeamFaultStatus = ((Number) event.getValue()).intValue();
+            updateLowBeamToKanzi();
+        });
```

```diff
--- 同文件（新增组合计算）
+    private void updateLowBeamToKanzi() {
+        int nearBeam = mLowBeamFaultStatus == 1 ? 0 : mLowBeamStatus;   // 故障时强制发0关闭
+        kanziManager.setValue("", KanziType.Light.NEAR_BEAM, nearBeam);
+    }
```

## 为什么能修复
两个输入信号各自缓存于 `mLowBeamStatus`/`mLowBeamFaultStatus`，任一变化都触发 `updateLowBeamToKanzi()` 统一计算：故障=1 时无论开关状态如何都向 Kanzi 发 0，灯模熄灭；故障恢复后自动回显真实开关状态。`VehicleService` 订阅清单与 Carlib 映射表同步新增 6220 号信号，链路完整。隐患：故障恢复瞬间若车端不重发开关状态，显示依赖缓存的 `mLowBeamStatus`（符合预期，因为缓存即最后已知值）。

## 复盘与经验
- 渲染值由多信号组合决定时（状态 + 故障 + 超时），要用"缓存各输入 + 单一计算函数"模式，任一输入变化重算，避免在单个 handler 里直传。
- 故障/降级信号是显示层必须消费的一等输入，接信号清单时要对照整车信号表把"状态对"（X_STS / X_FLT_STS）一起接。
- 与 058f0cf2 同日同模式：Carlib 加 ID → 映射表加 recId → VehicleService 订阅 → KanziSignalMapping 加 handler，这套四层样板已成团队肌肉记忆，值得沉淀为接入 checklist。
