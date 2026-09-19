# SIR-8117 · 头盔多媒体音量进度条多次调节后偶现拉不动

- **提交**：`0266e2db` | 2026-09-11 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
系统音量设置中调节"头盔多媒体音量"进度条，多次调节后偶现进度条拖不动，触摸拖拽无响应。

## 根因分析
自定义控件 `LimitedSeekBar`（`ToggleableLimitSeekBar`）实现了"拖动超过上限时钳制到 limit 并回调"的逻辑：首次超过上限（`hasTriggered = true; progress = limit`）后 `return true` 消费事件。原代码在钳制的同时调用了 `parent.requestDisallowInterceptTouchEvent(false)`，等于立刻告诉父容器"可以重新拦截触摸事件"。问题在于此时用户手指仍按在滑杆上（同一个手势序列尚未 UP），父容器（如可滚动列表）此后可随时把后续 MOVE 事件拦截走用于自身滚动，`LimitedSeekBar` 收不到 MOVE，表现为"拉不动"。是否被抢走取决于父容器拦截时机，因此呈偶现性，且"多次调节"（反复触发超限钳制）会放大触发概率。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/widget/LimitedSeekBar.kt`
```diff
                     hasTriggered = true
                     progress = limit
                     onFirstExceedLimit?.invoke(newProgress, true)
-                    parent.requestDisallowInterceptTouchEvent(false)
+                    parent.requestDisallowInterceptTouchEvent(true)
                     return true
                 } else {
                     onFirstExceedLimit?.invoke(newProgress, false)
```

## 为什么能修复
改为 `requestDisallowInterceptTouchEvent(true)` 后，在本次触摸手势期间持续禁止父容器拦截，MOVE 事件继续送达 `LimitedSeekBar`，滑杆在超限钳制后仍可响应拖动，直到手势结束（父容器在 UP 后自动恢复拦截权）。副作用极小：代价只是本次手势内父容器暂时不能滚动，而这正是拖滑杆时的期望行为。

## 复盘经验
- 自定义 View 在 `onTouchEvent` 中"消费事件但中途放行父容器拦截"是自相矛盾的：要么完整接管手势，要么别消费；`requestDisallowInterceptTouchEvent` 的生命周期是"一次手势"，不应在手势中途翻转为 false。
- "偶现、多次操作后出现"的触摸类问题，优先怀疑父子容器事件拦截竞争，复现路径要围绕触发特殊分支（超限、越界、反弹）构造。
- `return true` 消费 DOWN 之外，关键分支（钳制、动画启动）里也要保证事件流连续性。
