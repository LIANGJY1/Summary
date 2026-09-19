# 无单号 · EnergyManagement 修复静态代码扫描（SonarQube 规则治理）

- **提交**：`5cb54534` | 2026-07-07 | duanlonglong | EnergyManagement | feature（实为静态扫描整改，按 bugfix 视角复盘）
- **关联单**：无

## 问题
SonarQube 扫描能量管理模块（Java 为主）报出大量问题：日志字符串重复字面量、lambda 冗余花括号、匿名内部类式写法、可继承的工具基类构造器未收紧、废弃 `systemUiVisibility`、未使用类等，涉及 16 个文件（+410/-651，净删 240 行）。

## 根因分析
模块由不同时期多人堆叠：`MainActivity.java`（370 行级改动）里十几个车辆信号监听器各自带重复日志字面量与冗余 lambda 体；`EnergyConsumptionUtils` 是整类无引用的示例残留（带双检锁单例的演示代码）。

## 关键代码修改
1. 日志字面量收敛为常量 + 方法引用（`view/ui/MainActivity.java`）：
```diff
+    //region Log string constants
+    private static final String LOG_PREFIX_MILEAGE_DEBUG = "[MileageDebug] ";
+    private static final String SOURCE_CALLBACK = "callback";
+    private static final String LOG_SEPARATOR_UI_UPDATED = ", uiUpdated=";
+    ...
+    //endregion
-    private final VehicleService.ChargingGunConnectionListener mChargingGunConnectionListener = connected ->
-            updateChargingGunConnectedUi(connected);
+    private final VehicleService.ChargingGunConnectionListener mChargingGunConnectionListener = this::updateChargingGunConnectedUi;
```
2. 删除无引用的能耗示例工具类（`module/EnergyConsumptionUtils.java` 整文件 -153 行）：
```diff
-public class EnergyConsumptionUtils {
-    private static volatile EnergyConsumptionUtils INSTANCE;
-    public static EnergyConsumptionUtils getInstance() { ...双检锁... }
-    public void addFuelData(double data) { ...LinkedList 池化演示代码... }
```
3. 摸鱼式整改的典型 hunk（`view/custom/BaseDialog.java`）：
```diff
-        switch (event.getAction()) {
-            case MotionEvent.ACTION_DOWN:
-                if (isPointOutsideView(x, y, rootLayoutView)) {
-                    dismiss();
-                    return true;
-                }
-                break;
+        if (event.getAction() == MotionEvent.ACTION_DOWN
+                && isPointOutsideView(x, y, rootLayoutView)) {
+            dismiss();
+            return true;
```
```diff
-    public BaseDialog(@NonNull Context context) {
+    protected BaseDialog(@NonNull Context context) {
```
其余：`ChargeGunMonitorService` 4 处方法加 `//NOSONAR`（认知复杂度类告警）、补去抖日志字段；`VehicleService.java` 大规模格式化；废弃 API 处 `//NOSONAR` 留痕。

## 为什么能修复
字符串字面量常量化消除"duplicated string literals"规则；lambda 只有一条语句时改方法引用消除冗余告警；单 case 的 switch 改 if 消除"switch should be if"规则；基类构造器 `public`→`protected` 消除"utility class should not have public constructor"规则；整类删除直接消掉全部相关告警。行为均等价，唯二风险点是删除类需确认无反射引用、构造器收紧需无外部实例化。

## 复盘与经验
- 与 BTMusic/AccountCenter/Vlog 三次治理相比，本提交额外做了"日志字符串常量化 + region 分区"，对 300+ 行的信号监听 Activity 是值得推广的日志工程化手法。
- 整类删除的 `EnergyConsumptionUtils` 属于"复制粘贴进仓库的示例代码"，入库时就应拦住——静态扫描在 CI 前置可以避免这类欠债。
- `//NOSONAR` 集中在认知复杂度与废弃 API 两类，说明团队对这两类采取"接受现状"策略，建议在扫描配置里把同类规则降级，减少标记噪声。
