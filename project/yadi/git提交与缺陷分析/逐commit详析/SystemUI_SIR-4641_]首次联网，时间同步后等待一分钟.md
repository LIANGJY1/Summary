# SIR-4641 · 首次联网时间同步后状态栏时间未及时更新
- **提交**：`1a196d19` | 2026-07-31 | dufan | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
首次联网完成时间同步后，通知中心的时间立即跳变，但状态栏时间要等约一分钟（下一分钟的整分 tick）才更新，两处时间不同步。

## 根因分析
通知中心时间用自定义 `NotificationCenterTextClock`（继承 `TextClock`），系统时间同步（`TIME_SET`/`TIMEZONE_CHANGED` 广播）会触发 TextClock 重新格式化文本、走其覆写的 `setText`/富文本处理路径，因此通知中心即时刷新。而状态栏时间 `tvTime` 是 `fragment_status_bar.xml` 里一个普通 `TextClock`（`format24Hour="HH:mm"`），它按自身节拍（每分钟的 ACTION_TIME_TICK）重绘；时间同步落在两次 tick 之间时，状态栏要等到下一个整分才重新格式化，形成最长约 1 分钟的滞后。缺陷库结论与此一致："状态栏时间刷新慢"，方案"通知界面刷新就同步状态栏"——即借通知中心时钟的刷新时机联动状态栏。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/NotificationCenterTextClock.java`、`application/SystemUI/src/main/java/com/android/systemui/statusbar/ui/StatusBarFragment.java`
```diff
--- application/SystemUI/.../statusbar/widget/NotificationCenterTextClock.java
+import androidx.lifecycle.MutableLiveData;
+    public static MutableLiveData<String> SRefreshLiveData = new MutableLiveData<>();
     // 富文本处理入口（文本变化即系统时间已更新）
+        SRefreshLiveData.postValue(text.toString());
         applyStyledText(text);
--- application/SystemUI/.../statusbar/ui/StatusBarFragment.java
-    private TextView ... (相关 import 调整)
+    private TextClock mTvClock;
     private void initView(View view) {
         rootView = view.findViewById(R.id.status_bar);
+        mTvClock = view.findViewById(R.id.tvTime);
+        NotificationCenterTextClock.SRefreshLiveData.observe(getViewLifecycleOwner(), time -> {
+            mTvClock.refreshTime();
+        });
         initStatusIcons();
```

## 为什么能修复
`NotificationCenterTextClock` 在文本变化（即时间同步生效）时向静态 `SRefreshLiveData` post 值；`StatusBarFragment` 订阅后立即调用 `mTvClock.refreshTime()` 强制状态栏 TextClock 重新取当前时间渲染，消除对下一整分 tick 的等待，两处时钟同步。隐患：用**静态** LiveData 做跨组件通信，若存在多个 StatusBarFragment 实例或通知中心时钟未创建，联动会失效或重复触发；`refreshTime()` 为隐藏 API（`@SuppressLint("NewApi")` 规避 lint），系统升级存在兼容风险。

## 复盘经验
- 同屏多个时钟/时间显示组件应共享同一数据源与刷新事件，而不是各自依赖系统 tick，否则时间同步（NTP/手动改时间）后必然出现不一致窗口。
- `TextClock.refreshTime()` 是修复时间同步滞后的正确工具，但它是 hidden API，车机固件可控可用，移植到通用 Android 需谨慎。
- 用静态 LiveData 当事件总线是快速但脆弱的方案：生命周期泄漏、多实例重复订阅都是隐患，量产代码建议收敛到统一的时间源仓库（repository +观察者）。
