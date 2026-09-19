# SIR-8370 · 自动远光灯二次确认弹窗按钮文案与 UI 不符
- **提交**：`ebbcafca` | 2026-09-15 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
开启自动远光灯时的二次确认弹窗，确认按钮文言与 UI 设计稿不符。

## 根因分析
`application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt` 弹窗调用处把确认按钮文案取自 `R.string.confirm`。该资源并不在 Setting 模块自身的 `strings.xml` 中（本模块只定义了 `sure` = "确认"），`confirm` 实际解析到依赖库/其他模块的同名资源，文案与设计稿要求的"确认"不一致——同名不同值的字符串资源串扰导致弹窗按钮文字错误。典型的"通用确认文案存在多个候选资源，取错了一个"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
+++ b/application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
@@ -320,7 +320,7 @@
                 showTipDialog(
                     title = getString(R.string.auto_high_beam_open_title),
                     content = getString(R.string.auto_high_beam_open_content),
-                    confirmText = getString(R.string.confirm),
+                    confirmText = getString(R.string.sure),
                     cancelText = getString(R.string.cancel),
                     onConfirm = {
                         autoHighBeamStateTemp = true
```

## 为什么能修复
改用本模块定义的 `R.string.sure`（值恰为"确认"），文案与设计稿一致；改动仅此一处取值，弹窗逻辑、状态流转（`autoHighBeamStateTemp`）不受影响。隐患：依赖库中 `confirm` 资源仍被其他页面引用，若其文案也不符规范需另行统一治理。

## 复盘与经验
- 多模块工程中 `R.string.confirm`/`sure` 这类近义通用文案极易取错模块，通用按钮文案应收敛为唯一资源并全局引用。
- 资源遮蔽（模块资源覆盖依赖库同名资源）是静默的，编译期不报错，review 时要确认字符串的来源模块。
- 弹窗文言类 UI 单的验证只需对照设计稿核对每个按钮字面，成本低但用户感知明显，提测前可用脚本全局比对常用按钮文案。
