# SIR-7156 · 首次联网 NTP 校时后状态栏时间不刷新

- **提交**：`cbb6773d` | 2026-09-02 | caohongliang | SystemUI（JSON 模块标 BTPhone，diff 实际全在 SystemUI） | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互

## 问题
首次联网完成时间同步后，状态栏时间迟迟不走，要等约一分钟才跳变更新。

## 根因分析
状态栏时钟 `StatusBarFragment` 里的 `mTvClock`（TextClock）此前不做任何主动刷新，而是依赖 `NotificationCenterTextClock.SRefreshLiveData`：通知中心的 `NotificationCenterTextClock` 在 `setText` 时 `postValue`，状态栏 observe 到后才调 `mTvClock.refreshTime()`。这条链路有两个弱点：一是间接依赖另一个"隐藏视图"的绘制/文本变化；二是原生 `TextClock` 内部按分钟对齐的 handler 在 NTP 大幅校时后不会重新对齐（提交 [why] 原话），`ACTION_TIME_CHANGED` 后旧调度仍按旧时间轴计时，于是状态栏要等到下一个自然分钟 tick 才更新，表现为"未及时更新"。

## 关键代码修改
改动文件：StatusBarFragment.java、NotificationCenterTextClock.java
```diff
// application/SystemUI/src/main/java/com/android/systemui/statusbar/ui/StatusBarFragment.java
+    private void registerTimeReceiver(Context context) {
+        if (mTimeReceiverContext != null) {
+            return;
+        }
+        IntentFilter filter = new IntentFilter();
+        filter.addAction(Intent.ACTION_TIME_TICK);
+        filter.addAction(Intent.ACTION_TIME_CHANGED);
+        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
+        context.registerReceiver(mTimeReceiver, filter, null, mMainHandler);
+        mTimeReceiverContext = context;
+    }
```
```diff
// 同文件 onDestroyView：先注销再调 super
-        super.onDestroyView();
+        if (mTimeReceiverContext != null) {
+            mTimeReceiverContext.unregisterReceiver(mTimeReceiver);
+            mTimeReceiverContext = null;
+        }
+        mTvClock = null;
+        super.onDestroyView();
```
```diff
// application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/NotificationCenterTextClock.java
-    public static MutableLiveData<String> SRefreshLiveData = new MutableLiveData<>();
（并删除 setText 中的 SRefreshLiveData.postValue，解耦两处时钟）
```

## 为什么能修复
状态栏改为直接监听 `ACTION_TIME_TICK / ACTION_TIME_CHANGED / ACTION_TIMEZONE_CHANGED` 三个系统广播，收到即 `mTvClock.refreshTime()`。NTP 校时会发出 `ACTION_TIME_CHANGED`，时区变化也有对应广播，因此大幅校时后时钟立即重刷，不再依赖原生 TextClock 内部校准器自愈，也不再依赖通知中心时钟的间接信号。副作用：receiver 生命周期管理必须严格（已配对注销并置空 `mTvClock` 防泄漏）；`mTimeReceiverContext` 防重复注册。`TIME_TICK` 每分钟一次的常驻广播开销极小。

## 复盘与经验
- 不要让 A 视图的刷新依赖 B 视图"顺带"发出的信号（本例 LiveData 桥接两个时钟），刷新源应直接订阅系统事件。
- 原生 `TextClock` 对 NTP 大幅校时存在已知的不重对齐问题，车机（长期联网、频繁校时）必须自建 `TIME_CHANGED` 刷新兜底。
- `registerReceiver`/`unregisterReceiver` 与 `super.onDestroyView()` 的调用顺序：先清理再交还父类，避免父类销毁后再触碰子类状态。
