# SIR-8686 · 打开音量 OSD 条时被故障通知（HUN）遮挡

- **提交**：`86285109` | 2026-09-18 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 待测试验证 · 域 主交互

## 问题
触发故障通知（HUN 暂态弹窗）后，再打开音量 OSD 条，OSD 条被故障通知卡片盖住，用户看不到音量变化。

## 根因分析
Android 悬浮窗的 Z 序由 window type 在平台层级表（`windowTypeToLayerLw`）中的层值决定。修复前 HUN 容器 `CarHeadsUpNotificationAppContainer` 硬编码 `TYPE_SYSTEM_ERROR`，并带注释 "This type allows covering status bar and receiving touch input"（原代码自己承认是借用了"盖住一切"的顶层类型，还挂了 NOSONAR）；而音量 OSD（`VolumeWindowManager.getLayoutParams()`）用的是更低的 `TYPE_SYSTEM_DIALOG`。两者叠加时 OSD 必然在 HUN 之下，即"OSD 被故障通知遮挡"。元数据 `[why]type使用不对` 与 diff 一致。另外旧实现把 window type 写死在 `getLayoutParams` 里，`CarHeadsUpNotificationContainer` 基类完全不知道 type，导致多显示场景无法区分层级。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/notification/headsup/CarHeadsUpNotificationAppContainer.java、.../headsup/CarHeadsUpNotificationContainer.java、.../vehiclecontrol/volume/VolumeWindowManager.kt（共 +26/-9）
```diff
--- .../notification/headsup/CarHeadsUpNotificationAppContainer.java
@@ 构造链
     public CarHeadsUpNotificationAppContainer(Context context, int displayId) {
-        this(createDisplayContext(context, displayId), true);
+        this(context, displayId,
+                displayId == Display.DEFAULT_DISPLAY
+                        ? WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
+                        : WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
     }
@@ addView 参数
         WindowManager.LayoutParams wrapperParams = new WindowManager.LayoutParams(
                 ViewGroup.LayoutParams.MATCH_PARENT,
                 ViewGroup.LayoutParams.WRAP_CONTENT,
-                // This type allows covering status bar and receiving touch input
-                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,   //NOSONAR
+                // L0/LX 使用系统对话框层级，L1 使用导航栏层级。
+                getWindowType(),
                 WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                         | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                 PixelFormat.TRANSLUCENT);
--- .../vehiclecontrol/volume/VolumeWindowManager.kt
@@ getLayoutParams
-            type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG
+            type = WindowManager.LayoutParams.TYPE_TOAST
```
配套改动：基类 `CarHeadsUpNotificationContainer` 新增 `mWindowType` 字段与 `getWindowType()`，type 经构造函数逐级传入，日志同步打印 windowType。

## 为什么能修复
修复做了"一降一升"：HUN 从顶层的 `TYPE_SYSTEM_ERROR` 降到主屏 `TYPE_NAVIGATION_BAR`（副屏用 `TYPE_SYSTEM_DIALOG`），音量 OSD 从 `TYPE_SYSTEM_DIALOG` 升到 `TYPE_TOAST`，使 OSD 的层值高于 HUN，遮挡关系反转，音量条可正常盖在故障通知之上。type 改为构造注入后，同一容器在不同 display 上可用不同 type，不再硬编码。需要留意两点：其一，最终叠放顺序依赖平台 framework 对 `TYPE_TOAST`/`TYPE_NAVIGATION_BAR` 的层级表定制（仓库内 `KeyguardActor.kt` 注释表明本平台层级顺序已被定制，非纯原生 AOSP），换平台需重新验证；其二，`DriveTouchMaskWindow.kt:24` 的注释仍写着"HUN TYPE_SYSTEM_ERROR"，本次未同步更新，属于遗留的不一致文档。

## 复盘与经验
- 悬浮窗遮挡类 bug 的第一排查项是 window type，而不是布局或 addView 顺序；`TYPE_SYSTEM_ERROR`（已废弃）这类"万能顶层"类型短期省事，长期必然与其他系统窗打架。
- 同一个物理弹窗在多显示设备上可能需要不同 window type（本例主屏/副屏分支），把 type 从使用点抽到构造参数是必要重构，顺带让日志可定位。
- 层级类修复要同时梳理"谁该在谁之上"的全局顺序表（状态栏/Dock/HUN/OSD/锁屏），只调一对关系容易按下葫芦浮起瓢；相关注释（如 DriveTouchMaskWindow 的层级说明）应随改动同步维护。
