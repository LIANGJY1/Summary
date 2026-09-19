# SIR-2860 · 3D 车模座椅记忆：点位置 3 后高亮仍停在位置 2
- **提交**：`3acbee01` | 2026-07-16 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模座椅记忆面板选中位置 2 时点击位置 3，位置 3 未立即高亮，界面仍显示位置 2。

## 根因分析
`KanziSignalMapping` 的点击处理（`SEAT_MEMORY_3` 分支）只做两件事：`sendToVehicle(CCU_SEATPOSITIONMEMORYRECALL, 3)` 下发车端，然后 `postDelayed` 延迟后 `getIntProperty` 读车端实际值回刷 UI。问题在于点击后立即读属性，车端执行记忆召回有延迟，读到的仍是旧值 2，`updateSeatMemoryRecallToKanzi(2)` 把刚该显示的高亮覆盖回位置 2。同时 `CCU_SEATPOSITIONMEMORYRECALL` 的 signalHandler 对任何回调值都无条件回刷 UI，车端迟到的旧值回调同样会覆盖用户刚点击的目标。本质是"乐观 UI 缺失 + 无过滤地接受陈旧反馈值"。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+    private int mPendingSeatMemoryPosition = 0; // 点击座椅记忆后的目标位置，等待车端确认
+
+    private void handleSeatMemoryClick(int targetPosition) {
+        clearSeatMemoryPending();
+        mPendingSeatMemoryPosition = targetPosition;
+        mLastActiveMemoryPosition = targetPosition;
+        updateSeatMemoryRecallToKanzi(targetPosition);   // 先让目标位置立即高亮
+        sendToVehicle(CarPropertyIds.CCU_SEATPOSITIONMEMORYRECALL, targetPosition);
+        mSeatMemoryFeedbackRunnable = () -> {
+            int actualMemory = mVehicleService.getIntProperty(CCU_SEATPOSITIONMEMORYRECALL, 0);
+            if (mPendingSeatMemoryPosition != 0 && actualMemory != mPendingSeatMemoryPosition) {
+                clearSeatMemoryPending();
+                updateSeatMemoryRecallToKanzi(actualMemory); // 超时按真实值校正
+                return;
+            }
+            clearSeatMemoryPending();
+            updateSeatMemoryRecallToKanzi(actualMemory);
+        };
+        mHandler.postDelayed(mSeatMemoryFeedbackRunnable, SEAT_MEMORY_FEEDBACK_DELAY_MS);
+    }
```

```diff
--- signalHandlers.put(CCU_SEATPOSITIONMEMORYRECALL, ...) 回调过滤
+            if (mPendingSeatMemoryPosition != 0 && seatMemory != mPendingSeatMemoryPosition) {
+                LogUtils.d(TAG, "Seat memory callback ignored: actual=" + seatMemory
+                        + ", expecting=" + mPendingSeatMemoryPosition);
+                return;   // 等待确认期间忽略不匹配的旧值回调
+            }
```

## 为什么能修复
三层消除根因：① 点击后先把目标值 `updateSeatMemoryRecallToKanzi(targetPosition)` 同步给 Kanzi，UI 乐观高亮，不再依赖迟到的车端值；② pending 期间 signalHandler 丢弃与目标不符的旧值回调，堵住覆盖路径；③ 超时 runnable 重新读真实车端信号，若下发失败按实际值回退，保证 UI 最终与车况一致。隐患：`SEAT_MEMORY_FEEDBACK_DELAY_MS` 若小于车端实际召回耗时，正常成功的场景也会先走一次"真实值校正"，但读到的仍是旧值会短暂回退显示——好在车端后续回调仍会被接受（pending 已清空）。

## 复盘与经验
- "点击 → 下发 → 立刻读属性回刷 UI"是车机典型反模式：CAN/车端执行有滞后，读到的必是旧值。正确姿势是乐观更新 + pending 窗口 + 超时兜底校正。
- 属性回调不做来源/时序过滤直接刷 UI，会让任何陈旧回调覆盖用户操作；对"写后读"型信号要有期待值比对。
- 三个记忆位按钮的重复 postDelayed 代码先收敛成 `handleSeatMemoryClick(int)` 再修 bug，说明重复代码处修复时优先合并逻辑，否则要改三处、漏一处。
