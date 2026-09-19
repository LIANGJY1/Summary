# SIR-1648 · 编辑位置名字光标问题
- **提交**：`3baa30fc` | 2026-07-02 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 输入法

## 问题
编辑（重命名）位置名称时，已有名字以提示文案（灰色 placeholder）形式显示，光标停在开头，编辑体验异常。

## 根因分析
`VehicleControlFragment.showEditPositionDialog`（`application/Setting/.../ui/fragment/VehicleControlFragment.kt`）构造 `EditDialog(title = "重命名", hint = currentName)`：无论当前名字是什么，一律作为 `hint` 传入。`hint` 在 EditText 中只是占位提示——文本为灰色、非真实内容、光标位于位置 0，用户必须先清掉/无视占位再输入；已改过名的位置名字也被当占位符显示，视觉上像"没保存过"。缺陷库归因"需求遗漏"：需求要求已命名内容应作为正文回填（光标在末尾），实现时用了 hint 参数。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt`
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -1741,10 +1741,24 @@
     private fun showEditPositionDialog(position: Int, currentName: String) {
-        val dialog = EditDialog(
-            title = "重命名",
-            hint = currentName
-        )
+        val defaultPositionName = when (position) {
+            1 -> "位置1"
+            2 -> "位置2"
+            3 -> "位置3"
+            else -> null
+        }
+        val useHint = defaultPositionName != null && currentName == defaultPositionName
+        val dialog = if (useHint) {
+            EditDialog(
+                title = "重命名",
+                hint = currentName
+            )
+        } else {
+            EditDialog(
+                title = "重命名",
+                content = currentName
+            )
+        }
```

## 为什么能修复
区分两种情形：名字仍是默认值（"位置1/2/3"）时保持 `hint` 展示占位；一旦用户改过名（currentName 与默认名不同），以 `content` 回填为真实正文，EditText 光标落在文本末尾，符合编辑语义。改动局限在弹窗构造，无副作用；默认名是硬编码字符串，若与资源文案改动不同步会误判，建议后续统一取自 strings 资源。

## 复盘与经验
- `hint` 与 `content` 的语义差异（占位符 vs 正文、光标位置）是输入框类缺陷高频点：回显已有数据必须用 content。
- "默认值是否算已编辑"需要显式定义；本例以"名字等于默认名"作为判据，把占位与正文分开处理，是可复用的模式。
