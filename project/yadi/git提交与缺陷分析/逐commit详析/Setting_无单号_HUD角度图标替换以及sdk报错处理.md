# 无单号 · HUD 角度图标替换以及 SDK 报错处理

- **提交**：`37d1b73b` | 2026-06-30 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
两件事打包进一个提交：1) HUD/座椅调节的角度与亮度图标从 mdpi 位图替换为语义色 vector（含删除前一日补的 drawable-night png）；2) 规避座椅 SDK 初始化报错——注释掉 `initSeatSdk()` 调用并恢复座椅 CAN 状态对 UI 的使能控制。

## 实现结构
- `init/SettingVehicleService.kt`：`init()` 中 `initSeatSdk()` 调用注释、`initSeatSdk()` 函数体整体注释（`VehicleSdk.getInstance().initialize(...)` 连接座椅 SDK 的逻辑停用）。
- `VehicleControlFragment.kt`：恢复被注释的两行使能逻辑 `isSeatControlEnabled = (status == 0)` 并 `updateSeatControlEnabledState(...)`，座椅相关 CAN 状态异常时禁用座椅控制 UI。
- `VehicleControlFragment.kt` 布局引用：`layout_seat_adjustment.xml` 删掉 40 行旧高度调节视图，`gearswitchview_adjust_left_right.xml` 删 3 行。
- 资源：删除 `drawable-mdpi/adjust_corner_*/adjust_height_*/hud_light_*` 6 张 png 与 `drawable-night/adjust_height_*` 2 张 png，新增同名 vector（fillColor 语义色）+ `selector_common_right_round_btn.xml`、`shape_btn_white_selected.xml`、`selector_common_text_color_black.xml`。

数据流：车辆信号 → SettingVehicleService（不再初始化座椅 SDK）→ CAN status 观察者 → isSeatControlEnabled → 座椅控件 enable；图标则完全走资源替换。

## 关键代码
```diff
# application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
-        initSeatSdk()
+//        initSeatSdk()
...
-    fun initSeatSdk() {
-        val vehicleSdk = VehicleSdk.getInstance()
-        vehicleSdk.initialize(applicationContext, object : ConnectionListener {
-            override fun onVehicleServiceConnected() {
-                driveStateManager = vehicleSdk.getManager(DriveStateManager::class.java)
-            }
-            ...
-        })
-    }
+//    fun initSeatSdk() { ... }   // 整体注释停用
```
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
                     if (status != null) {
-//                        isSeatControlEnabled = (status == 0)
-//                        updateSeatControlEnabledState(isSeatControlEnabled)
+                        isSeatControlEnabled = (status == 0)
+                        updateSeatControlEnabledState(isSeatControlEnabled)
                     }
```

实现讲解：SDK 报错的"处理"方式是注释停用——座椅控制改回走原有 CAN 信号通道（isSeatControlEnabled 判断 status==0 正常才使能 UI），这是供应商 SDK 不稳时的常见降级：保留调用代码便于回切，先保功能可用。图标 vector 化同时删掉了 d6c6dd54 刚补的 night png，说明夜间位图方案被最终放弃。

## 复盘与要点
- 风险提示：以注释方式停用 SDK 是临时手段，注释体长期滞留会误导后来者（本文件同函数内已有大量注释块），应有 TODO/单号跟踪何时移除或恢复。
- 一个提交混"图标替换"（UI 资源）与"SDK 报错处理"（功能降级）两件事，测试范围却只写了"HUD角度图标替换"，座椅控制的回归风险被提交信息掩盖，属于 commit 切分纪律问题。
- 可复用手法：png→vector 时同步删 drawable-night 对应文件，避免"白天 vector + 夜间旧 png"混用造成两模式风格不一致。
