# SIR-5819 · 偶发挂 D 档 DOCK 栏主题跳变
- **提交**：`7e553c41` | 2026-08-13 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 仪表信息

## 问题
挂入 D 档时偶发 DOCK 栏（及状态栏）主题跳变：kanzi 车模画面异常帧透过透明的 Dock 栏暴露出来。

## 根因分析
缺陷库归因为"app 与车模（kanzi）的切换时序问题"。代码上，Dock/状态栏的透明背景由仪表形态 `displayState` 驱动（0/3 形态下 `clMenu` 可见且背景设为透明，见 `NavBarFragment.updateMenuVisibility()`），但存在两个时序漏洞：
1. `applyDockBackground(transparent)` 只判断了"当前页是主页（`currentPosition == 2`）"就允许变透明，没有校验当时 `displayState` 是否处于 Android 正常显示形态；车模/应用切换瞬间形态不属于 {0,2,3}（kanzi 异常画面）时仍被置透明，异常帧直接透出。
2. 透明/不透明状态分散在各事件回调里，`updateMenuVisibility()` 切换 `clMenu` 可见性时不重设背景，显示状态残留导致跳变；`StatusBarFragment` 也没有监听 `displayState` 变化，形态切换后状态栏背景不跟随。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java`、`statusbar/ui/StatusBarFragment.java`
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java (updateMenuVisibility)
             int displayState = settingsControllerService.getDisplayState();
             if (displayState == 0 || displayState == 3) {
                 clMenu.setVisibility(View.VISIBLE);
+                setBackGround(false);
             } else {
                 clMenu.setVisibility(View.GONE);
+                setBackGround(true);
             }
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java (applyDockBackground)
-        if (transparent && currentPosition != 2) {
-            LogUtils.d(TAG, "applyDockBackground: current page not Home!");
+        int displayState = settingsControllerService.getDisplayState();
+        if (transparent && (currentPosition != 2 || (displayState != 0
+                && displayState != 2 && displayState != 3))) {
+            LogUtils.d(TAG, "applyDockBackground: current page not Home or display mode not Android !");
             return;
         }
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/statusbar/ui/StatusBarFragment.java (onViewCreated)
+        // 监听仪表形态
+        mSystemSettingsControllerService.displayState.observe(getViewLifecycleOwner(), displayState -> {
+            if (displayState != 0 && displayState != 2 && displayState != 3) {
+                setBackGround(true);
+            } else {
+                setBackGround(false);
+            }
+        });
```

## 为什么能修复
透明化的门槛从"仅主页"升级为"主页 且 displayState ∈ {0,2,3}"，kanzi 异常形态期间 `applyDockBackground` 直接拒绝透明请求；`updateMenuVisibility()` 在切换可见性的同时强制重设背景，消除状态残留；状态栏挂上 `displayState` LiveData 实时联动。三者叠加后，形态切换的任意中间时序下 Dock/状态栏都保持不透明兜底，异常帧不再透出。隐患：displayState 判定集合（0,2,3 vs 仅 0/3）在两处不完全一致，说明形态枚举语义需统一维护，否则后续加形态容易再漏。

## 复盘与经验
- 透明 UI 依赖外部系统状态（仪表形态/车模切换）时，"允许透明"必须是多条件白名单（页面 + 系统形态 + 档位），单一条件必然在时序边界漏帧。
- 状态切换回调里要"先落完整状态再返回"：可见性与背景同源同设，避免遗留旧形态的透明底。
- 对系统形态这类可用 LiveData 表达的状态，观察式联动优于事件式各点手写判断，天然覆盖所有切换路径。
