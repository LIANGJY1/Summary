# SIR-2178 · 点击3D车模把手加热和座椅加热无响应
- **提交**：`72c8c592` | 2026-07-09 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
在 3D 车模上点击把手加热、座椅加热按钮，Kanzi 界面无任何响应反馈。

## 根因分析
`KanziSignalMapping` 的按钮点击处理中，原逻辑是：`getNextHandleHeatLevel(mHandleHeatLevel)` 算出下一档后只执行 `sendToVehicle(CCU_SETHANDLEHEATSWREQ, nextLevel)`，本地缓存档位 `mHandleHeatLevel` 与 Kanzi 状态要等 `HANDLEBAR_FEEDBACK_DELAY_MS` 延迟后回读底层 `getIntProperty` 才更新。即"必须拿到底层实际 value 才回给 Kanzi"。若底层回读不及时/丢回调，Kanzi 永远收不到状态更新，表现为点击无响应；即便回读正常，也有可感知的延迟。根因是交互反馈完全依赖底层回执（同步等待），没有本地乐观更新兜底。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java（+62/-3）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ KanziType.Button.HANDLEBAR 点击处理
+                int previousHandleLevel = mHandleHeatLevel;
                 int nextHandleLevel = getNextHandleHeatLevel(mHandleHeatLevel);
+                mHandleHeatLevel = nextHandleLevel;
+                updateHandlebarStateToKanzi();          // 立即把新档位反馈给 Kanzi
                 sendToVehicle(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, nextHandleLevel);
-                mHandler.postDelayed(() -> {
+                if (mHandleHeatFeedbackRunnable != null) {
+                    mHandler.removeCallbacks(mHandleHeatFeedbackRunnable);
+                }
+                mHandleHeatFeedbackRunnable = () -> {
                     int actual = mVehicleService.getIntProperty(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, 0);
-                    mHandleHeatLevel = actual;
+                    if (actual != nextHandleLevel) {
+                        LogUtils.d(TAG, "Handlebar heat timeout rollback, sent " + nextHandleLevel
+                                + " but received " + actual + ", restore to level: " + previousHandleLevel);
+                        mHandleHeatLevel = previousHandleLevel;   // 回执不符则回滚
+                    } else {
+                        mHandleHeatLevel = actual;
+                    }
                     updateHandlebarStateToKanzi();
-                }, HANDLEBAR_FEEDBACK_DELAY_MS);
+                    mHandleHeatFeedbackRunnable = null;
+                };
+                mHandler.postDelayed(mHandleHeatFeedbackRunnable, HANDLEBAR_FEEDBACK_DELAY_MS);
+                break;
+            case KanziType.Button.SEAT_HEATING:   // 座椅加热同构处理 + getNextSeatHeatLevel(0→3→2→1→0)
```

## 为什么能修复
改为"点击后立即用目标档位更新 `mHandleHeatLevel` 并 `updateHandlebarStateToKanzi()`"的乐观更新模式，Kanzi 马上收到 value、界面即时响应；延迟回执降级为校验机制——回读不符则回滚到 `previousHandleLevel` 并再次刷新 Kanzi。`removeCallbacks` 防止连点时旧回执覆盖新状态。座椅加热补齐了同构处理与 `getNextSeatHeatLevel` 循环档位（关→3→2→1→关）。隐患：若底层始终未生效且回执路径也失败，界面短暂显示与实车不一致的档位。

## 复盘与经验
- **交互类控件要乐观更新+回滚**：车控按钮的 UI 反馈不应等待底层回执，先本地翻转再异步校验，是无网络/弱实时总线场景的标准做法。
- **延迟回调用具名 Runnable + removeCallbacks 防竞态**：连点场景下，匿名 postDelayed 无法取消，旧回调会覆盖新状态。
- **回执校验要可回滚**：乐观更新必须保存 `previousLevel`，校验失败时恢复，否则错误状态会固化。
