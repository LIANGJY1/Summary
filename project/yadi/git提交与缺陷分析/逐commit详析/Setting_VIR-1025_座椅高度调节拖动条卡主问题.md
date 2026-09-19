# VIR-1025 · 座椅高度调节拖动条卡住
- **提交**：`8f528fb8` | 2026-09-17 | sgh | Setting | bugfix
- **缺陷库**：未关联缺陷库记录（ids 为 VIR-1025 验证单，defs 为空）

## 问题
座椅高度调节过程中，拖动条中途"卡住"无法继续拖动，调节手势被打断。

## 根因分析
提交信息给出机制：`[why] 调节座椅高度SCU返回数据把调节状态重置了`。`VehicleControlFragment` 中座椅调节弹窗（`seatAdjustPopup`）展示期间，SCU（座椅控制单元）数据异步返回会触发 `refreshSeatAdjustableState()` 刷新入口按钮状态；该函数原实现无条件执行 `mainSeatAdIv.isEnabled = !heightDisabled`——调节进行中 `heightDisabled` 为 true，入口按钮被 `isEnabled=false` 禁用，调节状态被重置，正在拖动的拖动条失去响应（卡住）。

## 关键代码修改
改动文件：VehicleControlFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
             seatAdjustPopup = null
             currentSeatTouchView = null
             longPressHandoff = false
+            // 弹窗关闭后补刷入口按钮可用状态（调节期间被跳过的禁用判断在此恢复）
+            refreshSeatAdjustableState()
         }
...
         seatAdjustmentBinding?.apply {
             val heightAlpha = if (heightDisabled) 0.3f else 1.0f
             mainSeatAdIv.alpha = heightAlpha
-            mainSeatAdIv.isEnabled = !heightDisabled
             val angleAlpha = if (angleDisabled) 0.3f else 1.0f
             passagerSeatAdIv.alpha = angleAlpha
-            passagerSeatAdIv.isEnabled = !angleDisabled
+            if (!isSeatAdjustPopupActive()) {
+                mainSeatAdIv.isEnabled = !heightDisabled
+                passagerSeatAdIv.isEnabled = !angleDisabled
+            }
         }
     }
 
+    private fun isSeatAdjustPopupActive(): Boolean = seatAdjustPopup?.isShowing == true
```

## 为什么能修复
核心思路是"调节期间冻结禁用写、弹窗关闭后补偿刷新"：`refreshSeatAdjustableState()` 在弹窗存活（`isSeatAdjustPopupActive()`）时只更新 `alpha`（视觉置灰提示）而跳过 `isEnabled` 赋值，SCU 返回数据不再打断进行中的调节手势；弹窗 `dismiss` 回调里补一次 `refreshSeatAdjustableState()`，把调节期间被跳过的禁用判断恢复，保证关闭弹窗后入口按钮状态与车况一致。副作用：调节期间外观 alpha 与 isEnabled 短暂不一致，但因弹窗遮罩下不可点击入口，无实际误操作风险。

## 复盘与经验
- "控件 isEnabled"是交互状态而非纯视觉状态：异步数据刷新 UI 时要区分"视觉反馈（alpha）"与"可用性（isEnabled）"，手势进行中禁用写可用性是防"卡住"类 bug 的通用手法。
- 跳过某次刷新后必须有补偿点（本例在 dismiss 回调补刷），否则冻结逻辑会引入新的状态不同步。
- 拖动条/滑杆"中途卡死"类问题，优先排查拖拽期间是否有其他回调把控件 disable、rebound 重置或布局重刷。
- VIR 单（验证单）也承载真实 bugfix，缺陷库无记录时提交信息中的 what/why/how 是唯一上下文，应完整填写。
