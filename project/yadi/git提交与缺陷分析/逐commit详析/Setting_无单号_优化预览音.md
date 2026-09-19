# SRS_SYSSetting_008 · 优化预览音

- **提交**：`0a462e17` | 2026-08-31 | sgh | Setting | feature
- **关联单**：SRS_SYSSetting_008（标题引用，无系统单号）

## 需求/目标
音量调节的预览音（beep）不干扰关键场景：语音/导航/通话/来电铃声占用音频焦点时静默；音量页不可见（切换/隐藏）时立即停播且不再触发。

## 实现结构
- `VolumeViewModel.kt`（+60，核心）：
  - `mActiveFocusUsages`：从音频焦点回调缓存的当前 usage 集合；`mBeepBlockingUsages` 定义四类抑制源（ASSISTANT/导航/通话/铃声）。
  - `updateFocusUsages()` 供 Fragment 焦点回调调用；`isBeepSuppressed()` 在 `playBeepWithUsage` 入口拦截。
  - `setPageVisible(false)` 遍历 `mGroupStates`，在每组的播放互斥锁内 `stopPlayForGroupLocked` 并清 `isPlaying`。
- `VolumeFragment.kt`：`onCarFocusChanged` 回调先 `updateFocusUsages` 再走原停播逻辑；新增 `onHiddenChanged/onPause/onResume` 三态维护页面可见性。

数据流：CarAudioManager 焦点回调 → usage 集合缓存 → 播放入口拦截；页面生命周期 → visible 标志 → 停播 + 播放入口拦截。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/VolumeViewModel.kt
@@ -167,7 +190,44 @@
+    private val mBeepBlockingUsages = setOf(
+        AudioAttributes.USAGE_ASSISTANT,
+        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
+        AudioAttributes.USAGE_VOICE_COMMUNICATION,
+        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
+    )
+
+    fun updateFocusUsages(focusHolders: List<AudioFocusInfo>?) {
+        mActiveFocusUsages = focusHolders?.map { it.attributes.usage }?.toSet() ?: emptySet()
+    }
+
+    private fun isBeepSuppressed(groupId: Int): Boolean {
+        val suppressed = mActiveFocusUsages.any { it in mBeepBlockingUsages }
+        ...
+        return suppressed
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VolumeFragment.kt
@@ -414,6 +415,21 @@
+    override fun onHiddenChanged(hidden: Boolean) {
+        super.onHiddenChanged(hidden)
+        mViewModel.setPageVisible(!hidden)
+    }
+
+    override fun onPause() {
+        super.onPause()
+        mViewModel.setPageVisible(false)
+    }
```
```diff
@@ -76,6 +76,7 @@
         override fun onCarFocusChanged(
             audioZoneId: Int, focusHolders: List<AudioFocusInfo>?
         ) {
+            mViewModel.updateFocusUsages(focusHolders)
             mViewModel.setStopPlay(focusHolders)
         }
```

实现讲解：两道闸门构成完整的静音策略——入口闸（`playBeepWithUsage` 开头两个 if，直接 return）与出口闸（`setPageVisible(false)` 在每组 mutex 锁内逐组停播，保证与正在进行的播放串行化，不产生并发停/播竞态）。状态用 `@Volatile` 标记跨线程读写。

## 复盘与要点
- "预览音避让关键音频"是车机音量页的通用需求，本实现把抑制源声明为可配置集合（`mBeepBlockingUsages`），新增抑制场景只需加一个 usage。
- Fragment 用 `onHiddenChanged + onPause/onResume` 双通道同步可见性是 ViewPager+show/hide 混用场景的标准写法，注意漏掉 onHiddenChanged 会导致切页后预览音照播。
- 遗留风险：`isBeepSuppressed(groupId)` 参数未参与判断（只留了日志用途），签名与实现不一致，易误导调用方。
