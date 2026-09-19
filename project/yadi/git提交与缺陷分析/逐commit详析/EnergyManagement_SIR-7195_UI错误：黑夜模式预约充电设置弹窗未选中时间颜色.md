# SIR-7195 · 黑夜模式预约充电弹窗未选中时间颜色与 UI 不一致
- **提交**：`ee459fb0` | 2026-09-02 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
黑夜模式下，预约充电设置弹窗的滚轮时间选择器（`WheelScrollView`）中未选中时间项颜色过深/过亮，与 UI 设计稿不符（同批顺带修正里程管理标签、停止充电按钮的日夜配色）。

## 根因分析
`WheelScrollView` 两个构造函数中未选中项颜色硬编码引用通用灰色 `R.color.gray_400`——该颜色不分日夜模式。黑夜模式下 `gray_400` 与弹窗深色背景对比失衡，与设计稿要求的半透明浅灰不一致。正确做法在该工程已有先例（`energy_distribution_ring_bg_color` 等语义化 day/night 颜色对），此控件没有接入。

## 关键代码修改
改动文件：`.../view/custom/WheelScrollView.java`、`res/values/colors.xml`、`res/values-night/colors.xml`、`res/drawable/bg_stop_charging_button.xml`、`res/layout/activity_main.xml`
```diff
--- .../energymanagement/view/custom/WheelScrollView.java
     public WheelScrollView(Context context, AttributeSet attrs) {
         super(context, attrs);
-        defaultTextColor = ContextCompat.getColor(context, R.color.gray_400);
+        defaultTextColor = ContextCompat.getColor(
+                context, R.color.reservation_time_unselected_text_color);
         defaultSelectedTextColor = ContextCompat.getColor(context, R.color.text_default_default);
     }
（两个构造函数同步修改）

--- application/EnergyManagement/src/main/res/values/colors.xml
+    <color name="reservation_time_unselected_text_color">#4D1F222A</color>
+    <color name="stop_charging_button_bg_color">@color/bg_button_default</color>
+    <color name="mileage_management_label_text_color">#991F222A</color>

--- application/EnergyManagement/src/main/res/values-night/colors.xml
+    <color name="reservation_time_unselected_text_color">#4DEEEEEE</color>
+    <color name="stop_charging_button_bg_color">#5A5C61</color>
+    <color name="mileage_management_label_text_color">#99EEEEEE</color>
```
（`bg_stop_charging_button.xml` 与"里程管理"标签改引新语义色，实现日夜自动切换。）

## 为什么能修复
新增三个语义化颜色 key 并给出 day/night 成对取值：未选中时间白天 `#4D1F222A`、黑夜 `#4DEEEEEE`（30% 透明浅灰），由资源系统按 `values-night` 自动切换，`WheelScrollView` 改引语义色后黑夜模式颜色与设计稿一致；顺带把"停止充电"按钮背景、"里程管理"标签纳入同一机制。该方案比 `1e0c3fb3` 在代码里判 `uiMode` 更优——用回了资源系统的正道。风险：注意未选中项颜色在全模式（不止预约弹窗）统一变化，需回归滚轮其他使用处。

## 复盘与经验
- 自绘控件需要日夜配色时，优先新增语义化颜色 key + `values-night` 资源对，而不是在代码里判断 `uiMode`（后者有热切换不刷新问题，参见 `1e0c3fb3`）。
- 通用灰色（gray_400 等）直接用于文字/前景色是日夜模式翻车高发点，语义化命名（xxx_unselected_text_color）才能承载模式差异。
- 一次 bugfix 内把同页相关控件的配色一起收敛到语义色，属于合理范围的顺带治理。
