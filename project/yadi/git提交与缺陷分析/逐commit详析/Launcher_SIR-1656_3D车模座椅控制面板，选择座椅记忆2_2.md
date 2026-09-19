# SIR-1656 · 选中座椅记忆2重命名却改了座椅记忆1的名字
- **提交**：`5c8c36f1` | 2026-07-10 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模座椅控制面板中，选中"座椅记忆 2"后执行重命名，实际被修改的是"座椅记忆 1"的名称。

## 根因分析
重命名操作的目标位置取自 `KanziSignalMapping` 的成员 `mLastActiveMemoryPosition`。该字段只在用户点击座椅记忆按钮时更新；而界面上记忆选中态（高亮蓝线）还可能来自车辆信号反馈 `CCU_SEATPOSITIONMEMORYRECALL` → `updateSeatMemoryRecallToKanzi(seatMemory)`。当车辆侧召回记忆 2、界面已高亮记忆 2 时，`mLastActiveMemoryPosition` 仍停留在默认值 1，重命名便写入了 `cseat_position_name_1`。根因是"选中态"有两个来源（本地点击 / 车辆信号回灌），但派生字段只跟随其中一个来源更新，双源失步。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java（+5/-1）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ updateSeatMemoryRecallToKanzi
     private void updateSeatMemoryRecallToKanzi(int seatMemory) {
         int blueLineState = (seatMemory >= 1 && seatMemory <= 3) ? seatMemory : 0;
+        if (blueLineState != 0) {
+            mLastActiveMemoryPosition = blueLineState;
+        }
         LogUtils.d(TAG, "Seat memory recall -> Kanzi: Seat_Memory=" + seatMemory
-                + ", Seat_BlueLine_State=" + blueLineState);
+                + ", Seat_BlueLine_State=" + blueLineState
+                + ", active position=" + mLastActiveMemoryPosition);
         kanziManager.setValue("", KanziType.CarModel.SEAT_MEMORY, seatMemory);
         kanziManager.setValue("", KanziType.CarModel.SEAT_BLUE_LINE_STATE, blueLineState);
     }
```

## 为什么能修复
车辆信号回灌路径（反馈值 1/2/3 有效时）现在同步刷新 `mLastActiveMemoryPosition`，使"重命名目标"与"界面高亮选中态"始终一致；反馈值无效（0）时保持原值不清空，避免误把选中态抹掉。同时日志补充 active position 便于回归。隐患很小：若业务上"信号高亮"不应等同于"用户选中"，此同步会把二者强绑定，需产品语义确认。

## 复盘与经验
- **派生状态必须跟随所有写入源**：`mLastActiveMemoryPosition` 这类从选中态派生的字段，每个能改变选中态的入口（点击/信号/恢复默认）都要同步维护，漏一处即失步。
- **"界面显示"与"操作目标"要同源**：重命名用 `mLastActiveMemoryPosition` 而高亮用 `SEAT_BLUE_LINE_STATE`，两个真相源必然漂移；理想做法是单一选中态存储，其余全部派生。
- **回灌信号是状态失步高发区**：车辆 CAN 信号会绕过本地交互直接改 UI，review 时要专门排查"信号路径是否也更新了所有关联缓存"。
