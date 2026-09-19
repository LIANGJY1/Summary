# 无单号 · 全局免唤醒 UI 修改（去掉提示 icon）

- **提交**：`45eec65b` | 2026-07-01 | dufan | Setting | feature
- **关联单**：无

**类型**：纯 UI 微调提交（1 个布局 XML，-1 行）。

## 内容一句话
全局免唤醒说明卡片 `dialog_global_wake_up.xml` 中，自带头部控件移除 `app:showBlueAbout="true"` 属性，不再显示右侧蓝色"i"提示图标。

```diff
# application/Setting/src/main/res/layout/dialog_global_wake_up.xml
                 android:layout_marginVertical="@dimen/dp_20"
-                app:showBlueAbout="true"
                 app:titleT="@string/global_wake_up" />
```

**要点**：通过自定义控件的自定义属性（`showBlueAbout`）控制图标显隐，布局一行即完成需求——这类"声明式开关"设计让 UI 微调不需要动代码，是自定义控件属性化的收益示例。
