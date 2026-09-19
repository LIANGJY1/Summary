# SIR-6666 · 里程管理界面数字与单位间隙略大

- **提交**：`7fa3e340` | 2026-08-27 | liqingqing | EnergyManagement | bugfix（UI 微调）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心（提交信息标注 B，以缺陷库为准）

## 问题
里程管理界面中数值与单位（如 `12.3 kwh / 100 km`）之间空隙过大，与 UI 设计稿不符。

## 根因分析
`MileageManagementActivity` 的 `getSpannableString(value, unit)` 用 `SpannableString("$value  $unit")` 拼接，**硬编码了两个空格**；百公里电耗更是把 `" / 100 km"` 作为独立小字号 Span（`getUnitSpannableString`，16sp）追加，`" / "` 又带空格与斜杠两侧空隙。多层空格+多段 Span 叠加使数字与单位视觉间距明显偏大。修复删除拼接串中的固定双空格（`"$value$unit"`），并把 `"kwh / 100 km"` 改为整体单位串 `"kWh/100km"` 走同一 `getSpannableString`，同时删除不再使用的 `getUnitSpannableString()`。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
@@ applyTripEavCnsUI
         binding?.lvlSubtotalAvgConsumption?.setRightText(
-            SpannableStringBuilder()
-                .append(getSpannableString(displayValue, "kwh"))
-                .append(getUnitSpannableString(" / 100 km"))
+            getSpannableString(displayValue, "kWh/100km")
         )

@@ getSpannableString
-        val mileage = SpannableString("$value  $unit")
+        val mileage = SpannableString("$value$unit")
（另：默认值处同样改为 getSpannableString("0", "kWh/100km")；删除 getUnitSpannableString 方法）
```

## 为什么能修复
数字与单位之间不再有插入的空格字符，单位串（含斜杠）无额外空白，视觉间距收窄为字体自身字距，与设计稿一致；顺带把单位大小写规范为 `kWh/100km`。删除冗余方法降低后续维护分叉。无逻辑风险，仅文案渲染变化。

## 复盘与经验
- 用 SpannableString 拼数值+单位时，间距控制应在字符串模板一处统一，避免"值串带空格 + 单位串带空格"叠加放大。
- 同类 UI 拼接逻辑多处复制（如默认值与真实数据两条路径）时，改一处漏一处的风险高，收敛到单一函数可一并修好。
