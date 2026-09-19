# SIR-8321 · 按号码搜索联系人不命中多号码联系人

- **提交**：`19600627` | 2026-09-11 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 挂起 · 域 蓝牙电话

## 问题
蓝牙电话界面输入已存储联系人的电话号码进行搜索，若该联系人有多个号码，输入的是非第一个号码时联系人检索不到、界面不显示。

## 根因分析
`ContactsViewModel` 按号码搜索时，对每个 `ContactData` 调用 `getItem(contact)` 取号码——该方法无条件返回 `phoneList.get(0)`（第一个号码），搜索匹配只针对第一个号码进行。缺陷库根因"同一联系人有多个号码时，只会匹配第一个号码"准确：用户输入第二个/第三个号码时匹配失败，`getItem` 命中不了该联系人，结果列表为空。多号码联系人在蓝牙通讯录（PBAP 同步）中很常见（手机+工作等），故为低概率但合理的场景。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/ContactsViewModel.java`
```diff
-            ContactData.PhoneItem firstPhone = getItem(contact);
+            ContactData.PhoneItem matchedPhone = getItem(contact, numberStr);
             ...
-            String pinyin = pinyin(contact, firstPhone);
+            String pinyin = pinyin(contact, matchedPhone);
             ...
-            searchResult.setPhone(firstPhone.getNumber());
+            searchResult.setPhone(matchedPhone.getNumber());
@@
-    private static ContactData.PhoneItem getItem(ContactData contact) {
+    private static ContactData.PhoneItem getItem(ContactData contact, String numberStr) {
         ...
-        ContactData.PhoneItem firstPhone = phoneList.get(0);
-        if (firstPhone == null || firstPhone.getNumber() == null) {
-            return null;
+        ContactData.PhoneItem firstPhone = null;
+        for (ContactData.PhoneItem phone : phoneList) {
+            if (phone == null || phone.getNumber() == null) {
+                continue;
+            }
+            if (firstPhone == null) {
+                firstPhone = phone;
+            }
+            // 优先使用当前输入命中的号码，避免多号码联系人只能搜索第一个号码
+            if (!TextUtils.isEmpty(numberStr) && phone.getNumber().contains(numberStr)) {
+                return phone;
+            }
         }
         return firstPhone;
     }
```

## 为什么能修复
`getItem` 改为遍历联系人的所有号码：任一号码 `contains(numberStr)` 即返回命中的那个 `PhoneItem`，搜索结果的号码展示也随之用命中号码（`searchResult.setPhone(matchedPhone.getNumber())`）；无命中时回退返回第一个有效号码（保持旧的不带号码搜索行为），并顺带修掉了原 `get(0)` 可能越界/返回 null 元素的隐患。时间复杂度仍是 O(号码数)，无性能与副作用。

## 复盘经验
- "取 first"式字段访问（`list.get(0)`）在多值字段（多号码、多邮箱）上做匹配必然漏数据，匹配逻辑要遍历全集。
- 搜索结果展示与匹配使用同一个 `PhoneItem`（命中的那个），避免"搜中 B 号码、展示 A 号码"的错位。
- 顺带消除 `get(0)` 对空列表/含 null 元素列表的崩溃隐患，属于低成本的防御性收益。
