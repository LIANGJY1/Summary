# SIR-1744 · 五指抓屏回驻车桌面后行车不返回仪表界面
- **提交**：`76aeb00e` | 2026-07-02 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
五指抓屏手势回到驻车桌面（Launcher）后，车速从 0 升到 >0 时不会主动切回行车仪表界面。

## 根因分析
页面切换由 `PageStateMachine`（`application/SystemUI/.../digitalkey/mainaction/PageStateMachine.kt`）驱动。缺陷库归因是需求理解偏差：此前实现只按"非地图等其他应用在前台时触发车速变化"处理，遗漏了驻车桌面这一前台形态。代码层面可见两个缺口：1) `DigitalKeyVehicleService` 的 `onTopActivityChange` 只计算 `isNavForeground/isEnergyForeground/isLauncherForeground` 三个布尔并同步给状态机，没有"普通应用页"这一概念；2) `PageStateMachine.setSpeed(speed)` 只是更新 `ctx.speed`，没有在 S3（`State.S3_Android_Drive`）下监听"车速 0→>0"沿并触发回仪表事件——驻车桌面属于 Launcher 前台，既不是地图也不是普通应用，车速升起后没有任何事件把它拉回仪表状态 1。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`、`application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt`、`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/DataContext.kt`
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
@@ -203,7 +203,22 @@
     fun setSpeed(speed: Float) {
         if (!initialized) return
         val oldSpeed = ctx.speed
         ctx.speed = speed
+        // S3 下车速从 0 升到 >0：应用页/地图全屏自动返回
+        if (oldSpeed <= 0 && speed > 0 && curState == State.S3_Android_Drive) {
+            when {
+                ctx.appForeground -> {
+                    handleEvent(Event.AppPageBackWithSpeed)
+                }
+                ctx.navForeground -> {
+                    handleEvent(Event.MapFullscreenBackWithSpeed)
+                }
+            }
+        }
     }
+    @JvmStatic
+    @Synchronized
+    fun setAppForeground(foreground: Boolean) {
+        if (!initialized) return
+        ctx.appForeground = foreground
+    }
```
配套：`DataContext` 新增字段 `var appForeground: Boolean = false`（非 Launcher、非地图、非能量中心的普通应用页）；`DigitalKeyVehicleService.onTopActivityChange` 中计算 `isAppForeground = topPkgName.isNotBlank() && !isNavForeground && !isLauncherForeground` 并调用 `PageStateMachine.setAppForeground(...)`。

## 为什么能修复
现在驻车桌面回行车后（车速 0→>0、当前处于 S3_Android_Drive），状态机在 `setSpeed` 里显式产生边沿事件：普通应用前台触发 `Event.AppPageBackWithSpeed`、地图全屏触发 `Event.MapFullscreenBackWithSpeed`，由状态机统一迁回仪表状态 1（导航在前台时为状态 2），不再依赖"前台形态恰好被旧逻辑覆盖"。隐患：`appForeground` 的判定把"能量中心在前台"排除在外（`isAppForeground` 未排除 energy），能量中心行车中的行为仍由其它事件分支管理，需关注交叉场景；边沿检测依赖 `oldSpeed` 的持续维护，若 `setSpeed` 漏调则不触发。

## 复盘与经验
- 状态机驱动页面切换时，"哪些前台形态可以触发某迁移"要枚举穷尽（地图/普通应用/Launcher/能量中心），漏一类就是一个缺陷单。
- 车速类连续量做触发条件时用边沿检测（oldSpeed<=0 && speed>0），比电平判断更能表达"起步"语义。
- 前台形态判定集中在一处计算并显式命名（isAppForeground），比散落的取反条件更可维护。
