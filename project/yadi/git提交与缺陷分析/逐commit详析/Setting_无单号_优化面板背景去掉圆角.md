# 无单号 · Setting 主面板背景去圆角（UI 调整）

- **提交**：`4aa52da0` | 2026-09-09 | sgh | Setting | bugfix（实为 UI 优化，未关联单号）
- **缺陷库**：未关联单号

## 问题
按最新 UI 要求，Setting 主面板背景不应带圆角（提交注明 [why] 新需求）。

## 根因分析
纯 UI 样式调整，无逻辑缺陷。原布局使用带圆角的 shape drawable 作为面板背景：`activity_main.xml` 根 LinearLayout 用 `@drawable/shape_bg_application`，`include_back.xml` 的左右分栏 `left_view`/`right_view` 分别用 `@drawable/shape_round_top_left`/`@drawable/shape_round_top_right`（顶部圆角背景），与最新 UI 不符。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/activity_main.xml、application/Setting/src/main/res/layout/include_back.xml（2 文件 +3/-3）
```diff
--- application/Setting/src/main/res/layout/activity_main.xml
-            android:background="@drawable/shape_bg_application"
+            android:background="@color/bg_application"
--- application/Setting/src/main/res/layout/include_back.xml
-        android:background="@drawable/shape_round_top_left"
+        android:background="@color/bg_settingtab"
@@
-        android:background="@drawable/shape_round_top_right"
+        android:background="@color/bg_application"
```

## 为什么能修复
用纯色资源替换圆角 shape 背景，面板边角即变为直角，符合新 UI。仅资源引用替换，无逻辑风险；旧的 shape drawable 若已无其他引用，属遗留死资源，可在后续清理。

## 复盘与经验
- 面板圆角这类视觉规格改动，建议集中检查模块内所有 `shape_round_*` 类背景引用，逐个布局零散替换容易漏改（本次即为多文件联动的小样例）。
- 提交信息 [SIR-XXX] 未关联单号的做法会让 UI 变更无法追溯到需求来源，复盘时需注意其流程合规性。
