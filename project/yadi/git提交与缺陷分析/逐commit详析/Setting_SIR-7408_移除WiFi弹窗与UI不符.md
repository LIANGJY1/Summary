# SIR-7408 · 移除 WiFi 弹窗与 UI 不符
- **提交**：`3acaa54e` | 2026-09-04 | dufan | Setting | bugfix（弹窗文案与按钮样式）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
"移除"已保存 WiFi 网络的确认弹窗中，确认按钮文案与背景样式与 UI 设计稿不符（确认按钮应为红色警示样式，文案应为"移除"）。

## 根因分析
`WlanDialogFragment` 调用通用 `TextDialog` 时只传了 title/content/确认文案（`proceed_to_remove`）/取消文案，`TextDialog` 的确认按钮没有背景样式定制能力，默认普通样式，不符合"危险操作红色按钮"的设计规范；文案 `proceed_to_remove` 也非设计稿要求的 `remove`（"移除"）。属于公共组件能力缺失 + 调用方文案不对的组合。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt`、`component/CommonTools/src/main/java/com/yadea/common/dialog/TextDialog.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt
                         TextDialog(
                             it.ssid,
                             getString(R.string.wlan_remove_hint),
-                            getString(R.string.proceed_to_remove),
-                            getString(R.string.cancel)
+                            getString(R.string.remove),
+                            getString(R.string.cancel),
+                            R.drawable.selector_common_red_btn
                         ).setCallback(
```

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/TextDialog.kt
     val content: String? = null,
     val confirm: String = "",
-    val cancel: String = ""
+    val cancel: String = "",
+    val confirmRes: Int = 0
 ) : BaseDialogFragment() {
```

```diff
--- component/CommonTools/src/main/java/com/yadea/common/dialog/TextDialog.kt
         if (!(TextUtils.isEmpty(cancel))) {
             mTvCancel?.text = cancel
         }
+        if (confirmRes != 0) {
+            mTvConfirm?.setBackgroundResource(confirmRes)
+        }
         mTvConfirm?.setOnFastClickListener {
```

（`TextDialog` 构造新增可选参数 `confirmRes: Int = 0`，非 0 时对确认按钮 `setBackgroundResource(confirmRes)`。）

## 为什么能修复
调用方传入 `R.drawable.selector_common_red_btn` 后确认按钮应用红色警示选择器背景，文案换成"移除"，与设计稿一致；`confirmRes` 默认 0 不影响既有调用点（Kotlin 默认参数，向后兼容）。公共组件获得"危险操作按钮样式"能力后，其他删除/格式化类确认弹窗可直接复用。无副作用。

## 复盘与经验
- "删除/移除/格式化"类确认弹窗应统一使用警示样式（红色确认按钮），与其在调用方逐个 hack，不如给公共 Dialog 组件加可选样式参数（默认值保证兼容），一次性收敛。
- 通用组件缺能力时，调用方容易临时将就（先用默认样式上线），随后以 UI 缺陷形式回流；评审时对"公共组件凑合用"要保持敏感。
- Kotlin 默认参数比重载构造更适合这种可选能力扩展，调用方零改动。
