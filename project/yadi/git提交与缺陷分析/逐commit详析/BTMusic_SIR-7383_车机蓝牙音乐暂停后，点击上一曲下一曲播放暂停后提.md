# SIR-7383 · 蓝牙音乐暂停后，点击上一曲/下一曲/播放/暂停提示"音频使用中，无法播放"
- **提交**：`672c6151` | 2026-09-04 | dufan | BTMusic | bugfix（cherry-pick 自 0a2c4b7d，原提交不在本仓库）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
车机蓝牙音乐暂停后，再点击上一曲/下一曲/播放/暂停，弹出"音频使用中，无法播放"toast，操作被拦截，音乐无法恢复播放。

## 根因分析
缺陷在 `application/BTMusic/.../manager/BtMusicModel.kt` 的 `isCanClick()`。该方法被 `setPlayAndPause()`/`setPrevious()`/`setNext()` 三个按键入口在调用 `mController.transportControls` 之前作为焦点闸门。追溯历史：`isCanClick()` 最早由 SIR-5767（提交 178ea894）引入，原始实现是对 `getCarFocusForZoneId(PRIMARY_AUDIO_ZONE)` 的返回列表直接 `forEach`，逐项判断 usage 是否属于 `AUDIO_VOLUME_GROUP_MEDIA`，命中才 `return true`；若列表为 null 或空，forEach 体一次都不执行，直接落到方法尾部的 `return false` 并弹 `R.string.audio_in_use_hint`。也就是说"查不到任何焦点持有者"被误判成"音频被别人占用"。而蓝牙音乐暂停后手机侧 often 会释放音频焦点，`getCarFocusForZoneId` 返回空恰是常态，于是"暂停后再操作"必现误拦截。正确语义应为：焦点列表为空 = 无人占用 = 可点击。本次 diff 实际只把该空判断从单行 `if (audioFocusInfos.isNullOrEmpty()) return true` 展开为带 `LogUtils.d(TAG, "isCanClick: isNullOrEmpty")` 日志的代码块——**语义修复在本分支已由先行提交 64965053（"代码优化"重构）落地**，本提交是从发布分支 cherry-pick 同一修复时，因主干已含该逻辑而仅残留日志部分，diff 与提交信息"修改判断逻辑"不完全一致，以 diff 实际为准。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
     private fun isCanClick(): Boolean{
         val audioFocusInfos =
             App.app?.getCarAudioManager()?.getCarFocusForZoneId(CarAudioManager.PRIMARY_AUDIO_ZONE)
-        if (audioFocusInfos.isNullOrEmpty()) return true
+        if (audioFocusInfos.isNullOrEmpty()) {
+            LogUtils.d(TAG, "isCanClick: isNullOrEmpty")
+            return true
+        }
         audioFocusInfos.forEach {
```
（语义等价修复的原始形态，见先行提交 64965053 将 `?.forEach` 链式写法拆为显式空判断 + `return true`）

## 为什么能修复
真正的修复语义是"`audioFocusInfos.isNullOrEmpty()` 时直接 `return true`"：把"无焦点持有者"从误判的"被占用"纠正为"空闲"，暂停态下按键不再被 `if (!isCanClick()) return` 拦截，也不弹错误 toast。本分支上该语义已生效，本次提交补充的日志让后续排查能直接确认走到了空焦点分支。隐患：判断粒度仍是"遍历焦点列表找 media 组"，若第三方应用持有 media 组焦点但实际未出声，仍会拦截播放，属既有策略限制而非本次引入。

## 复盘与经验
- `collection?.forEach { if (cond) return true }` + 方法尾 `return false` 是经典陷阱：列表为空时循环体不执行，"未命中"和"无数据"两种语义被混为一谈；先做空/空集合分支再遍历可同时修正语义和可读性。
- "暂停后无法恢复播放"类问题优先怀疑音频焦点查询结果的状态相关性——暂停态焦点常被释放，任何依赖"当前焦点必须是媒体组"的闸门都会在暂停态误伤。
- cherry-pick 跨分支合并时，若目标分支已包含等价修复，实际落地的 diff 可能只剩日志；复盘时要用 `git log -L` 追溯行级历史，找到语义修复的真正落点，不要只看提交信息。
