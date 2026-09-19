# SIR-6018 · 无后台音乐播放器时多媒体卡片暂停态下一曲图标高亮

- **提交**：`4590190e` | 2026-08-25 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
手机连接车机蓝牙，后台无音乐播放器时，dock 栏多媒体卡片显示为暂停态，"下一曲"图标高亮（可用），UI 状态错误。

## 根因分析
`MediaForegroundService` 负责把蓝牙媒体状态构建成通知（`notificationManager.notify(notificationId, buildNotification(mPlaying, ...))`）推给系统，dock 栏媒体卡片消费该通知渲染播放/暂停按键与可用态。原更新条件只判断连接态：`mBTConnected.value == true && mA2dpIsConnect.value == true`——只要蓝牙和 A2DP 连着，哪怕当前**无音乐源**（手机端没有任何播放器提供播放队列），也会发出一次带默认播放状态的更新通知。缺陷库根因"发送了更新广播"（此处广播即通知更新通道）：无源状态下推送的暂停+可播状态让 dock 卡片呈现"暂停、下一曲高亮"的假可用 UI。修复在条件上追加 `!mBtMusicModel.mIsNoMusicSource`，无音源时不再发送更新通知。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt（+1/-1）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ 通知更新条件
-        if (mBTConnected.value == true && mA2dpIsConnect.value == true) {
+        if (mBTConnected.value == true && mA2dpIsConnect.value == true && !mBtMusicModel.mIsNoMusicSource) {
             notificationManager.notify(
                 notificationId, buildNotification(mPlaying, currentTitle, currentArtist)
             )
```

## 为什么能修复
无音源时更新通知被拦下，dock 栏不再收到"暂停+可播"的假状态推送，卡片维持无源缺省表现（配合 `1edeea1c` 的缺省图修复与 SystemUI 侧 `d40342a3` 的无源点击提示，三条链路共同收敛出一致的无源 UI）。隐患：拦截是"不发"而非"发空态"，若之前已发过一次状态通知，需依赖其他清空路径重置卡片，极端时序下最后一次错误通知可能残留；`mIsNoMusicSource` 的维护准确性成为该闸门的正确性前提。

## 复盘与经验
- 状态推送方要在源头校验"这个状态是否真实有效"（有无音源），否则消费端会渲染出按钮可用但实际无队列的假 UI。
- 同一"无音源"问题在 dock 卡片上分三处收口（本提交拦通知、`1edeea1c` 修缺省封面、`d40342a3` 加点击提示），说明多团队/多模块消费同一状态时需要统一的无源状态契约。
- 布尔条件里每追加一个维度都应有注释说明业务含义（蓝牙连着≠有音乐可播）。
