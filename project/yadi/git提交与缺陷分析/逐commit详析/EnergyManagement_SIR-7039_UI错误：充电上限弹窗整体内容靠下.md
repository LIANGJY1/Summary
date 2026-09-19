# SIR-7039 · 充电上限弹窗整体内容靠下
- **提交**：`e8287029` | 2026-09-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
充电上限说明弹窗（dialog_charge_limit_info）整体内容偏下，与设计稿不符。

## 根因分析
纯布局间距问题：`dialog_charge_limit_info.xml` 中顶部说明文本的 `layout_marginTop` 为 52dp 偏大，而底部按钮 `layout_marginBottom` 仅 24dp 偏小，内容块在竖直方向整体被"压"向弹窗下部。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/dialog_charge_limit_info.xml`
```diff
--- application/EnergyManagement/src/main/res/layout/dialog_charge_limit_info.xml
-            android:layout_marginTop="52dp"
+            android:layout_marginTop="30dp"
（顶部说明文本）

-            android:layout_marginBottom="24dp"
+            android:layout_marginBottom="36dp"
（底部按钮容器，layout_alignParentBottom）
```

## 为什么能修复
顶部 marginTop 由 52dp 收窄到 30dp，底部 marginBottom 由 24dp 增加到 36dp，上下留白一减一增，使内容块在弹窗中重新居中，恢复设计稿比例。纯 XML 尺寸调整，无逻辑副作用，风险仅在视觉回归。

## 复盘与经验
- 车机 UI 走查类缺陷（B 级必现）多数源于布局硬编码 dp 与设计稿对不上，评审时应对照标注逐项核对 margin/padding。
- 弹窗类布局建议用居中约束或 weight 分配留白，减少上下 margin 手工配平带来的偏移。
