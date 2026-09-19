# SIR-6244 · carplay来电挂断后来电弹窗残留

- **提交**：`b8c2a8fe` | 2026-08-24 | ljl | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联（元数据 rc/sol 为 [image]，以 diff 实际为准）

## 问题
CarPlay 已连接状态下，来电挂断后，车机上的来电弹窗没有消失，残留在屏幕上。

## 根因分析
`CarPlayCallWindow`（BTPhone 的 CarPlay 来电悬浮窗，基于 `WindowManager.addView/removeView`）的 show/hide 存在竞态与误判：
① 旧 `hide()` 以 `mRootView.isAttachedToWindow()` 为前置条件才执行 `removeView`。但 View 存在"半附着"状态——`addView` 已把窗口提交给 WMS、View 的 attach 尚未完成，此时 `isAttachedToWindow()` 返回 false，`removeView` 被跳过，窗口在 WMS 里仍然存活且可见，来电弹窗就残留了（挂断时恰好处于该状态窗口）。
② 旧 `show()` 在 `mShowing` 标志与实际窗口状态不同步时（hide 刚把 `mShowing` 置 false 但 view 仍 attach），再次 `addView` 会抛 `IllegalStateException`，show 流程中断，`render` 之后的状态维护也被打乱。缺陷库 rc/sol 仅填 [image]（截图说明），以代码实际为准：这是典型的窗口标志位与 WMS 真实状态漂移问题。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java（+20/-4）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java
@@ show()
             ensureView();
             render(callMap);
+            mRootView.setVisibility(View.VISIBLE);
             if (!mShowing) {
-                mWindowManager.addView(mRootView, mLayoutParams);
+                try {
+                    mWindowManager.addView(mRootView, mLayoutParams);
+                } catch (IllegalStateException e) {
+                    // 处理 hide() 与 addView 的竞态：view 可能仍 attach 在窗口上（但已 GONE），
+                    // 此时复用现有窗口即可，不必抛异常
+                    if (mRootView.isAttachedToWindow()) {
+                        LogUtils.w(TAG, "show: view already attached, reuse existing window");
+                    } else {
+                        throw e;
+                    }
+                }
                 mShowing = true;
@@ hide()
-            if (mRootView.isAttachedToWindow()) {
-                mWindowManager.removeView(mRootView);
-            }
+            // 先设 GONE 确保视觉立即消失，解决 ViewRoot 未就绪时 removeView 不可靠的问题
+            mRootView.setVisibility(View.GONE);
+            // 去掉 isAttachedToWindow() 前置判断：半附着状态下该标志可能为 false，
+            // 但窗口实际已添加到 WMS；直接 removeView 并 catch 异常即可
+            mWindowManager.removeView(mRootView);
+        } catch (IllegalArgumentException e) {
+            // view 尚未真正 attach 到窗口，忽略即可；视觉已 GONE，不会残留
+            LogUtils.d(TAG, "hide: view not attached yet, ignore");
```

## 为什么能修复
`hide()` 不再信任 `isAttachedToWindow()`，无条件 `removeView` 并用 `IllegalArgumentException` 兜底"确实没加上"的情况，保证 WMS 侧窗口必然被移除；同时先 `setVisibility(GONE)`，即使 remove 走异常分支视觉上也立即消失，双保险消除残留。`show()` 捕获竞态异常改为复用已 attach 的窗口，保证挂断后再来电仍能正常弹出。隐患：异常兜底依赖系统 `addView/removeView` 的异常语义（IllegalStateException/IllegalArgumentException），跨 API 版本需留意；`mShowing` 标志仍可能与真实状态漂移，只是现在不再造成可见故障。

## 复盘与经验
- `isAttachedToWindow()` 描述的是 View 视图树的附着状态，不等于"窗口已从 WMS 移除"，拿它做 `removeView` 的前置条件是误用——remove 直接调用并捕获异常更可靠。
- WindowManager 悬浮窗的 show/hide 要设计成幂等且可重入：先 GONE 保视觉、再 removeView 保资源、异常按"已达标"处理。
- 挂断（hide）与来电（show）在来去电切换时高频竞态，窗口类组件的竞态用例应纳入测试范围（本单影响域"手车互联"必现）。
