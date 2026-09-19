# 无单号 · 统一修改驾驶和主动安全相关信号下发值和回弹逻辑

- **提交**：`324a25f0` | 2026-09-02 | sgh | Setting | feature（含多处实质性缺陷修复）
- **关联单**：无

## 需求/目标
把"反馈(状态)信号"与"下发(请求)信号"两套值域在全应用层面统一命名并修正换算：状态开关 0=关/1=开，CCU 设置类命令 1=关/2=开/0=NoAction；加热与能量回收档位"下发值=反馈值+1"；同步修正回弹判断、超时回滚与湿滑模式置灰的极性。

## 实现结构
- `CanSignalConstants.kt`：删掉语义混乱的 `SWITCH_ON_2=1/SWITCH_OFF_1=0`，新立 `SWITCH_CMD_ON=2/SWITCH_CMD_OFF=1`（命令域）与既有 `SWITCH_ON=1/SWITCH_OFF=0`（状态域）对照——常量体系自此"两域四值"清晰。
- `DrivingFragment.kt`：能量回收下发 `position+1`，新增 `ENERGY_RECOVERY_STS_OFF=0/REQ_OFF=1` 双常量；回弹判断从 `temp == feedback` 改为 `feedback + 1 == temp`；湿滑/极致续航下发用 `SWITCH_CMD_*`、回显用 `SWITCH_ON`；**修复湿滑模式置灰极性** `(valid == 1)` → `(valid == 0)`；**修复极致续航节能下发热器发 0 的 bug**——0 在新协议是 NoAction 不生效，改为 `SWITCH_CMD_OFF(1)`。
- `DrivingSafetyFragment.kt`：坡道驻车回显从 `state == 2` 改为 `state == SWITCH_ON(1)`（反馈域），陡坡缓降注释同步，魔法数字消除。
- `VehicleControlFragment.kt`：座椅/把手加热的图标映射回到反馈域 0..3，档位点击与 RadioGroup 选择一律 `+1` 后下发并存 temp；超时回滚条件 `getState() != getTemp()` 改为 `getState() + 1 != getTemp()`（否则永远不等、1 秒必回滚）。

数据流：UI position → `+1` 变请求值下发；CAN 状态 0..3 → 原样刷 UI，`状态+1 == 已发请求` 才 cancelRebound，否则失败 toast/超时回滚。

## 关键代码
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CanSignalConstants.kt
@@ -17,16 +17,11 @@
-    const val SWITCH_OFF_1 = 0
-    const val SWITCH_ON_2= 1
+    /**
+     * 下发命令开关值（新协议，CCU 设置类信号）
+     * 0x02 开
+     * 0x01 关
+     * 0x00 No Action
+     */
+    const val SWITCH_CMD_ON = 2//开
+    const val SWITCH_CMD_OFF = 1//关
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -462,8 +469,9 @@
-        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SETSEATHEATSWREQ, 0)
-        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, 0)
+        // 加热档位信号为 CCU 新协议：关闭需下发 SWITCH_CMD_OFF(1)，0 为 No Action 不生效
+        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SETSEATHEATSWREQ, CanSignalConstants.SWITCH_CMD_OFF)
+        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, CanSignalConstants.SWITCH_CMD_OFF)
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -343,7 +345,8 @@
                 delay(1000)
-                if (getState() != getTemp()) {
+                // 反馈(0..3)与下发(1..4)值域不同，反馈+1==下发才表示已达期望档位
+                if (getState() + 1 != getTemp()) {
```

实现讲解：这是对 fc29efff/e0903b2f/7e29ce90 三次演进的"总收口"：确立"状态域 0/1、命令域 1/2、档位请求=反馈+1"三条换算规则，并用 `CMD/STS` 前缀常量固化。三处真 bug 一并修掉——湿滑置灰极性反了、加热关闭发 0 无效、回弹值域不匹配导致必回滚。

## 复盘与要点
- "同一信号请求/反馈值域错位 1"是多档位 CAN 的高发坑，约定 `req = sts + 1` 后，所有比较点必须显式写 `+1` 并注释，本提交每个比较点都加了说明，值得照抄。
- `0` 在带 NoAction 语义的协议里是"不发"，不是"关"——把关闭操作写成发 0 是典型协议误读，评审时见到字面量 0 下发要条件反射式质疑。
- 常量体系经过 SWITCH_ON/OFF → ON_2/OFF_1 → CMD_ON/CMD_OFF 三版，说明前期没有先定义"两域"模型；新项目接入 CAN 前应先定值域规范再写业务。
