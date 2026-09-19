# SIR-8097 · 3D 车模座椅位置为空时，点击无 toast 提示
- **提交**：`caab0624` | 2026-09-18 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 待测试验证 · 域 3D车模

## 问题
3D 车模的座椅记忆位置为空时，点击对应座椅记忆按钮没有任何 toast 提示，交互无反馈。

## 根因分析
`Launcher` 的 `KanziSignalMapping.handleSeatMemoryClick(targetPosition)` 原来点击后直接进入记忆召回流程（`clearSeatMemoryPending()` → 设置 `mPendingSeatMemoryPosition` 等），完全没有检查该位置是否已保存过座椅位置，更没有调用 Kanzi 的 toast 接口做提示——缺陷库 rc"没判断座椅为空"、提交 `[why] 没有掉用kanzi接口`。而设置侧 `VehicleControlFragment` 的同类场景已有"座椅为空弹提示"的行为（提交注释也点明"与 Settings VehicleControlFragment 行为一致"），Launcher 侧 3D 车模入口属于漏实现。

## 关键代码修改
改动文件：KanziSignalMapping.java（+28）
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
     private void handleSeatMemoryClick(int targetPosition) {
+        // 座椅位置为空时拦截，通过 Kanzi Toast 提示（与 Settings VehicleControlFragment 行为一致）
+        int absPosition = SeatUserManager.INSTANCE.getSavePosition(targetPosition);
+        if (absPosition == -1 || !SeatUserManager.INSTANCE.isSeatPositionSaved(absPosition)) {
+            LogUtils.d(TAG, "Seat memory" + targetPosition + " not saved, show Kanzi toast");
+            showKanziToast(SEAT_EMPTY_TOAST_VALUE);
+            return;
+        }
+
         clearSeatMemoryPending();
         mPendingSeatMemoryPosition = targetPosition;
...
+    private void showKanziToast(int toastValue) {
+        if (mKanziToastDismissRunnable != null) {
+            mHandler.removeCallbacks(mKanziToastDismissRunnable);
+        }
+        kanziManager.setValue("", KanziType.CarModel.TOAST_STATE, toastValue);
+        mKanziToastDismissRunnable = () -> {
+            kanziManager.setValue("", KanziType.CarModel.TOAST_STATE, 0);
+            mKanziToastDismissRunnable = null;
+        };
+        mHandler.postDelayed(mKanziToastDismissRunnable, KANZI_TOAST_DISMISS_DELAY_MS);
+    }
```

## 为什么能修复
点击入口先经 `SeatUserManager.getSavePosition`/`isSeatPositionSaved` 校验：位置未保存（`-1` 或未落存）时 `return` 拦截召回流程，改走 `showKanziToast(3)`——向 Kanzi（3D 车模渲染端）写入 `TOAST_STATE=3`（座椅位置为空）触发提示，3 秒后由 `mKanziToastDismissRunnable` 回写 0 复位；重复触发时先 `removeCallbacks` 防止旧关闭任务提前清掉新 toast。空位点击从"无响应"变为"提示且不执行召回"，与设置页行为对齐。副作用：toast 显示/关闭靠 UI 侧定时器驱动，若 3 秒内进程被杀，Kanzi 端 TOAST_STATE 可能停留在 3，依赖下次写入复位。

## 复盘与经验
- 同一业务动作在多个入口（设置页、3D 车模）暴露时，前置校验与提示行为必须在两端一致，最好抽公共校验；对齐已有实现（注释中显式引用 Settings 行为）是低成本补齐方式。
- Kanzi 这类跨进程/跨引擎 UI 的"状态值 + 定时复位"模式，要处理好重复触发（先取消旧定时器）与复位兜底。
- `isSeatPositionSaved` 的判空条件要覆盖 `getSavePosition` 返回哨兵值 `-1` 的情况，单一判断容易漏。
