# YD-392788 · 能耗分布条百分比带小数点
- **提交**：`0724857b` | 2026-07-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：未关联单号（YD 内部单）

## 问题
里程设置界面的能耗分布条上，电机系统/其他能耗占比显示了形如 "33.3%" 的小数，UI 要求整数百分比。

## 根因分析
能耗分布是自绘 View：`EnergyDistributionView`（`application/EnergyManagement/.../view/custom/EnergyDistributionView.java`）在 `onDraw` 里用 `canvas.drawText` 画标签，格式化串写的是 `String.format("%.1f%%", motorDisplay)` 与 `String.format("%.1f%%", otherDisplay)`，`%.1f` 保留一位小数；而占比计算结果本就是浮点数（`motorDisplay/otherDisplay`），于是带小数的文本被直接画到了分布条下。UI 规格只要整数百分比，属格式化占位符选错。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyDistributionView.java`（另大量调整 `res/values/strings.xml` 为字符串补 `translatable="false"`，与本缺陷无直接关系，属顺带整理）
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyDistributionView.java
@@ -181,12 +181,12 @@ public class EnergyDistributionView extends View {
-        canvas.drawText(String.format("%.1f%%", motorDisplay), 19, 63, labelPaint);
+        canvas.drawText(String.format("%.0f%%", motorDisplay), 19, 63, labelPaint);
         // 其他能耗标签
-        canvas.drawText(String.format("%.1f%%", otherDisplay), 130, 63, labelPaint);
+        canvas.drawText(String.format("%.0f%%", otherDisplay), 130, 63, labelPaint);
```

## 为什么能修复
`%.0f` 将占比四舍五入为整数输出，"33.3%" 变 "33%"，符合 UI 规格。`%.0f` 是四舍五入而非截断，两标签之和可能与 100% 有 ±1 的取整误差，若 UI 对"合计必须 100%"敏感需要额外配平，本次未处理。

## 复盘与经验
- 显示格式应与 UI 标注逐一对照，`%.1f` 与 `%.0f` 一字之差就是一条缺陷；数据格式化建议抽成常量或工具方法统一管理。
- 自绘 View 的 `canvas.drawText` 常被遗漏在 UI 走查之外，评审时应重点检查绘制文本的格式串。
