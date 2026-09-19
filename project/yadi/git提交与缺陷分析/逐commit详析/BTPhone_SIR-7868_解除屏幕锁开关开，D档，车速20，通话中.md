# SIR-7868 · 通话浮窗中拨号小键盘无法显示/点击
- **提交**：`74353b08` | 2026-09-09 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（根因：拨号盘显示在 display0）

## 问题
解除屏幕锁开关开启、D 档、车速<20、通话中调出电话浮窗时，点击小键盘按钮拨号盘（DialPad）不显示、无法操作。

## 根因分析
`DialPadWindowManager.getWindowManager()` 原来直接 `mContext.getSystemService(Context.WINDOW_SERVICE)` 获取 WindowManager——这绑定默认 display 0，而 D 档行驶时仪表显示图层压在 display0 上层，拨号盘被盖住看不见也点不到。通话浮窗本身已显示在 display 2（仪表屏），拨号盘却挂在 display0，两屏不一致是问题本质。旧的 `resolveWindowManager()`（Activity/Context 双路兜底）也是默认屏语义，无法指定目标屏。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/DialPadWindowManager.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/DialPadWindowManager.java
@@ -122,7 +122,22 @@
         if (mWindowManager == null) {
             synchronized (this) {
                 if (mWindowManager == null) {
-                    mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
+                    DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
+                    Display display = displayManager.getDisplay(TARGET_DISPLAY_ID);
+                    if (display == null) {
+                        // 目标屏未就绪时不回退到默认屏，避免拨号盘显示在 Android 图层。
+                        LogUtils.w(TAG, "Target display is unavailable, displayId=" + TARGET_DISPLAY_ID);
+                        return null;
+                    }
+                    Context displayContext = mContext.getApplicationContext()
+                            .createDisplayContext(display);
+                    mWindowManager = displayContext.getSystemService(WindowManager.class);
                 }
             }
         }
```
（`TARGET_DISPLAY_ID = 2`；`addFloatWindow/updateFloatWindowLayout/removeFloatWindow` 统一改为先取 `getWindowManager()` 并判空防 NPE；删除 `resolveWindowManager()`、`FLAG_WATCH_OUTSIDE_TOUCH` 的窗体外点击关闭逻辑与重复的 `mPreviousView` 赋值。）

## 为什么能修复
通过 `createDisplayContext(display)` + `displayContext.getSystemService(WindowManager.class)` 把拨号盘窗口挂到 display 2，与通话浮窗同屏，不再被仪表图层遮挡，可点可见。关键防御：目标屏未就绪时 `getWindowManager()` 返回 null 且各入口判空直接 return，明确不回退 display0，避免"看起来弹了但被盖住"的半残状态。副作用：删除了 ACTION_OUTSIDE 关闭逻辑，拨号盘的收起路径依赖浮窗自身交互，需确认外部关闭入口仍存在。

## 复盘与经验
- 车机多屏（display0 主屏/display2 仪表/display4 HUD）开发中，`getSystemService(WINDOW_SERVICE)` 默认绑定 display0；任何浮窗都应显式决定目标屏，与同业务窗口保持同屏。
- "窗口在但看不见/点不到"优先怀疑跨 display 或图层（Z-order）问题，而不是布局尺寸。
- 指定 display 的 WindowManager 获取失败时不要静默回退默认屏——显示在错误的屏比不显示更误导用户；判空 + 日志是更安全的降级。
