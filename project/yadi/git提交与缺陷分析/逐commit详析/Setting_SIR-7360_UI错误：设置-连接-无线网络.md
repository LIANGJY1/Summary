# SIR-7360 · WiFi 密码输入确认键颜色（蓝）与 UI 不符
- **提交**：`7413f018` | 2026-09-15 | sgh | Setting(CommonTools 组件) | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置-连接-无线网络，输入密码界面的"确认"按键显示为蓝色，与 UI 设计要求不符。

## 根因分析
公共组件 `component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt` 的默认样式全部用 `Color.parseColor` **硬编码十六进制色值**：`normalBgColor = Color.parseColor("#404C60")`、`pressedBgColor = "#364050"`、disabled 态 `"#99404C60"` 等。该组件被 WiFi 密码输入弹窗复用时，颜色不来自全局主题色板（token），一旦设计规范调整主题色（或不同项目色板不同），组件仍按旧硬编码值渲染，出现"确认键发蓝、与 UI 不符"。根因与 SIR-8376 同类：控件默认样式绕过色彩 token 体系。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt；component/CommonTools/src/main/res/layout/view_state_loading_button.xml
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt
+++ b/component/CommonTools/src/main/java/com/yadea/common/widgets/StateLoadingButton.kt
@@ -30,17 +31,18 @@
     // 样式配置（支持不同状态不同颜色）
-    private var normalBgColor: Int = Color.parseColor("#404C60")
-    private var normalTextColor: Int = Color.parseColor("#FFFFFF")
+    private var normalBgColor: Int = context.getColor(R.color.bg_button_suggest)
+    private var normalTextColor: Int = context.getColor(R.color.text_white_default)
 
-    private var loadingBgColor: Int = Color.parseColor("#404C60")
-    private var loadingTextColor: Int = Color.parseColor("#FFFFFF")
+    private var loadingBgColor: Int = context.getColor(R.color.bg_button_suggest)
+    private var loadingTextColor: Int = context.getColor(R.color.text_white_default)
 
-    private var disabledBgColor: Int = Color.parseColor("#99404C60")
-    private var disabledTextColor: Int = Color.parseColor("#4CFFFFFF")
+    private var disabledBgColor: Int = context.getColor(R.color.bg_button_suggest_disabled)
+    private var disabledTextColor: Int = context.getColor(R.color.text_white_disabled)
 
-    private var pressedBgColor: Int = Color.parseColor("#364050")
-    private var pressedTextColor: Int = Color.parseColor("#99FFFFFF")
+    private var pressedBgColor: Int = context.getColor(R.color.bg_button_suggest_press)
+    private var pressedTextColor: Int = context.getColor(R.color.text_white_press)
```
（另：`view_state_loading_button.xml` 中按钮文字的 `android:layout_marginStart="@dimen/dp_12"` 被移除，顺带修正按钮文字不居中的偏移。）

## 为什么能修复
normal/loading/disabled/pressed 四态颜色全部改引 `R.color` token（`bg_button_suggest` 系列、`text_white` 系列），颜色收敛到统一色板后与设计规范天然一致；各业务方仍可通过 setter 覆盖。顺带删除文字 `marginStart` 使文案居中。风险点：token 色值若与此前硬编码值不同，所有未显式定制颜色的使用方会同步变色——这正是本次想要的全局一致性收益，但需回归其他引用该组件的页面。

## 复盘与经验
- 公共组件默认样式绝不能 `Color.parseColor` 硬编码，一律引设计 token（color 资源），否则主题演进时散点失控。
- "某个页面按钮颜色不对"而组件是公共的时，修复要评估全局影响面：token 化会同时改变所有使用方，需整体回归。
- 顺带清理同类小问题（文字 margin 偏移）可以在一次 UI 修复中一并闭环，但要在提交信息里点明，避免 review 漏看。
