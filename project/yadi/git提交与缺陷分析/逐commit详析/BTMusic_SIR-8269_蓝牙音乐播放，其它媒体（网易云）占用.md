# SIR-8269 · 切回蓝牙音乐后播放进度条显示异常

- **提交**：`1d0f9bc9` | 2026-09-14 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
蓝牙音乐播放中被其它媒体应用（网易云）占用音频，切换回蓝牙音乐后，界面播放进度条位置不对（与真实播放进度不符）。

## 根因分析
`MainActivity` 对 `mBtMusicModel.mMusicProgress` 的观察回调里有一道"播放中才刷新"的闸门：仅当 `mMusicTotal > 0 && mIsPlay.value == true` 时才 `mBinding.playControl.setProgress(it)`。应用退后台/音频被其它应用占用期间进度仍在推进，但回到前台时 `mMusicProgress` LiveData 的值往往没有变化（不会重新发射），观察回调不执行；即使执行，若此刻 `mIsPlay` 为 false（暂停态/占用态）也会被闸门拦下——进度条停留在离开时的旧位置，缺陷库根因"未更新进度"准确。缺少"回到前台时主动按模型当前值强制刷一次"的路径。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`
```diff
         mBluetoothPlayerService.mBtMusicModel.mMusicProgress.observe(this) {
-            LogUtils.d(TAG, "progress->$it")
-            if ((mBluetoothPlayerService.mBtMusicModel.mMusicTotal.value?.toInt() ?: 0) > 0) {
-                if (mBluetoothPlayerService.mBtMusicModel.mIsPlay.value == true) {
-                    mBinding.playControl.setProgress(it)
-                }
-            } else {
-                mBinding.playControl.setTotal(0)
-                mBinding.playControl.setProgress(0)
-            }
+            setMusicProgress(it)
         }
@@
+    private fun setMusicProgress(time: Long, isForce: Boolean = false) {
+        LogUtils.d(TAG, "progress->$time")
+        if ((mBluetoothPlayerService.mBtMusicModel.mMusicTotal.value?.toInt() ?: 0) > 0) {
+            if (mBluetoothPlayerService.mBtMusicModel.mIsPlay.value == true || isForce) {
+                mBinding.playControl.setProgress(time)
+            }
+        } else {
+            mBinding.playControl.setTotal(0)
+            mBinding.playControl.setProgress(0)
+        }
+    }
@@
     override fun onResume() {
         super.onResume()
-        Log.i("Yadea_Trace", "BTMusic MainActivity_success")
+        Log.i("Yadea_Trace", "BTMusic MainActivity_success   ${mBluetoothPlayerService.mBtMusicModel.mMusicProgress.value}")
+        setMusicProgress(mBluetoothPlayerService.mBtMusicModel.mMusicProgress.value ?: 0, true)
```

## 为什么能修复
把进度刷新抽成 `setMusicProgress(time, isForce)`：观察回调走默认非强制路径（保持"暂停时不被周期进度打扰"的原语义）；`onResume` 用模型里最新的 `mMusicProgress.value` 以 `isForce = true` 强刷一次，绕过"播放中"闸门与 LiveData 不重发的问题，切回前台后进度条立即对齐真实进度。副作用小：暂停状态下回到前台也会按当前进度校正一次，属合理行为；进度值来自模型缓存，与服务的真实进度之间仍有常规延迟。

## 复盘与经验
- LiveData 观察只能兜住"值变化"，回到前台必须用 `onResume` 主动按当前模型值强刷 UI，二者配合才能覆盖"离开期间状态漂移"的场景。
- 在观察回调里叠加多个前置条件（total>0、isPlaying）会让"该刷的时候不刷"，闸门条件应只保护周期性更新，关键节点（前台恢复、切源）用显式 force 旁路。
- 重构观察回调时先抽方法再加重载参数，diff 清晰且两条刷新路径语义分明（本提交即是范例）。
