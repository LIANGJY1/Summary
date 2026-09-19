# 无单号 · BTMusic 主界面设置 10dp 顶部间距

- **提交**：`1b758453` | 2026-08-06 | daizhecheng | BTMusic | bugfix（UI 调整类）
- **缺陷库**：未关联单号

## 问题
BTMusic 主界面内容顶到屏幕上边缘，不符合 UI 稿要求的 10dp 顶部间距。

## 根因分析
纯 UI 样式问题：`application/BTMusic/src/main/res/layout/activity_main.xml` 根布局下的 `com.yadea.common.widgets.LapseTouchLayout` 未设置 paddingTop，内容直接贴合状态栏/屏幕顶部。提交元数据也明确标注 `[why]UI要求`、影响等级 C，属于视觉走查问题而非逻辑缺陷。

## 关键代码修改
改动文件：`application/BTMusic/src/main/res/layout/activity_main.xml`
```diff
// application/BTMusic/src/main/res/layout/activity_main.xml
     <com.yadea.common.widgets.LapseTouchLayout
         android:layout_width="match_parent"
         android:layout_height="match_parent"
+        android:paddingTop="@dimen/dp_10"
         android:clickable="true"
         android:focusable="true">
```

## 为什么能修复
在根触摸布局上加 `paddingTop=10dp`，其全部子内容整体下移 10dp，满足 UI 稿。单属性布局改动，无逻辑副作用；仅注意 padding 不影响 LapseTouchLayout 的点击区域（仍是全屏），行为无变化。

## 复盘与经验
- UI 走查类问题最典型的最小修复：间距统一走 `@dimen/dp_xx` 维度资源而非硬编码数值，便于全局换肤/适配调整。
- 此类 C 级视觉问题与逻辑缺陷混在同一 bugfix 流程中管理时，应通过影响等级字段区分回归范围。
