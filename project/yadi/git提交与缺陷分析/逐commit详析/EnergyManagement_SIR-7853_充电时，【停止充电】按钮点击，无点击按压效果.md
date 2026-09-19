# SIR-7853 · 能量中心【停止充电】按钮无按压效果

- **提交**：`dd8a9916` | 2026-09-10 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
充电时点击【停止充电】按钮，没有任何按压反馈，用户无法从视觉确认点击已生效。

## 根因分析
【停止充电】是一个 `RelativeLayout`（id `btn_stop_charging`，activity_main.xml），背景为矢量 drawable `bg_stop_charging_button.xml`，其 `<path android:fillColor>` 固定引用单一颜色 `@color/stop_charging_button_bg_color`；按钮文字样式 `StopChargingButtonText` 的 textColor 也是固定色 `@color/text_default_default`。整个视图链上没有任何 `state_pressed` 状态资源，按压时自然无任何变化。另外按钮的点击区域是父容器 RelativeLayout，内层 TextView 默认不随父视图进入 pressed 状态，即使给文字单独配 selector 也拿不到状态，需要 `duplicateParentState`。

## 关键代码修改
改动文件：res/color/selector_stop_charging_button_bg.xml、selector_stop_charging_button_text.xml、selector_charge_limit_info_close_text_color.xml（新增）、drawable/bg_stop_charging_button.xml、layout/activity_main.xml、values(-night)/colors.xml、values/themes.xml（8 文件 +29/-4）
```diff
--- application/EnergyManagement/src/main/res/color/selector_stop_charging_button_bg.xml（新增）
+    <item android:color="@color/stop_charging_button_pressed_bg_color" android:state_pressed="true" />
+    <item android:color="@color/stop_charging_button_bg_color" />
--- application/EnergyManagement/src/main/res/drawable/bg_stop_charging_button.xml
-            android:fillColor="@color/stop_charging_button_bg_color"
+            android:fillColor="@color/selector_stop_charging_button_bg"
--- application/EnergyManagement/src/main/res/layout/activity_main.xml
                 android:layout_centerInParent="true"
+                android:duplicateParentState="true"
                 android:text="@string/stop_charging"
                 android:textAppearance="@style/StopChargingButtonText" />
--- application/EnergyManagement/src/main/res/values/themes.xml
-        <item name="android:textColor">@color/text_default_default</item>
+        <item name="android:textColor">@color/selector_stop_charging_button_text</item>
```

## 为什么能修复
按钮背景 path 的 fillColor 改为带 `state_pressed` 分支的 color selector，RelativeLayout 按下时背景 drawable 收到 pressed 状态即换色；文字颜色经同样的 selector + 内层 TextView `duplicateParentState="true"`（继承父容器 pressed 态）实现同步变色，按压反馈完整。顺带把日/夜两套按钮配色固化为显式色值（日间 #545F72、夜间 #5A5C61 及对应按压色），并把按钮宽度 194dp 收窄到 144dp 对齐新 UI。风险很小，纯资源层改动；注意 color selector 只能在 API 21+ 于 textColor/fillColor 中生效，车机平台满足。

## 复盘与经验
- 自绘矢量背景按钮的按压效果 = color/drawable selector + 状态能到达视图：自定义 path fillColor 引用 selector 是可行做法，但别忘内层子控件要 `duplicateParentState`。
- 点击区域在父容器、视觉在子控件的复合按钮，状态传导是最常见遗漏点。
- 颜色资源应日/夜成对定义并命名区分 default/pressed，本次把原引用全局色 `bg_button_default` 换成专属色，避免其他处改动误伤。
