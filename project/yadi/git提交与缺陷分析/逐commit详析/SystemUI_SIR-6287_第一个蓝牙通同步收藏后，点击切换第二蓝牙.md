# SIR-6287 · 切换第二蓝牙后收藏显示第一台手机的收藏内容

- **提交**：`89d1aae5` | 2026-08-24 | liujinfeng | SystemUI/BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
第一台手机蓝牙同步收藏后，切换连接第二台蓝牙，收藏页显示的仍是第一台手机的收藏联系人。

## 根因分析
两条根因叠加（缺陷库："切换连接之后，缓存数据未及时清理"；提交 how："切换设备时清理缓存，使用缓存时进行设备比对"）：
① `FavoritesRepository` 的查询兜底逻辑在指定 `ACCOUNT_NAME=macAddress` 查不到数据时，会做一次**不限制账号**的全局查询 `STARRED=1`——全局查询把通讯录里所有账号（包括上一台手机同步留下的账号）标星联系人全部捞出来，于是第二台设备的收藏页直接展示了第一台设备的收藏，这正是"串数据"的直接来源；
② 通话记录/联系人/收藏三条数据链路（`CallLogRepository`、`ContactsRepository`、`FavoritesViewModel.globalFavoriteList`）都不记录"数据属于哪台设备"，切机时也不清缓存，旧设备的 LiveData/内存列表原样存活，新设备连接后未同步完成前 UI 继续展示旧数据；Fragment 收到 `Empty` 状态时也未清空 adapter（`clearAdapterData` 是空方法）。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/ 下 CallLogFragment.java、FavoritesFragment.java、repository/CallLogRepository.java、repository/ContactsRepository.java、telecom/repositories/FavoritesRepository.java、viewmodel/CallLogViewModel.java、viewmodel/ContactsViewModel.java、viewmodel/FavoritesViewModel.java（8 文件，+130/-79）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/repositories/FavoritesRepository.java
@@ 删除跨账号兜底查询（串数据直接来源）
-                    // 【新增】如果仍然没有数据，尝试全局查询STARRED=1的联系人（不限制ACCOUNT_NAME）
-                    if ((mCursor == null || mCursor.getCount() == 0)) {
-                        mCursor = mContext.getContentResolver().query(
-                            ...Phone.CONTENT_URI, projection,
-                            Phone.STARRED + "=?", new String[]{"1"}, orderBy);
-                        if (setEmitter(emitter, globalStarredCount, macAddress)) return;
-                    }
                     setCursor(emitter, mCursor);
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/FavoritesViewModel.java
@@ 设备切换检测
+        } else if (connectionState == BluetoothConnectionState.CONNECTED) {
+            clearFavoritesIfDeviceChanged();
+    private void clearFavoritesIfDeviceChanged() {
+        String currentDeviceMac = bluetoothManager.getCurrentConnectedDeviceMac();
+        if (TextUtils.isEmpty(currentDeviceMac) || TextUtils.isEmpty(favoriteDataDeviceMac)
+                || TextUtils.equals(currentDeviceMac, favoriteDataDeviceMac)) {
+            return;
+        }
+        clearFavorites();
+        uiState.setValue(new FavoritesUiState.Empty(""));
+    }
@@ 记录数据归属设备
         globalFavoriteList.clear();
         globalFavoriteList.addAll(favorites);
+        favoriteDataDeviceMac = bluetoothManager.getCurrentConnectedDeviceMac();
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/CallLogViewModel.java
@@ 消费侧丢弃过期设备数据
+                if (!TextUtils.isEmpty(currentDeviceMac) && !TextUtils.isEmpty(dataDeviceMac)
+                        && !TextUtils.equals(currentDeviceMac, dataDeviceMac)) {
+                    repository.clearCallLogs();
+                    uiState.setValue(new CallLogUiState.Empty(""));
+                    return;
+                }
```
（`CallLogRepository`/`ContactsRepository` 新增 `callLogDataDeviceMac`/`contactsDataDeviceMac` 字段，在 `syncCallLogData`/`syncContactData` 时记录当前连接 MAC，`clearCallLogs`/`cleanData` 等清缓存方法同步清空 MAC；两个 Fragment 在断连/Empty 时补齐 adapter 清空调用。）

## 为什么能修复
三层防御同时落地：源头删掉跨账号全局兜底查询，收藏只可能来自当前设备账号；数据侧为每条缓存记录"归属 MAC"，设备切换（CONNECTED 且 MAC 不一致）立即清空缓存并置 Empty；消费侧 LiveData 到达时再校验归属 MAC，过期数据被丢弃。即使某一层被绕过，下一层仍能兜住。隐患：MAC 记录使用内存变量，进程被杀后缓存持久化标记（`KEY_IS_SYNCED`）与内存 MAC 的一致性需要重启路径再验证；三套 Repository/ViewModel 各自复制了同样的设备比对逻辑，仍属重复代码。

## 复盘与经验
- 多设备共享一套存储（通讯录 Provider 按 ACCOUNT_NAME 区分）时，查询绝不能放宽账号条件做"全局兜底"，否则必然串数据——兜底逻辑绕过的恰恰是数据隔离边界。
- 缓存数据应携带"来源标识"（设备 MAC），消费前校验来源是防串台的标准模式（tag-then-verify）。
- 设备切换是蓝牙应用必须显式处理的生命周期事件，断连/新连/切机三个时点都要有清缓存动作。
- 诊断用调试代码（`setEmitter` 全量打日志）在完成使命后要及时删除，它们常常本身携带行为副作用。
