# SIR-8383 · 切换日夜模式多媒体卡片先变缺省再恢复
- **提交**：`7cffcdba` | 2026-09-14 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
播放网易云音乐时切换白天/黑夜模式，Dock 栏多媒体卡片先闪成"缺省（暂无播放）"状态，随后才恢复音乐信息。

## 根因分析
`SystemUIApplication` 检测到 uiMode 变化后调 `NavBarActor.recreateForUiModeChange()`，其原实现是**整只替换 Fragment**：`new NavBarFragment()` + `FragmentHostManager` 事务 `replace(R.id.nav_bar, ...)`。新 Fragment 的媒体控件全部回到 XML 默认值，而媒体信息由 `CarNotificationListener` 的 `onShow` 推送、只在"有新通知事件"时才来——重建后没有任何重放机制，卡片必然空窗，直到下一次媒体通知（或进度更新）触发才恢复，于是表现为"先缺省后恢复"。本质：状态展示组件的重建丢失了会话型状态（当前媒体 Entry），而该状态的持有方（NotificationListener）没有提供"当前值查询/重放"能力。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt；.../navbar/actor/NavBarActor.kt；.../navbar/ui/NavBarFragment.java；.../notification/CarNotificationListener.java
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/navbar/actor/NavBarActor.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/navbar/actor/NavBarActor.kt
@@ -    fun recreateForUiModeChange() {
-        if (!isShow()) return
-        mNavBarFragment = NavBarFragment()
-        FragmentHostManager.get(getDisplayView())
-            .fragmentManager.beginTransaction()
-            .replace(R.id.nav_bar, mNavBarFragment, TAG)
-            .commitNowAllowingStateLoss()
-    }
+    fun refreshForUiModeChange() {
+        if (::mNavBarFragment.isInitialized) {
+            LogUtils.d(TAG, "refreshForUiModeChange")
+            mNavBarFragment.refreshForUiModeChange()
+        }
+    }
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java
+++ b/application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java
@@ +    private AlertEntry mCurrentMediaEntry;
@@ (onShow 分支)
             if (!alertEntry.getNotification().isGroupSummary()) {
+                mCurrentMediaEntry = alertEntry;
                 if (mChangeListener != null) {
                     mChangeListener.onShow(alertEntry);
                 }
@@ (onRemove 分支：key 相等时 mCurrentMediaEntry = null)
@@     public void setNotificationListener(IMediaNotificationListener listener) {
         this.mChangeListener = listener;
+        if (listener != null && mCurrentMediaEntry != null) {
+            Log.d(TAG, "setNotificationListener: restore current media key="
+                    + mCurrentMediaEntry.getKey());
+            listener.onShow(mCurrentMediaEntry);
+        }
     }
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
+++ b/application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
+    public void refreshForUiModeChange() {
+        ...
+        mIvMap.setImageResource(R.drawable.btn_map_sel);   // 逐个重设按钮图标
+        mSeekBar.setProgressDrawable(requireContext().getDrawable(R.drawable.seekbar_progress));
+        mDockBackgroundDrawable = Objects.requireNonNull(
+                requireContext().getDrawable(R.drawable.vector_docker_bg)).mutate();
+        if (mCurrentMediaEntry != null) {
+            updatePlayPauseButton(mCurrentMediaEntry.getNotification().actions);
+            updateMediaAvatar(mCurrentMediaEntry.getNotification());
+        } else {
+            playBtn.setImageResource(R.drawable.vector_music_play);
+            updateRememberedMediaSourceState();
+        }
+        ... // 按 displayState 恢复 Dock 背景
+    }
```
（另：`NavBarFragment.onShow` 中若已在主线程则同步执行 `bindMediaActions`/`updateMediaInfo`，避免重建恢复路径先画一帧默认态。）

## 为什么能修复
双保险消除空窗：主路径把"销毁重建"降级为"原地 refresh"——Fragment 实例与 `mCurrentMediaEntry` 不再丢失，只重刷主题相关 drawable/颜色/背景，媒体卡片天然无闪断；兜底路径 `CarNotificationListener` 新增 `mCurrentMediaEntry` 缓存（show 写入、remove 按 key 清除），并在 `setNotificationListener` 注册时立即重放缓存 Entry，即使 Fragment 因其他原因重建也能瞬时恢复。副作用：refresh 需与未来新增控件保持同步（新增图标要记得加进 refresh 列表），否则该控件日夜切换后不更新。

## 复盘与经验
- "状态闪缺省再恢复"是典型的重建丢状态问题，优先把"重建"改造成"原地刷新资源"；无法避免重建时，数据源要提供当前值重放（setListener 时回放缓存）。
- 事件驱动的 UI 状态（通知推送型）必须额外维护"最新值"快照，否则任意时刻的组件重建都会造成状态真空。
- 主题切换刷资源要覆盖所有动态设置的 drawable/颜色，建议集中成单一方法并作为新增控件的标准登记点。
