# VIR-36 · 蓝牙中断恢复后来电功能自动恢复（复合修复）
- **提交**：`6e601c9d` | 2026-08-16 | liujinfeng | BTPhone | bugfix（复合提交：恢复链路 + 来电姓名数据封装）
- **缺陷库**：未关联缺陷记录（VIR-36 无 defs 数组条目）
- **注**：单标题为"蓝牙连接中断后再恢复，来电功能可自动恢复正常"，提交正文却写"[what]联系人来电只显示名字"，diff 实际同时覆盖两件事（进程退出方式、缓存恢复、姓名/号码封装），以 diff 实际为准。

## 问题
蓝牙连接中断再恢复后，来电功能不能自动恢复正常；来电时联系人姓名与号码的数据封装错误（姓名字段被拼上号码，UI 又按"有姓名就不显示号码"处理，最终只显示名字）。

## 根因分析
1. `MainActivity.onLapseDownExit()` 原实现 `finishAffinity()` + `Process.killProcess(Process.myPid())`——用户下滑退出即杀进程，蓝牙电话服务随之死亡，蓝牙断连重连后无人重建来电监听，来电功能无法自动恢复。
2. `ContactRepository.queryContactByPhoneNum()` 等处把显示名封装成 `displayName + " " + phoneNum`，`finallyPhoneContact()` 亦拼接格式化号码；`ViewUtil.updateCallInfo2()` 原逻辑是"有 contactName 就只显示姓名、否则显示号码"，拿到被污染的姓名字段后号码永久丢失。
3. 进程被杀重建后 `ContactsViewModel`/`ContactsFragment` 的恢复逻辑缺失：`updatePage()` 在 uiState 为 null 时会重新触发 PBAP 同步；`setupSyncProgressListener()` 的 RxBus 订阅未保存 `Disposable` 也未注销，重建后重复订阅。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java`、`fragment/ContactsFragment.java`、`telecom/repositories/ContactRepository.java`、`telecom/utils/ViewUtil.java`、`viewmodel/ContactsViewModel.java`
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
     public void onLapseDownExit() {
-        finishAffinity();
-        Process.killProcess(Process.myPid());
+        finish();
     }
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/repositories/ContactRepository.java
-                    contact.setDisplayName(displayName+" "+phoneNum);
+                    contact.setDisplayName(displayName);
...
-                mPhoneContact.setDisplayName(bluetoothContact.getDisplayName() + " " + TelecomUtils.getFormattedNumber(context, phoneNum));
+                String contactName = bluetoothContact.getDisplayName();
+                mPhoneContact.setDisplayName(contactName);
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/utils/ViewUtil.java
         String rawContactName = call.getContactName();
         String rawNumber = call.getNumber();
         String displayNumber = StringUtil.formatPhoneSpaces(rawNumber);
         if (!TextUtils.isEmpty(rawContactName)) {
-            tvName.setNameAndNumber(StringUtil.stringMaxNameOrPhone(StringUtil.formateDisPlayName(call.getContactName())));
+            String formattedContactName = StringUtil.formateDisPlayName(rawContactName);
+            String callInfo = TextUtils.isEmpty(displayNumber)
+                    ? formattedContactName
+                    : formattedContactName + " " + displayNumber;
+            tvName.setNameAndNumber(StringUtil.stringMaxNameOrPhone(callInfo));
         } else { ... }
```
（另：`ContactsFragment.updatePage()` 先查 `viewModel.getRepositoryContacts()`，缓存非空则 `forceUpdateSuccessState` 恢复并跳过自动同步；`syncProgressDisposable` 保存并在 `onDestroyView` 注销；`ContactsViewModel` 在 PBAP 授权更新时若已有缓存联系人直接 `Success` 恢复、下载中状态改为"继续观察不重启超时"。）

## 为什么能修复
退出改为 `finish()` 后进程与前台服务存活，蓝牙重连时监听链路自动恢复，来电功能不再依赖进程重启；姓名字段回归"纯姓名"语义，号码由 ViewUtil 统一格式化拼接，"只显示名字"与姓名带尾随号码两个问题同时消除；缓存恢复 + 订阅注销让 Activity 重建不重触发同步、不泄漏监听，保证中断恢复链路状态一致。隐患：不再杀进程后需确认服务在不需要时能正常自停，避免常驻。

## 复盘与经验
- 车机常驻应用禁止 `killProcess` 自杀式退出：进程死亡会连带摧毁协议栈监听与服务，"退出再进来功能失效/不自动恢复"多源于此。
- 数据封装层（Repository）与展示层（ViewUtil）对字段的语义必须单一：姓名字段拼号码等于把两层数据耦合，展示层的分支判断（有名字就不显示号码）立刻失真。
- 复合问题排查顺序：先保进程/服务存活，再查数据流封装，最后处理重建恢复与订阅泄漏，三者共同构成"中断恢复"完整性。
