# SIR-8209 · 蓝牙已连接但联系人数据不能同步（卡 loading）

- **提交**：`875d51f0` | 2026-09-11 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 待测试验证 · 域 蓝牙电话

## 问题
蓝牙已连接、数据共享已开启，进入蓝牙电话后联系人同步一直卡在 loading，通话记录/联系人拉不下来。

## 根因分析
`InCallServiceImpl` 的联系人拉取链路分两步：先通过 PBAP listing 广播拿到联系人数量，回调里调用 `startPullPhoneBook(size)`；方法内部再用 `UiBluetoothMonitor.get().getMainDevice()` 现取"当前活跃 HFP 设备"来发起 `mBluetoothPbapClient.pullPhonebook(...)`。两步用的 device 来源不一致，构成状态竞态：在权限后授权、设备切换等场景，取 listing 数量时和发起拉取时的"主设备"可能不同甚至为 null——`currentDevice == null` 时直接 `return`，且既不置 `STATE_DOWNLOAD_FAIL` 也不同步下载状态，UI 的 Loading 永远等不到结束事件；即便取到旧设备，pullPhonebook 发向错误设备也会失败。缺陷库根因"不依赖蓝牙广播的 device 来请求数据，使用协议广播返回的 device"准确描述了这一错配。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java`
```diff
-                            startPullPhoneBook(requestedContacts);
+                            startPullPhoneBook(device, requestedContacts);
```
```diff
-    public void startPullPhoneBook(int size){
+    public void startPullPhoneBook(BluetoothDevice device, int size){
         ...
         if(mBluetoothPbapClient == null){
+            LogUtils.w(TAG, "Cannot pull contacts because PBAP client is unavailable");
+            mDownloadStates.setPbDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
+            ContactsRepository.getInstance(getApplication()).syncDownloadStates(mDownloadStates);
             return;
         }
-        // 不再信任 mBluetoothPbapClient.getConnectedDevices() … 直接从 UiBluetoothMonitor 获取当前活跃的HFP设备
-        BluetoothDevice currentDevice = UiBluetoothMonitor.get().getMainDevice();
-        if (currentDevice == null) {
+        // 使用 listing 结果广播携带的设备，保证联系人数量和后续拉取属于同一次 PBAP 请求。
+        if (device == null) {
             LogUtils.w(TAG, "Cannot pull contacts because broadcast device is unavailable");
             endContactsDownload();
+            mDownloadStates.setPbDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
+            ContactsRepository.getInstance(getApplication()).syncDownloadStates(mDownloadStates);
             return;
         }
         ...
         boolean pullPhonebookResult = mBluetoothPbapClient.pullPhonebook(
-                currentDevice, PB_PATH, 0x8F, 0, size);
+                device, PB_PATH, 0x8F, 0, size);
         if(!pullPhonebookResult){
             endContactsDownload();
             mDownloadStates.setPbDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
-            if (ContactsFragment.getUiState() instanceof ContactsUiState.Loading) {
-                ContactsRepository.getInstance(getApplication()).syncDownloadStates(mDownloadStates);
-            }
+            ContactsRepository.getInstance(getApplication()).syncDownloadStates(mDownloadStates);
         }
     }
```

## 为什么能修复
把"数量回调广播里携带的 device"一路透传给 `pullPhonebook`，数量统计与数据拉取绑定到同一次 PBAP 会话/同一设备，消除了两步之间主设备漂移的竞态；所有提前 return 的失败分支（PBAP client 为空、device 为空、pull 失败）统一补上 `STATE_DOWNLOAD_FAIL` + `syncDownloadStates`，UI 不再永久 Loading；去掉 `ContactsFragment.getUiState() instanceof Loading` 的前置条件，失败状态总能送达仓库层。隐患：多设备场景下若广播 device 与用户当前选中设备不同，会拉取"发起请求那台"的数据，需确认产品语义。

## 复盘与经验
- 跨回调两步操作（A 回调取数、B 方法消费）必须把上下文（device、会话 id）显式透传，不要在消费端重新查询"当前状态"——重新查询就是竞态入口。
- 提前 return 的失败分支必须同步置失败状态并通知 UI，"静默 return"是 Loading 永挂的常见来源。
- 状态同步前加 UI 状态判断（`instanceof Loading`）看似精准，实则让数据层行为依赖视图层状态，应无条件同步。
