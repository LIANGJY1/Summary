# SIR-6613 · 快速多次点击 dock 栏 AppList 按钮，dock 栏黑化一段时间

- **提交**：`a95ecec9` | 2026-08-26 | liujinfeng | BTPhone/SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
快速连续点击 dock 栏的 AppList 按钮，dock 栏会进入异常状态（黑化）一段时间才恢复。

## 根因分析
SystemUI 的 `ActivityManagerService` 监听任务栈变化来维护"当前前台任务"，进而驱动 `NavBarFragment` 的显示状态。快速点 AppList 时序为：新 Applist 任务启动（`onTaskMovedToFront`/`onTaskStackChanged`）→ 旧 Applist 任务被移除，且移除事件是**延迟异步**到达的。旧代码在 `onTaskRemoved(taskId)` 里无条件 `dispatchTopTaskIfNeeded("onTaskRemoved")` 重新查询/分发前台任务——旧任务的延迟删除事件到达时，新任务其实已是前台，这次"多余"的重新分发以旧的栈快照为依据，把 dock 栏的前台页面判断**错误覆盖**为"无前台/旧任务"状态（缺陷库：旧 Applist 任务的延迟删除事件错误覆盖了新任务的 Dock 状态），dock 栏据此渲染为黑化异常态。核心缺陷是：任务删除处理只看"有任务被删"这一事件，没有校验"被删的是不是当前记录的那个任务"。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/activity/ActivityManagerService.java`、`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java`

```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/activity/ActivityManagerService.java
@@ -36,6 +36,7 @@ public class ActivityManagerService extends BaseManager {
     private String lastPkg;
     private String lastClassName;
+    private volatile int lastTaskId = -1;
@@ -104,6 +105,7 @@
+                lastTaskId = taskInfo.taskId;
                 handleTopTaskChanged(topActivity.getPackageName(), topActivity.getClassName(),
                         taskInfo.displayId, "onTaskMovedToFront");
@@ -113,7 +115,9 @@
         public void onTaskRemoved(int taskId) throws RemoteException {
             super.onTaskRemoved(taskId);
             LogUtils.d(TAG, "onTaskRemoved: taskId=" + taskId);
-            dispatchTopTaskIfNeeded("onTaskRemoved");
+            if (taskId == lastTaskId) {
+                dispatchTopTaskIfNeeded("onTaskRemoved");
+            }
         }
```

（`dispatchTopTaskIfNeeded` 等前台更新路径同步 `lastTaskId = taskInfo.taskId`；`NavBarFragment.onClick` 给 map/home/music/setting/moreApp 五个 dock 按钮统一加 `ViewUtilsKt.isInvalidClick(clMenu, 500)` 的 500ms 防抖。）

## 为什么能修复
新增 `volatile int lastTaskId` 记录最近一次确认的前台任务 id，凡 `onTaskMovedToFront`、`dispatchTopTaskIfNeeded` 等更新前台时同步刷新；`onTaskRemoved` 只在 `taskId == lastTaskId`（被删的确实是当前记录的任务）时才重新查询分发前台。旧 Applist 任务的延迟删除事件到达时，`lastTaskId` 已被新任务覆盖、id 不相等，错误的重新分发被直接忽略，dock 栏状态不再被旧事件污染。`volatile` 保证 binder 线程与主线程间的可见性。辅助的 500ms 点击防抖从入口侧进一步降低连点造成的任务抖动。隐患：若存在"删除当前任务但不触发 movedToFront"的正常路径，过滤后可能少一次刷新，需依赖其他任务栈事件兜底（代码注释显示兜底逻辑存在）。

## 复盘与经验
- 异步事件驱动的"当前状态"必须带身份校验：收到"XX 被移除"事件时先比对 id 再更新状态，否则过期的旧事件会覆盖新状态——这是竞态类 UI 异常（黑化、闪烁、状态错乱）的通用解法。
- 快速连点按钮是对一切任务栈监听逻辑的极限压力测试，"偶现才修"不如入口防抖（500ms `isInvalidClick`）+ 事件幂等（id 比对）双管齐下。
- 跨线程（binder 回调 → UI 状态）共享的游标变量要 `volatile`，否则比对该变量本身也可能读到旧值，修复失效。
