# SIR-6453 · hicar+蓝牙双连状态下切换其他设备蓝牙，断开后新设备不连

- **提交**：`2b625449` | 2026-08-26 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
设备已同时连接 hicar 和蓝牙，此时切换连接其他设备的蓝牙：本机蓝牙被断开，但新设备的连接流程没有继续，表现为"断开了旧连接、却不连新设备"。

## 根因分析
`BluetoothUtil.kt` 的设备切换逻辑运行在非主线程（蓝牙切换/连接处理线程）。当需要弹提示（`ToastUtils.showMsgToast`）或弹确认框（`showConnectHintDialog(childFragmentManager, hint, callback)`）时，代码直接在当前子线程调用。Android 严格规定 Toast 的创建以及 `FragmentManager` 的 DialogFragment 事务必须在主线程（Looper）执行，否则抛出 `RuntimeException`（如 "Can't create handler inside thread that has not called Looper.prepare()" / FragmentManager 的 "Must be called from main thread"）。缺陷库写明"子线程更新UI异常"。异常抛出后，切换流程的后续 `callback.invoke()` / 连接新设备的代码被中断，于是出现"旧连接已断、新连接未发"的半完成状态——hicar+蓝牙双连时必现，正是因为该组合恰好走进了带弹窗提示的分支。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt`

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -94,10 +94,12 @@ class BluetoothUtil {
                     } else {
-                        ToastUtils.showMsgToast(
-                            MyApplication.myApplication!!,
-                            getString(R.string.click_bluetooth_hint)
-                        )
+                        CoroutineScope(Dispatchers.Main).launch {
+                            ToastUtils.showMsgToast(
+                                MyApplication.myApplication!!,
+                                getString(R.string.click_bluetooth_hint)
+                            )
+                        }
                     }
```

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -116,11 +118,13 @@ class BluetoothUtil {
-                                showConnectHintDialog(
-                                    childFragmentManager,
-                                    hint,
-                                    callback
-                                )
+                                CoroutineScope(Dispatchers.Main).launch {
+                                    showConnectHintDialog(
+                                        childFragmentManager,
+                                        hint,
+                                        callback
+                                    )
+                                }
                                 return
```

（第三处 `showConnectHintDialog` 同样包进 `CoroutineScope(Dispatchers.Main).launch`。）

## 为什么能修复
三处 UI 调用全部切到 `Dispatchers.Main` 协程中执行，Toast 与 DialogFragment 事务回到主线程，异常不再抛出，切换流程得以继续走到连接新设备的分支。注意两点隐患：一是 `CoroutineScope(Dispatchers.Main).launch` 创建的是无生命周期管理的裸作用域，弹窗回调 `callback` 是异步回到原线程语义之外执行的，若期间设备断开/页面销毁需自行防重入；二是 `return` 仍在子线程原逻辑中立即生效，协程只是"异步发 UI"，流程控制未被打乱——这是本次写法正确的原因。更规范的替代是 `withContext(Dispatchers.Main)`（挂起而非另起协程）或封装主线程 Handler 工具。

## 复盘与经验
- 蓝牙/网络回调线程里调 UI 是车机应用崩溃的经典来源：Toast、Dialog、FragmentManager、View 操作全部要求主线程，凡是后台线程路径里的 UI 调用都应统一走主线程分发工具，而不是逐处临时包协程。
- "断开了但不连新设备"这类半完成状态，多半是流程中段抛异常被截断——排查时先看 logcat 有无线程异常，再顺着异常点看流程被切断在哪。
- 双连接组合场景（hicar+蓝牙）往往是唯一走到某弹窗分支的路径，这解释了"为什么只有这个组合复现"：用例设计应覆盖所有"需要弹提示"的连接组合。
