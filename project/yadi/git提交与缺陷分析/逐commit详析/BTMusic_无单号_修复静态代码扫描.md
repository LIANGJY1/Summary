# 无单号 · BTMusic 修复静态代码扫描（SonarQube 规则治理）

- **提交**：`b02aee28` | 2026-07-02 | duanlonglong | BTMusic | feature（实为静态扫描整改，按 bugfix 视角复盘）
- **关联单**：无

## 问题
SonarQube 全量扫描 BTMusic 模块报出一批代码异味/隐患：单例写法不规范、废弃 API 调用、Android 13 广播注册与 Parcelable 反序列化兼容性、冗余构造器、空方法等，共涉及 16 个文件（+156/-184）。

## 根因分析
该模块早期代码按"能跑就行"快速堆叠：`private constructor()` 手写单例 + `App.app!!` 全局可空强解包、直接调用 `getParcelableExtra`（Android 13 起废弃）、`registerReceiver` 未按 targetSdk 34 要求声明 `RECEIVER_EXPORTED`、`bondedDevices` 可空返回值未按 Kotlin 空安全处理、Java 旧式实例初始化块构造集合。这些在 SonarQube 规则集中均为高频命中项。

## 关键代码修改
1. 手写单例改为 Kotlin `object`，魔法数字收敛为常量（`application/BTMusic/src/main/java/com/yadea/btmusic/manager/BluetoothController.kt`）：
```diff
-class BluetoothController private constructor() {
+object BluetoothController {
+    private const val TAG = "BluetoothController"
+    private const val A2DP_SINK = 11
+    private const val AVRCP_CONTROLLER = 12
```
2. 广播注册按 Android 13+ 分支声明导出属性，并封装 Parcelable 兼容读取扩展（同文件）：
```diff
-        mAppContext!!.registerReceiver(mBluetoothReceiver, intentFilter)
+        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
+            mAppContext!!.registerReceiver(mBluetoothReceiver, intentFilter, Context.RECEIVER_EXPORTED)
+        } else {
+            mAppContext!!.registerReceiver(mBluetoothReceiver, intentFilter)
+        }
```
```diff
+    private inline fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(
+        name: String, clazz: Class<T>
+    ): T? {
+        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
+            getParcelableExtra(name, clazz)
+        } else {
+            @Suppress("DEPRECATION")
+            getParcelableExtra(name)
+        }
+    }
```
3. Java 实例初始化块改为 `Arrays.asList` 一体化构造（`service/InitService.java`）：
```diff
-    private final List<Class<?>> mServiceList = new ArrayList<>();
-    {
-        mServiceList.add(MediaServiceCmdControllerService.class);
-        mServiceList.add(BluetoothPlayerService.class);
-        mServiceList.add(MusicCarService.class);
-    }
+    private final List<Class<?>> mServiceList = new ArrayList<>(Arrays.asList(
+            MediaServiceCmdControllerService.class,
+            BluetoothPlayerService.class,
+            MusicCarService.class
+    ));
```

其余改动同属一类：`BtMusicModel.instance` 改静态访问、`bondedDevices?.toSet()` 空安全化、`isNullOrEmpty()` 替代手动判空、删除 `CardBean` 冗余全参构造器、嵌套 if 合并为 `&&` 短路、`device?.let{}` 替代 `if (device != null)`。

## 为什么能修复
每处都对应明确的 SonarQube 规则：单例规范（object 声明）、废弃 API（Compat 扩展封装 + 版本分支）、空安全（`?.` / `isNullOrEmpty`）、未使用代码（删构造器）、嵌套深度（短路合并）。版本分支写法保证在低版本车上仍走旧 API，功能行为不变，仅消除扫描告警与高版本兼容隐患。

## 复盘与经验
- `getParcelableExtraCompat` 这类 inline 扩展是治理"废弃 API + 多版本兼容"的可复用范式，值得下沉到 CommonTools 全仓使用，而不是每个模块各写一份。
- 整改里混入了行为等价重写（如 `device.address == connectedDevice?.address`），扫描治理提交最怕夹带语义变化，应保持"每 hunk 可独立判断等价性"。
- `RECEIVER_EXPORTED` 属于 targetSdk 升级后的硬性合规项，不只是消除告警——不声明在 Android 14 上会直接崩溃，此类扫描项应视为高危而非风格问题。
