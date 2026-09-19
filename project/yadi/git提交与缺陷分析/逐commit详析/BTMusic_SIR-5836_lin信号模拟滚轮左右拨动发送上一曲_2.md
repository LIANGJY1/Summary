# SIR-5836 · LIN 滚轮切歌一次跳两首（移除媒体会话重复路径）
- **提交**：`e036cb01` | 2026-08-18 | dufan | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
LIN 信号模拟滚轮左右拨动发送上一曲/下一曲时，一次拨动跳过两首歌。

## 根因分析
缺陷库根因"FWK 和应用都实现了切换"。BTMusic 内部存在两条切歌链路：链路一是应用自己监听 CAN 滚轮信号（`MusicCarService` 的 `ROLLER_UP_SWITCH/ROLLER_DOWN_SWITCH` → `handleUpSwitch/handleDownSwitch` LiveData → `MainActivity` 观察者调用 `setPrevious()/setNext()`）；链路二是 FWK 把滚轮事件转成媒体会话传输命令，回调到 `MediaForegroundService` 的 `onSkipToNext()/onSkipToPrevious()`，其内部同样调用 `mBtMusicModel.setNext()/setPrevious()`。一次滚轮拨动两条链路各执行一次切换，即跳两首。`9d0c2dd8`（8-14）曾尝试用 `mIsReady=false` 屏蔽应用侧车辆属性通道但收效有限，本提交把媒体会话链路真正移除。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt`
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
         override fun onSkipToNext() {
             LogUtils.d(tag, "onSkipToNext")
-            mBluetoothPlayerService.mBtMusicModel.setNext()
+//            mBluetoothPlayerService.mBtMusicModel.setNext()
         }

         override fun onSkipToPrevious() {
             LogUtils.d(tag, "onSkipToPrevious")
-            mBluetoothPlayerService.mBtMusicModel.setPrevious()
+//            mBluetoothPlayerService.mBtMusicModel.setPrevious()
         }
```

## 为什么能修复
媒体会话回调被置空后，FWK 下发的 skip 命令在应用侧不再触发第二次 `setNext()/setPrevious()`，切歌只剩 CAN 信号直控链路一次执行，"跳两首"消除。隐患明显：来自其他来源的媒体 skip 命令（如语音助手、方向盘媒体键经 FWK 分发）也无法再切歌，属功能性回退风险，需要确认 FWK 是唯一分发方；用注释而非删除代码保留回滚能力，但长期应显式移除并注明原因。

## 复盘与经验
- "同一硬件输入只允许一个消费端"：接入 LIN/_CAN 按键时必须先清点 FWK 是否已转发媒体命令，双端实现必然动作翻倍。
- 修复此类问题时，`9d0c2dd8`（改就绪标志）与本提交（直接摘除重复回调）对比说明：屏蔽要作用在真正的重复执行点上，否则无效。
- 注释式停用便于快速验证与回滚，但合入前应补 TODO 与原因说明，避免半年后无人敢删。
