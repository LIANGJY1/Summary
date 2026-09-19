# SIR-6664 · 里程设置界面各模块间隙略大

- **提交**：`5add88c5` | 2026-08-27 | liqingqing | EnergyManagement | bugfix（UI 微调）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心（提交信息标注 B，以缺陷库为准）

## 问题
能量中心"里程设置"界面两个模块卡片（本次里程/小计里程）之间及与标题区的间隙偏大，与 UI 设计稿不符；界面标题文案也不一致。

## 根因分析
`activity_mileage_management.xml` 中两个模块卡片原先用"顶部对齐标题栏 + margin 定宽"的约束方式：`layout_current_mileage` 为 `320dp` 宽、`layout_marginTop="27dp"`、`layout_constraintTop_toBottomOf="@id/title_bar"`；`layout_subtotal_mileage` 用 `layout_marginEnd="500dp"` 从父容器右侧反推位置。这种"端侧大 margin 定位 + 顶部悬挂"的布局使卡片随内容下沉，模块间与底部留白被放大。修复改为**底边对齐父容器、卡片间用 16dp 水平 margin、距底 12dp** 的锚定方式，同时加宽卡片（320→340dp）并把标题字号 26sp→30sp、容器 104x39dp→120x45dp，标题文案由"里程设置"改为"里程管理"。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml、application/EnergyManagement/src/main/res/values/strings.xml
```diff
--- application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml
             android:id="@+id/layout_current_mileage"
-            android:layout_width="320dp"
+            android:layout_width="340dp"
             android:layout_height="420dp"
             android:layout_marginStart="84dp"
-            android:layout_marginTop="27dp"
+            android:layout_marginBottom="12dp"
-            app:layout_constraintLeft_toLeftOf="parent"
-            app:layout_constraintTop_toBottomOf="@id/title_bar">
+            app:layout_constraintBottom_toBottomOf="parent"
+            app:layout_constraintStart_toStartOf="parent">
（layout_subtotal_mileage：marginEnd=500dp 改为 marginStart=16dp，
 约束改为 constraintBottom_toBottomOf=parent + constraintStart_toEndOf=@id/layout_current_mileage）

--- application/EnergyManagement/src/main/res/values/strings.xml
-    <string name="mileage_settings_title" translatable="false">里程设置</string>
+    <string name="mileage_settings_title" translatable="false">里程管理</string>
```

## 为什么能修复
两个卡片改为"底边锚定父容器 + 相邻水平 16dp margin"后，间距成为显式固定值（16dp/12dp），不再受顶部悬挂布局的隐式留白影响，与设计稿的紧凑排布一致。无逻辑改动，无副作用。

## 复盘与经验
- 用超大 `marginEnd/marginStart`（如 500dp）反推控件位置是脆弱写法，屏幕尺寸变化即错位；相邻控件应直接互相约束。
- 模块间距类 UI bug 优先把"隐式间距"（对齐+悬浮产生的空隙）改造成"显式 margin/约束"，评审可量化。
