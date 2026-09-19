# SIR-7075 · 联系人同步到 100% 后又显示同步 0%
- **提交**：`fdd89716` | 2026-09-01 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
联系人同步进度到 100% 后，界面又跳回同步 0% 重新开始。

## 根因分析
`InCallServiceImpl` 的 PBAP 协议监听是全局广播式回调，不带"请求方"信息。当其它进程（元数据：其它进程同时进行联系人获取）也发起 PBAP listing/下载时，蓝牙电话本应用在一次下载 `DOWNLOAD_COMPLETED` 之后，协议监听又会收到新一轮 `ACTION_LISTING` / `DOWNLOAD_STARTED` 回调。原代码对这些回调不做事务归属判断：listing 回调直接重算 `totalContacts` 并重置下载状态、进度重新从 0 起算，形成"100% → 0%"的回跳。相关状态字段 `mPbapContacts` 还会在清空时被置 `null`，后续回调触发 `mPbapContacts.clear()` 有 NPE 风险。

## 关键代码修改
改动文件：`application/BTPhone/.../telecom/InCallServiceImpl.java`（主）、`.../repository/ContactsRepository.java`、`.../viewmodel/ContactsViewModel.java`、`.../fragment/ContactsFragment.java`、`.../Constants.java`
```diff
--- .../telecom/InCallServiceImpl.java
+    /** 联系人数量查询已发起，仅允许消费一次 listing 结果。 */
+    private volatile boolean mContactsListingPending;
+    /** 当前是否有本应用发起的完整联系人下载。 */
+    private volatile boolean mContactsDownloadInProgress;
@@ ACTION_LISTING 回调（PB_PATH 分支）
+                        // 广播不带请求方信息，只消费当前同步事务的第一个结果。
+                        if (!mContactsListingPending) {
+                            LogUtils.i(TAG, "Ignore unsolicited or duplicate contacts listing result");
+                            return;
+                        }
+                        mContactsListingPending = false;
@@ DOWNLOAD_STARTED / DOWNLOAD_COMPLETED / DOWNLOAD_FAILED（PB_PATH 分支）
+                            if (!mContactsDownloadInProgress) {
+                                LogUtils.i(TAG, "Ignore contacts download state outside current sync transaction");
+                                return;
+                            }
@@ downloadContacts()
+        mContactsDownloadInProgress = true;
         boolean pullPhonebookResult = mBluetoothPbapClient.pullPhonebook(...);
         if(!pullPhonebookResult){
+            mContactsDownloadInProgress = false;
@@ DOWNLOAD_COMPLETED 收尾
+                            mContactsDownloadInProgress = false;

--- .../repository/ContactsRepository.java
-    private List<BluetoothPbapContact> mPbapContacts = null;
+    private List<BluetoothPbapContact> mPbapContacts = new ArrayList<>();
（清空时不再 `mPbapContacts = null`，只 clear）
```
配套：静默授权校验改为先监听结果再发起（`checkCachedContactsAuthorizationSilently()` 返回 pending、新增 `CONTACTS_SILENT_CHECK_COMPLETE` 事件与 `handleSilentContactsCheckComplete()`），校验期间缓存延迟展示；进度上限从 95 调为 99、分母为 0 时跳过进度上报。

## 为什么能修复
核心是给 PBAP 同步加了"事务归属"判定：只有本应用发起（`downloadContacts()` 前置 `mContactsDownloadInProgress = true`）的事务才允许消费 listing/下载/逐条回调，其它进程触发的重复回调一律忽略，进度不再被外部回调重置；完成或失败时复位标志，下一轮同步可正常开启。`mPbapContacts` 初始化为空集合消除清空-置 null 的 NPE 隐患。副作用：若本应用发起后回调因异常缺失，标志可能滞留 true，代码在 `ACTION_DISCONNECT` 等路径统一复位（`mContactsListingPending/mContactsDownloadInProgress = false`）做了兜底。

## 复盘与经验
- 车机里 PBAP/A2DP 等协议广播是全局的、无请求方标识，多进程竞争时必须用"发起标志 + 单次消费 + 断连复位"把回调圈进自己的事务。
- "进度回跳"类问题先怀疑重复回调把分母/状态重置，而不是进度计算本身。
- 集合成员变量在清空时置 null 是 NPE 温床，保持"空集合不可变引用 + clear"更安全。
- 一个 bugfix 里同时强化了静默授权校验时序（先订阅后发起），属于典型的"修 A 顺带堵住 B"，但提交体积大、评审成本高，宜拆分。
