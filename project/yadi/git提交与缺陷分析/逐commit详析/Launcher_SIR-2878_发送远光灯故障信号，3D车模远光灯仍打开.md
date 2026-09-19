# SIR-2878 · 远光灯故障时3D车模仍显示远光灯（VHAL缺少故障信号监听）

- **提交**：`898ea752` | 2026-07-23 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
发送远光灯故障信号后，3D 车模的远光灯仍然显示打开。

## 根因分析
`KanziSignalMapping.java` 只订阅了远光灯状态信号 `THREE_D_MODEL_LCM_HIGHBEAMSTS`，收到后直接 `kanziManager.setValue("", KanziType.Light.FAR_BEAM, highBeam)` 透传给 Kanzi——完全没有监听远光灯**故障**信号，故障=1 时状态信号可能仍是 1（或保持旧值），3D 模型因此继续点亮远光。对照近光灯已有的完整实现（`mLowBeamStatus/mLowBeamFaultStatus` 双缓存 + `updateLowBeamToKanzi()` 组合判断），远光灯是从一开始就少接了这条信号。缺陷库 rc"vhal未添加该信号"准确：`CarPropertyIds` 里根本没有远光故障 ID，`VehicleService` 订阅列表里也没有。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java；application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java；component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt；component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
+    /**
+     * 远光灯故障状态(0:正常,1:故障)
+     */
+    const val THREE_D_MODEL_LCM_HIGHBEAMFLTSTS: Int = 6221
--- a/component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
+        CarPropertyIds.THREE_D_MODEL_LCM_HIGHBEAMFLTSTS to CarPropertyIdWrapper(
+            recId = VehiclePropertyIds.LCM_HIGHBEAMFLTSTS,
+            recType = Int::class
+        )//远光灯故障状态(0:正常,1:故障)
--- a/application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java
             CarPropertyIds.THREE_D_MODEL_LCM_HIGHBEAMSTS,//反馈,0：关闭  1：开启
+            //远光灯故障状态(0:正常,1:故障)
+            CarPropertyIds.THREE_D_MODEL_LCM_HIGHBEAMFLTSTS,
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+    private int mHighBeamStatus = 0;      // 0=关闭, 1=开启
+    private int mHighBeamFaultStatus = 0; // 0=正常, 1=故障
         signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_HIGHBEAMSTS, event -> {
-            int highBeam = (int) event.getValue();
-            kanziManager.setValue("", KanziType.Light.FAR_BEAM, highBeam);
+            mHighBeamStatus = ((Number) event.getValue()).intValue();
+            updateHighBeamToKanzi();
+        });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_HIGHBEAMFLTSTS, event -> {
+            mHighBeamFaultStatus = ((Number) event.getValue()).intValue();
+            updateHighBeamToKanzi();
+        });
+    private void updateHighBeamToKanzi() {
+        int farBeam = mHighBeamFaultStatus == 1 ? 0 : mHighBeamStatus;
+        kanziManager.setValue("", KanziType.Light.FAR_BEAM, farBeam);
+    }
```

## 为什么能修复
从信号定义（`CarPropertyIds=6221`）、VHAL 映射（`LCM_HIGHBEAMFLTSTS`）、服务订阅（`VehicleService`）到信号处理（`signalHandlers`）全链路补齐故障信号；两个 handler 都收敛到 `updateHighBeamToKanzi()`，以"故障优先"组合判断（fault=1 强制下发 0）再写 Kanzi，任一信号先到都能得到正确的合并结果，与近光灯实现完全对称。副作用：`KanziType.Light.FAR_BEAM` 的写入从"状态直传"变为"组合计算"，若后续还要叠加超时信号（`mLightTimeoutFlag` 类似逻辑），需并入同一方法。

## 复盘与经验
- 车模/仪表类显示要区分"状态信号"与"故障信号"两条输入，显示值 = f(状态, 故障)，只接状态信号是本类 bug 的固定模式——近光已示范了正确写法，远光漏抄是典型的复制不对称。
- 车控信号从 VHAL 到 UI 要过四层（ID 定义→映射→订阅→handler），新增信号必须四层同补，漏一层就是"静默无效"。
- 多信号合成一个显示项时，把所有 handler 收敛到同一个 update 方法做全量重算，避免信号到达顺序造成的中间态错显。
