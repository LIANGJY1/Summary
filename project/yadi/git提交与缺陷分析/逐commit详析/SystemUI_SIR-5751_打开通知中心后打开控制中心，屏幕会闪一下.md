# SIR-5751 · 打开通知中心后切换控制中心屏幕闪一下
- **提交**：`658d0f44` | 2026-08-11 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
通知中心展开状态下切换到控制中心（反向同理），切换瞬间屏幕闪一下。

## 根因分析
`PanelHostFragment` 原页面切换流程是严格串行的两阶段动画：先 `PanelAnimHelper.animateExit(fromContentRoot)`（子 View 透明度渐隐到 0），完成后再 `animateEnter(toContentRoot)`（子 View 从 alpha=0 渐入）。缺陷库归因"两个页面的动画叠加效果"：切换期间旧页子 View 全部透明、新页子 View 尚未开始渐入，中间存在一帧"两个页面内容都近乎全透明"，只剩背景高斯模糊层暴露，视觉上就是闪屏。此外 `animateEnter/animateExit` 的 `onAnimationCancel` 未从 `runningAnimators` 移除自身、完成计数以 `totalGroups`（组数）而非 `children.size`（子 View 数）为准，连续切换时旧动画取消/新动画启动的状态可能错乱，加剧闪烁。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/panel/ui/PanelHostFragment.kt`、`dropdownbar/panel/anim/PanelAnimHelper.kt`
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/panel/ui/PanelHostFragment.kt
+        // Cross-fade the page containers while child views keep their original movement/scale
+        // animations. This avoids a fully transparent frame that exposes only the blur layer.
         fromView.apply {
             visibility = View.VISIBLE
+            alpha = 1f
         }
         toView.apply {
-            visibility = View.INVISIBLE
+            visibility = View.VISIBLE
             translationY = 0f
-            alpha = 1f
+            alpha = 0f
         }
+        fromView.animate().alpha(0f).setDuration(PAGE_EXIT_FADE_DURATION).start()
+        toView.animate().alpha(1f).setDuration(PAGE_ENTER_FADE_DURATION).start()
         ...
             PanelAnimHelper.animateExit(
                 fromContentRoot,
                 onEnd = onPhaseCompleted,
+                preserveOpacity = true,
+                cancelExisting = false
             )
         ...
             PanelAnimHelper.animateEnter(
                 toContentRoot,
                 onEnd = onPhaseCompleted,
+                preserveOpacity = true,
+                cancelExisting = false
             )
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/panel/anim/PanelAnimHelper.kt
     fun animateEnter(
         parent: ViewGroup,
         onEnd: (() -> Unit)? = null,
+        preserveOpacity: Boolean = false,
+        cancelExisting: Boolean = true
     ) {
         ...
-                child.alpha = 0f
+                child.alpha = if (preserveOpacity) 1f else 0f
                 ...
                 val set = AnimatorSet().apply {
-                    val anims = mutableListOf<Animator>(alphaAnim, transYAnim, scaleXAnim, scaleYAnim)
+                    val anims = mutableListOf<Animator>(transYAnim, scaleXAnim, scaleYAnim)
+                    if (!preserveOpacity) {
+                        anims.add(alphaAnim)
+                    }
```
（同时修复：完成计数 `totalGroups`→`totalChildren`；`onAnimationEnd/Cancel` 中 `runningAnimators.remove(animation)`。）

## 为什么能修复
页面级容器（fromView/toView）自己做 cross-fade（333ms 出场淡出 / 500ms 进场淡入），保证任意时刻至少有一个页面容器可见；子 View 级动画改为 `preserveOpacity=true` 只做位移/缩放、不动 alpha，消除了"双层同时透明"的中间帧。`cancelExisting=false` 避免切换动画互相取消造成状态抖动，计数修复保证 `onEnd`/`finishSwitch` 恰好在全部子 View 完成后触发一次。隐藏面板路径保留 `preserveOpacity=false` 的淡出，整体视觉行为不变。副作用：切页动画更"平"，子 View 不再逐个渐显，属于可接受的视觉取舍。

## 复盘与经验
- 两层动画（容器 + 子 View）都用透明度时极易产生"全透明中间帧"，页面切换的标准做法是：容器层 cross-fade 保底可见，内容层只做位移/缩放等不透明动效。
- 动画完成计数必须与真实并发单元（子 View 数）一致；`onAnimationCancel` 不清理动画集合会泄漏 `runningAnimators` 并影响后续 cancelAll 行为。
- 串行动画（先出后进）视觉空窗是常见闪屏来源，能用并行动画就不要串行等待。
