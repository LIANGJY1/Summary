# SIR-6802 · 黑夜模式下无法设置自动亮度（选择器状态指向错误资源）
- **提交**：`5bf9bd90` | 2026-08-31 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
显示设置为白天模式时"自动亮度"可正常设置；切到黑夜模式后，"自动亮度"选项视觉上无法设置（选中态显示不对），用户以为功能失效。

## 根因分析
公共按钮背景选择器 `selector_common_gray_btn.xml` 的状态表里，`android:state_selected="true"` 一项错指向了 `@drawable/shape_btn_white_unselected`（未选中的白色形状）——典型的复制上一行后忘改资源。于是任何使用该 selector 的选项（含亮度设置页的"自动亮度"）在选中态拿到的底图与未选中态相同甚至相反：白天主题下两者反差小、勉强可用；黑夜主题下选中项呈现"白底未选"外观，与暗色背景形成刺眼错位，看起来就是"点不动/不可设"。功能逻辑本身（亮度开关读写）没有问题，纯 UI 状态-资源映射错误。

## 关键代码修改
改动文件：component/CommonTools/src/main/res/drawable/selector_common_gray_btn.xml
```diff
--- a/component/CommonTools/src/main/res/drawable/selector_common_gray_btn.xml
     <!-- 选中/按下状态 -->
     <item android:state_pressed="true" android:drawable="@drawable/shape_btn_white_selected" />
-    <item android:state_selected="true" android:drawable="@drawable/shape_btn_white_unselected" />
+    <item android:state_selected="true" android:drawable="@drawable/shape_btn_white_selected" />
     <item android:state_checked="true" android:drawable="@drawable/shape_btn_white_selected" />
     <!-- 默认/未选中状态 -->
     <item android:drawable="@drawable/shape_setting_gray_round_bg" />
```

## 为什么能修复
`state_selected` 改指向 `shape_btn_white_selected` 后，与 `state_pressed`/`state_checked` 语义对齐，选中项获得正确高亮底图，黑夜模式下"自动亮度"被正确标识为可操作状态。因为是公共组件，所有复用该 selector 的界面一并修正。无逻辑副作用；唯一注意点是若某些页面"故意"依赖旧错误表现做视觉区分，会随之改变（排查后未见此用法）。

## 复盘与经验
- selector 状态表逐行核对"状态 ↔ 资源"语义一致性，`selected`/`checked`/`pressed` 三行指向不同资源几乎必是笔误。
- 公共 drawable 是全局放大器：一行错误影响所有复用页面，且在不同主题（昼夜）下症状不同，容易误判为模式相关逻辑 bug——先查资源层再查逻辑层。
- "白天正常、黑夜异常"类问题，优先怀疑两套主题资源/选择器的映射差异，而不是功能代码。
