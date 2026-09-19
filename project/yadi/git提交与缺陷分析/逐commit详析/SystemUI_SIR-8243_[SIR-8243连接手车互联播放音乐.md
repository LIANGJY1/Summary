# SIR-8243 · 重复点击多媒体卡片多次进入手车互联动效
- **提交**：`9e2dc850` | 2026-09-14 | caohongliang | SystemUI/Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
连接手车互联播放音乐后断开，在 Launcher 应用列表的手车互联 Tab 上反复点击 SystemUI 多媒体卡片，每次都重新播放"进入手车互联"的转场动效，页面被重复拉起。

## 根因分析
`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java` 的 `handleMusicCardClick` 只判断了驾驶触摸锁（drive touch lock）就无条件执行"退 Linux 表单回桌面 → 收起负一屏 → 拉起手车互联页"的整套流程，没有任何"目标页已在前台"的前置判断。而此时前台其实是 Launcher 的 `AppListActivity` 切到了手车互联 Tab，SystemUI 侧却无法感知 AppList 内部的 Tab 状态——`SysUIConfig.lastTopPackage` 只能判断 Launcher 在前台，应用列表页会被误判为"不在手车互联页"，于是每次点击都重发动效。跨应用（Launcher→SystemUI）的页面内状态缺失是根因。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/Constants.java；application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt；application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java；application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
+++ b/application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
@@ -106,6 +108,13 @@
         tabLayout?.setupWithViewPager(viewPager)
+        viewPager?.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
+            override fun onPageSelected(position: Int) {
+                updateCurrentTab(position)
+            }
+        })
+        // 向 SystemUI 同步当前页面，用于媒体卡片点击时避免重复拉起手车互联页
+        updateCurrentTab(viewPager?.currentItem ?: 0)
@@ -128,11 +137,16 @@
+    private fun updateCurrentTab(position: Int) {
+        val updated = SettingsUtils.setGSetting(APP_LIST_CURRENT_TAB, position)
+        LogUtils.d(TAG, "updateCurrentTab position=$position, updated=$updated")
+    }
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
+++ b/application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
@@ -478,6 +478,11 @@
         }
+        if (musicPending == null && mLastMediaSource == MEDIA_SOURCE_PHONE_LINK
+                && isCarConnectPageForeground()) {
+            LogUtils.d(TAG, "handleMusicCardClick skipped: car connect page already foreground");
+            return;
+        }
         exitLinuxFormToDriveHome();
@@ -500,6 +505,22 @@
+    private boolean isCarConnectPageForeground() {
+        int currentTab = SettingsUtils.INSTANCE.getGSetting(SysUIConfig.APP_LIST_CURRENT_TAB, 0);
+        boolean isForeground = TextUtils.equals(
+                SysUIConfig.lastTopPackage, SysUIConfig.LAUNCHER_PACKAGE_NAME)
+                && TextUtils.equals(
+                SysUIConfig.lastTopActivity, SysUIConfig.LAUNCHER_APP_CLASS_NAME)
+                && currentTab == SysUIConfig.APP_LIST_TAB_CAR_CONNECT;
+        LogUtils.d(TAG, "isCarConnectPageForeground topPackage=" + SysUIConfig.lastTopPackage
+                + ", topActivity=" + SysUIConfig.lastTopActivity
+                + ", currentTab=" + currentTab + ", result=" + isForeground);
+        return isForeground;
+    }
```

## 为什么能修复
Launcher 侧把 `AppListActivity` 的 `viewPager` 当前 Tab 实时写入 `SettingsUtils` 全局存储（key `launcher_app_list_current_tab`），SystemUI 点击媒体卡片时三重校验：媒体源是手车互联（`MEDIA_SOURCE_PHONE_LINK`）+ Launcher `AppListActivity` 在前台（`lastTopPackage`/`lastTopActivity`）+ 全局 Tab 值为 `APP_LIST_TAB_CAR_CONNECT`，命中即跳过拉起，动效不再重复播放。跳过前还判断了 `musicPending == null`，避免吞掉尚待处理的点击。隐患：跨进程经 Settings.Global 传递 Tab 状态存在微小窗口不一致（切 Tab 后立刻点击），但概率极低且有完整日志可查。

## 复盘与经验
- "重复拉起/重复动效"类问题的通用解法：拉起动作前加"目标是否已在前台"守卫，缺的不是逻辑而是状态守卫。
- 跨应用需要感知对方"页面内部状态"（Tab、子页面）时，用全局设置/广播等共享存储同步，不能只靠包名+Activity 名判断。
- 守卫条件要足够窄（前台 Activity + Tab + 媒体源同时满足才拦截），否则会误伤正常跳转入口。
