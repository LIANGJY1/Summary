# SIR-7673 · 热点密码输入框唤起时光标停在最前面
- **提交**：`1e4949d0` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：需求遗漏）

## 问题
点击"热点密码"唤起设置密码弹窗时，输入框已回填现有密码，但光标停在文本最前面，用户直接输入会把新字符插在旧密码中间，应默认光标在末尾。

## 根因分析
`HotspotPwdEditDialogFragment`（`BaseDialogFragment` 子类）在初始化时通过 `mBinding.etContent.setText(initPwd)` 回填已有密码 `initPwd`。Android EditText setText 后默认 selection 落在位置 0（请求焦点的时机与 setText 的顺序决定了光标位置），代码没有显式 `setSelection`，导致弹窗唤起时光标在最前。属于初始化状态遗漏，一行修复。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotPwdEditDialogFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotPwdEditDialogFragment.kt
@@ -63,6 +63,7 @@
         mBinding.tvTitle.text = getString(R.string.set_hotspot_password)
         mBinding.etContent.hint = getString(R.string.set_hotspot_password_hint)
         mBinding.etContent.setText(initPwd)
+        mBinding.etContent.setSelection(initPwd.length)
         mBinding.slbBtn.setButtonEnabled(false)
         mBinding.etContent.addTextChangedListener(object : TextWatcher {
```

## 为什么能修复
`setSelection(initPwd.length)` 把光标显式定位到回填文本末尾，输入新字符即追加在原密码后，符合用户预期；`initPwd.length` 与 setText 内容长度一致，无越界风险（initPwd 为空时 length=0 亦安全）。无其他副作用。

## 复盘与经验
- "setText 回填 + 期望光标在末尾"必须显式 `setSelection(text.length)`，不能依赖系统默认行为——EditText 光标默认位置受焦点时机影响，极易停在第 0 位。
- 带初始值的所有输入类弹窗（重命名、密码、备注）都应把"回填后定位光标"当作初始化模板的一部分，否则同类问题会在每个弹窗重复出现。
- 该缺陷根因登记为"需求遗漏"，实为交互细节未写入开发清单：需求评审时对输入框类交互（焦点、光标、键盘类型、长度限制）应有固定 checklist。
