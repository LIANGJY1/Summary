# 无单号 · [SRS_DRVSetting_006] pp0 新需求：新增边撑感应和坐垫感应开关
- **提交**：`c52adf23` | 2026-09-18 | sgh | Setting | feature
- **关联单**：无（SRS_DRVSetting_006）

## 需求/目标
驾驶设置页新增两个车辆感应开关："边撑感应"（监测边撑是否收起）与"座垫感应"（监测座垫是否有人），开关状态通过 CAN 车控属性下发/上报，含中英双语文案。

## 实现结构
8 个文件、+145/-1，端到端打通一条信号链：
- **Carlib**：`CarPropertyIds.kt` 新增 `CCU_KICKSTANDVALID=5232`、`CCU_SEATSWITCHVALID=5233`；`CarPropertyMapping.kt` 补映射；`frameworkLibs/libs/android.car.jar` 二进制更新。
- **SettingVehicleService.kt**（+6）：两个新属性 ID 加入订阅清单，并注册回调把车端反馈写入 `kickstandSenseSwitchSetting` / `seatSenseSwitchSetting` 两个 LiveData。
- **DrivingFragment.kt**（+75）：照搬页面既有的"乐观开关"模式——点击时 `sendVehicleProperty` 下发并记 `pendingState`；订阅 LiveData 收到车端反馈后，与 pendingState 一致则 `SwitchHelper.cancelRebound`（取消回弹动画，确认成功），否则回滚为车端真实值并清 pending。
- **UI/文案**：`fragment_driving.xml` 加两个 `SkinSwitchCardView`；`values/strings.xml` 与 `values-en/strings.xml` 各加 8 条（标题/副标题/开失败/关失败）。

数据流：用户拨开关 → `sendVehicleProperty(CCU_*, SWITCH_ON/OFF)` 下发 CAN → MCU 执行 → 车端状态经 `SettingVehicleService` 订阅链回调 → LiveData → Fragment 观察者刷新 UI 并对账 pendingState。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
// 边撑感应
setupSwitchListener(ssvKickstandSense.switchCompat, { true }) { isChecked ->
    val state = if (isChecked) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF
    logClick("[Command Send] Set kickstand sense: $state")
    settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_KICKSTANDVALID, state)
    kickstandSensePendingState = isChecked
}
```

```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
fun updateKickstandSenseUI(state: Boolean) {
    mBinding.apply {
        if (state == kickstandSensePendingState) {
            SwitchHelper.cancelRebound(ssvKickstandSense.switchCompat)
        } else {
//            when (kickstandSensePendingState) {  // 失败 toast 暂被注释
//                true -> showToast(getString(R.string.kickstand_sense_switch_open_fail_tip))
//                ...
//            }
        }
        ssvKickstandSense.isChecked = state
    }
    kickstandSensePendingState = null
}
```

实现讲解：典型的车控开关"乐观更新 + 车端对账"闭环——UI 先行翻转，真实状态以 CAN 回报为准，pendingState 只在一次对账后清空，天然幂等防重复弹错。两个开关从信号 ID、服务订阅、Fragment 监听、UI 到双语文案逐层照抄既有"极致续航/湿滑模式"的模板，是高度模式化的组入式开发。

## 复盘与要点
- **模板化组入是 Setting 模块的基本功**：新开关 = 信号 ID + 订阅回调 + pending 对账 + 卡片布局 + 四条文案，五处齐全即可；本次照抄既有模式，审查成本低、行为可预期。
- **失败 toast 被注释是半成品信号**：`*_fail_tip` 文案与 pending 回滚逻辑都写好了，但对账不一致时的用户提示被注释掉——失败时静默回滚，用户不知道操作没生效；上线前需恢复。
- **信号 ID 连号注册（5232/5233）**：依赖与 MCU 侧 DBC 约定一致，jar 包同步更新说明底层 car API 也有配套，跨仓联动的提交要注意 CI 构建顺序。
