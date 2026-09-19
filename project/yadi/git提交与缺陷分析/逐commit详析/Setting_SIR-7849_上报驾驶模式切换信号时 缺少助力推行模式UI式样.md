# SIR-7849 · 驾驶模式缺少"助力推行"UI 式样与 Tab 选项
- **提交**：`2e3c7aa2` | 2026-09-17 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
上报驾驶模式切换信号时，设置页驾驶模式选择缺少"助力推行"模式的 UI 式样和 Tab 选项，收到助力推行反馈信号时界面无法正确显示。

## 根因分析
`DrivingFragment` 原实现只定义了三档驾驶模式：请求值常量 `DRIVE_MODE_REQ_ECO=2/COMFORT=1/SPORT=3`、反馈值常量 `DRIVE_MODE_STS_COMFORT=0/ECO=1/SPORT=2`，`getDriveModeValue(position)` 与 `getDriveModeUiIndex(modeValue)` 的映射表也只覆盖三项，`strings.xml` 的 `setting_driver_mode_title` 数组同样只有"经济/舒适/运动"。当车控上报新的"助力推行"模式（请求值 4、反馈值 3）时，映射落到 `else` 分支返回 `INVALID_STATE_VALUE`，Tab 无该项、配图 `setting_drive_mode_push` 也未接线——属需求新增模式后 UI 代码未同步的"缺失 UI"问题。

## 关键代码修改
改动文件：DrivingFragment.kt、values/strings.xml、values-en/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
-        // 驾驶模式下发(请求)信号值：经济=2, 舒适=1, 运动=3
-        private const val DRIVE_MODE_REQ_ECO = 2
+        // 驾驶模式下发(请求)信号值：经济=2, 舒适=1, 运动=3,助力4
         private const val DRIVE_MODE_REQ_COMFORT = 1
+        private const val DRIVE_MODE_REQ_ECO = 2
         private const val DRIVE_MODE_REQ_SPORT = 3
+        private const val DRIVE_MODE_PUSH_ASSIST = 4
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
     private fun getDriveModeValue(position: Int): Int {
         return when (position) {
-            0 -> DRIVE_MODE_REQ_ECO
-            1 -> DRIVE_MODE_REQ_COMFORT
-            2 -> DRIVE_MODE_REQ_SPORT
+            0 -> DRIVE_MODE_PUSH_ASSIST
+            1 -> DRIVE_MODE_REQ_ECO
+            2 -> DRIVE_MODE_REQ_COMFORT
+            3 -> DRIVE_MODE_REQ_SPORT
             else -> DRIVE_MODE_REQ_COMFORT
         }
     }
...
+                DRIVE_MODE_STS_PUSH_ASSIST -> ivVehicleDisplay.setImageResource(R.drawable.setting_drive_mode_push)
                 DRIVE_MODE_STS_COMFORT -> ivVehicleDisplay.setImageResource(R.drawable.setting_drive_mode_com)
```
```diff
// application/Setting/src/main/res/values/strings.xml
     <string-array name="setting_driver_mode_title" translatable="false">
+        <item>助力推行</item>
         <item>经济</item>
         <item>舒适</item>
         <item>运动</item>
```

## 为什么能修复
新增常量 `DRIVE_MODE_PUSH_ASSIST`（请求 4）/`DRIVE_MODE_STS_PUSH_ASSIST`（反馈 3）后，双向映射表 `getDriveModeValue`/`getDriveModeUiIndex` 把第 0 个 Tab 对应助力推行，`setting_driver_mode_title` 数组插入"助力推行"补齐 Tab 文案，`when(drivingMode)` 分支接上 `setting_drive_mode_push` 配图。上报值→UI 索引→Tab 选中（`rgDriveMode.setSelectedIndex(uiIndex)`）整条链路重新对齐，助力推行模式可正常下发与回显。注意点：Tab 插入首位导致既有三档的 position 全部位移，映射两表必须同步修改（本提交正是成对修改），漏改一边就会出现"选运动下发经济"的错位 bug。

## 复盘与经验
- 枚举式 UI（模式/档位选择）的 Tab 顺序、请求信号值、反馈信号值三套编码天然不对齐（请求与反馈值系都不同），必须集中用映射函数转换而不是拿 position 直接当信号值。
- 新增枚举值时要全文搜索所有 `when`/映射分支，`else ->` 兜底会把"缺分支"掩盖成"显示异常"，可考虑日志或显式断言暴露未覆盖项。
- UI 式样评审应对照信号矩阵（CAN/CAR Property 定义表）确认模式全集，避免车控新增模式后 UI 被动补课。
