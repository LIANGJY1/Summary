# SIR-1656 · 3D车模座椅记忆 2 重命名却改了记忆 1 的名字

- **提交**：`1efa1a63` | 2026-07-03 | liqingqing | EnergyManagement(Launcher/3D车模) | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模座椅控制面板中选中"座椅记忆 2"后做重命名，实际被改的是座椅记忆 1 的名字。

## 根因分析
`KanziSignalMapping.java` 中重命名按钮事件 `KanziType.Button.SEAT_NAME_1` 的处理写死了 `onSeatRename(1)`——无论面板当前高亮的是哪个记忆位，重命名永远作用于 seat1。而座椅记忆的选中态是动态的：用户点击 `SEAT_MEMORY_1/2/3` 按钮、或车辆通过 `CCU_SEATPOSITIONMEMORYRECALL` 信号反馈，都可能让当前激活位变为 2/3。**"UI 呈现的选中位"与"重命名操作的目标位"是两份状态，前者在 kanzi 侧，后者在 Java 侧从未记录**，两份状态脱节导致错位。提交消息自述"kanzi 命名接口一直传的 seat1"。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`（+6/-2）；`application/Launcher/libs/kanzi-release.aar`（二进制更新，294MB→343MB）
```diff
// --- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+    private int mLastActiveMemoryPosition = 1;  // 最后激活的座椅记忆位置（1/2/3），用于重命名按钮判断
             case KanziType.Button.SEAT_MEMORY_1:
+                mLastActiveMemoryPosition = 1;
                 sendToVehicle(CarPropertyIds.CCU_SEATPOSITIONMEMORYRECALL, 1);
                 break;
             case KanziType.Button.SEAT_MEMORY_2:
+                mLastActiveMemoryPosition = 2;
                 ...
             case KanziType.Button.SEAT_MEMORY_3:
+                mLastActiveMemoryPosition = 3;
                 ...
             case KanziType.Button.SEAT_NAME_1:
                 if (intValue == 1) {
-                    LogUtils.d(TAG, "Seat name1 clicked");
-                    onSeatRename(1);
+                    LogUtils.d(TAG, "Seat rename clicked, active position=" + mLastActiveMemoryPosition);
+                    onSeatRename(mLastActiveMemoryPosition);
                 }
```

## 为什么能修复
本地新增 `mLastActiveMemoryPosition` 记录最近点击的记忆位，重命名不再写死 1 而是取该变量，使重命名目标跟随用户点击的位置。缺陷库 rc 进一步指出选中态还可能来自车辆信号反馈 `CCU_SEATPOSITIONMEMORYRECALL`（`updateSeatMemoryRecallToKanzi()`），并描述了在反馈同步中回写 `mLastActiveMemoryPosition` 的方案——**该描述与本次 diff 不完全一致**：本提交只做了"点击时本地记忆"，信号反馈路径的同步（当前代码 `updateSeatMemoryRecallToKanzi()` 中 `mLastActiveMemoryPosition = blueLineState`）是后续提交完善的结果，以 diff 实际内容为准。残留隐患：若选中态仅由信号反馈改变而用户未点击，本提交版本下重命名仍会指向旧位置。

## 复盘与经验
- **双端状态必须单一来源**：选中态由 kanzi/车辆信号驱动、操作目标由 Java 侧决定，两份状态没有同步通道是错位的根源；任何"UI 显示 A、操作作用于 B"类 bug 都应优先排查状态同步点。
- **写死的默认值是最常见的隐性 bug**：`onSeatRename(1)` 在单记忆位时代可能正确，扩展到 3 个记忆位后没有随之泛化。
- 缺陷库 rc 记录的是问题的最终态方案，与单个 fix 提交的 diff 可能存在演化差差，复盘时应以 diff 为准、rc 为辅。
