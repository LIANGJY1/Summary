# SIR-7420 · 能量中心里程设置界面下滑，还未松手应用已退出
- **提交**：`f4e51ced` | 2026-09-03 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
在能量中心的里程管理页（二级页）使用下滑手势退出时，手指刚拖动超过阈值、还未松手，整个能量中心就已经退到后台。

## 根因分析
`MileageManagementActivity` 为下滑退出手势实现了两个回调：`OnLapseDownExitStartListener.onLapseDownExitStart()` 与 `OnLapseDownExitListener.onLapseDownExit()`。问题在于它把真正的退出动作放在了 `onLapseDownExitStart()` 里——该回调在拖动刚越过 touchSlop 时触发（手指仍在屏幕上），此处直接 `moveTaskToBack(true)`，导致"未松手即退出"。为了防重复还引入了 `mExitingByLapseDown` 标志在 `onLapseDownExit()` 里吞掉后续回调，逻辑越打越复杂。而主页面 `MainActivity` 的正确行为是在松手后（`onLapseDownExit()`）调用 `exitEnergyManagementApp()`（`BaseActivity` 中封装的 `moveTaskToBack(true)` + `finishAffinity()`），二级页与主页行为不一致。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt`

```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
-class MileageManagementActivity : BaseActivity(), EnergyLapseTouchLayout.OnLapseDownExitListener,
-    EnergyLapseTouchLayout.OnLapseDownExitStartListener {
+class MileageManagementActivity : BaseActivity(), EnergyLapseTouchLayout.OnLapseDownExitListener {
```

```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
     override fun onLapseDownExit() {
-        if (mExitingByLapseDown) {
-            LogUtils.d(TAG, "[GrabberExit] onExitComplete ignored after exit-start back")
-            return
-        }
-        LogUtils.d(TAG, "[GrabberExit] move task to back from secondary page on exit complete")
-        mExitingByLapseDown = true
-        moveTaskToBack(true)
-    }
-
-    override fun onLapseDownExitStart() {
-        LogUtils.d(TAG, "[GrabberExit] move task to back from secondary page on exit start")
+        // [bugfix] 只在松手且退出动画完成后退出（与 MainActivity 一致）。
+        // 之前在 onLapseDownExitStart()（拖动刚超过 touchSlop、手指未松开）就 moveTaskToBack，
+        // 导致"还未松手能量中心已退出"。
         mExitingByLapseDown = true
-        moveTaskToBack(true)
+        exitEnergyManagementApp()
     }
```

## 为什么能修复
把退出时机从"拖动越过阈值"（`onLapseDownExitStart`）挪到"松手且动画完成"（`onLapseDownExit`），并复用 `BaseActivity.exitEnergyManagementApp()`，与 `MainActivity` 的退出路径完全一致，手指未松开不再触发任务回退。同时删掉 `OnLapseDownExitStartListener` 实现和防重入分支，逻辑简化。隐患：`exitEnergyManagementApp()` 含 `finishAffinity()`，二级页退出时整个任务栈所有 Activity 均被销毁，下次进入需重建——但这正是主页面的既有语义，属于刻意对齐。

## 复盘与经验
- 手势退出类交互要严格区分"开始/进行/松手确认"三个回调的语义：视觉反馈放过程回调，真正的退出/提交动作只放松手回调，否则会出现"还没决定就已经执行"的诡异体验。
- 二级页与主页的同类手势行为应收敛到同一基类方法（本例 `exitEnergyManagementApp()`），复制粘贴一份略有差异的退出逻辑是这类 bug 的直接来源。
- 当发现需要 `mExitingByLapseDown` 之类的防重入标志时，往往是回调时机本身选错了，应先审视回调语义而不是继续堆标志位。
