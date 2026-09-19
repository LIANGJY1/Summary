# SIR-6193 · 黑夜模式下防盗哨兵弹窗布局与UI式样不一致

- **提交**：`e47cf238` | 2026-08-21 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
黑夜模式下防盗哨兵弹窗的正文排版与 UI 设计稿不一致（行距/间距异常）。

## 根因分析
缺陷库根因为"UI问题，修改UI间距"。`dialog_sentinel.xml` 中提示文案 `anti_theft_sentinel_hint` 的 TextView 未设置行距，黑夜模式资源下文案换行后行间过密，与设计稿不符；同时容器 `paddingBottom="@dimen/dp_42"` 使底部留白偏大。修复：给该 TextView 增加 `android:lineSpacingExtra="4sp"` 增加正文行距，并删除容器的 `paddingBottom`，让底部间距由内容自然撑开，与 UI 式样对齐。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/dialog_sentinel.xml（+2/-2）
```diff
@@ application/Setting/src/main/res/layout/dialog_sentinel.xml @@
-    android:paddingTop="@dimen/dp_36"
-    android:paddingBottom="@dimen/dp_42">
+    android:paddingTop="@dimen/dp_36">
...
             android:text="@string/anti_theft_sentinel_hint"
             android:textColor="@color/text_default_press"
+            android:lineSpacingExtra="4sp"
             android:textSize="@dimen/sp_24" />
```

## 为什么能修复
行距与底部间距按设计稿数值校准，黑夜模式下弹窗排版与 UI 式样一致。改动纯视觉、无逻辑风险。

## 复盘与经验
- 主题（白天/黑夜）切换会改变文案字体与换行表现，布局间距要在两套主题下都对照设计稿验证。
- 文案类 TextView 显式声明行距（lineSpacingExtra）而不是依赖主题默认值，可避免跨主题不一致。
