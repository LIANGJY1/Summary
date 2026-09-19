# SIR-8605 · 手车互联页面未弹出"连接方式"弹窗
- **提交**：`713192dc` | 2026-09-17 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 手车互联

## 问题
手机连接蓝牙并确认 CarPlay/HiCar 连接后进入手车互联页面，"连接方式"选择弹窗未弹出。

## 根因分析
`AppListActivity` 内置了一个自动退出机制：`mAutoCloseHandler` + `mAutoCloseRunnable`（到期执行 `requestExitToLauncher(false)` 关闭页面回到桌面）。原实现中，`CarConnectFragment.showDialog()` 在弹窗前调用 `changeAutoCloseExit(true)` 移除自动关闭回调，确认/取消后再 `changeAutoCloseExit(false)` 重启定时器。问题在于该"暂停-恢复"只覆盖了弹窗生命周期，任何其他交互（如 `layout.onDownTouch` 触发的 `startAutoCloseTimer()`）都会重新拉起定时器；用户停留在手车互联页时定时器到期触发 `requestExitToLauncher`，Activity 进入退出流程，导致 `DialogUtil.showSwitchDialog` 挂载的"连接方式"弹窗无法展示——即提交信息所述"界面关闭回调取消"（关闭回调把界面关了，弹窗被连带吞掉）。

## 关键代码修改
改动文件：AppListActivity.kt、CarConnectFragment.kt、CommonTools/BaseActivity.kt
```diff
// application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
         mBinding.layout.onDownTouch = {
-            startAutoCloseTimer()
+            if (mBinding.viewPager.currentItem == 0) {
+                startAutoCloseTimer()
+            }
         }
...
             override fun onPageSelected(position: Int) {
+                changeAutoCloseExit(position == 1)
                 updateCurrentTab(position)
             }
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
     private fun showDialog(isCarLink: Boolean = true){
         if (isCarPlayConn) {
-            changeAutoCloseExit(true)
             DialogUtil.showSwitchDialog(
...
                     override fun onConfirm() {
-                        changeAutoCloseExit(false)
                         startLinkActivity(isCarLink)
                     }
                     override fun onCancel() {
-                        changeAutoCloseExit(false)
                         LogUtils.d(TAG, "onCancel")
                     }
                 })
...
-    private fun changeAutoCloseExit(isRemove: Boolean = true){
-        (context as? AppListActivity)?.apply {
-            changeAutoCloseExit(isRemove)
-        }
-    }
```
（BaseActivity.kt 仅将 `TAG = javaClass.toString()` 改为 `javaClass.simpleName`，日志优化，与本缺陷无关）

## 为什么能修复
修复思路从"弹窗期间暂停自动关闭"改为"手车互联页干脆不自动关闭"：`onPageSelected` 里 `changeAutoCloseExit(position == 1)`——切到第 1 页（手车互联）时移除自动退出回调，切回第 0 页恢复；`onDownTouch` 也只在第 0 页重启定时器。这样自动关闭定时器在 CarConnect 页永远不会触发，`showDialog()` 不再需要自己维护暂停/恢复状态机，弹窗一定能在存活的 Activity 上展示。Fragment 到 Activity 的 `changeAutoCloseExit` 桥接方法随之删除，减少了跨层状态耦合。潜在副作用：手车互联页不再自动退出，需依赖用户手动返回，属产品可接受行为。

## 复盘与经验
- "定时器暂停/恢复"分散在多个回调（弹窗确认、取消、触摸）之间时极易漏掉某条路径，改为按页面状态集中管理（`onPageSelected` 一处决策）更稳健。
- 弹窗依赖宿主 Activity 存活：任何"弹窗未出现"类问题应先排查宿主是否已被 finish/退出动画接管。
- Fragment 通过 `(context as? AppListActivity)` 强转调用宿主私有逻辑是脆弱设计，关闭策略上收到 Activity 自身按状态处理更干净。
- 顺带的 `javaClass.simpleName` 修改说明日志 Tag 规范化常与 bugfix 混在同一提交，阅读历史时需区分主次。
