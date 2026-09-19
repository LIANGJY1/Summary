# SIR-5869 · HUD 蓝牙电话图标后出现黑色背景框
- **提交**：`1ffe5fed` | 2026-08-13 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 HUD

## 问题
补盲影像触发在 HUD 显示时拨打蓝牙电话，电话卡片（挂断等图标）后面出现黑色背景框。

## 根因分析
`application/BTPhone/src/main/res/layout/float_hud_window.xml` 根容器直接写了 `android:background="@color/black"`。HUD 场景要求浮窗内容无底色叠加（补盲影像之上只应显示图标本体），黑色底随窗口一起合成，就在图标后面呈现为黑色背景块。

## 关键代码修改
改动文件：`application/BTPhone/src/main/res/layout/float_hud_window.xml`
```diff
--- application/BTPhone/src/main/res/layout/float_hud_window.xml
     xmlns:app="http://schemas.android.com/apk/res-auto"
     android:layout_width="wrap_content"
     android:layout_height="wrap_content"
-    xmlns:tools="http://schemas.android.com/tools"
-    android:background="@color/black">
+    xmlns:tools="http://schemas.android.com/tools">
```

## 为什么能修复
移除根容器黑色背景后，HUD 浮窗变为无底色容器，仅图标/文字可见，黑色背景框随之消失。透明窗口需依赖 `PixelFormat.TRANSLUCENT` 等窗口参数配合（该浮窗既有配置已满足）。无功能副作用，唯一注意点是若依赖黑底保证对比度的极端场景（强光下图标可读性）会受影响。

## 复盘与经验
- HUD/AR 类叠加显示对"背景色"极其敏感：普通屏上无感的 `background="@color/black"`，到透明叠加场景就会变成显性黑块；浮窗布局默认应无背景。
- 这类问题一行 diff 即可修复，但定位成本高（涉及窗口合成），布局评审时应对叠加型浮窗的 background 属性保持警惕。
- 同一布局若同时用于普通屏与 HUD，应拆分两份布局或用主题属性控制背景，而不是共用一个写死底色的文件。
