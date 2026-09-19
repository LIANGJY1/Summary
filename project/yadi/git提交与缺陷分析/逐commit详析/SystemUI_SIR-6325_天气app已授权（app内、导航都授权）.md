# SIR-6325 · 天气已授权但通知中心显示错误并伴随主交互ANR

- **提交**：`34f9022a` | 2026-08-25 | liujinfeng | SystemUI/Carlib | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 天气 墨迹天气

## 问题
天气 app 已授权（app 内、导航均授权）但暂无数据时，通知中心天气状态显示错误；且下拉通知中心会触发主交互 ANR。

## 根因分析
两个独立缺陷叠加（提交 why 已明确）：
① **状态不刷新**：`PanelHostFragment.dispatchPageVisible()` 里当 `visiblePage == targetPage`（目标页本就可见）时直接 return 跳过 `onPageVisible()`，而通知中心天气授权状态恰恰是在 `onPageVisible()` 里主动查询的——授权发生在通知中心保持可见期间时，查询不会重跑，界面停留在"未授权/暂无数据"的错误态。缺陷库根因"通知中心可见时未主动查询授权状态"。
② **主线程同步 Binder 导致 ANR**：`DigitalKeyVehicleService` 在 Car 就绪回调里于主线程调用 `car.getCarManager(Car.POWER_SERVICE/CAR_INPUT_SERVICE)`（同步 Binder）；`PropertyManager.register/unregisterCallbacks` 也在主线程逐个执行 `registerCallback/unregisterCallback`（同样含同步 Binder，且逐属性 try 缺失，一个失败中断全部）。下拉通知中心触发大量属性注册时主线程被阻塞，出现 ANR。

## 关键代码修改
改动文件：application/SystemUI/.../SystemSettingsControllerService.kt、DigitalKeyVehicleService.kt、dropdownbar/panel/ui/PanelHostFragment.kt、component/Carlib/.../CarServiceManager.kt、PropertyManager.kt（5 文件，+116/-35）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/panel/ui/PanelHostFragment.kt
@@ dispatchPageVisible()
         if (visiblePage == targetPage) {
             LogUtils.d(TAG, "dispatchPageVisible skipped: ...")
+            if (targetPage == PanelPageRouter.PAGE_NOTIFICATION) {
+                notificationFragment?.onPageVisible()
+            } else {
+                quickFragment?.onPageVisible()
+            }
             return
         }
--- component/Carlib/src/main/java/com/neusoft/libcar/manager/CarServiceManager.kt
@@ 新增后台获取 CarManager
+    fun <T> getCarManagerAsync(serviceName: String, managerClass: Class<T>, callback: (T?) -> Unit) {
+        PROPERTY_READ_HANDLER.post {
+            if (released) return@post
+            val manager = try { managerClass.cast(getCar()?.getCarManager(serviceName)) } catch (e: Exception) { null }
+            if (!released) { try { callback(manager) } catch (e: Exception) { ... } }
+        }
+    }
--- component/Carlib/src/main/java/com/neusoft/libcar/manager/PropertyManager.kt
@@ 属性注册移至串行后台线程
+    private val propertyOperationExecutor = Executors.newSingleThreadExecutor { Thread(it, "CarPropertyOperation") }
         ids.forEach { propertyId ->
-            var registerResult = carPropertyManager?.registerCallback(callback, vehiclePropertyId, 0f)
+            propertyOperationExecutor.execute {
+                ... try { manager.registerCallback(...) ... 单属性 catch }
+                if (immediateCallback) { mHandler.post { callback.onChangeEvent(property) } }
+            }
```
（`DigitalKeyVehicleService` 改用 `requestCarInputManager()/requestCarPowerManager()` 走 `getCarManagerAsync`，重试也改为重新请求；`SystemSettingsControllerService` 用 `mLastHudValue/mLastScreenValue` 去重替代与 `LiveData.value` 比较，消除 postValue 异步导致的多余刷新。）

## 为什么能修复
`onPageVisible()` 在"页面已可见"分支也被调用，授权状态每次都主动查询，通知中心显示恢复正常；CarManager 获取与属性注册/注销全部移出主线程（CarLib 后台线程 + 单线程串行 executor），主线程不再被同步 Binder 阻塞，ANR 消除；`immediateCallback` 切回主线程派发保留了业务回调线程语义，避免引入新的线程问题。隐患：注册移到后台线程后，注册完成时序相对主线程异步化，依赖"注册即回调"立即生效的页面需容忍延迟；单线程 executor 串行化降低了并发但可能堆积任务。

## 复盘与经验
- "页面可见时刷新"的去重优化（already visible 就跳过）会吃掉"可见期间状态变化"的场景：可见性回调应幂等可重入，去重只能用于真正幂等的渲染。
- CarService/属性回调的 `getCarManager`、`registerCallback`、`unregisterCallback` 全部是潜在阻塞的同步 Binder 调用，主线程一律禁止——封装层（Carlib）统一异步化是治本方案。
- 批量注册要逐项 try/catch，避免单属性异常拖垮整批；异步化后注意用 post 保持原有回调线程契约。
- 一个提交同时修"显示错误"与"ANR"两个症状时，像本提交一样分文件分逻辑清晰落地，是好的修复组织方式。
