# SIR-7151 · 手机端播放后自动暂停，车机端无法播放蓝牙音乐
- **提交**：`37c4d644` | 2026-09-03 | dufan | BTMusic | bugfix（日志调整为主，见"不符注记"）
- **缺陷库**：等级 A · 频次 必现-80%~100% · 状态 关闭 · 域 主交互
- **注**：**diff 与元数据明显不符**。本单标为 A 级"无法播放音乐/修改回调逻辑"，但实际 diff 只有两处日志调整与死代码清理，不含任何播放链路功能改动；同作者 17 分钟前的 `ae8d340b`（SIR-7197，同样的"回调不一样/修改回调逻辑"描述）才是重构 `mIsNoMusicSource` 判定逻辑的功能性修复。推测两单同根同源，功能修复落在 `ae8d340b`，本提交为其配套清理。

## 问题
手机连接车机蓝牙，手机端播放音乐后自动暂停，此后车机端无法播放蓝牙音乐。

## 根因分析
按元数据描述：手机音乐软件已开启的情况下蓝牙协议回调（AVRCP 元数据/播放状态）与未开启时不同，导致车机侧"无音源"标志 `BtMusicModel.mIsNoMusicSource` 被误置、播放被拦截。但**本提交 diff 并未改动该逻辑**——真正的机制修复见 `ae8d340b`：把音源判定唯一收敛到 `musicChange` 数据路径、移除 `MediaForegroundService.onBluetoothConnectedChanged` 与 `onMetadataChanged` 中对 `mIsNoMusicSource` 的误覆盖/误置真。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/service/BluetoothPlayerService.kt`

```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/BluetoothPlayerService.kt
         override fun onCommState(commandID: String, value: Int) {
-            if (commandID != "dsi_lux") {
-//                LogUtils.d(TAG, "onCommState: commandID = $commandID; value = $value")
-            }
             if (commandID == "Meter_Form") {
+                LogUtils.d(TAG, "onCommState: commandID = $commandID; value = $value")
                 when (value) {
```

## 为什么能修复
就本提交自身而言无功能修复效果：仅删除了仅含注释日志的空 `if (commandID != "dsi_lux")` 死代码块，并在 `Meter_Form` 分支内补充了一条 `onCommState` 日志。播放问题的实际修复依赖同批 `ae8d340b` 的回调逻辑重构；本提交的价值是让 `Meter_Form`（仪表形态切换，与"车机端无法播放"现象相关的联屏/仪表状态）链路可观测，便于后续定位。无副作用。

## 复盘与经验
- 同一缺陷的多笔提交可能分散在相邻 commit：只看单号和等级（本单 A 级）会误以为此提交含关键修复，必须以 diff 为准；本例是"功能修复合入 A、日志清理单独成 B"的典型。
- 清理"if 包一行注释日志"这类死代码值得鼓励——空分支会误导阅读者以为有条件逻辑。
- 对 A 级必现问题，若提交 diff 与单号描述不符，复盘时应沿同作者/同模块的邻近提交回溯真实修复点（本例 `ae8d340b`），避免把日志提交当经验沉淀。
