# SIR-6721 · 单联系人（无号码）同步空白且进度条卡住
- **提交**：`884c75d6` | 2026-08-28 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
手机通讯录只有 1 个联系人（有姓名、无号码）时，车机端联系人列表显示空白；点"重新获取"后"正在同步"进度条一直卡住不结束。

## 根因分析
两个叠加问题。其一，PBAP 协议下载的 vcf 列表首条 `0.vcf` 是"本机号码"占位项，协议层按"数量-1"的口径工作：`InCallServiceImpl` 中原代码 `int requestedContacts = size > CONTACTS_COUNT_LIMIT ? MAX_CONTACTS_COUNT : size;` 直接用协议数量 `size` 作为请求数，实际能取回的有效联系人是 `size-1` 条；当 `size==1` 时取回 0 条有效联系人，同步完成回调判定不满足，进度条卡住。其二，联系人转换链路（`ContactsFragment` 手动转换、`ContactRepository.getContacts`）把"无号码"的联系人整体丢弃（`getmNumber()` 为空即 continue），即使同步成功，仅有姓名无号码的联系人也不会出现在列表，表现为"空白"。

## 关键代码修改
改动文件：InCallServiceImpl.java、ContactsFragment.java、ContactRepository.java
```diff
--- a/.../telecom/InCallServiceImpl.java
@@
-                        int requestedContacts = size > CONTACTS_COUNT_LIMIT
-                                ? MAX_CONTACTS_COUNT : size;
-                        totalContacts = requestedContacts;
+                        totalContacts = Math.min(size, CONTACTS_COUNT_LIMIT);
+                        int requestedContacts = totalContacts > 0 ? totalContacts + 1 : 0;
                         LogUtils.d(TAG, "totalContacts is------:" + totalContacts);
                         if (result == 0) {
-                            // 协议数量超过5000时使用5001作为边界值，否则使用协议实际数量。
+                            // 0.vcf为本机号码：非空通讯录请求数需在协议联系人数量上加1，最大请求5001条。
```
```diff
--- a/.../fragment/ContactsFragment.java
@@ 手动转换 PBAP 联系人
-            if (pbapContact != null && pbapContact.getmNumber() != null && !pbapContact.getmNumber().isEmpty()) {
+            if (pbapContact == null) {
+                continue;
+            }
             List<ContactData.PhoneItem> phoneItemList = new ArrayList<>();
             if (pbapContact.getmNumber() != null) { ... }
+            String displayName = pbapContact.getdisplayName();
+            ContactData contactData = new ContactData(displayName, phoneItemList);
+            ...
+            cachedContacts.add(contactData);
```
`ContactRepository.getContacts` 做同样的改造：null 判断提前 continue，号码快照失败仅告警不跳过，`phoneItemList` 为空也构造 `ContactData` 加入 `mContactList`；同时删除了只为"无号码即丢弃"服务的私有方法 `getmNumber(...)`。

## 为什么能修复
请求数 `+1` 补偿了 `0.vcf` 本机号码占位条目，使有效联系人数与 `totalContacts` 一致，同步完成判定不再落空，进度条正常走完（上限仍钳制在 5001，不越协议边界）。转换逻辑改为"只丢 null、不丢无号码"，有姓名无号码的联系人得以入列显示。隐患：无号码联系人进入列表后，点击拨号等下游逻辑需容忍空 `phoneItemList`，本提交在两处转换点统一了该约定，但搜索/排序等旁路若也有同样过滤需另行排查。

## 复盘与经验
- 协议层的隐含约定（0.vcf 占位、数量-1 口径）必须在调用侧显式注释并补偿，这次是把旧注释"超过5000时用5001作为边界值"改成了说清因果的注释，值得学习。
- "进度条卡住"类问题优先怀疑完成回调的计数条件和数据口径不一致，而不是 UI 本身。
- 同一份数据在多处手动转换（Fragment 一份、Repository 一份）极易出现过滤规则不一致，应下沉到 Repository 单点转换。
