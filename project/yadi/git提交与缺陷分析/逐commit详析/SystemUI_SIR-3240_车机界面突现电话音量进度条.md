# SIR-3240 · 车机界面突现电话音量进度条（音量回调未判断 FLAG_SHOW_UI）

- **提交**：`9260c9ca` | 2026-07-23 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
使用中界面会突然弹出电话音量进度条（OSD），此时用户并没有调节音量。

## 根因分析
`CarAudioVolumeController.kt` 的 `onGroupVolumeChanged` 回调里，只要收到音量变化就弹出音量条，没有检查 `flags`。Android 音量回调的 flags 是语义载体：`FLAG_SHOW_UI` 才表示"应展示 UI"，系统内部调音（如通话建立/挂断时音频服务自动调整 `GROUP_CALL` 音量、应用静默 setVolume）同样会触发 `onGroupVolumeChanged`，但 flags 不带 `FLAG_SHOW_UI`——这些"系统自调"被当成用户操作，电话音量条突现。同时本类自己发起的 `setGroupVolume(groupId, targetVolume, 0)` 第三参传 0，即自己发出的调节也不声明要弹 UI，进一步混淆了语义。缺陷库 rc"未判断标志位"与 diff 完全一致。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt
@@ onGroupVolumeChanged 回调
             try {
                 val cam = carAudioManager ?: return
+                if ((flags and AudioManager.FLAG_SHOW_UI) == 0) {
+                    //补充判断flags，部分情况不需要弹出音量条
+                    LogUtils.i(TAG, "onGroupVolumeChanged: (flags and AudioManager.FLAG_SHOW_UI) == 0")
+                    return
+                }
                 val volume = cam.getGroupVolume(groupId)
@@ 三处用户路径的 setGroupVolume
-            cam.setGroupVolume(groupId, targetVolume, 0)
+            cam.setGroupVolume(groupId, targetVolume, AudioManager.FLAG_SHOW_UI)
```

## 为什么能修复
回调入口处 flags 无 `FLAG_SHOW_UI` 直接 return，系统内部调音不再触发 OSD，"突现电话音量条"消失；同时把用户主动调节（滚轮/按键路径）的 `setGroupVolume` flags 从 0 改为显式 `FLAG_SHOW_UI`，保证拦截后用户自己的调节仍会正常弹条——这两处是配套的：只加拦截不补 flags 会出现"音量条永远不弹"的新问题。回调内的 debounce 逻辑、进度计算不受影响。风险：若某些车载音量键路径依赖"回调弹条"而非主动路径弹条，需要确认全部入口都带上了 `FLAG_SHOW_UI`。

## 复盘与经验
- 系统 flags/参数是官方语义通道：`FLAG_SHOW_UI` 的存在就是为了让"改音量"与"显示音量条"解耦，弹出 UI 前先验 flags 是标准做法。
- 加一道拦截时必须同步排查自家发射端：自己 `setGroupVolume(..., 0)` 发出的回调也会被自己拦掉，拦截与补 flags 要成对提交。
- "突现/闪现"类 UI bug 常来自系统回调，把回调来源（用户 vs 系统）区分开是第一步。
