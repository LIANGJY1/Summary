# SIR-4196 · 3D车模连接传感器后，无胎温胎压实时数据显示

- **提交**：`682d66ee` | 2026-07-28 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模连接胎压传感器后，胎温胎压实时数据不显示。根因是 Launcher 没有真正连上 VehicleSDK 的服务。

## 根因分析
Launcher 中存在两条 VehicleSDK 初始化路径：`Myapplication.onCreate()` 里的 `initVehicleSDK()` 和 `KanziDataSourceManager.init()` 内部的初始化，二者先后各调用一次 `VehicleSdk.getInstance().initialize()`。缺陷库根因指出：多次调用 SDK 初始化方法时，SDK 只回调了"最后一次注入的监听"——`Myapplication` 后初始化注入的 `ConnectionListener` 把 `KanziDataSourceManager` 注入的监听顶掉了，于是 SDK 服务绑定成功后只回调了 `Myapplication` 的监听，`KanziDataSourceManager.onVehicleServiceConnected()` 永远不被触发，其中的 `registerTireListeners()`（胎压监听注册）和 `sendTireTemperatureToKanzi()`（胎温推送）都不会执行，`vehicleSDKInit` 也一直是 false，表现为"没有连接到 VehicleSDK 的服务"。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt；application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java；component/commonlibs/VehicleSDK.jar（二进制更新）

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt
         init(this)
         KanziDataSourceManager.getInstance(this).init()
-        initVehicleSDK()
         addTransparentPlaceholderWindow()
     }
 
-    private fun initVehicleSDK() {
-        //初始化车辆SDK
-        VehicleSdk.getInstance().initialize(myApplication, object : ConnectionListener {
-            override fun onVehicleServiceConnected() {
-                LogUtils.d(TAG, "onVehicleService Connected")
-                Constants.setVehicleSDKInit(true)
-            }
-            ...
-        })
-    }
```

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
             public void onVehicleServiceConnected() {
                 LogUtils.d(TAG, "VehicleSdk service connected");
                 vehicleSDKInit = true;
+                com.yadea.launcher.Constants.setVehicleSDKInit(true);
                 registerTireListeners();
                 sendKanTrip();
                 sendTireTemperatureToKanzi();
@@
             public void onVehicleServiceDisconnected() {
                 LogUtils.d(TAG, "VehicleSdk service disconnected");
                 vehicleSDKInit = false;
+                com.yadea.launcher.Constants.setVehicleSDKInit(false);
             }
```

## 为什么能修复
删除 `Myapplication` 中的二次初始化后，全应用只剩 `KanziDataSourceManager.init()` 这一次 `initialize()` 调用，其注入的监听不会再被覆盖，服务绑定成功后 `onVehicleServiceConnected()` 能正常回调，胎压监听得以注册、数据得以推送。配套的 VehicleSDK.jar 二进制更新（86995→87572 字节）对应缺陷库方案中"sdk 中增加监听 list 缓存，服务绑定后遍历列表回调绑定状态"，从 SDK 侧根除"只回调最后一次监听"的问题。副作用：原 `Myapplication` 监听承担的全局 flag 职责被移入 `KanziDataSourceManager` 回调中通过 `Constants.setVehicleSDKInit()` 补齐，功能等价，无遗漏。

## 复盘与经验
- **重复初始化是监听覆盖的经典来源**：同一个 SDK 被多处 `initialize()`，若 SDK 内部只保存单个 listener 引用，先注册的监听会被静默顶掉，且无任何报错，只能靠"回调没来"的现象倒推。
- **初始化应收敛到唯一入口**：SDK 连接类资源应在 Application 层或统一 Manager 中单点初始化，避免各模块自行调用导致时序与覆盖问题。
- **SDK 设计层面要支持多监听**：服务连接类 SDK 应内部维护 listener 列表（本例 jar 更新正是这么改的），把"单监听覆盖"这类调用方易犯的错误在框架侧兜底。
- **全局状态 flag 要与真实连接回调同源**：`Constants.setVehicleSDKInit` 的赋值移到唯一连接回调中，保证状态与实际绑定一致，避免多处赋值造成状态漂移。
