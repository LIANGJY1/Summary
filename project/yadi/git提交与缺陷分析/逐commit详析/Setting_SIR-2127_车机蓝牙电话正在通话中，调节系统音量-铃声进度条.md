# SIR-2127 · 补注册音频焦点回调（上一提交的收尾）
- **提交**：`0fddc72c` | 2026-07-10 | dufan | Setting | bugfix（补丁）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（与 9e857725 同单）

## 问题
同 SIR-2127：蓝牙通话中调节铃声进度条仍会发出预览音。前一提交 `9e857725` 已写好 `mCarFocusCallback`（焦点变化时停播预览音），但回调从未注册，机制实际不生效。

## 根因分析
`SoundFragment` 中 `mCarFocusCallback` 已定义，但 onStart 生命周期里的回调注册块只注册了 `mCarExtBTVolumeCallback`、`mCarOutputDeviceCallback` 等，漏掉了 `registerCarFocusCallback(mCarFocusCallback)`；onStop 也无对应反注册。属于"上一提交只写了一半"的集成遗漏：状态监听机制三要素（定义、注册、反注册）缺了后两环。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt（+2/-1）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
@@ onStart 回调注册块
             registerCarOutputDeviceCallback(
                 mCarOutputDeviceCallback
             )
+            registerCarFocusCallback(mCarFocusCallback)
@@ onStop 反注册块
             unregisterExtBTVolumeCallback(mCarExtBTVolumeCallback)
             unregisterCarOutputDeviceCallback(mCarOutputDeviceCallback)
+            unregisterCarFocusCallback(mCarFocusCallback)
```

## 为什么能修复
注册后 `CarAudioManager.CarFocusCallback.onCarFocusChanged` 才会真正回调，`mViewModel.setStopPlay(focusHolders)` 得以在通话焦点建立时停掉同组预览音，前一提交的停播逻辑从此生效；onStop 反注册对齐，避免 Fragment 销毁后仍持有回调引发泄漏或重复回调。该修复与前提交构成完整闭环，无新增副作用。

## 复盘与经验
- **监听器三要素同提交交付**：定义、注册、反注册缺一不可，code review 时对每个新增 callback 应机械核对这三处。
- **"改了但没生效"优先查注册链路**：修复后问题依旧时，第一时间验证回调是否真的被触发（加日志在 onCarFocusChanged），而不是继续改处理逻辑。
- **同单两个提交应 squash 或显式标注依赖**：中间态提交进入分支后，若只 cherry-pick 第一个，功能静默失效。
