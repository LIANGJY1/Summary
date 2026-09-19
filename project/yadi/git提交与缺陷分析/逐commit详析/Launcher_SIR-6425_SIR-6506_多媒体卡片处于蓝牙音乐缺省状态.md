# SIR-6425 / SIR-6506 · 媒体卡片缺省态点击打开错误应用（无音源记忆）

- **提交**：`f39f18b3` | 2026-08-27 | caohongliang | Launcher/SystemUI | bugfix
- **缺陷库**：SIR-6425 等级 B · 必现-80%~100% · 关闭 · 域 3D车模；SIR-6506 等级 B · 必现-80%~100% · 关闭 · 域 主交互（提交信息均标注 D，以缺陷库为准）

## 问题
多媒体卡片处于蓝牙音乐缺省（占位）状态时点击，打开的却是网易云音乐；缺省态下按钮灰显状态也与实际音源不匹配。

## 根因分析
`NavBarFragment` 中点击媒体卡片的跳转目标由 `musicPkg` 决定，而 `clearMediaInfo()` 会把 `musicPkg` 重置回 `DOCK_MUSIC_PACKAGE_NAME`（网易云），且 `mCurrentMediaEntry` 清空后卡片没有"上一次音源是谁"的记忆——用户上次用蓝牙音乐听歌，媒体通知消失后点卡片，跳转的却是固定默认的网易云。同时缺省态按钮 `setNoMediaSourceState` 无条件把 alpha 压到 0.3（视觉禁用）但 `setEnabled(true)`，且不感知蓝牙/手机互联连接状态。修复引入**音源记忆**：新增 `MEDIA_SOURCE_NETEASE/BLUETOOTH/PHONE_LINK` 三态与 `LAST_MEDIA_SOURCE`（Settings.Global 键 `dock_last_media_source`），真实媒体通知到达时 `rememberMediaSource(pkg)` 持久化音源；缺省时 `updateRememberedMediaSourceState()` 按记忆恢复图标与跳转包名。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java、application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java、application/Launcher/src/main/java/com/yadea/launcher/Constants.java、application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt、application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
+    private void rememberMediaSource(String packageName) {
+        if (SysUIConfig.DOCK_MUSIC_PACKAGE_NAME.equals(packageName)) {
+            mLastMediaSource = MEDIA_SOURCE_NETEASE;
+        } else if (SysUIConfig.MUSIC_PACKAGE_NAME.equals(packageName)) {
+            mLastMediaSource = MEDIA_SOURCE_BLUETOOTH;
+        } else {
+            mLastMediaSource = MEDIA_SOURCE_PHONE_LINK;
+        }
+        SettingsUtils.INSTANCE.setGSetting(LAST_MEDIA_SOURCE, mLastMediaSource);
+    }
+
+    private void updateMediaControlState() {
+        boolean unavailable = (mLastMediaSource == MEDIA_SOURCE_BLUETOOTH
+                && !settingsControllerService.isBluetoothConnected())
+                || (mLastMediaSource == MEDIA_SOURCE_PHONE_LINK
+                && SettingsUtils.INSTANCE.getGSetting(SysUIConfig.PHONE_LINK_CONNECT_TYPE, 0) == 0);
+        if (mCurrentMediaEntry == null || unavailable) {
+            setNoMediaSourceState(unavailable, previousBtn, playBtn, next);
+        } ...
（点击分支新增：mLastMediaSource == MEDIA_SOURCE_PHONE_LINK 时跳 Launcher 的车联 Tab，
 extra 为 EXTRA_OPEN_CAR_CONNECT_TAB，AppListActivity.onNewIntent/onCreate 解析后 viewPager.setCurrentItem(1)）
```
配套：`DeviceConnectManager` 在 init 与连接回调中把 `PHONE_LINK_CONNECT_TYPE` 写入 Settings.Global（0=未连接）；`NavBarFragment` 注册蓝牙 ACL 连接/断开广播与 `PHONE_LINK_CONNECT_TYPE` ContentObserver，实时刷新缺省态；`onResume`/挡位变化时无媒体通知则按记忆刷新卡片。

## 为什么能修复
缺省卡片不再固定指向网易云：跳转包名、默认图标、按钮可用态全部由"上次真实音源"驱动，蓝牙缺省态点击进入蓝牙音乐，互联音源点击直达车联页。蓝牙/互联连接状态变化通过广播与 ContentObserver 实时同步，音源不可用时按钮置灰（alpha 0.3）、可用时恢复 1.0，消除"看着禁用却可点"与"该禁用却可用"两种错位。持久化到 Settings.Global 使重启/重启 Fragment 后记忆不丢。风险点：三源映射是硬编码包名判断，新增音源需扩展。

## 复盘与经验
- "缺省态"不是"回到出厂默认"，而是"回到最近一次的真实状态"：涉及用户选择（音源、输入法、主题等）的入口都应有记忆。
- 跨应用（SystemUI 跳 Launcher Tab）联动用 Settings.Global + 自定义 extra 传递，两端常量（PHONE_LINK_CONNECT_TYPE）需同步维护，注意双重定义的一致性。
- 控件"视觉禁用"（alpha）与"逻辑禁用"（setEnabled）分离是隐患来源，应封装成单一 API 同时设置。
