# SIR-8019 · carlink断连后蓝牙音乐向dock推送异常媒体信息

- **提交**：`ee6b8b36` | 2026-09-09 | dufan | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
连接 carlink 后播放手车互联音乐，手机端断开连接后，dock 栏多媒体卡片显示异常（仍显示蓝牙音乐的残留/无效媒体信息）。

## 根因分析
`BtMusicModel`（object 单例）通过 `MediaBrowserCompat`/`MediaController` 回调监听蓝牙音乐源，在 `musicChange(metaData)` 中解析 `METADATA_KEY_TITLE`/`METADATA_KEY_ARTIST` 等字段，并用 `mIsNoMusicSource` 标记"当前无音乐源"状态；该标记在 `isNoMusicSource()` 中用于拦截播放/切歌并提示"无音源"，同时也决定了 dock 多媒体卡片是否应显示无音源状态。判定条件为：title 是 `"MUSIC_SOURCE_BT"`/`"Not Provided"`/空，且 artist 是空/`"MUSIC_SOURCE_BT"` 时才算无音源。手机互联（carlink）断开后，蓝牙媒体会话上报的元数据 artist 字段为 `"Unavailable"`，旧逻辑未覆盖该取值，导致 `mIsNoMusicSource` 保持 false，蓝牙音乐继续 `postValue` 更新 `mMusicTitle`/`mMusicArtist`/`mMetadata` 并向 dock 发送更新通知，卡片便显示了异常内容。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt（1 文件 +1/-1）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ object BtMusicModel · musicChange()
         mIsNoMusicSource =
             (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided") || TextUtils.isEmpty(title))
-                    && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT"))
+                    && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT") || TextUtils.equals(artist, "Unavailable"))
```

## 为什么能修复
把断连场景下蓝牙媒体会话实际上报的 `"Unavailable"` artist 归入"无音乐源"判定，`mIsNoMusicSource` 正确置 true 后，`isNoMusicSource()` 会拦截后续操作并提示，dock 卡片不再收到异常的媒体更新通知。改动只放宽了一个布尔判定条件，正常播放时 title/artist 为真实歌名，不受影响；风险在于若某些正常歌曲的 artist 恰为 "Unavailable" 会被误判为无音源，但该字符串是协议占位值，实际冲突概率极低。

## 复盘与经验
- 对外部媒体源（MediaSession 元数据）做"无内容"判定时，必须枚举协议中的全部占位字符串（"Not Provided"、"MUSIC_SOURCE_BT"、"Unavailable"），新增互联协议后要重新核对这些魔法值。
- 偶现/场景类显示异常，优先在数据入口处（musicChange）打点日志确认真实上报值，再补判定分支，而不是在 UI 层打补丁。
- 用单一布尔标记（mIsNoMusicSource）收敛"是否推送更新"的决策，修复时只改一处判定即可全局生效，是良好的状态收敛设计。
