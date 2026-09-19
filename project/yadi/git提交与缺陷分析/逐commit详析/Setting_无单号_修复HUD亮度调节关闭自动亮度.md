# 无单号 · 修复 HUD 亮度调节关闭自动亮度（实为缺陷修复，标题误标 feature）

- **提交**：`b4a02bf5` | 2026-06-30 | sgh | Setting | feature（实为 bugfix）
- **关联单**：无

> 定性说明：标题含"修复"，diff 是行为缺陷修复 + 死代码清理，按 bugfix 模板撰写。

## 问题
用户拖动/点击 HUD 亮度档位条时，若"自动亮度"处于开启状态，不会按需求关闭自动亮度；只有拖动过程（onDragStarted）触发，点击轨道跳档、点击滑块跳档两条路径都不生效。

## 根因分析
关闭自动亮度的逻辑原先挂在通用的拖拽监听链路上（外层 `setOnGearChangedListener` 内部在特定分支里发 `HUD_AUTO_BRIGHT_SWITCH=0`），该链路只在"拖动开始"路径执行；`GearSwitchView2` 没有独立的"档位变化开始"回调，点击轨道/滑块直接 `setCurrentGear(gear)` 跳档，绕过了拖动开始事件，导致自动亮度不会被关闭。

## 关键代码修改
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchView2.kt
+    // 档位变化开始回调接口
+    fun interface OnGearChangeStartListener {
+        fun onGearChangeStart()
+    }
...
+    var onGearChangeStartListener: OnGearChangeStartListener? = null
...
                         isDragging = true
                         dragCurrentPosition = dragStartPosition
+                        // 触发档位变化开始回调
+                        onGearChangeStartListener?.onGearChangeStart()
                         handleDragMove(touchX)
...
             if (gear in minGear..maxGear && gear != currentGear) {
+                // 触发档位变化开始回调（点击轨道 / 点击滑块两条路径同补）
+                onGearChangeStartListener?.onGearChangeStart()
                 setCurrentGear(gear, animate = true)
```
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
+            driverHudLayout.includeSeekbarHudBrightness.gearBright.onGearChangeStartListener =
+                GearSwitchView2.OnGearChangeStartListener {
+                    if (autoBrightnessState) {
+                        logObserve("onDragStartListener->onDragStarted")
+                        driverHudLayout.includeSeekbarHudBrightness.tvAuto.setChecked(false)
+                        settingVehicleService.sendL2A(CarPropertyIds.HUD_AUTO_BRIGHT_SWITCH, 0)
+                    }
+                }
```

## 为什么能修复
`GearSwitchView2` 把"档位变化开始"抽象为独立回调 `onGearChangeStartListener`，并在全部三条用户交互路径（按下开始拖动、点击轨道跳档、点击滑块跳档）统一触发；业务层把"关自动亮度 + UI 上取消 AUTO 勾选"从旧监听迁移到该回调，三条路径行为对齐，缺陷消除。`tvAuto.setChecked(false)` 同步了 UI 状态，避免信号已关但开关仍显示开启。

## 复盘与经验
- 根因模式：交互控件只暴露"过程"回调（onChanged/onDragStarted）而缺"起点"回调时，业务规则挂在过程回调上必然漏路径。给自定义控件补齐 `OnXxxStartListener` 这类语义完整的回调集，是控件通用化的一部分（本提交在拖动/点击轨道/点击滑块三处对称插入）。
- 同提交清理了 LightFragment 中约 34 行注释掉的后雾灯/智能近光灯等死代码，方向正确但与缺陷修复混提，回溯缺陷修复时应聚焦 VehicleControlFragment/GearSwitchView2 两文件。
- 修复同时发 `HUD_AUTO_BRIGHT_SWITCH=0` 信号与 `tvAuto.setChecked(false)`，"状态+信号"双写是对的做法；遗留风险是若自动亮度状态由车端反向同步，可能出现短暔回跳，需依赖后续状态回调收敛。
