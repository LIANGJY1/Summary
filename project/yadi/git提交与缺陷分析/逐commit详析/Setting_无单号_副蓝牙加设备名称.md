# SRS_BT_LinkSetting_010 · 副蓝牙加设备名称

- **提交**：`60d533bc` | 2026-08-28 | sgh | Setting | feature
- **关联单**：SRS_BT_LinkSetting_010（标题引用，无系统单号）

## 需求/目标
给副蓝牙（ANW 蓝牙）补齐设备名称能力：修改设备名时同步下发给副蓝牙模块，副蓝牙页面展示与主蓝牙一致的持久化名称，默认名称生成兜底修正。

## 实现结构
- `Hardwarelibs/BtAnwManager.java`：新增 `setLocalDevName(String)`，mBtAdapter 为空时现场取 `BtAdapter.getDefaultAdapter()`，再调原生 `AnWBT_SetLocalDevName`——把"设名"能力下沉到硬件封装层。
- `MyApplication.kt`：应用启动同步名称链路中，在主蓝牙 `mWxBtManager.name` 之后追加 `BtAnwManager.setLocalDevName(deviceName)`，双蓝牙同时同步；顺带删除一条重复 import。
- `ConnectFragment.kt`：用户改名入口在改主蓝牙名后同步改副蓝牙名。
- `BluetoothAnwFragment.kt`：副蓝牙页头部名称固定展示 `DeviceUtils.getDeviceName()`（与主蓝牙一致）。
- `DeviceUtils.kt`：默认设备名生成中 VIN 不足 6 位的兜底值由 `"1234567890"` 改为空串（不再生成假后缀），并加日志。

数据流：VIN 读取 → DeviceUtils 生成默认名并持久化 → 启动同步（主+副蓝牙）/用户改名 → ConnectFragment → 主、副蓝牙双路下发。

## 关键代码
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ -120,6 +120,19 @@
+    /**
+     * 设置副蓝牙本地设备名称
+     */
+    public void setLocalDevName(String name) {
+        BtAdapter adapter = mBtAdapter;
+        if (adapter == null) {
+            adapter = BtAdapter.getDefaultAdapter(ContextGetter.applicationContext());
+        }
+        if (adapter != null && name != null) {
+            adapter.AnWBT_SetLocalDevName(name);
+        }
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/MyApplication.kt
@@ -93,6 +92,9 @@
             if (deviceName.isNotEmpty() && BluetoothUtil.mWxBtManager.name != deviceName) {
                 BluetoothUtil.mWxBtManager.name = deviceName
             }
+            if (deviceName.isNotEmpty()) {
+                BtAnwManager.getInstance().setLocalDevName(deviceName)
+            }
```
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/utils/DeviceUtils.kt
@@ -19,8 +20,9 @@
-                    if (vin.length < 6) "1234567890" else vin.substring(vin.length - 6)
+                    if (vin.length < 6) "" else vin.substring(vin.length - 6)
```

实现讲解：改动本质是"副蓝牙补齐主蓝牙已有的名称链路"：入口（改名）、启动（同步）、展示（页面回显）三处对齐。`setLocalDevName` 内做 adapter 懒获取与判空，让上层调用无需关心蓝牙栈初始化时序。

## 复盘与要点
- 双蓝牙（主/副）并存时，凡设备级配置（名称、开关）都存在"一改要改两处"的一致性问题；本提交把双路同步收敛到 `syncDeviceNameToBluetooth` 与改名入口，后续若再加第三路应抽成列表遍历。
- 默认名兜底从假 VIN 后缀改为空串是对的——用 `"1234567890"` 兜底会让多台车同名，蓝牙搜索时无法区分。
- 注意 `e08e4e51`（主蓝牙展示兜底）与本提交（副蓝牙展示持久化名）同日落地，说明"设备名显示"是该阶段的高频问题域。
