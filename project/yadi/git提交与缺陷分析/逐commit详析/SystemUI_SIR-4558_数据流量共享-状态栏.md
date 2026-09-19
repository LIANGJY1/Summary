# SIR-4558 · 状态栏新增数据流量共享图标（feat，非 bugfix）

- **提交**：`0061319c` | 2026-08-10 | liujinfeng | SystemUI | **feature（提交标注 [feat]，缺陷库 mistag=true）**
- **缺陷库**：未关联缺陷（SIR-4558 为需求单，defs 为空）

## 问题与说明
本提交是**新功能**：手车互联开启"数据流量共享"后，状态栏显示共享网络图标（`vector_net_share`）替代原信号图标组，通过 Settings.Global 键 `device_share_network_is_open` 联动。无缺陷修复语义，按结构剖析。

## 实现结构
改动文件：`application/SystemUI/src/main/java/com/android/systemui/statusbar/icon/PhoneStatusBarPolicy.java`、`application/SystemUI/src/main/java/com/android/systemui/statusbar/ui/StatusBarFragment.java`、`application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/StatusBarGestureHelper.java`、`application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java`、`application/SystemUI/src/main/res/values/ids.xml`
```diff
// application/SystemUI/src/main/java/com/android/systemui/statusbar/ui/StatusBarFragment.java
+    private ImageView mIvNetShare;
...
+        mIvNetShare = createStatusIconView(R.id.iv_statusbar_share,
+                R.drawable.vector_net_share, true);
...
         mStatusIconContainer.addView(mIvDvr);
-        mStatusIconContainer.addView(mSignalContainer);
+        if (SettingsUtils.INSTANCE.getGSetting(SysUIConfig.DEVICE_SHARE_NETWORK_STATUS, 0) == 1) {
+            mStatusIconContainer.addView(mIvNetShare);
+        } else {
+            mStatusIconContainer.addView(mSignalContainer);
+        }
```
```diff
// application/SystemUI/src/main/java/com/android/systemui/statusbar/icon/PhoneStatusBarPolicy.java
+    private ContentObserver deviceShareNetworkObserver = new ContentObserver(mHandler) {
+        @Override public void onChange(boolean selfChange) { notifyShareNetworkIcon(); }
+    };
+    // 构造时注册：registerContentObserver(deviceShareNetworkStatusUri, false, deviceShareNetworkObserver)
+    // destroy 时 unregister，回调经 mFixedIconCallback.updateShareNetworkIcon() 触发 rebuildStatusIcons()
```
另有一处夹带改动：`StatusBarGestureHelper` 把状态栏下拉手势响应区从右半屏（`touchX >= viewWidth / 2f`）改为全屏（`touchX >= 0`），与数据共享需求无直接关系，属顺带调整。

## 设计要点与风险
- 开关监听采用 `ContentObserver` 观察 `Settings.Global`，变化后整体 `rebuildStatusIcons()` 重建图标容器，实现"共享图标 vs 信号图标"二选一；注册/反注册在 `PhoneStatusBarPolicy` 构造/destroy 成对出现，无泄漏。
- `rebuildStatusIcons()` 每次读取设置重建全部图标，逻辑简单但开关高频切换时会整排重建，好在车机场景频率极低。
- 风险点：`rebuildStatusIcons` 是二选一替换，共享开启期间真实 SIM 信号变化（`updateNetworkType`）更新的是 `mSignalContainer`（已从容器移除），关闭共享后重建容器显示的是旧信号态——依赖重建时机兜底，若信号在共享期间变化且关闭后无重建触发，会显示过期信号图标。
- 手势区扩为全屏属于交互行为变更，夹带在功能提交里未在提交信息说明，回归时容易遗漏。

## 复盘与经验
- 状态栏图标"替换式显示"（A 或 B 二选一）要考虑被隐藏一侧的状态在隐藏期间的变化，重建时机需要额外触发点。
- Settings.Global + ContentObserver 是跨进程开关同步的标准做法，配合注册/反注册成对书写即可无泄漏。
- 功能提交中夹带无关行为变更（手势区扩大）会稀释回归范围，应单独成提交或在提交信息中显式声明。
