# 无单号 · [3D车模]增加氛围灯开关和显示模式接口

- **提交**：`a926ecd4` | 2026-08-19 | liqingqing | Launcher | 功能接口新增（标题标注bugfix，实际为纯新增能力，无缺陷关联）
- **缺陷库**：未关联单号

## 问题
3D 车模缺少氛围灯开关与显示模式（常亮/呼吸）的信号接入，Kanzi 端无法展示氛围灯状态。

## 根因分析
本提交不是缺陷修复，而是为 3D 车模补齐氛围灯数据通路：在 `CarPropertyIds.kt` 新增 `SCREEN_AMBIENT_LIGHT_SWITCH`、`SCREEN_AMBIENT_LIGHT_MODE`、`THREE_D_MODEL_MCU_SEND_MPU_TIMEOUT_REPORT_0C10FF07` 三个属性 ID 并在 `CarPropertyMapping.kt` 注册；`VehicleService` 将三个信号加入订阅列表；`KanziSignalMapping` 为三个信号各注册 handler 缓存值并调用 `updateAmbientLightToKanzi()` 合成状态；`KanziDataSourceManager.sendAmbientLightInitStateToKanzi()` 在初始化时查询初值；`KanziType` 新增 `Light.Ambientlight` 常量。状态合成规则：超时标志为 1 时下发 0；开关开+模式0（常亮）下发 1；开关开+模式1（呼吸）下发 2；其余下发 0。

## 关键代码修改
改动文件：application/Launcher/.../control/KanziDataSourceManager.java、control/KanziSignalMapping.java、manager/KanziType.java、services/VehicleService.java、component/Carlib/.../CarPropertyIds.kt、CarPropertyMapping.kt（6 文件，+70/-0）
```diff
@@ application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java @@
+    private void updateAmbientLightToKanzi() {
+        if (mScreenAmbientLightTimeoutFlag == 1) {
+            kanziManager.setValue("", KanziType.AMBIENTLIGHT, 0);
+            return;
+        }
+        int ambientLightState = 0;
+        if (mScreenAmbientLightSwitch == 1 && mScreenAmbientLightMode == 0) {
+            ambientLightState = 1;
+        } else if (mScreenAmbientLightSwitch == 1 && mScreenAmbientLightMode == 1) {
+            ambientLightState = 2;
+        }
+        kanziManager.setValue("", KanziType.AMBIENTLIGHT, ambientLightState);
+    }
```

## 为什么能修复
功能补齐类改动：信号订阅 + 初值查询 + 状态合成三件套，保证 Kanzi 车模在冷启动（init 查询）和运行期（信号回调）都能拿到氛围灯状态。无修复副作用讨论意义；隐患在于模式值 0x2~0x7（闪烁/Reserved）未映射，当前按 0 处理。

## 复盘与经验
- 车模/仪表类 UI 接新信号的标准套路：注册 ID → 订阅回调 → 初始化查询初值 → 多信号合成状态，缺"初值查询"就会出现重启后状态错。
- 超时标志（MCU->MPU 通信超时）要纳入显示合成，通信异常时宁可显示默认态也不显示旧值。
- 标注 [bugfix] 前缀但实为功能新增的提交，建议在缺陷库中留痕，避免后期统计失真。
