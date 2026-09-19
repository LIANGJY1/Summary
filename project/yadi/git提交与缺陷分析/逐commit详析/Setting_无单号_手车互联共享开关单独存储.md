# 无单号 · 手车互联共享开关单独存储（Setting 侧，按设备维度）

- **提交**：`13ba9d1d` | 2026-07-21 | dufan | Setting | feature
- **关联单**：无（与 Launcher 侧 `72f52731` 成对提交）

## 需求/目标
把"流量共享开关"状态从单一全局 GSetting 键改为按设备（蓝牙 MAC / CarLink deviceId）分键的 JSON Map 存储，解决多手机轮流连接时共享状态互相串台的问题。

## 实现结构
4 个文件（+85/-33）：
- `Constants.java`：键名 `CURRENT_DEVICE_SHARE_NETWORK_STATE` 改为 `DEVICE_SHARE_NETWORK_STATE`（语义从"当前设备"变为"设备集合"）；
- `init/DeviceConnectManager.kt`：新增 `changeShareNetworkState(state)`（以当前设备 mac/deviceId 为 key 写入 JSON Map 并落 GSetting）、`getShareNetworkState()`（缺省 2=关闭）、`loadMapFromSettings()`（GSetting 字符串 → LinkedHashMap 解析）；`setShareNetWork(state)` 增加先调 `changeShareNetworkState(state)` 落盘，state==0 时统一弹"共享失败"toast；HiCar `onShareNetworkStop` 中 `setShareNetWork(2)` 改为 `setShareNetWork(0)`；
- `BluetoothFragment.kt`：删除 ContentObserver 监听 GSetting 变化的整段逻辑，改由回调 `onShareNetwork(state)` 直接驱动；读取开关状态改调 `getShareNetworkState()`；新增 `BluetoothUtil.SCurrentThirdDevice` 静态变量在遍历已配对设备时记录当前第三方互联设备；开关回显失败分支增加 `if (isChecked)` 判断避免误翻；
- `BluetoothUtil.kt`：新增 `SCurrentThirdDevice: CachedBluetoothDevice?`。

数据流：用户切开关 / HiCar、CarLink 回调 → DeviceConnectManager → 以当前设备标识为 key 更新 JSON Map → GSetting 持久化；UI 回显时按当前设备 key 反查。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
+    fun changeShareNetworkState(state: Int) {
+        SCurrentThirdDevice?.apply {
+            val sharedNetworkState = loadMapFromSettings()
+            val json = JSONObject()
+            val mac = when(phoneCarConnectionType) {
+                2 -> getAddress()
+                3 -> deviceId
+                else -> ""
+            }
+            sharedNetworkState[mac] = state
+            ...
+            SettingsUtils.setGSetting(DEVICE_SHARE_NETWORK_STATE, json.toString())
+        }
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
-        val uri = Settings.Global.getUriFor(CURRENT_DEVICE_SHARE_NETWORK_STATE)
-        mShareNetStateContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
-            override fun onChange(selfChange: Boolean) {
-                setTrafficSharingView()
-            }
-        }
-        requireContext().contentResolver.registerContentObserver(uri, false, mShareNetStateContentObserver!!)
+        // 改由 DeviceConnectManager 回调 onShareNetwork(state) 直接驱动 UI
```
实现讲解：存储模型从"一个键=当前设备状态"升级为"一个键=所有设备状态表"，设备标识取 `phoneCarConnectionType==2`（蓝牙）的 MAC 或 `==3`（CarLink）的 deviceId。同时砍掉了 ContentObserver 跨进程监听，改为进程内回调直推，减少一层间接性。

## 复盘与要点
- "共享状态跟人（设备）走"是典型多设备车机需求：Map-JSON-单键存储是 Settings.Global 这类只支持字符串表的轻量方案，代价是每次读都要全量解析。
- 值得注意的取舍：`SCurrentThirdDevice` 用静态变量在"遍历列表"时顺带赋值，时序上依赖 UI 刷新先于读写状态，隐式耦合易在后台同步场景失效。
- Launcher 侧同日清空了对旧键的全部写入（`72f52731`），存储所有权收敛到 Setting，跨 App 只保留回调通知，方向正确。
