# SIR-3224 · 同步联系人时切到最近通话显示空图片（同步通知发错Fragment+状态卡死）

- **提交**：`d0afcd45` | 2026-07-27 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
同步联系人的过程中切换到"最近通话"tab，页面显示空图片（空态占位），不同步数据。

## 根因分析
多个同步状态缺陷叠加，其中最直接的一处是 `MainActivity.notifyCallLogSyncStart()` 用 `findFragmentByTag("f1")` 找 CallLogFragment——**tag 写错了**（通话记录页实际是 `f2`），通讯录同步开始的通知根本送不到最近通话页，后者拿不到"协调同步"的启动信号，停在空态。此外：`FavoritesFragment` 在 `SyncRequired`/`Empty` 分支把 `mSyncInProgress` 置 true 且无出口，标志位卡死后 `onResume` 的重同步被 `mSyncInProgress` 拦截；`startSync()` 在 `viewModel == null` 时也已把 `mSyncInProgress=true`，形成永久阻塞；`BluetoothManager.isPbapAuthorizedInternal` 里用"试拉 1 条 vCard"探测授权（副作用探测，慢且可能干扰同步链路），事件回调里多处直接 `postValue(false)` 不走统一入口；`UtilsKt.isAutoSyncContacts()`（读 `Settings.Global auto_sync_contacts_$mac`）在多处把自动同步挡掉，但该开关并未在产品上提供入口，导致"该同步的没同步"。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java；.../fragment/CallLogFragment.java；.../fragment/ContactsFragment.java；.../fragment/FavoritesFragment.java；.../manager/BluetoothManager.java；.../viewmodel/ContactsViewModel.java；.../viewmodel/FavoritesViewModel.java；.../utils/Utils.kt（删除）；等 12 个文件
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
@@ notifyCallLogSyncStart
-        Fragment callLogFragment = getSupportFragmentManager().findFragmentByTag("f1");
+        Fragment callLogFragment = getSupportFragmentManager().findFragmentByTag("f2");
--- a/.../fragment/FavoritesFragment.java
             } else if (state instanceof FavoritesUiState.SyncRequired) {
-            mSyncInProgress = true;
+            mSyncInProgress = false;
@@ startSync
-        mSyncInProgress = true;
         if (viewModel != null) {
+            mSyncInProgress = true;
             viewModel.startSync(ignoreIsAsync);
--- a/.../manager/BluetoothManager.java
-    private void updatePbapAuthorizationState(int pbapType) {
-        boolean authorized = isPbapAuthorizedInternal(pbapType);
-        pbapAuthorized.postValue(authorized);
-    }
+    private void updatePbapAuthorizationState(boolean authorized) {
+        if (Boolean.TRUE.equals(pbapAuthorized.getValue()) != authorized) {
+            pbapAuthorized.postValue(authorized);
+        }
+    }
     // 事件回调改为显式 updatePbapAuthorizationState(true/false)；删除 pullVcardListing 试拉探测
--- a/.../fragment/ContactsFragment.java
-        if (viewModel != null && UtilsKt.isAutoSyncContacts()) {
+        if (viewModel != null) {
             viewModel.startSync(false);
--- a/application/BTPhone/src/main/java/com/yadea/btphone/utils/Utils.kt（整个文件删除，含 isAutoSyncContacts）
```

## 为什么能修复
① tag 改正后同步开始通知真正到达 CallLogFragment，最近通话页随通讯录同步协同刷新，空图片消失——这是该现象的直接修复；② `mSyncInProgress` 的置位/复位收拢到 `startSync` 与各终态分支，标志位不再卡死；③ PBAP 授权状态改为事件驱动（连接/断开时显式推 true/false）并去掉试拉探测，状态更新快且无副作用；④ 删除 `isAutoSyncContacts` 门槛后，授权/连接即自动同步，配合 ContactsViewModel 里删掉 `isAsync()` 分支，"等待收藏和通话记录同步"的协调链路被打通。风险：移除自动同步开关属产品行为变更，若有用户依赖手动同步需回归确认。

## 复盘与经验
- `findFragmentByTag` 这类魔法字符串 tag 是 Fragments 协作最脆的一环，tag 常量应集中定义；发错对象时"对面没反应"比崩溃更难查。
- `mSyncInProgress` 式布尔标志的每个赋值点都要有对应复位点，且复位应放在终态渲染分支里，否则一次异常路径就永久卡死。
- 用带副作用的操作（试拉数据）去"探测"状态，不如直接订阅状态事件；探测慢、有干扰，还把授权检查变成了触发下载的入口。
- 一个"空图片"症状背后可能有 tag 错、标志卡死、开关拦截三层原因，复盘按数据流逐段验证而不是停在第一个像样的解释。
