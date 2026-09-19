# SIR-3547 · 双路通话挂起浮窗未显示“保留中”（布局硬编码文言 + 文案变更）

- **提交**：`bd459b86` | 2026-07-24 | liujinfeng | BTPhone | bugfix（UI 文言修正）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（rc"UI文言变更"/sol"修改UI文言"）

## 问题
双路通话挂起时浮窗状态文字仍显示"已挂起"，未按最新文案要求显示"保留中"。

## 根因分析
`float_three_way_holding_window.xml` 与 `float_three_way_outgoing_window.xml` 两个布局里，挂起状态文字被**硬编码**为 `android:text="已挂起"`，未走字符串资源。产品文案变更（"已挂起"→"保留中"）只改得到资源文件、改不到写死在布局里的字面量，浮窗便与全车文案规范脱节。同提交顺带把 `try_again` 文案补充为"手机未授权/无数据…"的完整指引。另需注意：`values-en/strings.xml` 里新增的 `call_waiting` 值也是"保留中"（中文），英文环境将显示中文，属于本次埋下的 i18n 遗留。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/layout/float_three_way_holding_window.xml；application/BTPhone/src/main/res/layout/float_three_way_outgoing_window.xml；application/BTPhone/src/main/res/values/strings.xml；application/BTPhone/src/main/res/values-en/strings.xml
```diff
--- a/application/BTPhone/src/main/res/layout/float_three_way_holding_window.xml（outgoing_window 同）
-            android:text="已挂起"
+            android:text="@string/call_waiting"
--- a/application/BTPhone/src/main/res/values/strings.xml
+    <string name="call_waiting">保留中</string>
-    <string name="try_again">手机未授权，请授权后点击“重新获取”</string>
+    <string name="try_again">手机未授权/无数据，请在手机蓝牙设置中授权访问通讯录后点击“重新获取”按钮</string>
--- a/application/BTPhone/src/main/res/values-en/strings.xml
+    <string name="call_waiting">保留中</string>   <!-- 注意：英文资源里也是中文值 -->
```

## 为什么能修复
布局改引 `@string/call_waiting` 后文案由资源统一供给，中文环境显示"保留中"，满足验收；后续再改文案只需动 strings.xml。风险/遗留：① `values-en` 未翻译（应填 "On hold"/"Call waiting" 之类），英文车机仍显示中文；② 新文案 key 只在两个布局引用，若其他浮窗/全屏界面还有"已挂起"字样需另行排查。

## 复盘与经验
- 布局里写死中文文案必然在文案变更/多语言时翻车，`lint` 的 `HardcodedText` 告警不该被忽略。
- 文案变更类 bug 的检查范围是"全资源树"：改了默认 strings.xml，必须同步 values-en 等语言目录，否则只是把中文 bug 换成 i18n bug。
- 用户引导类文案（如 try_again）要写清"原因 + 用户该做什么"，模糊文案会引发二次客服工单。
