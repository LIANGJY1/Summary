# SIR-5859 · 无音源时点击播放无提示
- **提交**：`5ec0a6f8` | 2026-08-18 | dufan | BTMusic | bugfix（补逻辑类）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
连接手机后无音源，在车机端点击播放（及上一曲/下一曲）没有任何提示，用户不知道为什么没有反应。

## 根因分析
缺陷库归因"未添加对应逻辑"。`BtMusicModel` 的 `setPlayAndPause()/setPrevious()/setNext()` 只检查 `mController` 是否为 null 就直接向手机媒体会话转发 `transportControls` 命令，完全没有"当前是否有音源"的判定；媒体元数据回调 `onMetadataChanged` 里也没有记录无音源状态。手机端未选音源时元数据表现为 `title == "MUSIC_SOURCE_BT"` 且描述为空，应用此前对这一组合不做任何识别，按键后即无声失败。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt`、`res/values/strings.xml`、`res/values-en/strings.xml`
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
+    var mIsNoMusicSource = true
     ...
                 val subtitle = description.subtitle
+                mIsNoMusicSource =
+                    TextUtils.equals(title, "MUSIC_SOURCE_BT") && TextUtils.isEmpty(descriptionChar)
         ...
             LogUtils.e(TAG, "setPlayAndPause mController is null")
             return
         }
+        if (isNoMusicSource()) {
+            LogUtils.e(TAG, "setPlayAndPause noMusicSource")
+            return
+        }
         ...
+    fun isNoMusicSource(): Boolean {
+        if (mIsNoMusicSource) {
+            ThreadUtils.runOnUiThread {
+                App.app?.let {
+                    ToastUtils.showCenterToast(
+                        context = it,
+                        message = it.getString(R.string.no_music_source_hint),
+                        outMinWidth = com.yadea.common.R.dimen.dp_580
+                    )
+                }
+            }
+            return true
+        }
+        return false
+    }
```
（新增文案 `no_music_source_hint`＝"当前无音源播放，需要在手机端选择音源播放"。`setPrevious()/setNext()` 同样加守卫。）

## 为什么能修复
`onMetadataChanged` 实时把"title 为 MUSIC_SOURCE_BT 且描述为空"识别为无音源并落状态；三个播放控制入口统一经 `isNoMusicSource()` 守卫：无音源时弹中文提示并拦截命令转发，用户得到明确反馈，也不再向手机发送无效控制。风险点：`isNoMusicSource()` 名为查询实际带弹 Toast 副作用，且 Toast 走 UI 线程 post，若未来在高频路径调用会连续弹窗；`mIsNoMusicSource` 初始 true 的默认值在首次元数据回调前点击也会提示，行为合理但依赖约定。

## 复盘与经验
- 跨设备控制（车机控手机媒体会话）必须考虑对端无资源时的反馈路径，"发了命令没反应"是最差体验，入口守卫 + 提示是标配。
- 无音源这类状态可从媒体元数据的约定字段（title/描述组合）推导，识别规则要与手机端协议约定一致并集中在一处。
- 查询函数里藏 UI 副作用（isNoMusicSource 弹 Toast）可读性差，应拆为纯判断 + 调用方提示，避免复用时误弹窗。
