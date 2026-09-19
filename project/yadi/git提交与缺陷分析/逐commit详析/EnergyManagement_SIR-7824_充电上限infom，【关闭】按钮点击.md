# SIR-7824 · 充电上限 infom"关闭"按钮无点击按压效果
- **提交**：`47f5ab42` | 2026-09-09 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心（根因：没有点击态）

## 问题
充电上限 info 弹窗的【关闭】按钮点击时无按压反馈，用户感知不到"点到了"。

## 根因分析
`dialog_charge_limit_info.xml` 中关闭按钮容器的背景固定引用 `@drawable/button`（静态图，无 state_pressed 状态），文字颜色也是固定 `@style/ChargeLimitInfoCloseText`，按压时视觉零变化。修复补一套标准 selector 点击态资源：新增 `selector_charge_limit_info_close_button.xml`（默认/按压两态 shape：420x74dp、圆角 12dp、描边 2dp）与 `selector_charge_limit_info_close_text_color`，并在 values 与 values-night 各加 4 个颜色（按压背景加深：日间 `#FFFFFF→#261F222A` 叠加、夜间 `#1AFFFFFF→#26FFFFFF`，按压时文字降透明度）。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/drawable/selector_charge_limit_info_close_button.xml（新增）；application/EnergyManagement/src/main/res/layout/dialog_charge_limit_info.xml；application/EnergyManagement/src/main/res/values/colors.xml；application/EnergyManagement/src/main/res/values-night/colors.xml；application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
```diff
--- application/EnergyManagement/src/main/res/layout/dialog_charge_limit_info.xml
@@ -46,7 +46,7 @@
             android:layout_marginBottom="36dp"
-            android:background="@drawable/button"
+            android:background="@drawable/selector_charge_limit_info_close_button"
             android:clickable="true"
             android:focusable="true">
@@ -60,7 +60,8 @@
                 android:text="@string/close"
-                android:textAppearance="@style/ChargeLimitInfoCloseText" />
+                android:textAppearance="@style/ChargeLimitInfoCloseText"
+                android:textColor="@color/selector_charge_limit_info_close_text_color" />
```
```diff
--- application/EnergyManagement/src/main/res/values/colors.xml
@@ -21,6 +21,10 @@
+    <color name="charge_limit_info_close_pressed_bg_color">#261F222A</color>
+    <color name="charge_limit_info_close_stroke_color">#1A1F222A</color>
+    <color name="charge_limit_info_close_pressed_stroke_color">#1A1F222A</color>
+    <color name="charge_limit_info_close_pressed_text_color">#991F222A</color>
```
本提交还捆绑了 `MainActivity` 中充电余量/电流电压功率占位值逻辑的较大改动（新增 `CHARGE_REMAINING_TIME_INVALID=0xFFFF`、`resetChargeMetricsToPlaceholder()`、`isChargingActive()` 判定等），与"关闭按钮点击态"单号无直接关系，属顺带提交（见下"副作用"）。

## 为什么能修复
按钮背景切到两态 selector、文字色切到 state 颜色选择器后，按压即有背景加深+文字变淡的反馈，符合"增加点击态"的 UI 要求；颜色成对维护 values/values-night，日夜主题一致。捆绑的充电数值占位逻辑把非法值（0xFFFF）、未充电、枪断开等场景统一重置为占位文案，属于相邻功能的顺手加固，但扩大了本提交的回归范围（充电调试页数值刷新需整体回归）。

## 复盘与经验
- 自绘/静态背景按钮（`@drawable/button`）默认无点击态，车机 UI 验收把"每个可点元素必须有按压反馈"作为固定检查项。
- 点击态资源应成套沉淀（selector drawable + 文字色 selector + 日夜颜色对），新弹窗直接复用，避免每个按钮各写一遍。
- 一个提交只解决一个单号：本例 MainActivity 的充电数值逻辑混入 D 级 UI 单号，测试范围声明与实际改动面不匹配，增加回归漏测风险。
