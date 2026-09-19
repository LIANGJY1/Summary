# SIR-8655 · 点击暂态通知按钮，通知闪现两次/动画回弹
- **提交**：`5cb64688` | 2026-09-17 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 待测试验证 · 域 主交互

## 问题
偶现点击暂态通知（Heads-Up 通知）的业务按钮时，通知退出动画完成后又闪回原位，看起来像通知出现了两次/动画回弹。

## 根因分析
`CarHeadsUpNotificationTopAnimationHelper.getAnimateOutAnimator()` 的退出动画（`AnimatorSet` = 位移动画 + 模糊动画）在 `onAnimationEnd` 里调用 `clearTransientState(view)`。该方法会重置视图的临时状态——包括把 `translationY`/`alpha` 等位置与透明度恢复到初始值。问题时序：退出动画播完 → `onAnimationEnd` 先把视图重置回原位 → Manager 随后才真正把 View 从窗口移除。两步之间存在间隙，且重置发生在退出终态之后，用户能看到通知"弹回"到屏幕上再消失一次。偶现性来自动画结束回调与移除操作的分线程/分帧时序差异。

## 关键代码修改
改动文件：CarHeadsUpNotificationTopAnimationHelper.java（2 行）
```diff
// application/SystemUI/src/main/java/com/android/systemui/notification/headsup/animationhelper/CarHeadsUpNotificationTopAnimationHelper.java
             public void onAnimationEnd(Animator animation) {
-                clearTransientState(view);
+                // 正常结束后由 Manager 立即移除 View，保留退出终态，避免移除前回弹到原位。
+                clearBlur(view);
             }
 
             @Override
             public void onAnimationCancel(Animator animation) {
                 clearTransientState(view);
             }
```

## 为什么能修复
退出动画正常结束时不再做全量 `clearTransientState`（位置+透明度+模糊全重置），只 `clearBlur(view)` 清理模糊渲染效果，视图保持退出动画的终态（已移出屏幕/透明），Manager 紧接着移除 View，中间不再有"回到原位"的画面。`onAnimationCancel` 分支保留 `clearTransientState`：被取消（例如重新进入）时才需要把视图恢复到可复用的初始状态。改动把"重置"限定在真正需要重置的场景，消除了终态回弹。副作用：若 Manager 在动画结束后的移除路径异常，视图将停留在退出终态而非原位——但该视图即将被移除，风险可控。

## 复盘与经验
- 动画 `onAnimationEnd` 是 onCancel 与正常结束共用的回调，二者需要的收尾状态往往不同：正常结束应保持终态，取消才需要复位，必须区分处理。
- "动画结束后闪回/回弹"类问题的标准排查思路：检查动画结束回调是否把属性重置回初值，以及移除 View 的时机是否晚于重置。
- 属性动画收尾只清理"非终态的辅助效果"（如 blur、clip），不要动决定终态的 transform 属性，可从根上避免时序竞态。
