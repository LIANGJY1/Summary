# SIR-8541 · 蓝牙电话跑 monkey 时 SystemUI 手势空 MotionEvent 崩溃

- **提交**：`1abaf87e` | 2026-09-16 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 待测试验证 · 域 本地多媒体

## 问题
蓝牙电话场景跑 monkey 压测时，SystemUI 出现一处 crash（触摸事件异常）。

## 根因分析
`StatusBarPanelView`（状态栏下拉面板，实现 `View.OnTouchListener`）把所有触摸事件原样转给 `GestureDetector`：`mDetector?.onTouchEvent(event)`。monkey 压测会注入**残缺的触摸序列**——没有先 `ACTION_DOWN` 就来了 `ACTION_MOVE`/`ACTION_UP`。`GestureDetector` 内部 `onFling` 计算需要起始 Down 事件建立的 velocity tracker 上下文，缺 Down 时其内部状态为空（元数据所述"onFling 里面有空 MotionEvent 事件"），触发空指针崩溃。正常用户操作总是从 Down 开始，所以只有压测能稳定打出。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/StatusBarPanelView.kt
```diff
--- application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/StatusBarPanelView.kt
     override fun onTouchEvent(event: MotionEvent?): Boolean {
         if (event != null) {
-            mDetector?.onTouchEvent(event)
+            val action = event.actionMasked
+            if (action == MotionEvent.ACTION_DOWN) {
+                mHasGestureDownEvent = true
+            }
+
+            // GestureDetector 的 onFling 要求存在起始事件，忽略压测产生的残缺触摸序列。
+            if (mHasGestureDownEvent) {
+                mDetector?.onTouchEvent(event)
+            } else if (action == MotionEvent.ACTION_UP) {
+                LogUtils.w(TAG, "onTouchEvent ignored: missing down event")
+            }
+
+            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
+                mHasGestureDownEvent = false
+            }
         }
         return super.onTouchEvent(event)
     }
```

## 为什么能修复
新增 `mHasGestureDownEvent` 门控：只有在收到过 `ACTION_DOWN` 的手势序列内才把事件喂给 `GestureDetector`，序列结束（UP/CANCEL）即复位。残缺序列（无 Down 的 MOVE/UP）被拦截在 detector 之外，`onFling` 的空上下文崩溃路径被切断，同时留下 `LogUtils.w` 日志便于压测复盘。正常手势完全不受影响；隐患是若系统在某些场景真的先于 Down 注入事件（极罕见），手势灵敏度理论上有差异，但门控在 DOWN 即置位，实际无感。

## 复盘与经验
- monkey/压测必测项：自绘触摸面板把事件转发给 `GestureDetector` 前要校验序列完整性（有无 Down），这是系统 UI 崩溃的高频来源。
- `GestureDetector` 不是对任意事件序列都安全，防御性门控 + 告警日志是标准加固手法。
- crash 复盘要看注入源：正常交互无法复现的崩溃，先怀疑输入异常注入（monkey、红外屏干扰、多指乱序）。
