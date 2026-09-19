# SIR-7500 · 湿滑模式开关开启时，驾驶模式仍可切换且未置灰
- **提交**：`6f2345ad` | 2026-09-05 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
湿滑（Slip）模式开关开启时，驾驶模式单选组没有置灰，用户仍可切换驾驶模式，违反"湿滑模式优先、禁切驾驶模式"的产品规则。

## 根因分析
`DrivingFragment.kt` 观察 CAN 状态并计算置灰条件：`isDrivingModeEnabled` 默认随 `CAN_STATUS_ENABLED` 置真，随后有一段互斥逻辑——湿滑模式开时强制 `isDrivingModeEnabled = false`。但这段互斥的判定写错了常量：`settingVehicleService.slipModeSwitchSetting.value == CanSignalConstants.SWITCH_CMD_ON`。查 `component/Carlib/.../CanSignalConstants.kt` 可知两套编码并存：`SWITCH_ON = 1 / SWITCH_OFF = 0` 是**接收状态值**；`SWITCH_CMD_ON = 2 / SWITCH_CMD_OFF = 1` 是**新协议 CCU 设置类信号的下发命令值**（注释明确"下发命令开关值"）。`slipModeSwitchSetting.value` 是回读的状态量，取值为 1；拿它和命令值 2 比较永远不成立，互斥分支形同虚设，驾驶模式永不置灰。修复把比较常量改为 `SWITCH_ON`，并补一条 `log("isDrivingModeEnabled: $isDrivingModeEnabled")` 便于后续验证。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
-                if (isDrivingModeEnabled && settingVehicleService.slipModeSwitchSetting.value == CanSignalConstants.SWITCH_CMD_ON) {
+                if (isDrivingModeEnabled && settingVehicleService.slipModeSwitchSetting.value == CanSignalConstants.SWITCH_ON) {
                     isDrivingModeEnabled = false
                 }
+                log("isDrivingModeEnabled: $isDrivingModeEnabled")
                 mBinding.vcNestedScrollView.runWithScrollRestore {
                     mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
```

## 为什么能修复
状态值（1）对状态值（1）比较后，湿滑模式开启时互斥分支真正生效：`isDrivingModeEnabled=false` 传给 `rgDriveMode.setGrayState(...)`，驾驶模式单选组置灰且不可切换。改动仅一个常量，无副作用；风险在于若车端某协议版本回读值确实用命令编码（2），需要按协议文档确认——此类"双编码并存"信号必须以收发方向区分。

## 复盘与经验
- CAN/车控信号常同时存在"状态编码"和"命令编码"两套取值（本项目 0/1 vs 0/1/2），常量名含 `CMD` 的是下发命令值，回读状态比较误用是高频事故点，代码评审应把"比较方向 + 常量语义"作为固定检查项。
- 这类错误表现为"条件永远为假/真"，功能静默失效而非崩溃，非常规测试只能靠 UI 走查发现；给关键互斥结果（如 `isDrivingModeEnabled`）加日志能显著缩短定位链路。
- 置灰互斥逻辑建议收敛为单一函数（如 `computeDriveModeEnabled()`）并配单元测试，避免散落在各 observer 里各写各的常量。
