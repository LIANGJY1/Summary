# SIR-6432 · 点击最近通话拨号，通话浮窗不显示联系人名称

- **提交**：`e0d01bb6` | 2026-08-26 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
最近通话列表里明明显示了联系人名称，点击该记录在车机上拨号后，通话浮窗却只显示号码、不显示名称。

## 根因分析
通话记录数据表与联系人数据表没有关联（缺陷库注明"协议给过来的数据就是这样"）：通话记录自带 `contactName`，而通话浮窗的 `updateCallContainerFromTelecom` 只从 `ContactRepository` 里按号码反查联系人。当该号码不在联系人库中时（蓝牙通话记录的名字来自手机侧 PBAP/MissedCall 协议数据），反查失败走到 else 分支仅打日志，名称丢失。同时入口链路也有断点：`CallLogDescFragment` 拨号时调 `UiCallManager.get().safePlaceCall(number, "")` 把名称硬编码为空串，且旧代码 `updateLastDisplayName(displayName)` 在 `safePlaceCall` 方法开头无条件执行——包括非车机发起的来电路径也会污染"最后一次呼出名称"，导致兜底数据源本身不可信。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogDescFragment.java`、`application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java`、`application/BTPhone/src/main/java/com/yadea/btphone/telecom/dataexchange/DataExchangeCenter.java`

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogDescFragment.java
@@ -211,7 +211,7 @@ public class CallLogDescFragment extends BaseFragment  {
                 String number = callLogItem.getPhoneNumber();
                 if (!StringUtil.isBlank(number)) {
                     // 执行拨号，让状态机管理UI跳转
-                    UiCallManager.get().safePlaceCall(number,"");
+                    UiCallManager.get().safePlaceCall(number, callLogItem.getContactName());
```

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
@@ -1374,7 +1374,6 @@ public class UiCallManager implements UiBluetoothMonitor.Listener {
     public boolean safePlaceCall(String number, String displayName) {
         LogUtils.d(TAG, "Incoming call name: " + displayName + ", number: " + number);
-        updateLastDisplayName(displayName);
@@ -1402,6 +1401,7 @@ public class UiCallManager implements UiBluetoothMonitor.Listener {
                 // 记录最后一次呼出的号码，便于主界面补全
                 setLastCallPhone(number);
+                updateLastDisplayName(displayName);
                 placeCall(number);
```

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/telecom/dataexchange/DataExchangeCenter.java
@@ -225,7 +227,18 @@ public class DataExchangeCenter {
                 } else {
-                    LogUtils.d(TAG, "no contact name found for number: " + number);
+                    String lastCallName = UiCallManager.getDisplayName();
+                    String lastCallNumber = UiCallManager.get().getLastCallPhone();
+                    if (Constants.isCallInitiatedByCar()
+                            && !TextUtils.isEmpty(lastCallName)
+                            && PhoneNumberUtils.compare(number, lastCallNumber)
+                            && !PhoneNumberUtils.compare(number, lastCallName)) {
+                        uiCall.setContactName(lastCallName);
+                    } else {
+                        LogUtils.d(TAG, "no contact name found for number: " + number);
+                    }
                 }
```

## 为什么能修复
修复构建了一条受约束的兜底链：点击通话记录拨号时把记录自带的 `contactName` 一并传入；`safePlaceCall` 只在"车机发起呼出"分支里把号码与名称成对登记（`setLastCallPhone` + `updateLastDisplayName`），保证兜底数据源与拨号动作原子对应、且不被来电路径污染；浮窗刷新时反查联系人失败，则只在同时满足"车机发起 + 兜底名非空 + `PhoneNumberUtils.compare` 号码匹配 + 名字不是号码本身"四个条件时回填 `uiCall.setContactName(lastCallName)`。号码比较用 `PhoneNumberUtils.compare` 兼容 +86 前缀等格式差异，`!compare(number, lastCallName)` 防止把号码当名字显示。副作用小：兜底名仅取最近一次车机呼出记录，号码不匹配或蓝牙来电一律走原逻辑。

## 复盘与经验
- 数据源不关联时（通话记录 vs 联系人），"列表显示的名称"与"详情/浮窗反查的名称"天然可能不一致，UI 层需要显式传递 + 兜底设计，不能默认两处必然取到同一个名字。
- 兜底信息必须带上下文校验（谁发起的、号码是否匹配、数据是否合理），否则会把 A 通话的名字错贴到 B 通话上；本例的四条件守卫是好的范例。
- `updateLastDisplayName` 从方法入口移到呼出分支内，说明"全局最近一次 XX"这类可变状态要收敛写入点、限定业务场景，否则迟早被无关路径污染。
