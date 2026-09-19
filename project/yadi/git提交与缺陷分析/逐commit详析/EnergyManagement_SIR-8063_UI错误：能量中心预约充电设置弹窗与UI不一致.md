# SIR-8063 · 预约充电设置弹窗 UI 与设计稿不一致

- **提交**：`e1a8c0ca` | 2026-09-10 | liqingqing | EnergyManagement | bugfix（UI 对版修复）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心"预约充电设置"弹窗的整体尺寸、背景、时间选择器样式与 UI 设计稿不一致（缺陷库记"UI变更"，即按新设计稿对版）。

## 根因分析
旧实现按一版已废弃的设计稿开发，与新稿差异遍布布局与自绘控件：① `ReservationChargingDialog` 常量 `DIALOG_WIDTH_DP=660/DIALOG_HEIGHT_DP=494`，且 `syncSkin()` 里错用了 `R.drawable.time_picker` 作为弹窗背景（那是时间滚轮的资源）；② `TimePickerView`（自绘 ViewGroup）是"时:分"冒号布局（COLON_WIDTH_DP=32），新稿要求"时/分"单位字布局，且滚轮宽 56dp、选中字号 24sp 均偏小；③ `dialog_reservation_charging.xml` 含副标题、label 居中对齐滚轮等旧排版，与新稿的固定边距（133dp/590dp）、选中行半透明遮罩条（#0F393F4D）不符；④ 确认按钮背景是 279x74 矢量圆角（18dp），新稿为 12dp 圆角 #545F72。

## 关键代码修改
改动文件：view/custom/ReservationChargingDialog.java、TimePickerView.java、WheelScrollView.java、drawable/dialog_smal.xml、drawable/reservation_confirm_bg.xml、layout/dialog_reservation_charging.xml（6 文件 +118/-94）
```diff
--- application/EnergyManagement/src/main/java/.../view/custom/ReservationChargingDialog.java
-    private static final int DIALOG_WIDTH_DP = 660;
-    private static final int DIALOG_HEIGHT_DP = 494;
+    private static final int DIALOG_WIDTH_DP = 840;
+    private static final int DIALOG_HEIGHT_DP = 480;
@@ syncSkin()
-        rootLayout.setBackgroundResource(R.drawable.time_picker);
+        rootLayout.setBackgroundResource(R.drawable.dialog_smal);
--- application/EnergyManagement/src/main/java/.../view/custom/TimePickerView.java
@@ addChildView()
-        TextView colonText = new TextView(context);
-        colonText.setText(":");
+        TextView hourUnitText = createUnitText(context, "时");
         WheelScrollView minuteWheel = new WheelScrollView(context, attrs, WheelScrollView.WHEEL_TYPE.MINUTE_RESERVATION);
+        TextView minuteUnitText = createUnitText(context, "分");
         addView(hourWheel);
-        addView(colonText);
+        addView(hourUnitText);
         addView(minuteWheel);
+        addView(minuteUnitText);
--- application/EnergyManagement/src/main/res/drawable/reservation_confirm_bg.xml
-<vector ... 279x74 圆角18dp path ...>
+<shape><corners android:radius="12dp" /><solid android:color="#545F72" /></shape>
```
（WheelScrollView：DEFAULT_ITEM_WIDTH_DP 56→100、DEFAULT_SELECTED_TEXT_SIZE 24→30；布局整版重排：去副标题、label 定宽定距、新增选中行 64dp 半透明遮罩 View、时间滚轮宽 144→280dp）

## 为什么能修复
这是一次"按新设计稿重写弹窗"的对版提交：尺寸常量、背景资源、控件结构（冒号→单位字）、字号、按钮形状逐项对齐新 UI，视觉差异从根上消除。代码层的实质改进有两点：`TimePickerView.onMeasure/onLayout` 的列宽计算同步改为"时轮+单位+分轮+单位"四列，避免只改布局不改自绘导致的错位；单位字颜色与选中色分离（unitTextColor），`themeChanged()` 里按 view.getId()==NO_ID 区分单位字与选中文字，防止换肤时单位字被误染成选中色。遗留问题：提交说明自曝"文件漏提"，部分改动随后由 08541a90 补交。

## 复盘与经验
- 自绘控件（TimePickerView/WheelScrollView）的尺寸常量与布局 XML 是一套契约，UI 对版时必须同步改 onMeasure/onLayout 列宽计算，只改一处必然错位。
- 背景资源按语义命名（time_picker 被误用作弹窗背景）能从源头防错用；dialog_smal 这种拼写不规范命名值得收敛。
- 设计稿变更类缺陷修复宜一个弹窗一次提交整版对齐（尺寸+布局+drawable+自绘），拆散提交容易出现本例的"文件漏提"半成品状态。
