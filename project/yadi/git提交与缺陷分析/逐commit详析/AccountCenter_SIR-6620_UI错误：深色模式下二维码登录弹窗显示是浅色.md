# SIR-6620 · 深色模式二维码弹窗配色（漏提文件补充）

- **提交**：`285a409f` | 2026-08-27 | liqingqing | AccountCenter | bugfix（补丁提交）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心（提交信息标注 B，以缺陷库为准）

## 问题
`68fc81e7` 将二维码弹窗颜色资源化后，未随提交带上夜间色资源文件，深色模式下引用的颜色没有夜间限定值（可能编译报错或仍取日间色），需要补提。

## 根因分析
上一提交只在 `values/colors.xml` 定义了日间默认色并让布局改引 `@color/qr_dialog_*`，但新建的 `values-night/colors.xml`（夜间限定目录下的资源文件）漏于提交。Android 资源体系要求同名资源必须有默认值，夜间值缺失时深色模式回退日间配色，bug 现象依旧。本提交补上该文件，与前一提交合并才构成完整修复。

## 关键代码修改
改动文件：application/AccountCenter/src/main/res/values-night/colors.xml（新增）
```diff
--- /dev/null
+++ application/AccountCenter/src/main/res/values-night/colors.xml
+<resources>
+    <color name="qr_dialog_background">#222325</color>
+    <color name="qr_dialog_title_text">#EEEEEE</color>
+    <color name="qr_dialog_fail_title_text">#EEEEEE</color>
+    <color name="qr_dialog_secondary_text">#99EEEEEE</color>
+</resources>
```

## 为什么能修复
`values-night` 限定符目录补齐后，深色模式下系统按限定符匹配自动取到 `#222325` 背景、`#EEEEEE` 标题、`#99EEEEEE` 说明文字，弹窗完成夜间换肤；日间路径不受影响。该提交本质是上一提交的"另一半"，单独存在时无意义。

## 复盘与经验
- 新增资源目录/文件的提交，diff stat 一眼就能看出"引用了但没提交"的破绽；资源化改造后必须编译验证夜间路径。
- 同一单的拆分提交要用相同标题+明确"漏提文件"标注，方便回溯时把两个 commit 当作一个逻辑修复整体阅读。
- 代码评审时对 `@color/xxx` 引用要做"定义是否齐全（默认值+夜间值）"检查。
