# SIR-2084 · 充电功率恢复时 UI 闪 0.0kW 再显示真实值

- **提交**：`80e03e7b` | 2026-07-08 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
停发充电信息信号再恢复后（timeout 恢复），充电功率先显示 0.0kW，约百毫秒后才跳回真实值（如 11.16kW），肉眼可见闪烁。

## 根因分析
timeout 恢复信号（0C11FF04）与功率信号（`ENERGY_EEM_CHARGING_POWER`）是**两个独立的 CAN 帧**，各有发送周期，恢复信号先到、真实功率帧晚约 92ms。`MainActivity.handleChargingPowerDisplayTimeout()` 在恢复分支（`lost == false`）立即调用 `refreshChargingPowerDisplayFromFramework()`，经 `readEnergyEemChargingPowerOnce()` 读取**框架缓存值**——而 timeout 期间 MCU 停发功率数据，缓存里残留 0——于是把 0 推给 UI 显示 0.0kW，直到下一帧真实 CAN 数据经 callback 到达才纠正。根因是"恢复时刻主动读了一次过期缓存"，把信号丢失期的脏值当成了恢复值。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`（+3/-12）
```diff
// --- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
         if (lost) {
             binding.tvMileageDebugChargePowerValue.setText(R.string.mileage_debug_charge_power_value);
         } else {
-            refreshChargingPowerDisplayFromFramework("chargingPowerDisplayTimeoutRecovered");
+            // [bugfix] 恢复时不主动读取功率缓存值，避免读到 0 导致 UI 先闪 0.0kW 再显示真实值。
+            // 真实功率值会随下一帧 CAN 数据通过 callback 自然更新到 UI。
+            LogUtils.d(TAG, "[CHARGING_POWER_DISPLAY_TIMEOUT] recovered, wait for callback to update power");
         }
     }
-    private void refreshChargingPowerDisplayFromFramework(String caller) {
-        ...
-        int power = vehicleService.readEnergyEemChargingPowerOnce(caller);
-        if (power >= 0) {
-            updateChargingPower(power, SOURCE_READ_ONCE_PREFIX + caller);
-        }
-    }
```

## 为什么能修复
恢复时只清除 timeout 标志、不再主动读缓存；UI 保持上一次的 timeout 文案，等下一帧真实功率经常规 callback 通道刷新，0.0kW 的中间态彻底消失。代价是恢复到显示真实功率增加约一个 CAN 帧周期（~92ms）的延迟——对功率数字而言完全可接受。`refreshChargingPowerDisplayFromFramework` 已无调用点，一并删除，避免后人误用。隐患：若恢复后 MCU 长时间不再发功率帧，UI 将停留在占位文案，需依赖信号丢失检测再次兜底。

## 复盘与经验
- **多帧信号有到达时差，恢复/联动动作不要立刻读相关联的缓存**：跨帧组合场景（A 帧触发、B 帧供值）应让数据走自己的周期通道，而非主动"拉"一次脏缓存。
- **"读一次缓存"类 API（readXxxOnce）天然可能读到陈旧值**，用于初始化可接受，用于状态切换的即时刷新就是闪烁制造机。
- 删除无人调用的辅助方法与修 bug 同等重要——留着就是未来的误用点。
