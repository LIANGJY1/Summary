# SIR-6525 · 无联系人手机多次点"重新获取"，联系人界面卡在"正在同步"

- **提交**：`1775204b` | 2026-08-27 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（提交信息标注 D，以缺陷库为准）

## 问题
蓝牙已连接，对无联系人的手机（如 P60）多次点击"重新获取"，联系人界面永远停留在"正在同步"的 Loading 态。

## 根因分析
两个机制互相踩踏：
1. **防抖拦截真实请求**：`InCallServiceImpl.startPullPhoneBook` 里此前为修重复触发加了 500ms 时间戳防抖（`lastPullPhonebookTime`），用户连续点击"重新获取"时，新一次真实的 pullPhonebook 请求被静默丢弃，而旧一次请求的（空）结果仍在流转。
2. **超时兜底被误清**：`ContactsViewModel.isCoordinated()` 是空数据也会走的公共路径，其末尾无条件 `cancelSyncTimeout()`。P60 这类手机每次同步完成但列表为空， LiveData 空数据发射一路走到这里，把 `setupSyncTimeout()`（`SyncConfig.SYNC_TIMEOUT_MS` 后检查真实下载状态并强制复位 UI）安排的超时 Runnable 取消掉了。超时被删、新请求被防抖拦下，没有任何机制再把 `isSyncing` 置回，Loading 永久卡死。缺陷库概括为"新的请求被防抖拦截，旧的结果又取消了超时"。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java、application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java
     public void startPullPhoneBook(int size){
-        // 【修复】防抖：500ms 内只执行一次，避免重复触发 pullPhonebook
-        long now = SystemClock.elapsedRealtime();
-        if (now - lastPullPhonebookTime < 500) {
-            LogUtils.w(TAG, "startPullPhoneBook debounced, skip duplicate call within 500ms ...");
-            return;
-        }
-        lastPullPhonebookTime = now;
-
         LogUtils.d(TAG, "开始下载联系人>>>"+size);

--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java
             if (pbDownload(pbDownloadState)) {
                 LogUtils.w(TAG, "PBAP download failed, show unsynced state");
+                cancelSyncTimeout();
                 ...
             } else if (pbState(pbDownloadState)) {
                 LogUtils.i(TAG, "3@Empty，PBAP download completed but contacts empty, ...");
+                cancelSyncTimeout();
                 ...
             }
         }
+        cancelSyncTimeout();
         LogUtils.i(TAG, "5@Empty，observeContactsData - Data updated, show empty state");

@@ isCoordinated()
-        cancelSyncTimeout();
         return false;
     }

     private void updateGlobal(List<Object> contacts) {
+        cancelSyncTimeout();
（另有延迟复核分支 4@Empty / STATE_DOWNLOAD_FAIL 两处补 cancelSyncTimeout()）
```

## 为什么能修复
去掉防抖后，"重新获取"每次都会真正发起 `pullPhonebook`（重复请求由 PBAP 下载状态机与结果路径自然吸收）；`isCoordinated` 公共路径不再滥杀超时兜底，`cancelSyncTimeout()` 改为只在每个**终态分支**（下载失败、下载完成但空、数据落库、延迟复核结论）精确调用——即"超时只在确认有结论时才取消"。于是即使手机无联系人，30 秒超时必然触发，进入检查真实下载状态→复位 UI 的兜底链路，Loading 不再永久卡死。风险：高频点击会多次发起 PBAP 请求，依赖底层状态机幂等。

## 复盘与经验
- 防抖/节流放在用户输入侧是安全的，放在跨进程/异步结果链路里会吞掉"重试"语义；重复请求的正确挡板是业务状态机（下载中不接受新请求），不是时间窗。
- 兜底超时的取消必须与"到达终态"一一对应；在公共数据路径里顺手 cancel，等于变相拆除超时保护。
- 修 A 问题（重复触发）引入的防抖反而造成 B 问题（卡 loading），说明补丁要有回归视角：每次新防御逻辑都要回答"它会不会挡住正常路径"。
