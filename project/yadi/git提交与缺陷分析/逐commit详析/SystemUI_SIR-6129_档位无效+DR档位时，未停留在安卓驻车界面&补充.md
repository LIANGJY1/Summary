# SIR-6129 · 档位无效+D/R档时未停留在安卓驻车界面 & 补充主题变化通知仪表

- **提交**：`3b5b5190` | 2026-08-21 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
整车发送"档位无效"信号但档位值为 D/R 时，上层按 D/R 驱动了界面切换，没有保持在安卓驻车（P 档）界面；缺陷库同时记录第二项：主题变化未通知仪表。

## 根因分析
缺陷库根因："档位无效之前说不用上层应用处理，现在无效情况依然发出档位变化，非正常流程也已补充容错"。`DigitalKeyVehicleService` 原来只订阅 `ENERGY_PCU_ACTUALGEAR` 档位信号并直接驱动 `PageStateMachine.setGear/handleEvent`、`NavBarActor`/`StatusBarActor.updateGear` 等，完全不感知档位有效性；当 PCU 发出无效档位（validity=1）时，错误档位照样把状态机切走，驻车界面丢失。修复新增订阅 `CarPropertyIds.PCU_ACTUALGEARVALID`（0 有效/1 无效），缓存 `currentGearValid`；把档位分发逻辑抽取为 `handleActualGearChanged(gear)`，入口处 `if (!currentGearValid) return` 直接丢弃无效期间的档位变化；从无效恢复有效时用 `getCachedIntProperty(ENERGY_PCU_ACTUALGEAR)` 补读最新档位，避免漏处理无效期间发生的换挡。第二项修复：`SystemUIApplication` UI Mode 变化处新增通过 `SystemSettingsControllerService.setMeterThemeStyle()` 以 `ID_METER_THEME_STYLE`（0 白天/1 黑夜）经 AL 通知仪表。

## 关键代码修改
改动文件：DigitalKeyVehicleService.kt、SystemUIApplication.kt、SystemSettingsControllerService.kt、SysUIConfig.java、CarPropertyMapping.kt（5 文件，+107/-40）
```diff
@@ application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt @@
+    private fun handleActualGearChanged(gear: Int) {
+        if (!currentGearValid) {
+            LogUtils.i(TAG, "PCU_ActualGearFeed: gear=0x${gear.toString(16)} but currentGearValid=false, skip")
+            return
+        }
+        if (currentGear == gear) { ... return }
+        currentGear = gear
+        checkStandby1GearState()
+        ...（原有 Actor/PageStateMachine 分发不变）
+    }
+
+    CarPropertyIds.PCU_ACTUALGEARVALID -> {
+        val switch = (value as? Int) ?: return
+        val wasValid = currentGearValid
+        currentGearValid = (switch == 0)
+        if (!wasValid && currentGearValid) {
+            val latestGear = mCarServiceManager?.getCachedIntProperty(
+                CarPropertyIds.ENERGY_PCU_ACTUALGEAR, defaultValue = currentGear) ?: currentGear
+            if (latestGear != currentGear && latestGear != CarConstants.INVALID) {
+                handleActualGearChanged(latestGear)
+            }
+        }
+    }
```

## 为什么能修复
无效档位不再进入状态机和 UI 分发链，界面稳定停留在驻车态；恢复有效时补读缓存档位保证不丢真实换挡事件，这是"信号过滤 + 恢复补偿"的完整闭环。隐患：若 PCU 长时间停留无效态而真实车辆已在行驶，上层仍按旧档位（P）显示，依赖整车侧保证无效态及时恢复。

## 复盘与经验
- 整车信号的"有效性"位与"值"必须一起消费：协议约定会变（"之前说不用处理，现在又发无效值"），上层加容错是唯一可靠的防御。
- 过滤掉一类信号后必须考虑补偿：恢复时刻补读最新值（缓存优先），否则无效窗口内的事件永久丢失。
- 跨域（HUD/仪表）通知容易被遗漏，主题这类全局状态变化要列出所有订阅方（SystemUI 各 Actor + 仪表）逐一核对。
