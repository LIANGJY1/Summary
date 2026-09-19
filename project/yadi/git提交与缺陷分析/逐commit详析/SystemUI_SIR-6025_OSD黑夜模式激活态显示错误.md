# SIR-6025 · OSD（音量浮层）黑夜模式激活态显示错误

- **提交**：`e0f53b2f` | 2026-08-20 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
白天/黑夜模式切换后，OSD 音量调节浮层（VolumeDialog）的激活态仍用旧主题颜色渲染，黑夜模式下显示错误。

## 根因分析
缺陷库根因："主题切换未主动刷新UI"。`VolumeDialogActor` 的视图在 `initView()` 时一次性解析 NsrCommonUI 颜色资源，之后系统 UI Mode（白天/夜间）切换并不会自动触发其重建——该 OSD 由 SystemUI 进程自行管理视图，不走 Activity 的 `recreate` 生命周期，配置变化后颜色资源仍是旧主题解析结果。项目里 NavBarActor、StatusBarActor、QuickSettingActor 都已有各自的 `recreateForUiModeChange()`（本提交前 `SystemUIApplication.onConfigurationChanged` 路径只刷新了 QuickSettingActor），唯独音量 OSD 漏掉了，属于新增 Actor 时未接入主题切换通知链。修复为 `VolumeDialogActor` 补上同名方法：先记录当前是否在显示，`hide()` 后 `initView()` 重建视图（重新解析新主题颜色），若切换前正在显示则用缓存音量状态 `showVolume()` 恢复。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt、SystemUIApplication.kt（2 文件，+32）
```diff
@@ application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt @@
+    fun recreateForUiModeChange() {
+        if (Looper.myLooper() != Looper.getMainLooper()) {
+            mVolumeHandler.post { recreateForUiModeChange() }
+            return
+        }
+        LogUtils.i(TAG, "recreateForUiModeChange")
+        val wasShowing = isShow()
+        if (wasShowing) {
+            hide()
+        }
+        initView()
+        if (wasShowing) {
+            showVolume(
+                volume = mCurrentVolume,
+                maxVolume = mMaxVolume,
+                minVolume = mMinVolume,
+                isMute = mIsMute,
+                sourceLabel = mSourceLabel,
+                groupId = mGroupId
+            )
+        }
+    }
```
```diff
@@ application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt @@
         quickSettingActor.recreateForUiModeChange()
+        val volumeDialogActor =
+            ActorController.getInstance()[ActorController.TYPE_VOLUME_ADJUST] as VolumeDialogActor
+        volumeDialogActor.recreateForUiModeChange()
```

## 为什么能修复
主题切换事件现在会显式驱动音量 OSD 重建视图，颜色资源按新 UI Mode 重新解析，激活态配色随主题走；hide/show 保护避免重建时浮层闪没或状态丢失。隐患：重建期间如果正好有音量变化回调，可能操作到旧视图（依赖 mVolumeHandler 主线程串行化缓解）。

## 复盘与经验
- SystemUI 这类常驻进程的自绘视图不会自动响应 UI Mode 切换，必须显式接入 recreateForUiModeChange 通知链；新增一个全局浮层就要同步加一个刷新入口。
- 项目内已有同类 Actor 的成熟模式（NavBar/StatusBar/QuickSetting），新组件照抄同构方法即可避免此类漏网——"对齐既有模式"是最便宜的防错手段。
- 重建无状态浮层时先快照显示状态与业务数据，重建后原样恢复，可做到用户无感。
