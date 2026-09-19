# SIR-7067 · 累计总行驶里程数值字重与 UI 不一致
- **提交**：`992703f1` | 2026-09-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心"累计总行驶里程"数值字重与 UI 设计稿不一致（应为 semibold，实际按默认字体渲染），同时主页"充电上限"标题字重偏粗。

## 根因分析
该工程字体不走 XML 的 `textStyle`，而是统一由 `EnergyTypeface.apply(view, typeface)` 在代码里逐个控件应用。两处遗漏：`MileageManagementActivity.onCreate()` 中 `binding.tvTotalMileageValue`（累计总行驶里程数值）没有调用 `EnergyTypeface.apply(..., semibold)`，落入系统默认字重；反向问题在 `activity_main.xml` 的"充电上限"标题上——XML 里残留 `android:textStyle="bold"`，而未在 `EnergyTypeface.apply` 清单中声明，导致其显示为系统粗体而非指定字库字重。

## 关键代码修改
改动文件：`.../view/ui/MainActivity.java`、`.../view/ui/MileageManagementActivity.kt`、`res/layout/activity_main.xml`、`res/values/strings.xml`
```diff
--- .../energymanagement/view/ui/MainActivity.java
         EnergyTypeface.apply(binding.km1, semibold);
+        EnergyTypeface.apply(binding.chargeLimit, regular);
         EnergyTypeface.apply(binding.tvMileageManagementLabel, regular);

--- .../energymanagement/view/ui/MileageManagementActivity.kt
         setContentView(binding!!.root)
+        EnergyTypeface.apply(binding!!.tvTotalMileageValue, EnergyTypeface.semibold(this))

--- application/EnergyManagement/src/main/res/layout/activity_main.xml
                             android:text="充电上限"
                             android:textColor="@color/text_default_default"
-                            android:textSize="24sp"
-                            android:textStyle="bold" />
+                            android:textSize="24sp" />
```
另将 `total_driving_mileage_label` 文案中的全角括号"（km）"改为半角"(km)"。

## 为什么能修复
给 `tvTotalMileageValue` 补上 semibold 字体应用，数值字重与设计稿一致；删掉 XML 中游离的 `textStyle="bold"` 并把 `chargeLimit` 纳入 `EnergyTypeface` 统一管理，消除"XML 字重与代码字库双轨"造成的失控。隐患较小，只需回归主页与里程管理页标题、数值两处显示。

## 复盘与经验
- 当字重统一由代码（Typeface）接管时，XML 里的 `textStyle` 必须清场，否则两套机制叠加或遗漏都会产生视觉偏差。
- "逐控件 apply 字体"的模式天然易漏新增控件，更好的做法是封装到 Base 类/Binding 适配器按 style 或 tag 批量应用。
- 文案里全角/半角括号混用也是 UI 走查常见扣分点，字符串资源应统一规范。
