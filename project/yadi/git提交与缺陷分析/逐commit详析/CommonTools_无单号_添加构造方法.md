# 无单号 · 添加构造方法（CachedBluetoothDevice 支持无 BluetoothDevice 的互联设备条目）

- **提交**：`6d7de086` | 2026-07-02 | dufan | CommonTools | feature
- **关联单**：无

## 需求/目标
为蓝牙缓存设备类 `CachedBluetoothDevice` 增加一个不依赖系统 `BluetoothDevice` 对象的构造方法，使 CarPlay/HiCar/CarLink 等"互联设备"也能复用同一套缓存设备模型参与蓝牙设备列表展示。

## 实现结构
- 修改 `component/Hardwarelibs/.../bluetooth/local/CachedBluetoothDevice.java`：新增 public 构造器（context/name/address/互联连接类型等 6 参），`mDevice` 允许为 null；为 `getAddress()`、`getName()`、`getBondState()`、`isBonded()`、`toString()` 补上 `mDevice == null` 的降级分支；新增公共字段 `mAddress` 兜底地址。
- 修改 `component/Hardwarelibs/.../bluetooth/IWxBluetoothManager.java`：日志方法补参数、清理一行注释。

数据流：互联模块用"名称+地址+连接类型"直接 new 出 `CachedBluetoothDevice` → 后续列表/比较/展示逻辑照常调用 getter → getter 检测到 `mDevice == null` 时退回构造时传入的 `mName`/`mAddress`/默认值。

## 关键代码
```diff
--- a/component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDevice.java
@@ -83,6 +84,19 @@
+    public CachedBluetoothDevice(Context context, String name, String address, int phoneCarConnectionType, int connectionType, boolean isPhoneCarConnect) {
+        this.mContext = context;
+        this.mName = name;
+        this.mAddress = address;
+        this.mPhoneCarConnectionType = phoneCarConnectionType;
+        this.mConnectionType = connectionType;
+        this.mIsPhoneCarConnect = isPhoneCarConnect;
+        this.mLocalAdapter = LocalBluetoothAdapter.getInstance();
+        this.mProfileManager = null;
+        this.mDevice = null;
+    }
```

```diff
@@ -383,6 +400,9 @@
     public String getName() {
+        if (this.mDevice == null) {
+            return mName;
+        }
         String name = this.mDevice.getName();
         return TextUtils.isEmpty(name) ? this.mDevice.getAddress() : name;
     }
```

实现讲解：原类强绑定系统 `BluetoothDevice`（真实 BT 设备），互联通道（USB/无线 CarPlay 等）没有对应的 BT 对象，因此新增"轻量构造器"只填元数据。所有从 `mDevice` 取值的出口都加了 null 卫语句，`toString()` 也从 `mDevice.toString()` 改为 `getAddress()` 避免 NPE。这是典型的"哑对象/幽灵条目"手法——用同一个 UI 模型承载两类来源的数据。

## 复盘与要点
- 用 null 分支而非子类/接口区分"真实 BT 设备"与"互联虚拟设备"，改动小但把 null 判断散进了 5+ 个方法；若互联条目继续增多，抽 `BluetoothDevice` 抽象或组合会更稳。
- 新增公共字段 `mAddress` 与 getter 并存，外部可绕过 null 降级逻辑直接改字段，属于快改留下的封装破口。
- 可复用手法：给缓存模型补"无后端实体"构造器，是打通多数据源列表（BT + CarPlay + HiCar）的最低成本方案。
