# SIR-8559 · 进入里程设置界面后打开 APPList，返回时直接落到能量中心首页

- **提交**：`e9a924fa` | 2026-09-18 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 能量中心

## 问题
从能量中心进入里程设置页（MileageManagementActivity）后，点击两次打开 APPList，关闭 APPList 返回时不是回到里程设置页，而是回到了能量中心首页。

## 根因分析
`MileageManagementActivity.onPause()` 中无条件调用了 `finishWhenLeavingForeground()`，该方法在非 `isFinishing`/`isChangingConfigurations` 时直接 `finish()` 自身。打开 APPList 属于"临时离开前台"，同样会触发 `onPause()`，于是里程设置页在后台被悄悄销毁；用户关闭 APPList 返回任务栈时，栈顶已经是能量中心首页，表现为"点两次 APPList 就回首页"。提交信息 `[why]onpause的时候也finish掉了` 与代码完全吻合。此前代码里还有 `mExitingByLapseDown` 标志位，专门防止"下滑手势退出"场景被 onPause 重复 finish——这个补丁标志本身就是 onPause 乱 finish 的设计缺陷信号。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt（+1/-15）
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
@@ onResume 之后 / onPause
         super.onPause()
         dismissResetConfirmationDialog("onPause")
         onActivityPaused()
-        if (mExitingByLapseDown) {
-            LogUtils.d(TAG, "[GrabberExit] onPause: skip finishWhenLeavingForeground")
-            return
-        }
-        finishWhenLeavingForeground()
+        // bugfix[8559]打开 APPList 等临时离开前台的场景保留里程页，关闭 APPList 后可返回原页面。
@@ onLapseDownExitComplete
         LogUtils.d(TAG, "[GrabberExit] exit from secondary page on exit complete")
-        mExitingByLapseDown = true
         exitEnergyManagementApp()
     }
-
-    private fun finishWhenLeavingForeground() {
-        if (isFinishing || isChangingConfigurations) {
-            return
-        }
-        LogUtils.d(TAG, "finishWhenLeavingForeground")
-        finish()
-    }
```

## 为什么能修复
真正的退出路径并没有被删：下滑手势退出仍走 `onLapseDownExitComplete() → exitEnergyManagementApp()`，系统返回键/首页逻辑也各自独立。删掉 onPause 里的兜底 finish 后，APPList 这类"被覆盖"场景不再销毁 Activity，返回时自然停留在里程设置页；连带的 `mExitingByLapseDown` 标志也一并删除，逻辑简化。潜在隐患是页面存活时间变长、后台刷新 runnable 可能继续跑，但刷新逻辑本身由 `onActivityPaused()` 控制，风险可控。

## 复盘与经验
- 不要用 `onPause()` 做"离开即销毁"的生命周期决策：onPause 只代表失去前台焦点，被弹窗、悬浮列表覆盖时也会触发，用它 finish 页面必然误伤。
- 当一个补丁标志（如 `mExitingByLapseDown`）需要在某条路径上"豁免"另一条路径的副作用时，说明副作用本身放错了位置，应当消除副作用而不是继续加豁免条件。
- 车机多窗口/浮窗环境（APPList、OSD、HUD）下，"离开前台"的语义比手机端复杂，页面退出应收敛到唯一的显式退出入口（本例的 `exitEnergyManagementApp()`）。
