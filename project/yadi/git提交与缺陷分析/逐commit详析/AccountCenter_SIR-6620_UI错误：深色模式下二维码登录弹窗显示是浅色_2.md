# SIR-6620 · 深色模式下二维码登录弹窗仍为浅色

- **提交**：`68fc81e7` | 2026-08-27 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心（提交信息标注 B，以缺陷库为准）

## 问题
深色模式下，二维码登录弹窗背景和文字仍是浅色主题的配色，与 UI 设计稿不符。

## 根因分析
`dialog_qr_code_login.xml` 布局中的标题、说明文字、失败标题、刷新提示等 `textColor` 全部硬编码为浅色模式的十六进制值（`#333333`、`#999999`、`#20232B`），弹窗背景 `drawable/bg_dialog_qr_code.xml` 的 solid 色硬编码 `#F0F2F5`。硬编码颜色不参与资源限定符（`values-night`）匹配，系统切到夜间模式时拿不到替代色，弹窗自然保持浅色。修复方式是把所有硬编码色抽取为 `values/colors.xml` 中的颜色资源（`qr_dialog_background`、`qr_dialog_title_text`、`qr_dialog_fail_title_text`、`qr_dialog_secondary_text`）并在布局/drawable 中统一引用，夜间色由配套提交补齐。

## 关键代码修改
改动文件：application/AccountCenter/src/main/res/drawable/bg_dialog_qr_code.xml、application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml、application/AccountCenter/src/main/res/values/colors.xml
```diff
--- application/AccountCenter/src/main/res/drawable/bg_dialog_qr_code.xml
-    <solid android:color="#F0F2F5"/>
+    <solid android:color="@color/qr_dialog_background"/>

--- application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml
-            android:textColor="#333333"
+            android:textColor="@color/qr_dialog_title_text"
（共6处：标题/刷新文字、两处说明文字、失败标题、刷新提示，分别引用 qr_dialog_title_text、qr_dialog_secondary_text、qr_dialog_fail_title_text）

--- application/AccountCenter/src/main/res/values/colors.xml
+    <color name="qr_dialog_background">#F0F2F5</color>
+    <color name="qr_dialog_title_text">#333333</color>
+    <color name="qr_dialog_fail_title_text">#20232B</color>
+    <color name="qr_dialog_secondary_text">#999999</color>
```

## 为什么能修复
布局不再持有写死的颜色，改为通过资源名间接取值；日间默认值与原色完全一致保证无回归，夜间值在配套提交 `285a409f` 的 `values-night/colors.xml`（背景 `#222325`、标题 `#EEEEEE`、说明 `#99EEEEEE`）中定义后，深色模式即可自动命中。用户协议/隐私政策链接色未纳入本次抽取，保持蓝色，符合设计要求。风险极低，唯一隐患是漏提夜间资源文件（本例确实发生了，见 `285a409f`）。

## 复盘与经验
- 深浅色适配的前提是"颜色全部资源化"：任何一处硬编码色都会成为夜间模式的视觉 bug。
- 抽取颜色时给资源名带业务前缀（`qr_dialog_`），避免全局 colors.xml 命名冲突。
- 涉及新增资源文件（尤其 values-night 目录新建）的提交最容易漏提，拆 commit 前要本地编译+双模式跑一遍验证。
