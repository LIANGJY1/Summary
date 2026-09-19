# SIR-983 · 同步五千联系人过程 ANR 异常退出
- **提交**：`20ad0f11` | 2026-07-03 | duanlonglong | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙电话同步约五千联系人过程中应用 ANR、页面异常退出（偶现）。

## 根因分析
同步链路的三处主线程重活叠加导致 ANR：
1. `ContactsRepository.syncContactData`（`application/BTPhone/.../repository/ContactsRepository.java`）在主线程对全量联系人执行 `deduplicateContacts(contacts)` 去重，五千条级别的比较/建表直接卡住 UI 线程；
2. 下载完成回调 `syncDownloadStates` 与数据为空兜底逻辑中，主线程直接调用阻塞式 Binder IPC `phoneForward.getAllContacts()` 从 TelecomForward 拉取全量联系人，IPC 返回大 Parcel 时主线程被长时间挂起；
3. `ContactsAdapter.updateCallLogList` 收到新列表后在主线程执行 `convertToListItems`（按字母 `LinkedHashMap` 分组 + 每个号码展开一条记录），五千联系人转换出上万个列表项，主线程一次性构建。
三段耗时串在主线程上，输入法/系统调度稍慢即触发 5 秒 ANR。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/adapter/ContactsAdapter.java`、`application/BTPhone/src/main/java/com/yadea/btphone/repository/ContactsRepository.java`、`application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java`
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/repository/ContactsRepository.java
@@ -128,21 +129,24 @@
-        // 【修复】对联系人数据进行去重处理
-        List<BluetoothPbapContact> deduplicatedContacts = deduplicateContacts(contacts);
-        this.mPbapContacts = deduplicatedContacts;
-        refreshContactsUI();
+        // 【修复】将去重和UI刷新移到后台线程执行
+        io.reactivex.Single.fromCallable(() -> {
+                    List<BluetoothPbapContact> deduplicatedContacts = deduplicateContacts(contacts);
+                    return deduplicatedContacts;
+                }).subscribeOn(io.reactivex.schedulers.Schedulers.io())
+                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
+                .subscribe(deduplicatedContacts -> {
+                    this.mPbapContacts = deduplicatedContacts;
+                    refreshContactsUI();
+                }, error -> LogUtils.e(TAG, "syncContactData deduplication failed: " + ...));
```
同款改造应用于：`syncDownloadStates` 中 `phoneForward.getAllContacts()` 的阻塞 Binder IPC（包进 `Single.fromCallable` + `Schedulers.io()`，结果回到主线程再 `refreshContactsUI/_contacts.postValue`）；`ContactsAdapter.updateCallLogList` 中 `convertToListItems(newContactList)` 的分组转换（`Single.fromCallable(...).subscribeOn(io).observeOn(mainThread)` 后才 `notifyDataSetChanged`）。另含结构整理：点击回调抽出 `handleContactClick`、分组逻辑拆出 `groupContactsByLetter/addContactItems`、`computeIfAbsent` 替代 containsKey+put。

## 为什么能修复
三处耗时操作（全量去重、大列表转换、阻塞 IPC）全部移到 `Schedulers.io()` 后台线程，主线程只保留轻量的 UI 提交（`notifyDataSetChanged`、`postValue`），五千联系人的同步不再阻塞 UI 线程，ANR 消除。风险点：RxJava 订阅未做生命周期管理（`@SuppressLint("CheckResult")` 丢弃 Disposable），页面销毁后回调仍会触达 adapter/repository；且并发同步到达时无串行化保护，理论上存在旧结果覆盖新结果的窗口。

## 复盘与经验
- "数据量小的时候没事"的同步代码在五千条规模下就是 ANR：凡循环分组/去重/展开列表项的代码，默认放后台线程。
- 阻塞式 Binder IPC（`getAllContacts` 返回全量大列表）与磁盘 IO 同级危险，主线程调用是 ANR 高发点。
- RxJava `fromCallable + subscribeOn(io) + observeOn(mainThread)` 是最小侵入的异步化模板，但要配套管理 Disposable 与结果时序，否则引入新的竞态。
