# SIR-6815 · 两路通话时第二路联系人名称错乱
- **提交**：`8c6a4c84` | 2026-08-31 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙连接下先后拨打/接听两路电话时，第二路通话界面显示的是第一路通话的联系人姓名（名称与号码错配）。

## 根因分析
通话找不到联系人时的"兜底显示"数据来自 `UiCallManager` 两个独立静态变量：`displayName`（通过 `updateLastDisplayName(displayName)` 保存）和 `lastCallPhone`（通过 `setLastCallPhone(number)` 保存，另服务于"最后一次呼出"补全）。两者在不同代码路径、不同时机写入，天然不保证成对。`DataExchangeCenter.updateCallContainerFromTelecom` 的兜底逻辑用 `getDisplayName()` 取名字、却用 `getLastCallPhone()` 取号码，再用 `PhoneNumberUtils.compare(number, lastCallNumber)` 决定是否套用兜底姓名。两路通话时，第二路的 `number` 可能与"最后呼出号码"匹配（同号码重呼/并发更新），而 `displayName` 仍是第一路的缓存，姓名与号码的判定基准来自两个时间点的快照，导致张冠李戴。

## 关键代码修改
改动文件：UiCallManager.java、DataExchangeCenter.java
```diff
--- a/.../telecom/telecom/UiCallManager.java
     private static String displayName = "";
+    private static String displayNumber = "";
 
-    private static void updateLastDisplayName(String name) {
+    private static void updateLastDisplayInfo(String number, String name) {
+        displayNumber = number;
         displayName = name;
     }
...
+    public static String getDisplayNumber() {
+        return displayNumber;
+    }
@@ 拨号处
                 setLastCallPhone(number);
-                updateLastDisplayName(displayName);
+                updateLastDisplayInfo(number, displayName);
```
```diff
--- a/.../telecom/dataexchange/DataExchangeCenter.java
@@ 兜底姓名判定
-                    String lastCallNumber = UiCallManager.get().getLastCallPhone();
+                    String lastCallNumber = UiCallManager.getDisplayNumber();
                     if (Constants.isCallInitiatedByCar()
                             && !TextUtils.isEmpty(lastCallName)
                             && PhoneNumberUtils.compare(number, lastCallNumber)
```

## 为什么能修复
号码与姓名合并进同一个 `updateLastDisplayInfo(number, name)` 原子写入，兜底判定改读与之配对的 `getDisplayNumber()`，"姓名-号码"永远来自同一次拨号的快照，比较基准一致后第二路通话不再套用第一路的姓名。原 `getLastCallPhone()` 继续专职"最后呼出号码补全"，职责分离。隐患：静态变量无并发保护，若两路拨号写操作严格并发仍可能交错，但车机拨号串行化（3s 间隔限制）下风险极低。

## 复盘与经验
- "成对使用的数据必须成对保存"：姓名+号码这类组合键拆成两个独立缓存变量，各自更新时机不同，迟早错配——封装为一个 update 调用是根治手段。
- 兜底/降级逻辑的判定基准要与数据来源严格同源，跨变量拼条件等于引入隐式时序依赖。
- 低概率（10%~40%）通话类缺陷常源于状态缓存的复用，两路/多路场景是必测用例。
