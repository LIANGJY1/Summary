# 无单号 · 添加CarLink设备标识

- **提交**：`7b92870b` | 2026-07-08 | dufan | HardwareLibs | feature
- **关联单**：无

## 需求/目标
给蓝牙缓存设备模型 `CachedBluetoothDevice` 增加 `mDeviceId`（设备唯一标识）字段，为上层 CarLink 设备的识别/删除逻辑（同日后续提交 `86505312`）提供数据支撑。

## 实现结构
只改动 `component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDevice.java`：
- 新增公共字段 `public String mDeviceId`（紧挨既有的 `mAddress`、`mPhoneCarConnectionType`，后者注释标明 1:CarPlay / 2:HiCar / 3:CarLink，即本字段服务于手机-车互联场景）。
- 新增 `getDeviceId()/setDeviceId()` 存取器。
- `toString()` 加入 `deviceId=` 输出，便于日志排查。

数据流：底层蓝牙连接管理在构建/缓存设备对象时可通过 setter 写入设备 ID，Setting 层（BluetoothFragment/DeviceConnectManager）读取该 ID 精确定位要删除的 CarLink 设备，避免同名/同地址设备误删。

## 关键代码
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDevice.java
@@ -76,6 +76,7 @@ public final class CachedBluetoothDevice implements Comparable<CachedBluetoothDe
     private BluetoothDeviceType mDeviceType;
     public String mAddress;
+    public String mDeviceId;
     private int mPhoneCarConnectionType; //1:CarPlay   2:HiCar   3:CarLink

@@ -962,6 +963,15 @@
+    public String getDeviceId() {
+        return mDeviceId;
+    }
+
+    public void setDeviceId(String deviceId) {
+        this.mDeviceId = deviceId;
+    }
```
实现讲解：典型的"模型扩字段"提交——在既有设备缓存对象上横向扩展一个标识位，并同步补齐 `toString` 日志输出。字段直接声明为 `public`，沿用了该类 `mAddress` 的既有风格（模型本身就是半开放的缓存数据袋）。

## 复盘与要点
- 小步快照式开发：本提交（20:06 加字段）与 2 分钟后的 `86505312`（20:08 修改删除逻辑）构成一个功能对，字段先行、逻辑随后，review 时应两提交连看。
- 可复用手势：扩展设备模型时同步改 `toString()`，让新增字段天然进入日志链路，排查连接问题时能直接看到 ID。
- 遗留风险：`mDeviceId` 未参与 `equals()`，若 CarLink 设备 ID 是业务上的唯一键，用它删除设备时仍需自行处理"两设备 equals 相等但 ID 不同"的边界。
