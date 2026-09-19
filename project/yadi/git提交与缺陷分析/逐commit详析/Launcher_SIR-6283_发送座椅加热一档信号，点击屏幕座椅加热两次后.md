# SIR-6283 · 3D车模座椅加热连点两次后状态变未加热

- **提交**：`59ee74b9` | 2026-08-24 | liqingqing | Launcher | bugfix（提交头误标为 [feature]，实际为缺陷修复；mistag 已核对）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
CAN 反馈座椅加热为一档（`SCU_SeatHeatSwSts=1`）时，连续点击 3D 车模座椅加热按钮两次，车模最终显示"未加热"，预期应显示一档。

## 根因分析
`KanziSignalMapping.java` 处理 `Button.SEAT_HEATING` 点击时，先记下 `previousSeatHeatLevel = mSeatHeatLevel`，把 `mSeatHeatLevel` 更新为下一档，再挂一个 1 秒延迟的反馈校验 `mSeatHeatFeedbackRunnable`。旧校验逻辑 `seatHeat(nextSeatHeatLevel, previousSeatHeatLevel)` 读车辆实际反馈 `CCU_SETSEATHEATSWREQ`，一旦 `actualSeatHeat != nextSeatHeatLevel` 就认定"设置失败"，把 `mSeatHeatLevel` 回滚为**点击前的** `previousSeatHeatLevel` 并向 Kanzi 写 `CarModel.Seat_Heat=0`。缺陷场景正是这个"回滚"误伤：第一次点击已让车进入一档且车辆反馈为 1；第二次点击把期望档位推进到二档，1 秒后读到的实际反馈仍是 1（车辆按一次循环/未生效），`1 != 2` 触发回滚，`mSeatHeatLevel` 被恢复为 0 而不是反映真实的 1——用"请求值≠反馈值"推出"应回到更旧的本地缓存状态"，两步推断都错了。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java（+8/-10）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ Button.SEAT_HEATING 点击处理
-                int previousSeatHeatLevel = mSeatHeatLevel;
                 int nextSeatHeatLevel = getNextSeatHeatLevel(mSeatHeatLevel);
                 mSeatHeatLevel = nextSeatHeatLevel;
                 mSeatHeatFeedbackRunnable = () -> {
-                    seatHeat(nextSeatHeatLevel, previousSeatHeatLevel);
+                    updateSeatHeatFromVehicle(nextSeatHeatLevel);
                     updateSeatHeatToKanzi();
                     mSeatHeatFeedbackRunnable = null;
                 };
@@ 校验逻辑重写
-    private void seatHeat(int nextSeatHeatLevel, int previousSeatHeatLevel) {
+    private void updateSeatHeatFromVehicle(int requestedSeatHeatLevel) {
         int actualSeatHeat = mVehicleService.getIntProperty(CarPropertyIds.CCU_SETSEATHEATSWREQ, 0);
-        if (actualSeatHeat != nextSeatHeatLevel) {
-            LogUtils.d(TAG, "Seat heat timeout rollback, sent " + nextSeatHeatLevel
-                    + " but received " + actualSeatHeat + ", restore to level: " + previousSeatHeatLevel);
-            mSeatHeatLevel = previousSeatHeatLevel;
-        } else {
-            mSeatHeatLevel = actualSeatHeat;
+        LogUtils.d(TAG, "Seat heat feedback: requested=" + requestedSeatHeatLevel
+                + ", actual=" + actualSeatHeat);
+        if (actualSeatHeat != requestedSeatHeatLevel) {
+            LogUtils.d(TAG, "Seat heat feedback differs from request, use vehicle feedback: "
+                    + actualSeatHeat);
         }
+        mSeatHeatLevel = actualSeatHeat;
     }
```

## 为什么能修复
删除了 `previousSeatHeatLevel` 回滚分支，校验逻辑改为无条件以车辆实际反馈 `CCU_SETSEATHEATSWREQ` 更新 `mSeatHeatLevel`：第二次点击后即使反馈（1）与请求（2）不一致，状态也收敛到车辆真实值 1（一档），再由 `updateSeatHeatToKanzi()` 把 `CarModel.Seat_Heat=1` 同步给 3D 车模，显示正确。副作用：车辆反馈异常（如读不到、默认 0）时会直接把 UI 拉回 0，不再有本地兜底——这是"以车为准"策略的固有取舍；对快于 1 秒反馈周期的连续点击仍可能丢一次校验窗口。

## 复盘与经验
- "请求≠反馈就回滚"类校验必须回滚到**反馈值**（事实源），而不是点击前的本地缓存（过期的猜测）；本例回滚值 0 与真实值 1 都对不上。
- 延迟校验（1 秒 postDelayed）窗口内用户继续操作会产生竞态：校验触发时的"世界"已不是发起请求时的"世界"，比对逻辑要基于当前事实而非发起时的快照。
- 本地乐观更新 + 异步确认的模型中，确认分支只做一件事：向事实源对齐。
- 提交头把 bugfix 标成 [feature]，缺陷追踪元数据会失真，提交模板/门禁应校验类型字段。
