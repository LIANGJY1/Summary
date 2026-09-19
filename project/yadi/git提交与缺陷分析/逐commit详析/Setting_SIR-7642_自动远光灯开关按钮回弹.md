# SIR-7642 · 自动远光灯开关按钮异常回弹
- **提交**：`02687c2b` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：按钮回弹逻辑问题）

## 问题
开启自动远光灯（IHC_SWITCH）弹窗确认后，开关置 ON，但车辆 CAN 回显成功后按钮仍被回弹到 OFF，即"按钮回弹"。

## 根因分析
`LightFragment` 有"二次弹窗确认后 1 秒未回显则回弹 OFF"的防呆逻辑。原实现 `startReboundForSwitch(view)` 用本地 `reboundJobs` map 管理 Job；而回显成功的 `updateAutoHighBeamUI(state)` 里只调用了 `SwitchHelper.cancelRebound(ssvAutoHighBeam.switchCompat)`——取消的是另一个 Helper，`reboundJobs` 里挂起的回弹任务从未被取消，超时后无条件执行 `view.isChecked = false`，把已确认成功的开关弹回，形成必现回弹。两个回弹机制并存、取消路径只覆盖其一，是逻辑缺陷核心。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
@@ -325,7 +326,10 @@
                         autoHighBeamStateTemp = true
                         mBinding.ssvAutoHighBeam.isChecked = true
                         settingVehicleService.sendL2A(CarPropertyIds.IHC_SWITCH, CanSignalConstants.SWITCH_ON)
-                        startReboundForSwitch(mBinding.ssvAutoHighBeam.switchCompat)
+                        ReboundHelper.start(mBinding.ssvAutoHighBeam, viewLifecycleOwner.lifecycleScope, 1000) {
+                            mBinding.ssvAutoHighBeam.isChecked = false
+                            mBinding.ssvAutoHighBeam.enableOverlay()
+                        }
```
```diff
@@ -396,6 +400,7 @@
     fun updateAutoHighBeamUI(state: Boolean) {
         mBinding.apply {
             if (autoHighBeamStateTemp == state) {
+                ReboundHelper.cancel(mBinding.ssvAutoHighBeam)
                 SwitchHelper.cancelRebound(ssvAutoHighBeam.switchCompat)
             }
             ssvAutoHighBeam.isChecked = state
```

## 为什么能修复
回弹任务改由通用 `ReboundHelper`（CommonTools 单例，内部以 key→Job map 管理、start 先 cancel 旧任务）承载，key 用 `mBinding.ssvAutoHighBeam` 本身；CAN 回显与临时状态一致时 `updateAutoHighBeamUI` 先 `ReboundHelper.cancel(...)` 再刷新 UI，回弹任务被真正取消，开关保持 ON。修复后"超时未回显才回弹、回显成功即取消"语义闭环。隐患：`autoHighBeamStateTemp == state` 的比较若回显值与临时值不一致（车辆拒绝），回弹照常执行，符合预期；但 key 传 View 对象依赖 binding 不重建，Fragment 视图重建后旧 Job 残留需靠 lifecycleScope 自动取消兜底。

## 复盘与经验
- "超时回弹 + 回显取消"必须用同一套任务管理；两套并存的 cancel 只覆盖其一，是这类必现回弹的直接根因。
- 通用超时防呆逻辑（如 ReboundHelper：key 化 Job 管理、start 即取消旧任务）值得沉淀为公共组件，各开关界面复用，避免每处自造 reboundJobs map。
- 车控开关的状态权威来自 CAN 回显，UI 临时态（autoHighBeamStateTemp）只用于比对，任何定时任务在回显到达后都应被取消。
