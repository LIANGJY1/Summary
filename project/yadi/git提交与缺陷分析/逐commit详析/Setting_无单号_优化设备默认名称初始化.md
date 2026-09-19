# SIR-XXX · 优化设备默认名称初始化

- **提交**：`8b3394fb` | 2026-09-02 | dufan | Setting | feature（内容实为缺陷修复：修初始化时序）
- **关联单**：SIR-XXX（占位单号）；cherry-pick 自 `75dedaf1`

## 问题现象
设备默认名称（蓝牙/副蓝牙/热点共用，按 VIN 生成）偶发初始化失败或写入错误值：原逻辑放在 Application 启动的协程里立即读 VIN，此时车机信号服务（VehicleSdk/CarService）尚未连接完成，`getProperty(VEHICLE_VIN_CODE)` 拿到 null/空，`handleDeviceName` 走兜底分支，生成的名称不符合"brand_VIN后6位"规则（如 60d533bc 之后的空后缀名），且后续不再纠正。

## 根因分析
初始化时机与依赖就绪时机错配：VIN 读取依赖 CAN 服务连接，而 `MyApplication.onCreate` 的 launch 块不等待连接回调。修法是把 `initDeviceName()` 挪到"Car service initialized successfully"回调之后，并用 `ThreadUtils.executeByIoWithDelay` 延迟 1 秒 + IO 线程执行，给底层注册表/属性缓冲一个就绪窗口；`syncDeviceNameToBluetooth` 随之整体迁移到 SettingVehicleService（职责归位：设备名同步本就是信号服务的事）。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@ -509,6 +515,7 @@
                         carPropertyEventCallback,
                         true
                     )
+                    initDeviceName()
                     Log.d(TAG, "Car service initialized successfully")
```
```diff
+    fun initDeviceName() {
+        ThreadUtils.executeByIoWithDelay(object : ThreadUtils.Task<Any>() {
+            override fun doInBackground(): Any? {
+                val vinProperty = settingVehicleService.getProperty(CarPropertyIds.VEHICLE_VIN_CODE)
+                handleDeviceName(vinProperty?.value .toString()) { deviceName ->
+                    syncDeviceNameToBluetooth(deviceName)
+                }
+                return null
+            }
+            ...
+        }, 1, TimeUnit.SECONDS)
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/MyApplication.kt
@@ -52,13 +45,9 @@
-                initDeviceName()
-
                 DeviceConnectManager.getInstance().startAllDeviceConnect()
```

## 为什么能修复
VIN 读取被移动到服务连接确认之后，并有 1 秒延迟缓冲，读到的必然是已注册属性的真实值；名称生成与三路同步（主蓝牙/副蓝牙/热点）在一个入口完成且 catch 兜底，时序确定后结果确定。该提交与 `e08e4e51`（展示层兜底）、`60d533bc`（副蓝牙链路）构成同一问题的展示、链路、源头三层修复。

## 复盘与经验
- 车机上"Application 启动即读车况属性"几乎必踩未就绪坑，正确锚点是服务连接成功回调，再辅以小额延迟兜底。
- 修 bug 优先修"时机"，其次才是"兜底值"：本提交把临时性兜底逻辑退居二线，让初始化顺序成为正确性的第一保障。
- 遗留：`vinProperty?.value .toString()`（多余空格）与固定 1 秒延迟仍是经验值，极端慢环境下可能仍读到空，更稳的做法是订阅 VIN 属性首次回报事件。
