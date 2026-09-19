# SIR-6132 · 模拟插枪充电取消后桌面时间日期消失
- **提交**：`52d7fb85` | 2026-08-31 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 能量中心

## 问题
模拟插枪充电后再取消（拔枪），回到 3D 驻车桌面时系统时间、日期不显示，桌面停留在异常状态。

## 根因分析
Launcher 的 `KanziSignalMapping` 负责把车控信号翻译给 Kanzi 3D 车模/桌面。原 `updateChargingGunStateToKanzi()` 末尾是 `if (mObcChargeState == 0) { setValue(CHARGING_STATE, kanziState); }`——只有 OBC 充电状态恰好已经归零时才补发 `CHARGING_STATE`；拔枪/取消瞬间若 `mObcChargeState` 尚未刷新（仍停在充电中的 0x1 等），`CHARGING_STATE` 信号就永远不发，Kanzi 侧状态机停留在"充电中"场景。3D 桌面在充电场景下会隐藏时间/日期组件，于是表现为"时间日期消失"。此外 `updateChargingStateToKanzi()` 里 0x0 映射为 0、靠末尾 `if (kanziState == 0)` 再用枪连接状态兜底回 1，逻辑缠绕且依赖"枪已连接"判断分散在两处。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- a/.../launcher/control/KanziSignalMapping.java
@@ updateChargingGunStateToKanzi
         kanziManager.setValue("", KanziType.CarModel.CHARGING_GUN_STATE, gunState);
-        if (mObcChargeState == 0) {
-            kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, kanziState);
+        if (!connected) {
+            LogUtils.d(TAG, "Charging gun disconnected -> Kanzi: Charging_State=0");
+            kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, 0);
+            return;
         }
+        // 先同步进入已插枪状态，再用缓存的 OBC 状态刷新充电中/完成/异常状态。
+        kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, 1);
+        if (mObcChargeState != 0) {
+            updateChargingStateToKanzi();
+        }
```
```diff
@@ updateChargingStateToKanzi
+        if (!isChargingGunConnected()) {
+            ... // 枪断开时强制 CHARGING_GUN_STATE=0、CHARGING_STATE=0
+            return;
+        }
         switch (mObcChargeState) {
             case 0x0:
-                kanziState = 0;
+                kanziState = 1;   // 0x0 → 1(已插枪)，注释同步更新
```
（同提交还把氛围灯信号 handler 的 key 从 `CarPropertyIds.SCREEN_AMBIENT_LIGHT_SWITCH/MODE` 换成了 `VehiclePropertyIds.LCM_IPSWITCHSTS/LCM_IPMODESET`，属氛围灯信号源切换，与充电修复同车打包。）

## 为什么能修复
新逻辑保证状态迁移"必有终点"：拔枪 → 无条件发 `CHARGING_STATE=0`，Kanzi 收到后退出充电场景、恢复显示时间/日期；插枪 → 先发"已插枪(1)"再按缓存 OBC 状态细化，消除原"0x0 映射 0 又被兜底掰回 1"的歧义。根因"条件性补发导致状态卡死"被无条件状态收敛取代。隐患：依赖信号到达顺序（枪状态先于 OBC 状态），若底层乱序仍可能闪一帧中间态，但不会再卡死。

## 复盘与经验
- 跨进程 UI 状态机（MPU→Kanzi）里，"只在某些条件下才发的状态信号"是卡死类缺陷的典型根源；状态跃迁必须保证终态信号无条件发出。
- 把"枪是否连接"提取为 `isChargingGunConnected()` 单点判断，消除了两处复制的连接判定漂移。
- 模拟器/仿真注入测试取消操作时，要额外验证"取消后的状态回落"，这正是本缺陷的触发路径。
