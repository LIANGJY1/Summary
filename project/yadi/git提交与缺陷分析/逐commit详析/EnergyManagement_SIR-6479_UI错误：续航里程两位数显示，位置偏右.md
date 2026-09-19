# SIR-6479 · 续航里程两位数时显示位置偏右

- **提交**：`fd3a719a` | 2026-08-27 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心主界面的续航里程数值在两位数（如 88km）时，显示位置较 UI 稿偏右。

## 根因分析
`activity_main.xml` 中承载续航里程的容器（1196dp 宽）用 `layout_constraintBottom_toBottomOf="parent"` + `marginBottom=40dp` 贴底定位，与 dock 栏之间的间距过大，整体位置比设计稿偏高偏右（缺陷库 [why]：与 dock 栏距离远）。容器内数字为水平排列，整体容器位置偏移直接体现为数值显示位置偏离标注点，数字位数少（两位数）时偏移感更明显。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/activity_main.xml`

```diff
--- a/application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ -398,7 +398,7 @@
             android:layout_width="1196dp"
             android:layout_height="120dp"
             android:layout_marginStart="48dp"
-            android:layout_marginBottom="40dp"
+            android:layout_marginBottom="28dp"
             android:orientation="horizontal"
             app:layout_constraintBottom_toBottomOf="parent"
             app:layout_constraintStart_toStartOf="parent">
```

## 为什么能修复
`marginBottom` 从 40dp 调整为 28dp，容器整体下移 12dp，与 dock 栏的间距回到 UI 标注值，续航里程数字的落位与设计稿对齐。单属性纯布局改动，零逻辑风险；需注意两位数/三位数（如 388km）切换时数字增长方向是否会影响与其他元素的相对位置，建议在长短数值两态下各对照一次设计稿。

## 复盘与经验
- "数字位数不同导致位置观感不同"的 UI 单，往往根子在容器定位间距（margin/约束）与设计稿有偏差，位数只是放大器——对齐间距比改数字对齐方式更对症。
- 车机仪表类 UI 与 dock 栏等系统级元素有空间耦合，布局走查时要按"dock 栏可见状态"核对间距标注，不能只看应用内相对位置。
