# SIR-6509 · 蓝牙已连接，收藏/最近通话仍显示"去连接"控件

- **提交**：`0de7b83d` | 2026-08-27 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话（提交信息标注 D，以缺陷库为准）

## 问题
蓝牙已连接、联系人同步中，"收藏"与"最近通话"页面却仍显示蓝牙未连接的"去连接"控件。

## 根因分析
两个页面的 ViewModel（`CallLogViewModel` / `FavoritesViewModel`）在 `handleBluetoothStateChange` 中处理连接状态：断连时 `uiState.postValue(new BluetoothDisconnected())`。`postValue` 是异步投递主线程的，与后续 CONNECTED 事件之间存在乱序/覆盖窗口——若"断连态 postValue 尚未落地、CONNECTED 分支已执行完数据加载并 setValue 新状态"，之后投递的 `BluetoothDisconnected` 才到达并覆盖成未连接 UI，形成"明明已连接却显示去连接"的陈旧状态。而且旧代码 CONNECTED 分支完全没有纠正当前已处于 `BluetoothDisconnected` 的情况。修复两步：断连改 `setValue` 同步落值（该方法本就运行在主线程回调中），保证状态严格按事件顺序切换；CONNECTED 时若发现残留 `BluetoothDisconnected` 则立即复位为 `Empty("")`，等待同步数据到达。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/CallLogViewModel.java、application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/FavoritesViewModel.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/CallLogViewModel.java
             repository.clearCallLogs();
             // 显示蓝牙未连接状态
-            uiState.postValue(new CallLogUiState.BluetoothDisconnected());
+            uiState.setValue(new CallLogUiState.BluetoothDisconnected());
         } else if (connectionState == BluetoothConnectionState.CONNECTED) {
             clearCallLogsIfDeviceChanged();
+            if (uiState.getValue() instanceof CallLogUiState.BluetoothDisconnected) {
+                LogUtils.i(TAG, "Bluetooth reconnected, reset stale disconnected state");
+                uiState.setValue(new CallLogUiState.Empty(""));
+            }
         }
（FavoritesViewModel 同样两处修改）
```

## 为什么能修复
`setValue` 在主线程同步更新，消除 postValue 的异步乱序窗口，断连→连接的状态迁移不再被延迟投递的旧状态覆盖；CONNECTED 分支的兜底检查则把任何已残留的"未连接态"强制归位，页面随 PBAP 同步数据刷新为正常列表。两个 ViewModel 同步修改保持行为一致。注意点：`setValue` 要求主线程调用，此处在 LiveData source 回调内成立；若未来调用线程变化需重新评估。

## 复盘与经验
- 在主线程回调里更新 LiveData 时用 `setValue` 而非 `postValue`：postValue 的延迟合并语义会让"最后写入"不一定是"最新事件"，连接类状态机尤其敏感。
- 状态机要给"陈旧状态"留出口：进入新状态时检查并清理上一态的残留（如重连时复位 BluetoothDisconnected）。
- 复制粘贴的双页面 ViewModel（收藏/通话记录）修 bug 必须成对修改，否则同一单会复发另一半。
