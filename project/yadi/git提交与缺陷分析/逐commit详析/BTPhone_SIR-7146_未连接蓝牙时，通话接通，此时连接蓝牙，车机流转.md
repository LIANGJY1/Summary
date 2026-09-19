# SIR-7146 · 未连蓝牙接通后连接蓝牙，车机流转弹窗 UI 样式错误
- **提交**：`f99ec86c` | 2026-09-02 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
未连接蓝牙时通话接通，随后连接蓝牙触发车机流转，流转确认弹窗（float_circulation_window）整体样式与设计稿不符：弹窗背景/圆角/尺寸、标题正文字号、确认/取消按钮配色均错误。

## 根因分析
流转弹窗布局 `float_circulation_window.xml` 是按旧样式实现：根布局用 `bg_dialog_confirm` 背景、`wrap_content` 高度、无顶部定位 margin；标题 `sp_20` 且 `textStyle="bold"`（该工程字体由字库接管，bold 渲染异常）；正文 `sp_16`；按钮 `sp_18`；取消按钮 `bg_btn_light` 与确认按钮同色（`bg_button_suggest`）+ 白字，视觉上双主按钮。按钮圆角 drawable `bg_btn_dark/light` 圆角 18dp 也与规范 12dp 不符。

## 关键代码修改
改动文件：`application/BTPhone/src/main/res/layout/float_circulation_window.xml`、`res/drawable/bg_btn_dark.xml`、`res/drawable/bg_btn_light.xml`
```diff
--- .../res/layout/float_circulation_window.xml
     <LinearLayout ...
-    android:layout_height="wrap_content"
+    android:layout_height="@dimen/dp_272"
-    android:background="@drawable/bg_dialog_confirm"
+    android:background="@drawable/bg_dialog"
+    android:layout_marginTop="@dimen/dp_129"
...
-        android:textSize="@dimen/sp_20"
-        android:textStyle="bold"
+        android:textSize="@dimen/sp_28"
+        android:textFontWeight="500"
（标题）
-        android:textSize="@dimen/sp_16"
+        android:textSize="@dimen/sp_24"
（正文）
-            android:textColor="@color/text_white_default"
-            android:textSize="@dimen/sp_18"
+            android:textColor="@color/text_default_default"
+            android:textSize="@dimen/sp_28"
（取消按钮：改为默认色深字，主次分明）

--- .../res/drawable/bg_btn_light.xml
-    <solid android:color="@color/bg_button_suggest"/>
-    <corners android:radius="18dp"/>
+    <solid android:color="@color/bg_button_default" />
+    <corners android:radius="12dp"/>
--- .../res/drawable/bg_btn_dark.xml
-    <corners android:radius="18dp"/>
+    <corners android:radius="12dp"/>
```

## 为什么能修复
布局逐项对齐设计稿：固定高度 272dp + `bg_dialog` 背景 + 顶部 129dp 定位；标题 28sp/字重 500（弃用游离的 `textStyle="bold"`，与全局 EnergyTypeface/字库机制一致）；正文 24sp；确认（深色底白字）/取消（默认底深字）主次分明；按钮圆角统一 12dp。风险极低，仅需回归流转弹窗白天/黑夜两套主题。

## 复盘与经验
- "临调用场景临时拼的弹窗"（流转确认窗）最容易偏离设计规范，评审新弹窗时应对照标准弹窗组件（bg_dialog、字号阶梯、主次按钮）逐项核对。
- `textStyle="bold"` 与工程字库机制冲突的老问题再次出现（见 `992703f1`），应统一用 `textFontWeight` 或字库应用，建议加 lint 检查。
- `bg_btn_light` 与确认按钮同色会让用户无法区分主次操作，安全确认类弹窗必须保证视觉层级。
