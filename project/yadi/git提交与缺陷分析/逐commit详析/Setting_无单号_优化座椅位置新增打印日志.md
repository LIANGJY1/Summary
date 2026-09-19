# 无单号 · 优化座椅位置新增打印日志
- **提交**：`03e7612c` | 2026-09-05 | sgh | Setting | 日志类提交（附一处 UI 描边微调）
- **缺陷库**：未关联单号

## 类型说明
主体是日志增强：`application/Setting/.../ui/fragment/VehicleControlFragment.kt` 在座椅调节相关的四个状态刷新点补充"前置条件全量打印"，用于离线分析座椅按钮置灰/不可用问题：
- `getCanState()`：打印 `antiplayFaulted`、`overheatFaulted`、`motorSelfLearnFaulted`、`motorFaultFaulted`、`cushionMotorSelfLearnFaulted`、`cushionMotorFaultFaulted`、`drivingDisabled`、`seatAdjustmentSts`、`cushionAdjustmentSts`、`seatSelfLearnActive`、`cushionSelfLearnActive` 全部 CAN 前置条件；
- `refreshSeatPositionState()` / `refreshSeatSaveState()` / `refreshSeatEditState()`：分别打印各自参与 `disabled` 计算的布尔条件及最终 `disabled` 值（按钮 alpha 按 `if (disabled) 0.3f else 1.0f` 刷新）。

另有一处夹带的 UI 改动：`component/CommonTools/.../drawable/shape_setting_seat_selected_bg.xml` 座椅选中态描边宽度 8dp→4dp（与日志无关，属顺带微调，与后续 SIR-7514 座椅选中效果系列修改同期）。

## 复盘与经验
- 座椅/车控类"按钮为什么置灰"问题的根因链条长（行车互斥、故障位、自学习进行中等多路 CAN 信号），在 disabled 的汇合点一次性打印全部输入与结论，是定位此类问题的高效模式。
- 日志提交里夹带 drawable 微调会让"日志提交"承担 UI 变更的测试责任，最好拆开提交以保持回滚粒度。
