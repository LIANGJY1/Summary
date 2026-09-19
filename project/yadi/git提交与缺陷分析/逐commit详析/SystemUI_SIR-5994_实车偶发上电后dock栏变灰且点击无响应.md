# SIR-5994 · 实车偶发上电后dock栏变灰且点击无响应

- **提交**：`ac3b8da8` | 2026-08-20 | liujinfeng | SystemUI/Carlib | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
实车偶发上电后 SystemUI 的 dock 栏整体变灰、点击无响应，SystemUI 表现类似卡死，需重启恢复。

## 根因分析
缺陷库根因："主线程调用CreateCar方法，该方法为同步返回，会导致主线程阻塞"。`CarConnectionManager`（component/Carlib）原来在初始化路径上直接同步调用 `Car.createCar(...)`——上电时车服务未就绪，该同步 Binder 调用会长时间挂起主线程，期间 SystemUI 主线程无法处理输入和绘制，dock 栏呈灰态且点击无响应。同理 `SystemUIApplication` 的 `onLifecycleChanged(isReady=true)` 回调里在主线程连续执行 `getCarManager(CAR_UX_RESTRICTION_SERVICE)` 等同步 Binder 调用并初始化通知组件，进一步加剧阻塞。修复方案：`createCar` 移到专用单线程 `CarConnectionWorker` 执行（`createCarAsync`），生命周期事件在 `createCar` 返回后再派发到主线程（注释明确"避免回调内的 getCarManager 抢占 Car 内部锁"），加看门狗重连与 `mReleased`/`mCreateCarInProgress` 状态防在途回调；`SystemUIApplication` 侧新增 `initializeCarFeaturesAsync`，把 Car manager 获取放 `SystemUICarInit` 后台线程，仅把 UI 对象初始化 post 回主线程，并用 `mCarFeatureGeneration` 代数丢弃断连/重连后过期的初始化结果。

## 关键代码修改
改动文件：CarConnectionManager.kt、SystemUIApplication.kt、NavBarFragment.java、CarAudioVolumeController.kt（4 文件，+331/-136）
```diff
@@ component/Carlib/src/main/java/com/neusoft/libcar/manager/CarConnectionManager.kt @@
+    private fun createCarAsync(context: Context, successLogPrefix: String) {
+        mConnectionExecutor.execute {
+            try {
+                Car.createCar(context, mHandler, CONNECTION_TIMEOUT_MS, mCarServiceLifecycleListener)
+            } catch (e: Exception) {
+                Logcat.e(TAG, "createCar failed", e)
+            }
+        }
+    }
```
```diff
@@ application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt @@
-                        try {
-                            CarAudioVolumeController.getInstance(applicationContext).init()
-                            val carUxRestrictionsManager = mCarServiceManager?.getCar()?.getCarManager(...)
-                            ...（主线程同步获取 + 初始化）
-                        } catch (e: RuntimeException) { ... }
+                        initializeCarFeaturesAsync(generation)
+    // 内部：mCarFeatureExecutor 后台执行 getCarManager，完成后 mMainHandler.post 回主线程，
+    // 且 generation 不匹配（断连/重连）时丢弃过期结果
```

## 为什么能修复
`Car.createCar` 与 `getCarManager` 这些同步 Binder 调用全部离开主线程，上电阶段即使车服务响应慢，SystemUI 主线程仍能正常渲染 dock 栏与响应点击，"变灰无响应"消除；代数校验避免异步化后引入的"旧连接初始化覆盖新连接"新问题。隐患：异步化把"初始化完成"变成时序不确定事件，所有依赖 Car 就绪顺序的逻辑都需要像本提交一样显式建模（在途标志、延迟派发、看门狗），复杂度显著上升。

## 复盘与经验
- `Car.createCar`/`getCarManager` 是同步 Binder 调用，在主线程调用等于把系统服务的可用性风险直接转嫁给 UI 流畅性；车载应用启动链路必须异步化。
- 偶发"整机像卡死"的现象优先查主线程阻塞（binder 耗时/锁竞争），而不是渲染逻辑。
- 同步操作异步化不是简单 post 了事：要配套处理回调时序、过期结果丢弃（generation/代数）与失败重试（看门狗），否则把卡死换成状态错乱。
