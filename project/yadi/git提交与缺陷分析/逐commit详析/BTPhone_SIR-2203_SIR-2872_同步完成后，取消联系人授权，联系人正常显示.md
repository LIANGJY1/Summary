# SIR-2872 / SIR-2203 · 取消联系人授权后收藏与最近通话不显示，重授权也不恢复
- **提交**：`e8aae2df` | 2026-07-17 | hedeyuan | BTPhone | bugfix
- **缺陷库**：等级 B（两单同源）· 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
PBAP 同步完成后取消联系人授权，联系人页正常但收藏、最近通话不显示；手机重新授予权限后收藏/最近通话依然不显示。

## 根因分析
缺陷库根因"currentState 状态未更新"。`FavoritesFragment` 的 `setupViewModel` 中 `viewModel.getUiState().observe(...)` 只用 state 渲染 UI，没有把最新状态回存到成员 `currentState`；而 `onResume` 的所有分支判断（是否跳过 PBAP 重检、是否显示等待授权 UI）全部基于过期的 `currentState`。取消授权时 `CallLogViewModel`/收藏页停留在 `AwaitingPhoneConfirmation`/`Loading`，重授权后 `handlePbapAuthorizationChange` 里旧的 `if (authorized) { startSyncCallLogs(); performRealSyncWithTimeout(); }` 被包在某个状态分支内未执行，状态机没有从等待态迁移出来，onResume 又把 `Empty` 当"自动加载完成"直接渲染空 UI，形成"重授权也不恢复"的死锁。另外 `ContactsRepository` 在 PBAP 下载失败时不 postValue，ViewModel 的 observer 无从得知失败，`isWaitingForAuthorization` 永远不清除。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java`、`fragment/CallLogFragment.java`、`fragment/FavoritesFragment.java`、`repository/ContactsRepository.java`、`viewmodel/CallLogViewModel.java`、`res/layout/activity_main.xml`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/FavoritesFragment.java
         viewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
+            this.currentState = state;   // 【核心】观察 UiState 变化时同步 currentState
             if (!isFragmentVisible()) { ... }
```

```diff
--- 同文件 onResume：Empty 状态不再视为"加载完成"，改为触发同步
             if (currentState instanceof FavoritesUiState.Empty) {
-                LogUtils.d(mTAG, "onResume: Empty state (auto-load completed), show empty UI, do NOT trigger sync");
-                showStateLayout();
+                LogUtils.i(mTAG, "onResume: Empty state detected, trigger sync to load data");
+                startSync(true, false, true);
                 return;
             }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/repository/ContactsRepository.java (PBAP 下载失败分支)
+            // 下载失败时,推送空列表触发ViewModel observer,使其能从AwaitingPhoneConfirmation/Loading状态切换到SyncRequired
+            // 否则ViewModel的isWaitingForAuthorization标志未清除,UI会一直卡在等待授权或同步中状态
+            mContacts.postValue(new ArrayList<>());
```

配套：`CallLogViewModel` 授权变为 true 时无条件 `performRealSyncWithTimeout()`；MainActivity 从 ViewPager1 迁移到 ViewPager2（`setOffscreenPageLimit(2)` 预加载联系人/收藏页）。

## 为什么能修复
三层解卡：① observer 回写 `currentState`，onResume 的分支判断基于真实最新状态（缺陷库方案"观察 UiState 状态变化，重新给 currentState 赋值"）；② Empty 态在授权正常时主动 `startSync`，重授权后数据能拉回来；③ PBAP 失败也 postValue 空列表，让状态机有事件可消费、迁移到 `SyncRequired`，等待标志得以清除。副作用：`CallLogViewModel` 构造器用 `ThreadUtils.runOnUiThreadDelayed(..., 1500)` 延迟绑定仓库观察者，属于用延时掩盖初始化时序问题的做法，慢设备上 1.5s 内的状态变化仍可能丢失。

## 复盘与经验
- 缓存式状态成员（`currentState`）若只在 onResume 读取而不随 LiveData 回调更新，必然产生决策用旧数据——要么删缓存每次现读，要么在唯一数据源回调里同步缓存。
- 异步状态机卡死的常见根因是"失败路径不发事件"：下载失败/取消授权等终态也必须 postValue，让等待标志有解除时机。
- 授权被用户撤销再恢复，是蓝牙电话应用必测的往返场景：授权态、同步态、UI 态三者要在重授权时全部能回到初始链路。
