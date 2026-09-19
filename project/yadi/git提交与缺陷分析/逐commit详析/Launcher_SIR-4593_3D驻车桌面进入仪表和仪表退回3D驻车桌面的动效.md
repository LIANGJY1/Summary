# SIR-4593 · 3D 驻车桌面进出仪表动效错误
- **提交**：`2f727b27` | 2026-07-30 | liang-jy | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
3D 驻车桌面进入仪表、仪表退回 3D 驻车桌面的切换动效播放错误：D 档时车模切换动画来不及渲染就被冻结。

## 根因分析
`KanziDataSourceManager.checkAndPerformRenderState()` 的仲裁逻辑是"后台或 D/R 档即调 `setRenderStop()` 暂停 Kanzi 车模渲染"。挂 D 档信号一到达（此时 Launcher 仍在前台），立即 `setRenderStop()`，而此时车模从驻车姿态切到行驶姿态的动效还没渲染完，画面被冻结在动效中间态，表现为进出仪表动效错误。缺陷库根因与此吻合："1.安卓侧过早暂停渲染 2.meter 新版动效未开发完成"，本提交解决第 1 条。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java`
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
+    private static final long DELAY_D_GEAR_STOP_MS = 600;// 如果在前台且档位是 D档(1)，延迟 600ms 调用停止渲染接口。切换至D档，车模有相关动效需要渲染。
+    private final Runnable mRenderStopRunnable = this::setRenderStop;
     private void checkAndPerformRenderState() {
+        ThreadUtils.getMainHandler().removeCallbacks(mRenderStopRunnable);
         // 1. 如果在后台，或者档位是 D档(1) / R档(2)，则停止渲染
         if (!mIsForeground || mCurrentGear == 1 || mCurrentGear == 2) {
-            setRenderStop();
+            if (mIsForeground && mCurrentGear == 1) {
+                ThreadUtils.getMainHandler().postDelayed(mRenderStopRunnable, DELAY_D_GEAR_STOP_MS);
+            } else {
+                setRenderStop();
+            }
         } else if (mCurrentGear == 0) { // 2. 如果在前台，且档位是 P档(0)，则恢复渲染
             setRenderStart();
         }
```

## 为什么能修复
"前台 + D 档"场景下停止渲染延后 600ms（`DELAY_D_GEAR_STOP_MS`），给车模切换动效留出渲染窗口；后台或 R 档仍立即停止，省电策略不变。入口处先 `removeCallbacks` 再决定，避免状态快速抖动（D→P→D）时旧的延迟停止任务在新一轮恢复渲染之后又把渲染停掉。隐患：600ms 是拍脑袋的经验值，动效时长变化（缺陷库第 2 条 meter 新版动效）或低端机上掉帧时仍可能截断；用 Handler 延迟而非监听动效完成回调，本质仍是时序补偿而非事件驱动。

## 复盘经验
- 状态驱动的启停仲裁要区分"立即生效"与"允许宽限"的状态组合：UI 动效类收尾应等事件完成回调，而不是固定延迟猜时长。
- Handler.postDelayed 与状态重入必须配对 removeCallbacks，否则延迟任务会穿越新状态造成"迟到的话把事办砸"。
- 渲染暂停/恢复这类跨进程（Kanzi/仪表）时序问题，联调阶段就要用信号时间戳对齐，别等 UI 走查才暴露。
