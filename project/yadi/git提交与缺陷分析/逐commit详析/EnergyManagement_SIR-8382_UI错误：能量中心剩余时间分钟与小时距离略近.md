# SIR-8382 · 能量中心剩余时间"XhYmin"小时与分钟挤在一起
- **提交**：`68625ff9` | 2026-09-15 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心显示剩余充电/续航时间时，"5h30min"样式的文案中 h 与 min 数字紧贴，视觉拥挤，与设计不符。

## 根因分析
`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java` 拼接剩余时间文案时，小时与分钟分支直接字符串相加：`text = hours + "h" + minutes + "min"`，"h"后没有分隔空格，两个数值段落连排（如 `5h30min`）被读成 `5h30` + `min`，观感与设计稿"5h 30min"不一致。纯文案格式问题，无逻辑缺陷。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
+++ b/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -1728,7 +1728,7 @@
         String text;
         if (hours > 0 && minutes > 0) {
-            text = hours + "h" + minutes + "min";
+            text = hours + "h " + minutes + "min";
         } else if (hours > 0) {
             text = hours + "h";
         } else {
```

## 为什么能修复
仅在"小时+分钟同时显示"分支的 `h` 后补一个空格，单时/单分分支（`"5h"`、`"30min"`）不涉及间距问题保持不变，改动最小且无任何逻辑影响。

## 复盘与经验
- 拼接复合单位的显示文案时，把"数值+单位"当作一个整体段落、段落间显式加分隔（空格/间距控件），可避免这类必然提单的视觉问题。
- 该文案是代码内硬编码而非 string 资源，多语言/设计变更时要全局搜索同类拼接；如后续需要本地化差异（如德语 "Std"），应迁入 strings.xml 用占位符格式化。
