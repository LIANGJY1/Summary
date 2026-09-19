# SIR-5889 · iPhone 不授权同步时一直显示进度条和"同步完成"
- **提交**：`c5dca19c` | 2026-08-14 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
iPhone 不授权联系人同步时，界面不显示"手机未授权"文案，而是停留在同步进度条并显示"同步完成"。

## 根因分析
缺陷库根因："未授权时 iPhone 会返回同步完成的状态，导致界面更新异常"。代码层面，`ContactsFragment.progressBarShow()` 里有一段"特殊处理"：`SyncRequired/Loading` 状态下收到 `progress == 100` 就强制把 UI 覆盖成同步完成态（`tvSyncProgress.setText("同步完成")`、进度条置 100% 并 VISIBLE），再 `postDelayed(500ms)` 调 `checkAndRecoverFromStuckLoading()` 兜底恢复。iPhone 未授权时 PBAP 也会回报 100% 传输结束，这段代码立刻把未授权页盖成"同步完成"；而 `ContactsViewModel.observeContactsData()` 中又有一段守卫——空数据列表在 `SyncRequired` 状态下"keep current state"直接 return，导致 ViewModel 永远不发布 Empty 状态，Fragment 的强制覆盖无法被纠正，界面卡死在进度条+同步完成。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java`、`viewmodel/ContactsViewModel.java`
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java
-        // 【新增】特殊处理：SyncRequired/Loading状态下收到100%进度，强制检查并恢复
+        // 100%只代表PBAP传输结束，最终显示由ViewModel根据联系人数据决定。
+        // 空数据会进入Empty状态并显示重新获取页面，不能在这里直接覆盖成"同步完成"。
         if (progress == 100 && (currentState instanceof ContactsUiState.SyncRequired ||
                 currentState instanceof ContactsUiState.Loading)) {
-            binding.imageState.setVisibility(GONE);
-            binding.btnAction.setVisibility(GONE);
-            binding.tvSyncProgress.setVisibility(VISIBLE);
-            binding.progressBarSync.setVisibility(VISIBLE);
-            binding.tvSyncProgress.setText("同步完成");
-            binding.progressBarSync.setProgress(100);
-            binding.tvSyncProgress.postDelayed(() -> { ...checkAndRecoverFromStuckLoading(); }, 500);
+            LogUtils.i(mTAG, "Received 100% progress in " +
+                    currentState.getClass().getSimpleName() +
+                    " state, wait for ViewModel to publish Success or Empty");
             return;
         }
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java (observeContactsData 的 addSource 回调)
-            // 【新增】处理重新授权后的数据恢复
-            ContactsUiState currentState = uiState.getValue();
-            if (contacts.isEmpty() && currentState instanceof ContactsUiState.SyncRequired) {
-                LogUtils.i(TAG, "Received empty list in SyncRequired state, may be re-authorizing, keep current state");
-                return;
-            }
-
             cancelSyncTimeout();
             updateGlobalContacts(contacts);
```

## 为什么能修复
状态裁决权收归 ViewModel：Fragment 收到 100% 只记日志不再动 UI；ViewModel 侧删掉"空数据保持现状"的守卫后，未授权场景的空列表会走 `updateGlobalContacts → handleEmptyContactsAfterSync()` 发布 Empty/未授权状态，UI 正确切换为未授权文案页。结合 `isWaitingForAuthorization` 的"只有非空数据才算授权成功"判断，实现缺陷库方案"根据实际同步数据和授权状态组合判断 UI"。副作用：合法的"重新授权中空数据"不再被保护，授权重试的中间空态可能短暂闪 Empty 页，由重新同步的 Loading 状态快速覆盖。

## 复盘与经验
- 协议回报（PBAP 100%）与业务结果（是否真的有数据/已授权）是两个层次，UI 决不能拿传输层进度直接覆盖状态机，"同步完成"只能由数据+授权组合推导。
- Fragment 里用 postDelayed 兜底"等 ViewModel 更新"是典型的时序赌博，去掉 View 层的临时 UI 覆盖、让状态单一来源（ViewModel）驱动渲染才是正解。
- "空数据 = 未同步完成"这类守卫看似稳健，实则吞掉了合法的终态迁移；守卫条件必须绑定可区分的信号（如授权状态），而不是仅凭数据是否为空。
