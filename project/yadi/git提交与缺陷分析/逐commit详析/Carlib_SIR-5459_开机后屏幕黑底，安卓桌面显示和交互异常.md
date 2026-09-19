# SIR-5459 · 开机黑底安卓桌面异常无法进入仪表桌面（主线程getProperty阻塞ANR）

- **提交**：`1332cfe0` | 2026-08-05 | liujinfeng | Carlib/Launcher | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 关闭 · 域 主交互

## 问题
偶现（<10%）：开机后屏幕黑底、安卓桌面显示与交互异常，无法进入仪表桌面——本质是 Launcher 主线程 ANR。

## 根因分析
车机开机阶段 CarService 尚在初始化、Binder 繁忙，此时 Launcher 主线程直接调用 `getProperty()/getIntProperty()` 等，底层是同步 Binder 调用（`PropertyManager.getProperty`），会被长时间挂起，主线程阻塞引发 ANR，整个桌面黑底无响应。典型调用如 `VehicleService` 服务连接回调里同步读 `CarPropertyIds.VEHICLE_VIN_CODE`；各业务在信号回调/初始化路径中的主线程 getProperty 读取同样踩雷。缺陷库结论：主线程请求 getProperty 导致线程阻塞。

## 关键代码修改
改动文件：component/Carlib/src/main/java/com/neusoft/libcar/manager/CarServiceManager.kt、application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java、application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
// component/Carlib/src/main/java/com/neusoft/libcar/manager/CarServiceManager.kt
     fun getProperty(propertyId: Int, area: Int = CarConstants.UNUSED): CarPropertyValue<*>? {
-        return PropertyManager.getProperty(propertyId, area)
+        return if (isMainThread()) {
+            getCachedProperty(propertyId, area)
+        } else {
+            getPropertyBlocking(propertyId, area)
+        }
     }
```
```diff
// CarServiceManager.kt onChangeEvent 中顺手维护缓存
             val convertedProperty = AppCarPropertyValue(...)
+            propertyCache[cacheKey(convertedProperty.propertyId, convertedProperty.areaId)] = convertedProperty
             carPropertyCallback?.onChangeEvent(convertedProperty)
...
+    // 主线程缓存未命中：立即返回 null/默认值，异步补拉（pendingCacheReads 去重 + RETRY_INTERVAL_MS 节流）
+    private fun requestCacheRefresh(propertyId: Int, area: Int = CarConstants.UNUSED) {
+        if (!isMainThread() || released || !CarConnectionManager.isConnected()) return
+        if (pendingCacheReads.putIfAbsent(key, true) != null) return
+        PROPERTY_READ_HANDLER.post { ... getPropertyBlocking(propertyId, area) ... }
+    }
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java VIN读取改异步
-        CarPropertyValue<?> vinProperty = getProperty(CarPropertyIds.VEHICLE_VIN_CODE);
-        ...Settings.System.putString(getContentResolver(), KEY_CUSTOM_DATA, vinValue);
+        readVinAsync();
+    private void readVinAsync() {
+        manager.getPropertyAsync(CarPropertyIds.VEHICLE_VIN_CODE, 0, vinProperty -> {
+            ...Settings.System.putString(getContentResolver(), KEY_CUSTOM_DATA, ...);
+        });
```
配套：`CarServiceManager` 新增 `propertyCache`（ConcurrentHashMap，onChangeEvent 写入、断连清空）、专用 `HandlerThread("CarPropertyRead")` 执行阻塞读、`getPropertyAsync/getIntPropertyAsync/...` 回调式 API 及 `getXxxBlocking` 系列供子线程使用；`VehicleService` 的 `mIsReady`/`mDestroyed`/`hasSendMessageSuccess` 改 volatile、回调列表改 `CopyOnWriteArrayList`。

## 为什么能修复
主线程的属性读取不再走同步 Binder：命中缓存直接返回最近一次 onChangeEvent 的值；未命中立即返回默认值并在后台线程补拉，主线程永远不被 CarService 拖死，开机 ANR 消除；VIN 等启动期读取改为异步回调，服务就绪流程不再被单个属性阻塞。副作用：主线程首读可能拿到"暂态默认值"（缓存未热），业务需容忍异步补齐；缓存一致性依赖 onChangeEvent 持续派发（与 2d21bbde 的看门狗互为补充）。

## 复盘与经验
- 车机主线程调用任何跨进程 getXxx（CarService/Telecom/WindowManager）都要默认"可能阻塞数秒"，尤其开机/服务重启窗口，必须有缓存或异步通道兜底。
- "事件缓存 + 未命中异步补拉 + 请求去重节流"是属性型服务的标准主线程读模式，写库时一次做对，全业务受益。
- 服务连接回调里不要做耗时同步操作（读 VIN、批量初始化），先完成连接状态机再异步补数据。
