# SIR-7425 · 弹窗显示时，左滑（侧滑返回）手势把弹窗关闭了
- **提交**：`1146e9c1` | 2026-09-04 | caohongliang | CommonTools | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
弹窗展示期间，用户在屏幕边缘做左滑（系统侧滑返回）手势，弹窗被意外关闭。

## 根因分析
`BaseDialogFragment` 此前通过 `setCancelable(isEnableClickMask)` + `setCanceledOnTouchOutside(isEnableClickMask)` 依赖系统对话框的取消机制：车机系统把"侧滑返回手势"翻译成 back 事件派发给前台窗口（弹窗的 window），`onBackPressed` 默认实现触发 dialog cancel，弹窗即被关闭——缺陷库"系统手势会触发back事件"与代码对应。另外遮罩关闭用裸 `setOnFastClickListener`，滑动进入遮罩区松手也会被当成单击误关。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt`

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
+    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
+        return object : ComponentDialog(requireContext(), theme) {
+            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
+            override fun onBackPressed() {
+                // 消费系统返回键和侧滑返回，不调用父类，避免弹窗被关闭。
+                Log.d(this@BaseDialogFragment.TAG, "System back ignored for dialog")
+            }
+        }
+    }
```

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
-            setCancelable(isEnableClickMask)
-            setCanceledOnTouchOutside(isEnableClickMask)
+            // 外部单击由遮罩识别，禁止系统按窗口外松手或返回手势直接取消。
+            setCancelable(false)
+            setCanceledOnTouchOutside(false)
```

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
+internal fun View.filterMaskTap(contentView: View) {
+    val contentBounds = RectF()
+    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
+        override fun onSingleTapUp(event: MotionEvent): Boolean {
+            // 使用实时边界，兼容键盘避让后的内容位移；滑入内容区也不能关闭。
+            contentBounds.set(0f, 0f, contentView.width.toFloat(), contentView.height.toFloat())
+            contentView.matrix.mapRect(contentBounds)
+            contentBounds.offset(contentView.left.toFloat(), contentView.top.toFloat())
+            if (... !contentBounds.contains(event.x, event.y)) {
+                performClick()
+            }
+            return true
+        }
+    })
+    setOnTouchListener { _, event ->
+        detector.onTouchEvent(event)
+        true
+    }
+}
```

（遮罩 `wrapperLayout` 的关闭改为 `if (isEnableClickMask)` 守卫 + `filterMaskTap(view)` 用 `GestureDetector` 区分单击与滑动，只对内容区外且非滑动的单击执行 `performClick()`（即原 safeDismiss 回调）。）

## 为什么能修复
自定义 `ComponentDialog` 重写 `onBackPressed()` 为空实现，back 事件（含系统转化的侧滑手势）被消费不再 cancel 弹窗；`setCancelable(false)` 双保险禁用 back 取消通道；遮罩关闭从"任何 down/up 都算点击"改为 GestureDetector 识别的纯单击 + 实时内容边界判定（经 `matrix.mapRect` 兼容键盘避让后的位移），滑动/误触不再关闭。副作用：弹窗完全屏蔽返回键，若某些弹窗产品上需要 back 关闭，需要新增显式配置项。

## 复盘与经验
- 车机/全面屏设备上"侧滑手势= back 事件"，弹窗若依赖系统 cancel 机制就会被手势关闭；需要弹窗期间屏蔽手势时，自定义 Dialog 重写 `onBackPressed` 消费事件是标准做法。
- DialogFragment 推荐在 `onCreateDialog` 返回自定义 Dialog 来接管按键行为，而不是在 Activity 层拦截——公共组件一次性解决所有弹窗。
- 自己实现"点遮罩关闭"时必须区分点击与滑动（GestureDetector）并用实时边界判断，否则键盘避让位移、滑动落点都会造成误关。
- 与 `fa35b1f8`（同一 `BaseDialogFragment` 的键盘避让修复）呼应：公共弹窗基类是车机交互问题的集中修复点，改一处全局生效。
