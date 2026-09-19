# SIR-8677 · 快速拉动 HUD 亮度进度条，刻度左右回弹
- **提交**：`18a730f3` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 车控车设

## 问题
快速拖动 HUD 亮度进度条时，进度条刻度会左右"弹一下"，跟手性被破坏。

## 根因分析
HUD 亮度走"下发 `HUD_BRIGHT_ADJUST` → 等车控反馈 → `updateHudBrightnessUI` 回显"链路。快速拖动时两次刷新在时间上交叠：用户刚拖到新档位，**滞后的反馈信号**（对应更早的下发值）到达后触发 `updateHudBrightnessUI(brightness)`，其中 `gearBright.setCurrentGear(brightness, true)`（带动画）把进度条拉回旧刻度，随后更新的反馈又把它拉向新刻度——刻度左右往复，即"回显触发回退动画"。此外原超时回滚逻辑用 `previousBrightness`（拖动前的本地旧值）做恢复目标，而回滚发生时真实状态应以 `hudBrightnessState`（最近收到的反馈值）为准，恢复基准也错位。

## 关键代码修改
改动文件：HudFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/HudFragment.kt
     fun updateHudBrightnessUI(brightness: Int) {
-        mBinding.apply {
-            hudBrightnessReboundJob?.cancel()
-            hudBrightnessReboundJob = null
-
-            mBinding.driverHudLayout.includeSeekbarHudBrightness.gearBright.setCurrentGear(
-                brightness,
-                true
-            )
+        // 快速滑动期间（有下发待确认值），滞后反馈只更新确认状态、不驱动进度条，
+        // 避免回显触发回退动画导致刻度卡顿回跳（对齐 DisplayFragment 的 hasTask 处理）
+        if (hudBrightnessTemp != -1) {
+            logObserve("HUD brightness pending confirm ($hudBrightnessTemp), record feedback $brightness without moving slider")
             hudBrightnessState = brightness
+            return
         }
+        mBinding.driverHudLayout.includeSeekbarHudBrightness.gearBright.setCurrentGear(
+            brightness,
+            true
+        )
+        hudBrightnessState = brightness
     }
...
                     action = {
-                        if (hudBrightnessState != hudBrightnessTemp) {
-                            logObserve("HUD brightness timeout rollback, ... restore to level: $previousBrightness")
-                            updateHudBrightnessUI(previousBrightness)
-                        }
                         val sentGear = hudBrightnessTemp
                         hudBrightnessTemp = -1
+                        if (hudBrightnessState != sentGear) {
+                            logObserve("HUD brightness timeout rollback, sent $sentGear but received $hudBrightnessState, restore to level: $hudBrightnessState")
+                            updateHudBrightnessUI(hudBrightnessState)
+                        }
                     })
```

## 为什么能修复
引入"待确认值"闸门：`hudBrightnessTemp != -1` 表示存在尚未被确认的下发值，此时滞后反馈只静默更新 `hudBrightnessState`、**跳过** `setCurrentGear` 回显，进度条保持用户手指位置不回跳；待确认值清零（`-1`）后反馈才恢复驱动 UI。超时回滚目标从 `previousBrightness` 改为 `hudBrightnessState`（真实收到的最新反馈），并且先清 `hudBrightnessTemp` 再调 `updateHudBrightnessUI`，避免回滚刷新自己又被闸门拦截。两处修改共同消除刻度左右弹动。副作用：确认链路彻底丢失时，进度条会停在用户最后拖动位置直到超时回滚兜底，行为可控。

## 复盘与经验
- 滑杆/进度条类控件的"信号回显"必须与"本地拖动"互斥：存在待确认下发值（`temp != -1` 即哨兵）时，反馈只记账不动 UI，是防回跳的标准闸门模式（注释中对齐 DisplayFragment 的 hasTask 处理，说明项目内已有范式）。
- 超时回滚的恢复目标应是"最新已确认状态"而非"操作前快照"，否则快速连续操作下恢复值本身就是过期的。
- 滞后反馈 + 动画回显的叠加是"进度条弹动"的通用成因，排查时先画下发/反馈/UI 三条时间线再改代码。
