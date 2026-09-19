# SIR-3235 · 无音乐播放时DOCK栏蓝牙音乐仍显示播放状态（异常媒体数据未清理通知）

- **提交**：`aa220bfe` | 2026-07-23 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 本地多媒体

## 问题
偶发场景：没有播放任何音乐时，DOCK 栏的蓝牙音乐入口仍显示"播放中"状态。

## 根因分析
`MediaForegroundService.kt` 在收到媒体数据更新时构建通知：代码里本就有"数据无效则跳过更新"的分支（日志 `"Skipping notification update - no valid data"`），但跳过的方式是什么都不做——蓝牙协议（AVRCP 元数据）推来异常/空数据时，**上一首/上一次状态遗留的通知与播放状态保持原样**，系统媒体通知与 MediaSession 里的过期信息继续把 DOCK 栏渲染成"播放中"。缺陷库 rc"异常数据导致/兼容处理"与 diff 一致：所谓兼容处理就是"无效数据时也要把状态刷成非播放"，而不是维持旧值。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ 通知更新分支
                     updateNotifyData()
                 } else {
                     LogUtils.d(tag, "Skipping notification update - no valid data")
+                    updateNotification(false, "", "")
                 }
```

## 为什么能修复
数据无效分支从"跳过"改为"以 isPlaying=false、空歌名/歌手调用 `updateNotification(false, "", "")`"，把通知与媒体状态显式重置为非播放态，DOCK 栏随之不再读到遗留的播放状态。改动 1 行、指向明确。隐患：若 `updateNotification` 的空串参数会让通知显示空白条目，还需要下游配合隐藏通知；异常数据到达的时序若在服务销毁后，仍可能漏刷。

## 复盘与经验
- "数据无效就跳过更新"是伪防御：无效数据也是一种需要渲染的状态（停止/清空），跳过等于把旧状态当成了新状态。
- 蓝牙音乐这类外部数据源驱动 UI 的场景，必须为"空/异常数据"设计显式的归零路径。
- 偶现 + 状态残留类问题，优先找"哪个分支什么都没做"——什么都不做的分支往往是 bug 藏身处。
