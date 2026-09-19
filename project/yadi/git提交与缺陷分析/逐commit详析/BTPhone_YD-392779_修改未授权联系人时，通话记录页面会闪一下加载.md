# YD-392779 · 修改未授权联系人时通话记录页面闪烁加载
- **提交**：`642347c0` | 2026-07-09 | duanlonglong | BTPhone | bugfix
- **缺陷库**：未关联单号（提交标题带 YD-392779，defs 为空）

## 问题
用户未授权同步联系人时，进入收藏/最近通话页面会先闪现"空列表加载图"，随后才显示错误占位图；预期直接显示错误图。

## 根因分析
`CallLogFragment.onViewCreated()` 里检测到 `CallLogUiState.SyncRequired` 且 `UtilsKt.isAutoSyncContacts()` 为 true 时，会无条件调用 `startSync(true)` 触发通话记录同步。但在未授权联系人访问（PBAP 未授权）的情况下，同步注定失败：先发起同步让 UI 进入"加载中"状态渲染出空列表/加载图，同步失败后再回落到错误图，形成"闪一下加载"的视觉跳变。根因是同步动作只判断了"自动同步开关"，没有校验"PBAP 协议是否已授权连接"这一前置条件。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogFragment.java（+14/-7）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogFragment.java
@@ onViewCreated
             if (currentState instanceof CallLogUiState.SyncRequired && UtilsKt.isAutoSyncContacts()) {
                 LogUtils.i(TAG, " onViewCreated: Detected unsynced state, trigger coordinated sync");
-                startSync(true); // 通知通讯录页面一起同步
+                BluetoothManager bluetoothManager = BluetoothManager.getInstance(requireContext());
+                if(isPbapConnected(bluetoothManager)){
+                    LogUtils.i(TAG, "PBAP协议已连接");
+                    startSync(true); // 通知通讯录页面一起同步
+                }else {
+                    LogUtils.i(TAG, "PBAP协议未连接，无法同步");
+                }
             }
         }
     }
+    private static boolean isPbapConnected(BluetoothManager bluetoothManager) {
+        return bluetoothManager != null && bluetoothManager.isPbapAuthorized(PB_PATH);
+    }
```

## 为什么能修复
在触发同步前新增 `isPbapAuthorized(PB_PATH)` 前置校验：未授权时直接跳过 `startSync(true)`，UI 保持在错误态不再进入"加载中"，闪烁消除。副作用很小——已授权路径行为不变；但若授权发生在页面驻留之后，本次进入不会自动补同步，需依赖后续状态回调再触发。

## 复盘与经验
- **"能做"不等于"该做"**：自动同步类动作除业务开关外，还要校验底层协议/权限的前置可用性，否则注定失败的操作会污染 UI 状态。
- **UI 闪烁多为"先进入中间态再回落"**：加载态→错误态的跳变，靠阻止无意义的状态迁移解决，比加动画掩盖更正确。
- **前置校验下沉为可复用谓词**：把 `isPbapConnected` 抽成静态方法，语义清晰且便于多处复用与测试。
