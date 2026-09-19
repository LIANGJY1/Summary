# SIR-7487 · 手机拨打联系人电话，车机只显示号码不显示姓名
- **提交**：`7b7efe27` | 2026-09-04 | caohongliang | BTPhone | bugfix（联系人仓库合并重构）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
安卓手机与车机蓝牙同步通讯录后，手机上拨打有联系人名字的电话，车机通话界面只显示电话号码，不显示联系人姓名。

## 根因分析
项目里同时存在两份联系人仓库：旧的 `telecom.repositories.ContactRepository`（1000+ 行）与较新的 `repository.ContactsRepository`（注释"简化独立版本"）。通话姓名查询（`PhoneNumberLookup` / 通话界面链路）与联系人页面各自从不同仓库取数，PBAP 下载完成后旧代码要向两个仓库分别 `syncContactData`（`InCallServiceImpl` 中可见 `ContactsRepository.syncContactData(uiContacts)` + `ContactRepository.syncContactData(telecomContacts)` 双写）；双写一旦有一路失败/时序滞后（如页面还通过反射直接读 `mPbapContacts` 私有字段兜底），两份数据漂移，通话姓名查询单查到的那份为空或过期，于是"只显示号码不显示姓名"——缺陷库"联系人仓库未同步"即指此。

## 关键代码修改
改动文件（13 个，核心 3 个）：`application/BTPhone/src/main/java/com/yadea/btphone/repository/ContactsRepository.java`（扩为唯一仓库）、`.../telecom/repositories/ContactRepository.java`（整文件删除，-1006 行）、`.../telecom/InCallServiceImpl.java`；其余为 `BtPhoneApp`、`ContactsFragment`、`ContactSearchActivity`、`BluetoothManager`、`CallLogRepository`、`ContactListPresenter`、`DataExchangeCenter`、`FavoritesRepository`、`DataClearUtil`、`FuzzyMatchViewModel` 的引用切换。

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/repository/ContactsRepository.java
- * 通讯录数据仓库（简化独立版本）
+ * 通讯录唯一数据仓库：页面、检索和通话姓名查询共用同一份快照
...
-    private String contactsDataDeviceMac;
+    private volatile String contactsDataDeviceMac;
...
-    private List<BluetoothPbapContact> mPbapContacts = new ArrayList<>();
+    // 快照归仓库独占，发布后不再修改内部集合；清理和更新均替换整份快照。
+    private volatile List<BluetoothPbapContact> mPbapContacts = Collections.emptyList();
+    private List<ContactData> mContactList;
+    private final AtomicLong dataVersion = new AtomicLong();
+    private volatile boolean contactsUpdatePending;
+    private final MemoryContactControl memoryContactControl = new MemoryContactControl();
...
+    public synchronized List<ContactData> getContacts(boolean forceLoad) {
+        if (mContactList == null || forceLoad) {
...
+            mContactList = Collections.unmodifiableList(contacts);
...
+        return mContactList;
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java（下载完成双写 → 单写 + 完成序号校验）
-                            List<BluetoothPbapContact> uiContacts = deepCopyContacts(contactsSnapshot);
-                            List<BluetoothPbapContact> telecomContacts = deepCopyContacts(contactsSnapshot);
-                            ContactsRepository.getInstance(getApplication()).syncContactData(uiContacts);
-                            ContactRepository.get().syncContactData(telecomContacts);
+                            //（数据只落入统一仓库；新增完成广播与数据回调乱序的代际跟踪）
+    private volatile long mContactsSyncGeneration;
+    private long mContactsRepositoryVersion;
+    private boolean mContactsCompletionReceived;
+    private static final long CONTACTS_COMPLETION_TIMEOUT_MS = 10_000L;
+    private final Runnable mContactsCompletionTimeout = () -> {
+        if (mContactsDownloadInProgress && mContactsCompletionReceived) {
+            ...
+            endContactsDownload();
+            mDownloadStates.setPbDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
+            ContactsRepository.get().syncDownloadStates(mDownloadStates);
+        }
+    };
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java（删除反射读私有字段的兜底）
-            java.lang.reflect.Field pbapField = ContactRepository.class.getDeclaredField("mPbapContacts");
-            pbapField.setAccessible(true); // NOSONAR
-            List<android.bluetooth.BluetoothPbapContact> pbapContacts = ...
-            （60+ 行手工转换/强刷 UI 逻辑）
+            // 页面恢复使用统一仓库的转换结果，不再反射读取另一份原始缓存。
+            List<ContactData> contacts = new ArrayList<>(ContactsRepository.get().getContacts(false));
+            if (contacts.isEmpty()) { return; }
+            showContacts(contacts);
+            viewModel.forceUpdateSuccessState(contacts);
```

（`BtPhoneApp` 启动从 `ContactRepository.init(this)` 改为 `ContactsRepository.getInstance(this)`；`BluetoothManager` PBAP 断开只清统一仓库；全局 13 处引用切换。）

## 为什么能修复
把"双仓库双写 + 反射取私有字段"收敛为"单一 `ContactsRepository` + 不可变快照 + 版本号（`AtomicLong dataVersion`）"，页面、检索、通话姓名查询读同一份数据，双写漂移这一根因被结构性消除；`InCallServiceImpl` 引入完成广播与数据回调乱序的代际校验（`mContactsSyncGeneration`）与 10 秒超时，保证"下载完成"状态只在数据真正落库后发出，避免 UI 提前成功但仓库为空。风险点：1000 行级删除重构，被删仓库中若有未被发现的旁路功能（如某些诊断缓存），可能出现功能回退，需回归通讯录/收藏/最近联系人/模糊检索全链路。

## 复盘与经验
- "同一份数据两个仓库"是名字显示类 bug 的结构性根源：任何一路写失败都会漂移。修复的正解是合并仓库、单一事实源，而不是继续补双写一致性。
- 用反射读别的类的私有字段做"兜底恢复"是危险信号——说明数据归属设计已经出错；重构时应把这类 hack 一并删除（本例 `ContactsFragment.pbapContactsShow` 从 60+ 行缩到 10 行）。
- 异步下载完成要有"数据落库才发完成通知"的序号/版本校验（完成广播与数据回调可能跨线程乱序），外加超时兜底，否则 UI 状态与数据状态脱节。
- 大规模仓库合并提交务必同步切换全部引用（本例 13 个文件），并用全局搜索确认旧类零残留。
