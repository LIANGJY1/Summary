# SIR-1662 · 中低配3D车模不应有座椅调节功能
- **提交**：`05ff7847` | 2026-07-09 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
中低配车型的 3D 车模上仍然显示座椅调节功能入口，与车型配置不符。

## 根因分析
Kanzi 侧根据 `CarModel.ConfigurationLevel` 配置字决定是否渲染座椅调节面板，但宿主端 `KanziDataSourceManager` 在初始化下发各车况初始状态（充电电压/里程/功率等）时，唯独漏掉了配置字的下发。Kanzi 拿不到配置等级，只能按默认（高配）逻辑渲染，导致中低配车模出现不该有的座椅调节功能。问题本质是"宿主与 3D 引擎之间的功能开关协议未闭环"：新增功能开关时只改了 Kanzi 侧，宿主侧下发路径没同步补齐。

## 关键代码修改
改动文件：application/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java（+17）
```diff
--- application/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
@@ private static final 常量区
+    private static final int CONFIGURATION_LEVEL_LOW = 0;
+    private static final int CONFIGURATION_LEVEL_HIGH = 2;
@@ 初始化下发序列
         sendChargingAlreadyPowerInitStateToKanzi();
+        sendConfigurationLevelToKanzi();
     }
+    private void sendConfigurationLevelToKanzi() {
+        int seatPositionConfig = SysPropUtils.INSTANCE.getSeatPosition();
+        int configurationLevel = seatPositionConfig == 0
+                ? CONFIGURATION_LEVEL_HIGH
+                : CONFIGURATION_LEVEL_LOW;
+        LogUtils.d(TAG, "Seat position config -> Kanzi: "
+                + "seatPosition=" + seatPositionConfig
+                + ", ConfigurationLevel=" + configurationLevel);
+        if (isKanziConnected && kanziManager != null) {
+            kanziManager.setValue("", KanziType.CarModel.CONFIGURATION_LEVEL, configurationLevel);
+        }
+    }
```

## 为什么能修复
初始化时把系统属性 `getSeatPosition()` 换算成 0/2 两档配置等级并主动 `setValue` 给 Kanzi，Kanzi 侧据此在中低配隐藏座椅调节面板，功能开关闭环。隐患：配置字只在初始化时下发一次，若下发时 `isKanziConnected` 为 false（Kanzi 尚未连上）则配置丢失，没有重发/连接后补发机制，存在时序上的残余风险。

## 复盘与经验
- **功能开关要双向闭环**：与外部渲染引擎（Kanzi）协作的功能开关，宿主侧"下发路径"必须与引擎侧"消费逻辑"同版本交付，单侧上线即出 bug。
- **新增配置项要有下发清单**：初始化下发序列（sendXxxInitStateToKanzi 一组方法）是隐式契约，新增 Kanzi 属性时应逐项核对该清单。
- **一次性下发依赖连接时序**：配置类数据建议在"连接就绪"事件后补发一次，而不是只在初始化里发一次。
