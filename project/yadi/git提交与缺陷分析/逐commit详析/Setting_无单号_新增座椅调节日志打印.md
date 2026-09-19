# 无单号 · 新增座椅调节日志打印

- **提交**：`022cd1a8` | 2026-09-15 | sgh | Setting | bugfix/优化
- **缺陷库**：未关联单号

## 问题
座椅调节相关功能需要补充可观测性，提交名义为"新增座椅调节日志打印"。

## 根因分析
注意：diff 与提交标题/元数据不符——实际 diff 并没有新增任何日志语句，而是在 `VehicleControlFragment` 的 `onResume()` 中追加了一次 `getCanState()` 调用（`loadSeatPositionNames()` 之后）。也就是说，真正的作用是每次界面回到前台时重新拉取一次 CAN 状态（座椅相关状态），以界面数据刷新代替日志收集。以 diff 实际为准，本提交更接近"座椅调节界面 onResume 时刷新 CAN 状态"的状态同步修复，标题中的"日志打印"未在代码中体现。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ class VehicleControlFragment : BaseFragment<FragmentVehicleControlBinding, BaseViewModel>
     override fun onResume() {
         super.onResume()
         loadSeatPositionNames()
+        getCanState()
     }
```

## 为什么能修复
座椅调节界面此前只在初次创建时读取 CAN 状态，若界面在后台期间车辆状态变化（或上次读取时机过早），回到前台时显示的是旧值。`onResume` 补读 `getCanState()` 保证每次可见时状态与车端一致。副作用极小：仅多一次属性读取；若 `getCanState()` 内部有异步回调刷新 UI，需注意页面已销毁时的回调防护。

## 复盘与经验
- 提交信息与实际 diff 不一致是常见的仓库卫生问题，复盘时应以 diff 为准，避免被标题误导为"纯日志提交"。
- "界面可见即重读一次车况"是车机 Setting 类应用对抗信号丢失/缓存失步的低成本兜底手段，可作为通用模式。
- 此类无单号小改动最好在提交信息中写明真实意图（状态刷新 vs 日志），方便后续追溯。
