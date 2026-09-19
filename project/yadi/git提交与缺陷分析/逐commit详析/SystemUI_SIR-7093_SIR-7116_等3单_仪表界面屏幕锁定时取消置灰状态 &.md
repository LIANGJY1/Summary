# SIR-7093/SIR-7116/SIR-7150 · 仪表锁屏置灰 / 状态3挂R档隐藏导航图标 / 恢复出厂后桌面加载过长
- **提交**：`06420c8a` | 2026-09-01 | ljl | SystemUI | bugfix（三合一提交）
- **缺陷库**：
  - SIR-7093：等级 B · 必现-80%~100% · 关闭 · 主交互
  - SIR-7116：等级 B · 必现-80%~100% · 关闭 · 系统需求
  - SIR-7150：等级 B · 必现-80%~100% · 关闭 · 主交互

## 问题
1. SIR-7150：仪表界面屏幕锁定时媒体控件被置灰（alpha 0.3）。
2. SIR-7093：仪表形态 3 时挂 R 档，导航栏图标被误隐藏。
3. SIR-7116：恢复出厂设置后，桌面"正在加载中"持续过长。

## 根因分析
1. `NavBarFragment.setMediaControlLocked()` 对不可点击控件同时执行 `setEnabled(false)`、`setAlpha(0.3f)`，把"禁用"渲染成了置灰态，超出设计预期。
2. `updateMenuVisibility()` 里存在 R 档强制隐藏分支：`if (mCurrentGear == 2) { clMenu.setVisibility(View.GONE); return }`，优先级高于仪表形态判断；形态 3 下 R 档本应显示导航图标，却先被档位分支拦下。
3. `SystemSettingsControllerService.setMeterForm()` 是一次性 `sendCommValue` 下发；恢复出厂时 Linux 侧 AL 连接认证慢，安卓侧发出的状态值被 Linux 丢弃且无补发机制，仪表形态状态停滞。此外 `DigitalKeyManager` 的唤醒信号 `when` 未覆盖 0x00（SHOW_NULL），恢复出厂后收到 0x00 无分支匹配，锁屏/桌面恢复流程卡住。

## 关键代码修改
改动文件：`.../navbar/ui/NavBarFragment.java`、`.../SystemSettingsControllerService.kt`、`.../digitalkey/DigitalKeyManager.kt`、`.../DigitalKeyConstants.kt`、`.../notifier/PlatformNotifierImpl.kt`、`component/Carlib/.../CarConstants.kt`
```diff
--- .../systemui/navbar/ui/NavBarFragment.java (SIR-7150)
     private void setMediaControlLocked(View view) {
         view.setClickable(false);
-        view.setEnabled(false);
-        view.setAlpha(0.3f);
+        // 锁屏时仅禁用点击，UI 保持正常状态，不置灰
+        view.setAlpha(1.0f);
     }

--- .../systemui/navbar/ui/NavBarFragment.java (SIR-7093)
     private void updateMenuVisibility() {
-        if (mCurrentGear == 2) {
-            // R档时无论仪表形态如何，都不显示底部菜单
-            clMenu.setVisibility(View.GONE);
-        } else {
-            int displayState = settingsControllerService.getDisplayState();
-            ...
-        }
+        // 完全由仪表形态/行驶触屏锁定状态决定，不再按档位强制隐藏
+        int displayState = settingsControllerService.getDisplayState();
+        if (displayState == 0 || displayState == 3) {
+            clMenu.setVisibility(View.VISIBLE);
+        } ...

--- .../systemsettings/SystemSettingsControllerService.kt (SIR-7116)
+    fun setMeterFormWithRetry(value: Int, timeoutMs: Long = METER_FORM_RETRY_TIMEOUT_MS) {
+        pendingMeterForm.set(value)
+        cancelMeterFormRetry()
+        sendMeterFormInternal(value)
+        scheduleMeterFormRetry(value, timeoutMs, 0L)
+    }   // 500ms 间隔补发（断连时 200ms），直到 mLastMeterForm 回射确认或 30s 超时
@@ ID_METER_FORM 回包处理
+                    onMeterFormConfirmed(value)

--- .../digitalkey/DigitalKeyManager.kt (SIR-7116)
-            DigitalKeyConstants.WAKE_UP_SHOW_LAUNCHER -> {
+            DigitalKeyConstants.WAKE_UP_SHOW_LAUNCHER, DigitalKeyConstants.WAKE_UP_SHOW_NULL -> {
                 hideKeyguardIfShowing()
```
`PlatformNotifierImpl.showLinuxDashboard()/showAndroidParkHome()` 同步改调 `setMeterFormWithRetry(1/0)`。

## 为什么能修复
置灰问题改为只 `setClickable(false)`、保持 alpha 1.0，视觉不再置灰且交互仍被禁用。R 档问题删除了档位优先分支，把页面流转统一交给 PageStateMachine，形态判断不被 R 档短路。恢复出厂问题用"带确认的补发"（pending 值 + 回射确认 + 断连重连后立即补发）对抗 Linux 侧丢包，并给唤醒信号 0x00 补上与 0x02 相同的处理分支，避免状态机停滞。隐患：重试机制新增 Handler/Runnable 生命周期管理，`onDestroy` 已做 `cancelMeterFormRetry()`；30 秒超时后若 Linux 仍未就绪则放弃，依赖重连补发路径兜底。

## 复盘与经验
- 跨芯片（Android/Linux）状态下发必须考虑"发出≠生效"，关键状态要有回读确认 + 补发，尤其恢复出厂/认证慢的场景。
- `when` 分支枚举新增值（如 0x00）时 Kotlin 不会强制穷尽非 sealed 类型，漏分支会静默卡流程。
- 档位驱动的 UI 强制分支会与形态驱动的显示逻辑打架，显示矩阵应收敛到单一决策源。
- "禁用"与"置灰"是两个语义：只禁交互时不要顺手改 alpha，除非设计如此。
