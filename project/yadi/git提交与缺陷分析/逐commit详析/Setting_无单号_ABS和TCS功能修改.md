# 无单号 [SRS_VehSetting_003] ABS和TCS功能修改
- **提交**：`11d164b8` | 2026-08-13 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_003

## 需求/目标
行车安全页（DrivingSafetyFragment）ABS/TCS 控件从"CAN 连接状态单条件置灰"升级为"CAN 状态 + 车端模式切换权限"双条件置灰（无权限时显示副标题说明）；新增坡道驻车/陡坡缓降"工作状态"信号播报（激活/退出 toast）；并把置灰逻辑抽成全局扩展函数。

## 实现结构
- `extension/ViewExtension.kt`：新增 `View.setGrayState(enabled)` 扩展，按控件类型（SkinSwitchCardView / ImageTextRadioGroup）分发 alpha+overlay/enable 处理。
- `SettingVehicleService.kt`：新增 4 个 LiveData：`mtcModeChangePermission`/`absModeChangePermission`（模式切换权限）、`pcuHillHoldSts`/`pcuDownhillDescentSts`（工作状态），接入订阅表。
- `DrivingSafetyFragment.kt`：状态位从 2 个拆为 4 个（TCS/ABS 权限独立 + 工作状态缓存）；`updateAbsTcsEnabledState(enabled, permissionEnabled, view, toast)` 统一"置灰 + 副标题"；`handleWorkStateToast` 用前值缓存做边沿检测触发激活/退出 toast。
- `DrivingFragment.kt`：改用 `setGrayState`，删除本地 `updateControlEnabledState`；同时修正湿滑/极致续航开关的失败 toast 条件（`isStateMatched` → `!isStateMatched`）。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingSafetyFragment.kt
+    private fun updateAbsTcsEnabledState(
+        enabled: Boolean,
+        permissionEnabled: Boolean,
+        view: ImageTextRadioGroup,
+        toast: String
+    ) {
+        view.setGrayState(enabled)
+        if (permissionEnabled) {
+            view.setSubTitle("")
+        } else {
+            view.setSubTitle(toast)
+        }
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingSafetyFragment.kt
+    private fun handleWorkStateToast(
+        state: Int?,
+        previousSts: Int,
+        activeToastResId: Int,
+        inactiveToastResId: Int,
+        updatePrevious: (Int) -> Unit
+    ) {
+        if (state == null) return
+        updatePrevious(state)
+        // 状态无变化则不toast
+        if (previousSts == INVALID_STATE_VALUE || previousSts == state) { return }
+        // 0→1 激活，1→0 退出
+        when (state) {
+            1 -> showToast(getString(activeToastResId))
+            0 -> showToast(getString(inactiveToastResId))
+        }
+    }
```
权限信号 `!= PERMISSION_DISABLED(0)` 为可用；每个observe 回调都基于"CAN 状态 && 权限"两个成员变量重算，保证任一信号到达都能得到一致的最终态。`handleWorkStateToast` 是标准的边沿检测（previous + INVALID 哨兵过滤首帧）。

## 复盘与要点
- 双条件置灰（通信可用性 × 业务权限）是车控设置的通用模型，`updateAbsTcsEnabledState` 把"置灰"与"原因提示"合并处理，可推广。
- 边沿检测播报工作状态比"只显示当前值"体验更好，前一帧哨兵值过滤首帧回显的做法值得复用。
- 本提交顺手修复了 e2ee6731 引入的失败 toast 条件写反问题（`isStateMatched`→`!isStateMatched`），说明该 pending 比对模板刚落地时就埋了逻辑反转 bug，复制模板代码需逐分支核对。
