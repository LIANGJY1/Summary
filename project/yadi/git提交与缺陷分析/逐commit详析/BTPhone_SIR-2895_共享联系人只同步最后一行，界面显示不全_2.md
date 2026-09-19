# SIR-2895 · 共享联系人只同步最后一行/界面显示不全（PBAP状态机时序错乱）之一

- **提交**：`bc62d8bc` | 2026-07-21 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
连接蓝牙后，在通讯录同步过程中来回切换 tab（通话记录/联系人/收藏），共享联系人只同步出最后一行，界面列表显示不全。

## 根因分析
三个 Fragment 的 `onResume` 状态机此前被人打过"优化补丁"（注释里还留着【修复】字样）：稳定态（`Success/SyncFailed/BluetoothDisconnected`）直接 skip PBAP 重查，且该跳过判断**放在了授权检查之前**——未授权时也会被当作"稳定态"跳过，等待授权的 UI 不再出现；同步进行中（`Loading` 置了 `mSyncInProgress=true`）又直接 `return`，`handleResumeWithContactsAuthorized()` 被注释掉不执行，切走再切回后同步流程没有恢复驱动，列表数据只落了一部分（只显示最后一行）。同时 `BluetoothManager.isPbapAuthorized()` 每次都 `pbapAuthorized.postValue(authorized)`，相同值重复发送导致页面反复重载，`ContactsViewModel.handlePbapAuthorizationChange` 是 private，Fragment 无法在恢复时主动驱动同步。缺陷库记录"搜索结果列表显示区域错误"，与 diff 实际（状态机恢复逻辑缺失）不完全一致，以 diff 为准。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogFragment.java；application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java；application/BTPhone/src/main/java/com/yadea/btphone/fragment/FavoritesFragment.java；application/BTPhone/src/main/java/com/yadea/btphone/manager/BluetoothManager.java；application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java
```diff
--- a/.../fragment/FavoritesFragment.java（CallLogFragment 同模式）
+        BluetoothManager bluetoothManager = BluetoothManager.getInstance(getActivity());
+        boolean isPbapAuthorized = bluetoothManager != null && bluetoothManager.isPbapAuthorized(FAV_PATH);
+        if (!isPbapAuthorized) {
+            showWaitingForContactsAuth();
+            return;
+        }
-        if (currentState instanceof FavoritesUiState.Success ||
+        if (isPbapAuthorized && (currentState instanceof FavoritesUiState.Success ||
                 currentState instanceof FavoritesUiState.SyncFailed ||
-                currentState instanceof FavoritesUiState.BluetoothDisconnected) {
+                currentState instanceof FavoritesUiState.BluetoothDisconnected)) {
             ...skip PBAP re-check...
@@ Empty 状态
-            //handleResumeWithContactsAuthorized();
+            handleResumeWithContactsAuthorized();
@@ Loading 状态分支
                 if (uiState instanceof FavoritesUiState.Loading) {
                     mSyncInProgress = true;
                 }
-                return;
             }
-        ...（删除重复的授权检查，末尾恢复 handleResumeWithContactsAuthorized()）
--- a/.../manager/BluetoothManager.java
-        pbapAuthorized.postValue(authorized);
+        // 判断当前授权状态是否改变，防止重复发送数据，导致页面重复加载
+        if (Boolean.TRUE.equals(pbapAuthorized.getValue()) != authorized) {
+            pbapAuthorized.postValue(authorized);
+        }
--- a/.../viewmodel/ContactsViewModel.java
-    private void handlePbapAuthorizationChange(Boolean authorized) {
+    public void handlePbapAuthorizationChange(Boolean authorized) {
```

## 为什么能修复
① 把授权检查提前到 `onResume` 最前并加 `isPbapAuthorized &&` 前置条件，"稳定态跳过"只在确已授权时生效；② `Loading` 不再提前 `return`，恢复到前台会继续走到 `handleResumeWithContactsAuthorized()`，同步流程被重新驱动，数据不再半途而废；③ `pbapAuthorized` 只在值变化时 `postValue`，消除重复加载；④ `handlePbapAuthorizationChange` 改 public 供 `ContactsFragment.onResume` 主动调用。多管齐下后"切 tab 打断同步→列表不全"的链路被打通。隐患：`onResume` 直接查授权仍会触发 PBAP listing，性能依赖 BluetoothManager 内部缓存。

## 复盘与经验
- 给状态机加"跳过优化"时，跳过条件必须包含完整前置守卫（此处漏了授权检查），否则优化会吞掉必要的恢复路径。
- "同步进行中就 return"是伪保护：页面离开再回来必须能重新拉起同步，Loading 分支应继续走恢复逻辑而不是中断。
- LiveData `postValue` 同值重发会让观察者整页重载，写入前先比对旧值是廉价且必要的幂等保护。
