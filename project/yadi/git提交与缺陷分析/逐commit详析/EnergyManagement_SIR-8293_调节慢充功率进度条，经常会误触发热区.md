# SIR-8293 · 调节慢充功率进度条易误触发热区导致退出能量中心

- **提交**：`a93e1c57` | 2026-09-16 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 测试错误 · 域 能量中心

## 问题
在能量中心主页调节"慢充功率"进度条时，手指常误触相邻卡片的点击热区，导致直接退出能量中心 APP（里程管理入口被误触发）。

## 根因分析
`activity_main.xml` 底部一排模块卡片原来的排列顺序是：`ll_module_slow_charge`（慢充卡片，宽 276dp，内含功率进度条）在前，`ll_module_mileage`（里程管理卡片，`clickable="true"`，点击后跳转/离开当前界面）紧贴其后。慢充进度条拖动是横向手势，松手或拖出边界时手指极易落在右侧相邻的里程管理卡片上——该卡片可点击且行为等同"离开当前界面"，于是表现为"误触发热区导致退出"。注意缺陷库状态为"测试错误"，说明该单在验证环节有过反复，但代码侧确实做了布局级规避。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/layout/activity_main.xml
```diff
--- application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ 底部 LinearLayout：里程管理卡片整体前移
+            <LinearLayout
+                android:id="@+id/ll_module_mileage"
+                android:layout_width="126dp"
+                android:layout_height="120dp"
+                android:background="@drawable/bg_bottom_card_mileage"
+                android:clickable="true"
+                ...（节点内容不变，仅位置移动）/>
             <FrameLayout
                 android:id="@+id/ll_module_slow_charge"
                 android:layout_width="276dp"
                 android:layout_height="120dp"
+                android:layout_marginStart="10dp"
                 android:background="@drawable/bg_bottom_card_small">
@@ 原位置的里程管理卡片被删除
-            <LinearLayout
-                android:id="@+id/ll_module_mileage"
-                ...
-            </LinearLayout>
```

## 为什么能修复
把里程管理卡片移到慢充卡片左侧，10dp 间距（`marginStart`）随之转移到慢充卡片上；进度条的横向拖动手势向右滑出时不再落在"可点击退出"的里程卡片上，误触热区消失。纯 XML 顺序调整、控件 id 与属性不变，代码零改动，回归风险集中在两个卡片的视觉顺序是否符合最新 UI 稿。隐患：这只是"错开手势与热区"的空间规避，若进度条自身的触摸边界（热区外溢）不收敛，向左滑出仍可能碰到左侧卡片。

## 复盘与经验
- 横向滑杆/进度条旁不要放"高代价点击"控件（退出、跳转类），布局排布阶段就要做手势热区隔离设计。
- 布局级规避（调整相邻关系）是快速止血手段，但根因级的修法通常是给滑杆收紧 `touchDelegate`/父容器拦截（如本布局外层的 `LapseTouchLayout`）。
- 状态为"测试错误"的单子也要看代码是否确有变更——本例即存在真实布局改动，归档时不能一律按"无改动"处理。
