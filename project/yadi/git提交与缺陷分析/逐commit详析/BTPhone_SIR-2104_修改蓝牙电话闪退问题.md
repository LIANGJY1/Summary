# SIR-2104 · 蓝牙电话偶发闪退（ConcurrentModificationException）

- **提交**：`ee82ac63` | 2026-07-08 | duanlonglong | BTPhone | bugfix
- **缺陷库**：等级 A · 频次 偶现-低于10% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙电话应用在来电/通话状态变化时偶发崩溃（等级 A），进程直接退出。崩溃发生在通话状态从 ACTIVE 转 DISCONNECTED、系统需要通过来电号码查询联系人信息的过程中。

## 根因分析
崩溃点是 `ConcurrentModificationException`：`ContactRepository` 的联系人号码列表 `mPbapContacts`（`ArrayList<BluetoothPbapContact>`）及其内部 `getmNumber()` 列表被**多线程共享**——蓝牙 PBAP 通讯录同步线程在写入/合并（`mergeNumbers` 直接改 target 内部列表），而通话状态回调线程在遍历（按号码 `findContactFromPbapData` 查联系人）。ArrayList 的 fail-fast 迭代器一旦发现 modCount 变化即抛 CME。偶现属性来自竞态窗口：只有"同步进行中 + 恰好来电/挂断触发号码查询"同时发生才崩。另外 `isContactLoading` 是普通 boolean，跨线程可见性也没有保证。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/telecom/repositories/ContactRepository.java`（+149/-...）、`.../telecom/repositories/RecentRepository.java`（同构改造）
```diff
// --- ContactRepository.java
-    private static boolean isContactLoading = true;
+    private static volatile boolean isContactLoading = true;
-    private List<BluetoothPbapContact> mPbapContacts = new ArrayList<>();
+    private List<BluetoothPbapContact> mPbapContacts = new CopyOnWriteArrayList<>();
+                List<String> numbersSnapshot;
+                try {
+                    numbersSnapshot = new ArrayList<>(pbapContact.getmNumber());
+                } catch (Exception e) {
+                    Log.w(TAG, "getContacts: failed to snapshot numbers, skip contact");
+                    continue;
+                }
                 List<ContactData.PhoneItem> phoneItemList = new ArrayList<>();
-                for (int j = 0; j < pbapContact.getmNumber().size(); j++) {
+                for (int j = 0; j < numbersSnapshot.size(); j++) { ... }
-            for (String contactNumber : pbapContact.getmNumber()) {   // findContactFromPbapData 同样先快照
+            for (String contactNumber : numbersSnapshot) { ... }
-                        mContactList = contactData;
+                        mContactList = new CopyOnWriteArrayList<>(contactData);
```

## 为什么能修复
三层防御闭环：`CopyOnWriteArrayList` 让"遍历 vs 写入"不再共享同一份 modCount，迭代器基于快照，CME 从结构上不可能发生；遍历前 `new ArrayList<>(...)` 快照拷贝（外包 try-catch 兜住快照瞬间的极端竞争）进一步隔离内部列表被并发修改的影响；`volatile` 保证 loading 标志跨线程立即可见。`RecentRepository` 同构改造消除了同类隐患。代价：COW 列表每次写都复制数组，通讯录量级（几千条）下可接受；快照拷贝有一次性内存开销。遗留疑点：`mergeNumbers` 中 `!TextUtils.isEmpty(number)` 判断被移除，空号码可能混入，与本 bug 无关但值得复查。

## 复盘与经验
- **"遍历共享集合 + 后台同步"是 CME 的标准配方**：车机上 PBAP 同步、CAN 信号、通话回调各自一条线程，任何被两者共享的集合默认按并发容器设计（COW/Collections.synchronized/快照），不要事后补救。
- **偶现 A 级崩溃优先看崩溃栈的集合操作点**，修复组合拳 = 并发容器 + 读取侧快照 + volatile 标志，三层各挡一类竞态。
- 修复时顺手保留的防御性 try-catch（snapshot 失败跳过该联系人）体现了"部分失败优于整体崩溃"的容错取向，适合稳定性敏感模块。
