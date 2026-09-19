# SIR-5984 · 荣耀x20同步通讯录后拨打电话，弹窗界面不显示联系人名称

- **提交**：`0312041e` | 2026-08-19 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
荣耀x20手机同步完通讯录后拨打电话，通话弹窗（浮窗）界面上不显示联系人名称，只显示号码或空白。

## 根因分析
缺陷库根因："传入的联系人姓名被清空了"。代码对应 `UiCallManager.safePlaceCall()`：旧逻辑在拨号前判断号码类型，若 `PhoneCheckUtil.isMobile(number) || PhoneCheckUtil.isTel(number)` 就执行 `updateLastDisplayName("")` 把显示名强制清空，本意大概是让后续流程从联系人缓存按号码重新匹配最新姓名；但当通话状态的联系人缓存数据异常（同步完成后缓存尚未就绪/被清）时，按号码匹配不出任何名字，而 UI 侧原本传入的 `displayName` 又已被清空，浮窗就没有名称可显示——低概率复现正对应"缓存异常"这一前置条件。提交说明 `[how]保留ui缓存数据传过去的名字作为兜底显示`。本提交同时携带了大量联系人同步链路的加固（新增 `checkContactsAuthorizationSilently()` 静默校验授权、`ContactsAdapter.updateContactsList` 用 `updateGeneration` 原子计数丢弃过期后台转换结果、PBAP 断开时统一清缓存等），属于同一问题域的连带修复。

## 关键代码修改
改动文件：UiCallManager.java、TelecomForward.java、IPhoneForward.java、InCallServiceImpl.java、BluetoothManager.java、ContactsFragment.java、ContactsAdapter.java、ContactsViewModel.java（8 文件，+295/-91）
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java @@
     public boolean safePlaceCall(String number, String displayName) {
         LogUtils.d(TAG, "Incoming call name: " + displayName + ", number: " + number);
-        if (PhoneCheckUtil.isMobile(number) || PhoneCheckUtil.isTel(number)) {
-            updateLastDisplayName("");
-        } else {
-            updateLastDisplayName(displayName);
-        }
+        updateLastDisplayName(displayName);
```
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/adapter/ContactsAdapter.java 丢弃过期转换结果 @@
+    private final AtomicInteger updateGeneration = new AtomicInteger();
...
     public void updateContactsList(List<ContactData> newContactList, Runnable onComplete) {
+        int generation = updateGeneration.incrementAndGet();
...
                 .subscribe(result -> {
+                    if (generation != updateGeneration.get()) {
+                        Log.d(TAG, "Ignore stale contacts conversion result, generation=" + generation);
+                        return;
+                    }
                     this.listItems = result.items;
```

## 为什么能修复
拨号时不再把 `displayName` 清成空串，UI 缓存里已有的姓名始终被保留为兜底；即便通话状态联系人缓存匹配失败，浮窗仍能显示传入的名字，消除了"清空+匹配失败=无名称"的组合条件。副作用：若号码在通讯录中被改名，浮窗可能短暂显示旧名（以拨号时刻传入的名为准），属可接受的取舍。`ContactsViewModel`/`ContactsFragment` 侧把"页面初始化自动全量同步"改为 `showEmptyWithoutSync()` + 静默数量校验，减少同步风暴对缓存一致性的干扰，是针对根因环境的治理。

## 复盘与经验
- "先清掉旧值再等新值回填"的兜底模式，一旦回填源不可靠（缓存异常/未就绪）就会两头落空；保留旧值作为兜底显示永远是更稳的选择。
- 低概率 bug 常是"清空动作"与"缓存异常"两个条件叠加，单看任意一条路径都复现不了，复盘要找状态组合而不是单点。
- 大提交里主线修复往往只有几行（safePlaceCall），其余是对同一数据链路的加固；阅读这类 commit 要先锁定与缺陷库 sol 对应的 hunk。
