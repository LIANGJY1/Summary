# SIR-5983 · 车控车设崩溃（主线程同步 Binder 写车辆属性致 ANR）
- **提交**：`56ed4a64` | 2026-08-18 | liujinfeng | Carlib | bugfix
- **缺陷库**：等级 A · 频次 偶现-低于10% · 状态 问题取消 · 域 车控车设

## 问题
自动化运行中车控车设崩溃，之后 dock 栏/applist 点击车控车设均无反应（应用已死或无响应），其他应用正常。

## 根因分析
提交信息明确归因："主线程同步调用车辆属性 Binder，远端 CarProperty 服务迟迟不返回，导致 Input ANR"。代码上，`component/Carlib` 的 `SendPropertyManager.setIntProperty()/setFloatProperty()/setByteArrayProperty()/setIntArrayProperty()` 全部在调用者线程直接执行 `carPropertyManager.setIntProperty(...)` 等同步 Binder 调用；而 `Setting`/`Launcher` 等业务常在主线程点击事件里发车控信号（如 `VehicleService.sendVehicleProperty`）。远端 CarProperty 服务阻塞时主线程被无限期挂起，输入超时即 ANR 崩溃，应用进程死亡后入口点击自然全部无反应。属跨进程同步调用被远程端"卡死"的经典 A 级故障。

## 关键代码修改
改动文件：`component/Carlib/src/main/java/com/neusoft/libcar/manager/SendPropertyManager.kt`、`CarServiceManager.kt`、`PropertyManager.kt`、`PropertyRollbackManager.kt`
```diff
--- component/Carlib/src/main/java/com/neusoft/libcar/manager/SendPropertyManager.kt
+    private val writeHandlerDelegate = lazy {
+        HandlerThread("CarPropertyWrite").let { thread ->
+            thread.start()
+            Handler(thread.looper)
+        }
+    }
+    private val writeHandler by writeHandlerDelegate
+
     fun setIntProperty(vehiclePropertyId: Int, area: Int, value: Any): Boolean {
-        return (value as? Int)?.let { intValue ->
-            carPropertyManager?.setIntProperty(vehiclePropertyId, area, intValue) != null
-        } ?: false
+        val intValue = value as? Int ?: return rejectInvalidValue("int", vehiclePropertyId, value)
+        return enqueueWrite("int", vehiclePropertyId, area, intValue.toHexString()) { manager ->
+            manager.setIntProperty(vehiclePropertyId, area, intValue)
+        }
     }
```
（`enqueueWrite()` 把全部类型的 Binder 写统一 `writeHandler.post` 到专用串行 `HandlerThread("CarPropertyWrite")` 执行，带 `queueDelayMs`/`durationMs` 观测日志与 500ms 慢写告警；返回值语义改为"是否成功入队"。`PropertyRollbackManager.startRollbackMonitor` 改为"先建 pending 超时任务、再投递异步写"，入队失败即 `cleanUp`；`CarServiceManager` 注销/释放时 `timeoutManager?.release()` 清理未完成回滚任务。）

## 为什么能修复
所有车辆属性写从调用线程（含主线程）剥离到专用串行后台线程，远端服务再慢也只阻塞该线程，主线程与输入事件不再被 Binder 同步调用挂死，ANR 崩溃链路被切断；串行队列同时保住了业务侧信号下发顺序不变。配套修改保证异步化后的正确性：回滚超时任务先于写入建立（回调先到也能清理）、manager 失效时拒绝入队、注销时释放待回滚任务防止回调已注销页面。隐患：返回值从"写成功"变为"入队成功"，依赖返回值做 UI 假设的调用方语义需要跟进（注释已统一改写）；慢写会积压串行队列，延迟告警日志可观测。

## 复盘与经验
- 主线程发起的任何 Binder 调用（尤其车辆属性/跨进程服务）都必须假设远端会卡死：统一收敛到专用工作线程是车载库层的必修课，Carlib 作为公共组件在此收口收益最大。
- 同步调用异步化必须连带审视三件事：返回值语义、顺序性保证（串行队列）、超时/回滚任务与写入的建立顺序——本提交四件全部处理，是教科书式改造。
- "点击无反应+应用崩溃"组合在车机上大概率是 ANR 被系统杀死，排查从 /data/anr/ 的主线程堆栈找同步 Binder 调用点。
- 缺陷库状态为"问题取消"、根因字段为空，但提交实打实修复了 A 级 ANR——缺陷库与代码的关联记录需要维护，否则复盘时高价值案例会被"取消"状态淹没。
