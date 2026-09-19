# SIR-6711 / SIR-7020 · 全屏导航车速降至 0，3D 驻车界面被白底覆盖 & 后摄故障 Toast
- **提交**：`19103951` | 2026-09-04 | ljl | SystemUI | bugfix（双单合并）
- **缺陷库**：SIR-6711 等级 C · 必现-80%~100% · 关闭 · 域 主交互；SIR-7020 等级 B · 必现-80%~100% · 关闭 · 域 安全监控

## 问题
1) 全屏地图导航时车速从非 0 降到 0，切出的 3D 驻车界面被白底覆盖，3D 车模不渲染（SIR-6711）；2) 后视摄像头故障时无"后视摄像头故障"Toast 提示（SIR-7020，需求变更/开发变更）。

## 根因分析
问题 1：进入 `S3_Android_Drive`（D 档暂态桌面）时，SystemUI 必须通过 `notifier.enterFiveFingerCapture(1)` 通知 Launcher 置 Kanzi 的 `D_Desktop=1`，Launcher 才渲染 3D 车模。旧代码只在"四指滑动"（`Event.FourFingerSwipe`）路径上做了该通知，而"车速降 0 触发 `DriveTouchUnlocked`""导航异常退出 `NavExitedForeground`""mapKeepForeground"等其他进入 S3 的迁移路径都没有调用——这些路径进入暂态桌面后 Launcher 未收到抓屏通知，桌面不渲染 3D 车模，表现为白底覆盖。问题 2：后摄故障 Toast 功能此前因 `NsrCommSdk.aar` 不支持 `ICameraHpStatusCallback` 而在 `3ed8eb5b` 中整段注释预埋（"待新版 NsrCommSdk.aar 到位后恢复"），本提交即新版 aar 到位后的启用。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`（核心）、`.../cmdcontroller/systemsetting/SystemSettingsControllerService.kt`、`.../digitalkey/DigitalKeyVehicleService.kt`、`component/commonlibs/NsrCommSdk.aar`（二进制更新，旧包备份为 `NsrCommSdk.aar.bak`）

```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
-    private const val D_ZONE_CLICK_INTO_S1_DELAY: Long = 333 //400
+    private const val FOUR_FINGER_SWIPE_INTO_S3_DELAY: Long = 333 //333
@@ （DriveTouchUnlocked / NavExitedForeground / mapKeepForeground 等所有进入 S3 的迁移均补齐）
                 Event.DriveTouchUnlocked -> {
                     State.S3_Android_Drive to { _: DataContext ->
+                        // D档进入暂态桌面需同步通知Launcher进入五指抓屏（置Kanzi D_Desktop=1），否则桌面不渲染3D车模（SIR-6711）
+                        mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) }, FOUR_FINGER_SWIPE_INTO_S3_DELAY)
                         notifier.showAndroidDrivePage()
                     }
                 }
```

```diff
--- application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt（预埋代码解除注释并启用）
-    //private val mRearCameraFaultShown = AtomicInteger(0)
+    private val mRearCameraFaultShown = AtomicInteger(0)
...
-                //IviCommManager.getInstance().registerCameraHpStatusCallback(mCameraHpStatusCallback)
+                IviCommManager.getInstance().registerCameraHpStatusCallback(mCameraHpStatusCallback)
...
+    private fun checkRearCameraFault(cameraOpt: Int, com0: Int) {
+        if (cameraOpt != 0 && com0 != 0 && mRearCameraFaultShown.getAndSet(1) == 0) {
+            ToastUtils.showMsgICToast(mAppContext, mAppContext.getString(R.string.rear_camera_fault_toast))
+        }
+    }
```

```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
-        //if (gear != 2) {
-        //    mSystemSettingsControllerService.resetRearCameraFaultToast()
-        //}
+        if (gear != 2) {
+            mSystemSettingsControllerService.resetRearCameraFaultToast()
+        }
```

## 为什么能修复
问题 1：所有进入 S3 的状态迁移统一补上 `enterFiveFingerCapture(1)`（延迟提取为 `FOUR_FINGER_SWIPE_INTO_S3_DELAY` 常量，与既有四指路径一致），Launcher 每次进 D 档暂态桌面都收到置 `D_Desktop=1` 的通知，3D 车模正常渲染，白底消失。问题 2：新版 `NsrCommSdk.aar`（二进制更新，47611→53365 字节）提供 `ICameraHpStatusCallback`，预埋代码解除注释后，`onCameraHpStatus` 回调里按 `camera_opt!=0 && com0!=0` 判定故障并经 `ToastUtils.showMsgICToast` 提示，`mRearCameraFaultShown` 原子标志保证一次挂 R 档只弹一次，档位切非 R 档由 `DigitalKeyVehicleService` 复归。隐患：`.aar.bak` 备份文件被提交进仓库，属工程卫生问题；333ms 的 postDelayed 魔法延迟依赖经验时序。

## 复盘与经验
- 状态机修复要覆盖"到达同一状态的所有迁移边"：只在一条事件路径上做的副作用调用（enterFiveFingerCapture），换条路进来就丢——同类"进入某态必须做的通知/初始化"应收敛到状态的 entry 动作里统一执行。
- "待依赖到位后恢复"的注释预埋代码，恢复时应连同依赖（aar 二进制更新）一起提交并回归验证；备份文件（.bak）不要入库。
- 摄像头故障这类安全提示要设计去重与复位语义（每挂一次 R 档提示一次），原子标志 + 状态变化复位是最简实现。
