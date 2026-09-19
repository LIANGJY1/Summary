# SIR-7359 · WiFi 输密码弹窗背景色上顶盖住状态栏
- **提交**：`fa35b1f8` | 2026-09-04 | caohongliang | Setting/CommonTools | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
从控制中心触发 WiFi 输入密码弹窗，键盘弹出时整个弹窗（含铺满屏幕的半透明遮罩背景）被键盘顶起，遮罩上移后盖住状态栏，视觉异常。

## 根因分析
`BaseDialogFragment` 的弹窗窗口（`wrapperLayout` 全屏遮罩 + 内容卡片）此前依赖窗口级 `softInputMode` 的平移模式（adjustResize/adjustPan 类）做键盘避让：键盘弹出时系统把**整个 dialog window**（包括作为"背景"的遮罩层）一起平移/压缩，遮罩随之越过状态栏区域。根因即提交说明的"键盘弹出属性导致"——避让粒度错在窗口级，而设计要求只有内容卡片避让、遮罩背景保持全屏固定。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt`（核心）、`.../dialog/EditDialog.kt`、`application/Setting/.../DeviceNameEditDialogFragment.kt`、`.../HotspotPwdEditDialogFragment.kt`、`.../WlanCustomEditDialogFragment.kt`

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
+    protected fun keepBackgroundFixedOnIme(contentView: View, inputView: View) {
+        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
+        val wrapper = wrapperLayout ?: return
+        val updateTranslation = update@{ windowInsets: WindowInsetsCompat? ->
+            if (isClosing || wrapperLayout !== wrapper) return@update
+            val visibleFrame = Rect()
+            wrapper.getWindowVisibleDisplayFrame(visibleFrame)
+            ...
+            val imeBottom = windowInsets
+                ?.takeIf { it.isVisible(WindowInsetsCompat.Type.ime()) }
+                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
+            ...
+            if (contentView.translationY != translation) {
+                contentView.translationY = translation
+            }
+        }
+        ViewCompat.setOnApplyWindowInsetsListener(wrapper) { _, insets ->
+            updateTranslation(insets); insets
+        }
+        wrapper.viewTreeObserver.addOnGlobalLayoutListener(listener)
+    }
```

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
+    override fun dismiss() {
+        freezeImeOffsetOnClose()
+        super.dismiss()
+    }
+    // dismissAllowingStateLoss / onCancel / onDestroyView 同样先 freeze 再走原逻辑，
+    // onDestroyView 中移除 OnGlobalLayoutListener
+
+internal fun calculateImeTranslation(inputBottom: Float, visibleBottom: Int, spacing: Int): Float {
+    return -(inputBottom + spacing - visibleBottom).coerceAtLeast(0f)
+}
```

（三个 Setting 弹窗与 `EditDialog` 在 `onViewCreated` 中调用 `keepBackgroundFixedOnIme(mBinding.root, mBinding.etContent)` 启用新避让。）

## 为什么能修复
把窗口 `softInputMode` 设为 `SOFT_INPUT_ADJUST_NOTHING`，系统不再平移整个 window（遮罩固定全屏、状态栏不被盖）；避让改为自研：监听 `WindowInsets`（ime 可见高度）+ `OnGlobalLayout`，用 `calculateImeTranslation` 计算输入框底边与可见区域底边的差值，只对内容卡片设 `translationY` 负向偏移。`isClosing` 冻结标志避免键盘先收起导致卡片复位闪动，`onDestroyView` 移除监听防泄漏。隐患：`IME_UPDATE_DELAY_MS = 400L` 的延迟补偿依赖经验值，个别输入法动画慢时可能有一帧错位；自研避让需覆盖多输入法差异。

## 复盘与经验
- 带"全屏遮罩"的对话框遇到键盘避让问题时，窗口级 adjust 参数（resize/pan）一定会把遮罩一起挪；正确姿势是 `SOFT_INPUT_ADJUST_NOTHING` + 仅对内容视图做 `translationY` 避让。
- 现代 Android 优先用 `WindowInsetsCompat.Type.ime()` 获取键盘高度，配合 `getWindowVisibleDisplayFrame` 兜底，兼顾新旧系统。
- DialogFragment 生命周期（dismiss/onCancel/onDestroyView）与动画/监听的交互要专门处理：关闭期间冻结避让计算、销毁时反注册 GlobalLayoutListener，否则会出现闪动与泄漏。
- 该修复沉淀在公共组件 `BaseDialogFragment`，一处实现、四处（三个 Setting 弹窗 + EditDialog）受益，公共组件是此类横切问题的正确修复位置。
