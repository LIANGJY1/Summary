# SIR-5839 · 未授权点击"重新获取"后页面误跳同步失败且文字错位
- **提交**：`cf23c36c` | 2026-08-13 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
iPhone 未授权联系人同步后，再次点击"重新获取"，本应停留在未授权页面，实际跳成"同步失败"页面，且失败页文案位置错乱。

## 根因分析
`ContactsViewModel.setupAuthorizationTimeout()` 的授权超时回调（5 秒未授权触发）把"用户未授权"错误归类为 `ContactsUiState.SyncFailed`（原代码 `uiState.setValue(new ContactsUiState.SyncFailed(failMsg))`，toast 也用 `sync_failed`）。而 `ContactsFragment.render()` 中 `SyncFailed` 分支渲染的是"同步失败页"（sync_error 图标 + 重试按钮），与未授权语义不符——点击"重新获取"重新走 `checkPbapAuthorizationStatus()` 后再次超时，页面就被切到失败页。此外 `render()` 的 SyncFailed 分支内部文案赋值互相错位：`tvSyncProgress` 设为 sync_failed 且 VISIBLE、`tvEmptyMessage` 设为 try_again 且 GONE，与布局设计相反，导致"文字位置错误"。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java`、`fragment/ContactsFragment.java`
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java (授权超时回调)
                 // 显示同步失败的Toast
-                String failMsg = getApplication().getString(com.yadea.btphone.R.string.sync_failed);
+                String failMsg = getApplication().getString(com.yadea.btphone.R.string.try_again);
                 toastMessage.setValue(failMsg);
                 // 显示同步失败状态，用户可以重试
-                uiState.setValue(new ContactsUiState.SyncFailed(failMsg));
+                uiState.setValue(new ContactsUiState.SyncRequired(failMsg));
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java (SyncFailed 分支)
             binding.tvSyncProgress.setText(R.string.sync_failed);
-            binding.tvSyncProgress.setVisibility(VISIBLE);
+            binding.tvSyncProgress.setVisibility(GONE);
             binding.progressBarSync.setVisibility(GONE);
-            binding.tvEmptyMessage.setText(R.string.try_again);
-            binding.tvEmptyMessage.setVisibility(GONE);
+            binding.tvEmptyMessage.setText(R.string.sync_failed);
+            binding.tvEmptyMessage.setVisibility(VISIBLE);
             binding.btnAction.setText(R.string.retry_sync);
```

## 为什么能修复
授权超时本质是"未授权需用户操作"，改发 `SyncRequired` 后 `render()` 走 `SyncRequired||Empty → showWaitAuthorized()` 分支，页面回到未授权态并等待用户手动触发，不再误入失败页；SyncFailed 分支的两个 TextView 文案/可见性对调归位，失败页文字显示在正确控件上。副作用：真正的"授权后同步失败"仍由同步超时等路径发 `SyncFailed`，语义划分更清晰；若产品本意想引导用户重试，toast 文案从 sync_failed 改为 try_again 已兼顾。

## 复盘与经验
- UI 状态机里"未授权（SyncRequired）"与"同步失败（SyncFailed）"是两种因果不同的终态，超时处理时必须先问"是什么超时"，不能因为表现相似就复用失败态。
- 渲染分支里成对的控件（tvSyncProgress/tvEmptyMessage）赋值容易复制粘贴错位，建议集中定义每个状态的控件可见性矩阵或用单一状态样式函数，杜绝手工两行两行对写。
- 点击"重新获取"这类重试入口要保证幂等：重试后到达的状态应与首次进入一致（未授权→未授权页），本例通过统一状态类型天然实现。
