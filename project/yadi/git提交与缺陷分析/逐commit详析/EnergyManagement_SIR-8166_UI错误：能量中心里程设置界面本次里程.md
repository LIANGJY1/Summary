# SIR-8166 · 能量中心里程设置界面"本次里程/小计里程"信息偏左

- **提交**：`a151ded5` | 2026-09-16 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
能量中心"里程管理"设置界面中，本次里程、小计里程信息整体偏左，与 UI 稿不符。

## 根因分析
布局 `activity_mileage_management.xml` 中承载里程信息的容器，在 `layout_marginStart="84dp"` 的基础上又叠加了 `android:paddingHorizontal="@dimen/dp_24"`。margin+padding 双重水平偏移导致内容比设计稿整体左移（相对内容区）24dp，即元数据所说的"额外多了24dp的间距"。修复即删除这个水平 padding，保留上下 padding（`paddingTop="27dp"`）不动。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml
```diff
--- application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml
@@             android:layout_marginStart="84dp"
             android:layout_marginBottom="12dp"
             android:background="@drawable/frame_21170"
-            android:paddingHorizontal="@dimen/dp_24"
             android:paddingTop="27dp"
             app:layout_constraintBottom_toBottomOf="parent"
             app:layout_constraintStart_toStartOf="parent">
```

## 为什么能修复
去掉与设计稿冲突的 24dp 水平内边距后，容器背景（`frame_21170`）与内部文字的相对位置恢复 UI 标注值。删除的是 `paddingHorizontal`，左右一起归零，不会出现只修一边导致的新偏移；背景拉伸范围不变，仅内容位置变化，回归风险低。

## 复盘与经验
- XML 里 margin 与 padding 叠加是 UI 偏移类缺陷的最常见来源，排查时先核对"设计稿要的是 margin 还是 padding"。
- `paddingHorizontal` 这类简写属性一次改两边，删除时要想清楚另一侧是否也被设计稿需要。
- 对 C 级必现 UI 错误，一行 diff 即可闭环，成本最低的缺陷类型，但也最应在 UI 走查阶段前置拦截。
