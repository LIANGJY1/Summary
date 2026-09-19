# VIR-36 · 蓝牙中断恢复后通话弹窗无联系人姓名（同步完成不刷新进行中通话）

- **提交**：`a17f79ad` | 2026-07-24 | liujinfeng | BTPhone | bugfix
- **缺陷库**：未关联单号（VIR 平台单）

## 问题
蓝牙连接中断后再恢复，恢复瞬间来电时，通话弹窗里联系人信息缺失（只显示号码无姓名），来电功能不能完整自动恢复。

## 根因分析
蓝牙重连后 PBAP 通讯录需要重新同步，同步完成前 `mPbapContacts` 为空——此时若有通话在进行/来电，`UiCall.getContactName()` 无数据源可查，弹窗只能显示号码；而通讯录同步完成后，代码只刷新了联系人列表 UI（`mContacts.postValue`），**从不回头补刷"进行中通话"的姓名**，导致中断恢复场景下通话弹窗永远拿不到姓名。提交 [why]"通讯录同步完成之前，无法取到联系人信息"、[how]"同步完成后，主动刷新通话弹窗"与 diff 一致。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/repository/ContactsRepository.java；application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
```diff
--- a/.../repository/ContactsRepository.java
@@ 通讯录同步完成、LiveData 更新之后
                             mContacts.postValue(objectList);
+                            // 检查并刷新当前通话信息
+                            refreshOngoingCallContactNames();
@@ 新增方法
+    private void refreshOngoingCallContactNames() {
+        List<UiCall> calls = UiCallManager.get().getCalls();
+        ...
+        for (UiCall call : calls) {
+            String normalizedCallNumber = call.getNumber().replaceAll("[^0-9+]", "");
+            String matchedName = findContactNameByNumber(normalizedCallNumber);
+            if (matchedName != null && !matchedName.equals(call.getContactName())) {
+                call.setContactName(matchedName);
+                anyUpdated = true;
+            }
+        }
+        if (anyUpdated) {
+            RxBus.getInstance().post(new RxEventMsg<>(Constants.EventCode.UPDATE_CALLS, null));
+        }
+    }
+    // findContactNameByNumber：PBAP 缓存精确匹配 + ≥7 位后缀匹配（处理带区号）
--- a/.../floatview/FloatCallWindowPresenter.java
+        // 监听通讯录同步完成，刷新联系人名称
+        addDisposable(RxBus.getInstance().toObservable(RxEventMsg.class, event -> {
+            if (event.getCode() == Constants.EventCode.UPDATE_CALLS) {
+                onCallStateChanged(UiCallManager.get().getPrimaryCall());
+            }
+        }));
```

## 为什么能修复
同步完成的收尾处新增 `refreshOngoingCallContactNames()`：用号码（归一化 + ≥7 位后缀匹配，兼容带区号）到新同步的 PBAP 缓存反查姓名，命中则 `call.setContactName` 并发 `UPDATE_CALLS` 事件；浮窗 Presenter 订阅该事件后调 `onCallStateChanged` 重渲染通话弹窗。这样"重连→来电→同步完成"的时序里，姓名会迟到但不缺席。隐患：Presenter 里 `UiCallManager.get().getPrimaryCall()` 未判空（若此刻无通话有 NPE 风险）；后缀匹配在极端相似号码间可能误匹配。

## 复盘与经验
- 依赖异步数据源（通讯录）的界面，数据到达后要"回扫"所有已渲染的视图状态，不能只刷新列表本体——进行中的通话、置顶的卡片都是易漏的消费者。
- 用事件总线（RxBus `UPDATE_CALLS`）解耦"数据就绪"与"界面刷新"，比各界面自己轮询同步状态干净。
- 电话号码匹配必须先归一化（去掉分隔符）再做后缀匹配，区号/格式差异是"通讯录里有却查不到"的最常见原因。
