# SIR-5977 · 荣耀x20同步通讯录后联系人数量比手机少一条（5000条边界+数据转换异常）

- **提交**：`e79654bd` | 2026-08-19 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
荣耀x20手机通讯录超过五千条时，车机同步完成后显示的联系人比手机端少（4999条），表现为同步数量边界值 + 数据转换丢失。

## 根因分析
缺陷库给出两层原因：一是手机通讯录超过五千条时 PBAP 同步存在 5000 条边界限制，车机只拉取 5000 条；二是真正造成"少一条"的数据转换环节，`InCallServiceImpl` 在同步完成后把同一个 `contactsSnapshot` 列表（元素为 `BluetoothPbapContact`）同时交给 `ContactsRepository.syncContactData()` 和 `ContactRepository.get().syncContactData()` 两个仓库。两个仓库共享同一批联系人对象并各自内部修改，RxJava 的 `Single.fromCallable` 在 IO 线程执行转换时与其它线程的访问产生并发修改异常，转换一旦抛异常该条联系人即被丢弃，最终显示 4999 条。提交说明 `[why]数据转换时出现异常`、`[how]数据转换传入原始数据的深拷贝版，防止并发修改异常` 与代码一致。本次还顺带加入了大量"收藏PBAP诊断"日志（`logFavoritePbapContacts`），说明作者同时在排查 PBAP 回调链路。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java（+108/-5，另含大量诊断日志）
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java @@
-                                            ContactsRepository.getInstance(getApplication()).syncContactData(contactsSnapshot);
-                                            ContactRepository.get().syncContactData(contactsSnapshot);
+                                            List<BluetoothPbapContact> uiContacts = deepCopyContacts(contactsSnapshot);
+                                            List<BluetoothPbapContact> telecomContacts = deepCopyContacts(contactsSnapshot);
+                                            ContactsRepository.getInstance(getApplication()).syncContactData(uiContacts);
+                                            ContactRepository.get().syncContactData(telecomContacts);
```
```diff
@@ InCallServiceImpl.java 新增深拷贝工具 @@
+    private static List<BluetoothPbapContact> deepCopyContacts(List<BluetoothPbapContact> contacts) {
+        List<BluetoothPbapContact> copies = new ArrayList<>(contacts.size());
+        for (BluetoothPbapContact contact : contacts) {
+            if (contact == null) {
+                copies.add(null);
+                continue;
+            }
+            Parcel parcel = Parcel.obtain();
+            try {
+                contact.writeToParcel(parcel, 0);
+                parcel.setDataPosition(0);
+                copies.add(BluetoothPbapContact.CREATOR.createFromParcel(parcel));
+            } finally {
+                parcel.recycle();
+            }
+        }
+        return copies;
+    }
```

## 为什么能修复
通过 `Parcel` 对每个 `BluetoothPbapContact` 做 `writeToParcel`/`createFromParcel` 完整深拷贝，两个 Repository 拿到的是互相独立的数据副本，任一仓库内部修改不再影响另一个，消除了转换过程中的并发修改异常，联系人不因异常被丢弃。副作用：5000 条通讯录全量深拷贝会带来一次内存翻倍与序列化耗时，在车载内存上属于可接受代价；5000 条边界本身（协议/上游限制）本次未真正解除，仅修复了少一条的显示问题。

## 复盘与经验
- 一个共享数据快照要喂给多个消费者时，默认按"只读约定"很容易被打破；对 Parcelable 对象用 Parcel 序列化是最省事的深拷贝手段。
- "同步数量差一条"这类看似边界问题，实际可能是转换异常吞数据——先看异常日志再下边界结论。
- 大批量数据转换应放在单线程内完成或使用不可变副本，RxJava `fromCallable` 切到 IO 线程并不意味着线程安全。
- 提交中混入大量临时诊断日志（含完整电话号码），作者自己在注释里标注"问题确认后应删除或降级为脱敏日志"，这类日志应有机制保证后续清理。
