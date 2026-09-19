# SIR-7197 · 已识别到音乐，点播放仍提示"未识别到音源"
- **提交**：`ae8d340b` | 2026-09-03 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 本地多媒体

## 问题
进入蓝牙音乐后已识别到手机音乐信息，点击播放键却提示"未识别到音源"，无法播放。典型场景：手机音乐软件已打开的情况下再打开车机蓝牙音乐。

## 根因分析
"未识别到音源"由标志位 `BtMusicModel.mIsNoMusicSource` 控制，旧逻辑对它的赋值分散且互相打架：
1. `onMetadataChanged` 回调里按 metadata 标题/描述判断（`MUSIC_SOURCE_BT`/`Not Provided` 置真），metadata 为空时直接 `mIsNoMusicSource = true`——而"手机软件先打开"的场景回调时序不同，可能先来一个空 metadata 或字段布局不同的 metadata，被误判为无音源；
2. `MediaForegroundService.onBluetoothConnectedChanged` 每次连接状态回调都无条件 `mIsNoMusicSource = true`，把之前判定好的有效音源状态覆盖掉；
3. 判断依据取自 `MediaMetadata` 的 title/description，而真正可靠的曲目信息在 `musicChange`（播放器实际下发的 title/artist）路径里。
低概率是因为依赖回调到达顺序——手机软件打开与否会改变 AVRCP 元数据回调的时序与内容。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt`（核心）、`.../manager/BluetoothController.kt`、`.../service/MediaForegroundService.kt`、`.../MainActivity.kt`、`.../service/BluetoothPlayerService.kt`

```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt (onMetadataChanged)
                 val mediaId = description.mediaId
                 val subtitle = description.subtitle
-                mIsNoMusicSource =
-                    (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided")) && (TextUtils.isEmpty(descriptionChar) || TextUtils.equals(descriptionChar, "MUSIC_SOURCE_BT"))
                 LogUtils.i(TAG, "onMetadataChanged =====> title: $title, ...")
-            } else {
-                mIsNoMusicSource = true
             }
```

```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt (musicChange)
-        
+        mIsNoMusicSource =
+            (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided")) && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT"))
         LogUtils.i(TAG, "musicChange title: $title,artist: $artist,...,mIsNoMusicSource:$mIsNoMusicSource")
```

```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
         LogUtils.i(TAG, "onBluetoothConnectedChanged isConnected=$isConnected ...")
-        mBtMusicModel.mIsNoMusicSource = true
         mBTConnected.postValue(isConnected)
```

配套：`BluetoothController` 在 `ACTION_CONNECTION_STATE_CHANGED` 时把 `mIsNoMusicSource` 复位为 true（新连接从"无音源"起点开始判定），并删除了与 A2DP 源侧重复的 `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` 处理分支。

## 为什么能修复
修复把"无音源"判定的唯一权威点收敛到 `musicChange`（真实曲目数据路径），按 title/artist 是否为占位值判定；`onMetadataChanged` 不再误判、不再对空 metadata 一票否决；连接回调也不再无条件覆盖为"无音源"，只在 A2DP 连接状态变化时重置等待新判定。时序无论怎样变化，最终状态都由最后一次真实曲目数据决定，与"手机软件是否先打开"无关。隐患：若某些手机从不触发 `musicChange` 而只回 metadata，判定点可能收不到数据，需依赖占位值兜底。

## 复盘与经验
- 同一布尔状态在多个回调里被多方写入（一处置真、一处覆盖、一处复位），时序一旦变化就出错——这是"回调不一样导致"类 bug 的本质。修复方向是收敛唯一写入点，而不是继续加标志。
- 蓝牙音乐音源判定应基于播放器实际下发的曲目数据（title/artist），而不是 AVRCP 元数据回调的中间态；对占位值（`MUSIC_SOURCE_BT`/`Not Provided`）要做白名单式识别。
- 复现概率低的蓝牙问题，先理清协议回调（AVRCP metadata、A2DP 状态、连接广播）的触发时序差异，"手机软件是否已打开"正是改变回调时序的典型变量。
