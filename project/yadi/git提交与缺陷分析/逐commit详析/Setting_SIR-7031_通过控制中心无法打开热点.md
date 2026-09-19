# SIR-7031 · 通过控制中心无法打开热点
- **提交**：`b4c2c9ae` | 2026-09-01 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互
- **注**：这是一个混合提交，除热点修复外还夹带 SafetyMonitorFragment 格式化 Loading 重构、CommonTools 新增 `LoadingDialog`、`ViewExtension.runWithScrollRestore` 等改动，本文聚焦热点主链路。

## 问题
从未进入过"连接"设置页时，从控制中心无法打开热点。

## 根因分析
热点 SSID 的初始化原本放在 `ConnectFragment.initView()`：读 VIN → `handleDeviceName(vin)` 回调里 `setGSetting(HotspotDialogFragment.HOTSPOT_NAME, deviceName)`。也就是说热点名只有在用户打开过连接设置页后才会写入全局设置。控制中心直接开热点时 `HotspotDialogFragment` 读 `HOTSPOT_NAME` 为空，旧代码回退到 `DeviceUtils.getDeviceName()`——该值此时也未就绪（提交信息称"没有获取到设备名称"），最终拿到空 SSID 去开热点，开热点流程异常失败。

## 关键代码修改
改动文件：`application/Setting/.../MyApplication.kt`、`.../ui/fragment/ConnectFragment.kt`、`.../diologfragment/HotspotDialogFragment.kt`
```diff
--- .../com/yadea/setting/MyApplication.kt
@@ applicationScope.launch {
-                syncDeviceNameToBluetooth()
+                initDeviceName()
...
+    fun initDeviceName() {
+        val vinProperty = settingVehicleService.getProperty(CarPropertyIds.VEHICLE_VIN_CODE)
+        handleDeviceName(vinProperty?.value.toString()) { deviceName ->
+            syncDeviceNameToBluetooth(deviceName)
+        }
+    }
@@ syncDeviceNameToBluetooth(deviceName)
             if (deviceName.isNotEmpty()) {
                 BtAnwManager.getInstance().setLocalDevName(deviceName)
             }
+            setGSetting(HotspotDialogFragment.HOTSPOT_NAME, deviceName)

--- .../ui/fragment/ConnectFragment.kt（initView 中删除上述初始化块）

--- .../diologfragment/HotspotDialogFragment.kt
-        mHotspotName = if (TextUtils.isEmpty(name)) DeviceUtils.getDeviceName() else name
+        mHotspotName = if (TextUtils.isEmpty(name)) "" else name
```

## 为什么能修复
设备名初始化从"打开连接页才执行"提升到 `MyApplication.onCreate` 的 `applicationScope` 中开机即执行，热点名随蓝牙名一起在启动阶段写入全局设置；控制中心再读 `HOTSPOT_NAME` 必有值。同时删除了 `DeviceUtils.getDeviceName()` 这个不可靠回退，避免拿到与设置页不一致的空值。隐患：若 VIN 属性尚未就绪，`vinProperty?.value.toString()` 会得到 "null" 字符串参与命名，依赖 `handleDeviceName` 内部兜底；此外未进入连接页前热点名显示为默认值，属可接受。

## 复盘与经验
- 依赖某个设置项的功能（控制中心开关），其设置项初始化必须放在 Application 级别，不能寄生于某个 UI 页面的 initView。
- "异步回调里写配置 + 读取处再回退"的双路逻辑很容易在时序上失配：回退值不可靠时应直接暴露问题（置空校验）而不是悄悄换一个来源。
- 混合提交（热点 + 行车记录仪格式化 + 通用 Loading 组件）降低可追溯性，建议拆分提交。
