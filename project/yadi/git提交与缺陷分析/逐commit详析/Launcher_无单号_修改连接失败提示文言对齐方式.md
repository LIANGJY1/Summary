# 无单号 · [SIR-XXX] 修改连接失败提示文言对齐方式
- **提交**：`0712be37` | 2026-09-16 | dufan | Launcher | feature（UI 样式调整）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
手车互联"连接失败"提示文案的对齐方式调整：由居中（gravity="center"）改为默认左对齐，按 UI 走查意见修正。

## 实现结构
仅改动 `res/layout/activity_link.xml` 1 行：删除连接失败提示 TextView 的 `android:gravity="center"` 属性。

## 关键代码
```xml
<!-- application/Launcher/src/main/res/layout/activity_link.xml -->
 <TextView
     android:layout_width="wrap_content"
     android:layout_height="wrap_content"
     android:layout_marginTop="@dimen/dp_30"
-    android:gravity="center"
     android:text="@string/fail_retry_hint"
     android:textColor="@color/text_default_press"
     android:textSize="24sp" />
```

实现讲解：单行删除，TextView 恢复默认的 start 对齐。注意 `gravity` 控制的是文字在 View 内部的对齐，View 本身 `wrap_content` 且随父布局排布，删除后多行失败文案将自然左对齐。

## 复盘与要点
- **类型标注**：纯 UI 属性调整（一行删除）。
- **小改动也走完整提交流程**：一行 UI 修正独立成提交、带 Change-Id 与测试范围，说明团队对走查类修改也有追溯要求，值得保持。
