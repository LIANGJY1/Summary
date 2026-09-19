# SIR-7636 · 未加载歌曲时，蓝牙音乐界面点击暂停无toast提示
- **提交**：`bbbda2a8` | 2026-09-07 | dufan | BTMusic | bugfix（cherry-pick 自 a871179a）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车机蓝牙音乐未加载到歌曲（无音源）时，点击暂停/播放等按键没有"未识别到音源"之类的 toast 提示。

## 根因分析
`BtMusicModel.kt` 中无音源标志 `mIsNoMusicSource` 由歌曲元数据推断，控制 `setPlayAndPause()`/`setNext()` 等入口里 `if (isNoMusicSource()) return`（内部弹 `music_no_source` 类 toast）的拦截是否生效。原判定：`mIsNoMusicSource = (title=="MUSIC_SOURCE_BT" || title=="Not Provided") && (artist 为空 || artist=="MUSIC_SOURCE_BT")`。问题在于手机蓝牙协议栈对"无曲目"的呈现并不统一——除了下发占位串 `MUSIC_SOURCE_BT`/`Not Provided` 外，还会直接下发**空 title**；此时左括号条件为 false，`mIsNoMusicSource` 被置 false，`isNoMusicSource()` 不成立，点击暂停既无歌曲可暂停也没有任何提示，用户操作无反馈。修复在 title 条件中补上 `TextUtils.isEmpty(title)`：空标题与两个占位串同等对待，只要 artist 也为空/占位即判定无音源。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
         mIsNoMusicSource =
-            (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided")) && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT"))
+            (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided") || TextUtils.isEmpty(title))
+                    && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT"))
```

## 为什么能修复
把"空 title"纳入无音源特征后，未加载歌曲场景 `mIsNoMusicSource=true`，按键入口统一走 `isNoMusicSource()` 的 toast 分支，操作有反馈。风险：若某些手机播放器确实以空 title + 有 artist 的形式播正常歌曲，不会误判（artist 条件仍要求为空/占位）；但若存在 title、artist 全空的正常音源（极罕见，如纯数据流），会被判无音源而拒绝操作，属可接受的边界取舍。

## 复盘与经验
- 依据上游枚举值做状态推断时，必须把"空值"当作一等公民纳入判定——蓝牙 AVRCP 元数据各手机栈实现差异大，空串占位非常常见。
- "点击无任何反馈"是可用性硬伤：入口处对非法/无意义操作统一给出 toast 提示，比逐个排查"为什么没反应"更值得优先建设。
- 同文件先前的 `isCanClick()` 修"误拦截"（SIR-7383），本次修"漏拦截"（SIR-7636），说明拦截型闸门的正确性要同时防两类错误，测试用例应成对设计。
