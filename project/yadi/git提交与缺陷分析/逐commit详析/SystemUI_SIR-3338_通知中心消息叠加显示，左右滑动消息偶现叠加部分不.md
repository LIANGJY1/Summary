# SIR-3338 · 折叠消息滑动时叠加部分不跟手（组合通知不支持滑动位移）

- **提交**：`64ddd337` | 2026-07-24 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~80%（库载必现-80%~100%） · 状态 关闭 · 域 主交互

## 问题
通知中心消息叠加（分组折叠）显示时，左右滑动消息偶现叠加部分不跟随滑动，界面出现"上半截走了、下面一层留在原地"的错位。

## 根因分析
`CarNotificationItemTouchListener` 实现了通知条目的手势滑动（跟随手指位移/滑删）。滑动位移只作用于**当前触摸的子视图**，而分组通知由 `GroupNotificationViewHolder` 管理的多个子条目叠放渲染——只滑最上面一张，下面被折叠的成员仍停在原位，视觉上"叠加部分不跟随"。滑动手势本就没有为组合通知设计整体位移，这属于能力缺失而非偶发异常（偶现感来自用户是否在折叠态上手势滑动）。缺陷库 sol"折叠消息禁止滑动"给出的修法是直接关掉该场景的手势，而非补齐整体位移。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationItemTouchListener.java
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationItemTouchListener.java
@@ onTouchEvent
             case MotionEvent.ACTION_OUTSIDE:
             case MotionEvent.ACTION_MOVE:
-                if (!hasValidGestureSwipeTarget()) {
+                if (!hasValidGestureSwipeTarget() || (mViewHolder instanceof GroupNotificationViewHolder)) {
                     break;
                 }
@@ ACTION_UP
                 case MotionEvent.ACTION_UP:
-                if (!hasValidGestureSwipeTarget()) {
+                if (!hasValidGestureSwipeTarget() || (mViewHolder instanceof GroupNotificationViewHolder)) {
                     onGestureEnd();
                     break;
                 }
```

## 为什么能修复
`ACTION_MOVE` 与 `ACTION_UP` 两处都加了 `mViewHolder instanceof GroupNotificationViewHolder` 短路：折叠消息从手势处理里整体退出，不再产生"单层位移"，叠加错位无从发生（功能上放弃了折叠消息的滑动手势，展开后仍可正常滑动，用户路径不受损）。MOVE 与 UP 成对修改保证状态一致，不会出现"动了一半抬起无响应"的悬挂态。隐患：若产品后续要求折叠消息也支持滑动删除，需要实现"组内所有子视图同步位移"，本提交是有意的功能裁剪。

## 复盘与经验
- 自定义 ItemTouch 手势要显式声明支持的 ViewHolder 类型；组合/分组视图不支持整体位移时，最安全的策略是禁用而不是放行半套手势。
- "偶现"滑动错位若只在特定视图形态（分组折叠）出现，问题其实必现——按形态拆分复现条件比按概率统计更有效。
- 手势处理里 MOVE/UP/UP-CANCEL 的分支必须成对修改，漏掉一个就会留下手势状态机不一致的次生 bug。
