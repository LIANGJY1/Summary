# SIR-6566 · 蓝牙音乐后台无播放，多媒体卡片默认文言显示错误

- **提交**：`90bd9f40` | 2026-08-27 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
蓝牙音乐退到后台、实际无音乐播放时，Dock 多媒体卡片仍收到蓝牙音乐的更新推送，默认占位文言显示错误（显示了蓝牙音乐的缺省内容而不是"无音源"）。

## 根因分析
`BtMusicModel` 用 `mIsNoMusicSource` 标记"蓝牙侧当前无真实音源"，`MediaForegroundService` 凭它决定是否 `startForeground` / `notificationManager.notify` 推送更新。但原判定条件不完整：`onMetadataChanged` 中只有 `descriptionChar` 为**空串**才认为无音源，某些手机端会话把 `descriptionChar` 也填成 `"MUSIC_SOURCE_BT"` 占位值，导致被误判为"有音源"；且 metadata 为 null 的分支没有置位逻辑，`mIsNoMusicSource` 保持上一次的 false。于是后台无播放时仍然发出更新通知（提交信息"发送了更新广播 → 不发送更新广播"），Dock 卡片被错误刷新。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt、application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ onMetadataChanged
                 mIsNoMusicSource =
-                    (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided")) && TextUtils.isEmpty(descriptionChar)
+                    (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided")) && (TextUtils.isEmpty(descriptionChar) || TextUtils.equals(descriptionChar, "MUSIC_SOURCE_BT"))
             } else {
+                mIsNoMusicSource = true
             }

--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ startForeground路径
-        if (!isForegroundStarted) {
+        if (!isForegroundStarted && !mBtMusicModel.mIsNoMusicSource) {
             try {
                 startForeground(notificationId, notification)

@@ onBluetoothConnectedChanged
         )
+        mBtMusicModel.mIsNoMusicSource = true
         mBTConnected.postValue(isConnected)
```
（updateNotification 中 `notify` 前已带 `&& !mBtMusicModel.mIsNoMusicSource` 门禁，本次为其补充了日志；另在 `updateMediaSessionState` 增加状态日志）

## 为什么能修复
三处合围把"无音源"判定补严：占位 descriptionChar 也识别为无音源、metadata 缺失直接置 true、蓝牙连接状态切换时先复位为无音源（等真实 metadata 证明有源）。于是后台无播放时 `updateNotification` 的 notify 门禁与 `startForeground` 门禁均不通过，不再向 Dock 推送更新，卡片保持系统默认"无音源"文言。隐患是占位串匹配依赖 `"MUSIC_SOURCE_BT"`/`"Not Provided"` 约定值，遇到新的手机端占位写法需再次扩充。

## 复盘与经验
- "默认态标志"必须在每个入口（含 null/异常分支）有确定赋值，只更新正常分支会让标志滞留旧值。
- 跨端约定的占位字符串（MUSIC_SOURCE_BT）是脆弱的魔法值，集中定义并覆盖所有字段组合才能稳定识别。
- UI 推送类修复优先收紧"源头发送条件"，比在接收端加过滤更干净（与 SIR-6290、SIR-6439 同一思路：Dock 状态乱/错多因应用侧多发更新）。
