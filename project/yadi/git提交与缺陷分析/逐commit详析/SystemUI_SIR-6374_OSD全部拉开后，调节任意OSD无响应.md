# SIR-6374 · OSD 全部拉开后调节任意音量条无响应

- **提交**：`53737f52` | 2026-08-26 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
多根 OSD（音量调节浮层）全部拉开后，在任意音量条上拖动调节均无响应，音量不变化。

## 根因分析
`VolumeDialogActor` 用 `mMultiItemViews: MutableList<MultiBarItem>` 登记多根模式的每个音量条（`initMultiBarItems` 中逐个 `mMultiItemViews.add(...)`），触摸调节时通过 `mMultiItemViews.find { it.groupId == groupId }` 取条目再调 `handleMultiTouchVolume`。缺陷在于**视图重建时列表不清空**：OSD 窗口会经历多次 inflate/销毁（`unInit` 也未清理该列表），旧一轮 inflate 的 `MultiBarItem` 仍留在列表头部；`find` 命中的是第一个匹配 groupId 的**旧条目**，而它对应的旧 `barContainer` 已从窗口 detach，`height` 恒为 0。`handleMultiTouchVolume` 开头的防御 `if (containerHeight <= 0) return` 便直接返回——滑动调节被"合法性保护"吞掉，表现为全量无响应。这是一个"缓存列表生命周期与视图生命周期不一致 + find 取首条"叠加的状态残留缺陷，且旧条目让保护逻辑反而成了掩盖点。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt`

```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt
@@ -339,6 +339,10 @@ class VolumeDialogActor : BaseActor() {
 
     @SuppressLint("ClickableViewAccessibility")
     private fun initMultiBarItems() {
+        // 重建视图时清空旧 Item，避免 mMultiItemViews 中残留已 Detach 的条目，
+        // 否则 find { groupId } 会优先命中旧条目，导致其 barContainer.height == 0，
+        // 多根展开后滑动调节直接 return，表现为无响应。
+        mMultiItemViews.clear()
         val itemIds = listOf(
             R.id.volume_media to CarAudioVolumeController.GROUP_MEDIA,
             R.id.volume_vr to CarAudioVolumeController.GROUP_VOICE,
@@ -917,6 +921,7 @@ class VolumeDialogActor : BaseActor() {
     override fun unInit() {
         mVolumeHandler.removeCallbacksAndMessages(null)
         mVolumeExecutor.shutdownNow()
+        mMultiItemViews.clear()
     }
```

## 为什么能修复
在 `initMultiBarItems` 入口先 `mMultiItemViews.clear()`，保证每轮 inflate 后列表只含本轮真正 attach 的条目，`find { groupId }` 必然命中可见视图，`barContainer.height > 0`，滑动调节链路恢复；`unInit` 中补 `clear()` 则在销毁侧同步清场，双保险堵住"重建"与"销毁"两条残留路径。副作用：无——清理的只是失效缓存引用，`543`/`733` 行等遍历点也只应处理存活条目。可改进点：用 groupId→item 的 Map 并以 attach 状态为准，或 `find` 时过滤 `isAttachedToWindow`，比"约定先 clear"更防回归。

## 复盘与经验
- "可重建的视图 + 常驻的缓存列表"是状态残留高发组合：凡是 add-only 的视图登记列表，inflate 入口必须先 clear，销毁路径也要清。
- `MutableList.find` 取首条在存在重复 key 时语义脆弱；缓存应保证 key 唯一，或查找时附加有效性过滤（如 `isAttachedToWindow`）。
- 防御性早退（`height <= 0 return`）会让真问题静默化：保护分支里至少要打日志，否则"为什么无响应"只能靠猜。
- 源码中的注释（开发者自己写清了因果链）就是最好的复盘材料——修 bug 时把机制注释进代码，后来者不必再踩一遍。
