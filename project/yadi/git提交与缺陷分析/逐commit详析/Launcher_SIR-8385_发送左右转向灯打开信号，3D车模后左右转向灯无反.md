# SIR-8385 · 转向灯打开时 3D 车模后转向灯无反应

- **提交**：`55b7e6ce` | 2026-09-16 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
发送左/右转向灯打开信号后，3D 车模只有前转向灯闪烁，后左/后右转向灯无反应。

## 根因分析
`KanziSignalMapping` 的转向灯信号处理只调用了 Kanzi 的前转向接口：左转 `LEFT_STEERING`、右转 `RIGHT_STEERING`（以及 `TURN_INDICATOR_L/R`）。而 Kanzi 侧后转向灯是独立属性（`LEFT_STEERING_REAR`/`RIGHT_STEERING_REAR`），原本只由 `PCU_TurningLightSts_RL`（0x21400110，PCU 报文）驱动；真实车辆上该 PCU 报文会到，但在台架/仿真环境**只模拟 LCM 报文**（`LCM_TurningLightSts_Left` 0x214000CF 等，且 LCM 报文只带前转向灯状态）时，后转向灯属性永远收不到数据，于是 3D 车模后灯不亮。属"信号来源单一 + 渲染接口未对齐"的驱动缺口，元数据概括为"没有调用 kanzi 后转向接口"。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ 右转信号处理
-            LogUtils.d(TAG, "Right turn signal -> Kanzi: Turn_indicator_R + RightSteering=" + rightTurn);
+            LogUtils.d(TAG, "Right turn signal -> Kanzi: RightSteering + RightSteering_Rear=" + rightTurn);
             kanziManager.setValue("", KanziType.Light.RIGHT_STEERING, rightTurn);
+            // [bugfix] 同左转：LCM_TurningLightSts_Right 同时驱动后右转灯，
+            // 避免只模拟 LCM 报文时 3D 车模后转向灯不亮
+            kanziManager.setValue("", KanziType.Light.RIGHT_STEERING_REAR, rightTurn);
@@ 左转信号处理
         kanziManager.setValue("", KanziType.Light.LEFT_STEERING, leftTurn);
+        // [bugfix] LCM_TurningLightSts_Left(0x214000CF, LCM 报文 0x0C12FF07) 只带前转向灯状态，
+        // 后转向灯原本只由 PCU_TurningLightSts_RL(0x21400110, PCU 报文 0x0C50FF03) 驱动，
+        // 只模拟 LCM 报文时后灯无数据 → 3D 车模只有前转向亮。此处由同一路信号同步驱动后左转灯，
+        // 保证前后转向灯一起闪烁；PCU 信号到达时仍会按实际后灯状态覆盖。
+        kanziManager.setValue("", KanziType.Light.LEFT_STEERING_REAR, leftTurn);
@@ timeoutFlag() 超时清零同步补齐
             kanziManager.setValue("", KanziType.Light.TURN_INDICATOR_L, 0);
             kanziManager.setValue("", KanziType.Light.TURN_INDICATOR_R, 0);
+            kanziManager.setValue("", KanziType.Light.LEFT_STEERING_REAR, 0);
+            kanziManager.setValue("", KanziType.Light.RIGHT_STEERING_REAR, 0);
```

## 为什么能修复
左/右转向信号现在同时驱动前、后两组 Kanzi 属性，后灯不再依赖可能缺失的 PCU 报文；真实环境下 PCU 信号到达仍会覆盖为实际后灯状态，双保险不冲突。`timeoutFlag()` 灯光超时清零同步补上两个 REAR 属性，避免"前灯已灭、后灯残留点亮"的新不一致。副作用：若车辆真实后转向灯状态与前灯不同步（如拖挂、后灯故障），LCM 信号驱动会短暂覆盖真实后灯状态，直到 PCU 报文到来纠正。

## 复盘与经验
- 3D 车模每个可视部件都要核对"信号→渲染属性"的映射完整性，缺一条映射在台架模拟环境（只发部分报文）就暴露为"灯不亮"。
- 增加新的渲染属性驱动点时，必须同步检查超时/复位路径的清零清单，否则修一个 bug 生另一个残留 bug。
- 注释里写明报文 ID（0x214000CF/0x21400110）与覆盖策略，这类跨域（CAN↔渲染）对接非常依赖此类可追溯注释。
