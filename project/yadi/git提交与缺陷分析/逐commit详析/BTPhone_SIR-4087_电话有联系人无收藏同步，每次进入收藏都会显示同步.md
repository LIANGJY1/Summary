# SIR-4087 · 有联系人无收藏时，每次进入收藏页都显示"同步中"

- **提交**：`27b258c5` | 2026-07-29 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
手机蓝牙电话有联系人但没有收藏（收藏为空是合法终态）时，每次进入收藏页都先显示"同步中"，长时间不结束或反复触发同步。

## 根因分析
`FavoritesViewModel` 的同步状态机存在多条"空数据/失败被吞"路径，叠加成循环：
1. `handleFavoritesDataReady()` 收到空收藏数据时，若 `isSyncing || isCoordinatedSync` 为真则直接 `return` 保持 Loading——**空收藏这个合法终态被当作"同步中"吞掉**，而同步标志又因后续机制迟迟不复位。
2. `observeSyncCompletion()` 用独立 Handler 做 30s 超时 + "仍在下载则再延 10s"的延迟重检，超时后 `downloadStates.resetStates()` 并重新 `startSyncFavorites()`——重触发后又是同步中，形成"同步→超时→重置→再同步"循环。缺陷库"数据为空时会延迟检查重复加载"即此。
3. `InCallServiceImpl` 中**收藏下载失败误写 `setPbDownloadState(STATE_DOWNLOAD_FAIL)`**（联系人状态位），UI 侧查询 `isContactsDownloading/Failed` 得到错误结论，状态机无从判断收藏已终结。
4. 蓝牙 CONNECTED 回调里 `checkSyncStatusOrLoadData()` 会再次拉起同步，放大重复加载。
另附带的真 bug：`ContactsFragment.isPbapAuthorized()` 实现是 `bluetoothManager != null && !isPbapAuthorized(PB_PATH)`，方法名与逻辑相反（双重否定），调用处语义全反。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/FavoritesViewModel.java；telecom/InCallServiceImpl.java；telecom/dataexchange/TelecomForward.java；viewmodel/CallLogViewModel.java；viewmodel/ContactsViewModel.java；fragment/ContactsFragment.java；adapter/ContactsAdapter.java；MainActivity.java

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/FavoritesViewModel.java
@@ handleFavoritesDataReady()
         cancelSyncTimeout();
+        boolean syncWasInProgress = isSyncing || isCoordinatedSync;
         if (favorites != null && !favorites.isEmpty()) {
             uiState.postValue(new FavoritesUiState.Success(new ArrayList<>(favorites)));
             updateGlobalFavoriteList(favorites);
         } else {
-            if (isSyncing || isCoordinatedSync) {
-                LogUtils.w(TAG, "FAVORITES_DATA_READY but data is empty during sync, keep Loading state");
-                return;
-            }
             uiState.postValue(new FavoritesUiState.Empty(""));
             globalFavoriteList.clear();
         }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/FavoritesViewModel.java
@@ setupSyncTimeout() 超时策略重写（原 observeSyncCompletion 的 30s+10s 延迟重检整体删除）
-        if (downloadStates != null && downloadStates.getPbDownloadState() == STATE_DOWNLOADING) {
-            downloadStates.resetStates();
-            UiCallManager.get().startSyncFavorites();
+        waitingForContactsDownload = downloadStates != null && downloadStates.isContactsDownloading();
+        if (waitingForContactsDownload) {
+            timeoutHandler.postDelayed(syncTimeoutRunnable, CONTACTS_DOWNLOAD_RECHECK_MS); // 500ms 轮询
+            return;
+        }
+        ...
+        if (waitingForContactsDownload) {   // 联系人下完 → 才开始收藏下载并重置满超时窗
+            waitingForContactsDownload = false;
+            UiCallManager.get().startSyncFavorites();
+            timeoutHandler.postDelayed(syncTimeoutRunnable, SyncConfig.SYNC_TIMEOUT_MS);
+            return;
+        }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java
@@ 收藏失败写自己的状态位（原误写联系人位）
-            mDownloadStates.setPbDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
+            mDownloadStates.setFavDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
```

其他要点：`InCallServiceImpl` 新增 `mPendingContactsDownload`，PBAP 未连接时下载请求挂起、CONNECTED 后补发（原先直接判 FAIL）；`TelecomForward` 把单一 `pendingPbapReconnect` 拆为 contacts/favorites/callLogs 三个待补发标志；`ContactsFragment` 修正 `isPbapNotAuthorized` 反逻辑并删除卡死时强制 `startSync(false)` 的补丁。

## 为什么能修复
修复核心是把"空收藏"确立为合法终态：数据就绪回调里空数据无条件落到 `Empty` 状态，Loading 不再被保持；删除 30s/10s 延迟重检与 `resetStates` 重触发后，超时路径变成确定性的状态机（联系人下载中→500ms 轮询等待→完成后才开收藏下载并重置超时窗；失败→SyncFailed；收藏超时→SyncRequired 提示），不再自我循环；收藏失败写入 `FavDownloadState` 后 UI 查询语义正确，配合"PBAP 未就绪先挂起后补发"，同步链条每个环节都有明确终态，"每次进入都同步中"的循环被切断。隐患：500ms 轮询上限依赖 `SYNC_TIMEOUT_MS` 总窗，若联系人下载超过总窗仍会走失败提示；多处注释掉的旧逻辑（如 `checkSyncStatusOrLoadData`）留待清理。

## 复盘与经验
- **"空数据"是终态不是异常**：列表类页面必须把 Empty 作为状态机的一等公民，"同步中收到空数据继续等"的写法会把合法空态卡成永久 Loading。
- **多个状态位各管各的，禁止串写**：收藏失败写 contacts 状态位，让下游所有判断失真；状态位命名与写入点要一一对应（本例 pb/fav 两个字段混用是直接根因之一）。
- **"超时后 reset 并重试"是无限循环模板**：重试必须有次数/总时限约束，否则环境不满足时就是死循环；本例改为"等前置（联系人下载）真正完成后才开下一阶段"的串行状态机。
- **布尔方法名与实现相反（isPbapAuthorized 返回"未授权"）是定时炸弹**： review 时对双重否定零容忍，发现即改名。
