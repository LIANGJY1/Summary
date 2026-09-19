# SIR-6439 · 连接 carlink 后 Dock 媒体卡片无音源、点击不跳转

- **提交**：`d1325de8` | 2026-08-27 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交信息标注 D，以缺陷库为准）

## 问题
连接 carlink 之后，Dock 栏媒体卡片无音源显示，点击媒体卡片也不跳转到对应应用。

## 根因分析
carlink 侧手机投来的媒体通知在 Android 侧会自动生成一条**分组摘要通知**（`Notification.isGroupSummary()`）。该摘要继承了媒体通知的 channel 特征，`NotificationUtils.isMediaLikeNotification()` 把它也判定成"类媒体通知"，于是在 `CarNotificationListener` 的 onShow/onRemove 路径中，摘要通知同样触发 `mChangeListener.onShow(alertEntry)`，把 Dock 媒体卡片状态（`NavBarFragment` 的 `mCurrentMediaEntry`）覆盖成摘要。摘要通知没有真实的 media `contentIntent`/actions，导致点击无跳转；其 title 也为空，`updateMediaInfo` 里 `musicTittle.setText("")` 把卡片文字清空，看起来就是"无音源"。提交信息概括为"系统分组摘要覆盖真实媒体通知 → 屏蔽分组摘要"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java、application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
```diff
--- application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java
         AlertEntry alertEntry = new AlertEntry(sbn);
         if (NotificationUtils.isMediaLikeNotification(alertEntry.getNotification())) {
-            if (mChangeListener != null) {
+            // Android's auto-group summary inherits the media channel but has no real media
+            // contentIntent/actions. Do not let it overwrite the actual media notification.
+            if (!alertEntry.getNotification().isGroupSummary() && mChangeListener != null) {
                 mChangeListener.onShow(alertEntry);
             }
             return;
（onRemove 路径同样加了 !isGroupSummary() 过滤）

--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
-        musicTittle.setText(TextUtils.isEmpty(title) ? "" : title);
+        musicTittle.setText(TextUtils.isEmpty(title) ? getString(R.string.have_not_player) : title);
```
同文件还修正默认图标逻辑：判断包名常量由 `MUSIC_PACKAGE_NAME` 改为 `DOCK_MUSIC_PACKAGE_NAME`，且分支互换（命中在线音乐包名显示 `vector_default_online`，否则 `vector_default_bt`），修正了原先"在线源显示蓝牙图标、蓝牙源显示在线图标"的反转。

## 为什么能修复
分组摘要被排除在媒体通知通道之外后，真实媒体通知（带 contentIntent 与标题）成为 `mCurrentMediaEntry`，卡片有内容、点击可跳转；空标题兜底为"无播放器"文案，`have_not_player` 让缺省态有正确文言。onRemove 同步过滤避免"摘要先到先删"把真实通知从卡片上错误清掉。隐患是若某应用确实用 group summary 携带媒体内容，该通知将被忽略，但主流媒体应用均以子通知承载内容，风险可控。

## 复盘与经验
- Android 自动分组摘要通知是"影子通知"：isMediaLikeNotification 这类宽泛特征判断必须叠加 `isGroupSummary()` 排除，否则会被顶替。
- 媒体卡片这类"展示+跳转"复合控件，被占位数据覆盖时两个能力同时失效，排查时可从"点击 intent 为空"反推数据源被污染。
- 缺省态文案不应依赖空字符串展示，统一走默认资源（如 have_not_player）才能与设计稿对齐。
