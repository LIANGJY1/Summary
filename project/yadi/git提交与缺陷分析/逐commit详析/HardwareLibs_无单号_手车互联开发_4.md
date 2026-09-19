# 无单号 · 手车互联开发：增加连接类型（CachedBluetoothDevice 补 getter/setter）

- **提交**：`ba75cde9` | 2026-06-27 | dufan | HardwareLibs | 类型：功能支撑提交（虽打 [bugfix] 标签，实际为接口扩展，影响等级 D、测试范围"无"）
- **缺陷库**：未关联单号

## 问题
无缺陷现象。手车互联（HiCar/CarPlay）开发过程中，上层需要读写 `CachedBluetoothDevice` 的连接类型字段，但该类此前只有 `mPhoneCarConnectionType` 的存取器，`mConnectionType` 字段没有对外访问方法（推测：字段已存在，供内部/序列化使用）。

## 根因分析
以 diff 实际内容为准：这不是缺陷修复，而是为 `component/Hardwarelibs/.../bluetooth/local/CachedBluetoothDevice.java` 补上 `mConnectionType` 的 getter/setter，共 8 行新增。属于手车互联开发链条中的一个前置接口扩展提交（同日的 `407c86d1`、`eb3a58af` 等为同一开发任务的 Setting/Launcher 侧改动）。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/local/CachedBluetoothDevice.java

```diff
--- component/Hardwarelibs/.../bluetooth/local/CachedBluetoothDevice.java
@@
         this.mPhoneCarConnectionType = mPhoneCarConnectionType;
     }
 
+    public int getConnectionType() {
+        return mConnectionType;
+    }
+
+    public void setConnectionType(int mConnectionType) {
+        this.mConnectionType = mConnectionType;
+    }
+
     public boolean isPhoneCarConnect() {
```

## 为什么能修复
不涉及修复。纯粹补齐字段访问器，让上层模块能传递/查询连接类型（手机互联 vs 车机本地蓝牙等语义，具体取值需看调用方）。无副作用，注意与已有的 `mPhoneCarConnectionType` 是两个字段，命名相近易混用。

## 复盘与经验
- **提交标签要诚实**：`[bugfix]` 标签 + what/why/how 三段全填同一句"增加连接类型"，说明提交模板被当作形式填充，会污染 bugfix 统计与缺陷回溯。
- **字段与存取器应同步交付**：`mConnectionType` 字段先于 getter/setter 存在，中间状态下层字段"有数据、无入口"，往往逼着调用方走反射或复制逻辑。
- **相近字段命名是隐患**：`mConnectionType` 与 `mPhoneCarConnectionType` 并存，建议在注释中标明语义边界。
