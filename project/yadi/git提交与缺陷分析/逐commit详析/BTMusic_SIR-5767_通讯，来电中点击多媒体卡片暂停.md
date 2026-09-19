# SIR-5767 · 通话中来电路点击多媒体卡片无 toast 提示
- **提交**：`178ea894` | 2026-08-31 | dufan | BTMusic | bugfix（单据状态"问题取消"，代码已落地，以 diff 为准）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 问题取消 · 域 主交互

## 问题
来电/通话中音频焦点被通话占用时，点击蓝牙音乐卡片的暂停/上一曲/下一曲，操作无响应也无任何提示，用户不知为何无效。

## 根因分析
`BtMusicModel` 的 `setPlayAndPause/setPrevious/setNext` 原先只有"控制器为空"和"无音源"两个拦截分支；通话占焦时请求媒体操作会被系统静默拒绝，应用层没有任何占焦检测与用户反馈。旧逻辑 `isCanChangeInfo()` 只针对在线音乐包名 `com.arcvideo.car.ncm.music` 做 UI 信息过滤，与"能否操作"无关。根因是缺少统一的"当前是否可操作媒体"判定入口（焦点策略：通话时协议栈/通话持有焦点，媒体请求失效），单据根因"焦点策略"即指此。

## 关键代码修改
改动文件：BtMusicModel.kt、values/strings.xml、values-en/strings.xml
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ setPlayAndPause / setPrevious / setNext 三处入口一致
         if (mController == null) { ... return }
+        if (!isCanClick()) return
         if (isNoMusicSource()) { ... return }
...
+    @SuppressLint("MissingPermission")
+    private fun isCanClick(): Boolean{
+        App.app?.getCarAudioManager()?.getCarFocusForZoneId(CarAudioManager.PRIMARY_AUDIO_ZONE)?.forEach {
+            val volumeGroupIdForUsage = App.app?.getCarAudioManager()
+                ?.getVolumeGroupIdForUsage(it.attributes.usage) ?: -1
+            if (volumeGroupIdForUsage == CarAudioManager.AUDIO_VOLUME_GROUP_MEDIA) {
+                return true
+            }
+        }
+        ThreadUtils.runOnUiThread {
+            App.app?.let {
+                ToastUtils.showCenterToast(it, it.getString(R.string.audio_in_use_hint))
+            }
+        }
+        return false
+    }
```
```diff
--- a/application/BTMusic/src/main/res/values/strings.xml
+    <string name="audio_in_use_hint">音频使用中，无法播放</string>
```

## 为什么能修复
新增 `isCanClick()` 作为三个操作入口的统一闸门：遍历主音频区焦点持有者，把焦点 usage 换算成音量组，若没有任何持有者属于媒体组（说明媒体焦点被通话等占用）则拦截操作并 toast"音频使用中，无法播放"，把静默失败变为可感知反馈；焦点确属媒体时直接放行。副作用：每次点击都要走一次 CarAudioManager 查询，代价轻微；单据最终标记"问题取消"（按"蓝牙音乐暂停，协议栈释放焦点"策略关闭），但本提交的提示逻辑已合入，两者不冲突——拦截+提示是更保守的用户体验兜底。

## 复盘与经验
- 车机多媒体操作必须显式处理音频焦点被占用场景，"系统会拒绝"不等于"用户能理解"，静默失败应一律转提示。
- 判定焦点可用性时按 volume group（AUDIO_VOLUME_GROUP_MEDIA）而非包名黑名单，包名硬编码（如旧的 ncm.music 判断）不可扩展。
- 单据状态（问题取消）与代码事实（已合入修复）可能不一致，复盘时以 diff 与测试结果为准并注明差异。
