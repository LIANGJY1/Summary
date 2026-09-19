# SIR-7374 · 热点断开连接二次确认弹窗与 UI 设计不符
- **提交**：`a4d5f26b` | 2026-09-04 | sgh | Setting | bugfix（UI 文案与弹窗样式）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置-热点页断开已连接设备时的二次确认弹窗，文案与样式与 UI 设计稿不符：旧文案是"将"%1$s"与本机的连接断开,并将该设备拉入黑名单"，实际业务并不拉黑名单，且弹窗无标题。

## 根因分析
需求遗漏：`HotspotDialogFragment.showDisconnectDialog()` 用通用 `TextDialog` 手工拼弹窗（无标题、正文带设备名格式化、警告样式缺失），文案 `hotspot_block_hint` 里写了"拉入黑名单"，与实际 `disconnectClient(item)` 仅断开连接的行为不符，也与新设计稿（标题"断开连接？"+ 正文"断开连接后，无法连接热点"的警告弹窗样式）不一致。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt`、`application/Setting/src/main/res/values/strings.xml`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
-        TextDialog(
-            "",
-            String.format(getString(R.string.hotspot_block_hint), displayName),
-            ResourceUtils.getString(R.string.confirm),
-            ResourceUtils.getString(R.string.cancel)
-        ).setCallback(object : Callback {
-            override fun confirm(content: Any?) {
-                disconnectClient(item)
-            }
+        showWarningDialog(
+            title = getString(R.string.hotspot_block_title),
+            content = getString(R.string.hotspot_block_hint),
+            confirmText = getString(R.string.confirm),
+            cancelText = getString(R.string.cancel),
+            onConfirm = { disconnectClient(item) })
 
-            override fun cancel() {
-            }
-        }).show(childFragmentManager, "BlockClientDialog")
```

```diff
--- application/Setting/src/main/res/values/strings.xml
-    <string name="hotspot_block_hint">将"%1$s"与本机的连接断开,并将该设备拉入黑名单</string>
+    <string name="hotspot_block_hint">断开连接后，无法连接热点</string>
+    <string name="hotspot_block_title">断开连接？</string>
```

## 为什么能修复
改用 CommonTools 统一的 `showWarningDialog`（带标题、警告样式），文案换成与设计稿一致的"断开连接？"+"断开连接后，无法连接热点"，删除了与实际行为不符的"拉入黑名单"描述；确认回调 `disconnectClient` 不变，行为无回归。副作用：`displayName` 变量在弹窗文案中不再使用（保留在方法内），文案去掉设备名后若同页多设备并发断开，用户无法从弹窗确认目标设备，属设计稿取舍。

## 复盘与经验
- 弹窗文案描述了代码并未实现的行为（"拉入黑名单"），是文案与实现脱节的典型；文案审查时要对照实际执行的动作。
- 优先复用项目统一的对话框封装（`showWarningDialog`）而非每次手写 `TextDialog` + 匿名 Callback，样式统一且参数更简洁（Kotlin 命名参数 + lambda）。
- 需求遗漏类 UI 缺陷多集中在"二次确认弹窗"这类次要交互上，UI 走查时应把所有 confirm dialog 的标题/正文/按钮逐一对照设计稿。
