# SIR-2886 · 蓝牙电话通讯录同步中切 tab 闪退
- **提交**：`edb60772` | 2026-07-20 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 A · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙通讯录同步进行时切换 tab，应用概率性闪退。

## 根因分析
缺陷库根因"通讯录同步未完成时切换 tab，fragment 销毁后，同步的数据继续在刷新"。`ContactsFragment` 通过 RxBus 接收同步进度事件并调用 `progressBarShow(progress)`/`updateSyncProgress()` 直接操作 `binding`；切 tab 后 ViewPager 适配器销毁 fragment，`binding` 已置空/视图已 detach，而迟到的进度事件继续触达观察者，访问 `binding.tvSyncProgress` 或 `getString()` 抛出 IllegalStateException/NPE。同样的问题存在于 `progressBarShow` 内部的 `binding.tvSyncProgress.postDelayed` 回调——延迟执行时 fragment 可能已销毁。此外 `BluetoothManager.isPbapAuthorized` 优先读 LiveData 缓存值，缓存过期导致状态判断失真，与真实授权状态脱节。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java`、`fragment/ContactsFragment.java`、`manager/BluetoothManager.java`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java
                 Integer progress = (Integer) event.getData();
                 if (progress != null) {
+                    if (!isAdded() || binding == null) {
+                        LogUtils.w(mTAG, "Fragment not attached or binding is null, ignore progress event: " + progress + "%");
+                        return;
+                    }
                     ...
                     updateSyncProgress(progress);
                 }
-        });
+        }, throwable -> LogUtils.e(mTAG, "RxBus error: " + throwable.getMessage()));
```

```diff
--- 同文件 progressBarShow 及两处 postDelayed 回调，均加同样守卫
     private void progressBarShow(int progress) {
+        if (binding == null || !isAdded()) {
+            LogUtils.w(mTAG, "progressBarShow: Fragment not attached or binding is null, skip");
+            return;
+        }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/manager/BluetoothManager.java (isPbapAuthorized)
-        Boolean cached = pbapAuthorized.getValue();
-        if (cached != null) {
-            return cached;
-        }
+        pbapAuthorized.postValue(authorized);   // 以实时检查结果为准并刷新缓存
         return authorized;
```

配套：MainActivity 各处 `startSync(..., true)` 改为 `startSync(..., false)`，去掉同步完成向通话记录页的反向通知（避免循环触发）。

## 为什么能修复
核心守卫 `binding == null || !isAdded()` 在每次触碰视图前校验 fragment 生命周期，销毁后迟到事件直接丢弃，消除崩溃路径；postDelayed 回调同样补守卫，堵住延迟执行窗口；`pbapAuthorized.postValue(authorized)` 保证 LiveData 缓存与实时状态一致，减少基于陈旧授权状态的错误分支。隐患：守卫是"丢弃事件"策略，若 fragment 重建后需要最新进度，要靠 onResume 重新拉取，本提交未体现兜底刷新。

## 复盘与经验
- Fragment 中所有异步回调（RxBus/LiveData/postDelayed）触达点都必须有 `isAdded()/binding==null` 守卫，这不是防御式编程的花架子，而是 ViewPager+异步同步场景的崩溃主源。
- 用 LiveData 当"缓存+状态"双职责时，读缓存的优先级高于实时检查会让过期值长期存在；要么以实时为准回写缓存，要么给缓存加时效。
- 双向通知（A 同步完通知 B、B 又可能触发 A）要用参数显式切断环路（本例 `notifyCallLog=false`），否则时序叠加会造成重复触发。
