# SIR-8169 · 里程设置界面"本次里程"环形图位置偏右

- **提交**：`d4d12944` | 2026-09-11 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心里程设置界面中，"本次里程"卡片的能量分布环形图（`energy_distribution_view_this_mile`）位置比设计稿偏右。

## 根因分析
`activity_mileage_management.xml` 中"本次里程"卡片的定位方式混乱：容器用 `paddingHorizontal="@dimen/dp_24"` 统一内缩，环形图再叠加 `marginStart="26dp"`，实际距卡片左缘 24+26=50dp，超出设计稿位置；而环形图纵向 `marginBottom="60dp"` 也与规格不符。本次返工改为"去掉容器水平 padding、子元素各自显式定 margin"的方案：环形图改为 `marginLeft="42dp"`（净左移 8dp）、`marginBottom="50dp"`，标题、行驶时长/里程行统一 `marginLeft="42dp"`，两块卡片（本次/小记）的 `paddingTop` 同步从 16dp 调为 27dp，纵向节奏一并对齐。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml`
```diff
@@ 本次里程卡片容器
-            android:paddingHorizontal="@dimen/dp_24"
-            android:paddingTop="@dimen/dp_16"
+            android:paddingTop="27dp"
@@ 环形图
                 android:id="@+id/energy_distribution_view_this_mile"
                 android:layout_width="240dp"
                 android:layout_height="86dp"
-                android:layout_marginStart="26dp"
-                android:layout_marginBottom="60dp"
+                android:layout_marginLeft="42dp"
+                android:layout_marginBottom="50dp"
@@ 行驶时长/标题等子元素统一增加
+                android:layout_marginLeft="42dp"
```

## 为什么能修复
环形图相对卡片的水平偏移从 50dp 收敛为 42dp（去掉叠加的 padding 后用单一 margin 表达），消除"偏右"；两卡片内部所有元素统一 42dp 左缘对齐、paddingTop 统一 27dp，整套间距回到设计规格。纯布局调整无逻辑风险；隐患是 `marginLeft` 与 `marginStart` 混用（本次多处用了 `marginLeft`），在仅 RTL 镜像的场景会失去自适应性，车机固定 LTR 下无实际影响。

## 复盘与经验
- 同一维度的偏移不要"容器 padding + 子元素 margin"双层叠加，取值难以心算、极易与设计稿错位；固定容器内建议子元素各自显式 margin。
- "位置偏右/偏左"类缺陷要算总偏移（padding+margin+约束基准），只看单层 margin 常常找错方向。
- 一次提交把同卡片全部子元素的间距一并对齐（而不是只挪环形图），避免修一个偏一个。
