# 无单号 · 修改驾驶模式下发和反馈信号

- **提交**：`e0903b2f` | 2026-08-27 | sgh | Setting | feature（内容实为信号一致性修正）
- **关联单**：无

## 需求/目标
驾驶模式的"下发(请求)信号"与"反馈(状态)信号"在 CAN 上是两套不同编码（下发：经济=2/舒适=1/运动=3；反馈：舒适=0/经济=1/运动=2），此前混用一套常量导致下发与回显不一致；本提交把两套编码显式拆开。

## 实现结构
- `DrivingFragment.kt`：常量区一分为二——`DRIVE_MODE_REQ_*`（下发）与 `DRIVE_MODE_STS_*`（反馈）；`getDriveModeValue()`（UI位置→下发值）改用 REQ 常量，`getDriveModeUiIndex()`（反馈值→UI位置）改用 STS 常量；`updateDrivingModeUI()` 的图标映射同步改用 STS 常量；顺带删除被注释的"助力推行"档遗留；KDoc 全部更新。
- `VehicleControlFragment.kt`：顺手修复把手锁状态为 ON 时背景误用 `seat_lock_off_bg` 的资源错配。

数据流：UI 选择 position → `getDriveModeValue`（REQ 编码）→ sendVehicleProperty；MCU 回报 → observe → `getDriveModeUiIndex`（STS 编码）→ `setSelectedIndex` + 图标。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -52,11 +52,15 @@
-        // 驾驶模式信号值常量
-        private const val DRIVE_MODE_PUSH_ASSIST = 3        // 助力推行
-        private const val DRIVE_MODE_ECO = 1                // 经济模式
-        private const val DRIVE_MODE_COMFORT = 0            // 舒适模式
-        private const val DRIVE_MODE_SPORT = 2              // 运动模式
+        // 驾驶模式外发信号值：经济=2, 舒适=1, 运动=3
+        private const val DRIVE_MODE_REQ_ECO = 2
+        private const val DRIVE_MODE_REQ_COMFORT = 1
+        private const val DRIVE_MODE_REQ_SPORT = 3
+
+        // 驾驶模式反馈信号值：舒适=0, 经济=1, 运动=2
+        private const val DRIVE_MODE_STS_COMFORT = 0
+        private const val DRIVE_MODE_STS_ECO = 1
+        private const val DRIVE_MODE_STS_SPORT = 2
```
```diff
@@ -333,7 +335,7 @@
-     * @param drivingMode 驾驶模式信号值：0=舒适，1=经济，2=运动，3=助力推行
+     * @param drivingMode 驾驶模式反馈(状态)信号值：舒适=0，经济=1，运动=2
      */
     fun updateDrivingModeUI(drivingMode: Int) {
         val uiIndex = getDriveModeUiIndex(drivingMode)
```

实现讲解：根因是"同一业务概念在请求/反馈两个方向上有不同物理编码"，旧代码共用一组常量必然错一个方向。修法是用 `REQ`/`STS` 前缀命名空间隔离两套枚举，映射函数各自只引用本方向常量，编译期即消除混用可能。另外把反馈值 else 分支落到 `INVALID_STATE_VALUE`，异常值不再误选模式。

## 复盘与要点
- 车机 CAN 开发中"同一功能的方向性双编码"很常见（set 值域 ≠ status 值域），接入信号时应第一时间建两套带前缀的常量，而不是复用。
- KDoc 里写清值域含义（本提交重点改注释）对接手者是最大善举；注释掉的"助力推行"死代码在此提交被清理。
- 顺带修复的 `seat_lock_on/off_bg` 资源错配说明 ON/OFF 状态贴图共用 UI 代码时极易复制粘贴出错，状态-资源映射也值得集中化。
