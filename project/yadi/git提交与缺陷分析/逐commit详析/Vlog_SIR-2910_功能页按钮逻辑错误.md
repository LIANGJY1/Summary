# SIR-2910 · Vlog功能页删除配对后跳转逻辑与按钮状态错误

- **提交**：`5e3f5fcc` | 2026-07-21 | daizhecheng | Vlog | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车载vlog

## 问题
功能页（已配对相机页）上"更多→删除配对"按钮链路逻辑错误：确认删除后页面停留在原页、连接状态与按钮显示不刷新；按钮文字颜色在连接/断开切换后也不对。

## 根因分析
`CameraPairedActivity.kt` 中原有弹窗链为 `showExportMoreDialog()（HintExtDialog 删除确认）→ showDeletePairedDialog()（HintConfirmDialog 选择）`，两级弹窗顺序与产品交互相反，且 `HintConfirmDialog.onConfirm()` 里只调用 `viewModel.disConnectBle()`/`disconnectCamera()`/`updateConnectText()`——断开相机后既不退出当前页，也不回到入口页 `HomeActivity`，页面停留在失效的功能页上，表现为"跳转逻辑错误"。另外 `updateConnectText()` 里切换 `btnConnect` 文案时没有同步切换文字颜色（连接态应为白色、未连接态应为默认色），导致按钮在不同背景色上文字颜色错误。元数据"跳转逻辑问题"与 diff 实际一致：核心就是删除配对后缺少向 `HomeActivity` 的跳转。

## 关键代码修改
改动文件：application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt
@@ private fun showExportMoreDialog() {
+        val mDeletePairedDialog = HintConfirmDialog(
+            getString(R.string.dialog_content_title) + " ${viewModel.getLastConnectDeviceInfo().first}",
+            getString(R.string.txt_auto_connect),
+            getString(R.string.txt_del_paired),
+            true, showInfo = true
+        )
+        mDeletePairedDialog.setConfirmBg(R.drawable.btn_bg_radio18_red)
+        mDeletePairedDialog.setOnDialogListener { showDeletePairedDialog() }
+        mDeletePairedDialog.show(supportFragmentManager, "DeletePairedDialog")
+    }
+
+    private fun showDeletePairedDialog() {
@@ HintExtDialog.OnDialogListener.onConfirm()
-                showDeletePairedDialog()
+                if (viewModel.isConnected) {
+                    viewModel.disConnectBle()
+                    viewModel.disconnectCamera()
+                }
+                startActivity(
+                    Intent(
+                        this@CameraPairedActivity,
+                        HomeActivity::class.java
+                    )
+                )
@@ private fun updateConnectText()  // 连接态按钮文字颜色
+                it.btnConnect.setTextColor(resources.getColor(R.color.text_white_default, null))
             } else {
                 ...
+                it.btnConnect.setTextColor(resources.getColor(R.color.text_default_default, null))
```

## 为什么能修复
把弹窗链恢复为"先选择（自动连接/删除配对）→ 再二次确认删除"的正确交互顺序；删除确认后主动断开 BLE 与相机并 `startActivity(HomeActivity)` 回到入口页，由入口页按最新连接状态重建流程，消除了停留在失效功能页的问题。`updateConnectText()` 补齐文字颜色后按钮态一致。隐患：删除后用 `startActivity` 而非 `finish()` 回首页，原页面仍在返回栈中，需依赖 `HomeActivity` 自身的跳转/finish 逻辑（见 `f80efe5b`）配合才干净。

## 复盘与经验
- "删除/解绑"类操作完成后的导航目标必须明确设计：回入口页让状态机重新推导，比在原页局部刷新更不易留脏状态。
- 两级确认弹窗的顺序就是交互的一部分，抽成两个方法（`showExportMoreDialog`/`showDeletePairedDialog`）后链路一目了然，避免嵌套回调里改错顺序。
- 切换按钮文案时要把"文案+背景+文字色"作为一个视觉状态整体更新，只改一处就会出现文字与底色错配。
