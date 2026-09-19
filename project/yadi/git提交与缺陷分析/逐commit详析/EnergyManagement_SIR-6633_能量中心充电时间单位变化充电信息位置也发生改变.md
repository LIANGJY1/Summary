# SIR-6633 · 充电时间单位变化时充电信息位置漂移
- **提交**：`52091348` | 2026-08-31 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心调试网格里"剩余充电时间"的单位（min/h 等）随数值变化切换时，相邻/同列的其他充电信息（功率、电流等）跟着左右移动，两列不再纵向对齐。

## 根因分析
`activity_main.xml` 的里程调试区是 2×2 网格，四个 cell（`view_mileage_debug_cell_1..4`）及其内层行全部 `layout_width="wrap_content"`：cell 宽度由当前文本实际宽度决定。剩余时间这类"数值+单位"组合（如 "59min"→"1h1min"）切换时 cell 宽度随之伸缩，水平 LinearLayout 里的兄弟 cell 被推挤位移，上下两行也无法对齐——wrap_content 把"数据宽度"变成了"布局宽度"，这是单据"宽度为 wrap_content"的根因。另外内层行同样 wrap_content 时，子 TextView 以行宽作 AT_MOST 上限，文本过宽会被折行/截断。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/layout/activity_main.xml
```diff
--- a/application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ 四个 cell 统一改造（以 cell_1 为例）
                     <LinearLayout
                         android:id="@+id/view_mileage_debug_cell_1"
-                        android:layout_width="wrap_content"
+                        android:layout_width="80dp"
                         ...
                         android:clipChildren="false">
+                    <!-- [bugfix] 两列网格：cell 固定 80dp（与 20sp 四字标签同宽）。数值文本变化
+                         不再引起兄弟列位移，且上下两行纵向对齐。内层行固定 200dp：行宽会作为子
+                         TextView 的 AT_MOST 测量上限，太小会折行或截断（116dp 时 "1h1min" 的 n
+                         被截断）；200dp 保证单行完整测量，配合 clipChildren=false，超宽文本
+                         溢出到列间距绘制而不被裁剪。 -->
                         <LinearLayout
-                            android:layout_width="wrap_content"
+                            android:layout_width="200dp"
                             ...
                             <TextView android:id="@+id/tv_mileage_debug_remaining_time_value"
+                                android:maxLines="1" ... />
                             <TextView android:id="@+id/tv_mileage_debug_remaining_time_unit"
-                                android:layout_marginStart="4dp" ...
```
四个 cell 同步改为 80dp 定宽、内层行 200dp、数值 TextView 加 `maxLines="1"`，容器链加 `clipChildren="false"`，并去掉单位 TextView 的 4dp 起始边距。

## 为什么能修复
网格几何与数据解耦：cell 固定 80dp 后文本再怎么变，兄弟列位置与两行对齐纹丝不动；内层行 200dp 提供了足够的测量上限防折行截断，`clipChildren=false` 让偶发超宽文本溢出到列间距而非被硬裁。修复者在 XML 注释里写明了测量机制与实验依据（116dp 截断复现），非常规范。隐患：定宽方案依赖字号/语言不变，若未来改字号或出更长单位需重新校准 80/200dp 两个经验值。

## 复盘与经验
- "数值+单位"类动态文本参与布局时，禁止用 wrap_content 决定网格列宽，定宽（或 minWidth）是消除布局抖动的标准解。
- Android 里容器宽度会成为子 View 的 AT_MOST 测量上限，"定宽防抖动"与"限宽防截断"要同时满足，`clipChildren=false` 是溢出显示的安全阀。
- 在布局文件里留下"为什么是这个值"的注释（含反例数据），是本次修复最值得学习的工程习惯。
