# 无单号 · 设置页面10dp顶部间距（UI要求）

- **提交**：`32b44b52` | 2026-08-05 | sgh | Setting | bugfix（UI 微调）
- **缺陷库**：未关联单号

## 说明
单文件 UI 微调：`application/Setting/src/main/res/layout/activity_main.xml` 中，根布局 `LapseTouchLayout` 的 `android:background="@drawable/shape_bg_application"` 下移到内层 `LinearLayout`，根布局改为 `android:paddingTop="10dp"`。效果：状态栏/顶部区域与设置页内容之间留出 10dp 间距，背景由内层容器承载，露出顶部间隙。按"UI 要求"执行的一次性布局调整，无逻辑变更，不深入剖析。

**复盘要点**：要"露出父容器间距"时，正确做法是把背景从父容器挪到内容容器、再给父容器加 padding；只加 padding 不挪背景会让间距被背景色填满而不可见——这是嵌套背景布局的常见细节。
