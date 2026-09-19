# SIR-7317 · 触发自定义按键单击导致"座椅&手把"页面返回顶部
- **提交**：`8c4fb73b` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：怀疑 DVR 服务后台弹 toast 导致界面重建 → 修改自定义 scrollview）

## 问题
触发方向盘自定义按键的单击效果后，"控制-座椅&手把"页面滚动位置被重置回顶部；类似的，DVR 服务在页面后台不可见时的回调也会引发同一现象。

## 根因分析
双因子叠加：
1. **UI 层**：`SafetyMonitorFragment` 所在 ViewPager 的缓存页在不可见（非 RESUMED）状态下收到 DVR 回调（onConnect / usb_memory / 文件数据）就直接执行 `setDashcamGray`、`refreshDvrStates`、`safeUpdateStorageBar` 等 UI 刷新。不可见页面的刷新会触发容器重排（提交注释原话："不可见页面（ViewPager 缓存页）刷新 UI 会触发容器重排，导致当前可见页面滚动位置被重置"）——DVR 服务后台的活跃回调（如弹 toast 拉起界面引起 pause/resume）放大了这一路径。
2. **容器层**：自定义键单击使页面焦点丢失时，触摸序列收不到 ACTION_UP，`SmartNestedScrollView.userScrollAllowed` 残留为 true，同时系统 relayout（如 UsbHostManagementActivity 被拉起）直接把 `mScrollY` 归零，上一版控件没有恢复机制，页面就停在顶部。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt、application/Setting/src/main/java/com/yadea/setting/ui/widget/SmartNestedScrollView.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
@@ -63,6 +63,24 @@
+    private var pendingUiRefresh = false
+
+    /**
+     * 页面可见（RESUMED）时才执行 UI 刷新；不可见时只更新数据并标记待刷新。
+     */
+    private fun applyWhenVisible(refresh: () -> Unit) {
+        if (view != null && isResumed) {
+            pendingUiRefresh = false
+            refresh()
+        } else {
+            pendingUiRefresh = true
+        }
+    }
@@ -371,8 +390,10 @@
         override fun onConnect() {
             dvrConnected = true
-            setDashcamGray(true)
-            refreshDvrStates()
+            applyWhenVisible {
+                setDashcamGray(true)
+                refreshDvrStates()
+            }
             getVideoData()
         }
--- application/Setting/src/main/java/com/yadea/setting/ui/widget/SmartNestedScrollView.kt
@@ -28,19 +28,35 @@
+    private var lastUserScrollY = 0
+    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
+        super.onScrollChanged(l, t, oldl, oldt)
+        // 仅用户手动滚动到非顶部位置时记录；t==0 不清零，避免 relayout 重置时误清恢复基准
+        if (userScrollAllowed && t > 0) {
+            lastUserScrollY = t
+        }
+    }
+    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
+        super.onLayout(changed, l, t, r, b)
+        if (scrollLock) return
+        // NestedScrollView 在重排/relayout ... 时可能直接把 mScrollY 重置为 0。
+        // 此时 userScrollAllowed 可能残留 true（焦点丢失未收到 UP），但该归零不是用户意图，仍须恢复。
+        if (scrollY == 0 && lastUserScrollY > 0) {
+            val child = getChildAt(0)
+            val height = b - t - paddingBottom - paddingTop
+            val scrollRange = if (child != null) (child.height - height).coerceAtLeast(0) else 0
+            if (scrollRange >= lastUserScrollY) {
+                scrollToProgrammatically(lastUserScrollY)
+            }
+        }
+    }
```
（此外 onResume 中检查 `pendingUiRefresh` 统一补刷；SafetyMonitorFragment 中所有 DVR 回调 UI 刷新点都包上 `applyWhenVisible`。）

## 为什么能修复
两层各堵一个口子：Fragment 层把"不可见期间刷 UI"改为"记数据、resume 后统一补刷"，从源头减少触发容器重排的次数；控件层增加 `lastUserScrollY` 恢复基准并在 `onLayout` 里检测"非用户意图的归零"后同帧恢复，即使重排发生也不再丢位置。隐患：onLayout 恢复假设"scrollY==0 且 lastUserScrollY>0 必然是重置"，用户真想滚回顶部时需要一次触摸序列把基准清零（UP 时 scrollY==0 才清），边界上可能有一次"弹回"手感；恢复同帧执行避免了闪烁。

## 复盘经验
- ViewPager 缓存页在不可见时刷 UI 是隐形炸弹：任何回调驱动 UI 的页面都应有 `applyWhenVisible` 式的门卫 + onResume 补刷。
- "焦点丢失吃掉 ACTION_UP"会让手势状态机残留脏标志，触摸状态设计时要考虑 UP/CANCEL 之外的异常退出路径。
- 防回顶控件不能只靠拦截 scrollTo——系统可以直接改 mScrollY，onLayout 兜底恢复才是完整解。
