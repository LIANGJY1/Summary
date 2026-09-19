# SIR-6681 · applist 跳仪表时 Dock 栏先黑化再跳转

- **提交**：`291dde88` | 2026-08-28 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交信息标注 D，以缺陷库为准）

## 问题
在 applist 界面跳转仪表界面时，Dock 栏先黑化（露出黑色底）再发生跳转，视觉突兀。

## 根因分析
`NavBarFragment.updateGearState` 里残留了一套按档位切背景的旧逻辑：`P→D/R` 延迟 600ms 执行 `setBackGround(true)`（把 `clRoot` 背景设为实色 `bg_status_and_dock`）；`D/R→P` 时 `setBackGround(false)`——该方法的 false 分支是 `clRoot.setBackgroundColor(Color.TRANSPARENT)`，把 Dock 背景直接置为透明。此外 `updateMenuVisibility` 也按 displayState 直接调 `setBackGround(false/true)`。这套"档位/形态驱动、直接改背景"的旧机制与新的 `applyDockBackground`（Drawable alpha 渐变，且带"当前页非主页不变透明"守卫）并存。applist 跳仪表时档位恰好从 D/R 回 P，旧逻辑无条件把 Dock 背景刷成透明，下层是黑色窗口，Dock 看起来"黑化"，随后才完成页面跳转。提交信息概括为"档位改变的时候 Dock 栏被设置成透明了 → 修改 Dock 背景设置逻辑"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
     public void updateGearState(int gear) {
-        if (mCurrentGear == 0 && (gear == 1 || gear == 2)) {
-            if (!handler.hasCallbacks(mPostActionRunnable)) {
-                handler.postDelayed(mPostActionRunnable, 600);
-            }
-        } else if ((mCurrentGear == 1 || mCurrentGear == 2) && gear == 0) {
-            handler.removeCallbacks(mPostActionRunnable);
-            setBackGround(false);
-        }
         this.mCurrentGear = gear;
         ...
-    private Runnable mPostActionRunnable = () -> setBackGround(true);
@@ updateMenuVisibility
             if (displayState == 0 || displayState == 3) {
                 clMenu.setVisibility(View.VISIBLE);
-                setBackGround(false);
             } else {
                 clMenu.setVisibility(View.GONE);
-                setBackGround(true);
             }
```
（`setBackGround` 方法本身保留，供其他入口使用。）

## 为什么能修复
删除档位驱动的强制透明/实色切换后，Dock 背景只由带守卫的 `applyDockBackground` 管理：该入口会判断"当前前台页面不是主页则不变透明"，applist 场景不再被置透明，黑化消失。两套机制收敛为一套，也消除了旧逻辑与新渐变动画互相覆盖导致的闪烁隐患。注意 `updateMenuVisibility` 中 menu 显隐逻辑保留，仅去掉背景副作用。

## 复盘与经验
- 同一 UI 属性（背景）被多个入口写入时，必须收敛为单一管理器并统一前置条件，否则任意入口的边界场景都会"漏黑"。
- 直接 `setBackgroundColor(TRANSPARENT)` 的"透明化"在多窗口车机上等于"透出下层窗口颜色"，非主页场景禁止使用，或必须带页面上下文守卫。
- 新旧机制更替时，旧的触发分支（哪怕注释说"延迟 600ms"这类精细补偿）要整体下线，而不是叠加并存。
