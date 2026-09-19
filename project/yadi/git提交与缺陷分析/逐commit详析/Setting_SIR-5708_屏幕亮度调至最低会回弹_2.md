# SIR-5708 · 屏幕亮度调至最低会回弹（Setting 侧）

- **提交**：`b20d7c1b` | 2026-08-06 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rc：进度区间有误 / sol：修改进度区间）

## 问题
设置-显示页把亮度档位拖到最低时，亮度被系统纠偏弹回，无法停在最低档（与 SystemUI 侧 `18b4d021` 同单号双修）。

## 根因分析
设置页亮度条 `GearSwitchViewNew`（`gearBright`）的 `gearCount` 配置为 21，UI 档位为 0~20，且 `onGearChangeListener` 里把 UI 档位值**原样**通过 `sendL2A(Constants.ID_BACKLIGHT_BRIGHT, gear)` 下发。而 `ID_BACKLIGHT_BRIGHT` 信号协议合法区间是 1~20（0 为非法值），用户拖到 UI 最低档 0 时下发 0，底层拒绝/纠偏后回调刷新 UI，产生回弹。此外接收侧 `SettingVehicleService` 对 `ID_BACKLIGHT_BRIGHT` 的回调 `screenBrightnessProgress.postValue(value)` 也直接透传信号值，与 UI 的 0 基档位错位 1，显示同样偏差。根因是 UI 档位（0 基）与信号区间（1 基）之间缺少偏移换算。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
-        mBinding.sbScreenLight.gearBright.gearCount = 21
+        mBinding.sbScreenLight.gearBright.gearCount = 20
@@
         mBinding.sbScreenLight.gearBright.onGearChangeListener =
             GearSwitchViewNew.OnGearChangeListener { gear ->
                 settingVehicleService.sendL2A(
-                    Constants.ID_BACKLIGHT_BRIGHT, gear
+                    Constants.ID_BACKLIGHT_BRIGHT, gear + 1
                 )
```
```diff
// application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
                 Constants.ID_BACKLIGHT_BRIGHT -> {
-                    screenBrightnessProgress.postValue(value)
+                    screenBrightnessProgress.postValue(value - 1)
                 }
```

## 为什么能修复
UI 侧收窄为 20 档（0~19），下发时统一 `+1` 映射到信号合法区间 1~20，最低档永远下发 1，不再触发底层纠偏；接收回调对称地 `-1` 还原成 0 基档位供 UI 显示，收发双向换算一致，显示与实际亮度严格对齐。副作用：该换算逻辑分散在发送方（DisplayFragment）与接收方（SettingVehicleService）两处，若后续其它页面直接读 `screenBrightnessProgress` 或再发 `ID_BACKLIGHT_BRIGHT`，必须知道这层 ±1 约定，否则又会错位；建议收敛到 ViewModel/单一映射函数。

## 复盘与经验
- 同一物理量在不同层的基数约定（0 基 UI vs 1 基协议）是最常见的 off-by-one 缺陷源，收发两侧必须成对换算并集中封装。
- 修"回弹"类 bug 的关键线索是：UI 发出的极值是否落在协议/系统合法区间内，先对区间再查动画。
- 同一单号在 SystemUI（`18b4d021`）与 Setting（本提交）各有一份亮度 UI，区间定义没共用常量，双处修改容易漂移，应提取统一区间常量或公共组件。
