# SIR-7850 · 充电剩余时间/充电功率文本截断未自适应

- **提交**：`0faa87e8` | 2026-09-11 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 待测试验证 · 域 能量中心

## 问题
能量中心主页充电信息区，"充电剩余时间"（如 `1h1min`）和"充电功率"数值在某些取值下被截断显示不全。

## 根因分析
这是对该区域一次"溢出绘制"方案的返工。此前 `activity_main.xml` 里用注释记录的方案是：数值行固定 200dp 宽 + `clipChildren="false"`，让超宽文本溢出到列间距绘制来规避截断（注释里还记载了 116dp 时 `1h1min` 的 n 被截断的实测）。但 200dp 溢出绘制在视觉上与相邻列内容重叠/越界，等效于"间距不对"，且 `fontFeatureSettings="tnum, lnum"` 中的 `tnum`（等宽数字）让每个数字占满等宽格，文本实际宽度被进一步放大，更容易溢出。缺陷库根因"间距不对"，本次改回按设计规格的固定单元格尺寸（114dp/115dp × 49dp），并去掉 `tnum` 让数字按比例宽度排版，从源头收窄文本宽度。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/activity_main.xml`
```diff
@@ 根布局
         android:background="@drawable/main_bg_theme"
+        android:clipChildren="false"
@@ 充电剩余时间列
-                    <!-- [bugfix] 两列网格：cell 固定 80dp … 200dp 保证单行完整测量，
-                         配合 clipChildren=false，超宽文本溢出到列间距绘制而不被裁剪。 -->
                     <LinearLayout
-                        android:layout_width="200dp"
-                        android:layout_height="wrap_content"
+                        android:layout_width="114dp"
+                        android:layout_height="49dp"
@@
                         <TextView
                             android:id="@+id/tv_mileage_debug_remaining_time_value"
-                            android:layout_width="wrap_content"
-                            android:layout_height="wrap_content"
-                            android:fontFeatureSettings="tnum, lnum"
+                            android:layout_width="114dp"
+                            android:layout_height="49dp"
+                            android:fontFeatureSettings="lnum"
+                            android:gravity="bottom"
                             android:maxLines="1"
@@ 充电功率列
                     <LinearLayout
-                        android:layout_width="200dp"
-                        android:layout_height="wrap_content"
+                        android:layout_width="115dp"
+                        android:layout_height="49dp"
```

## 为什么能修复
单元格与 TextView 固定为设计规格尺寸（114/115dp × 49dp、`gravity="bottom"` 底对齐），去掉 `tnum` 后数字采用比例字宽，常见取值的实际渲染宽度收窄到单元格内，不再依赖 200dp 溢出绘制，截断与越界同时消除；根布局补充 `clipChildren="false"` 保留极端长值时的溢出容错。隐患：`maxLines="1"` + 固定宽下极长取值（如 `10h59min`）仍可能裁剪，需车机字体实测边界；等宽改比例字宽后数字跳动时列宽会轻微变化，之前注释担心的"文本变化引起兄弟列位移"因单元格固定宽度而被挡住。

## 复盘与经验
- 用"溢出绘制"掩盖文本超宽只是把截断变成重叠，二次缺陷（间距/重叠）往往比原缺陷更明显；正确方向是控制文本实际宽度（字体特性、缩写、自适应字号）。
- `fontFeatureSettings` 的 `tnum`（等宽数字）会显著加宽数字串，空间紧张的场景慎用，`lnum` 单独使用通常即可满足对齐需求。
- 布局里留下的方案注释是宝贵的复盘材料——本次正是注释记载的"116dp 会截断"实测在指导返工，但也说明该方案本身未被验证视觉接受度。
