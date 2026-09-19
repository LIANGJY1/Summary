# YD-392845 · 手机未授权提示文言"重新授权"与按钮"重新获取"不一致

- **提交**：`d7343aff` | 2026-07-07 | duanlonglong | BTPhone | bugfix（纯文案修正）
- **缺陷库**：未关联单号（提交带 YD-392845 单号，缺陷库 defs 为空）

## 问题
蓝牙电话联系人未授权页面，提示语写"请授权后点击'重新授权'"，而实际按钮叫"重新获取"，文案与按钮名互相矛盾，用户找不到所指按钮。

## 根因分析
`try_again` 字符串（`手机未授权，请授权后点击“重新授权”`）与界面按钮的实际文案"重新获取"不同步，属文案录入错误，无逻辑问题。

## 关键代码修改
改动文件：`application/BTPhone/src/main/res/values-zh/strings.xml`、`application/BTPhone/src/main/res/values/strings.xml`（各 1 行）
```diff
// --- application/BTPhone/src/main/res/values/strings.xml（values-zh 同步同样修改）
-    <string name="try_again">手机未授权，请授权后点击“重新授权”</string>
+    <string name="try_again">手机未授权，请授权后点击“重新获取”</string>
```

## 为什么能修复
提示语中的按钮名与真实按钮一致，指引用户的操作路径不再歧义。注意 `values/`（默认资源）与 `values-zh/` 都存放中文文案并双双修改，保持了一致——但默认 locale 用中文本身就值得商榷（其他语言随系统回退到中文）。

## 复盘与经验
- **引导文案中引用的控件名是"强契约"**：文案里提到的按钮名应与实际控件字符串互相引用（如以 string 引用拼接），改按钮名时提示语才会联动。
- 本文件 `no_favorites` 行存在遗留的 `</string>>` 多余字符（本次上下文中可见但未处理），XML 宽容解析掩盖了笔误，值得专项清理。
