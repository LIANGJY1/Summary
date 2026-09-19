# SIR-8364 · 切换弹窗倒计时期间应用列表被自动关闭（mistag 标记）
- **提交**：`ab5e787f` | 2026-09-14 | dufan | Launcher | bugfix（提交头为 feature、mistag=true，按 diff 实际为缺陷修复）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
已连接 Carplay 时，在手车互联页点击切换 Carlink，弹出二次确认弹窗等待；弹窗倒计时尚未结束，应用列表页（AppList）自己先倒计时自动退出，页面被错误带离。

## 根因分析
`AppListActivity` 自带无操作自动关闭定时器（`mAutoCloseHandler` + `mAutoCloseRunnable`，由 `startAutoCloseTimer()` 启动）。而 `CarConnectFragment` 点击 `layoutCarlink`/`layoutHicar` 弹出 `DialogUtil.showSwitchDialog`（内部 `CountDownTimer` 11 秒）期间，**两个倒计时并行运行且互不感知**：用户停在弹窗上无操作，AppList 的 auto-close 定时器到点触发退出，把正在等弹窗的用户直接带走。同时 `DialogUtil` 的倒计时 `onFinish` 分支只调 `dismissDialog()` 清引用、没有真正 `safeDismiss()` 弹窗，且弹窗被外部途径关闭时（dismiss 路径）不会回调 `onCancel`，调用方无从恢复定时器——状态机在"弹窗等待"这一态缺少对宿主页面自动关闭的暂停/恢复。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt；application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt；application/Launcher/src/main/java/com/yadea/launcher/utils/DialogUtil.kt
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
+++ b/application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
@@ +    private fun showDialog(isCarLink: Boolean = true){
+        if (isCarPlayConn) {
+            changeAutoCloseExit(true)
+            DialogUtil.showSwitchDialog(
+                requireContext(),
+                childFragmentManager,
+                object : OnSwitchConfirmListener {
+                    override fun onConfirm() {
+                        changeAutoCloseExit(false)
+                        startLinkActivity(isCarLink)
+                    }
+
+                    override fun onCancel() {
+                        changeAutoCloseExit(false)
+                        LogUtils.d(TAG, "onCancel")
+                    }
+                })
+        } else {
+            startLinkActivity(isCarLink)
+        }
+    }
+
+    private fun changeAutoCloseExit(isRemove: Boolean = true){
+        (context as? AppListActivity)?.apply {
+            changeAutoCloseExit(isRemove)
+        }
+    }
```
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/utils/DialogUtil.kt
+++ b/application/Launcher/src/main/java/com/yadea/launcher/utils/DialogUtil.kt
@@ +        })?.setOnDismissListener(object : DismissListener {
+            override fun onDismiss() {
+                mSwitchDialog?.safeDismiss()
+                if (mListener != null) {
+                    mListener!!.onCancel()
+                }
+                dismissDialog()
+            }
+        })?.show(childFragmentManager, "ConnectHintDialog")
@@             override fun onFinish() {
                 if (mSwitchDialog != null && mSwitchDialog?.isVisible == true) {
-                    dismissDialog()
+                    mSwitchDialog?.safeDismiss()
                     if (mListener != null) {
                         mListener!!.onCancel()
                     }
+                    dismissDialog()
                 }
             }
```
（`AppListActivity` 新增公开入口 `changeAutoCloseExit(isRemove)`：true 走 `mAutoCloseHandler?.removeCallbacks`，false 调 `startAutoCloseTimer()` 重启。）

## 为什么能修复
弹弹出前 `changeAutoCloseExit(true)` 摘掉 AppList 自动关闭任务，弹窗存续期间宿主页不再倒计时退出；confirm/cancel 两条出口都 `changeAutoCloseExit(false)` 重启定时器，交互结束后恢复无人操作自动关闭能力。`DialogUtil` 补齐 `OnDismissListener` 与 `onFinish` 中的 `safeDismiss()`，保证倒计时到点、外部 dismiss 等所有关闭路径都会走到 `onCancel`，从而必定恢复定时器，不出现"暂停后永不恢复"。隐患：若弹窗进程异常未触发任何回调，定时器停在暂停态，AppList 将不再自动关闭（表现为停留，影响轻微）。

## 复盘与经验
- 页面级"无操作自动退出"定时器与模态弹窗天然冲突：弹窗展示期必须暂停，退出弹窗必须恢复，且恢复要覆盖所有 dismiss 路径。
- 弹窗工具类应保证"任何关闭路径都回调一次结果"，否则调用方的伴生状态（这里是定时器）会失配。
- 两个独立倒计时共存时，先画出谁该在什么状态下运行，再补状态切换钩子。
