# SIR-7708/SIR-7697 · CarPlay 来电无电话浮窗 & D 档图标覆盖 dock 栏
- **提交**：`2583034b` | 2026-09-09 | ljl | SystemUI（含 BTPhone） | bugfix
- **缺陷库**：SIR-7708 B·必现·手车互联（需求变更导致，已适配）；SIR-7697 C·高概率·主交互（需求变更导致，时序优化）

## 问题
① 手机连接 CarPlay 后进入行车仪表界面（Linux 仪表类页面盖住主屏），D 档车速>0 时来电，没有弹出电话浮窗；② 地图导航态下切换 D/P 档位时，D 档图标有概率覆盖 dock 栏。

## 根因分析
① `CarPlayCallManager.refreshWindow()` 原判据只有 `isCarPlayForeground() || mCallMap.isEmpty()` 就隐藏弹窗——CP"前台"指 CarPlay 投屏会话在前台，但需求变更后仪表界面由 Linux 侧渲染（Meter_Form 形态 1/4/5/6/7）直接盖住主屏，CP 通话界面实际不可见，弹窗却被判据压制，来电无浮窗；且弹窗只挂默认屏 display0，没有跨屏路由能力。② `NavBarFragment.updateMenuVisibility()` 中 Linux 形态（displayState 1/4/5）分支无条件 `View.GONE`，D/R+锁屏标由仪表渲染；需求变更后解除触屏锁定时要恢复 Android dock，旧逻辑缺 `mDriveTouchLocked` 联动，档位图标（仪表渲染）与 dock 显隐时序错开时出现"D 档图标覆盖 dock"。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java；application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java；application/BTPhone/src/main/java/com/yadea/btphone/BtPhoneApp.java；application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java
@@ -550,18 +556,30 @@
     private void refreshWindow() {
         try {
-            if (isCarPlayForeground() || mCallMap.isEmpty()) {
+            if ((isCarPlayForeground() && !isMeterDesktopCovering()) || mCallMap.isEmpty()) {
                 mCallWindow.hide();
             } else {
+                // Linux 仪表类页面盖住主屏时弹窗挂仪表屏（Display 2），否则挂默认屏（SIR-7708）
+                int targetDisplay = isMeterDesktopCovering()
+                        ? CarPlayCallWindow.DISPLAY_DASHBOARD
+                        : CarPlayCallWindow.DISPLAY_DEFAULT;
+                mCallWindow.show(new LinkedHashMap<>(mCallMap), targetDisplay);
             }
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/BtPhoneApp.java
+    private void registerMeterFormCallback() {
+        if (mMeterFormCallbackRegistered) return;
+        try {
+            IviCommManager.getInstance().registerCommStateCallback(new ICommStateCallback.Stub() {
+                @Override
+                public void onCommState(String id, int value) {
+                    if ("Meter_Form".equals(id)) {
+                        CarPlayCallManager.getInstance().onMeterFormChanged(value);
+                    }
+                }
+            });
+            mMeterFormCallbackRegistered = true;
+            IviCommManager.getInstance().sendCommValue("Meter_Form", "Get", 0);
```
`CarPlayCallWindow.show()` 增加 displayId 参数（DISPLAY_DEFAULT=0 / DISPLAY_DASHBOARD=2），懒加载 display2 的 WindowManager 并以 `mActiveWindowManager` 记录当前挂载屏、hide 按实际挂载屏摘除；`NavBarFragment` Linux 形态分支并入统一规则 `mDriveTouchLocked ? GONE : VISIBLE`。

## 为什么能修复
① 新增 ivicomm `Meter_Form` 订阅（L2A 初始化成功后注册、防重复、注册即 Get 同步初值），`isMeterDesktopCovering()` 把"Linux 页面盖住主屏"纳入弹窗判据，并在覆盖时把弹窗路由到 display2 仪表屏，来电浮窗恢复可见；跨屏挂载/摘除用 `mActiveWindowManager` 追踪避免 remove 错屏。② dock 显隐统一为"行驶触屏锁定时隐藏、解除即恢复"，与仪表档位图标渲染时序解耦，不再互相覆盖。风险：Meter_Form 与 Launcher KanziDataSourceManager 的渲染分区规则需保持一致（代码注释已声明），形态值扩展（如新增降级页）时两处要同步；跨屏切换窗口的竞态（hide 与 addView 并发）已有 reuse 分支兜底但需实测。

## 复盘与经验
- 车机"前台"判断不能只看 Android 侧会话状态：Linux/仪表侧渲染形态（Meter_Form 类信号）才是主屏可见性的最终事实，跨 OS 形态信号要纳入 UI 显隐判据。
- 浮窗支持多 display 挂载时，必须记录"当前挂在哪个 WindowManager"，移除时按记录摘除，否则跨屏切换后 remove 抛 IllegalArgumentException。
- 需求变更（仪表由 Android 渲染改 Linux 渲染）会同时打击多个模块的显隐逻辑，回归要覆盖"来电/媒体/dock/档位图标"等所有依赖渲染分区的 UI。
