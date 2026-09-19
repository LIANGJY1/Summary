# 无单号 · 从应用列表进入应用后退出，回到列表而非桌面

- **提交**：`b3ca3f68` | 2026-08-06 | liujinfeng | Launcher | bugfix
- **缺陷库**：未关联单号

## 问题
在 Launcher 应用列表（ApplistActivity）里点击进入某个应用后，再退出该应用时回到了应用列表页面，而不是按产品预期回到桌面。

## 根因分析
`AppInfoUtils` 通过 `mContext.startActivity(intent, op.toBundle())` 拉起目标应用后，**没有销毁底下的 ApplistActivity**，它仍留在任务栈中；目标应用退出后自然回退到列表页。工程里已有专门负责退出列表的 `AppListExitRequestRouter`（单例路由，绑定 `AppListActivity.requestExitToLauncher(isNeedGoHome)`），但启动应用的路径没有调用它。顺带修复：`AppRecyclerAdapter` 图标按压动效写反——`ACTION_DOWN` 时放大到 1.1 倍、抬起回 1 倍，正确的按压反馈应是按下缩小到 0.9 倍。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/utils/AppInfoUtils.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/AppListExitRequestRouter.kt`、`application/Launcher/src/main/java/com/yadea/launcher/adapter/AppRecyclerAdapter.kt`
```diff
// application/Launcher/src/main/java/com/yadea/launcher/utils/AppInfoUtils.java
             LogUtils.d(TAG, " launch intent->" + intent);
             mContext.startActivity(intent, op.toBundle());
+            // 退出Applist页面
+            AppListExitRequestRouter.Companion.getShared().requestExitIfAvailable(false);
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/services/AppListExitRequestRouter.kt
-    fun requestExitIfAvailable(): Boolean {
+    fun requestExitIfAvailable(isNeedGoHome: Boolean = true): Boolean {
         val target = boundTarget.get() ?: return false
-        target.requestExitToLauncher(true)
+        target.requestExitToLauncher(isNeedGoHome)
         return true
     }
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/adapter/AppRecyclerAdapter.kt
-                MotionEvent.ACTION_DOWN -> view!!.animate().scaleX(1.1f).scaleY(1.1f)
+                MotionEvent.ACTION_DOWN -> view!!.animate().scaleX(0.9f).scaleY(0.9f)
                     .setDuration(100).start()
```
`requestExitToLauncher(false)` 经 `AppListActivity.playExitAnimationThenNavigate(isNeedGoHome=false)` 播完退出动画后 finish 列表页且不触发回桌面导航。

## 为什么能修复
启动应用后立即通过路由请求退出列表（`isNeedGoHome=false`，仅 finish 列表不重复回桌面），ApplistActivity 从任务栈移除，目标应用退出后 back 栈的下一层就是桌面，符合"退出该应用应回到桌面"的预期。路由新增带默认值参数保持了既有调用点（默认 true）语义不变。`requestExitToLauncher` 内部有 `isExitAnimating/isFinishing/isDestroyed` 防重入，动画期间重复调用安全。风险很小，仅注意启动失败场景下列表已被关闭，用户需重新打开。

## 复盘与经验
- 车机 Launcher 的"页面生命周期即导航栈"，跳转第三方应用时必须显式决定中间页的去留，否则退出路径会暴露残留 Activity。
- 收口式路由（Router 单例 + 目标弱引用绑定）让跨模块请求退出有统一入口，扩展参数（isNeedGoHome）比另开方法更易维护。
- 按压反馈动效的方向（放大/缩小）虽小，写反会直接造成"按下变大"的怪异手感，动效代码值得走查一遍。
