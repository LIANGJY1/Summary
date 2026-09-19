# SIR-8365 · 控制中心蓝牙手机/耳机图标间距过近
- **提交**：`a4f9d650` | 2026-09-14 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
蓝牙手机与蓝牙耳机同时连接时，控制中心里耳机图标（`vector_bt_helmet`）紧贴手机蓝牙图标，两个图标距离略近，与 UI 设计不符。

## 根因分析
`application/SystemUI/src/main/res/layout/layout_basic_services.xml` 中，耳机图标通过 `app:layout_constraintStart_toEndOf="@id/iv_bluetooth_1"` 约束在手机蓝牙图标右侧，水平间距仅由 `android:layout_marginStart="@dimen/dp_8"` 控制。8dp 是设计还原时的取值偏小，两图标同时可见时视觉上过于拥挤。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/layout_basic_services.xml
```diff
--- a/application/SystemUI/src/main/res/layout/layout_basic_services.xml
+++ b/application/SystemUI/src/main/res/layout/layout_basic_services.xml
@@ -32,7 +32,7 @@
                 android:layout_width="wrap_content"
                 android:layout_height="wrap_content"
                 android:src="@drawable/vector_bt_helmet"
-                android:layout_marginStart="@dimen/dp_8"
+                android:layout_marginStart="@dimen/dp_14"
                 app:layout_constraintBottom_toBottomOf="parent"
                 app:layout_constraintStart_toEndOf="@id/iv_bluetooth_1"
                 android:visibility="gone"/>
```

## 为什么能修复
把 `iv_bluetooth_1` 右侧耳机图标的 `marginStart` 从 8dp 调到 14dp，直接放大两图标水平间距，纯布局参数修正，无任何逻辑与状态影响；图标默认 `gone`，单设备连接时布局不受影响。

## 复盘与经验
- 间距/字号类 UI 单多为设计标注还原偏差，修改前应对照设计稿标注值，避免凭感觉取数。
- 用约束布局排布"可能同时出现的多个状态图标"时，间距要按"全部可见"的最挤场景核对，而不是只看单图标态。
