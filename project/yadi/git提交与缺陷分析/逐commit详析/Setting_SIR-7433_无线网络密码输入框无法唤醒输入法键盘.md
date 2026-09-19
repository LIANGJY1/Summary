# SIR-7433 · [偶发]无线网络密码输入框无法唤醒输入法键盘
- **提交**：`8db46784` | 2026-09-05 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 输入法

## 问题
弹出的无线网络（WiFi 密码/热点密码）输入对话框偶发不拉起软键盘，用户必须手动点输入框才能输入。

## 根因分析
两个对话框 `HotspotPwdEditDialogFragment`、`WlanCustomEditDialogFragment` 原来都在 `onViewCreated` 里用固定延时拉起键盘：`mBinding.etContent.postDelayed({...requestFocus(); imm.showSoftInput(etContent, SHOW_IMPLICIT)}, 200)`。问题有二：其一，200ms 是拍脑袋定值——偶现场景下（弹窗动画未完成、窗口焦点尚未建立、DialogFragment 尚未完成 attach 到窗口），到点执行时 `showSoftInput` 的前提（View 已 focus 且窗口具有 IME 焦点）不成立，请求被 InputMethodManager 静默拒绝；其二，代码完全不看 `showSoftInput()` 的布尔返回值，失败后没有任何补救，于是表现为低概率"键盘不弹"。修复双管齐下：① 在 `onStart()` 给 Dialog 的 window 设置 `setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_VISIBLE or SOFT_INPUT_ADJUST_NOTHING)`，由 WindowSoftInputMode 在窗口层面声明"创建即显示 IME"，不再依赖应用层时序；② 把延时逻辑抽成 `showKeyboard(retryCount)`：`post`（下一帧、视图就绪后）先 `requestFocus()`，检查 `showSoftInput` 返回值，失败则在 100ms 后重试 1 次，同时保留 `isAdded || isDetached` 的生命周期防护。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotPwdEditDialogFragment.kt；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanCustomEditDialogFragment.kt
```diff
--- application/Setting/.../diologfragment/HotspotPwdEditDialogFragment.kt（WlanCustomEditDialogFragment 同构）
     override fun onStart() {
         super.onStart()
-        dialog?.setOnDismissListener {
-            mDismissListener?.onDismiss()
+        dialog?.run {
+            setOnDismissListener {
+                mDismissListener?.onDismiss()
+            }
+            window?.setSoftInputMode(
+                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
+                    or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
+            )
         }
     }
...
-        mBinding.etContent.postDelayed({
-            if (!isAdded || isDetached) return@postDelayed
-            mBinding.etContent.requestFocus()
-            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
-            imm.showSoftInput(mBinding.etContent, InputMethodManager.SHOW_IMPLICIT)
-        }, 200)
+        showKeyboard()
+    }
+
+    private fun showKeyboard(retryCount: Int = 1) {
+        mBinding.etContent.post {
+            if (!isAdded || isDetached) return@post
+            mBinding.etContent.requestFocus()
+            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
+            if (!imm.showSoftInput(mBinding.etContent, InputMethodManager.SHOW_IMPLICIT) && retryCount > 0) {
+                mBinding.etContent.postDelayed({ showKeyboard(retryCount - 1) }, 100)
+            }
+        }
+    }
```

## 为什么能修复
`SOFT_INPUT_STATE_ALWAYS_VISIBLE` 把"要显示键盘"声明进窗口属性，窗口创建时由系统侧保证 IME 拉起，绕开了"应用层定时请求撞上窗口未就绪"的竞态；`showSoftInput` 返回值检查 + 一次重试则兜住残余的时序抖动。两者叠加后偶发概率被消除。副作用：`ADJUST_NOTHING` 意味着键盘弹出时不平移/压缩对话框，若输入框位于屏幕下半部可能被键盘遮挡（车机横屏大屏下通常无碍）；重试仅 1 次，极端情况仍可能失败，但已从"无反馈"变为"可观测"。

## 复盘与经验
- 用固定 `postDelayed` 等 UI 就绪是偶现 bug 的头号来源——延时竞态只在高负载/首次启动时暴露，测试难以覆盖；正确姿势是"事件驱动（post 到视图就绪）+ 结果校验 + 有限重试"。
- `InputMethodManager.showSoftInput()` 有布尔返回值，绝大多数"键盘不弹"问题都出在忽略返回值的静默失败上。
- Dialog 场景想自动弹键盘，`window.setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_VISIBLE)` 是比应用层延时请求更可靠的系统级通道。
