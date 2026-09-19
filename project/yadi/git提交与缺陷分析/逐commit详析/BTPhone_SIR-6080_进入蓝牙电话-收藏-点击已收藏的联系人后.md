# SIR-6080 · 点击已收藏联系人拨号后自动退回主界面

- **提交**：`a05f3294` | 2026-08-21 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
进入蓝牙电话-收藏页，点击已收藏的联系人发起拨号，应用自动退出回主界面，拨号失败。

## 根因分析
缺陷库根因："蓝牙HFP的默认PhoneAccount失效"。拨号走 `TelecomForward` 的 `mTelecomManager.placeCall(uri, null)`，extras 为 null 时 Telecom 框架使用"用户选中的默认外呼 PhoneAccount"。而 HFP 场景下 PhoneAccount 由蓝牙进程的 `HfpClientConnectionService` 按连接的设备动态注册/注销（account id 即设备地址），设备重连、切换或多设备场景下默认账户可能指向一个已失效/非当前设备的账户，`placeCall` 找不到有效账户直接抛 `SecurityException/IllegalStateException`，上层未捕获导致页面退出。旧 `selectUserPhoneAccount(address)` 还按传入地址逐个比对 `getCallCapablePhoneAccounts()` 设置默认账户，但同样依赖账户列表的时机且不校验账户是否为 HFP 类型。修复：新增 `getCurrentHfpAccountHandle()`——按当前主设备地址匹配组件包名为 `com.android.bluetooth`、类名含 `HfpClientConnectionService` 的账户；拨号时主动 `extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, hfpAccountHandle)` 指定账户调用 `placeCall`，找不到才退回 `placeCall(uri, null)`。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/telecom/UiBluetoothMonitor.java、dataexchange/TelecomForward.java（2 文件，+49/-11）
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/telecom/dataexchange/TelecomForward.java @@
         Uri uri = Uri.fromParts("tel", number, null);
-        mTelecomManager.placeCall(uri, null);
+        PhoneAccountHandle hfpAccountHandle = UiBluetoothMonitor.get().getCurrentHfpAccountHandle();
+        if (hfpAccountHandle != null) {
+            Bundle extras = new Bundle();
+            extras.putParcelable(
+                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
+                    hfpAccountHandle
+            );
+            mTelecomManager.placeCall(uri, extras);
+        } else {
+            mTelecomManager.placeCall(uri, null);
+        }
```
```diff
@@ UiBluetoothMonitor.java 新增 HFP 账户精确匹配 @@
+            boolean isHfpAccount = component != null
+                    && "com.android.bluetooth".equals(component.getPackageName())
+                    && component.getClassName().contains("HfpClientConnectionService");
+            if (isHfpAccount
+                    && address.equalsIgnoreCase(handle.getId())) {
+                return handle;
+            }
```

## 为什么能修复
拨号不再依赖"默认账户"这个易失效的全局状态，每次外呼实时解析当前设备的 HFP 账户并显式指定，账户失效时 Telecom 不会因默认账户悬空而异常；匹配条件同时校验账户类型（HfpClient）与设备地址，避免误选其它协议账户。隐患：找不到 HFP 账户时仍走 `placeCall(uri, null)` 兜底，该路径失败仍会抛异常，上层最好再补一层 try-catch 与用户提示。

## 复盘与经验
- 依赖框架"默认选中项"的隐式状态是脆弱的：HFP PhoneAccount 随蓝牙连接动态增删，关键动作（拨号）应显式指定账户。
- PhoneAccount 的匹配不能只比 id，要连 ComponentName 类型一起校验，多协议（HFP/CS/虚拟账户）环境下才不会选错。
- "点击后应用退出"多半是未捕获异常——先查 crash 日志再查业务路径，本例即是 placeCall 抛异常穿透。
