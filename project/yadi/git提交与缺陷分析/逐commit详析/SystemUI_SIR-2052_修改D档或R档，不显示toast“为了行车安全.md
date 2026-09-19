# SIR-2052 · D/R 档点击音乐未弹出"请在 P 挡下打开应用"提示

- **提交**：`5057a57e` | 2026-07-07 | duanlonglong | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
D 档或 R 档点击导航栏音乐入口时，看不到"为了行车安全，请在 P 挡下打开应用"的 toast 提示。

## 根因分析
`NavBarFragment.java` 在非 P 档拦截音乐入口后调用的是 `ToastUtils.showMsgToast()`——普通 `Toast.makeText` 式提示**只显示在安卓主屏**。而 D/R 档行车场景下驾驶员视线在仪表屏（簇屏），主屏 toast 等于没提示；缺陷库 rc 即"Toast 未显示到仪表屏上导致"。本质是提示通道与用户视线所在屏幕不匹配：单屏应用思维直接搬到双屏（主屏+仪表）座舱，提示仍走默认 Display。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java`（1 行）、`component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt`（+90，新增仪表屏 toast 通道）
```diff
// --- NavBarFragment.java 非 P 档拦截处
-                    ToastUtils.INSTANCE.showMsgToast(requireContext(), getString(R.string.media_enter_toast));
+                    ToastUtils.INSTANCE.showMsgICToast(requireContext(), getString(R.string.media_enter_toast));
```
```diff
// --- component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt 新增
+    private const val TARGET_DISPLAY_ID = 2
+    /**
+     * 通过WindowManager添加View的方式显示Toast，层级提升到仪表上方(type=2038)，显示在Display ID 2上，2秒后自动移除
+     */
+    fun showMsgICToast(context: Context, message: String) {
+        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
+        val targetDisplay = displayManager.getDisplay(TARGET_DISPLAY_ID)
+        val displayContext = if (targetDisplay != null) context.createDisplayContext(targetDisplay) else context
+        val windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
+        MAIN_HANDLER.post {
+            // 移除上一次显示的Toast，确保只显示最新的一条
+            ...
+            val params = WindowManager.LayoutParams().apply {
+                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
+                flags = FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN
+                gravity = Gravity.CENTER
+            }
+            windowManager.addView(toastView, params)
+            MAIN_HANDLER.postDelayed(removeRunnable, TOAST_DISPLAY_DURATION_MS)
+        }
+    }
```

## 为什么能修复
新通道绕开系统 Toast，改用 `DisplayManager.getDisplay(2)` + `createDisplayContext()` 拿到仪表屏的 WindowManager，以 `TYPE_APPLICATION_OVERLAY`(2038) 挂自定义 toast 视图并 2 秒自动移除，提示直接出现在仪表屏上。隐患：Display ID 硬编码为 2，屏序变化/机型差异即失效（有 fallback 但会退回主屏旧问题）；`TYPE_APPLICATION_OVERLAY` 需要悬浮窗权限且层级压过仪表内容，多处同时调用只保留最新一条（旧视图被主动 removeView），并发安全性依赖主线程串行。

## 复盘与经验
- **多屏座舱里"提示"必须指明目标屏**：toast/弹窗封装 API 应带 display 参数，`showMsgToast`/`showMsgICToast` 的分化正是缺统一"目标屏"抽象的表现。
- **系统 Toast 不支持跨屏指定 Display**，跨屏提示只能走 WindowManager + displayContext 自绘，注意权限、类型与移除时机的管理。
- 硬编码 Display ID 是座舱 Android 常见隐患，宜从配置/资源读取。
