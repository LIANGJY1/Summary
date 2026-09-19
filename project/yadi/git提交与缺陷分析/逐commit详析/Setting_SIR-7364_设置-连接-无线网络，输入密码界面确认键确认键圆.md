# SIR-7364 · WiFi 密码界面确认键与取消键圆角不一致
- **提交**：`a200b18d` | 2026-09-15 | sgh | Setting(CommonTools 组件) | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置-连接-无线网络，输入密码弹窗中"确认"键与"取消"键的圆角大小不一致，与 UI 设计不符。

## 根因分析
确认键使用公共组件 `StateLoadingButton`，其 `component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt` 中圆角默认值 `cornerRadius = 18f`（类字段默认与自定义属性 `R.styleable.StateLoadingButton_slb_cornerRadius` 的 `getDimension(..., 18f)` 兜底值两处均为 18f）；而取消键是另一个控件（圆角 12f）。同弹窗两按钮走了不同默认圆角，视觉上圆角一大一小。这是设计值默认值录错：18f 不是规范值。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt
+++ b/component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt
@@ -45,7 +45,7 @@
     // 圆角配置
-    private var cornerRadius: Float = 18f
+    private var cornerRadius: Float = 12f
@@ -74,7 +74,7 @@
-                cornerRadius = getDimension(R.styleable.StateLoadingButton_slb_cornerRadius, 18f)
+                cornerRadius = getDimension(R.styleable.StateLoadingButton_slb_cornerRadius, 12f)
```

## 为什么能修复
把字段默认值与 styleable 兜底值两处 18f 统一改为 12f，与取消键及设计规范一致；通过 `slb_cornerRadius` 属性显式定制圆角的调用方不受影响（getDimension 仅在未设置属性时取兜底值）。注意两处默认值必须同步改——漏改 styleable 兜底值会导致"XML 未写属性时仍是旧值"的半修复。

## 复盘与经验
- 自定义 View 的默认样式值在"字段初始化"和"obtainStyledAttributes 兜底"两处各有一份，修改默认样式时必须全改，否则出现条件性回退。
- 同一弹窗内的成对控件（确认/取消）应使用同一组件或同一组样式参数，圆角、间距等由设计 token 统一，避免各写各的默认值。
- 默认值最好引用 dimen 资源而非字面量 f 值，设计变更时一处生效。
