# 无单号 · 蓝牙电话应用背景增加阴影

- **提交**：`aa7ee63d` | 2026-08-06 | liujinfeng | BTPhone | bugfix（UI 调整类）
- **缺陷库**：未关联单号

## 问题
蓝牙电话主界面背景没有阴影效果，卡片与窗口底层贴平，不符合 UI 设计稿的立体层次要求。

## 根因分析
纯样式问题：`activity_main.xml` 中承载主内容的 `ConstraintLayout` 只设置了 `android:background="@drawable/bg_main"`，没有 `elevation`，View 无 Z 轴高度，系统不会为它绘制投影；且该应用窗口本身是透明主题（见 `9b06b94b` 中 `windowIsTranslucent=true`），背景直接透出下层内容，更显得没有边界感。

## 关键代码修改
改动文件：`application/BTPhone/src/main/res/layout/activity_main.xml`
```diff
// application/BTPhone/src/main/res/layout/activity_main.xml
     <androidx.constraintlayout.widget.ConstraintLayout
         android:layout_width="match_parent"
         android:layout_height="match_parent"
-        android:background="@drawable/bg_main">
+        android:background="@drawable/bg_main"
+        android:elevation="32dp">
```

## 为什么能修复
`elevation=32dp` 赋予该容器 Z 轴高度，系统 OutlinesShadow 渲染管线会基于 `bg_main` 的 outline（圆角矩形）在其周围绘制环境光/点光源投影，卡片立刻与透明窗口底层分离出层次。与 `e33b5a70` 用 drawable layer-list 画假阴影不同，这里是真投影方案。注意点：elevation 阴影只在硬件加速开启且 background 提供有效 outline 时生效；32dp 的大值阴影范围大，若窗口裁剪（clipToPadding/clipChildren）不当可能被截断，需真机确认。

## 复盘与经验
- 浮层/卡片式窗口的立体感有两个实现层次：drawable 画假阴影（兼容性最好）与 `elevation` 真投影（效果自然），按项目渲染约束选择，同一应用内应统一。
- 透明主题（`windowIsTranslucent`）应用依赖 elevation 阴影与底部分隔，视觉走查要在实际透明叠层场景下看，单独截图看不出问题。
- elevation 生效前提是 background 的 outline，九宫格/透明边距背景可能拿不到正确 outline，导致阴影形状异常。
